package com.operit.ncmdownloader;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 网易云下载器（双模式）
 * 主视图：粘贴分享链接下载播放 + 当前播放（root模块抓取） + 我的下载 + 播放器
 * 设置页：工作模式（自动/仅手动）、自动下载开关、默认音质
 */
public class MainActivity extends Activity {

    private static final String PREF = "ncm_pref";
    private static final String KEY_MUSICU = "music_u";
    private static final String KEY_MODE = "mode";
    private static final String KEY_AUTODL = "auto_download";
    private static final String KEY_BR = "br";

    private EditText etInput;
    private RadioGroup rgType;
    private LinearLayout resultArea;
    private LinearLayout downloadedArea;
    private TextView tvStatus;

    // 播放器
    private MediaPlayer mp;
    private String nowPlaying = "";
    private Button btnPlay, btnStop;
    private TextView tvNow;

    // 当前播放（root模块Provider数据）
    private TextView tvNowPlaying;
    private String curId = "", curTitle = "", curArtist = "", curMusicU = "";

    private List<NcmApi.Song> currentSongs = new ArrayList<NcmApi.Song>();
    private int currentBr = NcmApi.BR_HIGH;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        currentBr = loadBr();
        if (getIntent().getBooleanExtra("mode_settings", false)) {
            buildSettingsUi();
        } else {
            buildUi();
            refreshDownloaded();
            loadCurrentFromProvider();
        }
    }

    // ==================== 主视图 ====================
    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        // 标题行 + 设置按钮
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = title("🎵 网易云下载器", 20, true);
        Button btnSettings = new Button(this);
        btnSettings.setText("⚙ 设置");
        btnSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, MainActivity.class)
                        .putExtra("mode_settings", true));
            }
        });
        titleRow.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        titleRow.addView(btnSettings);
        root.addView(titleRow);
        root.addView(title("无需root：粘贴分享链接下载播放；root环境自动抓取当前歌曲", 12, false));

        // 当前播放（root模块数据）
        root.addView(title("▶ 当前播放 (root自动获取)", 15, true));
        tvNowPlaying = new TextView(this);
        tvNowPlaying.setText("未检测到（无root环境请忽略）");
        tvNowPlaying.setTextSize(13);
        root.addView(tvNowPlaying);
        Button btnDlCur = new Button(this);
        btnDlCur.setText("⬇ 下载当前歌曲");
        btnDlCur.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                downloadCurrentFromProvider();
            }
        });
        root.addView(btnDlCur);

        // 输入区
        etInput = new EditText(this);
        etInput.setHint("粘贴分享链接，如 https://music.163.com/song?id=xxx\n或歌单 https://music.163.com/playlist?id=xxx / 163cn.tv/xxx / 纯ID");
        etInput.setTextSize(13);
        etInput.setMinLines(2);
        root.addView(etInput);

        rgType = new RadioGroup(this);
        rgType.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton rbSong = new RadioButton(this);
        rbSong.setText("单曲");
        rbSong.setId(2001);
        RadioButton rbList = new RadioButton(this);
        rbList.setText("歌单");
        rbList.setId(2002);
        rgType.addView(rbSong);
        rgType.addView(rbList);
        rgType.check(2002);
        root.addView(rgType);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        Button btnParse = new Button(this);
        btnParse.setText("解析");
        btnParse.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                parseInput();
            }
        });
        Button btnLogin = new Button(this);
        btnLogin.setText("登录设置");
        btnLogin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLoginDialog();
            }
        });
        row1.addView(btnParse, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row1.addView(btnLogin, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row1);

        // 音质
        LinearLayout brRow = new LinearLayout(this);
        brRow.setOrientation(LinearLayout.HORIZONTAL);
        brRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView brLabel = new TextView(this);
        brLabel.setText("音质:");
        brLabel.setTextSize(13);
        brRow.addView(brLabel);
        final RadioGroup brGroup = new RadioGroup(this);
        brGroup.setOrientation(RadioGroup.HORIZONTAL);
        addBr(brGroup, "128k", NcmApi.BR_STANDARD, currentBr == NcmApi.BR_STANDARD);
        addBr(brGroup, "320k", NcmApi.BR_HIGH, currentBr == NcmApi.BR_HIGH);
        addBr(brGroup, "无损", NcmApi.BR_LOSSLESS, currentBr == NcmApi.BR_LOSSLESS);
        brGroup.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                currentBr = checkedId;
            }
        });
        brRow.addView(brGroup);
        root.addView(brRow);

        // 解析结果区
        root.addView(title("📄 解析结果", 15, true));
        resultArea = new LinearLayout(this);
        resultArea.setOrientation(LinearLayout.VERTICAL);
        root.addView(resultArea);

        // 我的下载
        root.addView(title("💾 我的下载 (Music/NCM自动下载)", 15, true));
        downloadedArea = new LinearLayout(this);
        downloadedArea.setOrientation(LinearLayout.VERTICAL);
        root.addView(downloadedArea);
        Button btnRefresh = new Button(this);
        btnRefresh.setText("刷新下载列表");
        btnRefresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshDownloaded();
            }
        });
        root.addView(btnRefresh);

        // 播放器栏
        root.addView(title("▶ 播放器", 15, true));
        LinearLayout playerRow = new LinearLayout(this);
        playerRow.setOrientation(LinearLayout.HORIZONTAL);
        playerRow.setGravity(Gravity.CENTER_VERTICAL);
        btnPlay = new Button(this);
        btnPlay.setText("▶");
        btnPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePlay();
            }
        });
        btnStop = new Button(this);
        btnStop.setText("■");
        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopPlay();
            }
        });
        tvNow = new TextView(this);
        tvNow.setText("未播放");
        tvNow.setTextSize(13);
        playerRow.addView(btnPlay);
        playerRow.addView(btnStop);
        playerRow.addView(tvNow, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(playerRow);

        tvStatus = new TextView(this);
        tvStatus.setText("就绪");
        tvStatus.setTextSize(13);
        tvStatus.setPadding(0, dp(8), 0, 0);
        root.addView(tvStatus);

        setContentView(scroll);
    }

    /** 读取模块写入的当前歌曲（root自动获取） */
    private void loadCurrentFromProvider() {
        try {
            Cursor c = getContentResolver().query(CurrentProvider.URI, null, null, null, null);
            if (c != null) {
                if (c.moveToFirst()) {
                    final String id = c.getString(0);
                    final String title = c.getString(1);
                    final String artist = c.getString(2);
                    final String mu = c.getString(3);
                    if (mu != null && mu.length() > 0) curMusicU = mu;
                    if (id != null && id.length() > 0) {
                        curId = id;
                        curTitle = title == null ? "" : title;
                        curArtist = artist == null ? "" : artist;
                        tvNowPlaying.setText((curArtist.length() > 0 ? curArtist : "未知歌手")
                                + " - " + (curTitle.length() > 0 ? curTitle : id) + "\nID: " + id);
                    }
                }
                c.close();
            }
        } catch (Throwable t) {
            // Provider可能不存在（非root/未安装模块），忽略
        }
    }

    private void downloadCurrentFromProvider() {
        if (curId.length() == 0) {
            toast("未检测到当前播放歌曲");
            return;
        }
        final String id = curId;
        final String t = curTitle.length() > 0 ? curTitle : id;
        final String a = curArtist.length() > 0 ? curArtist : "未知歌手";
        setStatus("下载中: " + a + " - " + t);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final String fname = NcmApi.sanitize(a) + " - " + NcmApi.sanitize(t) + ".mp3";
                    NcmApi.downloadWithFallback(MainActivity.this, id, currentBr, getCookie(), fname);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            setStatus("✅ 已保存: " + fname);
                            refreshDownloaded();
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

    // ==================== 设置页 ====================
    private void buildSettingsUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        root.addView(title("⚙ 设置", 20, true));
        root.addView(title("工作模式", 15, true));
        final RadioGroup modeGroup = new RadioGroup(this);
        modeGroup.setOrientation(RadioGroup.VERTICAL);
        RadioButton rbAuto = new RadioButton(this);
        rbAuto.setText("自动模式（推荐）：root环境自动抓取当前歌曲+悬浮窗+可选自动下载；非root环境手动解析同样可用");
        rbAuto.setId(3001);
        rbAuto.setTextSize(13);
        RadioButton rbManual = new RadioButton(this);
        rbManual.setText("仅手动模式：关闭模块自动功能，只用手动粘贴链接解析下载");
        rbManual.setId(3002);
        rbManual.setTextSize(13);
        modeGroup.addView(rbAuto);
        modeGroup.addView(rbManual);
        modeGroup.check("manual".equals(loadMode()) ? 3002 : 3001);
        root.addView(modeGroup);

        root.addView(title("自动下载（root环境切歌自动保存）", 15, true));
        final CheckBox cbAutoDl = new CheckBox(this);
        cbAutoDl.setText("切歌时自动下载到 Music/NCM自动下载/");
        cbAutoDl.setTextSize(13);
        cbAutoDl.setChecked(loadAutoDl());
        root.addView(cbAutoDl);

        root.addView(title("默认音质", 15, true));
        final RadioGroup brGroup = new RadioGroup(this);
        brGroup.setOrientation(RadioGroup.HORIZONTAL);
        addBr(brGroup, "128k", NcmApi.BR_STANDARD, currentBr == NcmApi.BR_STANDARD);
        addBr(brGroup, "320k", NcmApi.BR_HIGH, currentBr == NcmApi.BR_HIGH);
        addBr(brGroup, "无损", NcmApi.BR_LOSSLESS, currentBr == NcmApi.BR_LOSSLESS);
        root.addView(brGroup);

        root.addView(title("说明", 13, true));
        TextView desc = new TextView(this);
        desc.setText("· root环境：LSPosed启用模块→作用域勾选网易云→重启网易云生效\n· 自动获取当前歌曲需网易云处于播放状态\n· 自动下载开关与工作模式由模块进程实时读取（无需重启）");
        desc.setTextSize(12);
        root.addView(desc);

        Button btnSave = new Button(this);
        btnSave.setText("保存设置");
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String mode = modeGroup.getCheckedRadioButtonId() == 3002 ? "manual" : "auto";
                boolean auto = cbAutoDl.isChecked();
                int br = brGroup.getCheckedRadioButtonId();
                if (br == 0) br = currentBr;
                saveSettings(mode, auto, br);
                pushSettingsToProvider(mode, auto, br);
                toast("已保存：模式=" + ("manual".equals(mode) ? "仅手动" : "自动")
                        + " 自动下载=" + (auto ? "开" : "关"));
                finish();
            }
        });
        root.addView(btnSave);

        Button btnBack = new Button(this);
        btnBack.setText("返回");
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        root.addView(btnBack);

        setContentView(scroll);
    }

    private void saveSettings(String mode, boolean auto, int br) {
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putString(KEY_MODE, mode)
                .putBoolean(KEY_AUTODL, auto)
                .putInt(KEY_BR, br)
                .apply();
        currentBr = br;
    }

    /** 同步设置到Provider，模块进程实时读取 */
    private void pushSettingsToProvider(String mode, boolean auto, int br) {
        try {
            ContentValues cv = new ContentValues();
            cv.put("mode", mode);
            cv.put("autoDownload", auto ? 1 : 0);
            cv.put("br", br);
            getContentResolver().update(CurrentProvider.SETTINGS_URI, cv, null, null);
        } catch (Throwable ignored) {
        }
    }

    private String loadMode() {
        return getSharedPreferences(PREF, MODE_PRIVATE).getString(KEY_MODE, "auto");
    }

    private boolean loadAutoDl() {
        return getSharedPreferences(PREF, MODE_PRIVATE).getBoolean(KEY_AUTODL, true);
    }

    private int loadBr() {
        return getSharedPreferences(PREF, MODE_PRIVATE).getInt(KEY_BR, NcmApi.BR_HIGH);
    }

    // ==================== 解析输入 ====================
    private void parseInput() {
        final String raw = etInput.getText().toString().trim();
        if (raw.length() == 0) {
            toast("请先粘贴网易云分享链接");
            return;
        }
        setStatus("解析中...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String[] r = NcmApi.resolveLink(raw);
                    if (r == null || r[1] == null) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                setStatus("❌ 无法识别链接，请检查是否为网易云分享链接");
                            }
                        });
                        return;
                    }
                    String type = r[0];
                    final String id = r[1];
                    if ("raw".equals(type)) {
                        type = rgType.getCheckedRadioButtonId() == 2001 ? "song" : "playlist";
                    }
                    final String ftype = type;
                    final String cookie = getCookie();
                    if ("song".equals(ftype)) {
                        final NcmApi.Song s = NcmApi.fetchSongInfo(id, cookie);
                        if (s == null) throw new Exception("歌曲不存在或已下架");
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                showSong(s);
                                setStatus("单曲解析成功 ID=" + id);
                            }
                        });
                    } else {
                        final List<NcmApi.Song> songs = NcmApi.fetchPlaylist(id, cookie);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                showPlaylist(songs);
                                setStatus("歌单解析成功，共 " + songs.size() + " 首");
                            }
                        });
                    }
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            setStatus("❌ 解析失败: " + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void showSong(final NcmApi.Song s) {
        resultArea.removeAllViews();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(this);
        name.setText(s.displayName() + (s.isFree() ? "  [免费]" : "  [VIP/付费]"));
        name.setTextSize(14);
        Button btnDown = new Button(this);
        btnDown.setText("⬇下载");
        btnDown.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                downloadSong(s);
            }
        });
        Button btnPlay = new Button(this);
        btnPlay.setText("▶播放");
        btnPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                playOnline(s);
            }
        });
        row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(btnDown, new LinearLayout.LayoutParams(dp(90), LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(btnPlay, new LinearLayout.LayoutParams(dp(90), LinearLayout.LayoutParams.WRAP_CONTENT));
        resultArea.addView(row);
    }

    private void showPlaylist(final List<NcmApi.Song> songs) {
        resultArea.removeAllViews();
        currentSongs = songs;
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        TextView cnt = new TextView(this);
        cnt.setText("共 " + songs.size() + " 首");
        cnt.setTextSize(13);
        Button btnAll = new Button(this);
        btnAll.setText("一键下载全部");
        btnAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                downloadAll();
            }
        });
        top.addView(cnt, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(btnAll);
        resultArea.addView(top);

        for (final NcmApi.Song s : songs) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(3), 0, dp(3));
            TextView name = new TextView(this);
            name.setText(s.displayName() + (s.isFree() ? "" : " [VIP]"));
            name.setTextSize(12);
            Button btnD = new Button(this);
            btnD.setText("下");
            btnD.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    downloadSong(s);
                }
            });
            Button btnP = new Button(this);
            btnP.setText("播");
            btnP.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    playOnline(s);
                }
            });
            row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(btnD, new LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.WRAP_CONTENT));
            row.addView(btnP, new LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.WRAP_CONTENT));
            resultArea.addView(row);
        }
    }

    // ==================== 下载 ====================
    private void downloadSong(final NcmApi.Song s) {
        setStatus("下载中: " + s.name);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final String fname = NcmApi.sanitize(s.artist) + " - " + NcmApi.sanitize(s.name) + ".mp3";
                    NcmApi.downloadWithFallback(MainActivity.this, String.valueOf(s.id), currentBr, getCookie(), fname);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            setStatus("✅ 已保存: " + fname);
                            toast("已保存到 Music/NCM自动下载/");
                            refreshDownloaded();
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

    private void downloadAll() {
        if (currentSongs.size() == 0) {
            toast("请先解析歌单");
            return;
        }
        setStatus("批量下载 " + currentSongs.size() + " 首...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                int ok = 0, fail = 0, skip = 0;
                final String cookie = getCookie();
                for (final NcmApi.Song s : currentSongs) {
                    if (!s.isFree()) {
                        skip++;
                        continue;
                    }
                    try {
                        final String fname = NcmApi.sanitize(s.artist) + " - " + NcmApi.sanitize(s.name) + ".mp3";
                        NcmApi.downloadWithFallback(MainActivity.this, String.valueOf(s.id), currentBr, cookie, fname);
                        ok++;
                    } catch (Throwable e) {
                        fail++;
                    }
                }
                final int fok = ok, ffail = fail, fskip = skip;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setStatus("批量完成: 成功 " + fok + "，跳过VIP " + fskip + "，失败 " + ffail);
                        toast("批量完成: 成功 " + fok + "，跳过VIP " + fskip + "，失败 " + ffail);
                        refreshDownloaded();
                    }
                });
            }
        }).start();
    }

    // ==================== 播放 ====================
    private void playOnline(final NcmApi.Song s) {
        setStatus("获取播放链接...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String url = NcmApi.fetchPlayUrl(String.valueOf(s.id), currentBr, getCookie());
                    if (url == null) throw new Exception("无播放链接(需VIP/付费，请先登录)");
                    final String u = url;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            playUri(Uri.parse(u), s.displayName());
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            setStatus("❌ 播放失败: " + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void playUri(Uri uri, String displayName) {
        try {
            stopPlay();
            mp = new MediaPlayer();
            mp.setDataSource(this, uri);
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    mp.start();
                }
            });
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    setStatus("播放完成");
                }
            });
            mp.prepareAsync();
            nowPlaying = displayName;
            tvNow.setText(displayName);
            btnPlay.setText("⏸");
            setStatus("▶ 播放中: " + displayName);
        } catch (Throwable t) {
            setStatus("❌ 播放失败: " + t.getMessage());
        }
    }

    private void togglePlay() {
        if (mp == null) {
            toast("请先选择要播放的歌曲");
            return;
        }
        try {
            if (mp.isPlaying()) {
                mp.pause();
                btnPlay.setText("▶");
                setStatus("⏸ 已暂停: " + nowPlaying);
            } else {
                mp.start();
                btnPlay.setText("⏸");
                setStatus("▶ 播放中: " + nowPlaying);
            }
        } catch (Throwable t) {
            toast("播放器错误: " + t.getMessage());
        }
    }

    private void stopPlay() {
        try {
            if (mp != null) {
                mp.stop();
                mp.release();
            }
        } catch (Throwable ignored) {
        }
        mp = null;
        nowPlaying = "";
        btnPlay.setText("▶");
        tvNow.setText("未播放");
    }

    // ==================== 我的下载 ====================
    private void refreshDownloaded() {
        downloadedArea.removeAllViews();
        final List<String[]> list = NcmApi.queryDownloaded(this);
        if (list.size() == 0) {
            TextView empty = new TextView(this);
            empty.setText("（暂无下载，解析链接后点击下载）");
            empty.setTextSize(12);
            downloadedArea.addView(empty);
            return;
        }
        for (final String[] item : list) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = new TextView(this);
            name.setText(item[1]);
            name.setTextSize(12);
            Button btnP = new Button(this);
            btnP.setText("▶");
            btnP.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    playUri(Uri.parse(item[0]), item[1]);
                }
            });
            row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(btnP, new LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.WRAP_CONTENT));
            downloadedArea.addView(row);
        }
    }

    // ==================== 登录设置（可选，VIP下载） ====================
    private void showLoginDialog() {
        final EditText et = new EditText(this);
        et.setHint("粘贴 MUSIC_U（网易云网页版登录后从Cookie获取）");
        et.setText(getCookie());
        et.setTextSize(13);
        new AlertDialog.Builder(this)
                .setTitle("登录设置")
                .setMessage("填写 MUSIC_U 可下载VIP歌曲（留空=匿名仅免费歌曲）")
                .setView(et)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        String mu = et.getText().toString().trim();
                        getSharedPreferences(PREF, MODE_PRIVATE).edit().putString(KEY_MUSICU, mu).apply();
                        toast(mu.length() > 0 ? "已保存登录Cookie" : "已清除Cookie");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String getCookie() {
        String mu = getSharedPreferences(PREF, MODE_PRIVATE).getString(KEY_MUSICU, "");
        if (mu.length() == 0 && curMusicU.length() > 0) mu = curMusicU; // root模块自动同步的Cookie
        return mu.length() > 0 ? "MUSIC_U=" + mu : "";
    }

    // ==================== 工具 ====================
    private void addBr(RadioGroup g, String label, final int br, boolean checked) {
        RadioButton rb = new RadioButton(this);
        rb.setText(label);
        rb.setTextSize(12);
        rb.setId(br);
        rb.setChecked(checked);
        g.addView(rb);
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

    @Override
    protected void onDestroy() {
        stopPlay();
        super.onDestroy();
    }
}