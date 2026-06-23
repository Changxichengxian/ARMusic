# ARMusic

ARMusic 是基于 LMusic 1.5.4 改造出来的 Android 音乐播放器。当前版本重点是手机端：保留 LMusic 原本的播放体验，同时加入内置标签编辑、作品整理、同曲分组、播放统计、愿望单和更适合动漫音乐库的搜索整理方式。

Android 包名是 `com.lalilu.lmusic.armusic`，不会覆盖手机上原来的 LMusic。

## 1.2.0 主要内容

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

ARMusic 仍在快速改动中。1.2.0 是当前手机端比较完整的一版，后续会继续处理视频播放、作品识别准确度、标签搜索准确度和同步体验。
