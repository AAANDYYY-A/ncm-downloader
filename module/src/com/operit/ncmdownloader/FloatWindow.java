package com.operit.ncmdownloader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XposedBridge;

/**
 * 悬浮窗：注入网易云进程，显示当前歌曲并提供下载面板。
 * 支持拖动、最小化、音质选择、单曲下载、歌单批量下载（自动跳过VIP）。
 */
public class FloatWindow {

    private static final String TAG = "NcmDownloader";
    private static FloatWindow inst;

    private final Context ctx;
    private final WindowManager wm;
    private LinearLayout root;
    private LinearLayout body;
    private TextView tvSong;
    private TextView tvStatus;
    private RadioGroup rgBr;
    private LinearLayout listWrap;
    private LinearLayout listContainer;
    private Activity activity;
    private boolean minimized = false;
    private int br = NcmApi.BR_HIGH;
    private String currentId = "", currentTitle = "", currentArtist = "";
    private final List<NcmApi.Song> songs = new ArrayList<NcmApi.Song>();

    public static synchronized void show(Activity act) {
        if (inst != null) {
            inst.activity = act; // 更新activity引用（用于弹窗）
            return;
        }
        try {
            inst = new FloatWindow(act);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " 悬浮窗创建失败: " + t);
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

    private FloatWindow(Activity act) {
        this.ctx = act.getApplicationContext();
        this.activity = act;
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        buildView(act);
        addToWindow(act);
    }

    private void buildView(final Activity act) {
        root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundDrawable(rounded(Color.parseColor("#F5FFFFFF"), dp(14)));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(dp(320), ViewGroup.LayoutParams.WRAP_CONTENT);
        root.setLayoutParams(rp);
        // 防止父级LinearLayout干扰
        root.setPadding(dp(10), dp(8), dp(10), dp(8));

        // 标题栏（可拖动）
        final TextView header = new TextView(ctx);
        header.setText("🎵 网易云下载  ⟶ 按住拖动");
        header.setTextColor(Color.parseColor("#1DB954"));
        header.setTextSize(14);
        header.setPadding(dp(4), dp(4), dp(4), dp(4));
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setSingleLine(true);
        header.setOnTouchListener(new View.OnTouchListener() {
            float startX, startY;
            int startRawX, startRawY;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                WindowManager.LayoutParams lp = (WindowManager.LayoutParams) root.getLayoutParams();
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startRawX = (int) e.getRawX();
                        startRawY = (int) e.getRawY();
                        startX = lp.x;
                        startY = lp.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        lp.x = (int) (startX + (e.getRawX() - startRawX));
                        lp.y = (int) (startY + (e.getRawY() - startRawY));
                        try {
                            wm.updateViewLayout(root, lp);
                        } catch (Throwable ignored) {
                        }
                        return true;
                }
                return false;
            }
        });
        root.addView(header, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

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
        root.addView(btnMin);

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
        root.addView(btnClose);

        // 主体（可收起）
        body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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

        // 歌单列表（可滚动）
        listWrap = new LinearLayout(ctx);
        listWrap.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(ctx);
        listContainer = new LinearLayout(ctx);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        sv.addView(listContainer, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams svp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220));
        listWrap.addView(sv, svp);
        listWrap.setVisibility(View.GONE);
        body.addView(listWrap);
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

    private void addToWindow(Activity act) {
        int type;
        if (Build.VERSION.SDK_INT >= 26) {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            type = WindowManager.LayoutParams.TYPE_PHONE;
        }
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                dp(330), ViewGroup.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                android.graphics.PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = dp(8);
        lp.y = dp(160);
        try {
            wm.addView(root, lp);
            XposedBridge.log(TAG + " 悬浮窗已添加");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " 悬浮窗添加失败(需悬浮窗权限): " + t);
        }
    }

    private void toggleMinimize() {
        minimized = !minimized;
        body.setVisibility(minimized ? View.GONE : View.VISIBLE);
    }

    private void close() {
        try {
            wm.removeView(root);
        } catch (Throwable ignored) {
        }
        inst = null;
    }

    private void postSong(final String id, final String title, final String artist) {
        try {
            root.post(new Runnable() {
                @Override
                public void run() {
                    currentId = id == null ? "" : id;
                    currentTitle = title == null ? "" : title;
                    currentArtist = artist == null ? "" : artist;
                    tvSong.setText("当前歌曲: " + currentArtist + " - " + currentTitle + "\nID: " + currentId);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void postStatus(final String s) {
        try {
            root.post(new Runnable() {
                @Override
                public void run() {
                    tvStatus.setText(s);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void promptPlaylist(final Activity act) {
        try {
            final EditText et = new EditText(act);
            et.setHint("歌单ID或分享链接");
            AlertDialog dlg = new AlertDialog.Builder(act)
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
                        Toast.makeText(act, "请输入歌单ID", Toast.LENGTH_SHORT).show();
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
                    root.post(new Runnable() {
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
                    String url = NcmApi.fetchPlayUrl(String.valueOf(s.id), br, readCookie());
                    if (url == null) {
                        postStatus("⏭ 需VIP/无链接: " + s.name);
                        return;
                    }
                    String fname = sanitize(s.artist) + " - " + sanitize(s.name) + ".mp3";
                    NcmApi.downloadToStorage(ctx, url, fname);
                    postStatus("✅ 已下载: " + s.name);
                } catch (Throwable t) {
                    postStatus("下载失败: " + t.getMessage());
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
        postStatus("批量下载中...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                int ok = 0, skip = 0, fail = 0;
                String cookie = readCookie();
                for (NcmApi.Song s : songs) {
                    if (!s.isFree()) {
                        skip++;
                        continue;
                    }
                    try {
                        String url = NcmApi.fetchPlayUrl(String.valueOf(s.id), brF, cookie);
                        if (url == null) throw new Exception("需VIP");
                        String fname = sanitize(s.artist) + " - " + sanitize(s.name) + ".mp3";
                        NcmApi.downloadToStorage(ctx, url, fname);
                        ok++;
                    } catch (Throwable t) {
                        fail++;
                    }
                }
                postStatus("批量完成: 成功" + ok + " 跳过VIP" + skip + " 失败" + fail);
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
}
