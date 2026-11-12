# Android 改造说明

Android 工程来自 LMusic，当前放在 `android/`。

## 发布 Android 在线更新

1. 用现有的签名构建新版 `armusicPreview`，递增 `versionCode`，并更新 `versionName`。
2. 用 Python 3.11 或更新版本生成发布文件。每个仍支持在线更新的旧版本，都传入当初发布的原始安装包。每个 `--previous` 对应一个旧包。

   ```text
   python scripts/android-release.py --apk <新版安装包> --previous <旧版安装包> --build-tools <Android SDK 的 build-tools/36.1.0> --output <发布目录>
   ```

3. 把输出的安装包、`armusic-android-update.json` 和差异包一起上传到 GitHub Release。用 `android-v<versionName>` 作为标签，发布时使用 `gh release create --latest=false`，让电脑端继续查找自己的最新版本。保留每个版本的原始安装包，以便以后生成跨版本差异包。

手机端可在「设置 → 关于与更新 → 在线更新」下载匹配当前安装包的差异。首次请手动安装 1.6.2 或更新版本。Android 会显示安装确认，第一次可能需要允许 ARMusic 安装应用。

## 已确认的点

- 播放器服务已经接入播放历史统计。
- 详情页里有一个外部“音乐标签”应用入口，包名是 `com.xjcheng.musictageditor`。
- `lmedia` 子模块负责媒体库扫描、标签读取、歌词读取和部分写入能力。
- 当前 Android debug APK 已经能编译通过。项目路径里有中文时，用根目录的 `scripts/android-debug-build.ps1` 编译。
- 局域网同步客户端已放在 `com.lalilu.lmusic.sync`，现在支持访问桌面端 `/health`、`/manifest`、`GET /tracks/{syncId}` 和 `POST /tracks/{syncId}`。
- 设置页已加入“ARMusic 同步”入口，能手输桌面端地址、拉取清单、对比差异，并做新增歌曲互传。
- 歌词设置里已加入“蓝牙歌词”兼容开关。它通过媒体标题转发当前歌词行，属于设备兼容方案，不是标准蓝牙歌词协议。

## 近期要改

1. 把详情页的“音乐标签编辑”按钮改成打开 ARMusic 内置页面。
2. 在 `lmedia` 的 TagLib 封装里补写标签字段：
   - 标题
   - 歌手
   - 专辑
   - 专辑歌手
   - 年份
   - 流派
   - 曲号
   - 封面
3. 完善同步页面：
   - 增加扫码或自动发现。
   - 增加已配对设备。
   - 增加同步前的单曲勾选和确认。
4. 把下载目录改成用户可选，并补下载后的更明确刷新提示。

## 暂时不动

- Android 包名先不改，避免一开始引入额外编译问题。
- 原 LMusic 的播放、歌词、歌单界面先保留。
