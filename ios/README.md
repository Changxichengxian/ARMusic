# ARMusic iOS

这里先放原生 iOS 内核，不做图标和完整界面。现在的重点是把 iPhone/iPad 上最难的几件事跑通：

- 把“文件”App 或分享进来的音乐复制到 ARMusic 自己的沙盒音乐库。
- 给本地歌曲生成和桌面端、Android 端一致的 `syncId`。
- 生成 `/manifest` 同步清单。
- 在局域网里用 UDP 广播发现 ARMusic 桌面端。
- 通过现有 `/health`、`/manifest`、`/tracks/{syncId}` 接口下载和上传歌曲。
- 配好 `AVAudioSession`，给后台播放和蓝牙输出留好位置。

## 现在有什么

核心 Swift Package 在：

```text
ios/ARMusicIOSCore
```

主要入口：

```swift
let kernel = try ARMusicIOSKernel(deviceName: "我的 iPhone")
let imported = try kernel.importFiles(urls)
let peers = await kernel.discoverPeers()
let result = try await kernel.planSync(with: peers[0].baseUrl)
try await kernel.syncEngine.downloadMissingTracks(
    from: peers[0].baseUrl,
    tracks: result.plan.download
)
```

## iOS 工程要配的权限

真正接进 Xcode 的 iOS App 后，Info.plist 至少要加这些：

- `NSLocalNetworkUsageDescription`：局域网发现和同步会触发这个权限。
- `NSAppTransportSecurity / NSAllowsLocalNetworking = true`：现在电脑端同步服务是局域网 HTTP。
- `UIBackgroundModes = audio`：后台播放需要。
- `LSSupportsOpeningDocumentsInPlace = true`：方便从“文件”App 导入。

示例文件在：

```text
ios/ARMusicIOSCore/Info.plist.example
```

## 重要限制

iOS 版不会像 Android 一样扫描整台手机。它只能管理 ARMusic 自己沙盒里的音乐，或者用户明确从“文件”App/分享面板授权给 ARMusic 的文件。导入后，ARMusic 会复制一份到自己的目录，之后播放、同步、写标签都围绕这份文件做。

这反而适合同步：桌面端传来的歌也直接进入 ARMusic 自己的音乐库，不会污染系统别的目录。
