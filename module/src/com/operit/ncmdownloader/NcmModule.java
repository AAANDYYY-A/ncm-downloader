package com.operit.ncmdownloader;

import android.app.Activity;
import android.app.AppOpsManager;
import android.content.ContentValues;
import android.content.Context;
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
 *  1. Hook MediaSession.setMetadata 拦截当前播放歌曲（网易云进程内，无权限问题）
 *  2. 歌曲信息写入 CurrentProvider（跨进程），UI 进程实时读取显示
 *  3. 自动下载到 Music/NCM自动下载/
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

        // 早期获取 Application Context
        XposedHelpers.findAndHookMethod(android.app.Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                appContext = (Context) param.args[0];
                installHooks();
                // 注册 Activity 生命周期回调：任何 Activity 恢复时启动悬浮窗
                // 比 Xposed hook 更可靠（不依赖 Activity.onCreate 的 hook 时机）
                try {
                    final Context c = appContext;
                    ((android.app.Application) c).registerActivityLifecycleCallbacks(
                            new android.app.Application.ActivityLifecycleCallbacks() {
                                @Override
                                public void onActivityResumed(Activity activity) {
                                    try {
                                        FloatWindow.show(activity);
                                    } catch (Throwable t) {
                                        XposedBridge.log(TAG + " 悬浮窗启动失败: " + t);
                                    }
                                }

                                @Override
                                public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                                }

                                @Override
                                public void onActivityStarted(Activity activity) {
                                }

                                @Override
                                public void onActivityPaused(Activity activity) {
                                }

                                @Override
                                public void onActivityStopped(Activity activity) {
                                }

                                @Override
                                public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
                                }

                                @Override
                                public void onActivityDestroyed(Activity activity) {
                                }
                            });
                    XposedBridge.log(TAG + " Activity生命周期回调已注册");
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " 注册ActivityLifecycleCallbacks失败: " + t);
                }
            }
        });

        // 兜底：Activity.onCreate 时初始化 + 启动悬浮窗
        XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (appContext == null && param.thisObject instanceof Activity) {
                    appContext = ((Activity) param.thisObject).getApplicationContext();
                }
                installHooks();
                // 延迟启动悬浮窗，等界面稳定
                try {
                    final Activity act = (Activity) param.thisObject;
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                Thread.sleep(1500);
                            } catch (Throwable ignored) {
                            }
                            try {
                                FloatWindow.show(act);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " 悬浮窗启动失败: " + t);
                            }
                        }
                    }).start();
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " 悬浮窗调度失败: " + t);
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
            // 绕过悬浮窗权限检查（OP_SYSTEM_ALERT_WINDOW = 24）
            hookAppOps();
            // 拦截 MediaSession.setMetadata 获取当前歌曲
            XposedHelpers.findAndHookMethod(MediaSession.class, "setMetadata", MediaMetadata.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        MediaMetadata md = (MediaMetadata) param.args[0];
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

                        // 自动下载
                        if (title != null && artist != null) {
                            Downloader.getInstance().onSongDetected(appContext, pureId, title, artist);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " setMetadata hook err " + t);
                    }
                }
            });
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