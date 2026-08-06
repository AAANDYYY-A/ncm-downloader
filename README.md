# NcmDownloader 🎵

网易云音乐下载器（**双模式**：无 root 独立 App + Xposed 自动下载模块）。

## ✨ 双模式

### ① 无 root 模式（所有人可用）
直接安装 App，**粘贴网易云分享链接**即可下载并播放：
- 单曲链接 `https://music.163.com/song?id=xxx` / `163cn.tv` 短链 / 纯 ID
- 歌单链接 `https://music.163.com/playlist?id=xxx` → 一键批量下载全部
- 内置本地播放器：解析结果可直接在线播放，已下载列表一键播放
- 音质可选：128k / 320k / 无损
- 登录设置：粘贴 `MUSIC_U` 后可下载 VIP 歌曲（网易云网页版登录后从 Cookie 获取）

### ② root / Xposed 模式（LSPosed）
在网易云音乐 App 内显示**悬浮窗**，全自动：
- 自动获取当前播放歌曲（Hook `MediaSession.setMetadata`，进程内拦截）
- 自动同步登录 Cookie（读取 `cm_cookie_storage.xml` 的 `MUSIC_U`，VIP 可下）
- 悬浮窗选音质、下载当前歌曲、输入歌单 ID 一键批量下载
- 可选：切歌自动下载到 `Music/NCM自动下载/`

## 📦 使用

1. 环境要求：Android 10+（root 模式需 [LSPosed](https://github.com/LSPosed/LSPosed)）
2. 安装 `release/ncm-downloader-v1.7.apk`
3. 无 root：打开 App → 粘贴分享链接 → 解析 → 下载/播放
4. root：LSPosed 启用模块，作用域勾选网易云音乐 → 重启网易云，悬浮窗自动出现

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
