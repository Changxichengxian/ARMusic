# ARMusic

ARMusic 是一个基于 LMusic 改造的音乐播放器项目，目标是同时做好 Android 版和桌面版，并让两边通过局域网同步音乐库、歌词、封面和播放数据。

现在先走局域网同步，不做 USB 优先方案。这样日常用起来少一步插线，也更适合桌面端和 Android 端在同一个网络里自动发现。

## 当前目录

```text
ARMusic/
  android/                 # 基于 LMusic 的 Android 工程
  desktop/                 # 桌面版播放器骨架，当前是 React + Vite
  docs/                    # 同步协议、路线图和安卓改造说明
  tools/legacy-apk-packager # 旧的 APK 打包工具，先归档保留
```

`阿婆的音乐/` 是本地测试素材，已加入忽略列表，不进 Git。

## 第一阶段目标

1. 桌面版能打开音乐库、播放本地歌曲，并维护自己的歌曲清单。
2. Android 版保留 LMusic 的播放器体验，补上内置标签编辑和局域网同步入口。
3. 两边在同一局域网内互相发现，先同步“新增歌曲”，不自动删除文件。
4. 每首歌生成稳定的 `syncId`，避免只靠文件名判断同一首歌。
5. 播放历史先沿用现有统计，后续再把推荐做明显。

## 开发入口

桌面版：

```powershell
cd desktop
npm install
npm run app:dev
```

只看网页界面时可以用 `npm run dev`。

Android 版：

```powershell
.\scripts\android-debug-build.ps1
```

这个脚本会先写好 `android/local.properties`，有本机 JDK 21 时自动切过去，再临时把项目映射到纯英文盘符编译。`lmedia` 里有 native 编译，直接在中文路径下跑 Ninja 容易失败。

如果已经在纯英文路径下，也可以手动运行：

```powershell
cd android
.\gradlew.bat assembleDebug
```

## 当前状态

- Android 工程已整理到 `android/`。
- `lmedia` 已补到和当前 LMusic 更匹配的版本，用来恢复媒体扫描、标签读取和歌词写入能力。
- Android Gradle wrapper 已切到本机可用的 Gradle 8.9，项目加载已跑通。
- Android debug APK 已能编译通过，输出在 `android/app/build/outputs/apk/debug/app-debug.apk`。
- 桌面版已经有 Electron 外壳、本地音乐文件夹扫描、基础播放和局域网清单服务。
- Android 设置里已经有“局域网同步”入口，可以手输桌面端地址、对比两边清单，并先把 Android 缺的歌下载到系统音乐目录。
- Android 歌词设置里新增“蓝牙歌词”兼容开关，会把当前歌词行临时作为媒体标题发给蓝牙设备；设备不支持时不会有额外效果。
- 外部“音乐标签”应用的跳转点已定位，后续会改成 ARMusic 内置标签编辑页。

## 近期不做

- 不做 USB 优先同步。
- 不自动删除另一台设备上的歌曲。
- 不急着改 Android 包名，先保证能编译和功能能跑。
