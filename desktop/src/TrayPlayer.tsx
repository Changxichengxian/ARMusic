import { Pause, Play, Power, SkipBack, SkipForward } from "lucide-react";
import { type CSSProperties, useCallback, useEffect, useMemo, useState } from "react";
import armusicIcon from "../../android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png";
import type { Language } from "./i18n";

interface TrayPlayerState {
  title: string;
  artist: string;
  coverUrl?: string;
  isPlaying: boolean;
  hasTrack: boolean;
  positionSeconds: number;
  durationSeconds: number;
}

const emptyPlayer: TrayPlayerState = {
  title: "",
  artist: "",
  isPlaying: false,
  hasTrack: false,
  positionSeconds: 0,
  durationSeconds: 0,
};

const trayCopy: Record<Language, {
  empty: string;
  unknownArtist: string;
  open: string;
  controls: string;
  previous: string;
  pause: string;
  play: string;
  next: string;
  quit: string;
  progress: string;
}> = {
  "zh-CN": { empty: "暂无正在播放的歌曲", unknownArtist: "未知艺术家", open: "打开 ARMusic", controls: "播放控制", previous: "上一首", pause: "暂停", play: "播放", next: "下一首", quit: "彻底退出", progress: "播放进度" },
  ja: { empty: "再生中の曲はありません", unknownArtist: "不明なアーティスト", open: "ARMusic を開く", controls: "再生コントロール", previous: "前の曲", pause: "一時停止", play: "再生", next: "次の曲", quit: "完全に終了", progress: "再生位置" },
  en: { empty: "Nothing playing", unknownArtist: "Unknown artist", open: "Open ARMusic", controls: "Playback controls", previous: "Previous", pause: "Pause", play: "Play", next: "Next", quit: "Quit ARMusic", progress: "Playback progress" },
};

function finiteNonNegative(value: number) {
  return Number.isFinite(value) ? Math.max(0, value) : 0;
}

function formatTime(value: number) {
  const seconds = Math.floor(finiteNonNegative(value));
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;
}

function savedLanguage(): Language {
  const value = localStorage.getItem("armusic-language");
  return value === "ja" || value === "en" ? value : "zh-CN";
}

export default function TrayPlayer() {
  const [player, setPlayer] = useState<TrayPlayerState>(emptyPlayer);
  const [ready, setReady] = useState(false);
  const [language, setLanguage] = useState<Language>(savedLanguage);
  const copy = trayCopy[language];
  const duration = finiteNonNegative(player.durationSeconds);
  const position = Math.min(finiteNonNegative(player.positionSeconds), duration || Number.MAX_SAFE_INTEGER);
  const progress = duration > 0 ? Math.min(100, (position / duration) * 100) : 0;
  const progressStyle = useMemo(() => ({ "--tray-progress": `${progress}%` } as CSSProperties), [progress]);

  const refresh = useCallback(async () => {
    if (!window.__TAURI_INTERNALS__) return;
    try {
      const { invoke } = await import("@tauri-apps/api/core");
      const next = await invoke<TrayPlayerState>("get_tray_player_state");
      setPlayer(next.hasTrack ? next : emptyPlayer);
    } finally {
      setReady(true);
    }
  }, []);

  useEffect(() => {
    if (!window.__TAURI_INTERNALS__) {
      setReady(true);
      return;
    }

    let active = true;
    let unlisten: (() => void) | undefined;
    void refresh().catch(() => setReady(true));
    void import("@tauri-apps/api/event")
      .then(({ listen }) => listen<TrayPlayerState>("armusic://tray-player-state", (event) => {
        if (active) setPlayer(event.payload.hasTrack ? event.payload : emptyPlayer);
      }))
      .then((stop) => {
        if (active) unlisten = stop;
        else stop();
      })
      .catch(() => undefined);

    // The poll is only a fallback for a freshly created or resumed WebView.
    const timer = window.setInterval(() => { void refresh().catch(() => undefined); }, 3_000);
    return () => {
      active = false;
      window.clearInterval(timer);
      unlisten?.();
    };
  }, [refresh]);

  useEffect(() => {
    const onStorage = (event: StorageEvent) => {
      if (event.key === "armusic-language") setLanguage(savedLanguage());
    };
    window.addEventListener("storage", onStorage);
    return () => window.removeEventListener("storage", onStorage);
  }, []);

  const action = useCallback(async (name: "previous" | "playPause" | "next" | "showMain" | "quit" | `seek:${number}`) => {
    if (!window.__TAURI_INTERNALS__) return;
    if (name === "playPause" && player.hasTrack) {
      setPlayer((value) => ({ ...value, isPlaying: !value.isPlaying }));
    }
    const { invoke } = await import("@tauri-apps/api/core");
    await invoke("tray_player_action", { action: name });
  }, [player.hasTrack]);

  const seek = useCallback((value: number) => {
    if (!player.hasTrack || duration <= 0) return;
    const next = Math.min(Math.max(value, 0), duration);
    setPlayer((current) => ({ ...current, positionSeconds: next }));
    void action(`seek:${next}`);
  }, [action, duration, player.hasTrack]);

  return (
    <main className={`tray-player ${ready ? "is-ready" : ""}`} data-theme="dark">
      <button className="tray-player__track" onClick={() => void action("showMain")} title={copy.open}>
        <span className="tray-player__cover">
          <img src={player.coverUrl || armusicIcon} alt="" />
        </span>
        <span className="tray-player__copy">
          <strong>{player.title || "ARMusic"}</strong>
          <small>{player.hasTrack ? player.artist || copy.unknownArtist : copy.empty}</small>
        </span>
      </button>

      <nav className="tray-player__controls" aria-label={copy.controls}>
        <button onClick={() => void action("previous")} disabled={!player.hasTrack} aria-label={copy.previous} title={copy.previous}>
          <SkipBack size={16} fill="currentColor" />
        </button>
        <button className="tray-player__play" onClick={() => void action("playPause")} disabled={!player.hasTrack} aria-label={player.isPlaying ? copy.pause : copy.play} title={player.isPlaying ? copy.pause : copy.play}>
          {player.isPlaying ? <Pause size={17} fill="currentColor" /> : <Play size={17} fill="currentColor" />}
        </button>
        <button onClick={() => void action("next")} disabled={!player.hasTrack} aria-label={copy.next} title={copy.next}>
          <SkipForward size={16} fill="currentColor" />
        </button>
      </nav>

      <button className="tray-player__quit" onClick={() => void action("quit")} aria-label={copy.quit} title={copy.quit}>
        <Power size={15} />
      </button>

      <label
        className="tray-player__progress"
        style={progressStyle}
        title={`${copy.progress} · ${formatTime(position)} / ${formatTime(duration)}`}
      >
        <input
          type="range"
          min="0"
          max={Math.max(duration, 1)}
          step="0.1"
          value={Math.min(position, Math.max(duration, 1))}
          disabled={!player.hasTrack || duration <= 0}
          onChange={(event) => seek(Number(event.currentTarget.value))}
          aria-label={copy.progress}
          aria-valuetext={`${formatTime(position)} / ${formatTime(duration)}`}
        />
      </label>
    </main>
  );
}
