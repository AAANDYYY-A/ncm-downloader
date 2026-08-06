package com.operit.ncmdownloader;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.CancellationSignal;

/** 跨进程数据通道：模块Hook(网易云进程)写入当前歌曲+登录Cookie，UI进程读取；
 *  同时承载设置同步：UI进程写入工作模式/自动下载开关，模块进程读取。 */
public class CurrentProvider extends ContentProvider {

    public static final String AUTHORITY = "com.operit.ncmdownloader.provider";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY + "/current");
    public static final Uri SETTINGS_URI = Uri.parse("content://" + AUTHORITY + "/settings");

    private static volatile String songId = "";
    private static volatile String songTitle = "";
    private static volatile String songArtist = "";
    private static volatile String musicU = "";

    // 设置（UI进程写入，模块进程读取）
    private static volatile String mode = "auto";      // auto=自动(root+手动) | manual=仅手动
    private static volatile boolean autoDownload = true; // 切歌自动下载
    private static volatile int br = 320000;           // 默认音质

    @Override
    public boolean onCreate() {
        return true;
    }

    private static final String PREF = "ncm_pref";
    private static final String KEY_MODE = "mode";
    private static final String KEY_AUTODL = "auto_download";
    private static final String KEY_BR = "br";

    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return query(uri, projection, selection, selectionArgs, sortOrder, null);
    }

    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder, CancellationSignal cancellationSignal) {
        String p = uri.getPath();
        if (p != null && p.startsWith("/settings")) {
            // 实时从磁盘读取（跨进程安全：Provider 运行在 App 进程，模块跨进程 query 走 Binder）
            String m = mode;
            boolean ad = autoDownload;
            int b = br;
            try {
                android.content.SharedPreferences sp = getContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
                m = sp.getString(KEY_MODE, mode);
                ad = sp.getBoolean(KEY_AUTODL, autoDownload);
                b = sp.getInt(KEY_BR, br);
            } catch (Throwable ignored) {
            }
            MatrixCursor c = new MatrixCursor(new String[]{"mode", "autoDownload", "br"});
            c.addRow(new Object[]{m, ad ? 1 : 0, b});
            return c;
        }
        MatrixCursor c = new MatrixCursor(new String[]{"id", "title", "artist", "musicU"});
        c.addRow(new Object[]{songId, songTitle, songArtist, musicU});
        return c;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/current";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        if (values != null) {
            if (uri.getPath() != null && uri.getPath().startsWith("/settings")) {
                android.content.SharedPreferences.Editor ed = null;
                try {
                    ed = getContext().getSharedPreferences(PREF, Context.MODE_PRIVATE).edit();
                } catch (Throwable ignored) {
                }
                if (values.containsKey("mode")) {
                    mode = values.getAsString("mode");
                    if (ed != null) ed.putString(KEY_MODE, mode);
                }
                if (values.containsKey("autoDownload")) {
                    autoDownload = values.getAsInteger("autoDownload") == 1;
                    if (ed != null) ed.putBoolean(KEY_AUTODL, autoDownload);
                }
                if (values.containsKey("br")) {
                    br = values.getAsInteger("br");
                    if (ed != null) ed.putInt(KEY_BR, br);
                }
                if (ed != null) {
                    try {
                        ed.apply();
                    } catch (Throwable ignored) {
                    }
                }
            } else {
                if (values.containsKey("id")) songId = values.getAsString("id");
                if (values.containsKey("title")) songTitle = values.getAsString("title");
                if (values.containsKey("artist")) songArtist = values.getAsString("artist");
                if (values.containsKey("musicU")) musicU = values.getAsString("musicU");
            }
            try {
                getContext().getContentResolver().notifyChange(uri, null);
            } catch (Throwable ignored) {
            }
        }
        return uri;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        insert(uri, values);
        return 1;
    }
}