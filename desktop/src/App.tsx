import type { ReactNode } from "react";
import { useMemo, useState } from "react";
import { Icon } from "./components/Icon";
import { localTracks, peers, remoteTracks } from "./data/sampleLibrary";
import { buildSyncPlan, createManifest, formatBytes, formatDuration } from "./lib/sync";
import type { Track } from "./types";

function App() {
  const [selectedTrack, setSelectedTrack] = useState<Track>(localTracks[0]);
  const [isPlaying, setIsPlaying] = useState(false);
  const [syncState, setSyncState] = useState<"idle" | "checking" | "ready">("idle");

  const syncPlan = useMemo(() => {
    const localManifest = createManifest("ARMusic Desktop", localTracks);
    const remoteManifest = createManifest("ARMusic Android", remoteTracks);
    return buildSyncPlan(localManifest, remoteManifest);
  }, []);

  const totalListenHours =
    Math.round(localTracks.reduce((sum, track) => sum + track.playSeconds, 0) / 360) / 10;

  return (
    <main className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-mark">AR</div>
          <div>
            <h1>ARMusic</h1>
            <p>局域网音乐同步</p>
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
            <Icon name="phone" />
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
      </aside>

      <section className="content">
        <header className="topbar">
          <div>
            <h2>音乐库</h2>
            <p>先把桌面版的库、同步状态和播放控制立起来。</p>
          </div>
          <div className="search-box">
            <Icon name="search" size={17} />
            <input placeholder="搜索歌曲、歌手、专辑" />
          </div>
        </header>

        <section className="summary-grid">
          <article className="summary-card">
            <span>本机歌曲</span>
            <strong>{localTracks.length}</strong>
            <p>第一版先接入本地目录扫描。</p>
          </article>
          <article className="summary-card">
            <span>累计听歌</span>
            <strong>{totalListenHours}h</strong>
            <p>后面会接 Android 的播放统计。</p>
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
                <p>这里之后接真实本地音乐目录。</p>
              </div>
              <button className="icon-button" title="选择音乐目录">
                <Icon name="folder" />
              </button>
            </div>

            <div className="track-list">
              {localTracks.map((track) => (
                <button
                  className={`track-row ${selectedTrack.syncId === track.syncId ? "selected" : ""}`}
                  key={track.syncId}
                  onClick={() => setSelectedTrack(track)}
                >
                  <span className="track-index">{formatDuration(track.durationSeconds)}</span>
                  <span className="track-main">
                    <strong>{track.title}</strong>
                    <em>
                      {track.artist} · {track.album}
                    </em>
                  </span>
                  <span className="track-meta">{formatBytes(track.sizeBytes)}</span>
                </button>
              ))}
            </div>
          </article>

          <article className="sync-panel">
            <div className="panel-header">
              <div>
                <h3>局域网同步</h3>
                <p>当前是同步计划演示，后面接真实发现和传输。</p>
              </div>
              <button
                className="primary-button"
                onClick={() => {
                  setSyncState("checking");
                  window.setTimeout(() => setSyncState("ready"), 600);
                }}
              >
                <Icon name="refresh" size={17} />
                检查
              </button>
            </div>

            <div className="sync-status">
              <Icon name="success" size={20} />
              <div>
                <strong>
                  {syncState === "idle" && "等待检查"}
                  {syncState === "checking" && "正在对比清单"}
                  {syncState === "ready" && "可以开始同步"}
                </strong>
                <span>同一局域网内配对后交换音乐库清单。</span>
              </div>
            </div>

            <div className="sync-list">
              <SyncBlock
                icon={<Icon name="download" />}
                title="从 Android 下载"
                tracks={syncPlan.download}
              />
              <SyncBlock
                icon={<Icon name="upload" />}
                title="上传到 Android"
                tracks={syncPlan.upload}
              />
            </div>
          </article>
        </section>
      </section>

      <footer className="player-bar">
        <button
          className="play-button"
          title={isPlaying ? "暂停" : "播放"}
          onClick={() => setIsPlaying((value) => !value)}
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
          <span>0:42</span>
          <div className="progress-line">
            <i />
          </div>
          <span>{formatDuration(selectedTrack.durationSeconds)}</span>
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
