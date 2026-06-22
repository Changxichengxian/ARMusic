# ARMusic Desktop

桌面版播放器骨架，当前使用 React + Vite + Tauri。React 负责界面，Rust 后端负责本地文件夹扫描、播放本地文件和提供局域网同步接口。

## 运行

```powershell
npm install
npm run dev
```

启动电脑应用外壳：

```powershell
npm run app:dev
```

打包成 Windows 可执行文件：

```powershell
npm run app:package
```

打包产物会放在 `src-tauri/target/release/bundle/nsis/`。

## 当前包含

- 音乐库列表的界面骨架。
- 播放控制的本地状态。
- 局域网同步面板。
- 同步计划的数据结构和对比函数。
- 选择本地音乐文件夹并扫描歌曲。
- 启动本机局域网同步服务，提供 `/manifest`、`GET /tracks/{syncId}` 和 `POST /tracks/{syncId}`。

Android 端已经能通过设置页手动连接桌面端，做清单对比和新增歌曲互传。
