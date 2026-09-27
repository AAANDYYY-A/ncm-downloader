package com.operit.ncmdownloader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XposedBridge;

/**
 * 内嵌式界面（v3.0 起）：不再使用系统悬浮窗（overlay），
 * 直接把 UI addContentView 嵌入网易云自己的 Activity。
 *
 * 优点：
 *  - 切到其他应用 / 按 Home → 随 Activity 一起不可见，永远不会挡住其他应用
 *  - 无需悬浮窗权限（AppOps hook 仅作兼容保留）
 *  - 网易云内部切换 Activity 时由 onResume 重新挂载，状态（最小化/位置/歌单）保留
 */
public class FloatWindow {

    private static final String TAG = "NcmDownloader";
    private static FloatWindow inst;
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private final Context ctx;
    private Activity host;
    /** 根容器：内含面板 + 小圆球（最小化时切换可见性） */
    private FrameLayout container;
    /** 定位参数（TOP|END + margins），拖动时更新 */
    private FrameLayout.LayoutParams lp;

    private LinearLayout panel;
    private LinearLayout body;
    private TextView tvSong;
    private TextView tvStatus;
    private ProgressBar pb;
    private TextView tvProgress;
    private RadioGroup rgBr;
    private LinearLayout listWrap;
    private LinearLayout listContainer;
    private View iconView;

    private boolean minimized = false;
    private int br = NcmApi.BR_HIGH;
    private String currentId = "", currentTitle = "", currentArtist = "";
    private final List<NcmApi.Song> songs = new ArrayList<NcmApi.Song>();

    // ==================== 静态入口 ====================

    public static synchronized void show(final Activity act) {
        try {
            if (inst != null) {
                inst.attachTo(act);
                return;
            }
            UI.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (inst == null) {
                            inst = new FloatWindow(act);
                        } else {
                            inst.attachTo(act);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " 界面创建失败: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + " show 失败: " + t);
        }
    }

    /** 网易云任一 Activity onResume → 把界面重新挂到当前 Activity（跟随前台） */
    public static void onActivityResume(final Activity act) {
        if (inst != null) {
            inst.attachTo(act);
        }
    }

    public static void updateSong(String id, String title, String artist) {
        if (inst != null) {
            inst.postSong(id, title, artist);
        }
    }

    public static void updateStatus(String s) {
        if (inst != null) {
            inst.postStatus(s);
        }
    }

    /** 更新下载进度（percent：0-100，-1=未知长度，-2=结束隐藏） */
    public static void updateProgress(int percent, String text) {
        if (inst != null) {
            inst.postProgress(percent, text);
        }
    }

    private FloatWindow(Activity act) {
        this.ctx = act.getApplicationContext();
        buildUi(act);
        attachTo(act);
        XposedBridge.log(TAG + " 内嵌界面已创建");
    }

    /** 把根容器挂到指定 Activity 的内容区（addContentView）；已挂同一 Activity 则跳过 */
    private void attachTo(final Activity act) {
        if (act == null || act.isFinishing()) {
            return;
        }
        try {
            if (act == host && container.getParent() != null) {
                return;
            }
            // 从旧父容器摘除（网易云 Activity 切换时）
            ViewGroup oldParent = (ViewGroup) container.getParent();
            if (oldParent != null) {
                oldParent.removeView(container);
            }
            host = act;
            if (lp == null) {
                lp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.gravity = Gravity.TOP | Gravity.END;
                lp.topMargin = dp(96);
                lp.rightMargin = dp(10);
            }
            act.getWindow().addContentView(container, lp);
            refreshMinimizeState();
            XposedBridge.log(TAG + " 内嵌界面已挂载: " + act.getClass().getSimpleName());
        } catch (Throwable t) {
            XposedBridge.log(TAG + " 挂载失败: " + t);
        }
    }

    // ==================== UI 构建 ====================

    private void buildUi(final Activity act) {
        container = new FrameLayout(ctx);

        // ---------- 面板 ----------
        panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundDrawable(rounded(Color.parseColor("#F5FFFFFF"), dp(14)));
        panel.setPadding(dp(10), dp(8), dp(10), dp(8));
        container.addView(panel, new FrameLayout.LayoutParams(dp(320), ViewGroup.LayoutParams.WRAP_CONTENT));

        // 标题栏（可拖动）
        LinearLayout headerRow = new LinearLayout(ctx);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        final TextView header = new TextView(ctx);
        header.setText("🎵 网易云下载  ⟶ 拖动");
        header.setTextColor(Color.parseColor("#1DB954"));
        header.setTextSize(14);
        header.setPadding(dp(4), dp(4), dp(4), dp(4));
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setSingleLine(true);
        DragHelper.install(header, this);
        headerRow.addView(header, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Button btnMin = new Button(ctx);
        btnMin.setText("—");
        btnMin.setTextSize(12);
        btnMin.setPadding(dp(6), 0, dp(6), 0);
        btnMin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleMinimize();
            }
        });
        headerRow.addView(btnMin);

        Button btnClose = new Button(ctx);
        btnClose.setText("✕");
        btnClose.setTextSize(12);
        btnClose.setPadding(dp(6), 0, dp(6), 0);
        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                close();
            }
        });
        headerRow.addView(btnClose);
        panel.addView(headerRow);

        // 主体
        body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        panel.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        tvSong = new TextView(ctx);
        tvSong.setText("当前歌曲: 无");
        tvSong.setTextColor(Color.parseColor("#333333"));
        tvSong.setTextSize(13);
        tvSong.setPadding(0, dp(6), 0, dp(4));
        body.addView(tvSong);

        // 音质选择
        LinearLayout brRow = new LinearLayout(ctx);
        brRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView brLabel = new TextView(ctx);
        brLabel.setText("音质:");
        brLabel.setTextSize(13);
        brLabel.setTextColor(Color.parseColor("#666666"));
        brRow.addView(brLabel);
        rgBr = new RadioGroup(ctx);
        rgBr.setOrientation(RadioGroup.HORIZONTAL);
        addBrOption("标准128k", NcmApi.BR_STANDARD, false);
        addBrOption("高320k", NcmApi.BR_HIGH, true);
        addBrOption("无损", NcmApi.BR_LOSSLESS, false);
        rgBr.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                br = checkedId;
            }
        });
        brRow.addView(rgBr);
        body.addView(brRow);

        Button btnDown = new Button(ctx);
        btnDown.setText("⬇ 下载当前歌曲");
        btnDown.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                downloadCurrent();
            }
        });
        body.addView(btnDown);

        // 歌单操作行
        LinearLayout plRow = new LinearLayout(ctx);
        plRow.setOrientation(LinearLayout.HORIZONTAL);
        Button btnInput = new Button(ctx);
        btnInput.setText("歌单ID");
        btnInput.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                promptPlaylist(act);
            }
        });
        plRow.addView(btnInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button btnAll = new Button(ctx);
        btnAll.setText("一键下载全部");
        btnAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                downloadAll();
            }
        });
        plRow.addView(btnAll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        body.addView(plRow);

        tvStatus = new TextView(ctx);
        tvStatus.setText("就绪");
        tvStatus.setTextColor(Color.parseColor("#1DB954"));
        tvStatus.setTextSize(12);
        tvStatus.setPadding(0, dp(4), 0, dp(2));
        body.addView(tvStatus);

        // 下载进度条
        pb = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        pb.setVisibility(View.GONE);
        body.addView(pb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)));

        tvProgress = new TextView(ctx);
        tvProgress.setTextColor(Color.parseColor("#1DB954"));
        tvProgress.setTextSize(11);
        tvProgress.setVisibility(View.GONE);
        body.addView(tvProgress);

        // 歌单列表
        listWrap = new LinearLayout(ctx);
        listWrap.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(ctx);
        listContainer = new LinearLayout(ctx);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        sv.addView(listContainer, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        listWrap.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)));
        listWrap.setVisibility(View.GONE);
        body.addView(listWrap);

        // ---------- 小圆球（最小化） ----------
        TextView iv = new TextView(ctx);
        iv.setText("♪");
        iv.setTextColor(Color.WHITE);
        iv.setTextSize(22);
        iv.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.parseColor("#E61DB954"));
        iv.setBackgroundDrawable(bg);
        FrameLayout.LayoutParams ivLp = new FrameLayout.LayoutParams(dp(48), dp(48));
        container.addView(iv, ivLp);
        iv.setVisibility(View.GONE);
        iconView = iv;
        DragHelper.install(iv, this);

        refreshMinimizeState();
    }

    /** 面板 / 圆球 可见性切换 */
    private void refreshMinimizeState() {
        if (panel == null || iconView == null) return;
        panel.setVisibility(minimized ? View.GONE : View.VISIBLE);
        iconView.setVisibility(minimized ? View.VISIBLE : View.GONE);
    }

    private void toggleMinimize() {
        minimized = !minimized;
        refreshMinimizeState();
    }

    private void addBrOption(String label, final int value, boolean checked) {
        RadioButton rb = new RadioButton(ctx);
        rb.setText(label);
        rb.setTextSize(12);
        rb.setId(value);
        rb.setChecked(checked);
        if (checked) br = value;
        rgBr.addView(rb);
    }

    // ==================== 拖动（更新 container 的 margins） ====================

    static class DragHelper {
        static void install(final View handle, final FloatWindow fw) {
            handle.setOnTouchListener(new View.OnTouchListener() {
                float downRawX, downRawY;
                int startRight, startTop;

                @Override
                public boolean onTouch(View v, MotionEvent e) {
                    switch (e.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            downRawX = e.getRawX();
                            downRawY = e.getRawY();
                            startRight = fw.lp.rightMargin;
                            startTop = fw.lp.topMargin;
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            int dx = (int) (e.getRawX() - downRawX);
                            int dy = (int) (e.getRawY() - downRawY);
                            fw.lp.rightMargin = Math.max(0, startRight - dx);
                            fw.lp.topMargin = Math.max(0, startTop + dy);
                            fw.container.setLayoutParams(fw.lp);
                            return true;
                        case MotionEvent.ACTION_UP:
                            float ddx = e.getRawX() - downRawX;
                            float ddy = e.getRawY() - downRawY;
                            if (v == fw.iconView && ddx * ddx + ddy * ddy < 400) {
                                fw.toggleMinimize(); // 圆球点击=展开
                            }
                            return true;
                    }
                    return false;
                }
            });
        }
    }

    // ==================== 歌曲/状态/进度 ====================

    private void postSong(final String id, final String title, final String artist) {
        UI.post(new Runnable() {
            @Override
            public void run() {
                currentId = id == null ? "" : id;
                currentTitle = title == null ? "" : title;
                currentArtist = artist == null ? "" : artist;
                if (tvSong != null) {
                    tvSong.setText("当前歌曲: " + currentArtist + " - " + currentTitle + "\nID: " + currentId);
                }
            }
        });
    }

    private void postStatus(final String s) {
        UI.post(new Runnable() {
            @Override
            public void run() {
                if (tvStatus != null) tvStatus.setText(s);
            }
        });
    }

    private void postProgress(final int percent, final String text) {
        UI.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (pb == null || tvProgress == null) return;
                    if (percent == -2) {
                        pb.setVisibility(View.GONE);
                        tvProgress.setVisibility(View.GONE);
                        return;
                    }
                    pb.setVisibility(View.VISIBLE);
                    tvProgress.setVisibility(View.VISIBLE);
                    if (percent < 0) {
                        pb.setIndeterminate(true);
                        tvProgress.setText("下载中... " + (text == null ? "" : text));
                    } else {
                        pb.setIndeterminate(false);
                        pb.setProgress(percent);
                        tvProgress.setText("下载 " + percent + "% " + (text == null ? "" : text));
                    }
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private void hideProgressArea() {
        postProgress(-2, null);
    }

    // ==================== 歌单 / 下载 ====================

    private void promptPlaylist(final Activity act) {
        try {
            Activity a = host != null && !host.isFinishing() ? host : act;
            final EditText et = new EditText(a);
            et.setHint("歌单ID或分享链接");
            AlertDialog dlg = new AlertDialog.Builder(a)
                    .setTitle("输入歌单")
                    .setView(et)
                    .setPositiveButton("获取歌单", null)
                    .setNegativeButton("取消", null)
                    .create();
            dlg.show();
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String input = et.getText().toString().trim();
                    if (input.length() == 0) {
                        Toast.makeText(a, "请输入歌单ID", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    dlg.dismiss();
                    loadPlaylist(input);
                }
            });
        } catch (Throwable t) {
            postStatus("弹窗失败: " + t);
        }
    }

    private void loadPlaylist(final String input) {
        postStatus("获取歌单中...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String[] r = NcmApi.resolveLink(input);
                    String id = (r != null) ? r[1] : null;
                    if (id == null) {
                        postStatus("无法解析歌单ID");
                        return;
                    }
                    final List<NcmApi.Song> list = NcmApi.fetchPlaylist(id, null);
                    songs.clear();
                    songs.addAll(list);
                    UI.post(new Runnable() {
                        @Override
                        public void run() {
                            renderPlaylist();
                            listWrap.setVisibility(View.VISIBLE);
                        }
                    });
                    postStatus("歌单共 " + list.size() + " 首（免费" + countFree(list) + "）");
                } catch (Throwable t) {
                    postStatus("获取歌单失败: " + t.getMessage());
                }
            }
        }).start();
    }

    private int countFree(List<NcmApi.Song> list) {
        int c = 0;
        for (NcmApi.Song s : list) {
            if (s.isFree()) c++;
        }
        return c;
    }

    private void renderPlaylist() {
        listContainer.removeAllViews();
        for (final NcmApi.Song s : songs) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView tv = new TextView(ctx);
            tv.setText(s.name + " - " + (s.artist == null ? "" : s.artist)
                    + (s.isFree() ? " [免费]" : " [VIP]"));
            tv.setTextSize(11);
            tv.setTextColor(Color.parseColor("#444444"));
            tv.setMaxLines(1);
            row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            Button btn = new Button(ctx);
            btn.setText("下");
            btn.setTextSize(10);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    downloadOne(s);
                }
            });
            row.addView(btn);
            listContainer.addView(row);
        }
    }

    private void downloadCurrent() {
        if (currentId.length() == 0) {
            postStatus("无当前歌曲");
            return;
        }
        NcmApi.Song s = new NcmApi.Song(Long.parseLong(currentId), currentTitle, currentArtist, "", 0);
        downloadOne(s);
    }

    private void downloadOne(final NcmApi.Song s) {
        postStatus("下载中: " + s.name);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String fname = sanitize(s.artist) + " - " + sanitize(s.name) + ".mp3";
                    NcmApi.downloadWithFallback(ctx, String.valueOf(s.id), br, readCookie(), fname,
                            new NcmApi.ProgressListener() {
                                @Override
                                public void onProgress(int percent) {
                                    postProgress(percent, s.name);
                                }
                            });
                    postStatus("✅ 已下载: " + s.name);
                } catch (Throwable t) {
                    postStatus("下载失败: " + t.getMessage());
                } finally {
                    hideProgressArea();
                }
            }
        }).start();
    }

    private void downloadAll() {
        if (songs.isEmpty()) {
            postStatus("请先获取歌单");
            return;
        }
        final int brF = br;
        final int total = songs.size();
        postStatus("批量下载中...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                int ok = 0, skip = 0, fail = 0;
                String cookie = readCookie();
                for (int i = 0; i < total; i++) {
                    final NcmApi.Song s = songs.get(i);
                    if (!s.isFree()) {
                        skip++;
                        continue;
                    }
                    final int idx = i + 1;
                    try {
                        postProgress(0, "(" + idx + "/" + total + ") " + s.name);
                        String fname = sanitize(s.artist) + " - " + sanitize(s.name) + ".mp3";
                        NcmApi.downloadWithFallback(ctx, String.valueOf(s.id), brF, cookie, fname,
                                new NcmApi.ProgressListener() {
                                    @Override
                                    public void onProgress(int percent) {
                                        postProgress(percent, "(" + idx + "/" + total + ") " + s.name);
                                    }
                                });
                        ok++;
                        postStatus("批量进行中: 已下 " + ok + " 首");
                    } catch (Throwable t) {
                        fail++;
                    }
                }
                postStatus("批量完成: 成功" + ok + " 跳过VIP" + skip + " 失败" + fail);
                hideProgressArea();
            }
        }).start();
    }

    private String readCookie() {
        try {
            String mu = NcmApi.readMusicUFromFile("/data/data/com.netease.cloudmusic/shared_prefs/cm_cookie_storage.xml");
            if (mu != null && mu.length() > 0) {
                return "MUSIC_U=" + mu;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\\\/:*?\"<>|\\n\\r]", "_").trim();
    }

    private int dp(int v) {
        return (int) (ctx.getResources().getDisplayMetrics().density * v + 0.5f);
    }

    private static GradientDrawable rounded(int color, int radius) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(radius);
        return gd;
    }

    /** 关闭：从 Activity 中移除界面 */
    private void close() {
        try {
            ViewGroup p = (ViewGroup) container.getParent();
            if (p != null) {
                p.removeView(container);
            }
        } catch (Throwable ignored) {
        }
        inst = null;
    }
}