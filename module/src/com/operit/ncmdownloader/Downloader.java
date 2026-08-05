package com.operit.ncmdownloader;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.XposedBridge;

/**
 * 下载器：通过网易云API获取播放链接并保存到 Music/NCM自动下载/
 */
public class Downloader {

    private static final String TAG = "NcmDownloader";
    private static final String API_URL = "https://music.163.com/api/song/enhance/player/url?ids=[%s]&br=320000";

    private static final Downloader INSTANCE = new Downloader();

    public static Downloader getInstance() {
        return INSTANCE;
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Set<String> downloading = new HashSet<String>();
    private final Set<String> downloaded = new HashSet<String>();
    private String lastId = "";

    public void onSongDetected(final Context context, final String mediaId, final String title, final String artist) {
        final String id = extractId(mediaId);
        if (id == null || id.length() == 0) {
            XposedBridge.log(TAG + " 无法从mediaId提取歌曲ID: " + mediaId);
            return;
        }
        if (id.equals(lastId)) {
            return; // 同一首歌不重复处理
        }
        lastId = id;

        if (downloaded.contains(id) || isFileExists(context, artist, title)) {
            XposedBridge.log(TAG + " 歌曲已下载过: " + artist + " - " + title);
            return;
        }

        synchronized (downloading) {
            if (downloading.contains(id)) {
                return;
            }
            downloading.add(id);
        }

        final String fname = sanitize(artist) + " - " + sanitize(title) + ".mp3";
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    download(context, id, fname);
                    synchronized (downloading) {
                        downloading.remove(id);
                    }
                    downloaded.add(id);
                    toast(context, "已下载: " + fname);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " 下载失败 id=" + id + " err=" + t);
                    synchronized (downloading) {
                        downloading.remove(id);
                    }
                }
            }
        });
    }

    private String extractId(String mediaId) {
        if (mediaId == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mediaId.length(); i++) {
            char c = mediaId.charAt(i);
            if (Character.isDigit(c)) {
                sb.append(c);
            } else if (sb.length() > 0) {
                break;
            }
        }
        String s = sb.toString();
        return s.length() > 0 ? s : null;
    }

    private String sanitize(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("[\\\\/:*?\"<>|\\n\\r]", "_").trim();
    }

    private boolean isFileExists(Context context, String artist, String title) {
        String fname = sanitize(artist) + " - " + sanitize(title) + ".mp3";
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                String selection = MediaStore.MediaColumns.DISPLAY_NAME + "=?";
                String[] args = new String[]{fname};
                android.database.Cursor c = context.getContentResolver().query(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        new String[]{MediaStore.MediaColumns._ID}, selection, args, null);
                if (c != null) {
                    boolean exists = c.getCount() > 0;
                    c.close();
                    return exists;
                }
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "NCM自动下载");
                return new File(dir, fname).exists();
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " 检查文件存在失败: " + t);
        }
        return false;
    }

    private void download(Context context, String id, String fname) throws Exception {
        // 1. 获取真实播放链接
        String url = fetchUrl(id);
        if (url == null) {
            throw new Exception("未获取到播放链接 id=" + id);
        }
        // 网易云播放链接为http明文，Android9+默认禁止明文；CDN支持https，强制升级
        if (url.startsWith("http://")) {
            url = "https://" + url.substring(7);
        }
        XposedBridge.log(TAG + " 获取到播放链接: " + url);

        // 2. 下载音频流
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(120000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
        conn.setRequestProperty("Referer", "https://music.163.com/");
        InputStream in = conn.getInputStream();

        byte[] buf = new byte[8192];
        int n;

        if (Build.VERSION.SDK_INT >= 29) {
            // Android 10+ 用 MediaStore 写入公共目录（无需存储权限）
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, fname);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg");
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/NCM自动下载");
            Uri uri = context.getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) {
                in.close();
                conn.disconnect();
                throw new Exception("MediaStore 插入失败");
            }
            OutputStream os = context.getContentResolver().openOutputStream(uri);
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
            os.close();
            in.close();
            conn.disconnect();
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "NCM自动下载");
            if (!dir.exists() && !dir.mkdirs()) {
                in.close();
                conn.disconnect();
                throw new Exception("创建目录失败");
            }
            File f = new File(dir, fname);
            FileOutputStream fos = new FileOutputStream(f);
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }
            fos.close();
            in.close();
            conn.disconnect();
        }
        XposedBridge.log(TAG + " 下载完成: " + fname);
    }

    private String fetchUrl(String id) {
        HttpURLConnection conn = null;
        try {
            String api = String.format(API_URL, id);
            conn = (HttpURLConnection) new URL(api).openConnection();
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
            conn.setRequestProperty("Referer", "https://music.163.com/");
            // 携带网易云登录Cookie（VIP歌曲需要）
            try {
                String mu = NcmApi.readMusicUFromFile("/data/data/com.netease.cloudmusic/shared_prefs/cm_cookie_storage.xml");
                if (mu != null && mu.length() > 0) {
                    conn.setRequestProperty("Cookie", "MUSIC_U=" + mu);
                }
            } catch (Throwable ignored) {
            }
            InputStream in = conn.getInputStream();
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                sb.append(new String(buf, 0, n, "UTF-8"));
            }
            in.close();
            String json = sb.toString();
            // 解析 {"data":[{"url":"..."}]}
            int idx = json.indexOf("\"url\":\"");
            if (idx < 0) {
                return null;
            }
            int start = idx + 7;
            int end = json.indexOf('"', start);
            if (end < 0) {
                return null;
            }
            String url = json.substring(start, end);
            // JSON 转义还原
            url = url.replace("\\/", "/").replace("\\u0026", "&");
            return url.length() > 0 ? url : null;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " 获取播放链接失败: " + t);
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private void toast(final Context context, final String msg) {
        try {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    try {
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show();
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }
}
