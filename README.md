# ARMusic

ARMusic 是基于 LMusic 1.5.4 改造出来的 Android 音乐播放器。当前版本重点是手机端：保留 LMusic 原本的播放体验，同时加入内置标签编辑、作品整理、同曲分组、播放统计、愿望单和更适合动漫音乐库的搜索整理方式。

Android 包名是 `com.lalilu.lmusic.armusic`，不会覆盖手机上原来的 LMusic。

## 1.2.1 主要内容

- 内置音乐标签编辑，不再依赖外部“音乐标签”应用。
- 标签编辑支持联网搜索歌曲信息、歌词和封面，先预览，保存后才写入音乐文件。
- 把“专辑”的使用方式迁移到“作品”，更适合动漫、游戏、影视音乐整理。这个入口只是界面叫法和整理方式的变化，设置里可以在“专辑 / 番剧 / 作品”之间切换；如果你本来就是按专辑听歌，切回“专辑”也能正常用。
- 支持同曲分组，同一首歌的不同版本可以合并统计播放次数和播放时长。
- 增加播放记录过滤、播放次数排序、播放时长排序和历史统计导入。
- 搜索支持歌曲、作品、艺术家和歌词。
- 搜索空白页加入随机封面瀑布流，点按播放，长按进详情。
- 每日推荐默认缓慢横向滚动，可在标题右侧关闭或重新开启。
- 支持屏蔽指定文件夹，避免电话录音等音频混进音乐库。
- 增加可自定义栏目的愿望单，默认只有“准备听”，可以自己添加动漫、漫画、小说或别的栏目。

## 给电脑端 agent 的 ADB 入口

ARMusic 现在提供一个面向开发者模式 / USB 调试的轻量导入入口。它的目标是让电脑上的 agent 帮用户做繁琐迁移，比如从网易云、LMusic 或其他播放器整理出播放次数、播放时长、作品名、同曲分组，然后写进 ARMusic。外部 agent 不需要重新编译 ARMusic，也不需要知道源码里的数据库细节，只需要通过 ADB 调用这个入口。

前提：

- 手机已开启开发者模式和 USB 调试。
- ARMusic 已安装，并且至少打开过一次、授予音频权限，让曲库扫描完成。
- 交换文件建议放在 ARMusic 自己的目录：`/sdcard/Android/data/com.lalilu.lmusic.armusic/files/agent/`。

先导出当前曲库清单：

```powershell
adb shell am start -n com.lalilu.lmusic.armusic/com.lalilu.lmusic.agent.ARMusicAgentActivity --es command export_library
adb pull /sdcard/Android/data/com.lalilu.lmusic.armusic/files/agent/armusic-library.json .
adb pull /sdcard/Android/data/com.lalilu.lmusic.armusic/files/agent/armusic-agent-result.json .
```

`armusic-library.json` 里会包含 `mediaId`、歌名、歌手、原专辑、作品、同曲分组、时长和文件路径。电脑端 agent 最好优先使用 `mediaId` 匹配歌曲；如果没有 `mediaId`，ARMusic 会退回到文件路径、歌名和歌手匹配，但准确度会低一些。

导入时推荐生成一个 JSON：

```json
{
  "songs": [
    {
      "mediaId": "song-media-id-from-armusic-library",
      "title": "歌曲名",
      "artist": "歌手",
      "work": "作品名",
      "sameSongGroup": "同一首歌的分组名",
      "playCount": 12,
      "playedDurationMs": 2400000
    }
  ]
}
```

然后推到手机并导入：

```powershell
adb push .\armusic-agent-import.json /sdcard/Android/data/com.lalilu.lmusic.armusic/files/agent/armusic-agent-import.json
adb shell am start -n com.lalilu.lmusic.armusic/com.lalilu.lmusic.agent.ARMusicAgentActivity --es command import_bundle --es path /sdcard/Android/data/com.lalilu.lmusic.armusic/files/agent/armusic-agent-import.json
adb pull /sdcard/Android/data/com.lalilu.lmusic.armusic/files/agent/armusic-agent-result.json .
```

支持的命令：

- `export_library`：导出 ARMusic 当前曲库清单。
- `import_bundle`：一次性导入播放历史、作品和同曲分组。
- `import_history`：只导入播放历史。
- `import_works`：只导入作品。
- `import_groups`：只导入同曲分组。

播放历史默认是追加，不会清空旧数据。重复导入同一份文件时，ARMusic 会按歌曲、开始时间和播放时长跳过明显重复的记录。`playCount` 会转换成播放次数，`playedDurationMs` 会转换成播放总时长；如果没有 `playedDurationMs`，会用歌曲时长乘以 `playCount` 估算。

这个入口不会自动读取其他 App 的私有数据。别的播放器如果没有导出能力，仍然需要电脑端 agent 自己通过截图、网页记录、备份文件或用户授权的方式把数据整理出来；ARMusic 负责接收整理后的结果。

## 目录

```text
ARMusic/
  android/       # Android 工程
  docs/          # Android 相关说明
  scripts/       # Android 构建脚本
  tools/         # 旧工具归档
```

## 构建

Windows 下可以直接运行：

```powershell
.\scripts\android-armusic-build.ps1
```

也可以在 `android/` 目录里运行 Gradle：

```powershell
.\gradlew.bat :app:assembleArmusicPreview --no-daemon --console=plain
```

输出 APK：

```text
android/app/build/outputs/apk/armusicPreview/app-armusicPreview.apk
```

## 说明

ARMusic 仍在快速改动中。1.2.1 是当前手机端比较完整的一版，后续会继续处理视频播放、作品识别准确度、标签搜索准确度和同步体验。
