import type { ReactNode } from "react";
import { useEffect, useMemo, useRef, useState } from "react";
import { Icon } from "./components/Icon";
import { localTracks, peers, remoteTracks } from "./data/sampleLibrary";
import { buildSyncPlan, createManifest, formatBytes, formatDuration } from "./lib/sync";
import type { SyncServerStatus, Track } from "./types";

type ScanState = "idle" | "scanning" | "done" | "error";
type SyncState = "idle" | "checking" | "ready";

function App() {
  const audioRef = useRef<HTMLAudioElement>(null);
  const bridge = window.armusic;
  const [tracks, setTracks] = useState<Track[]>(localTracks);
  const [selectedTrack, setSelectedTrack] = useState<Track>(localTracks[0]);
  const [libraryFolder, setLibraryFolder] = useState<string>("");
  const [searchText, setSearchText] = useState("");
  const [isPlaying, setIsPlaying] = useState(false);
  const [scanState, setScanState] = useState<ScanState>("idle");
  const [scanMessage, setScanMessage] = useState("");
  const [syncState, setSyncState] = useState<SyncState>("idle");
  const [syncStatus, setSyncStatus] = useState<SyncServerStatus>({
    running: false,
    port: null,
    addresses: [],
  });

  useEffect(() => {
    if (!bridge) {
      return;
    }

    Promise.all([bridge.getLibraryState(), bridge.getSyncStatus()])
      .then(([library, status]) => {
        if (library.tracks.length > 0) {
          setTracks(library.tracks);
          setSelectedTrack(library.tracks[0]);
        }
        setLibraryFolder(library.folderPath ?? "");
        setSyncStatus(status);
      })
      .catch((error) => {
        setScanState("error");
        setScanMessage(error instanceof Error ? error.message : "读取桌面端状态失败");
      });
  }, [bridge]);

  const filteredTracks = useMemo(() => {
    const keyword = searchText.trim().toLowerCase();
    if (!keyword) {
      return tracks;
    }

    return tracks.filter((track) =>
      [track.title, track.artist, track.album, track.relativePath]
        .join(" ")
        .toLowerCase()
        .includes(keyword),
    );
  }, [searchText, tracks]);

  const syncPlan = useMemo(() => {
    const localManifest = createManifest("ARMusic Desktop", tracks);
    const remoteManifest = createManifest("ARMusic Android", remoteTracks);
    return buildSyncPlan(localManifest, remoteManifest);
  }, [tracks]);

  const totalListenHours = Math.round(tracks.reduce((sum, track) => sum + track.playSeconds, 0) / 360) / 10;

  async function chooseMusicFolder() {
    if (!bridge) {
      setScanState("error");
      setScanMessage("当前是网页预览。运行 Electron 版后才能扫描本地音乐文件夹。");
      return;
    }

    setScanState("scanning");
    setScanMessage("正在扫描音乐文件夹");

    try {
      const result = await bridge.chooseMusicFolder();
      if (result.canceled) {
        setScanState("idle");
        setScanMessage("");
        return;
      }

      setTracks(result.tracks);
      setSelectedTrack(result.tracks[0] ?? localTracks[0]);
      setLibraryFolder(result.folderPath ?? "");
      setScanState("done");
      setScanMessage(`已扫描 ${result.tracks.length} 首歌`);
      setIsPlaying(false);
    } catch (error) {
      setScanState("error");
      setScanMessage(error instanceof Error ? error.message : "扫描失败");
    }
  }

  async function toggleSyncServer() {
    if (!bridge) {
      setSyncState("ready");
      return;
    }

    setSyncState("checking");
    const nextStatus = syncStatus.running ? await bridge.stopSyncServer() : await bridge.startSyncServer();
    setSyncStatus(nextStatus);
    setSyncState("ready");
  }

  async function togglePlay() {
    const audio = audioRef.current;

    if (!selectedTrack.playUrl || !audio) {
      setIsPlaying((value) => !value);
      return;
    }

    if (isPlaying) {
      audio.pause();
      setIsPlaying(false);
      return;
    }

    try {
      await audio.play();
      setIsPlaying(true);
    } catch {
      setScanState("error");
      setScanMessage("这首歌暂时播放不了，可能是文件路径失效或格式不支持。");
    }
  }

  function selectTrack(track: Track) {
    setSelectedTrack(track);
    setIsPlaying(false);
  }

  return (
    <main className="app-shell">
      <audio ref={audioRef} src={selectedTrack.playUrl} onEnded={() => setIsPlaying(false)} />

      <aside className="sidebar">
        <div className="brand">
          <div className="brand-mark">AR</div>
          <div>
            <h1>ARMusic</h1>
            <p>{bridge ? "桌面端" : "网页预览"} · 局域网同步</p>
          </div>
        </div>

        <nav className="nav-list">
          <button className="nav-item active">
            <Icon name="library" />
            音乐库
          </button>
          <button className="nav-item">
            <Icon name="server" />
            同步
          </button>
          <button className="nav-item">
            <Icon name="shield" />
            标签
          </button>
        </nav>

        <section className="peer-card">
          <div className="section-title">
            <Icon name="android" />
            已配对设备
          </div>
          {peers.map((peer) => (
            <div className="peer-row" key={peer.id}>
              <div>
                <strong>{peer.name}</strong>
                <span>{peer.address}</span>
              </div>
              <em>{peer.lastSeen}</em>
            </div>
          ))}
        </section>

        <section className="peer-card">
          <div className="section-title">
            <Icon name="server" />
            本机同步服务
          </div>
          <div className="server-state">
            <strong>{syncStatus.running ? "已启动" : "未启动"}</strong>
            {syncStatus.addresses.length > 0 ? (
              syncStatus.addresses.map((address) => <span key={address}>{address}</span>)
            ) : (
              <span>选择音乐文件夹后可给 Android 访问清单。</span>
            )}
          </div>
        </section>
      </aside>

      <section className="content">
        <header className="topbar">
          <div>
            <h2>音乐库</h2>
            <p>{libraryFolder || "先选择电脑上的音乐文件夹，桌面端会生成可同步清单。"}</p>
          </div>
          <div className="search-box">
            <Icon name="search" size={17} />
            <input
              value={searchText}
              placeholder="搜索歌曲、歌手、专辑"
              onChange={(event) => setSearchText(event.target.value)}
            />
          </div>
        </header>

        <section className="summary-grid">
          <article className="summary-card">
            <span>本机歌曲</span>
            <strong>{tracks.length}</strong>
            <p>{scanState === "scanning" ? "正在扫描目录。" : "本地文件会生成稳定同步编号。"}</p>
          </article>
          <article className="summary-card">
            <span>累计听歌</span>
            <strong>{totalListenHours}h</strong>
            <p>后面会和 Android 的播放统计合并。</p>
          </article>
          <article className="summary-card">
            <span>待同步</span>
            <strong>{syncPlan.download.length + syncPlan.upload.length}</strong>
            <p>新增互传，不自动删除。</p>
          </article>
        </section>

        <section className="main-grid">
          <article className="library-panel">
            <div className="panel-header">
              <div>
                <h3>本机列表</h3>
                <p>{scanMessage || "选择文件夹后会显示真实歌曲文件。"}</p>
              </div>
              <button className="primary-button" onClick={chooseMusicFolder}>
                <Icon name="folder" size={17} />
                {scanState === "scanning" ? "扫描中" : "选目录"}
              </button>
            </div>

            <div className="track-list">
              {filteredTracks.length === 0 ? (
                <p className="empty-text">没有匹配的歌曲。</p>
              ) : (
                filteredTracks.map((track) => (
                  <button
                    className={`track-row ${selectedTrack.syncId === track.syncId ? "selected" : ""}`}
                    key={track.syncId}
                    onClick={() => selectTrack(track)}
                  >
                    <span className="track-index">
                      {track.durationSeconds > 0 ? formatDuration(track.durationSeconds) : "本地"}
                    </span>
                    <span className="track-main">
                      <strong>{track.title}</strong>
                      <em>
                        {track.artist} · {track.album}
                      </em>
                    </span>
                    <span className="track-meta">{formatBytes(track.sizeBytes)}</span>
                  </button>
                ))
              )}
            </div>
          </article>

          <article className="sync-panel">
            <div className="panel-header">
              <div>
                <h3>局域网同步</h3>
                <p>桌面端提供清单、文件读取和接收 Android 上传。</p>
              </div>
              <button className="primary-button" onClick={toggleSyncServer}>
                <Icon name="refresh" size={17} />
                {syncStatus.running ? "停止" : "启动"}
              </button>
            </div>

            <div className="sync-status">
              <Icon name="success" size={20} />
              <div>
                <strong>
                  {syncState === "idle" && (syncStatus.running ? "同步服务已启动" : "等待启动同步服务")}
                  {syncState === "checking" && "正在切换同步服务"}
                  {syncState === "ready" && (syncStatus.running ? "Android 可以访问电脑清单" : "同步服务已停止")}
                </strong>
                <span>
                  {syncStatus.running
                    ? "同一局域网内访问 /manifest 可获取本机歌曲清单。"
                    : "启动后会监听局域网地址。"}
                </span>
              </div>
            </div>

            <div className="sync-list">
              <SyncBlock
                icon={<Icon name="download" />}
                title="从 Android 下载"
                tracks={syncPlan.download}
              />
              <SyncBlock icon={<Icon name="upload" />} title="上传到 Android" tracks={syncPlan.upload} />
            </div>
          </article>
        </section>
      </section>

      <footer className="player-bar">
        <button
          className="play-button"
          title={isPlaying ? "暂停" : "播放"}
          onClick={togglePlay}
        >
          {isPlaying ? <Icon name="pause" size={22} /> : <Icon name="play" size={22} />}
        </button>
        <div className="now-playing">
          <strong>{selectedTrack.title}</strong>
          <span>
            {selectedTrack.artist} · {selectedTrack.album}
          </span>
        </div>
        <div className="progress">
          <span>{selectedTrack.playUrl ? "本地文件" : "演示"}</span>
          <div className="progress-line">
            <i />
          </div>
          <span>{selectedTrack.durationSeconds > 0 ? formatDuration(selectedTrack.durationSeconds) : "--:--"}</span>
        </div>
      </footer>
    </main>
  );
}

interface SyncBlockProps {
  icon: ReactNode;
  title: string;
  tracks: Track[];
}

function SyncBlock({ icon, title, tracks }: SyncBlockProps) {
  return (
    <section className="sync-block">
      <div className="sync-block-title">
        {icon}
        <strong>{title}</strong>
        <span>{tracks.length}</span>
      </div>
      {tracks.length === 0 ? (
        <p className="empty-text">没有新增歌曲。</p>
      ) : (
        tracks.map((track) => (
          <div className="sync-track" key={track.syncId}>
            <strong>{track.title}</strong>
            <span>
              {track.artist} · {formatBytes(track.sizeBytes)}
            </span>
          </div>
        ))
      )}
    </section>
  );
}

export default App;
