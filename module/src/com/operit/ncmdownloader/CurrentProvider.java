package com.operit.ncmdownloader;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/** 跨进程数据通道：模块Hook(网易云进程)写入当前歌曲+登录Cookie，UI进程读取 */
public class CurrentProvider extends ContentProvider {

    public static final String AUTHORITY = "com.operit.ncmdownloader.provider";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY + "/current");

    private static volatile String songId = "";
    private static volatile String songTitle = "";
    private static volatile String songArtist = "";
    private static volatile String musicU = "";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
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
            if (values.containsKey("id")) songId = values.getAsString("id");
            if (values.containsKey("title")) songTitle = values.getAsString("title");
            if (values.containsKey("artist")) songArtist = values.getAsString("artist");
            if (values.containsKey("musicU")) musicU = values.getAsString("musicU");
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