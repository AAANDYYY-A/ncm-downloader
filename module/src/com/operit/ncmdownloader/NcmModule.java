package com.operit.ncmdownloader;

import android.app.Activity;
import android.app.AppOpsManager;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.os.Bundle;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 网易云音乐自动下载模块
 * 原理：
 *  1. Hook MediaSession.setMetadata / MediaMetadata.Builder.build 拦截当前播放歌曲
 *  2. 歌曲信息写入 CurrentProvider（跨进程），UI 进程实时读取显示
 *  3. 悬浮窗启动走 Activity.onCreate（主线程直接调用，不依赖线程调度）
 *  4. 自动下载受设置页"自动下载"开关控制；工作模式=仅手动时禁用模块自动功能
 */
public class NcmModule implements IXposedHookLoadPackage {

    private static final String TAG = "NcmDownloader";
    private static final String TARGET = "com.netease.cloudmusic";
    private static volatile Context appContext = null;
    private static volatile boolean hooked = false;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET.equals(lpparam.packageName)) {
            return;
        }
        String process = lpparam.processName == null ? "" : lpparam.processName;
        if (!TARGET.equals(process)) {
            return;
        }
        XposedBridge.log(TAG + " 模块已加载 process=" + process);

        // 早期获取 Application Context（attach 参数可能是 ContextImpl，取其 Application）
        XposedHelpers.findAndHookMethod(android.app.Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                try {
                    Context base = (Context) param.args[0];
                    if (base instanceof android.app.Application) {
                        appContext = base;
                    } else {
                        Context app = base.getApplicationContext();
                        appContext = app != null ? app : base;
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " attach取Context失败: " + t);
                }
                installHooks();
            }
        });

        // 主路径：Activity.onCreate 时初始化并启动悬浮窗（hook回调本身在主线程）
        XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                try {
                    if (appContext == null && param.thisObject instanceof Activity) {
                        appContext = ((Activity) param.thisObject).getApplicationContext();
                    }
                    installHooks();
                    // 直接在主线程启动悬浮窗（当前就是主线程）
                    try {
                        FloatWindow.show((Activity) param.thisObject);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " 悬浮窗启动失败: " + t);
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " onCreate调度失败: " + t);
                }
            }
        });
    }

    private synchronized void installHooks() {
        if (hooked || appContext == null) {
            return;
        }
        try {
            XposedBridge.log(TAG + " 安装 Hook");
            hookAppOps();
            // 渠道1：MediaSession.setMetadata（主播放通道）
            XposedHelpers.findAndHookMethod(MediaSession.class, "setMetadata", MediaMetadata.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    handleMetadata((MediaMetadata) param.args[0]);
                }
            });
            // 渠道2：MediaMetadata.Builder.build()（兼容 MediaSessionCompat 等间接路径）
            try {
                XposedHelpers.findAndHookMethod(MediaMetadata.Builder.class, "build", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        handleMetadata((MediaMetadata) param.getResult());
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log(TAG + " Builder.build hook失败(可选): " + t);
            }
            hooked = true;
            XposedBridge.log(TAG + " Hook 安装完成");

            // 读取网易云登录Cookie写入Provider，供UI下载VIP歌曲使用
            try {
                String mu = NcmApi.readMusicUFromFile("/data/data/com.netease.cloudmusic/shared_prefs/cm_cookie_storage.xml");
                if (mu != null && mu.length() > 0) {
                    ContentValues cv = new ContentValues();
                    cv.put("musicU", mu);
                    appContext.getContentResolver().update(CurrentProvider.URI, cv, null, null);
                    XposedBridge.log(TAG + " 已同步登录Cookie MUSIC_U len=" + mu.length());
                } else {
                    XposedBridge.log(TAG + " 未找到MUSIC_U（网易云未登录）");
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + " 读取Cookie失败 " + t);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " installHooks失败 " + t);
        }
    }

    private void handleMetadata(MediaMetadata md) {
        try {
            if (md == null) {
                return;
            }
            String id = md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
            if (id == null || id.length() == 0) {
                return;
            }
            String pureId = extractDigits(id);
            if (pureId.length() == 0) {
                return;
            }
            String title = md.getString(MediaMetadata.METADATA_KEY_TITLE);
            String artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
            XposedBridge.log(TAG + " 歌曲变化 id=" + pureId + " | " + artist + " - " + title);

            // 写入跨进程 Provider 供 UI 显示
            try {
                ContentValues cv = new ContentValues();
                cv.put("id", pureId);
                if (title != null) cv.put("title", title);
                if (artist != null) cv.put("artist", artist);
                appContext.getContentResolver().update(CurrentProvider.URI, cv, null, null);
            } catch (Throwable t) {
                XposedBridge.log(TAG + " 写入Provider失败 " + t);
            }

            // 更新悬浮窗
            FloatWindow.updateSong(pureId, title, artist);

            // 自动下载（受设置开关控制）
            if (title != null && artist != null && isAutoDownloadEnabled()) {
                Downloader.getInstance().onSongDetected(appContext, pureId, title, artist);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " handleMetadata err " + t);
        }
    }

    /** 读取设置：工作模式 + 自动下载开关 + 默认音质 */
    private static boolean isAutoDownloadEnabled() {
        try {
            if (appContext == null) {
                return true;
            }
            Cursor c = appContext.getContentResolver().query(CurrentProvider.SETTINGS_URI, null, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        String mode = c.getString(0);
                        boolean auto = c.getInt(1) == 1;
                        // 仅手动模式：禁用模块自动功能
                        if ("manual".equals(mode)) {
                            return false;
                        }
                        return auto;
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " 读取设置失败: " + t);
        }
        return true;
    }

    /** 绕过悬浮窗权限检查：OP_SYSTEM_ALERT_WINDOW=24 一律放行 */
    private void hookAppOps() {
        try {
            XposedHelpers.findAndHookMethod(AppOpsManager.class, "checkOpNoThrow", int.class, int.class, String.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if ((Integer) param.args[0] == 24) {
                        param.setResult(0); // MODE_ALLOWED
                    }
                }
            });
            try {
                XposedHelpers.findAndHookMethod(AppOpsManager.class, "checkOpNoThrow", int.class, String.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if ((Integer) param.args[0] == 24) {
                            param.setResult(0);
                        }
                    }
                });
            } catch (Throwable ignored) {
            }
            XposedBridge.log(TAG + " AppOps悬浮窗权限已放行");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hookAppOps失败: " + t);
        }
    }

    private static String extractDigits(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isDigit(c)) sb.append(c);
            else if (sb.length() > 0) break;
        }
        return sb.toString();
    }
}