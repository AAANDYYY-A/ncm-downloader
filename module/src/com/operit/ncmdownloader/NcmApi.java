package com.operit.ncmdownloader;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 网易云 API 封装：播放链接、歌单、下载、链接解析 */
public class NcmApi {

    public static final int BR_STANDARD = 128000;
    public static final int BR_HIGH = 320000;
    public static final int BR_LOSSLESS = 999000;

    public static class Song {
        public long id;
        public String name;
        public String artist;
        public String album;
        public int fee; // 0/8=免费, 1=VIP, 4=付费

        public Song(long id, String name, String artist, String album, int fee) {
            this.id = id;
            this.name = name;
            this.artist = artist;
            this.album = album;
            this.fee = fee;
        }

        public boolean isFree() {
            return fee == 0 || fee == 8;
        }
    }

    /** 解析用户输入：纯数字ID 或 分享链接，返回数字ID */
    public static String resolveId(String input) {
        if (input == null) return null;
        input = input.trim();
        if (input.length() == 0) return null;
        if (input.matches("\\d+")) return input;

        Matcher m = Pattern.compile("https?://163cn\\.tv/[A-Za-z0-9]+").matcher(input);
        if (m.find()) {
            String loc = followRedirect(m.group());
            if (loc != null) {
                Matcher idm = Pattern.compile("id=(\\d+)").matcher(loc);
                if (idm.find()) return idm.group(1);
            }
        }
        Matcher idm = Pattern.compile("id=(\\d+)").matcher(input);
        if (idm.find()) return idm.group(1);
        return null;
    }

    private static String followRedirect(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(15000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = conn.getHeaderField("Location");
                if (loc != null) {
                    // 可能多次跳转
                    if (loc.contains("163cn.tv")) return followRedirect(loc);
                    return loc;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    /** 获取歌曲播放链接（不同br），带登录Cookie可下VIP歌曲 */
    public static String fetchPlayUrl(String id, int br, String cookie) throws Exception {
        String api = "https://music.163.com/api/song/enhance/player/url?ids=[" + id + "]&br=" + br;
        String json = httpGet(api, cookie);
        int idx = json.indexOf("\"url\":\"");
        if (idx < 0) return null;
        int start = idx + 7;
        int end = json.indexOf('"', start);
        if (end < 0) return null;
        String url = json.substring(start, end);
        url = url.replace("\\/", "/").replace("\\u0026", "&");
        return url.length() > 0 ? url : null;
    }

    /** 从网易云 cm_cookie_storage.xml 文本提取 MUSIC_U 登录Cookie */
    public static String extractMusicUFromXml(String xml) {
        if (xml == null) return null;
        int idx = xml.indexOf("MUSIC_U_music.163.com_/");
        if (idx < 0) return null;
        String seg = xml.substring(idx);
        String key = "\u0026quot;value\u0026quot;:\u0026quot;";
        int v = seg.indexOf(key);
        if (v < 0) return null;
        int start = v + key.length();
        int end = seg.indexOf("\u0026quot;", start);
        if (end < 0) return null;
        String val = seg.substring(start, end);
        return val.length() > 0 ? val : null;
    }

    /** 读取网易云Cookie文件并提取MUSIC_U */
    public static String readMusicUFromFile(String path) {
        try {
            java.io.File f = new java.io.File(path);
            if (!f.exists()) return null;
            byte[] data = new byte[(int) f.length()];
            java.io.FileInputStream fis = new java.io.FileInputStream(f);
            fis.read(data);
            fis.close();
            return extractMusicUFromXml(new String(data, "UTF-8"));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 获取歌单全部歌曲 */
    public static List<Song> fetchPlaylist(String id) throws Exception {
        String api = "https://music.163.com/api/playlist/detail?id=" + id;
        String json = httpGet(api);
        List<Song> list = new ArrayList<Song>();
        int ti = json.indexOf("\"tracks\":");
        if (ti < 0) return list;
        String tracks = json.substring(ti + 9);
        parseTracks(tracks, list);
        return list;
    }

    private static void parseTracks(String tracks, List<Song> out) {
        // 匹配每首歌的起始 {id:xxx,"name":"yyy"
        Pattern songHead = Pattern.compile("\\{\"id\":(\\d+),\"name\":\"((?:[^\"\\\\]|\\\\.)*)\"");
        Pattern artistP = Pattern.compile("\"artists\":\\[\\{\"name\":\"((?:[^\"\\\\]|\\\\.)*)\"");
        Pattern albumP = Pattern.compile("\"album\":\\{\"name\":\"((?:[^\"\\\\]|\\\\.)*)\"");
        Pattern feeP = Pattern.compile("\"fee\":(\\d+)");
        Matcher m = songHead.matcher(tracks);
        int lastEnd = 0;
        while (m.find()) {
            long id = Long.parseLong(m.group(1));
            String name = unescape(m.group(2));
            String artist = "";
            String album = "";
            // 在该歌曲对象范围内提取artist/album（下一个songHead之前）
            int segStart = m.start();
            int segEnd = tracks.length();
            Matcher nm = songHead.matcher(tracks);
            if (nm.find(segStart + 1)) {
                segEnd = nm.start();
            }
            String seg = tracks.substring(segStart, segEnd);
            Matcher am = artistP.matcher(seg);
            if (am.find()) artist = unescape(am.group(1));
            Matcher alm = albumP.matcher(seg);
            if (alm.find()) album = unescape(alm.group(1));
            int fee = 0;
            Matcher fm = feeP.matcher(seg);
            if (fm.find()) fee = Integer.parseInt(fm.group(1));
            out.add(new Song(id, name, artist, album, fee));
            lastEnd = m.end();
        }
    }

    private static String unescape(String s) {
        if (s == null) return "";
        return s.replace("\\/", "/").replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static String httpGet(String url) throws Exception {
        return httpGet(url, null);
    }

    private static String httpGet(String url, String cookie) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
        conn.setRequestProperty("Referer", "https://music.163.com/");
        if (cookie != null && cookie.length() > 0) {
            conn.setRequestProperty("Cookie", cookie);
        }
        InputStream in = conn.getInputStream();
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            sb.append(new String(buf, 0, n, "UTF-8"));
        }
        in.close();
        conn.disconnect();
        return sb.toString();
    }

    /** 下载音频流到 Music/NCM自动下载/ */
    public static void downloadToStorage(Context context, String url, String fname) throws Exception {
        // 网易云播放链接为http明文，Android9+默认禁止明文；CDN支持https，强制升级
        if (url != null && url.startsWith("http://")) {
            url = "https://" + url.substring(7);
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(120000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        InputStream in = conn.getInputStream();
        byte[] buf = new byte[8192];
        int n;
        if (Build.VERSION.SDK_INT >= 29) {
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
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            os.close();
            in.close();
            conn.disconnect();
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "NCM自动下载");
            if (!dir.exists() && !dir.mkdirs()) {
                in.close();
                conn.disconnect();
                throw new Exception("目录创建失败");
            }
            File f = new File(dir, fname);
            FileOutputStream fos = new FileOutputStream(f);
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            fos.close();
            in.close();
            conn.disconnect();
        }
    }

    public static String sanitize(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\\\/:*?\"<>|\\n\\r]", "_").trim();
    }
}