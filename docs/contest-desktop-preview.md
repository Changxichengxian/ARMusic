# ARMusic 电脑版参赛预览

[Qoder CN 独立预览](https://armusic-desktop-preview-pbm9l5a7qld.qoder.zone/) 使用创建站点的 Qoder CN 账号打开。

参赛预览直接运行 ARMusic 的桌面前端，沿用首页、作品详情、顶部播放器和右侧播放面板。在预览构建中，首页会载入两部示例作品并显示首次提示。《夜行支线》的两个版本共用一个播放位置，切换时保留原来的播放或暂停状态。

## 构建并本地验收

在仓库根目录执行以下命令。静态文件输出在 `desktop/dist-contest-preview/`。

```sh
npm ci
npm --workspace desktop run build:contest-preview
npm --workspace desktop exec -- vite preview --outDir dist-contest-preview
```

1. 打开首页，点《夜行支线》进入作品详情并试听。
2. 播放几秒，在右侧切换「毡音键盘」和「高八度钟琴」，确认音乐从原位置继续。
3. 暂停并切回前一个版本，确认音乐保持暂停。
4. 回到首页，点《退潮图志》，确认新曲从头播放。

## 素材

两张海报和三段配乐来自本次参赛的示例曲库，素材来源与生成方式见[参赛素材说明](https://github.com/Changxichengxian/ARMusic/blob/3531d91f014b1f6c74aff7ae6f044e58ff966ece/contest-web/%E4%BA%A4%E6%8E%A5%E4%B8%8E%E7%B4%A0%E6%9D%90%E6%9D%A5%E6%BA%90.md)。示例曲库由 `contest-preview` 构建模式启用，默认桌面构建按原流程加载用户曲库。
