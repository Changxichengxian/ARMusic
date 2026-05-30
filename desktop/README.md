# ARMusic Desktop

桌面版播放器骨架，当前使用 React + Vite + Electron。React 负责界面，Electron 负责本地文件夹扫描、播放本地文件和提供局域网同步接口。

## 运行

```powershell
npm install
npm run dev
```

启动电脑应用外壳：

```powershell
npm run app:dev
```

## 当前包含

- 音乐库列表的界面骨架。
- 播放控制的本地状态。
- 局域网同步面板。
- 同步计划的数据结构和对比函数。
- 选择本地音乐文件夹并扫描歌曲。
- 启动本机局域网同步服务，提供 `/manifest` 和 `/tracks/{syncId}`。

音乐标签编辑和 Android 端实际同步入口还没有接入。
