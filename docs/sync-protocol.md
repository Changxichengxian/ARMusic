# 局域网同步方案

## 目标

ARMusic 的 Android 版和桌面版都维护自己的音乐库。两边在同一局域网下发现彼此后，交换清单，只传缺失或变更的内容。

第一版只做新增同步，不自动删除。

## 设备发现

优先方案：

1. 桌面版启动一个局域网服务，暴露设备名、库版本和同步端口。
2. Android 版在同一网段扫描 ARMusic 服务。
3. 首次连接时显示配对码，用户确认后保存设备指纹。

后续可以加入 mDNS，也就是局域网自动发现服务；第一版可以先用“显示 IP + 扫码/手输地址”跑通。

## 歌曲身份

每首歌生成一个 `syncId`。不要只用文件名，因为两边文件名可能不同。

第一版 `syncId`：

```text
sha256(fileSize + normalizedFileName + first64KB + last64KB)
```

先不依赖文件名本身做匹配，但会把规范化文件名放进哈希，避免两个不同的小文件刚好头尾一致。后面接入真实音频解析后，可以补上 durationMs。

## 清单结构

```json
{
  "libraryId": "desktop-main",
  "deviceName": "ROG-PC",
  "generatedAt": "2026-05-31T02:00:00+08:00",
  "tracks": [
    {
      "syncId": "sha256:...",
      "title": "Song",
      "artist": "Artist",
      "album": "Album",
      "durationSeconds": 240,
      "sizeBytes": 8123456,
      "relativePath": "Artist/Album/Song.mp3",
      "modifiedAt": "2026-05-31T02:00:00.000Z",
      "source": "desktop"
    }
  ]
}
```

## 第一版接口

桌面端现在已经有最小同步服务：

```text
GET /health
GET /manifest
GET /tracks/{syncId}
```

`/manifest` 返回本机清单，`/tracks/{syncId}` 只允许按清单里的歌曲编号读取文件，不提供任意路径访问。

Android 端现在已经有对应的最小客户端：

```text
android/app/src/main/java/com/lalilu/lmusic/sync/ARMusicLanSyncClient.kt
android/app/src/main/java/com/lalilu/lmusic/sync/ARMusicSyncModels.kt
android/app/src/main/java/com/lalilu/lmusic/sync/ARMusicSyncPlanner.kt
```

第一步先支持手输桌面端地址，例如 `192.168.1.20:38689`。客户端会自动补 `http://`，然后读取 `/health`、`/manifest`，再用本地清单和对方清单生成下载、上传、冲突三类结果。

## 同步流程

1. A 请求 B 的清单。
2. A 对比本地清单和 B 的清单。
3. A 列出三类结果：
   - 本地缺失：从 B 下载。
   - 对方缺失：上传给 B。
   - 冲突：同 `syncId` 但标签或路径不同，先保留本地，提示用户。
4. 文件传完后，接收方重新扫描库。

当前已经实现第 1 到第 3 步的数据结构和对比逻辑。下一步要接 Android 页面入口，把“输入地址 -> 读取清单 -> 展示待同步歌曲 -> 下载到用户选择目录”串起来。

## 安全边界

- 首次连接必须用户确认。
- 只暴露 ARMusic 自己的同步接口，不开放任意文件访问。
- 文件写入只允许进入用户选择的音乐库目录。
- 删除同步必须等第二阶段再做，并且默认关闭。
