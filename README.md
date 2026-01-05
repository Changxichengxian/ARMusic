# Elder Music EXE

Windows 桌面工具：选择本地歌曲/歌词，一键生成内置资源的离线音乐 APK，方便远程给长辈安装。

## 产品目标
- 零配置：APK 内置歌曲与歌词，安装即可离线播放（Android 端不启用联网歌词）。
- 简操作：大按钮、一键生成、可选默认音量/循环模式。
- 离线安全：不依赖网络，不修改手机存储。

## 技术路线（推荐方案）
- 桌面端：Python 3.10+ + Qt（PySide6 或 PyQt6），打包用 PyInstaller 输出 EXE（动态链接 Qt 符合 LGPL 常规要求）。
- Android 打包：调用已有 LMusic 工程的 Gradle Wrapper（无需额外安装 Gradle），JDK 17，Android SDK 已安装。
- 资产策略：将音频/歌词/封面放入 `app/src/main/assets/` 下，并生成 `songs.json` 供 App 读取；Gradle 设置 `noCompress` 避免音频被压缩。

## 目录建议
```
elder-music-exe/
  README.md
  .gitignore
  app/               # 桌面端代码（Python + Qt）
    main.py          # 入口（启动 Qt UI）
    ui.py            # Qt 窗口：选目录、补歌词、生成 assets、打包
    packer.py        # 生成 songs.json + 复制资产 + 调用 Gradle
    lyrics_fetcher.py# 在线歌词抓取（仅 EXE 使用，App 不联网）
    config.py        # 默认路径/缓存
  dist/              # PyInstaller 输出 EXE（构建后出现）
```

## 开发流程
1) 准备环境  
   - 安装 Python 3.10+，确认 `python --version`。  
   - 安装依赖：`pip install pyside6 requests`（如用 PyQt 选 `pyqt6`，后续如需 zip/7z 可按需补充）。  
   - 确保本机有 JDK 17、Android SDK，LMusic 工程可用 `./gradlew assembleDebug` 正常构建。

2) 配置路径  
   - 在 `config.py` 中保存：LMusic 工程根路径（如 `.../lmusic/LMusic-main/LMusic-main`）、默认歌曲目录、输出 APK 存放目录。

3) 生成资源  
   - 运行 `python app/main.py` 打开 UI，选择歌曲/歌词/封面目录（支持 mp3/flac/ape）。  
   - 可选：点击“在线补歌词”调用 lrclib.net 补齐缺失 `.lrc`（仅 EXE 用，APK 仍离线）。  
   - 点击“生成 songs.json 并复制资产”写入 `app/src/main/assets`，曲目示例：  
     ```json
     {
       "title": "童年",
       "artist": "罗大佑",
       "durationMs": 256000,
       "audio": "asset:///music/tongnian.mp3",
       "lyric": "asset:///lyrics/tongnian.lrc",
       "cover": "asset:///covers/tongnian.jpg"
     }
     ```
   - 将音频、歌词、封面分别复制到 LMusic 的 `app/src/main/assets/{music,lyrics,covers}`。

4) 调整 Gradle（一次性）  
   - 在 LMusic `app/build.gradle.kts` 加入 `aaptOptions { noCompress += listOf("mp3","flac","ape") }`（如尚未配置）。  
   - 确保 `AssetDataSource` 播放 `asset:///` 路径逻辑已存在（如需则后续开发）。

5) 调用打包  
   - Debug 出包：调用 LMusic 的 `./gradlew assembleDebug`。  
   - Release 出包：如有 `keystore.properties` 则用 `./gradlew assembleRelease`，否则 fallback debug 签名。  
   - 桌面程序在 UI 上显示日志，并在完成后弹出 APK 路径。

6) 打包 EXE（桌面端）  
   - 命令：`pyinstaller -F -w app/main.py -n elder-music.exe`。  
   - 输出 EXE 放在 `dist/`，可压缩后分发。

## 后续迭代点
- UI：添加进度条、错误提示、日志窗口；可选一键清理旧资源。
- 元数据：自动读取标签（标题/歌手/封面），减少手填。
- 校验：生成前检查文件名配对（音频-歌词-封面）。
- 更新：支持保存配置、默认路径、最近使用列表。

## 开发里程碑（建议）
- Milestone 1：命令行版脚本（无 GUI）跑通资源生成 + Gradle 打包。
- Milestone 2：简单 GUI（选择文件夹 + 日志输出），打包成 EXE。
- Milestone 3：完善校验、进度、错误提示，优化 Release 流程。
