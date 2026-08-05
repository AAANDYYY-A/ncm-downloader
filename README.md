# NcmDownloader 🎵

网易云音乐自动下载 **Xposed 模块**（LSPosed）。

在网易云音乐 App 内显示悬浮窗：实时显示当前播放歌曲，支持自动下载（默认 320k 高音质）、单曲下载、歌单批量下载，VIP 歌曲可跟随登录态下载。

## ✨ 功能

- 🪟 **悬浮窗**：显示当前歌曲 / 音质切换 / 下载按钮 / 歌单批量下载（可拖动、最小化）
- 🔍 **歌曲检测**：Hook `MediaSession.setMetadata`，进程内拦截当前播放歌曲，无需无障碍权限
- ⬇️ **自动下载**：检测到新歌曲自动保存到 `Music/NCM自动下载/`（Android 10+ 走 MediaStore，无需存储权限）
- 🔑 **VIP 支持**：自动读取网易云登录 Cookie（`MUSIC_U`），VIP 歌曲可下载
- 🧩 **跨进程 UI**：歌曲信息通过 ContentProvider 同步给 UI App（`CurrentProvider`）

## 📦 使用

1. 环境要求：Android 10+、[LSPosed](https://github.com/LSPosed/LSPosed)（Zygisk 或 Riru 均可）
2. 安装 `release/ncm-downloader-v1.6.apk`
3. LSPosed 中启用模块，作用域勾选 **网易云音乐**（`com.netease.cloudmusic`）
4. 重启网易云音乐 App，悬浮窗自动出现
5. 网易云内**登录**（用于下载 VIP 歌曲），播放任意歌曲即可自动下载

## 🏗️ 原理

```
MediaSession.setMetadata (Hook)
        │  进程内拦截，无权限问题
        ▼
歌曲 ID / 标题 / 歌手 ──► FloatWindow（悬浮窗实时显示）
        │
        ▼
网易云 API enhance/player/url (携带 MUSIC_U Cookie)
        │
        ▼
播放链接(https) ──► 下载 ──► Music/NCM自动下载/{歌手} - {歌名}.mp3
```

关键实现：

- `NcmModule.java`：入口，Hook `Application.attach` 获取 Context、注册 Activity 生命周期回调（悬浮窗启动不依赖 hook 时机）、Hook `MediaSession.setMetadata`
- `FloatWindow.java`：TYPE_APPLICATION_OVERLAY 悬浮窗，可拖动，AppOps 悬浮窗权限由模块内 Hook 放行
- `Downloader.java`：获取真实播放链接并下载（携带 Referer 与 Cookie）
- `CurrentProvider.java`：跨进程歌曲信息同步
- `MainActivity.java`：UI App 主界面（可选安装）

## 🔧 构建

需要：JDK 8+、Android SDK（android.jar）、Android build-tools（dx / zipalign）、Python3。

```bash
./build.sh          # 输出 release/ncm-downloader.apk
```

脚本会依次：编译 stub → 编译模块源码（Xposed API 仅编译期引用，不打包进 dex）→ dx 生成 classes.dex → 重新打包（resources.arsc 4 字节对齐）→ 签名。

## 📄 License

MIT License
