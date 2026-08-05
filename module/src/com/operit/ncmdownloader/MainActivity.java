package com.operit.ncmdownloader;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
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

/** 模块 UI：查看当前播放歌曲ID、选音质下载、歌单一键下载 */
public class MainActivity extends Activity {

    private static final String TARGET = "com.netease.cloudmusic";

    private TextView tvTitle, tvId, tvStatus;
    private EditText etPlaylist;
    private LinearLayout songList;
    private RadioGroup qualityGroup;

    private String currentId = "";
    private String currentTitle = "";
    private String currentArtist = "";
    private String currentMusicU = "";
    private List<NcmApi.Song> currentSongs = new ArrayList<NcmApi.Song>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestStoragePermission();
        buildUi();
        startMediaSessionListener();
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT < 29) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
            }
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        // 标题
        root.addView(title("🎵 网易云自动下载", 20, true));

        // 当前播放区块
        root.addView(title("▶ 当前播放", 16, true));
        tvTitle = new TextView(this);
        tvTitle.setText("未检测到播放");
        tvTitle.setTextSize(15);
        root.addView(tvTitle);
        tvId = new TextView(this);
        tvId.setText("ID: -");
        tvId.setTextSize(14);
        root.addView(tvId);

        // 音质选择
        root.addView(title("音质", 16, true));
        qualityGroup = new RadioGroup(this);
        qualityGroup.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton rbStd = new RadioButton(this);
        rbStd.setText("标准 128k");
        rbStd.setId(1001);
        RadioButton rbHigh = new RadioButton(this);
        rbHigh.setText("高 320k");
        rbHigh.setId(1002);
        RadioButton rbLoss = new RadioButton(this);
        rbLoss.setText("无损(需VIP)");
        rbLoss.setId(1003);
        qualityGroup.addView(rbStd);
        qualityGroup.addView(rbHigh);
        qualityGroup.addView(rbLoss);
        qualityGroup.check(1002);
        root.addView(qualityGroup);

        Button btnDownload = new Button(this);
        btnDownload.setText("下载当前歌曲");
        btnDownload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                downloadCurrent();
            }
        });
        root.addView(btnDownload);

        // 分割
        root.addView(title("📋 歌单下载", 16, true));
        etPlaylist = new EditText(this);
        etPlaylist.setHint("输入歌单ID或分享链接，如 383599882 或 163cn.tv/xxxx");
        etPlaylist.setTextSize(14);
        root.addView(etPlaylist);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        Button btnFetch = new Button(this);
        btnFetch.setText("获取歌单");
        btnFetch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fetchAndShowPlaylist();
            }
        });
        Button btnAll = new Button(this);
        btnAll.setText("一键下载全部");
        btnAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                downloadAll();
            }
        });
        row1.addView(btnFetch, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row1.addView(btnAll, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row1);

        songList = new LinearLayout(this);
        songList.setOrientation(LinearLayout.VERTICAL);
        root.addView(songList);

        // 状态
        tvStatus = new TextView(this);
        tvStatus.setText("就绪");
        tvStatus.setTextSize(13);
        tvStatus.setPadding(0, dp(8), 0, 0);
        root.addView(tvStatus);

        setContentView(scroll);
    }

    private TextView title(String text, float sp, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sp);
        tv.setPadding(0, dp(6), 0, dp(2));
        if (bold) tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return tv;
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    // ===== 当前歌曲数据通道 =====
    private void startMediaSessionListener() {
        // 通道1：模块Hook写入的ContentProvider（跨进程，Android13+也可靠）
        registerProviderObserver();
        queryProvider();
        // 通道2：直接MediaSessionManager（Android12-可用；13+无权限会回退）
        try {
            final MediaSessionManager msm = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) return;
            MediaSessionManager.OnActiveSessionsChangedListener listener = new MediaSessionManager.OnActiveSessionsChangedListener() {
                @Override
                public void onActiveSessionsChanged(List<MediaController> controllers) {
                    handleControllers(controllers);
                }
            };
            msm.addOnActiveSessionsChangedListener(listener, null);
            handleControllers(msm.getActiveSessions(null));
        } catch (Throwable t) {
            // Android13+ 无 MEDIA_CONTENT_CONTROL 权限，使用模块 Provider 数据
            setStatus("已切换到模块数据通道");
        }
    }

    private void registerProviderObserver() {
        try {
            getContentResolver().registerContentObserver(CurrentProvider.URI, true,
                    new android.database.ContentObserver(new android.os.Handler(android.os.Looper.getMainLooper())) {
                        @Override
                        public void onChange(boolean selfChange) {
                            queryProvider();
                        }
                    });
        } catch (Throwable ignored) {
        }
    }

    private void queryProvider() {
        try {
            android.database.Cursor c = getContentResolver().query(CurrentProvider.URI, null, null, null, null);
            if (c != null) {
                if (c.moveToFirst()) {
                    final String id = c.getString(0);
                    final String title = c.getString(1);
                    final String artist = c.getString(2);
                    final String mu = c.getString(3);
                    if (mu != null) currentMusicU = mu;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (id != null && id.length() > 0) {
                                currentId = id;
                                currentTitle = title;
                                currentArtist = artist;
                                tvTitle.setText((artist == null ? "" : artist) + " - " + (title == null ? "" : title));
                                tvId.setText("ID: " + id);
                            }
                        }
                    });
                }
                c.close();
            }
        } catch (Throwable t) {
            setStatus("查询播放状态失败: " + t.getMessage());
        }
    }

    private void handleControllers(List<MediaController> controllers) {
        if (controllers == null) return;
        for (MediaController c : controllers) {
            try {
                if (c == null || c.getPackageName() == null) continue;
                if (!TARGET.equals(c.getPackageName())) continue;
                MediaMetadata md = c.getMetadata();
                if (md == null) continue;
                String id = md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
                if (id == null) continue;
                currentId = extractId(id);
                currentTitle = md.getString(MediaMetadata.METADATA_KEY_TITLE);
                currentArtist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
                final String t = currentTitle, a = currentArtist, i = currentId;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        tvTitle.setText((a == null ? "" : a) + " - " + (t == null ? "" : t));
                        tvId.setText("ID: " + i);
                    }
                });
            } catch (Throwable ignored) {
            }
        }
    }

    private String extractId(String mediaId) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mediaId.length(); i++) {
            char ch = mediaId.charAt(i);
            if (Character.isDigit(ch)) sb.append(ch);
            else if (sb.length() > 0) break;
        }
        return sb.toString();
    }

    // ===== 下载当前 =====
    private void downloadCurrent() {
        final String id = currentId;
        if (id == null || id.length() == 0) {
            toast("未检测到当前播放的网易云歌曲");
            return;
        }
        final int br = getSelectedBr();
        final String t = currentTitle == null ? id : currentTitle;
        final String a = currentArtist == null ? "未知歌手" : currentArtist;
        setStatus("下载中: " + a + " - " + t);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String url = NcmApi.fetchPlayUrl(id, br, currentMusicU);
                    if (url == null) throw new Exception("未获取到播放链接(该歌曲需VIP/付费，当前账号非VIP或未登录)");
                    final String fname = NcmApi.sanitize(a) + " - " + NcmApi.sanitize(t) + ".mp3";
                    NcmApi.downloadToStorage(MainActivity.this, url, fname);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            setStatus("✅ 下载完成: " + fname);
                            toast("已保存到 Music/NCM自动下载/");
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            setStatus("❌ 下载失败: " + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private int getSelectedBr() {
        int id = qualityGroup.getCheckedRadioButtonId();
        if (id == 1001) return NcmApi.BR_STANDARD;
        if (id == 1003) return NcmApi.BR_LOSSLESS;
        return NcmApi.BR_HIGH;
    }

    // ===== 歌单 =====
    private void fetchAndShowPlaylist() {
        final String raw = etPlaylist.getText().toString().trim();
        if (raw.length() == 0) {
            toast("请输入歌单ID或链接");
            return;
        }
        setStatus("解析歌单...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final String pid = NcmApi.resolveId(raw);
                    if (pid == null) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                setStatus("❌ 无法解析歌单ID");
                            }
                        });
                        return;
                    }
                    final List<NcmApi.Song> songs = NcmApi.fetchPlaylist(pid);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            showPlaylist(songs);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            setStatus("❌ 获取歌单失败: " + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void showPlaylist(List<NcmApi.Song> songs) {
        currentSongs = songs;
        songList.removeAllViews();
        setStatus("歌单共 " + songs.size() + " 首");
        for (final NcmApi.Song s : songs) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(3), 0, dp(3));
            TextView name = new TextView(this);
            name.setText(s.name + "\n" + (s.artist == null ? "" : s.artist)
                    + (s.isFree() ? "  [免费]" : "  [VIP/付费]"));
            name.setTextSize(13);
            Button btn = new Button(this);
            btn.setText("下载");
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    downloadOne(s);
                }
            });
            row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(btn, new LinearLayout.LayoutParams(dp(80), LinearLayout.LayoutParams.WRAP_CONTENT));
            songList.addView(row);
        }
    }

    private void downloadOne(final NcmApi.Song s) {
        if (!s.isFree()) {
            setStatus("⏭ 跳过VIP/付费: " + s.name);
            toast("跳过VIP/付费歌曲: " + s.name);
            return;
        }
        final int br = getSelectedBr();
        setStatus("下载中: " + s.name);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String url = NcmApi.fetchPlayUrl(String.valueOf(s.id), br, currentMusicU);
                    if (url == null) throw new Exception("无播放链接(需VIP/付费)");
                    final String fname = NcmApi.sanitize(s.artist) + " - " + NcmApi.sanitize(s.name) + ".mp3";
                    NcmApi.downloadToStorage(MainActivity.this, url, fname);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            setStatus("✅ 下载完成: " + s.name);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            setStatus("❌ " + s.name + ": " + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void downloadAll() {
        if (currentSongs.size() == 0) {
            toast("请先获取歌单");
            return;
        }
        final int br = getSelectedBr();
        setStatus("开始批量下载 " + currentSongs.size() + " 首...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                int ok = 0, fail = 0, skip = 0;
                for (final NcmApi.Song s : currentSongs) {
                    if (!s.isFree()) {
                        skip++;
                        continue;
                    }
                    try {
                        String url = NcmApi.fetchPlayUrl(String.valueOf(s.id), br, currentMusicU);
                        if (url == null) throw new Exception("无链接(需VIP)");
                        final String fname = NcmApi.sanitize(s.artist) + " - " + NcmApi.sanitize(s.name) + ".mp3";
                        NcmApi.downloadToStorage(MainActivity.this, url, fname);
                        ok++;
                        final int curOk = ok;
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                setStatus("批量下载 " + curOk + "/" + currentSongs.size() + ": " + s.name);
                            }
                        });
                    } catch (Throwable e) {
                        fail++;
                    }
                }
                final int fok = ok, ffail = fail, fskip = skip;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setStatus("批量下载完成: 成功 " + fok + "，跳过VIP " + fskip + "，失败 " + ffail);
                        toast("批量下载完成: 成功 " + fok + "，跳过VIP " + fskip + "，失败 " + ffail);
                    }
                });
            }
        }).start();
    }

    private void setStatus(final String msg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (tvStatus != null) tvStatus.setText(msg);
            }
        });
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }
}