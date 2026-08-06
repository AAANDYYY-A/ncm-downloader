package com.operit.ncmdownloader;

import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

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

/**
 * 网易云 API 封装：单曲/歌单信息、播放链接、下载、分享链接解析。
 * 全部走 HTTPS 公开接口，无需 root / Xposed。
 */
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

        public String displayName() {
            return (artist == null || artist.length() == 0 ? "未知歌手" : artist)
                    + " - " + (name == null || name.length() == 0 ? String.valueOf(id) : name);
        }
    }

    /**
     * 解析用户输入（分享链接/纯数字/短链），返回 {type, id}
     * type: "song" / "playlist" / "raw"(纯数字无法判断类型)
     */
    public static String[] resolveLink(String input) {
        if (input == null) return null;
        input = input.trim();
        if (input.length() == 0) return null;
        if (input.matches("\\d+")) {
            return new String[]{"raw", input};
        }
        String url = input;
        // 网易云短链 163cn.tv（兼容误写 63cn.tv）
        Matcher sm = Pattern.compile("https?://(?:163|63)cn\\.tv/[A-Za-z0-9]+").matcher(input);
        if (sm.find()) {
            String loc = followRedirect(sm.group());
            if (loc != null) url = loc;
        }
        String type;
        if (url.contains("/playlist") || url.contains("playlist?")) {
            type = "playlist";
        } else if (url.contains("/song") || url.contains("song?id")) {
            type = "song";
        } else {
            type = "raw";
        }
        String id = null;
        Matcher idm = Pattern.compile("(?:song|playlist)[^\\d]*id=(\\d+)").matcher(url);
        if (idm.find()) {
            id = idm.group(1);
        } else {
            idm = Pattern.compile("id=(\\d+)").matcher(url);
            if (idm.find()) id = idm.group(1);
        }
        return new String[]{type, id};
    }

    /** 短链跳转解析（302/301/303/307 + HTML回退） */
    private static String followRedirect(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
            int code;
            try {
                code = conn.getResponseCode();
            } catch (Throwable t) {
                return null;
            }
            String loc = conn.getHeaderField("Location");
            if (loc != null && loc.length() > 0) {
                if (!loc.startsWith("http")) {
                    int slash = url.indexOf('/', 8);
                    String base = slash > 0 ? url.substring(0, slash) : url;
                    loc = base + (loc.startsWith("/") ? "" : "/") + loc;
                }
                if (loc.contains("163cn.tv") || loc.contains("63cn.tv")) {
                    return followRedirect(loc);
                }
                return loc;
            }
            if (code == 200) {
                // 部分短链返回HTML，尝试从 body 提取跳转目标
                InputStream in = conn.getInputStream();
                byte[] buf = new byte[8192];
                int n = in.read(buf);
                in.close();
                if (n > 0) {
                    String body = new String(buf, 0, n, "UTF-8");
                    String target = extractFromBody(body);
                    if (target != null) return target;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    /** 从短链HTML body中提取跳转目标 */
    private static String extractFromBody(String body) {
        if (body == null) return null;
        String[][] pats = {
                {"song", "song\\?id=(\\d+)"},
                {"playlist", "playlist\\?id=(\\d+)"},
                {"song", "\\\"id\\\":(\\d+)"},
                {"raw", "id=(\\d+)"}
        };
        for (String[] p : pats) {
            Matcher m = Pattern.compile(p[1]).matcher(body);
            if (m.find()) {
                return "https://music.163.com/" + p[0] + "?id=" + m.group(1);
            }
        }
        return null;
    }

    /** 单曲详情 */
    public static Song fetchSongInfo(String id, String cookie) throws Exception {
        String api = "https://music.163.com/api/song/detail?ids=[" + id + "]";
        String json = httpGet(api, cookie);
        JSONObject o = new JSONObject(json);
        JSONArray arr = o.optJSONArray("songs");
        if (arr == null || arr.length() == 0) return null;
        return parseSongJson(arr.getJSONObject(0));
    }

    /** 歌单全部歌曲 */
    public static List<Song> fetchPlaylist(String id, String cookie) throws Exception {
        String api = "https://music.163.com/api/playlist/detail?id=" + id;
        String json = httpGet(api, cookie);
        JSONObject o = new JSONObject(json);
        JSONObject result = o.optJSONObject("result");
        List<Song> list = new ArrayList<Song>();
        if (result == null) return list;
        JSONArray tr = result.optJSONArray("tracks");
        if (tr != null) {
            for (int i = 0; i < tr.length(); i++) {
                Song s = parseSongJson(tr.optJSONObject(i));
                if (s != null) list.add(s);
            }
        }
        return list;
    }

    private static Song parseSongJson(JSONObject o) {
        if (o == null) return null;
        try {
            long id = o.optLong("id");
            String name = o.optString("name");
            JSONArray artists = o.optJSONArray("artists");
            String artist = "";
            if (artists != null && artists.length() > 0) {
                JSONObject a0 = artists.optJSONObject(0);
                if (a0 != null) artist = a0.optString("name");
            }
            String album = "";
            JSONObject alb = o.optJSONObject("album");
            if (alb != null) album = alb.optString("name");
            int fee = o.optInt("fee");
            return new Song(id, name, artist, album, fee);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 获取歌曲播放/下载链接（多API通道，总有一个能出URL）
     * 1. /api/song/enhance/player/url —— 播放接口（带Cookie可下VIP）
     * 2. /song/media/outer/url?id=x.mp3 —— 最老外链接口（免费歌曲兜底，无需Cookie）
     */
    public static String fetchPlayUrl(String id, int br, String cookie) throws Exception {
        String[] apis = {
                "https://music.163.com/api/song/enhance/player/url?ids=[" + id + "]&br=" + br,
                "https://music.163.com/song/media/outer/url?id=" + id + ".mp3"
        };
        for (String api : apis) {
            try {
                String u = fetchUrlFromApi(api, cookie);
                if (u != null && u.length() > 0) {
                    return u;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 老外链接口单独取URL（免费歌曲，302->mp3） */
    public static String fetchOuterUrl(String id) {
        try {
            return fetchUrlFromApi("https://music.163.com/song/media/outer/url?id=" + id + ".mp3", null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 下载（多轮新URL重试 + http/https变体 + 老外链接口兜底） */
    public static Uri downloadWithFallback(Context ctx, String id, int br, String cookie, String fname) throws Exception {
        Exception last = null;
        // 两轮：每轮用全新URL（CDN链接有时效，过期会403）
        for (int i = 0; i < 2; i++) {
            String url = null;
            try {
                url = fetchPlayUrl(id, br, cookie);
            } catch (Throwable ignored) {
            }
            if (url == null) {
                continue;
            }
            try {
                return downloadToStorage(ctx, url, fname);
            } catch (Exception e) {
                last = e;
            }
        }
        // 最老外链接口兜底（免费歌曲往往可下）
        String outer = fetchOuterUrl(id);
        if (outer != null) {
            try {
                return downloadToStorage(ctx, outer, fname);
            } catch (Exception ignored) {
            }
        }
        throw last != null ? last : new Exception("无播放链接(需VIP/付费，请先登录)");
    }

    private static String fetchUrlFromApi(String api, String cookie) throws Exception {
        if (api.contains("outer/url")) {
            // 老外链接口返回302 -> Location指向mp3
            HttpURLConnection conn = (HttpURLConnection) new URL(api).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(15000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
            conn.setRequestProperty("Referer", "https://music.163.com/");
            int code = conn.getResponseCode();
            String loc = conn.getHeaderField("Location");
            conn.disconnect();
            if (loc != null && (code == 301 || code == 302 || code == 303 || code == 307)) {
                if (!loc.startsWith("http")) {
                    loc = "https://music.163.com" + (loc.startsWith("/") ? "" : "/") + loc;
                }
                return loc;
            }
            return null;
        }
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

    /** 查询已下载到 Music/NCM自动下载/ 的歌曲，返回 List<String[]>{uri, displayName} */
    public static List<String[]> queryDownloaded(Context context) {
        List<String[]> list = new ArrayList<String[]>();
        try {
            android.database.Cursor c = context.getContentResolver().query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.MediaColumns._ID,
                            MediaStore.MediaColumns.DISPLAY_NAME,
                            MediaStore.MediaColumns.RELATIVE_PATH,
                            MediaStore.MediaColumns.DURATION},
                    MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?",
                    new String[]{"%NCM自动下载%"},
                    MediaStore.MediaColumns.DATE_ADDED + " DESC");
            if (c != null) {
                while (c.moveToNext()) {
                    long id = c.getLong(0);
                    String name = c.getString(1);
                    Uri uri = android.content.ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
                    list.add(new String[]{uri.toString(), name});
                }
                c.close();
            }
        } catch (Throwable ignored) {
        }
        return list;
    }

    /** 下载音频流到 Music/NCM自动下载/，返回 Uri（可播放）；失败自动重试一次 + 系统DownloadManager兜底 */
    public static Uri downloadToStorage(Context context, String url, String fname) throws Exception {
        Exception lastErr = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return downloadOnce(context, url, fname);
            } catch (Exception e) {
                lastErr = e;
                try {
                    Thread.sleep(800);
                } catch (Throwable ignored) {
                }
            }
        }
        // HttpURLConnection 被CDN拒绝(403)时，改用系统DownloadManager（系统网络栈）
        try {
            return downloadViaDownloadManager(context, url, fname);
        } catch (Throwable t) {
            // 忽略，抛原始错误
        }
        throw lastErr != null ? lastErr : new Exception("下载失败");
    }

    /** 系统DownloadManager下载（保存到 Music/NCM自动下载/，等待完成） */
    private static Uri downloadViaDownloadManager(Context context, String url, String fname) throws Exception {
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
        req.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI | DownloadManager.Request.NETWORK_MOBILE);
        req.setTitle(fname);
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN);
        req.addRequestHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
        req.addRequestHeader("Referer", "https://music.163.com/");
        String dir = Environment.DIRECTORY_MUSIC + "/NCM自动下载";
        req.setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, "NCM自动下载/" + fname);
        long id = dm.enqueue(req);
        // 轮询等待完成（最多5分钟）
        long deadline = System.currentTimeMillis() + 300000;
        DownloadManager.Query q = new DownloadManager.Query().setFilterById(id);
        while (System.currentTimeMillis() < deadline) {
            Cursor c = null;
            try {
                c = dm.query(q);
                if (c != null && c.moveToFirst()) {
                    int status = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS));
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        c.close();
                        File f = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "NCM自动下载/" + fname);
                        return Uri.fromFile(f);
                    } else if (status == DownloadManager.STATUS_FAILED) {
                        int reason = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_REASON));
                        c.close();
                        throw new Exception("下载失败 reason=" + reason);
                    }
                }
            } finally {
                if (c != null) c.close();
            }
            Thread.sleep(1200);
        }
        throw new Exception("下载超时");
    }

    private static Uri downloadOnce(Context context, String url, String fname) throws Exception {
        // 网易云CDN链接为http明文（App端即http下载）。优先原样http，失败再试https变体
        try {
            return downloadRaw(context, url, fname);
        } catch (Exception e) {
            if (url != null && url.startsWith("http://")) {
                String httpsUrl = "https://" + url.substring(7);
                return downloadRaw(context, httpsUrl, fname);
            }
            throw e;
        }
    }

    private static Uri downloadRaw(Context context, String url, String fname) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(120000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
        conn.setRequestProperty("Referer", "https://music.163.com/");
        conn.setRequestProperty("Accept", "*/*");
        int code = conn.getResponseCode();
        if (code != 200) {
            String errBody = "";
            try {
                InputStream es = conn.getErrorStream();
                if (es != null) {
                    byte[] b = new byte[256];
                    int n = es.read(b);
                    if (n > 0) errBody = new String(b, 0, n, "UTF-8");
                    es.close();
                }
            } catch (Throwable ignored) {
            }
            conn.disconnect();
            throw new Exception("HTTP " + code + " " + errBody.trim());
        }
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
            return uri;
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
            return Uri.fromFile(f);
        }
    }

    public static String sanitize(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\\\/:*?\"<>|\\n\\r]", "_").trim();
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
}