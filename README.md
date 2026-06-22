# ARMusic

ARMusic 是基于 LMusic 1.5.4 改造出来的音乐播放器项目。`1.0.0` 是 ARMusic 的第一个发布版本，保留 LMusic 原本好用的播放体验，同时开始加入桌面端、局域网同步、内置标签编辑、作品整理和播放统计这些 ARMusic 自己的功能。

这个仓库里现在同时放 Android 端和桌面端。Android 端的包名是 `com.lalilu.lmusic.armusic`，不会覆盖你手机上原来的 LMusic；桌面端已经从 Electron 改成 Tauri。

## 1.0.0 主要内容

- Android 端不覆盖原 LMusic，方便继续把 LMusic 当保底使用。
- 内置音乐标签编辑，不再依赖外部“音乐标签”应用。
- 标签编辑里支持联网搜索歌曲信息、歌词和封面，并先预览，保存后才真正写入音乐文件。
- 把“专辑”使用习惯改成“作品”，更适合动漫歌、游戏歌和同一作品多首歌的整理方式。
- 支持同曲分组，同一首歌的不同版本可以合并统计播放次数和播放时长。
- 播放统计增加播放次数、播放时长排序，并把历史导入数据和新统计相加。
- 搜索支持歌曲、作品、艺术家和歌词。
- 支持屏蔽指定文件夹，减少电话录音这类文件混进音乐库。
- 设置里加入局域网同步入口，桌面端和 Android 端可以在同一网络内交换新增歌曲。
- 桌面端使用 Tauri，负责本地音乐库扫描、基础播放和局域网同步服务。
- 加入准备听、动漫、漫画、小说四类备忘，方便记录之后要补的内容。

## 目录

```text
ARMusic/
  android/                 # 基于 LMusic 1.5.4 改造的 Android 工程
  desktop/                 # Tauri 桌面端
  ios/                     # 原生 iOS 内核，先做导入、播放、局域网同步
  docs/                    # 同步协议、路线图、发布说明
  scripts/                 # Android 和桌面端构建脚本
  tools/legacy-apk-packager # 旧的 APK 打包工具，归档保留
```

`阿婆的音乐/` 是本地测试素材，已经加入忽略列表，不进 Git。

## 开发入口

桌面端开发：

```powershell
cd desktop
npm install
npm run app:dev
```

只看网页界面时可以用：

```powershell
npm run desktop:web
```

Android 端构建：

```powershell
.\scripts\android-armusic-build.ps1
```

这个脚本会写好 `android/local.properties`，有本机 JDK 21 时自动使用它，并临时把项目映射到纯英文盘符编译，避开中文路径导致的 native 编译问题。输出在：

```text
android/app/build/outputs/apk/armusicPreview/app-armusicPreview.apk
```

桌面端 Windows 打包：

```powershell
npm run desktop:package
```

输出在：

```text
desktop/src-tauri/target/release/bundle/nsis/
```

如果已经有 Windows 代码签名证书，可以用：

```powershell
.\scripts\tauri-build-signed.ps1 -CertificateThumbprint "证书指纹"
```

iOS 内核：

```text
ios/ARMusicIOSCore
```

它是一个原生 Swift Package，先处理 iOS 沙盒音乐库、文件导入、局域网发现、同步清单和播放会话。完整 iOS 界面后面再接。

## 发布说明

ARMusic 1.0.0 是从 LMusic 1.5.4 分出来后的第一个 ARMusic 版本。它还不是“完全稳定版”，更像一个可以继续试用和改 bug 的起点。后续会继续处理视频播放、作品识别、同步体验和标签搜索准确度。
