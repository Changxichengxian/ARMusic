# ARMusic

ARMusic 是基于 LMusic 1.5.4 改造出来的 Android 音乐播放器，并配有同一视觉风格的 Windows 便携版。手机端保留 LMusic 原本的播放体验，电脑端针对鼠标和大屏重新设计了点击、搜索、歌词、标签编辑与跨端同步逻辑。

当前手机端与电脑端版本统一为 **1.5.0**。两个版本都能检查 GitHub Release；电脑端会在启动后自动检查，也可以在设置中手动检查或直接打开 GitHub 项目。

Android 包名是 `com.armusic`，不会覆盖手机上原来的 LMusic，也会和旧包名的 AR Music 并存。

## 当前主要内容

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
- Windows 电脑版不生成安装程序；真实曲库放在便携程序同目录的 `Music` 文件夹即可自动扫描。
- 电脑端启动后自动检查 GitHub Release，设置中也可以手动检查更新或打开项目页。
- 电脑版支持完整作品曲目、不中断播放的 MP3 标签编辑、歌词排版与定时停止播放。
- USB 调试连接手机后，可以双向同步歌曲、逐次听歌记录和愿望单；同步前会先显示预览，文件冲突默认跳过。

## 给电脑端 agent 的 ADB 入口

ARMusic 现在提供一个面向开发者模式 / USB 调试的轻量导入入口。它的目标是让电脑上的 agent 帮用户做繁琐迁移，比如从网易云、LMusic 或其他播放器整理出播放次数、播放时长、作品名、同曲分组，然后写进 ARMusic。外部 agent 不需要重新编译 ARMusic，也不需要知道源码里的数据库细节，只需要通过 ADB 调用这个入口。

前提：

- 手机已开启开发者模式和 USB 调试。
- ARMusic 已安装，并且至少打开过一次、授予音频权限，让曲库扫描完成。
- 交换文件建议放在 ARMusic 自己的目录：`/sdcard/Android/data/com.armusic/files/agent/`。

先导出当前曲库清单：

```powershell
adb shell am start -n com.armusic/com.lalilu.lmusic.agent.ARMusicAgentActivity --es command export_library
adb pull /sdcard/Android/data/com.armusic/files/agent/armusic-library.json .
adb pull /sdcard/Android/data/com.armusic/files/agent/armusic-agent-result.json .
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
adb push .\armusic-agent-import.json /sdcard/Android/data/com.armusic/files/agent/armusic-agent-import.json
adb shell am broadcast --receiver-foreground -n com.armusic/com.lalilu.lmusic.agent.ARMusicAgentReceiver -a com.armusic.AGENT_COMMAND --es command import_bundle --es path /sdcard/Android/data/com.armusic/files/agent/armusic-agent-import.json
adb pull /sdcard/Android/data/com.armusic/files/agent/armusic-agent-result.json .
```

支持的命令：

- `export_library`：导出 ARMusic 当前曲库清单。
- `export_history`：导出逐次播放记录。
- `export_wishlist` / `import_wishlist`：导出或安全合并愿望单栏目与内容。
- `import_bundle`：一次性导入播放历史、作品和同曲分组。
- `import_history`：只导入播放历史。
- `import_works`：只导入作品。
- `import_groups`：只导入同曲分组。
- `export_backup` / `import_backup`：导出或恢复 ARMusic 自身备份。清理历史和文件交换相关命令由电脑版同步流程自动调用，不建议手动执行。

播放历史默认是追加，不会清空旧数据。重复导入同一份文件时，ARMusic 会按歌曲、开始时间和播放时长跳过明显重复的记录。`playCount` 会转换成播放次数，`playedDurationMs` 会转换成播放总时长；如果没有 `playedDurationMs`，会用歌曲时长乘以 `playCount` 估算。电脑同步使用数据同步前台服务，手机锁屏或息屏后也会继续；命令运行期间通知栏会显示一条低打扰提示，完成后自动消失。

这个入口不会自动读取其他 App 的私有数据。别的播放器如果没有导出能力，仍然需要电脑端 agent 自己通过截图、网页记录、备份文件或用户授权的方式把数据整理出来；ARMusic 负责接收整理后的结果。

## 目录

```text
ARMusic/
  android/       # Android 工程
  desktop/       # Windows 电脑版（React + Tauri）
  docs/          # Android 相关说明
  scripts/       # Android 构建脚本
  tools/         # 旧工具归档
```

## 构建

### Windows 电脑版

安装依赖后，可先在浏览器里预览界面：

```powershell
npm install
npm run desktop:web
```

启动完整电脑应用：

```powershell
npm run desktop:dev
```

生成不带命令行窗口的 Windows 便携版：

```powershell
npm run desktop:package
```

便携版输出在 `desktop/src-tauri/target/release/armusic.exe`，不生成安装程序。发布时把真实曲库放在 EXE 同目录的 `Music` 文件夹，ARMusic 启动后会自动扫描；没有真实歌曲时只显示空状态，不再内置演示曲库。

当前整理好的版本位于 `artifacts/ARMusic-Desktop-1.5.0-Portable/`，里面是便携程序和从手机同步的 343 首真实歌曲，没有安装版和演示曲库。电脑版优先读取音频文件内嵌的标题、歌手、专辑、作品、封面与歌词；只有文件确实没有封面时才绘制 ARMusic 的彩色圆线艺术封面。逐次播放记录保存在 `Music/.armusic-history.json`，播放器只累计音频实际前进的时间，不会为了统计时长反复改写 MP3。

电脑版使用固定左侧导航、中间曲库和常驻底部播放器。搜索入口在侧边栏，更新曲库放在设置中；空白搜索页是方形封面的纵向海报墙，滚轮会平滑控制鼠标所在的一列。右上角可以把海报墙扩展到整个内容区，旁边的悬浮小窗能实时调整海报大小、速度，以及全部向上、全部向下或相邻列反向滚动。中央直接平滑滚动当前歌词。歌词翻译显示在主行下方，并可单独选择系统、圆体、宋体或等宽字体，切换左、中、右对齐，按 0.5 秒调整前后 5 秒时间偏移，以及设置 15、30、60 分钟或本曲结束后停止。手动滚动歌词时会暂停自动跟随，5 秒无操作，或鼠标离开歌词区后在外面点击，都会回到当前高亮行。设置支持简体中文、繁体中文、日语和英语。

作品页会列出作品内的全部歌曲，“播放全部”、上一首和下一首都使用该作品的完整队列。电脑版可以新建、改名和删除歌单，也能在不打断播放的情况下添加或移除歌曲；歌单保存在 `Music/.armusic-playlists.json`。歌曲列表、作品、历史、歌单和海报墙等位置都可以右键歌曲直接进入标签编辑。标签编辑器可修改 MP3 内嵌的标题、歌手、专辑、作品、同曲组、流派、日期、封面和歌词，打开与保存时不会暂停正在播放的歌曲；替换前会校验音频与未编辑标签。

USB 调试连接手机后，可先预览再双向同步歌曲、歌单、逐条听歌记录和愿望单。同步在手机后台串行执行，锁屏和息屏不会挂起命令，也不会弹出透明界面抢焦点；运行期间只显示一条低打扰通知。歌单会保留名称、说明、歌曲顺序及双方新加入的歌曲；只有用户明确删除的整张歌单或歌单内歌曲才会把删除同步到另一端，删除记录会留在同步文件中，避免旧副本复活。愿望单按并集合并，两端新加的栏目和内容都会保留，不会自动删除；歌曲文件冲突默认跳过，必须手动指定保留哪一端。听歌时间默认在手机和电脑两边都保留；“只保留在电脑”的模式需要双重确认，且只有电脑完整保存、快照一致和手机本地备份都通过后才允许清空手机记录。跨端歌曲同步目前限定为时长至少 15 秒的 MP3，当前 343 首全部在范围内；其他格式仍可本地播放。局域网兼容方式不再广播配对信息，需要手动输入电脑显示的完整临时地址，并且只允许双方保留听歌记录；清空手机历史只能从电脑版 USB 面板发起。

点窗口关闭按钮默认只是缩到 Windows 系统托盘，播放不会中断。点击托盘图标会显示无外圈透明阴影的极简播放器，可查看封面、歌名和歌手，控制上一首、播放或暂停、下一首，也可以在底边进度条跳转或彻底退出。Windows 任务栏图标会同步显示当前歌曲的播放进度。设置中可以关闭“关闭时缩到系统托盘”，也可以开启登录 Windows 后在后台启动 ARMusic。桌面端只允许一个实例；再次双击便携程序会唤回原窗口，不会启动第二个播放器。

### Android

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

ARMusic 仍在快速改动中，后续会继续处理视频播放、作品识别准确度、标签搜索准确度和同步体验。
