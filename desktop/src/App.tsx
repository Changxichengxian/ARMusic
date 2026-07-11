import {
  Album,
  ArrowDown,
  ArrowDownToLine,
  ArrowUp,
  ArrowUpDown,
  ArrowUpFromLine,
  Check,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  Clock3,
  Disc3,
  FolderOpen,
  Github,
  History,
  Home,
  Languages,
  ListMusic,
  ListPlus,
  Maximize2,
  Mic2,
  Minimize2,
  Minus,
  Moon,
  MoreHorizontal,
  Music2,
  PanelRightOpen,
  Pause,
  PencilLine,
  Play,
  RefreshCw,
  Repeat2,
  Search,
  Settings2,
  Shuffle,
  SkipBack,
  SkipForward,
  SlidersHorizontal,
  Smartphone,
  Sparkles,
  Square,
  Sun,
  Trash2,
  UsersRound,
  Volume2,
  VolumeX,
  Wifi,
  X,
  type LucideIcon,
} from "lucide-react";
import {
  type ChangeEvent,
  type CSSProperties,
  type KeyboardEvent as ReactKeyboardEvent,
  type RefObject,
  memo,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { flushSync } from "react-dom";
import {
  currentMonitor,
  getCurrentWindow,
  PhysicalPosition,
  PhysicalSize,
} from "@tauri-apps/api/window";
import armusicIcon from "../../android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png";
import { AdbSyncPanel } from "./components/AdbSyncPanel";
import { CoverArt } from "./components/CoverArt";
import { AddTrackToPlaylistDialog, PlaylistsView } from "./components/PlaylistsView";
import { TagEditor } from "./components/TagEditor";
import { createDesktopBridge } from "./desktopBridge";
import { I18nProvider, languageNames, useI18n, type Language, type MessageKey } from "./i18n";
import {
  formatBytes,
  formatDuration,
  formatListenTime,
  normalizeScannedTracks,
  paletteFor,
  stableNumber,
} from "./lib/music";
import { buildSyncPlan, createManifest } from "./lib/sync";
import { mediaListeningDeltaMs } from "./lib/listeningClock";
import { editTrackFromContextMenu } from "./lib/trackContextMenu";
import { posterLibrarySignature, shuffledPosterBatch } from "./lib/posterQueue";
import { adjacentTrack, queueTracks, workPlaybackQueue, type PlaybackQueue } from "./lib/workQueue";
import {
  getDesktopBehaviorPreferences,
  listenToTrayPlayerActions,
  publishTrayPlayerState,
  saveCloseToTray,
  saveLaunchAtStartup,
} from "./trayBridge";
import type {
  ARMusicBridge,
  InspectorTab,
  SyncServerStatus,
  TagSaveResult,
  Track,
  ViewId,
  WishlistCategoryData,
  WishlistPayload,
} from "./types";
import "./GroupedLibraryView.css";

const mainNavigation: Array<{ id: ViewId; labelKey: MessageKey; icon: LucideIcon }> = [
  { id: "home", labelKey: "nav.home", icon: Home },
  { id: "songs", labelKey: "nav.songs", icon: ListMusic },
  { id: "works", labelKey: "nav.works", icon: Disc3 },
  { id: "artists", labelKey: "nav.artists", icon: UsersRound },
  { id: "history", labelKey: "nav.history", icon: History },
  { id: "playlists", labelKey: "nav.playlists", icon: Album },
  { id: "wishlist", labelKey: "nav.wishlist", icon: Sparkles },
];

const utilityNavigation: Array<{ id: ViewId; labelKey: MessageKey; icon: LucideIcon }> = [
  { id: "sync", labelKey: "nav.sync", icon: RefreshCw },
  { id: "settings", labelKey: "nav.settings", icon: Settings2 },
];

const emptySyncStatus: SyncServerStatus = { running: false, port: null, addresses: [] };
const appVersion = "1.5.0";
const githubProjectUrl = "https://github.com/Changxichengxian/ARMusic";
const githubLatestReleaseApi = "https://api.github.com/repos/Changxichengxian/ARMusic/releases/latest";

type UpdateCheckStatus = "idle" | "checking" | "current" | "available" | "failed";

interface UpdateCheckState {
  status: UpdateCheckStatus;
  latestVersion: string;
  releaseUrl: string;
}

function compareVersionName(left: string, right: string): number {
  const parts = (value: string) => value.trim().replace(/^[vV]/, "").split(/[._-]/).map((part) => Number(part.replace(/\D/g, "")) || 0);
  const leftParts = parts(left);
  const rightParts = parts(right);
  const length = Math.max(leftParts.length, rightParts.length);
  for (let index = 0; index < length; index += 1) {
    const difference = (leftParts[index] ?? 0) - (rightParts[index] ?? 0);
    if (difference) return difference;
  }
  return 0;
}
const audioExtensions = new Set(["mp3", "flac", "wav", "m4a", "aac", "ogg", "ape", "wma"]);

interface LyricLine {
  timeSeconds: number | null;
  text: string;
  translation?: string;
}

type LyricFont = "system" | "yezi" | "pingfang";
type LyricAlignment = "left" | "center" | "right";
type LyricFocusPosition = "upper" | "center" | "lower";
type SleepTimerMode = "off" | "running" | "trackEnd";
type WorkLabelMode = "album" | "series" | "work";
type PosterFlow = "up" | "down" | "alternate";
type SongSortMode = "title" | "artist" | "work" | "album" | "recent" | "recentPlayed" | "listenTime" | "random";
type GroupKind = "work" | "artist";
type GroupSortMode = "name" | "songCount" | "recentPlayed" | "listenTime" | "random";
type GroupTrackSortMode = "title" | "context" | "recentPlayed" | "listenTime" | "random";
type SortDirection = "asc" | "desc";

const lyricFontFamilies: Record<LyricFont, string> = {
  system: '"Segoe UI Variable", "PingFang SC", "Microsoft YaHei UI", system-ui, sans-serif',
  yezi: '"ARMusic YeZi", "Microsoft YaHei UI", "PingFang SC", system-ui, sans-serif',
  pingfang: '"ARMusic PingFang", "PingFang SC", "PingFang TC", "Hiragino Sans GB", "Microsoft YaHei UI", system-ui, sans-serif',
};

const lyricFocusRatios: Record<LyricFocusPosition, number> = {
  upper: 0.3,
  center: 0.5,
  lower: 0.7,
};

function parseLyrics(rawLyrics?: string): LyricLine[] {
  const raw = rawLyrics?.replace(/\r\n?/g, "\n").trim();
  if (!raw) return [];

  const offsetMatch = raw.match(/^\s*\[offset:([+-]?\d+)\]\s*$/im);
  const offsetSeconds = offsetMatch ? Number(offsetMatch[1]) / 1000 : 0;
  const timestampPattern = /\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]/g;
  const metadataPattern = /^\s*\[(?:ar|al|ti|by|re|ve|length|offset):.*\]\s*$/i;
  const timed: LyricLine[] = [];
  const plain: LyricLine[] = [];

  raw.split("\n").forEach((rawLine) => {
    if (metadataPattern.test(rawLine)) return;

    const timestamps = [...rawLine.matchAll(timestampPattern)];
    const text = rawLine
      .replace(timestampPattern, "")
      .replace(/<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>/g, "")
      .trim();
    if (!text) return;

    if (!timestamps.length) {
      plain.push({ timeSeconds: null, text });
      return;
    }

    timestamps.forEach((timestamp) => {
      const minutes = Number(timestamp[1]);
      const seconds = Number(timestamp[2]);
      const fraction = timestamp[3]
        ? Number(timestamp[3].padEnd(3, "0").slice(0, 3)) / 1000
        : 0;
      timed.push({
        timeSeconds: Math.max(0, minutes * 60 + seconds + fraction + offsetSeconds),
        text,
      });
    });
  });

  if (!timed.length) return plain;
  return timed
    .sort((left, right) => (left.timeSeconds ?? 0) - (right.timeSeconds ?? 0))
    .reduce<LyricLine[]>((lines, line) => {
      const previous = lines.at(-1);
      if (previous && previous.timeSeconds !== null && line.timeSeconds !== null && Math.abs(previous.timeSeconds - line.timeSeconds) < 0.02) {
        if (previous.text !== line.text) {
          previous.translation = previous.translation ? `${previous.translation}\n${line.text}` : line.text;
        }
        return lines;
      }
      lines.push(line);
      return lines;
    }, []);
}

type AppStyle = CSSProperties & {
  "--now-a": string;
  "--now-b": string;
  "--now-c": string;
  "--lyric-font": string;
  "--lyric-align": LyricAlignment;
  "--lyric-normal-size": string;
  "--lyric-active-size": string;
  "--lyric-short-size": string;
  "--lyric-short-active-size": string;
  "--lyric-gap": string;
};

interface ActiveListeningSession {
  syncId: string;
  sessionId: string;
  startedAtMs: number;
  listenedMs: number;
  lastTickMs: number;
  lastMediaTimeSeconds: number | null;
  lastCheckpointMs: number;
  basePlaySeconds: number;
}

interface StoredPlaybackState {
  syncId: string;
  relativePath: string;
  positionSeconds: number;
  updatedAt: number;
}

function readStoredPlaybackState(): StoredPlaybackState | null {
  try {
    const parsed = JSON.parse(localStorage.getItem("armusic-playback-state-v1") || "null") as Partial<StoredPlaybackState> | null;
    if (!parsed || typeof parsed.syncId !== "string") return null;
    return {
      syncId: parsed.syncId,
      relativePath: typeof parsed.relativePath === "string" ? parsed.relativePath : "",
      positionSeconds: Number.isFinite(parsed.positionSeconds) ? Math.max(0, Number(parsed.positionSeconds)) : 0,
      updatedAt: Number.isFinite(parsed.updatedAt) ? Number(parsed.updatedAt) : 0,
    };
  } catch {
    return null;
  }
}

function writeStoredPlaybackState(track: Pick<Track, "syncId" | "relativePath">, positionSeconds: number) {
  const state: StoredPlaybackState = {
    syncId: track.syncId,
    relativePath: track.relativePath || "",
    positionSeconds: Math.max(0, positionSeconds),
    updatedAt: Date.now(),
  };
  localStorage.setItem("armusic-playback-state-v1", JSON.stringify(state));
  localStorage.setItem("armusic-last-track-id", track.syncId);
}

function newListeningSessionId(): string {
  return globalThis.crypto?.randomUUID?.()
    ?? `desktop-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

function newRecommendationSeed(): number {
  const values = new Uint32Array(1);
  globalThis.crypto?.getRandomValues?.(values);
  return values[0] || (Date.now() >>> 0);
}

function App() {
  return <I18nProvider><ARMusicApp /></I18nProvider>;
}

function ARMusicApp() {
  const { t } = useI18n();
  const bridge = useMemo(() => createDesktopBridge(), []);
  const audioRef = useRef<HTMLAudioElement>(null);
  const searchRef = useRef<HTMLInputElement>(null);
  const directoryInputRef = useRef<HTMLInputElement>(null);
  const listeningSessionRef = useRef<ActiveListeningSession | null>(null);
  const pendingPlaybackRestoreRef = useRef<{ syncId: string; positionSeconds: number } | null>(null);
  const historyWriteQueueRef = useRef<Promise<unknown>>(Promise.resolve());
  const recommendationSeedRef = useRef(newRecommendationSeed());
  const [view, setView] = useState<ViewId>("home");
  const [tracks, setTracks] = useState<Track[]>([]);
  const [currentTrackId, setCurrentTrackId] = useState("");
  const [featuredTrackId, setFeaturedTrackId] = useState("");
  const [searchText, setSearchText] = useState("");
  const [folderPath, setFolderPath] = useState("");
  const [needsMusicFolder, setNeedsMusicFolder] = useState(true);
  const [isScanning, setIsScanning] = useState(false);
  const [scanMessage, setScanMessage] = useState(() => t("status.noFolder"));
  const [syncStatus, setSyncStatus] = useState<SyncServerStatus>(emptySyncStatus);
  const [isSyncing, setIsSyncing] = useState(false);
  const [isPlaying, setIsPlaying] = useState(false);
  const [position, setPosition] = useState(0);
  const [duration, setDuration] = useState(0);
  const [volume, setVolume] = useState(() => {
    const saved = Number(localStorage.getItem("armusic-volume"));
    return Number.isFinite(saved) ? Math.max(0, Math.min(1, saved)) : 0.72;
  });
  const [isMuted, setIsMuted] = useState(() => localStorage.getItem("armusic-muted") === "true");
  const [playMode, setPlayMode] = useState<"repeat" | "shuffle">(() => localStorage.getItem("armusic-play-mode") === "shuffle" ? "shuffle" : "repeat");
  const [inspectorTab, setInspectorTab] = useState<InspectorTab>("lyrics");
  const [posterWallOpen, setPosterWallOpen] = useState(false);
  const [theme, setTheme] = useState<"light" | "dark">(() => {
    const saved = localStorage.getItem("armusic-theme");
    if (saved === "light" || saved === "dark") return saved;
    return window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
  });
  const [lyricFont, setLyricFont] = useState<LyricFont>(() => {
    const saved = localStorage.getItem("armusic-lyric-font");
    return saved && saved in lyricFontFamilies ? saved as LyricFont : "yezi";
  });
  const [lyricAlignment, setLyricAlignment] = useState<LyricAlignment>(() => {
    const saved = localStorage.getItem("armusic-lyric-alignment");
    return saved === "center" || saved === "right" ? saved : "left";
  });
  const [lyricOffsetMs, setLyricOffsetMs] = useState(() => {
    const saved = Number(localStorage.getItem("armusic-lyric-offset-ms"));
    return Number.isFinite(saved) ? Math.max(-5_000, Math.min(5_000, Math.round(saved / 500) * 500)) : 0;
  });
  const [lyricScale, setLyricScale] = useState(() => {
    const saved = Number(localStorage.getItem("armusic-lyric-scale"));
    return Number.isFinite(saved) ? Math.max(85, Math.min(110, Math.round(saved / 5) * 5)) : 100;
  });
  const [lyricGap, setLyricGap] = useState(() => {
    const saved = Number(localStorage.getItem("armusic-lyric-gap"));
    return Number.isFinite(saved) ? Math.max(7, Math.min(13, Math.round(saved))) : 11;
  });
  const [lyricFocusPosition, setLyricFocusPosition] = useState<LyricFocusPosition>(() => {
    const saved = localStorage.getItem("armusic-lyric-focus-position");
    return saved === "upper" || saved === "lower" ? saved : "center";
  });
  const [sleepTimerMode, setSleepTimerMode] = useState<SleepTimerMode>("off");
  const [sleepTimerDeadline, setSleepTimerDeadline] = useState<number | null>(null);
  const [sleepTimerDefaultSeconds, setSleepTimerDefaultSeconds] = useState(() => {
    const saved = Number(localStorage.getItem("armusic-sleep-timer-seconds"));
    return Number.isFinite(saved) ? Math.max(60, Math.min(86_340, Math.round(saved / 60) * 60)) : 30 * 60;
  });
  const [sleepTimerPauseWhenCompletion, setSleepTimerPauseWhenCompletion] = useState(() => localStorage.getItem("armusic-sleep-timer-finish-track") === "true");
  const [workLabelMode, setWorkLabelMode] = useState<WorkLabelMode>(() => {
    const saved = localStorage.getItem("armusic-work-label-mode");
    return saved === "album" || saved === "series" ? saved : "work";
  });
  const [launchAtStartup, setLaunchAtStartup] = useState(() => localStorage.getItem("armusic-launch-at-startup") === "true");
  const [closeToTray, setCloseToTray] = useState(() => localStorage.getItem("armusic-close-to-tray") !== "false");
  const [searchEngaged, setSearchEngaged] = useState(false);
  const [tagEditorTrack, setTagEditorTrack] = useState<Track | null>(null);
  const [playlistTrack, setPlaylistTrack] = useState<Track | null>(null);
  const [toast, setToast] = useState("");
  const [updateCheck, setUpdateCheck] = useState<UpdateCheckState>({ status: "idle", latestVersion: "", releaseUrl: githubProjectUrl });
  const [sortMode, setSortMode] = useState<SongSortMode>(() => {
    const saved = localStorage.getItem("armusic-song-sort");
    return saved === "artist" || saved === "work" || saved === "album" || saved === "recent" || saved === "recentPlayed" || saved === "listenTime" ? saved : "title";
  });
  const [songRandomSeed, setSongRandomSeed] = useState(Date.now);
  const [selectedWorkName, setSelectedWorkName] = useState("");
  const [selectedArtistName, setSelectedArtistName] = useState("");
  const [playbackQueue, setPlaybackQueue] = useState<PlaybackQueue | null>(null);
  const [initialPlaybackState] = useState<StoredPlaybackState | null>(() => readStoredPlaybackState());

  const currentTrack = currentTrackId ? tracks.find((track) => track.syncId === currentTrackId) : undefined;
  const recommendationTracks = useMemo(() => [...tracks].sort((left, right) => (
    stableNumber(`${recommendationSeedRef.current}:${left.syncId}`)
      - stableNumber(`${recommendationSeedRef.current}:${right.syncId}`)
  )), [tracks]);
  const featuredTrack =
    recommendationTracks.find((track) => track.syncId === featuredTrackId) ?? recommendationTracks[0];
  const nowPalette = currentTrack ? paletteFor(currentTrack) : ["#303030", "#a9a9a9", "#f1f1f1"];
  const appStyle: AppStyle = {
    "--now-a": nowPalette[0],
    "--now-b": nowPalette[1],
    "--now-c": nowPalette[2],
    "--lyric-font": lyricFontFamilies[lyricFont],
    "--lyric-align": lyricAlignment,
    "--lyric-normal-size": `${13 * lyricScale / 100}px`,
    "--lyric-active-size": `${16 * lyricScale / 100}px`,
    "--lyric-short-size": `${11 * lyricScale / 100}px`,
    "--lyric-short-active-size": `${14 * lyricScale / 100}px`,
    "--lyric-gap": `${lyricGap}px`,
  };

  const filteredTracks = useMemo(() => {
    const query = searchText.trim().toLocaleLowerCase();
    const next = query
      ? tracks.filter((track) =>
          [track.title, track.artist, track.album, track.work, track.genre, track.relativePath]
            .filter(Boolean)
            .some((value) => value!.toLocaleLowerCase().includes(query)),
        )
      : [...tracks];

    return next.sort((a, b) => {
      if (sortMode === "artist") return a.artist.localeCompare(b.artist, "zh-CN");
      if (sortMode === "work") return (a.work || a.album || a.title).localeCompare(b.work || b.album || b.title, "zh-CN");
      if (sortMode === "album") return a.album.localeCompare(b.album, "zh-CN");
      if (sortMode === "recent") {
        return (b.modifiedAt || b.lastPlayedAt || "").localeCompare(a.modifiedAt || a.lastPlayedAt || "");
      }
      if (sortMode === "recentPlayed") return (b.lastPlayedAt || "").localeCompare(a.lastPlayedAt || "");
      if (sortMode === "listenTime") return b.playSeconds - a.playSeconds || a.title.localeCompare(b.title, "zh-CN");
      if (sortMode === "random") return stableNumber(`${songRandomSeed}:${a.syncId}`) - stableNumber(`${songRandomSeed}:${b.syncId}`);
      return a.title.localeCompare(b.title, "zh-CN");
    });
  }, [searchText, songRandomSeed, sortMode, tracks]);

  const works = useMemo(() => groupTracks(tracks, (track) => track.work || track.album, t("misc.uncategorized")), [t, tracks]);
  const artists = useMemo(() => groupTracks(tracks, (track) => track.artist, t("misc.uncategorized")), [t, tracks]);
  const visibleWorks = useMemo(() => filterGroups(works, searchText), [searchText, works]);
  const visibleArtists = useMemo(() => filterGroups(artists, searchText), [artists, searchText]);
  const totalListenSeconds = tracks.reduce((total, track) => total + track.playSeconds, 0);
  const localManifest = useMemo(() => createManifest("ARMusic Desktop", tracks), [tracks]);
  const remoteManifest = useMemo(() => createManifest("ARMusic Android", []), []);
  const syncPlan = useMemo(() => buildSyncPlan(localManifest, remoteManifest), [localManifest, remoteManifest]);
  const showSearchResults = searchEngaged && Boolean(searchText.trim());
  const workLabel = t(`settings.workLabel.${workLabelMode}` as MessageKey);

  const flash = useCallback((message: string) => setToast(message), []);
  const checkForUpdate = useCallback(async (silent = false) => {
    setUpdateCheck((current) => ({ ...current, status: "checking" }));
    try {
      const response = await fetch(githubLatestReleaseApi, {
        cache: "no-store",
        headers: { Accept: "application/vnd.github+json" },
      });
      if (!response.ok) throw new Error(`GitHub ${response.status}`);
      const release = await response.json() as { tag_name?: string; name?: string; html_url?: string };
      const latestVersion = release.tag_name || release.name || "";
      if (!latestVersion) throw new Error("missing release version");
      const available = compareVersionName(latestVersion, appVersion) > 0;
      setUpdateCheck({
        status: available ? "available" : "current",
        latestVersion,
        releaseUrl: release.html_url || githubProjectUrl,
      });
      if (available) flash(t("settings.updateFound", { version: latestVersion }));
      else if (!silent) flash(t("settings.updateCurrent", { version: appVersion }));
    } catch {
      setUpdateCheck((current) => ({ ...current, status: "failed" }));
      if (!silent) flash(t("settings.updateFailed"));
    }
  }, [flash, t]);
  const openGithub = useCallback(async () => {
    const url = updateCheck.status === "available" ? updateCheck.releaseUrl : githubProjectUrl;
    try {
      if (bridge) await bridge.openExternalUrl(url);
      else window.open(url, "_blank", "noopener,noreferrer");
    } catch {
      flash(t("settings.openGithubFailed"));
    }
  }, [bridge, flash, t, updateCheck.releaseUrl, updateCheck.status]);
  const switchPosterWall = useCallback((open: boolean) => {
    if (open === posterWallOpen) return;
    const transitionDocument = document as Document & {
      startViewTransition?: (update: () => void) => unknown;
    };
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches || !transitionDocument.startViewTransition) {
      setPosterWallOpen(open);
      return;
    }
    transitionDocument.startViewTransition(() => {
      flushSync(() => setPosterWallOpen(open));
    });
  }, [posterWallOpen]);

  const changeTheme = useCallback((nextTheme?: "light" | "dark") => {
    const requestedTheme = nextTheme === "light" || nextTheme === "dark" ? nextTheme : undefined;
    const update = () => setTheme((current) => requestedTheme ?? (current === "light" ? "dark" : "light"));
    const transitionDocument = document as Document & {
      startViewTransition?: (callback: () => void) => { finished?: Promise<unknown> };
    };
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches || !transitionDocument.startViewTransition) {
      update();
      return;
    }
    document.documentElement.classList.add("armusic-theme-transition");
    const cleanup = () => document.documentElement.classList.remove("armusic-theme-transition");
    const fallback = window.setTimeout(cleanup, 500);
    const transition = transitionDocument.startViewTransition(() => flushSync(update));
    void transition.finished?.finally(() => {
      window.clearTimeout(fallback);
      cleanup();
    });
  }, []);
  const toggleTheme = useCallback(() => changeTheme(), [changeTheme]);

  const navigateFromSidebar = useCallback((nextView: ViewId) => {
    setView(nextView);
    switchPosterWall(false);
    setSearchEngaged(false);
    setSearchText("");
  }, [switchPosterWall]);
  const updateSidebarSearch = useCallback((value: string) => {
    setSearchText(value);
    if (value.trim()) switchPosterWall(false);
  }, [switchPosterWall]);
  const clearSidebarSearch = useCallback(() => setSearchText(""), []);
  const engageSidebarSearch = useCallback(() => setSearchEngaged(true), []);
  const openWorkView = useCallback((name: string) => {
    setSelectedWorkName(name);
    setView("works");
  }, []);
  const closeWorkView = useCallback(() => setSelectedWorkName(""), []);
  const closeArtistView = useCallback(() => setSelectedArtistName(""), []);

  useEffect(() => {
    if (!toast) return;
    const timer = window.setTimeout(() => setToast(""), 2600);
    return () => window.clearTimeout(timer);
  }, [toast]);

  useEffect(() => {
    const timer = window.setTimeout(() => void checkForUpdate(true), 1800);
    return () => window.clearTimeout(timer);
  }, [checkForUpdate]);

  useEffect(() => {
    localStorage.setItem("armusic-theme", theme);
  }, [theme]);

  useEffect(() => {
    localStorage.setItem("armusic-lyric-font", lyricFont);
    localStorage.setItem("armusic-lyric-alignment", lyricAlignment);
    localStorage.setItem("armusic-lyric-offset-ms", String(lyricOffsetMs));
    localStorage.setItem("armusic-lyric-scale", String(lyricScale));
    localStorage.setItem("armusic-lyric-gap", String(lyricGap));
    localStorage.setItem("armusic-lyric-focus-position", lyricFocusPosition);
  }, [lyricAlignment, lyricFocusPosition, lyricFont, lyricGap, lyricOffsetMs, lyricScale]);

  useEffect(() => {
    localStorage.setItem("armusic-volume", String(volume));
    localStorage.setItem("armusic-muted", String(isMuted));
    localStorage.setItem("armusic-play-mode", playMode);
  }, [isMuted, playMode, volume]);

  useEffect(() => {
    if (sortMode !== "random") localStorage.setItem("armusic-song-sort", sortMode);
    localStorage.removeItem("armusic-song-random-seed");
  }, [sortMode]);

  useEffect(() => {
    localStorage.setItem("armusic-sleep-timer-seconds", String(sleepTimerDefaultSeconds));
    localStorage.setItem("armusic-sleep-timer-finish-track", String(sleepTimerPauseWhenCompletion));
    localStorage.setItem("armusic-work-label-mode", workLabelMode);
  }, [sleepTimerDefaultSeconds, sleepTimerPauseWhenCompletion, workLabelMode]);

  useEffect(() => {
    if (!currentTrack || pendingPlaybackRestoreRef.current?.syncId === currentTrack.syncId) return;
    writeStoredPlaybackState(currentTrack, position);
  }, [currentTrack, position]);

  useEffect(() => {
    localStorage.setItem("armusic-launch-at-startup", String(launchAtStartup));
    localStorage.setItem("armusic-close-to-tray", String(closeToTray));
  }, [closeToTray, launchAtStartup]);

  useEffect(() => {
    let active = true;
    void getDesktopBehaviorPreferences()
      .then((preferences) => {
        if (!active || !preferences) return;
        setCloseToTray(preferences.closeToTray);
        setLaunchAtStartup(preferences.launchAtStartup);
      })
      .catch(() => undefined);
    return () => { active = false; };
  }, []);

  useEffect(() => {
    if (!sleepTimerDeadline) return;
    const checkTimer = () => {
      if (Date.now() < sleepTimerDeadline) return;
      setSleepTimerDeadline(null);
      if (sleepTimerPauseWhenCompletion && currentTrack && isPlaying) {
        setSleepTimerMode("trackEnd");
        flash(t("settings.timerWaitingTrackEnd"));
      } else {
        setIsPlaying(false);
        setSleepTimerMode("off");
        flash(t("settings.timerFinished"));
      }
    };
    checkTimer();
    const timer = window.setInterval(checkTimer, 1_000);
    return () => window.clearInterval(timer);
  }, [currentTrack, flash, isPlaying, sleepTimerDeadline, sleepTimerPauseWhenCompletion, t]);

  useEffect(() => {
    directoryInputRef.current?.setAttribute("webkitdirectory", "");
  }, []);

  useEffect(() => {
    if (!bridge) return;
    void Promise.all([bridge.getLibraryState(), bridge.getSyncStatus()])
      .then(([library, status]) => {
        if (library.tracks.length > 0) {
          const imported = normalizeScannedTracks(library.tracks, { unknownArtist: t("misc.unknownArtist"), localMusic: t("home.localMusic") });
          const legacySavedTrackId = localStorage.getItem("armusic-last-track-id") || "";
          const savedTrackId = initialPlaybackState?.syncId || legacySavedTrackId;
          const restoredTrack = imported.find((track) => (
            track.syncId === savedTrackId
            || track.legacySyncIds?.includes(savedTrackId)
            || Boolean(initialPlaybackState?.relativePath && track.relativePath === initialPlaybackState.relativePath)
          ));
          const latestPlayedTrack = [...imported].sort((left, right) => (
            (Date.parse(right.lastPlayedAt || "") || 0) - (Date.parse(left.lastPlayedAt || "") || 0)
          ))[0];
          const initialTrack = restoredTrack || latestPlayedTrack || imported[0];
          pendingPlaybackRestoreRef.current = {
            syncId: initialTrack.syncId,
            positionSeconds: restoredTrack ? initialPlaybackState?.positionSeconds || 0 : 0,
          };
          setTracks(imported);
          setCurrentTrackId(initialTrack.syncId);
          const firstRecommendation = [...imported].sort((left, right) => (
            stableNumber(`${recommendationSeedRef.current}:${left.syncId}`)
              - stableNumber(`${recommendationSeedRef.current}:${right.syncId}`)
          ))[0];
          setFeaturedTrackId(firstRecommendation?.syncId || imported[0].syncId);
          setFolderPath(library.folderPath || "");
          setNeedsMusicFolder(false);
          setScanMessage(t("status.loaded", { count: imported.length }));
        } else {
          setTracks([]);
          setCurrentTrackId("");
          setFeaturedTrackId("");
          setFolderPath(library.folderPath || "");
          setNeedsMusicFolder(!library.folderPath);
          setScanMessage(library.folderPath ? t("status.emptyFolder") : t("status.noFolder"));
        }
        setSyncStatus(status);
      })
      .catch(() => setScanMessage(t("status.libraryNotReady")));
  // Loading the library is a startup concern. Re-running it for a language-only
  // change used the startup playback snapshot and could jump back to that song.
  }, [bridge, initialPlaybackState]);

  useEffect(() => {
    const pending = pendingPlaybackRestoreRef.current;
    const restoredPosition = currentTrack && pending?.syncId === currentTrack.syncId
      ? Math.max(0, Math.min(pending.positionSeconds, Math.max(0, (currentTrack.durationSeconds || pending.positionSeconds + 1) - 1)))
      : 0;
    setPosition(restoredPosition);
    setDuration(currentTrack?.durationSeconds || 0);
    if (!currentTrack) setIsPlaying(false);
  }, [currentTrack?.syncId, currentTrack?.durationSeconds]);

  useEffect(() => {
    const pending = pendingPlaybackRestoreRef.current;
    if (!currentTrack || pending?.syncId !== currentTrack.syncId) return;
    const timer = window.setTimeout(() => {
      if (pendingPlaybackRestoreRef.current !== pending) return;
      pendingPlaybackRestoreRef.current = null;
      writeStoredPlaybackState(currentTrack, pending.positionSeconds);
    }, 2_000);
    return () => window.clearTimeout(timer);
  }, [currentTrack?.syncId]);

  useEffect(() => {
    const audio = audioRef.current;
    if (!audio || !currentTrack?.playUrl) {
      audio?.pause();
      return;
    }
    audio.volume = isMuted ? 0 : volume;
    if (isPlaying) {
      void audio.play().catch(() => {
        setIsPlaying(false);
        flash(t("status.formatFailed"));
      });
    } else {
      audio.pause();
    }
  }, [currentTrack?.playUrl, flash, isMuted, isPlaying, t, volume]);

  useEffect(() => {
    if (!currentTrack || !isPlaying || currentTrack.playUrl) return;
    const timer = window.setInterval(() => {
      setPosition((value) => {
        const max = duration || currentTrack.durationSeconds || 240;
        if (value + 0.5 >= max) return 0;
        return value + 0.5;
      });
    }, 500);
    return () => window.clearInterval(timer);
  }, [currentTrack?.durationSeconds, currentTrack?.playUrl, duration, isPlaying]);

  useEffect(() => {
    if (!bridge) return;

    const accrue = (session: ActiveListeningSession) => {
      const now = Date.now();
      const wallElapsedMs = session.lastTickMs > 0
        ? Math.max(0, now - session.lastTickMs)
        : 0;
      const audio = audioRef.current;
      const mediaTime = audio && currentTrack?.playUrl && session.syncId === currentTrack.syncId
        ? audio.currentTime
        : Number.NaN;

      if (Number.isFinite(mediaTime)) {
        const previousMediaTime = session.lastMediaTimeSeconds;
        session.lastMediaTimeSeconds = mediaTime;
        session.lastTickMs = now;
        // `currentTime` advances only while the media really plays, so a hidden tray window keeps
        // accurate time and system sleep contributes zero. The wall-clock allowance rejects a
        // large manual seek even if the browser delays its `seeking` event.
        session.listenedMs += mediaListeningDeltaMs({
          previousMediaTimeSeconds: previousMediaTime,
          mediaTimeSeconds: mediaTime,
          wallElapsedMs,
          isPlaying,
          isSeeking: Boolean(audio?.seeking),
        });
        return;
      }

      if (session.lastTickMs <= 0 || !isPlaying) {
        session.lastTickMs = now;
        return;
      }
      // Non-media fallback: cap delayed timers so a suspended WebView cannot become listening time.
      session.listenedMs += Math.min(wallElapsedMs, 5_000);
      session.lastTickMs = now;
    };

    const checkpoint = (session: ActiveListeningSession, force = false) => {
      const wholeSeconds = Math.floor(session.listenedMs / 1000);
      if (wholeSeconds <= 0) return;
      if (!force && session.listenedMs - session.lastCheckpointMs < 5_000) return;
      session.lastCheckpointMs = session.listenedMs;
      setTracks((items) => items.map((track) => track.syncId === session.syncId ? {
        ...track,
        playSeconds: Math.max(track.playSeconds, session.basePlaySeconds + wholeSeconds),
        lastPlayedAt: new Date(session.startedAtMs).toISOString(),
      } : track));
      historyWriteQueueRef.current = historyWriteQueueRef.current
        .catch(() => undefined)
        .then(() => bridge.recordListeningSession({
          syncId: session.syncId,
          sessionId: session.sessionId,
          startedAtMs: session.startedAtMs,
          durationMs: Math.floor(session.listenedMs),
        }))
        .catch(() => undefined);
    };

    const previous = listeningSessionRef.current;
    if (previous && previous.syncId !== currentTrack?.syncId) {
      accrue(previous);
      checkpoint(previous, true);
      listeningSessionRef.current = null;
    }

    if (!currentTrack) return;
    if (!listeningSessionRef.current && isPlaying) {
      listeningSessionRef.current = {
        syncId: currentTrack.syncId,
        sessionId: newListeningSessionId(),
        startedAtMs: Date.now(),
        listenedMs: 0,
        lastTickMs: Date.now(),
        lastMediaTimeSeconds: Number.isFinite(audioRef.current?.currentTime)
          ? audioRef.current?.currentTime ?? null
          : null,
        lastCheckpointMs: 0,
        basePlaySeconds: currentTrack.playSeconds,
      };
    }

    const session = listeningSessionRef.current;
    if (!session || session.syncId !== currentTrack.syncId) return;
    if (!isPlaying) {
      accrue(session);
      checkpoint(session, true);
      session.lastTickMs = 0;
      session.lastMediaTimeSeconds = null;
      return;
    }

    session.lastTickMs = Date.now();
    session.lastMediaTimeSeconds = Number.isFinite(audioRef.current?.currentTime)
      ? audioRef.current?.currentTime ?? null
      : null;
    const timer = window.setInterval(() => {
      accrue(session);
      checkpoint(session);
    }, 1_000);
    const handleVisibility = () => {
      accrue(session);
      checkpoint(session, true);
    };
    const resetMediaClock = () => {
      const mediaTime = audioRef.current?.currentTime;
      session.lastMediaTimeSeconds = Number.isFinite(mediaTime) ? mediaTime ?? null : null;
      session.lastTickMs = Date.now();
    };
    const audio = audioRef.current;
    document.addEventListener("visibilitychange", handleVisibility);
    audio?.addEventListener("seeking", resetMediaClock);
    audio?.addEventListener("loadedmetadata", resetMediaClock);

    return () => {
      window.clearInterval(timer);
      document.removeEventListener("visibilitychange", handleVisibility);
      audio?.removeEventListener("seeking", resetMediaClock);
      audio?.removeEventListener("loadedmetadata", resetMediaClock);
      accrue(session);
      checkpoint(session, true);
      session.lastTickMs = 0;
      session.lastMediaTimeSeconds = null;
    };
  }, [bridge, currentTrack?.syncId, isPlaying]);

  useEffect(() => {
    if (!bridge || !syncStatus.running) return;
    let active = true;
    const refresh = () => {
      void bridge.getLibraryState().then((library) => {
        if (!active || !library.tracks.length) return;
        setTracks(normalizeScannedTracks(library.tracks, {
          unknownArtist: t("misc.unknownArtist"),
          localMusic: t("home.localMusic"),
        }));
      }).catch(() => undefined);
    };
    const timer = window.setInterval(refresh, 4_000);
    return () => {
      active = false;
      window.clearInterval(timer);
    };
  }, [bridge, syncStatus.running, t]);

  const startTrack = useCallback((track: Track, queue: PlaybackQueue | null) => {
    pendingPlaybackRestoreRef.current = null;
    writeStoredPlaybackState(track, 0);
    setPlaybackQueue(queue);
    setCurrentTrackId(track.syncId);
    setPosition(0);
    setIsPlaying(true);
  }, []);

  const playTrack = useCallback((track: Track) => startTrack(track, null), [startTrack]);
  const changeSongSort = useCallback((mode: SongSortMode) => {
    if (mode === "random") setSongRandomSeed(Date.now());
    setSortMode(mode);
  }, []);

  const playWorkTrack = useCallback((track: Track, workName: string, workTracks: Track[]) => {
    startTrack(track, workPlaybackQueue(workName, workTracks));
  }, [startTrack]);

  const playArtistTrack = useCallback((track: Track, artistName: string, artistTracks: Track[]) => {
    startTrack(track, {
      id: `artist:${artistName}`,
      trackIds: [...new Set(artistTracks.map((item) => item.syncId))],
    });
  }, [startTrack]);

  const playPlaylistTrack = useCallback((track: Track, playlistTracks: Track[] = [track]) => {
    startTrack(track, {
      id: `playlist:${playlistTracks.map((item) => item.syncId).join("|")}`,
      trackIds: playlistTracks.map((item) => item.syncId),
    });
  }, [startTrack]);

  const selectTrack = useCallback((track: Track) => {
    pendingPlaybackRestoreRef.current = null;
    writeStoredPlaybackState(track, 0);
    setPlaybackQueue(null);
    setCurrentTrackId(track.syncId);
    setPosition(0);
    setIsPlaying(false);
  }, []);

  const nextTrack = useCallback(() => {
    const next = adjacentTrack(tracks, currentTrackId, 1, playbackQueue, playMode === "shuffle");
    if (next) startTrack(next, playbackQueue);
  }, [currentTrackId, playbackQueue, playMode, startTrack, tracks]);

  const startSleepTimer = useCallback((seconds: number, pauseWhenCompletion: boolean) => {
    const normalizedSeconds = Math.max(60, Math.min(86_340, Math.round(seconds / 60) * 60));
    setSleepTimerDefaultSeconds(normalizedSeconds);
    setSleepTimerPauseWhenCompletion(pauseWhenCompletion);
    setSleepTimerMode("running");
    setSleepTimerDeadline(Date.now() + normalizedSeconds * 1_000);
  }, []);
  const cancelSleepTimer = useCallback(() => {
    setSleepTimerMode("off");
    setSleepTimerDeadline(null);
  }, []);

  const handleTrackEnded = useCallback(() => {
    if (sleepTimerMode === "trackEnd") {
      setIsPlaying(false);
      setSleepTimerMode("off");
      setSleepTimerDeadline(null);
      flash(t("settings.timerFinished"));
      return;
    }
    nextTrack();
  }, [flash, nextTrack, sleepTimerMode, t]);

  const previousTrack = useCallback(() => {
    const previous = adjacentTrack(tracks, currentTrackId, -1, playbackQueue);
    if (previous) startTrack(previous, playbackQueue);
  }, [currentTrackId, playbackQueue, startTrack, tracks]);

  useEffect(() => {
    void publishTrayPlayerState({
      title: currentTrack?.title || "",
      artist: currentTrack?.artist || "",
      coverUrl: currentTrack?.coverUrl,
      isPlaying: Boolean(currentTrack && isPlaying),
      hasTrack: Boolean(currentTrack),
      positionSeconds: position,
      durationSeconds: duration || currentTrack?.durationSeconds || 0,
    }).catch(() => undefined);
  }, [currentTrack?.artist, currentTrack?.coverUrl, currentTrack?.durationSeconds, currentTrack?.syncId, currentTrack?.title, duration, isPlaying, position]);

  useEffect(() => {
    let active = true;
    let unlisten: (() => void) | undefined;
    void listenToTrayPlayerActions((action) => {
      if (action === "previous") previousTrack();
      else if (action === "next") nextTrack();
      else if (action === "playPause" && currentTrack) setIsPlaying((value) => !value);
      else if (action.startsWith("seek:")) {
        const nextPosition = Number(action.slice(5));
        if (Number.isFinite(nextPosition)) seekTo(nextPosition, audioRef, duration, setPosition);
      }
    }).then((stop) => {
      if (active) unlisten = stop;
      else stop();
    }).catch(() => undefined);
    return () => {
      active = false;
      unlisten?.();
    };
  }, [currentTrack?.syncId, duration, nextTrack, previousTrack]);

  const changeLaunchAtStartup = useCallback((enabled: boolean) => {
    const previous = launchAtStartup;
    setLaunchAtStartup(enabled);
    void saveLaunchAtStartup(enabled).then((preferences) => {
      if (!preferences) return;
      setLaunchAtStartup(preferences.launchAtStartup);
      setCloseToTray(preferences.closeToTray);
    }).catch(() => {
      setLaunchAtStartup((value) => value === enabled ? previous : value);
      flash(t("settings.systemSaveFailed"));
    });
  }, [flash, launchAtStartup, t]);

  const changeCloseToTray = useCallback((enabled: boolean) => {
    const previous = closeToTray;
    setCloseToTray(enabled);
    void saveCloseToTray(enabled).then((preferences) => {
      if (!preferences) return;
      setLaunchAtStartup(preferences.launchAtStartup);
      setCloseToTray(preferences.closeToTray);
    }).catch(() => {
      setCloseToTray((value) => value === enabled ? previous : value);
      flash(t("settings.systemSaveFailed"));
    });
  }, [closeToTray, flash, t]);

  useEffect(() => {
    function handleShortcut(event: globalThis.KeyboardEvent) {
      const target = event.target as HTMLElement | null;
      const isTyping = target?.matches("input, textarea, [contenteditable='true']");
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "f") {
        event.preventDefault();
        setSearchEngaged(true);
        searchRef.current?.focus();
        return;
      }
      if (event.key === "Escape" && posterWallOpen) {
        switchPosterWall(false);
        return;
      }
      if (event.key === "Escape" && searchEngaged) {
        setSearchEngaged(false);
        setSearchText("");
        searchRef.current?.blur();
        return;
      }
      if (isTyping) return;
      if (event.code === "Space" && tracks.length) {
        event.preventDefault();
        setIsPlaying((value) => !value);
      } else if (event.shiftKey && event.key === "ArrowRight") {
        event.preventDefault();
        seekTo(position + 5, audioRef, duration, setPosition);
      } else if (event.shiftKey && event.key === "ArrowLeft") {
        event.preventDefault();
        seekTo(position - 5, audioRef, duration, setPosition);
      } else if (event.ctrlKey && event.key === "ArrowRight") {
        event.preventDefault();
        nextTrack();
      } else if (event.ctrlKey && event.key === "ArrowLeft") {
        event.preventDefault();
        previousTrack();
      }
    }
    window.addEventListener("keydown", handleShortcut);
    return () => window.removeEventListener("keydown", handleShortcut);
  }, [duration, nextTrack, position, posterWallOpen, previousTrack, searchEngaged, switchPosterWall, tracks.length]);

  const chooseMusicFolder = useCallback(async () => {
    if (!bridge) {
      directoryInputRef.current?.click();
      return;
    }

    setIsScanning(true);
    setScanMessage(t("status.readingFolder"));
    try {
      const result = await bridge.chooseMusicFolder();
      if (result.canceled) {
        setScanMessage(t("status.notChanged"));
        return;
      }
      const imported = normalizeScannedTracks(result.tracks, { unknownArtist: t("misc.unknownArtist"), localMusic: t("home.localMusic") });
      setTracks(imported);
      setFolderPath(result.folderPath || "");
      setNeedsMusicFolder(false);
      setScanMessage(t("status.imported", { count: imported.length }));
      if (imported[0]) {
        setCurrentTrackId(imported[0].syncId);
        setFeaturedTrackId(imported[0].syncId);
      } else {
        setCurrentTrackId("");
        setFeaturedTrackId("");
        setIsPlaying(false);
      }
      flash(t("status.libraryUpdated", { count: imported.length }));
    } catch {
      setScanMessage(t("status.readFailed"));
      flash(t("status.importFailed"));
    } finally {
      setIsScanning(false);
    }
  }, [bridge, flash, t]);

  function importWebFolder(event: ChangeEvent<HTMLInputElement>) {
    const files = Array.from(event.target.files || []).filter((file) => {
      const extension = file.name.split(".").pop()?.toLowerCase() || "";
      return audioExtensions.has(extension);
    });
    if (!files.length) {
      flash(t("status.noAudio"));
      return;
    }

    const imported: Track[] = files.map((file, index) => {
      const relativePath = file.webkitRelativePath || file.name;
      const parts = relativePath.split("/");
      const stem = file.name.replace(/\.[^.]+$/, "");
      const nameParts = stem.split(" - ");
      const artist = nameParts.length > 1 ? nameParts.shift()!.trim() : t("misc.unknownArtist");
      const title = nameParts.length ? nameParts.join(" - ").trim() : stem;
      return {
        syncId: `browser-${file.name}-${file.size}-${index}`,
        title,
        artist,
        album: parts.length > 1 ? parts[parts.length - 2] : t("home.localMusic"),
        durationSeconds: 0,
        sizeBytes: file.size,
        relativePath,
        playUrl: URL.createObjectURL(file),
        playSeconds: 0,
        source: "desktop",
        genre: t("home.localMusic"),
      };
    });

    setTracks(imported);
    setCurrentTrackId(imported[0].syncId);
    setFeaturedTrackId(imported[0].syncId);
    setFolderPath(files[0].webkitRelativePath.split("/")[0] || t("misc.browserLibrary"));
    setNeedsMusicFolder(false);
    setScanMessage(t("status.temporary", { count: imported.length }));
    flash(t("status.foundLocal", { count: imported.length }));
    event.target.value = "";
  }

  async function toggleSyncServer() {
    if (!bridge) {
      flash(t("status.syncDesktopOnly"));
      return;
    }
    setIsSyncing(true);
    try {
      const status = syncStatus.running ? await bridge.stopSyncServer() : await bridge.startSyncServer();
      setSyncStatus(status);
      flash(status.running ? t("status.syncStarted") : t("status.syncStopped"));
    } catch {
      flash(t("status.syncFailed"));
    } finally {
      setIsSyncing(false);
    }
  }

  const cycleFeatured = useCallback((direction: number) => {
    if (!recommendationTracks.length) return;
    setFeaturedTrackId((current) => {
      const index = Math.max(0, recommendationTracks.findIndex((track) => track.syncId === current));
      return recommendationTracks[(index + direction + recommendationTracks.length) % recommendationTracks.length].syncId;
    });
  }, [recommendationTracks]);

  useEffect(() => {
    if (recommendationTracks.length < 2) return;
    const timer = window.setInterval(() => cycleFeatured(1), 10_000);
    return () => window.clearInterval(timer);
  }, [cycleFeatured, recommendationTracks.length]);

  const openTagEditor = useCallback((track: Track) => {
    if (!bridge) {
      flash(t("status.tagEditorDesktopOnly"));
      return;
    }
    setTagEditorTrack(track);
  }, [bridge, flash, t]);

  const openAddToPlaylist = useCallback((track: Track) => {
    if (!bridge) {
      flash(t("status.playlistsDesktopOnly"));
      return;
    }
    setPlaylistTrack(track);
  }, [bridge, flash, t]);

  function applyTagSave(result: TagSaveResult) {
    const imported = normalizeScannedTracks(result.library.tracks, { unknownArtist: t("misc.unknownArtist"), localMusic: t("home.localMusic") });
    setTracks(imported);
    setCurrentTrackId((value) => value === result.previousSyncId ? result.newSyncId : value);
    setFeaturedTrackId((value) => value === result.previousSyncId ? result.newSyncId : value);
    setFolderPath(result.library.folderPath || folderPath);
    setNeedsMusicFolder(false);
    setScanMessage(t("status.loaded", { count: imported.length }));
  }

  const refreshDesktopLibrary = useCallback(async () => {
    if (!bridge) return;
    const library = await bridge.getLibraryState();
    const imported = normalizeScannedTracks(library.tracks, {
      unknownArtist: t("misc.unknownArtist"),
      localMusic: t("home.localMusic"),
    });
    setTracks(imported);
    setCurrentTrackId((value) => imported.some((track) => track.syncId === value) ? value : imported[0]?.syncId || "");
    setFeaturedTrackId((value) => imported.some((track) => track.syncId === value) ? value : imported[0]?.syncId || "");
    setFolderPath(library.folderPath || "");
    setNeedsMusicFolder(!library.folderPath);
    setScanMessage(imported.length
      ? t("status.loaded", { count: imported.length })
      : library.folderPath ? t("status.emptyFolder") : t("status.noFolder"));
  }, [bridge, t]);

  return (
    <WindowSurface
      theme={theme}
      style={appStyle}
      titlebarContent={currentTrack ? (
        <PlayerBar
          track={currentTrack}
          isPlaying={isPlaying}
          position={position}
          duration={duration}
          volume={volume}
          isMuted={isMuted}
          playMode={playMode}
          onTogglePlay={() => setIsPlaying((value) => !value)}
          onPrevious={previousTrack}
          onNext={nextTrack}
          onSeek={(value) => seekTo(value, audioRef, duration, setPosition)}
          onVolume={setVolume}
          onMute={() => setIsMuted((value) => !value)}
          onPlayMode={() => setPlayMode((value) => (value === "repeat" ? "shuffle" : "repeat"))}
          onInspector={() => { switchPosterWall(!posterWallOpen); setInspectorTab("lyrics"); }}
          onEdit={openTagEditor}
        />
      ) : null}
    >
    <div className={`app-shell ${currentTrack ? (posterWallOpen ? "app-shell--poster" : "") : "app-shell--empty"}`}>
      <audio
        ref={audioRef}
        src={currentTrack?.playUrl}
        onTimeUpdate={(event) => {
          const pending = pendingPlaybackRestoreRef.current;
          if (pending?.syncId === currentTrack?.syncId) return;
          setPosition(event.currentTarget.currentTime);
        }}
        onLoadedMetadata={(event) => {
          const nextDuration = event.currentTarget.duration || currentTrack?.durationSeconds || 0;
          setDuration(nextDuration);
          const pending = pendingPlaybackRestoreRef.current;
          if (!currentTrack || pending?.syncId !== currentTrack.syncId) return;
          const nextPosition = Math.max(0, Math.min(pending.positionSeconds, Math.max(0, nextDuration - 1)));
          event.currentTarget.currentTime = nextPosition;
          setPosition(nextPosition);
          pendingPlaybackRestoreRef.current = null;
          writeStoredPlaybackState(currentTrack, nextPosition);
        }}
        onEnded={handleTrackEnded}
      />
      <input ref={directoryInputRef} type="file" multiple hidden onChange={importWebFolder} />
      <MemoSidebar
        view={view}
        trackCount={tracks.length}
        theme={theme}
        workLabel={workLabel}
        searchText={searchText}
        searchRef={searchRef}
        onNavigate={navigateFromSidebar}
        onThemeToggle={toggleTheme}
        onSearch={updateSidebarSearch}
        onClearSearch={clearSidebarSearch}
        onSearchFocus={engageSidebarSearch}
      />

      <main className="content-shell">
        <div className={`page-scroll ${showSearchResults || posterWallOpen ? "page-scroll--search" : ""} ${!showSearchResults && !posterWallOpen && view === "home" ? "page-scroll--home" : ""}`}>
          {posterWallOpen ? (
            <SearchExperience
              tracks={tracks}
              allTracks={tracks}
              query=""
              currentTrack={currentTrack}
              position={position}
              lyricOffsetMs={lyricOffsetMs}
              onSeek={(value) => seekTo(value, audioRef, duration, setPosition)}
              onPlay={playTrack}
              onEdit={openTagEditor}
              onClose={() => switchPosterWall(false)}
            />
          ) : null}
          {showSearchResults ? (
            <SearchExperience
              tracks={filteredTracks}
              allTracks={tracks}
              query={searchText}
              currentTrack={currentTrack}
              position={position}
              lyricOffsetMs={lyricOffsetMs}
              onSeek={(value) => seekTo(value, audioRef, duration, setPosition)}
              onPlay={(track) => { playTrack(track); setSearchEngaged(false); }}
              onEdit={openTagEditor}
              onClose={() => { setSearchEngaged(false); setSearchText(""); searchRef.current?.blur(); }}
            />
          ) : null}
          {!showSearchResults && !posterWallOpen && view === "home" ? (
            <MemoHomeView
              tracks={filteredTracks}
              recommendations={recommendationTracks}
              featuredTrack={featuredTrack}
              works={works}
              workLabel={workLabel}
              totalListenSeconds={totalListenSeconds}
              trackCount={tracks.length}
              onPlay={playTrack}
              onSelect={selectTrack}
              onEdit={openTagEditor}
              onFeatured={setFeaturedTrackId}
              onCycleFeatured={cycleFeatured}
              onNavigate={setView}
              onOpenWork={openWorkView}
              onChooseFolder={chooseMusicFolder}
            />
          ) : null}
          {!showSearchResults && !posterWallOpen && view === "songs" ? (
            <MemoSongsView
              tracks={filteredTracks}
              workLabel={workLabel}
              currentTrackId={currentTrack?.syncId || ""}
              isPlaying={isPlaying}
              sortMode={sortMode}
              scanMessage={scanMessage}
              onSort={changeSongSort}
              onSelect={selectTrack}
              onPlay={playTrack}
              onEdit={openTagEditor}
              onAddToPlaylist={openAddToPlaylist}
              onChooseFolder={chooseMusicFolder}
            />
          ) : null}
          {!showSearchResults && !posterWallOpen && view === "works" ? (
            <MemoWorksView
              groups={visibleWorks}
              workLabel={workLabel}
              selectedName={selectedWorkName}
              currentTrackId={currentTrack?.syncId || ""}
              isPlaying={isPlaying}
              onOpen={setSelectedWorkName}
              onBack={closeWorkView}
              onPlay={playWorkTrack}
              onEdit={openTagEditor}
            />
          ) : null}
          {!showSearchResults && !posterWallOpen && view === "artists" ? (
            <MemoArtistsView
              groups={visibleArtists}
              selectedName={selectedArtistName}
              currentTrackId={currentTrack?.syncId || ""}
              isPlaying={isPlaying}
              onOpen={setSelectedArtistName}
              onBack={closeArtistView}
              onPlay={playArtistTrack}
              onEdit={openTagEditor}
            />
          ) : null}
          {!showSearchResults && !posterWallOpen && view === "history" ? <HistoryView tracks={filteredTracks} onPlay={playTrack} onEdit={openTagEditor} /> : null}
          {!showSearchResults && !posterWallOpen && view === "playlists" ? <PlaylistsView bridge={bridge} tracks={tracks} onPlay={playPlaylistTrack} onEdit={openTagEditor} onNotice={flash} /> : null}
          {!showSearchResults && !posterWallOpen && view === "wishlist" ? <WishlistView bridge={bridge} onNotice={flash} /> : null}
          {!showSearchResults && !posterWallOpen && view === "sync" ? (
            <SyncView
              status={syncStatus}
              isSyncing={isSyncing}
              folderPath={folderPath}
              trackCount={tracks.length}
              upload={syncPlan.upload}
              download={syncPlan.download}
              bridge={bridge}
              onToggle={toggleSyncServer}
              onChooseFolder={chooseMusicFolder}
              onLibraryChanged={refreshDesktopLibrary}
              onNotice={flash}
            />
          ) : null}
          {!showSearchResults && !posterWallOpen && view === "settings" ? (
            <SettingsView
              theme={theme}
              folderPath={folderPath}
              isScanning={isScanning}
              needsMusicFolder={needsMusicFolder}
              workLabelMode={workLabelMode}
              launchAtStartup={launchAtStartup}
              closeToTray={closeToTray}
              updateCheck={updateCheck}
              onTheme={changeTheme}
              onChooseFolder={chooseMusicFolder}
              onWorkLabelMode={setWorkLabelMode}
              onLaunchAtStartup={changeLaunchAtStartup}
              onCloseToTray={changeCloseToTray}
              onCheckForUpdate={() => void checkForUpdate(false)}
              onOpenGithub={() => void openGithub()}
              onNotice={flash}
            />
          ) : null}
        </div>
      </main>

      {currentTrack && !posterWallOpen ? (
        <NowPlayingInspector
          track={currentTrack}
          tracks={queueTracks(tracks, playbackQueue)}
          tab={inspectorTab}
          position={position}
          duration={duration}
          lyricFont={lyricFont}
          lyricAlignment={lyricAlignment}
          lyricOffsetMs={lyricOffsetMs}
          lyricScale={lyricScale}
          lyricGap={lyricGap}
          lyricFocusPosition={lyricFocusPosition}
          sleepTimerMode={sleepTimerMode}
          sleepTimerDeadline={sleepTimerDeadline}
          sleepTimerDefaultSeconds={sleepTimerDefaultSeconds}
          sleepTimerPauseWhenCompletion={sleepTimerPauseWhenCompletion}
          onTab={setInspectorTab}
          onClose={() => switchPosterWall(true)}
          onPlay={(track) => startTrack(track, playbackQueue)}
          onClearQueue={() => setPlaybackQueue({ id: `cleared:${currentTrack.syncId}`, trackIds: [] })}
          onEdit={openTagEditor}
          onLyricFont={setLyricFont}
          onLyricAlignment={setLyricAlignment}
          onLyricOffset={(value) => setLyricOffsetMs(Math.max(-5_000, Math.min(5_000, value)))}
          onLyricScale={(value) => setLyricScale(Math.max(85, Math.min(110, Math.round(value / 5) * 5)))}
          onLyricGap={(value) => setLyricGap(Math.max(7, Math.min(13, Math.round(value))))}
          onLyricFocusPosition={setLyricFocusPosition}
          onSleepTimerStart={startSleepTimer}
          onSleepTimerCancel={cancelSleepTimer}
          onSleepTimerDefaultSeconds={setSleepTimerDefaultSeconds}
          onSleepTimerPauseWhenCompletion={setSleepTimerPauseWhenCompletion}
          onSeek={(value) => seekTo(value, audioRef, duration, setPosition)}
        />
      ) : null}

      {toast ? (
        <div className="toast" role="status">
          <Check size={16} />
          {toast}
        </div>
      ) : null}

      {tagEditorTrack && bridge ? (
        <TagEditor
          track={tagEditorTrack}
          bridge={bridge}
          workLabel={workLabel}
          onClose={() => setTagEditorTrack(null)}
          onSaved={applyTagSave}
          onNotice={flash}
        />
      ) : null}
      {playlistTrack && bridge ? (
        <AddTrackToPlaylistDialog
          bridge={bridge}
          track={playlistTrack}
          onClose={() => setPlaylistTrack(null)}
          onNotice={flash}
        />
      ) : null}
    </div>
    </WindowSurface>
  );
}

function WindowSurface({ theme, style, titlebarContent, children }: { theme: "light" | "dark"; style: AppStyle; titlebarContent?: React.ReactNode; children: React.ReactNode }) {
  const { t } = useI18n();
  const [maximized, setMaximized] = useState(false);
  const inTauri = Boolean(window.__TAURI_INTERNALS__);
  const appWindow = useMemo(() => inTauri ? getCurrentWindow() : null, [inTauri]);

  const keepWindowInsideMonitor = useCallback(async () => {
    if (!appWindow || await appWindow.isMaximized()) return;
    const monitor = await currentMonitor();
    if (!monitor) return;

    const [size, position] = await Promise.all([
      appWindow.outerSize(),
      appWindow.outerPosition(),
    ]);
    const padding = Math.max(8, Math.round(8 * monitor.scaleFactor));
    const work = monitor.workArea;
    const width = Math.min(size.width, Math.max(1, work.size.width - padding * 2));
    const height = Math.min(size.height, Math.max(1, work.size.height - padding * 2));
    const left = Math.min(
      Math.max(position.x, work.position.x + padding),
      work.position.x + work.size.width - width - padding,
    );
    const top = Math.min(
      Math.max(position.y, work.position.y + padding),
      work.position.y + work.size.height - height - padding,
    );

    if (width !== size.width || height !== size.height) {
      await appWindow.setSize(new PhysicalSize(width, height));
    }
    if (left !== position.x || top !== position.y) {
      await appWindow.setPosition(new PhysicalPosition(left, top));
    }
  }, [appWindow]);

  useEffect(() => {
    if (!appWindow) return;
    let unlistenResize: (() => void) | undefined;
    let unlistenScale: (() => void) | undefined;
    let unlistenMove: (() => void) | undefined;
    let moveTimer: number | undefined;
    const refresh = () => { void appWindow.isMaximized().then(setMaximized).catch(() => undefined); };
    refresh();
    void keepWindowInsideMonitor().catch(() => undefined);
    void appWindow.onResized(refresh).then((stop) => { unlistenResize = stop; });
    void appWindow.onScaleChanged(() => {
      void keepWindowInsideMonitor().catch(() => undefined);
    }).then((stop) => { unlistenScale = stop; });
    void appWindow.onMoved(() => {
      window.clearTimeout(moveTimer);
      moveTimer = window.setTimeout(() => {
        void keepWindowInsideMonitor().catch(() => undefined);
      }, 220);
    }).then((stop) => { unlistenMove = stop; });
    return () => {
      window.clearTimeout(moveTimer);
      unlistenResize?.();
      unlistenScale?.();
      unlistenMove?.();
    };
  }, [appWindow, keepWindowInsideMonitor]);

  const minimize = () => { if (appWindow) void appWindow.minimize(); };
  const toggleMaximize = () => {
    if (!appWindow) return;
    void appWindow.toggleMaximize().then(() => appWindow.isMaximized()).then(setMaximized);
  };
  const close = () => { if (appWindow) void appWindow.close(); };

  return (
    <div className={`window-surface ${maximized ? "is-maximized" : ""}`} data-theme={theme} style={style}>
      <header className="window-titlebar" data-tauri-drag-region onDoubleClick={toggleMaximize}>
        <div className="window-brand" data-tauri-drag-region>
          <span className="brand__mark" data-tauri-drag-region><img src={armusicIcon} alt="" /></span>
          <strong data-tauri-drag-region>ARMusic</strong>
        </div>
        {titlebarContent ? (
          <div className="window-titlebar__player" data-tauri-drag-region onDoubleClick={(event) => event.stopPropagation()}>{titlebarContent}</div>
        ) : (
          <div className="window-titlebar__drag" data-tauri-drag-region />
        )}
        <div className="window-controls" onDoubleClick={(event) => event.stopPropagation()}>
          <button onClick={minimize} aria-label={t("window.minimize")} title={t("window.minimize")}><Minus size={15} /></button>
          <button onClick={toggleMaximize} aria-label={maximized ? t("window.restore") : t("window.maximize")} title={maximized ? t("window.restore") : t("window.maximize")}><Square size={12} /></button>
          <button className="window-controls__close" onClick={close} aria-label={t("window.close")} title={t("window.close")}><X size={15} /></button>
        </div>
      </header>
      <div className="window-content">{children}</div>
    </div>
  );
}

interface SidebarProps {
  view: ViewId;
  trackCount: number;
  theme: "light" | "dark";
  workLabel: string;
  searchText: string;
  searchRef: RefObject<HTMLInputElement>;
  onNavigate: (view: ViewId) => void;
  onThemeToggle: () => void;
  onSearch: (value: string) => void;
  onClearSearch: () => void;
  onSearchFocus: () => void;
}

function Sidebar({ view, trackCount, theme, workLabel, searchText, searchRef, onNavigate, onThemeToggle, onSearch, onClearSearch, onSearchFocus }: SidebarProps) {
  const { t } = useI18n();
  return (
    <aside className="sidebar">
      <label className="sidebar-search">
        <Search size={16} />
        <input
          ref={searchRef}
          value={searchText}
          onFocus={onSearchFocus}
          onChange={(event) => onSearch(event.target.value)}
          placeholder={t("top.searchLabel", { label: workLabel })}
        />
        {searchText ? <button onClick={onClearSearch} aria-label={t("top.clearSearch")}><X size={14} /></button> : <kbd>F</kbd>}
      </label>

      <nav className="sidebar__nav" aria-label={t("sidebar.mainPages")}>
        <span className="nav-caption">{t("sidebar.browse")}</span>
        {mainNavigation.map((item) => (
          <NavButton key={item.id} icon={item.icon} label={item.id === "works" ? workLabel : t(item.labelKey)} active={view === item.id} onClick={() => onNavigate(item.id)} />
        ))}
      </nav>

      <div className="library-note">
        <div><Music2 size={16} /><span>{t("sidebar.library")}</span></div>
        <strong>{trackCount}</strong>
        <small>{t("sidebar.localSongs")}</small>
      </div>

      <nav className="sidebar__nav sidebar__nav--bottom" aria-label={t("sidebar.tools")}>
        {utilityNavigation.map((item) => (
          <NavButton key={item.id} icon={item.icon} label={t(item.labelKey)} active={view === item.id} onClick={() => onNavigate(item.id)} />
        ))}
        <button className="nav-button" onClick={() => onThemeToggle()}>
          {theme === "light" ? <Moon size={18} /> : <Sun size={18} />}
          <span>{theme === "light" ? t("sidebar.dark") : t("sidebar.light")}</span>
        </button>
      </nav>
    </aside>
  );
}

const MemoSidebar = memo(Sidebar);

function NavButton({ icon: Icon, label, active, onClick }: { icon: LucideIcon; label: string; active: boolean; onClick: () => void }) {
  return (
    <button className={`nav-button ${active ? "is-active" : ""}`} onClick={onClick} aria-current={active ? "page" : undefined}>
      <Icon size={18} strokeWidth={active ? 2.4 : 1.8} />
      <span>{label}</span>
      {active ? <i /> : null}
    </button>
  );
}

interface PosterColumnMotion {
  offset: number;
  velocity: number;
  stepHeight: number;
  lastMeasuredAt: number;
}

interface PosterQueueItem {
  key: number;
  track: Track;
}

function SearchExperience({ tracks, allTracks, query, currentTrack, position, lyricOffsetMs, onSeek, onPlay, onEdit, onClose }: { tracks: Track[]; allTracks: Track[]; query: string; currentTrack?: Track; position: number; lyricOffsetMs: number; onSeek: (value: number) => void; onPlay: (track: Track) => void; onEdit: (track: Track) => void; onClose: () => void }) {
  const { t } = useI18n();
  const [posterScale, setPosterScale] = useState(() => {
    const saved = Number(localStorage.getItem("armusic-poster-wall-size"));
    return Number.isFinite(saved) ? Math.max(1, Math.min(7, Math.round(saved))) : 4;
  });
  const [posterSpeed, setPosterSpeed] = useState(() => {
    const saved = Number(localStorage.getItem("armusic-poster-wall-speed"));
    return Number.isFinite(saved) ? Math.max(12, Math.min(72, Math.round(saved))) : 34;
  });
  const [posterFlow, setPosterFlow] = useState<PosterFlow>(() => {
    const saved = localStorage.getItem("armusic-poster-wall-flow");
    return saved === "up" || saved === "down" ? saved : "alternate";
  });
  const [posterFullscreen, setPosterFullscreen] = useState(false);
  const posterQueueSerialRef = useRef(0);
  const posterShuffleBagsRef = useRef(new Map<string, Track[]>());
  const posterRecentDrawsRef = useRef<string[]>([]);
  const [posterQueues, setPosterQueues] = useState<PosterQueueItem[][]>([]);
  const trackRefs = useRef(new Map<number, HTMLDivElement>());
  const columnMotionRef = useRef(new Map<number, PosterColumnMotion>());
  const motionSettingsRef = useRef({ flow: posterFlow, speed: posterSpeed });
  const posterActionsRef = useRef({ onPlay, onEdit });
  posterActionsRef.current = { onPlay, onEdit };
  const posterSourceSignature = posterLibrarySignature(allTracks);
  const posters = useMemo(() => {
    const seenWorks = new Set<string>();
    return [...allTracks]
      .sort((left, right) => stableNumber(left.syncId) - stableNumber(right.syncId))
      .filter((track) => {
        const workKey = (track.work || track.album || track.syncId).trim().toLocaleLowerCase();
        if (seenWorks.has(workKey)) return false;
        seenWorks.add(workKey);
        return true;
      });
  // Listening checkpoints replace Track objects every five seconds. The visual
  // signature intentionally excludes history fields so playback never rebuilds
  // the whole poster wall; real tag, cover or library changes still do.
  }, [posterSourceSignature]);
  const posterColumnCount = 11 - posterScale;

  const nextQueueItem = useCallback((recent: PosterQueueItem[], fromStart = false): PosterQueueItem => {
    const bagKey = "global";
    let bag = posterShuffleBagsRef.current.get(bagKey) ?? [];
    if (!bag.length) {
      const boundaryTrack = fromStart ? recent[0]?.track : recent.at(-1)?.track;
      bag = shuffledPosterBatch(posters, (track) => track.syncId, boundaryTrack?.syncId);
      posterShuffleBagsRef.current.set(bagKey, bag);
    }
    const recentlyDrawn = new Set(posterRecentDrawsRef.current);
    const boundaryTrack = fromStart ? recent[0]?.track : recent.at(-1)?.track;
    const candidateIndex = bag.findIndex((track) => (
      track.syncId !== boundaryTrack?.syncId && !recentlyDrawn.has(track.syncId)
    ));
    const [track = posters[0]] = bag.splice(candidateIndex >= 0 ? candidateIndex : 0, 1);
    posterRecentDrawsRef.current.push(track.syncId);
    const recentLimit = Math.max(1, Math.min(72, posters.length - 1));
    if (posterRecentDrawsRef.current.length > recentLimit) {
      posterRecentDrawsRef.current.splice(0, posterRecentDrawsRef.current.length - recentLimit);
    }
    return { key: posterQueueSerialRef.current++, track };
  }, [posters]);

  useEffect(() => {
    posterQueueSerialRef.current = 0;
    posterShuffleBagsRef.current.clear();
    posterRecentDrawsRef.current = [];
    const queues = Array.from({ length: posterColumnCount }, () => [] as PosterQueueItem[]);
    for (let row = 0; row < 14 && posters.length; row += 1) {
      for (let column = 0; column < posterColumnCount; column += 1) {
        queues[column].push(nextQueueItem(queues[column]));
      }
    }
    columnMotionRef.current.clear();
    setPosterQueues(queues);
  }, [nextQueueItem, posterColumnCount, posters.length]);

  const rotatePosterQueues = useCallback((rotations: Map<number, { direction: "up" | "down"; count: number }>) => {
    if (!rotations.size || !posters.length) return;
    setPosterQueues((current) => current.map((queue, index) => {
      const rotation = rotations.get(index);
      if (!rotation || !queue.length) return queue;
      const next = [...queue];
      for (let step = 0; step < rotation.count; step += 1) {
        if (rotation.direction === "up") {
          next.shift();
          next.push(nextQueueItem(next));
        } else {
          next.pop();
          next.unshift(nextQueueItem(next, true));
        }
      }
      return next;
    }));
  }, [nextQueueItem, posters.length]);

  useEffect(() => {
    localStorage.setItem("armusic-poster-wall-size", String(posterScale));
    localStorage.setItem("armusic-poster-wall-speed", String(posterSpeed));
    localStorage.setItem("armusic-poster-wall-flow", posterFlow);
    motionSettingsRef.current = { flow: posterFlow, speed: posterSpeed };
  }, [posterFlow, posterScale, posterSpeed]);

  useEffect(() => {
    let frame = 0;
    let previousTime = performance.now();
    const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    const animate = (time: number) => {
      if (document.documentElement.classList.contains("armusic-theme-transition")) {
        previousTime = time;
        frame = window.requestAnimationFrame(animate);
        return;
      }
      const elapsed = Math.min(0.05, Math.max(0, (time - previousTime) / 1000));
      previousTime = time;
      const { flow, speed } = motionSettingsRef.current;
      const rotations = new Map<number, { direction: "up" | "down"; count: number }>();
      const transforms: Array<[HTMLDivElement, number]> = [];

      trackRefs.current.forEach((element, column) => {
        let motion = columnMotionRef.current.get(column);
        if (!motion || time - motion.lastMeasuredAt >= 350) {
          const run = element.firstElementChild as HTMLElement | null;
          const itemCount = run?.childElementCount ?? 0;
          const measuredStepHeight = itemCount > 0 ? (run?.scrollHeight ?? 0) / itemCount : 0;
          if (!Number.isFinite(measuredStepHeight) || measuredStepHeight <= 1) return;
          if (!motion) {
            motion = {
              offset: measuredStepHeight * ((column * 0.271) % 1),
              velocity: 0,
              stepHeight: measuredStepHeight,
              lastMeasuredAt: time,
            };
            columnMotionRef.current.set(column, motion);
          } else {
            if (Math.abs(motion.stepHeight - measuredStepHeight) > 1) {
              motion.offset = motion.stepHeight > 0 ? motion.offset / motion.stepHeight * measuredStepHeight : 0;
              motion.stepHeight = measuredStepHeight;
            }
            motion.lastMeasuredAt = time;
          }
        }
        const stepHeight = motion.stepHeight;

        const flowDirection = flow === "alternate"
          ? (column % 2 === 0 ? 1 : -1)
          : (flow === "up" ? 1 : -1);
        motion.velocity *= Math.exp(-2.4 * elapsed);
        motion.offset += ((reducedMotion ? 0 : flowDirection * speed) + motion.velocity) * elapsed;
        let movedUp = 0;
        let movedDown = 0;
        while (motion.offset >= stepHeight) {
          motion.offset -= stepHeight;
          movedUp += 1;
        }
        while (motion.offset < 0) {
          motion.offset += stepHeight;
          movedDown += 1;
        }
        if (movedUp) rotations.set(column, { direction: "up", count: movedUp });
        if (movedDown) rotations.set(column, { direction: "down", count: movedDown });
        transforms.push([element, motion.offset]);
      });
      // The transform wraps in the same frame that the queue changes. Commit the
      // queue before resetting the transform so a column never flashes its old head.
      if (rotations.size) flushSync(() => rotatePosterQueues(rotations));
      transforms.forEach(([element, offset]) => {
        element.style.transform = `translate3d(0, ${-offset}px, 0)`;
      });
      frame = window.requestAnimationFrame(animate);
    };
    frame = window.requestAnimationFrame(animate);
    return () => window.cancelAnimationFrame(frame);
  }, [posterColumnCount, rotatePosterQueues]);

  const scrollPosterColumn = useCallback((column: number, deltaY: number, deltaMode: number) => {
    const lineScale = deltaMode === 1 ? 18 : deltaMode === 2 ? window.innerHeight : 1;
    // Trackpads already report fine-grained pixel deltas. Keep the wheel response
    // deliberately restrained so one notch nudges a column instead of flinging it.
    const normalizedDelta = Math.max(-120, Math.min(120, deltaY * lineScale));
    const motion = columnMotionRef.current.get(column) ?? { offset: 0, velocity: 0, stepHeight: 0, lastMeasuredAt: 0 };
    motion.velocity = Math.max(-720, Math.min(720, motion.velocity + normalizedDelta * 2.2));
    columnMotionRef.current.set(column, motion);
  }, []);

  const posterWallGrid = useMemo(() => (
    <div
      className="poster-wall"
      aria-label={t("search.title")}
      style={{ "--poster-columns": posterColumnCount } as CSSProperties}
    >
      {posterQueues.map((queue, column) => (
        <div
          className="poster-wall__column"
          key={`poster-column-${column}`}
          onWheel={(event) => {
            event.preventDefault();
            event.stopPropagation();
            scrollPosterColumn(column, event.deltaY, event.deltaMode);
          }}
        >
          <div
            className="poster-wall__track"
            ref={(element) => {
              if (element) trackRefs.current.set(column, element);
              else trackRefs.current.delete(column);
            }}
          >
            <div className="poster-wall__run">
              {queue.map(({ key, track }, index) => (
                <button
                  key={key}
                  className="poster-wall__tile"
                  onClick={() => posterActionsRef.current.onPlay(track)}
                  onContextMenu={(event) => editTrackFromContextMenu(event, track, posterActionsRef.current.onEdit)}
                  tabIndex={index > 0 && index < 10 ? 0 : -1}
                  title={`${track.title} · ${track.artist}`}
                >
                  <CoverArt track={track} decorative />
                  <span><strong>{track.work || track.album || track.title}</strong><small>{track.artist}</small></span>
                </button>
              ))}
            </div>
          </div>
        </div>
      ))}
    </div>
  ), [posterColumnCount, posterQueues, scrollPosterColumn, t]);

  if (query.trim()) {
    return (
      <div className="search-stage search-stage--results">
        <div className="search-stage__result-head">
          <div><span>SEARCH RESULTS</span><h2>{tracks.length ? t("search.result", { count: tracks.length }) : t("search.noResult")}</h2></div>
          <button className="icon-button" onClick={onClose} aria-label={t("search.close")}><X size={18} /></button>
        </div>
        {tracks.length ? <div className="search-result-grid">{tracks.map((track) => (
          <button key={track.syncId} className="search-result-card" onClick={() => onPlay(track)} onContextMenu={(event) => editTrackFromContextMenu(event, track, onEdit)} title={`${track.title} · ${track.artist}`}>
            <CoverArt track={track} decorative />
            <span><strong>{track.title}</strong><small>{track.artist}</small></span>
            <i><Play size={18} fill="currentColor" /></i>
          </button>
        ))}</div> : <EmptyState icon={Search} title={t("search.noResult")} body={t("top.search")} />}
      </div>
    );
  }

  return (
    <div className={`search-stage search-stage--wall ${posterFullscreen ? "is-fullscreen" : ""}`}>
      {posterWallGrid}
      <div className="poster-wall__veil" />
      <DiscoveryLyrics track={currentTrack} position={position} lyricOffsetMs={lyricOffsetMs} onSeek={onSeek} />
      <div className="poster-wall__toolbar">
        <button className="poster-wall__tool-button" onClick={() => setPosterFullscreen((value) => !value)} aria-label={posterFullscreen ? t("search.wallExitFullscreen") : t("search.wallFullscreen")} title={posterFullscreen ? t("search.wallExitFullscreen") : t("search.wallFullscreen")}>
          {posterFullscreen ? <Minimize2 size={18} /> : <Maximize2 size={18} />}
        </button>
        <div className="poster-wall__settings">
          <button className="poster-wall__tool-button" aria-label={t("search.wallSettings")} title={t("search.wallSettings")}><Settings2 size={18} /></button>
          <section className="poster-wall__settings-popover" aria-label={t("search.wallSettings")}>
            <header><strong>{t("search.wallSettings")}</strong><small>{t("search.wallSettingsHint")}</small></header>
            <label className="poster-wall__range">
              <span><strong>{t("search.posterSize")}</strong><output>{posterScale} / 7</output></span>
              <input style={{ "--range-progress": `${(posterScale - 1) / 6 * 100}%` } as CSSProperties} type="range" min="1" max="7" step="1" value={posterScale} onChange={(event) => setPosterScale(Number(event.target.value))} />
              <small><span>{t("search.sizeSmall")}</span><span>{t("search.sizeLarge")}</span></small>
            </label>
            <div className="poster-wall__setting-group">
              <strong>{t("search.posterFlow")}</strong>
              <div className="poster-wall__flow-options" role="radiogroup" aria-label={t("search.posterFlow")}>
                {(["up", "down", "alternate"] as PosterFlow[]).map((flow) => (
                  <button key={flow} role="radio" aria-checked={posterFlow === flow} className={posterFlow === flow ? "is-active" : ""} onClick={() => setPosterFlow(flow)}>{t(`search.flow.${flow}` as MessageKey)}</button>
                ))}
              </div>
            </div>
            <label className="poster-wall__range">
              <span><strong>{t("search.posterSpeed")}</strong><output>{posterSpeed}</output></span>
              <input style={{ "--range-progress": `${(posterSpeed - 12) / 60 * 100}%` } as CSSProperties} type="range" min="12" max="72" step="2" value={posterSpeed} onChange={(event) => setPosterSpeed(Number(event.target.value))} />
              <small><span>{t("search.speedSlow")}</span><span>{t("search.speedFast")}</span></small>
            </label>
          </section>
        </div>
        <button className="poster-wall__tool-button poster-wall__tool-button--back" onClick={onClose} aria-label={t("search.backToLyrics")} title={t("search.backToLyrics")}>
          <PanelRightOpen size={18} />
        </button>
      </div>
    </div>
  );
}

function DiscoveryLyrics({ track, position, lyricOffsetMs, onSeek }: { track?: Track; position: number; lyricOffsetMs: number; onSeek: (value: number) => void }) {
  const { t } = useI18n();
  const lyrics = useMemo(() => parseLyrics(track?.lyrics), [track?.lyrics]);
  const lyricsRef = useRef<HTMLDivElement>(null);
  const manualTimerRef = useRef<number | undefined>(undefined);
  const lyricScrollFrameRef = useRef<number | undefined>(undefined);
  const pointerLeftWhileManualRef = useRef(false);
  const [manualLyricScrolling, setManualLyricScrolling] = useState(false);
  let activeLyric = lyrics.length > 0 && lyrics[0].timeSeconds !== null ? 0 : -1;
  lyrics.forEach((line, index) => {
    if (line.timeSeconds !== null && line.timeSeconds <= position + lyricOffsetMs / 1000 + 0.08) activeLyric = index;
  });

  const scrollToActiveLyric = useCallback((behavior: ScrollBehavior = "smooth") => {
    const container = lyricsRef.current;
    const activeLine = container?.querySelector<HTMLElement>("[data-active='true']");
    if (!container || !activeLine) return;
    window.cancelAnimationFrame(lyricScrollFrameRef.current ?? 0);
    const target = Math.max(0, Math.min(
      container.scrollHeight - container.clientHeight,
      activeLine.offsetTop + activeLine.offsetHeight / 2 - container.clientHeight / 2,
    ));
    if (behavior === "auto" || window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
      container.scrollTop = target;
      return;
    }
    const start = container.scrollTop;
    const distance = target - start;
    if (Math.abs(distance) < 1) return;
    const startedAt = performance.now();
    const animateScroll = (time: number) => {
      const progress = Math.min(1, (time - startedAt) / 620);
      const eased = 1 - Math.pow(1 - progress, 4);
      container.scrollTop = start + distance * eased;
      if (progress < 1) lyricScrollFrameRef.current = window.requestAnimationFrame(animateScroll);
    };
    lyricScrollFrameRef.current = window.requestAnimationFrame(animateScroll);
  }, []);
  const returnToCurrentLyric = useCallback(() => {
    window.clearTimeout(manualTimerRef.current);
    pointerLeftWhileManualRef.current = false;
    setManualLyricScrolling(false);
    window.requestAnimationFrame(() => scrollToActiveLyric());
  }, [scrollToActiveLyric]);
  const enterManualLyricMode = useCallback(() => {
    window.cancelAnimationFrame(lyricScrollFrameRef.current ?? 0);
    setManualLyricScrolling(true);
    window.clearTimeout(manualTimerRef.current);
    manualTimerRef.current = window.setTimeout(returnToCurrentLyric, 5_000);
  }, [returnToCurrentLyric]);
  useEffect(() => {
    if (activeLyric >= 0 && !manualLyricScrolling) scrollToActiveLyric();
  }, [activeLyric, manualLyricScrolling, scrollToActiveLyric]);
  useEffect(() => {
    window.clearTimeout(manualTimerRef.current);
    window.cancelAnimationFrame(lyricScrollFrameRef.current ?? 0);
    pointerLeftWhileManualRef.current = false;
    setManualLyricScrolling(false);
    const resetFrame = window.requestAnimationFrame(() => scrollToActiveLyric());
    return () => {
      window.cancelAnimationFrame(resetFrame);
      window.clearTimeout(manualTimerRef.current);
      window.cancelAnimationFrame(lyricScrollFrameRef.current ?? 0);
    };
  }, [scrollToActiveLyric, track?.syncId]);
  useEffect(() => {
    if (!manualLyricScrolling) return;
    const handleOutsidePointer = (event: PointerEvent) => {
      if (!pointerLeftWhileManualRef.current || lyricsRef.current?.contains(event.target as Node)) return;
      returnToCurrentLyric();
    };
    document.addEventListener("pointerdown", handleOutsidePointer, true);
    return () => document.removeEventListener("pointerdown", handleOutsidePointer, true);
  }, [manualLyricScrolling, returnToCurrentLyric]);

  return (
    <div
      className={`poster-lyrics ${activeLyric < 0 ? "is-untimed" : ""} ${manualLyricScrolling ? "is-manual" : ""}`}
      ref={lyricsRef}
      aria-live="off"
    >
      {lyrics.length ? (
        <div
          className="poster-lyrics__track"
          onWheel={enterManualLyricMode}
          onMouseEnter={() => { pointerLeftWhileManualRef.current = false; }}
          onMouseLeave={() => { if (manualLyricScrolling) pointerLeftWhileManualRef.current = true; }}
        >
          {lyrics.map((line, index) => {
            const contentLength = Math.max(line.text.length, line.translation?.length ?? 0);
            const activeScale = contentLength > 42 ? 1.01 : contentLength > 30 ? 1.05 : contentLength > 20 ? 1.1 : 1.18;
            return (
            <button
              type="button"
              key={`${line.timeSeconds ?? "plain"}-${index}-${line.text}`}
              className={`poster-lyrics__line ${index === activeLyric ? "is-active" : ""}`}
              data-active={index === activeLyric}
              disabled={line.timeSeconds === null}
              onClick={() => line.timeSeconds !== null && onSeek(Math.max(0, line.timeSeconds - lyricOffsetMs / 1000))}
              style={{ "--lyric-active-scale": activeScale } as CSSProperties}
            >
              <span className="poster-lyrics__copy">
                <span>{line.text}</span>
                {line.translation ? <small>{line.translation}</small> : null}
              </span>
            </button>
          );})}
        </div>
      ) : <p className="poster-lyrics__empty">{t("now.noLyrics")}</p>}
    </div>
  );
}

interface HomeViewProps {
  tracks: Track[];
  recommendations: Track[];
  featuredTrack?: Track;
  works: Array<[string, Track[]]>;
  workLabel: string;
  totalListenSeconds: number;
  trackCount: number;
  onPlay: (track: Track) => void;
  onSelect: (track: Track) => void;
  onEdit: (track: Track) => void;
  onFeatured: (id: string) => void;
  onCycleFeatured: (direction: number) => void;
  onNavigate: (view: ViewId) => void;
  onOpenWork: (name: string) => void;
  onChooseFolder: () => void;
}

function HomeView({ tracks, recommendations, featuredTrack, works, workLabel, totalListenSeconds, trackCount, onPlay, onSelect, onEdit, onFeatured, onCycleFeatured, onNavigate, onOpenWork, onChooseFolder }: HomeViewProps) {
  const { t, language } = useI18n();
  if (!featuredTrack) {
    return (
      <div className="page page--home page--empty-library">
        <EmptyState
          icon={Music2}
          title={t("home.emptyTitle")}
          body={t("home.emptyBody")}
          action={t("home.chooseFolder")}
          onAction={onChooseFolder}
        />
        <section className="quick-stats" aria-label={t("home.overview")}>
          <article><span>{t("home.library")}</span><strong>0</strong><small>{t("home.songUnit")}</small></article>
          <article><span>{workLabel}</span><strong>0</strong><small>{t("home.groupUnit")}</small></article>
          <article><span>{t("home.listened")}</span><strong>0</strong><small>{t("home.waitingMemory")}</small></article>
        </section>
      </div>
    );
  }
  const featuredIndex = Math.max(0, recommendations.findIndex((track) => track.syncId === featuredTrack.syncId));
  const picks = Array.from({ length: Math.min(4, recommendations.length) }, (_, offset) => (
    recommendations[(featuredIndex + offset) % recommendations.length]
  ));
  const recent = tracks
    .filter((track) => Boolean(track.lastPlayedAt) || track.playSeconds > 0)
    .sort((left, right) => {
      const playedAtDifference = (Date.parse(right.lastPlayedAt || "") || 0) - (Date.parse(left.lastPlayedAt || "") || 0);
      return playedAtDifference || right.playSeconds - left.playSeconds;
    })
    .slice(0, 8);
  return (
    <div className="page page--home">
      <section className="featured" key={featuredTrack.syncId} style={coverBackground(featuredTrack)} onContextMenu={(event) => editTrackFromContextMenu(event, featuredTrack, onEdit)}>
        <div className="featured__wash"><CoverArt track={featuredTrack} decorative /></div>
        <div className="featured__copy">
          <span className="eyebrow"><Sparkles size={14} /> {t("home.daily")}</span>
          <h2>{featuredTrack.title}</h2>
          <p>{featuredTrack.artist} · {featuredTrack.album}</p>
          <div className="featured__actions">
            <button className="button button--primary" onClick={() => onPlay(featuredTrack)}><Play size={17} fill="currentColor" />{t("home.play")}</button>
            <button className="icon-button icon-button--glass" aria-label={t("home.more")}><MoreHorizontal size={19} /></button>
          </div>
          <div className="featured__meta"><span>{featuredTrack.genre || t("home.localMusic")}</span><span>{featuredTrack.year || "ARMusic"}</span><span>{formatDuration(featuredTrack.durationSeconds)}</span></div>
        </div>
        <div className="featured__art"><CoverArt track={featuredTrack} /></div>
        <div className="featured__rail">
          {picks.map((track) => (
            <button key={track.syncId} className={track.syncId === featuredTrack.syncId ? "is-active" : ""} onClick={() => onFeatured(track.syncId)} onContextMenu={(event) => editTrackFromContextMenu(event, track, onEdit)}>
              <CoverArt track={track} decorative /><span><strong>{track.title}</strong><small>{track.artist}</small></span>
            </button>
          ))}
        </div>
        <div className="featured__arrows">
          <button onClick={() => onCycleFeatured(-1)} aria-label={t("home.previousPick")}><ChevronLeft size={18} /></button>
          <button onClick={() => onCycleFeatured(1)} aria-label={t("home.nextPick")}><ChevronRight size={18} /></button>
        </div>
        <span className="featured__timer" aria-hidden="true"><i /></span>
      </section>

      <section className="quick-stats" aria-label={t("home.overview")}>
        <article><span>{t("home.library")}</span><strong>{trackCount}</strong><small>{t("home.songUnit")}</small></article>
        <article><span>{workLabel}</span><strong>{works.length}</strong><small>{t("home.groupUnit")}</small></article>
        <article><span>{t("home.listened")}</span><strong>{formatListenTime(totalListenSeconds, language)}</strong><small>{t("home.yourMemory")}</small></article>
      </section>

      <div className="home-library-stage">
        <section className="home-shelf home-shelf--recent">
          <SectionHeading title={t("home.recent")} subtitle={t("home.recentSub")} action={t("home.allSongs")} onAction={() => onNavigate("songs")} />
          <div className="cover-grid cover-grid--recent" data-count={recent.length}>
            {recent.map((track) => <AlbumCard key={track.syncId} track={track} onPlay={onPlay} onOpen={onSelect} onEdit={onEdit} />)}
          </div>
        </section>

        <section className="home-shelf home-shelf--works">
          <SectionHeading title={t("home.byWorkLabel", { label: workLabel })} subtitle={t("home.byWorkSub")} action={t("home.viewWorkLabel", { label: workLabel })} onAction={() => onNavigate("works")} />
          <div className="work-strip">
            {works.slice(0, 4).map(([name, group]) => (
              <button
                className="work-card"
                key={name}
                style={coverBackground(group[0])}
                onClick={() => onOpenWork(name)}
                onContextMenu={(event) => editTrackFromContextMenu(event, group[0], onEdit)}
              >
                <div className="work-card__covers">
                  {group.slice(0, 3).map((track) => <CoverArt track={track} key={track.syncId} decorative />)}
                </div>
                <span><strong>{name}</strong><small>{t("common.songCount", { count: group.length })} · {group[0].artist}</small></span>
                <Play size={16} fill="currentColor" />
              </button>
            ))}
          </div>
        </section>
      </div>
    </div>
  );
}

function SectionHeading({ title, subtitle, action, onAction }: { title: string; subtitle: string; action: string; onAction: () => void }) {
  return (
    <div className="section-heading">
      <div><h2>{title}</h2><p>{subtitle}</p></div>
      <button onClick={onAction}>{action}<ChevronRight size={16} /></button>
    </div>
  );
}

function AlbumCard({ track, onPlay, onOpen, onEdit }: { track: Track; onPlay: (track: Track) => void; onOpen: (track: Track) => void; onEdit: (track: Track) => void }) {
  const { t } = useI18n();
  return (
    <article className="album-card" tabIndex={0} onDoubleClick={() => onPlay(track)} onContextMenu={(event) => editTrackFromContextMenu(event, track, onEdit)} onKeyDown={(event) => event.key === "Enter" && onOpen(track)}>
      <button className="album-card__art" onClick={() => onOpen(track)} onContextMenu={(event) => editTrackFromContextMenu(event, track, onEdit)} aria-label={t("common.open", { title: track.title })}>
        <CoverArt track={track} />
        <span className="album-card__play" data-track-context-ignore onClick={(event) => { event.stopPropagation(); onPlay(track); }}><Play size={18} fill="currentColor" /></span>
      </button>
      <div><strong>{track.title}</strong><span>{track.artist}</span></div>
    </article>
  );
}

interface SongsViewProps {
  tracks: Track[];
  workLabel: string;
  currentTrackId: string;
  isPlaying: boolean;
  sortMode: SongSortMode;
  scanMessage: string;
  onSort: (sort: SongSortMode) => void;
  onSelect: (track: Track) => void;
  onPlay: (track: Track) => void;
  onEdit: (track: Track) => void;
  onAddToPlaylist: (track: Track) => void;
  onChooseFolder: () => void;
}

function SongsView({ tracks, workLabel, currentTrackId, isPlaying, sortMode, scanMessage, onSort, onSelect, onPlay, onEdit, onAddToPlaylist, onChooseFolder }: SongsViewProps) {
  const { t } = useI18n();
  const sortMenuRef = useRef<HTMLDetailsElement>(null);
  const sortOptions: Array<{ value: SongSortMode; label: MessageKey }> = [
    { value: "title", label: "songs.byTitle" },
    { value: "artist", label: "songs.byArtist" },
    { value: "work", label: "songs.byWork" },
    { value: "album", label: "songs.byAlbum" },
    { value: "recent", label: "songs.recent" },
    { value: "recentPlayed", label: "songs.recentPlayed" },
    { value: "listenTime", label: "songs.listenTime" },
    { value: "random", label: "songs.random" },
  ];
  const activeSort = sortOptions.find((option) => option.value === sortMode) ?? sortOptions[0];
  return (
    <div className="page">
      <PageHeading eyebrow="LOCAL LIBRARY" title={t("songs.title")} subtitle={scanMessage}>
        <button className="button button--soft" onClick={() => tracks[0] && onPlay(tracks[0])}><Play size={16} fill="currentColor" />{t("songs.playAll")}</button>
        <details className="sort-menu" ref={sortMenuRef}>
          <summary aria-label={t("songs.sort")}><ArrowUpDown size={15} /><span>{t(activeSort.label)}</span><ChevronDown size={14} /></summary>
          <div className="sort-menu__popover" role="menu">
            <small>{t("songs.sort")}</small>
            {sortOptions.map((option) => <button key={option.value} className={sortMode === option.value ? "is-active" : ""} onClick={() => { onSort(option.value); sortMenuRef.current?.removeAttribute("open"); }} role="menuitemradio" aria-checked={sortMode === option.value}><span>{option.value === "work" ? t("songs.byWorkLabel", { label: workLabel }) : t(option.label)}</span>{sortMode === option.value ? <Check size={14} /> : null}</button>)}
          </div>
        </details>
      </PageHeading>
      {tracks.length ? (
        <div className="track-table">
          <div className="track-table__head"><span>#</span><span>{t("songs.song")}</span><span>{t("songs.work")}</span><span>{t("songs.format")}</span><span>{t("songs.duration")}</span><span /></div>
          {tracks.map((track, index) => (
            <TrackRow
              key={track.syncId}
              track={track}
              index={index}
              active={track.syncId === currentTrackId}
              playing={track.syncId === currentTrackId && isPlaying}
              onSelect={onSelect}
              onPlay={onPlay}
              onEdit={onEdit}
              onAddToPlaylist={onAddToPlaylist}
            />
          ))}
        </div>
      ) : (
        <EmptyState icon={FolderOpen} title={t("songs.emptyTitle")} body={t("songs.emptyBody")} action={t("home.chooseFolder")} onAction={onChooseFolder} />
      )}
    </div>
  );
}

function TrackRow({ track, index, active, playing, onSelect, onPlay, onEdit, onAddToPlaylist }: { track: Track; index: number; active: boolean; playing: boolean; onSelect: (track: Track) => void; onPlay: (track: Track) => void; onEdit: (track: Track) => void; onAddToPlaylist?: (track: Track) => void }) {
  const { t } = useI18n();
  return (
    <div className={`track-row ${active ? "is-active" : ""}`} tabIndex={0} onClick={() => onSelect(track)} onDoubleClick={() => onPlay(track)} onContextMenu={(event) => editTrackFromContextMenu(event, track, onEdit)} onKeyDown={(event: ReactKeyboardEvent) => event.key === "Enter" && onPlay(track)}>
      <button className="track-row__index" onClick={(event) => { event.stopPropagation(); onPlay(track); }} aria-label={t("songs.playTrack", { title: track.title })}>
        <span>{String(index + 1).padStart(2, "0")}</span>{playing ? <i className="playing-bars"><b /><b /><b /></i> : <Play size={14} fill="currentColor" />}
      </button>
      <div className="track-row__main"><CoverArt track={track} decorative /><span><strong>{track.title}</strong><small>{track.artist}</small></span></div>
      <span className="track-row__album">{track.album}</span>
      <span className="format-badge">{track.relativePath.split(".").pop()?.toUpperCase() || "AUDIO"}</span>
      <time>{formatDuration(track.durationSeconds)}</time>
      <div className="track-row__actions">
        {onAddToPlaylist ? <button onClick={(event) => { event.stopPropagation(); onAddToPlaylist(track); }} aria-label={t("playlists.addToPlaylist")} title={t("playlists.addToPlaylist")}><ListPlus size={16} /></button> : null}
        <button onClick={(event) => { event.stopPropagation(); onEdit(track); }} aria-label={t("songs.editTags")} title={t("songs.editTags")}><PencilLine size={16} /></button>
      </div>
    </div>
  );
}

interface GroupedLibraryViewProps {
  kind: GroupKind;
  workLabel?: string;
  groups: Array<[string, Track[]]>;
  selectedName: string;
  currentTrackId: string;
  isPlaying: boolean;
  onOpen: (name: string) => void;
  onBack: () => void;
  onPlay: (track: Track, groupName: string, tracks: Track[]) => void;
  onEdit: (track: Track) => void;
}

interface GroupSummary {
  name: string;
  tracks: Track[];
  listenSeconds: number;
  lastPlayedAt?: string;
  lastPlayedMs: number;
}

interface GroupSortOption<T extends string> {
  value: T;
  label: MessageKey;
}

function GroupSortControl<T extends string>({
  label,
  value,
  direction,
  options,
  onValue,
  onDirection,
}: {
  label: string;
  value: T;
  direction: SortDirection;
  options: Array<GroupSortOption<T>>;
  onValue: (value: T) => void;
  onDirection: () => void;
}) {
  const { t } = useI18n();
  const detailsRef = useRef<HTMLDetailsElement>(null);
  const active = options.find((option) => option.value === value) ?? options[0];

  useEffect(() => {
    const closeOutside = (event: PointerEvent) => {
      const details = detailsRef.current;
      if (!details?.open || details.contains(event.target as Node)) return;
      details.removeAttribute("open");
    };
    const closeOnEscape = (event: globalThis.KeyboardEvent) => {
      if (event.key === "Escape") detailsRef.current?.removeAttribute("open");
    };
    document.addEventListener("pointerdown", closeOutside, true);
    document.addEventListener("keydown", closeOnEscape);
    return () => {
      document.removeEventListener("pointerdown", closeOutside, true);
      document.removeEventListener("keydown", closeOnEscape);
    };
  }, []);

  return (
    <div className="group-sort-control">
      <details ref={detailsRef} className="group-sort-menu">
        <summary aria-label={label}>
          <ArrowUpDown size={15} />
          <span>{t(active.label)}</span>
          <ChevronDown size={14} />
        </summary>
        <div className="group-sort-menu__popover" role="menu">
          <small>{label}</small>
          {options.map((option) => (
            <button
              type="button"
              key={option.value}
              className={option.value === value ? "is-active" : ""}
              role="menuitemradio"
              aria-checked={option.value === value}
              onClick={() => {
                onValue(option.value);
                detailsRef.current?.removeAttribute("open");
              }}
            >
              <span>{t(option.label)}</span>
              {option.value === value ? <Check size={14} /> : null}
            </button>
          ))}
        </div>
      </details>
      <button
        type="button"
        className="group-sort-direction"
        disabled={value === "random"}
        onClick={onDirection}
        aria-label={t("groups.changeDirection", { direction: t(direction === "asc" ? "groups.ascending" : "groups.descending") })}
        title={t(direction === "asc" ? "groups.ascending" : "groups.descending")}
      >
        {direction === "asc" ? <ArrowUp size={16} /> : <ArrowDown size={16} />}
      </button>
    </div>
  );
}

function playedAtMs(track: Track): number {
  if (!track.lastPlayedAt) return 0;
  const value = Date.parse(track.lastPlayedAt);
  return Number.isFinite(value) ? value : 0;
}

function directed(value: number, direction: SortDirection): number {
  return direction === "asc" ? value : -value;
}

function compareOptionalNumber(left: number, right: number, direction: SortDirection): number {
  if (!left && !right) return 0;
  if (!left) return 1;
  if (!right) return -1;
  return directed(left - right, direction);
}

function groupTrackContext(kind: GroupKind, track: Track): string {
  if (kind === "work") return track.album || track.work || "—";
  return track.work || track.album || "—";
}

function defaultDirectionForSort(value: GroupSortMode | GroupTrackSortMode): SortDirection {
  return value === "name" || value === "title" || value === "context" ? "asc" : "desc";
}

function GroupedLibraryView({ kind, workLabel, groups, selectedName, currentTrackId, isPlaying, onOpen, onBack, onPlay, onEdit }: GroupedLibraryViewProps) {
  const { t, language } = useI18n();
  const storagePrefix = `armusic-${kind}`;
  const [groupSort, setGroupSort] = useState<GroupSortMode>(() => {
    const saved = localStorage.getItem(`${storagePrefix}-group-sort`);
    return saved === "songCount" || saved === "recentPlayed" || saved === "listenTime" ? saved : "name";
  });
  const [groupDirection, setGroupDirection] = useState<SortDirection>(() => localStorage.getItem(`${storagePrefix}-group-direction`) === "desc" ? "desc" : "asc");
  const [trackSort, setTrackSort] = useState<GroupTrackSortMode>(() => {
    const saved = localStorage.getItem(`${storagePrefix}-track-sort`);
    return saved === "context" || saved === "recentPlayed" || saved === "listenTime" ? saved : "title";
  });
  const [trackDirection, setTrackDirection] = useState<SortDirection>(() => localStorage.getItem(`${storagePrefix}-track-direction`) === "desc" ? "desc" : "asc");
  const [groupRandomSeed, setGroupRandomSeed] = useState(Date.now);
  const [trackRandomSeed, setTrackRandomSeed] = useState(Date.now);
  const collator = useMemo(() => new Intl.Collator(language, { numeric: true, sensitivity: "base" }), [language]);

  useEffect(() => {
    if (groupSort !== "random") localStorage.setItem(`${storagePrefix}-group-sort`, groupSort);
    localStorage.setItem(`${storagePrefix}-group-direction`, groupDirection);
    if (trackSort !== "random") localStorage.setItem(`${storagePrefix}-track-sort`, trackSort);
    localStorage.setItem(`${storagePrefix}-track-direction`, trackDirection);
    localStorage.removeItem(`${storagePrefix}-group-random-seed`);
    localStorage.removeItem(`${storagePrefix}-track-random-seed`);
  }, [groupDirection, groupSort, storagePrefix, trackDirection, trackSort]);

  const summaries = useMemo<GroupSummary[]>(() => groups.map(([name, tracks]) => {
    const lastTrack = tracks.reduce<Track | undefined>((latest, track) => (
      !latest || playedAtMs(track) > playedAtMs(latest) ? track : latest
    ), undefined);
    return {
      name,
      tracks,
      listenSeconds: tracks.reduce((total, track) => total + track.playSeconds, 0),
      lastPlayedAt: lastTrack?.lastPlayedAt,
      lastPlayedMs: lastTrack ? playedAtMs(lastTrack) : 0,
    };
  }), [groups]);

  const sortedGroups = useMemo(() => [...summaries].sort((left, right) => {
    let comparison = 0;
    if (groupSort === "name") comparison = directed(collator.compare(left.name, right.name), groupDirection);
    if (groupSort === "songCount") comparison = directed(left.tracks.length - right.tracks.length, groupDirection);
    if (groupSort === "recentPlayed") comparison = compareOptionalNumber(left.lastPlayedMs, right.lastPlayedMs, groupDirection);
    if (groupSort === "listenTime") comparison = directed(left.listenSeconds - right.listenSeconds, groupDirection);
    if (groupSort === "random") comparison = stableNumber(`${groupRandomSeed}:${left.name}`) - stableNumber(`${groupRandomSeed}:${right.name}`);
    return comparison || collator.compare(left.name, right.name);
  }), [collator, groupDirection, groupRandomSeed, groupSort, summaries]);

  const selected = summaries.find((group) => group.name === selectedName);
  const sortedTracks = useMemo(() => {
    if (!selected) return [];
    return [...selected.tracks].sort((left, right) => {
      let comparison = 0;
      if (trackSort === "title") comparison = directed(collator.compare(left.title, right.title), trackDirection);
      if (trackSort === "context") comparison = directed(collator.compare(groupTrackContext(kind, left), groupTrackContext(kind, right)), trackDirection);
      if (trackSort === "recentPlayed") comparison = compareOptionalNumber(playedAtMs(left), playedAtMs(right), trackDirection);
      if (trackSort === "listenTime") comparison = directed(left.playSeconds - right.playSeconds, trackDirection);
      if (trackSort === "random") comparison = stableNumber(`${trackRandomSeed}:${left.syncId}`) - stableNumber(`${trackRandomSeed}:${right.syncId}`);
      return comparison || collator.compare(left.title, right.title);
    });
  }, [collator, kind, selected, trackDirection, trackRandomSeed, trackSort]);

  const groupOptions: Array<GroupSortOption<GroupSortMode>> = [
    { value: "name", label: "groups.byName" },
    { value: "songCount", label: "groups.bySongCount" },
    { value: "recentPlayed", label: "songs.recentPlayed" },
    { value: "listenTime", label: "songs.listenTime" },
    { value: "random", label: "songs.random" },
  ];
  const trackOptions: Array<GroupSortOption<GroupTrackSortMode>> = [
    { value: "title", label: "songs.byTitle" },
    { value: "context", label: kind === "work" ? "songs.byAlbum" : "groups.byWorkAlbum" },
    { value: "recentPlayed", label: "songs.recentPlayed" },
    { value: "listenTime", label: "songs.listenTime" },
    { value: "random", label: "songs.random" },
  ];
  const Icon = kind === "work" ? Disc3 : UsersRound;
  const resolvedWorkLabel = workLabel || t("nav.works");
  const pageTitle = kind === "work" ? resolvedWorkLabel : t("nav.artists");
  const pageSubtitle = t(kind === "work" ? "works.subtitle" : "artists.subtitle");
  const emptyTitle = kind === "work" ? t("works.emptyLabel", { label: resolvedWorkLabel }) : t("artists.emptyTitle");
  const emptyBody = t(kind === "work" ? "works.emptyBody" : "artists.emptyBody");
  const backLabel = kind === "work" ? t("works.backLabel", { label: resolvedWorkLabel }) : t("artists.back");

  if (selected) {
    const firstTrack = sortedTracks[0] ?? selected.tracks[0];
    return (
      <div className={`page grouped-library-page grouped-library-page--detail is-${kind}`}>
        <div className="grouped-detail__toolbar">
          <button type="button" className="button button--soft" onClick={onBack}><ChevronLeft size={16} />{backLabel}</button>
          <GroupSortControl
            label={t("groups.sortSongs")}
            value={trackSort}
            direction={trackDirection}
            options={trackOptions}
            onValue={(value) => { if (value === "random") setTrackRandomSeed(Date.now()); setTrackSort(value); setTrackDirection(defaultDirectionForSort(value)); }}
            onDirection={() => setTrackDirection((value) => value === "asc" ? "desc" : "asc")}
          />
        </div>

        <section className="grouped-detail__masthead" style={firstTrack ? coverBackground(firstTrack) : undefined}>
          <div className={`grouped-detail__covers count-${Math.min(4, selected.tracks.length)}`}>
            {selected.tracks.slice(0, 4).map((track) => <CoverArt key={track.syncId} track={track} decorative />)}
          </div>
          <div className="grouped-detail__copy">
            <span>{kind === "work" ? `${resolvedWorkLabel} / TRACKS` : "ARTIST / TRACKS"}</span>
            <h1>{selected.name}</h1>
            <p>
              {t("common.songCount", { count: selected.tracks.length })}
              <i />
              {t("groups.totalListen", { time: formatListenTime(selected.listenSeconds, language) })}
            </p>
          </div>
          <button
            type="button"
            className="button button--primary grouped-detail__play"
            disabled={!firstTrack}
            onClick={() => firstTrack && onPlay(firstTrack, selected.name, sortedTracks)}
          >
            <Play size={17} fill="currentColor" />{t("songs.playAll")}
          </button>
        </section>

        <div className="grouped-track-table">
          <div className="grouped-track-table__head">
            <span>#</span>
            <span>{t("songs.song")}</span>
            <span>{t(kind === "work" ? "songs.byAlbum" : "groups.byWorkAlbum")}</span>
            <span>{t("groups.recentAndTime")}</span>
            <span />
          </div>
          {sortedTracks.map((track, index) => {
            const active = track.syncId === currentTrackId;
            const context = groupTrackContext(kind, track);
            const playTrack = () => onPlay(track, selected.name, sortedTracks);
            return (
              <div
                key={track.syncId}
                className={`grouped-track-row ${active ? "is-active" : ""}`}
                role="button"
                tabIndex={0}
                onClick={playTrack}
                onContextMenu={(event) => editTrackFromContextMenu(event, track, onEdit)}
                onKeyDown={(event: ReactKeyboardEvent) => event.key === "Enter" && playTrack()}
              >
                <button type="button" className="grouped-track-row__index" onClick={(event) => { event.stopPropagation(); playTrack(); }} aria-label={t("songs.playTrack", { title: track.title })}>
                  <span>{String(index + 1).padStart(2, "0")}</span>
                  {active && isPlaying ? <i className="playing-bars"><b /><b /><b /></i> : <Play size={13} fill="currentColor" />}
                </button>
                <div className="grouped-track-row__main">
                  <CoverArt track={track} decorative />
                  <span>
                    <strong>{track.title}</strong>
                    <small>{track.artist}</small>
                    <em>{context} · {formatListenTime(track.playSeconds, language)}</em>
                  </span>
                </div>
                <span className="grouped-track-row__context">{context}</span>
                <span className="grouped-track-row__stats">
                  <time>{formatLastPlayed(track.lastPlayedAt, language, t("misc.longAgo"))}</time>
                  <small>{formatListenTime(track.playSeconds, language)}</small>
                </span>
                <button type="button" className="grouped-track-row__edit" onClick={(event) => { event.stopPropagation(); onEdit(track); }} aria-label={t("songs.editTags")} title={t("songs.editTags")}><PencilLine size={15} /></button>
              </div>
            );
          })}
        </div>
      </div>
    );
  }

  return (
    <div className={`page grouped-library-page is-${kind}`}>
      <PageHeading eyebrow={kind === "work" ? resolvedWorkLabel : "ARTISTS"} title={pageTitle} subtitle={pageSubtitle}>
        <GroupSortControl
          label={t("groups.sortGroups")}
          value={groupSort}
          direction={groupDirection}
          options={groupOptions}
          onValue={(value) => { if (value === "random") setGroupRandomSeed(Date.now()); setGroupSort(value); setGroupDirection(defaultDirectionForSort(value)); }}
          onDirection={() => setGroupDirection((value) => value === "asc" ? "desc" : "asc")}
        />
      </PageHeading>
      {sortedGroups.length ? (
        <div className="grouped-library-grid">
          {sortedGroups.map((group) => {
            const firstTrack = group.tracks[0];
            return (
              <article className="grouped-library-card" key={group.name}>
                <button
                  type="button"
                  className="grouped-library-card__surface"
                  onClick={() => onOpen(group.name)}
                  onContextMenu={(event) => firstTrack && editTrackFromContextMenu(event, firstTrack, onEdit)}
                >
                  <span className="grouped-library-card__art">
                    <CoverArt track={firstTrack} decorative />
                  </span>
                  <span className="grouped-library-card__copy">
                    <strong>{group.name}</strong>
                    <small>{t("common.songCount", { count: group.tracks.length })} · {formatListenTime(group.listenSeconds, language)}</small>
                    <time>{formatLastPlayed(group.lastPlayedAt, language, t("misc.longAgo"))}</time>
                  </span>
                </button>
                <button
                  type="button"
                  className="grouped-library-card__play"
                  disabled={!firstTrack}
                  onClick={() => firstTrack && onPlay(firstTrack, group.name, group.tracks)}
                  aria-label={t("groups.playGroup", { title: group.name })}
                  title={t("songs.playAll")}
                >
                  <Play size={16} fill="currentColor" />
                </button>
              </article>
            );
          })}
        </div>
      ) : <EmptyState icon={Icon} title={emptyTitle} body={emptyBody} />}
    </div>
  );
}

function WorksView(props: Omit<GroupedLibraryViewProps, "kind">) {
  return <GroupedLibraryView {...props} kind="work" />;
}

function ArtistsView(props: Omit<GroupedLibraryViewProps, "kind">) {
  return <GroupedLibraryView {...props} kind="artist" />;
}

const MemoHomeView = memo(HomeView);
const MemoSongsView = memo(SongsView);
const MemoWorksView = memo(WorksView);
const MemoArtistsView = memo(ArtistsView);

function HistoryView({ tracks, onPlay, onEdit }: { tracks: Track[]; onPlay: (track: Track) => void; onEdit: (track: Track) => void }) {
  const { t, language } = useI18n();
  const sorted = tracks
    .filter((track) => track.playSeconds > 0 || Boolean(track.lastPlayedAt))
    .sort((a, b) => b.playSeconds - a.playSeconds);
  return (
    <div className="page">
      <PageHeading eyebrow="LISTENING HISTORY" title={t("nav.history")} subtitle={t("history.subtitle")} />
      {sorted.length ? (
        <>
          <div className="history-hero">
            <div><span>{t("history.past")}</span><strong>{formatListenTime(sorted.reduce((sum, track) => sum + track.playSeconds, 0), language)}</strong><small>{t("history.total")}</small></div>
            {sorted.slice(0, 4).map((track, index) => <CoverArt key={track.syncId} track={track} decorative className={`history-cover history-cover--${index}`} />)}
          </div>
          <div className="history-list">
            {sorted.map((track, index) => (
              <button key={track.syncId} onDoubleClick={() => onPlay(track)} onClick={() => onPlay(track)} onContextMenu={(event) => editTrackFromContextMenu(event, track, onEdit)}>
                <span>{String(index + 1).padStart(2, "0")}</span><CoverArt track={track} decorative /><strong>{track.title}<small>{track.artist} · {formatLastPlayed(track.lastPlayedAt, language, t("misc.longAgo"))}</small></strong><em>{formatListenTime(track.playSeconds, language)}</em><Play size={16} fill="currentColor" />
              </button>
            ))}
          </div>
        </>
      ) : <EmptyState icon={History} title={t("history.emptyTitle")} body={t("history.emptyBody")} />}
    </div>
  );
}

const defaultWishlistCategories: WishlistCategoryData[] = [
  { id: "to-listen", title: "准备听", color: 4_281_302_387, items: [] },
  { id: "anime", title: "动漫", color: 4_291_844_686, items: [] },
  { id: "manga", title: "漫画", color: 4_285_949_616, items: [] },
  { id: "novel", title: "小说", color: 4_290_344_997, items: [] },
];

type LegacyWishlistCategory = Partial<WishlistCategoryData> & {
  titleKey?: "wishlist.toListen" | "wishlist.anime" | "wishlist.manga" | "wishlist.novel";
};

const wishlistTitleByKey: Record<NonNullable<LegacyWishlistCategory["titleKey"]>, string> = {
  "wishlist.toListen": "准备听",
  "wishlist.anime": "动漫",
  "wishlist.manga": "漫画",
  "wishlist.novel": "小说",
};

function readLegacyWishlist(): WishlistCategoryData[] {
  try {
    const saved = localStorage.getItem("armusic-wishlist-v1");
    const parsed = saved ? JSON.parse(saved) as LegacyWishlistCategory[] : null;
    if (!Array.isArray(parsed) || parsed.length === 0) {
      return defaultWishlistCategories.map((category) => ({ ...category, items: [] }));
    }
    return parsed.flatMap((category, index) => {
      const fallback = defaultWishlistCategories[index];
      const id = typeof category.id === "string" && category.id.trim()
        ? category.id.trim()
        : fallback?.id || `custom-${index + 1}`;
      const titleFromKey = category.titleKey ? wishlistTitleByKey[category.titleKey] : "";
      const title = typeof category.title === "string" && category.title.trim()
        ? category.title.trim()
        : titleFromKey || fallback?.title || `栏目 ${index + 1}`;
      const color = typeof category.color === "number" && Number.isInteger(category.color)
        ? category.color
        : fallback?.color || 4_284_509_502;
      const items = Array.isArray(category.items)
        ? category.items.filter((item): item is string => typeof item === "string" && Boolean(item.trim())).map((item) => item.trim())
        : [];
      return [{ id, title, color, items }];
    });
  } catch {
    return defaultWishlistCategories.map((category) => ({ ...category, items: [] }));
  }
}

function wishlistTitleKey(category: WishlistCategoryData): MessageKey | null {
  const id = category.id.trim().toLocaleLowerCase();
  const title = category.title.trim().toLocaleLowerCase();
  if (id === "to-listen" || ["准备听", "準備聽", "to listen", "聴きたい曲"].includes(title)) return "wishlist.toListen";
  if (id === "anime" || ["动漫", "動畫", "动画", "anime", "アニメ"].includes(title)) return "wishlist.anime";
  if (id === "manga" || ["漫画", "漫畫", "manga", "マンガ"].includes(title)) return "wishlist.manga";
  if (id === "novel" || ["小说", "小說", "novel", "novels", "小説"].includes(title)) return "wishlist.novel";
  return null;
}

function WishlistView({ bridge, onNotice }: { bridge?: ARMusicBridge; onNotice: (message: string) => void }) {
  const { t } = useI18n();
  const legacyCategoriesRef = useRef<WishlistCategoryData[] | null>(null);
  if (!legacyCategoriesRef.current) legacyCategoriesRef.current = readLegacyWishlist();
  const [categories, setCategories] = useState<WishlistCategoryData[]>(legacyCategoriesRef.current);
  const [snapshotId, setSnapshotId] = useState("");
  const [saving, setSaving] = useState(false);
  const savingRef = useRef(false);
  const [activeId, setActiveId] = useState(() => categories[0]?.id || "to-listen");
  const [draft, setDraft] = useState("");
  const active = categories.find((category) => category.id === activeId) || categories[0];

  useEffect(() => {
    if (!bridge) return;
    let activeRequest = true;
    void bridge.migrateLegacyWishlist(legacyCategoriesRef.current || []).then((payload) => {
      if (!activeRequest) return;
      setCategories(payload.categories);
      setSnapshotId(payload.snapshotId);
      localStorage.setItem("armusic-wishlist-v1", JSON.stringify(payload.categories));
    }).catch(() => {
      if (activeRequest) onNotice(t("wishlist.saveFailed"));
    });
    return () => { activeRequest = false; };
  }, [bridge, onNotice, t]);

  useEffect(() => {
    if (categories.some((category) => category.id === activeId)) return;
    setActiveId(categories[0]?.id || "");
  }, [activeId, categories]);

  const persistCategories = async (next: WishlistCategoryData[], additive: boolean) => {
    if (savingRef.current) return false;
    const previous = categories;
    savingRef.current = true;
    setSaving(true);
    setCategories(next);
    try {
      if (!bridge) {
        localStorage.setItem("armusic-wishlist-v1", JSON.stringify(next));
        return true;
      }
      const payload: WishlistPayload = additive
        ? await bridge.migrateLegacyWishlist(next)
        : await bridge.saveWishlist({ expectedSnapshotId: snapshotId, categories: next });
      setCategories(payload.categories);
      setSnapshotId(payload.snapshotId);
      localStorage.setItem("armusic-wishlist-v1", JSON.stringify(payload.categories));
      return true;
    } catch {
      if (bridge) {
        try {
          const latest = await bridge.getWishlist();
          setCategories(latest.categories);
          setSnapshotId(latest.snapshotId);
          localStorage.setItem("armusic-wishlist-v1", JSON.stringify(latest.categories));
        } catch {
          setCategories(previous);
        }
      } else {
        setCategories(previous);
      }
      onNotice(t("wishlist.saveFailed"));
      return false;
    } finally {
      savingRef.current = false;
      setSaving(false);
    }
  };

  const categoryName = (category: WishlistCategoryData) => {
    const titleKey = wishlistTitleKey(category);
    return titleKey ? t(titleKey) : category.title || t("wishlist.new");
  };
  const addItem = async () => {
    const text = draft.trim();
    if (!text || !active || savingRef.current) return;
    const next = categories.map((category) => category.id === active.id
      ? { ...category, items: [...category.items, text] }
      : category);
    if (await persistCategories(next, true)) setDraft("");
  };
  const addCategory = async () => {
    if (savingRef.current) return;
    const title = window.prompt(t("wishlist.categoryPrompt"))?.trim();
    if (!title) return;
    const id = `custom-${Date.now()}`;
    const color = [4_281_302_387, 4_291_844_686, 4_285_949_616, 4_290_344_997][categories.length % 4];
    if (await persistCategories([...categories, { id, title, color, items: [] }], true)) setActiveId(id);
  };
  const removeItem = async (index: number) => {
    if (!active || savingRef.current) return;
    const next = categories.map((category) => category.id === active.id
      ? { ...category, items: category.items.filter((_, itemIndex) => itemIndex !== index) }
      : category);
    await persistCategories(next, false);
  };

  return (
    <div className="page">
      <PageHeading eyebrow="WISHLIST" title={t("nav.wishlist")} subtitle={t("wishlist.subtitle")}><button className="button button--soft" disabled={saving} onClick={() => void addCategory()}>{saving ? <RefreshCw className="spin" size={16} /> : <Sparkles size={16} />}{t("wishlist.new")}</button></PageHeading>
      <div className="wishlist-tabs">
        {categories.map((category) => <button key={category.id} disabled={saving} className={category.id === active?.id ? "is-active" : ""} onClick={() => setActiveId(category.id)}>{categoryName(category)} <span>{category.items.length}</span></button>)}
        <button disabled={saving} onClick={() => void addCategory()}>＋</button>
      </div>
      <div className="wishlist-composer">
        <Sparkles size={17} />
        <input disabled={saving} value={draft} onChange={(event) => setDraft(event.target.value)} onKeyDown={(event) => event.key === "Enter" && void addItem()} placeholder={t("wishlist.itemPlaceholder")} />
        <button className="button button--primary" disabled={saving} onClick={() => void addItem()}>{t("wishlist.add")}</button>
      </div>
      {active?.items.length ? <div className="wishlist-notes">{active.items.map((item, index) => (
        <article key={`${item}-${index}`}><span>{String(index + 1).padStart(2, "0")}</span><strong>{item}</strong><button disabled={saving} onClick={() => void removeItem(index)} aria-label={t("wishlist.remove")}><Trash2 size={16} /></button></article>
      ))}</div> : <EmptyState icon={Sparkles} title={t("wishlist.emptyTitle")} body={t("wishlist.emptyBody")} />}
    </div>
  );
}

interface SyncViewProps {
  status: SyncServerStatus;
  isSyncing: boolean;
  folderPath: string;
  trackCount: number;
  upload: Track[];
  download: Track[];
  bridge?: ARMusicBridge;
  onToggle: () => void;
  onChooseFolder: () => void;
  onLibraryChanged: () => void | Promise<void>;
  onNotice: (message: string) => void;
}

function SyncView({ status, isSyncing, folderPath, trackCount, upload, download, bridge, onToggle, onChooseFolder, onLibraryChanged, onNotice }: SyncViewProps) {
  const { t } = useI18n();
  return (
    <div className="page">
      <PageHeading eyebrow="AR MUSIC LINK" title={t("nav.sync")} subtitle={t("sync.subtitle")} />
      <AdbSyncPanel bridge={bridge} onLibraryChanged={onLibraryChanged} onNotice={onNotice} />
      <div className="sync-secondary-heading">
        <span>WI-FI</span>
        <div><strong>{t("sync.lanTitle")}</strong><small>{t("sync.lanBody")}</small></div>
      </div>
      <div className="sync-hero">
        <div className="sync-visual"><span className="sync-orbit" /><div className="device device--desktop"><Disc3 size={34} /><small>{t("sync.songs", { count: trackCount })}</small></div><div className="sync-beam"><i /><i /><i /></div><div className="device device--phone"><Smartphone size={30} /><small>Android</small></div></div>
        <div className="sync-copy"><span className={`status-pill ${status.running ? "is-online" : ""}`}><i />{status.running ? t("sync.online") : t("sync.waiting")}</span><h2>{status.running ? t("sync.found") : t("sync.ready")}</h2><p>{status.running ? t("sync.foundBody") : t("sync.readyBody")}</p><button className="button button--primary" onClick={onToggle} disabled={isSyncing}>{isSyncing ? <RefreshCw className="spin" size={17} /> : <Wifi size={17} />}{status.running ? t("sync.stop") : t("sync.start")}</button></div>
      </div>
      <div className="sync-grid">
        <section><div className="sync-section-title"><FolderOpen size={18} /><span><strong>{t("sync.desktopLibrary")}</strong><small>{folderPath || t("sync.noFolder")}</small></span><button onClick={onChooseFolder}>{t("sync.change")}</button></div><div className="sync-addresses">{status.addresses.length ? status.addresses.map((address) => <code key={address}>{address}</code>) : <p>{t("sync.addressHint")}</p>}</div></section>
        <section><SyncList icon={ArrowUpFromLine} title={t("sync.toPhone")} tracks={upload} /><SyncList icon={ArrowDownToLine} title={t("sync.toDesktop")} tracks={download} /></section>
      </div>
      <div className="security-note"><Wifi size={17} /><span><strong>{t("sync.security")}</strong><small>{t("sync.securityBody")}</small></span></div>
    </div>
  );
}

function SyncList({ icon: Icon, title, tracks }: { icon: LucideIcon; title: string; tracks: Track[] }) {
  return <div className="sync-list"><div><Icon size={17} /><strong>{title}</strong><span>{tracks.length}</span></div>{tracks.slice(0, 3).map((track) => <div className="sync-list__track" key={track.syncId}><CoverArt track={track} decorative /><span><strong>{track.title}</strong><small>{track.artist} · {formatBytes(track.sizeBytes)}</small></span></div>)}</div>;
}

function SettingsView({ theme, folderPath, isScanning, needsMusicFolder, workLabelMode, launchAtStartup, closeToTray, updateCheck, onTheme, onChooseFolder, onWorkLabelMode, onLaunchAtStartup, onCloseToTray, onCheckForUpdate, onOpenGithub, onNotice }: {
  theme: "light" | "dark";
  folderPath: string;
  isScanning: boolean;
  needsMusicFolder: boolean;
  workLabelMode: WorkLabelMode;
  launchAtStartup: boolean;
  closeToTray: boolean;
  updateCheck: UpdateCheckState;
  onTheme: (theme: "light" | "dark") => void;
  onChooseFolder: () => void;
  onWorkLabelMode: (mode: WorkLabelMode) => void;
  onLaunchAtStartup: (enabled: boolean) => void;
  onCloseToTray: (enabled: boolean) => void;
  onCheckForUpdate: () => void;
  onOpenGithub: () => void;
  onNotice: (message: string) => void;
}) {
  const { t, language, setLanguage } = useI18n();
  return (
    <div className="page page--narrow">
      <PageHeading eyebrow="PREFERENCES" title={t("nav.settings")} subtitle={t("settings.subtitle")} />
      <section className="settings-group"><h3>{t("sidebar.library")}</h3>
        <SettingRow icon={FolderOpen} title={needsMusicFolder ? t("top.import") : t("top.update")} body={folderPath || t("status.noFolder")}>
          <button className="settings-action" onClick={onChooseFolder} disabled={isScanning} title={folderPath || undefined}>
            {isScanning ? <RefreshCw className="spin" size={16} /> : <FolderOpen size={16} />}
            {isScanning ? t("top.reading") : needsMusicFolder ? t("top.import") : t("top.update")}
          </button>
        </SettingRow>
      </section>
      <section className="settings-group"><h3>{t("settings.system")}</h3>
        <SettingRow icon={RefreshCw} title={t("settings.launchAtStartup")} body={t("settings.launchAtStartupBody")}><Switch checked={launchAtStartup} onChange={onLaunchAtStartup} /></SettingRow>
        <SettingRow icon={Minus} title={t("settings.closeToTray")} body={t("settings.closeToTrayBody")}><Switch checked={closeToTray} onChange={onCloseToTray} /></SettingRow>
      </section>
      <section className="settings-group"><h3>{t("settings.aboutUpdates")}</h3>
        <SettingRow
          icon={Github}
          title={`ARMusic v${appVersion}`}
          body={updateCheck.status === "available"
            ? t("settings.updateAvailableBody", { version: updateCheck.latestVersion })
            : updateCheck.status === "failed"
              ? t("settings.updateFailed")
              : t("settings.autoUpdateBody")}
        >
          <div className="settings-update-actions">
            <button className="settings-action" onClick={onCheckForUpdate} disabled={updateCheck.status === "checking"}>
              {updateCheck.status === "checking" ? <RefreshCw className="spin" size={16} /> : <RefreshCw size={16} />}
              {t(updateCheck.status === "checking" ? "settings.checkingUpdate" : "settings.checkUpdate")}
            </button>
            <button className="settings-action" onClick={onOpenGithub}><Github size={16} />{t("settings.githubProject")}</button>
          </div>
        </SettingRow>
      </section>
      <section className="settings-group"><h3>{t("settings.appearance")}</h3>
        <SettingRow icon={theme === "light" ? Sun : Moon} title={t("settings.theme")} body={t("settings.themeBody")}><div className="segmented"><button className={theme === "light" ? "is-active" : ""} onClick={() => onTheme("light")}>{t("settings.light")}</button><button className={theme === "dark" ? "is-active" : ""} onClick={() => onTheme("dark")}>{t("settings.dark")}</button></div></SettingRow>
        <SettingRow icon={Languages} title={t("settings.language")} body={t("settings.languageBody")}>
          <div className="language-options" role="radiogroup" aria-label={t("settings.language")}>
            {(Object.entries(languageNames) as Array<[Language, string]>).map(([code, label]) => (
              <button key={code} role="radio" aria-checked={language === code} className={language === code ? "is-active" : ""} onClick={() => setLanguage(code)}>
                <span>{label}</span>
              </button>
            ))}
          </div>
        </SettingRow>
        <SettingRow icon={Disc3} title={t("settings.workLabelMode")} body={t("settings.workLabelModeBody")}>
          <div className="work-label-options segmented" role="radiogroup" aria-label={t("settings.workLabelMode")}>
            {(["album", "series", "work"] as WorkLabelMode[]).map((mode) => <button key={mode} role="radio" aria-checked={workLabelMode === mode} className={workLabelMode === mode ? "is-active" : ""} onClick={() => onWorkLabelMode(mode)}>{t(`settings.workLabel.${mode}` as MessageKey)}</button>)}
          </div>
        </SettingRow>
        <SettingRow icon={Album} title={t("settings.coverColor")} body={t("settings.coverColorBody")}><Switch defaultChecked onChange={() => onNotice(t("settings.updatedCover"))} /></SettingRow>
      </section>
      <section className="settings-group"><h3>{t("settings.playback")}</h3>
        <SettingRow icon={Volume2} title={t("settings.fade")} body={t("settings.fadeBody")}><Switch defaultChecked onChange={() => onNotice(t("settings.updatedPlayback"))} /></SettingRow>
      </section>
      <section className="settings-group"><h3>{t("settings.shortcuts")}</h3><ShortcutRow label={t("settings.playPause")} keys="Space" /><ShortcutRow label={t("settings.searchLibrary")} keys="Ctrl  F" /><ShortcutRow label={t("settings.seek")} keys="Shift  ← / →" /><ShortcutRow label={t("settings.prevNext")} keys="Ctrl  ← / →" /></section>
    </div>
  );
}

function SettingRow({ icon: Icon, title, body, children }: { icon: LucideIcon; title: string; body: string; children: React.ReactNode }) {
  return <div className="setting-row"><span><Icon size={18} /></span><div><strong>{title}</strong><small>{body}</small></div>{children}</div>;
}

function Switch({ defaultChecked = false, checked, onChange }: { defaultChecked?: boolean; checked?: boolean; onChange?: (checked: boolean) => void }) {
  const [internalChecked, setInternalChecked] = useState(defaultChecked);
  const value = checked ?? internalChecked;
  return <button className={`switch ${value ? "is-on" : ""}`} role="switch" aria-checked={value} onClick={() => {
    const next = !value;
    if (checked === undefined) setInternalChecked(next);
    onChange?.(next);
  }}><i /></button>;
}

function ShortcutRow({ label, keys }: { label: string; keys: string }) {
  return <div className="shortcut-row"><span>{label}</span><kbd>{keys}</kbd></div>;
}

function PageHeading({ eyebrow, title, subtitle, children }: { eyebrow: string; title: string; subtitle: string; children?: React.ReactNode }) {
  return <div className="page-heading"><div><span>{eyebrow}</span><h2>{title}</h2><p>{subtitle}</p></div>{children ? <div className="page-heading__actions">{children}</div> : null}</div>;
}

function EmptyState({ icon: Icon, title, body, action, onAction }: { icon: LucideIcon; title: string; body: string; action?: string; onAction?: () => void }) {
  return <div className="empty-state"><span><Icon size={28} /></span><h3>{title}</h3><p>{body}</p>{action ? <button className="button button--primary" onClick={onAction}>{action}</button> : null}</div>;
}

function formatSleepTimerClock(totalSeconds: number): string {
  const seconds = Math.max(0, Math.floor(totalSeconds));
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor(seconds / 60) % 60;
  const remainder = seconds % 60;
  return hours > 0
    ? `${String(hours).padStart(2, "0")}:${String(minutes).padStart(2, "0")}:${String(remainder).padStart(2, "0")}`
    : `${String(minutes).padStart(2, "0")}:${String(remainder).padStart(2, "0")}`;
}

function SleepTimerControl({ mode, deadline, defaultSeconds, pauseWhenCompletion, onStart, onCancel, onDefaultSeconds, onPauseWhenCompletion }: {
  mode: SleepTimerMode;
  deadline: number | null;
  defaultSeconds: number;
  pauseWhenCompletion: boolean;
  onStart: (seconds: number, pauseWhenCompletion: boolean) => void;
  onCancel: () => void;
  onDefaultSeconds: (seconds: number) => void;
  onPauseWhenCompletion: (enabled: boolean) => void;
}) {
  const { t } = useI18n();
  const [clock, setClock] = useState(() => Date.now());
  const running = mode !== "off";
  const remainingSeconds = deadline ? Math.max(0, Math.ceil((deadline - clock) / 1_000)) : 0;
  const hours = Math.floor(defaultSeconds / 3600);
  const minutes = Math.floor(defaultSeconds / 60) % 60;
  const status = mode === "trackEnd"
    ? t("settings.timerTrackEndPending")
    : formatSleepTimerClock(remainingSeconds);
  const updateCustomTime = (nextHours: number, nextMinutes: number) => {
    const normalizedHours = Math.max(0, Math.min(23, Math.floor(nextHours || 0)));
    const normalizedMinutes = Math.max(0, Math.min(59, Math.floor(nextMinutes || 0)));
    onDefaultSeconds(Math.max(60, normalizedHours * 3600 + normalizedMinutes * 60));
  };

  useEffect(() => {
    setClock(Date.now());
    if (mode !== "running" || !deadline) return;
    const timer = window.setInterval(() => setClock(Date.now()), 1_000);
    return () => window.clearInterval(timer);
  }, [deadline, mode]);

  return (
    <details className={`lyric-panel-settings sleep-timer-control ${running ? "is-running" : ""}`}>
      <summary aria-label={t("settings.sleepTimer")} title={running ? `${t("settings.sleepTimer")} · ${status}` : t("settings.sleepTimer")}>
        <Clock3 size={17} />
        {running ? <i /> : null}
      </summary>
      <section className="lyric-panel-settings__popover sleep-timer-popover">
        <header><strong>{t("settings.sleepTimer")}</strong><small>{running ? status : t("settings.sleepTimerBody")}</small></header>
        <div className="sleep-timer-presets" aria-label={t("settings.timerPresets")}>
          {[5, 10, 15, 30, 45, 60, 180].map((presetMinutes) => (
            <button key={presetMinutes} className={defaultSeconds === presetMinutes * 60 ? "is-active" : ""} onClick={() => onDefaultSeconds(presetMinutes * 60)}>{presetMinutes < 60 ? `${presetMinutes}m` : `${presetMinutes / 60}h`}</button>
          ))}
        </div>
        <div className="sleep-timer-custom">
          <span>{t("settings.timerCustom")}</span>
          <label><input type="number" min="0" max="23" value={hours} onChange={(event) => updateCustomTime(Number(event.currentTarget.value), minutes)} /><small>{t("settings.timerHours")}</small></label>
          <label><input type="number" min="0" max="59" value={minutes} onChange={(event) => updateCustomTime(hours, Number(event.currentTarget.value))} /><small>{t("settings.timerMinutes")}</small></label>
        </div>
        <div className="sleep-timer-finish"><span><strong>{t("settings.timerFinishTrack")}</strong><small>{t("settings.timerFinishTrackBody")}</small></span><Switch checked={pauseWhenCompletion} onChange={onPauseWhenCompletion} /></div>
        <button className={`sleep-timer-action ${running ? "is-cancel" : ""}`} onClick={() => running ? onCancel() : onStart(defaultSeconds, pauseWhenCompletion)}>{running ? t("settings.timerCancel") : t("settings.timerStart")}</button>
      </section>
    </details>
  );
}

interface InspectorProps {
  track: Track;
  tracks: Track[];
  tab: InspectorTab;
  position: number;
  duration: number;
  lyricFont: LyricFont;
  lyricAlignment: LyricAlignment;
  lyricOffsetMs: number;
  lyricScale: number;
  lyricGap: number;
  lyricFocusPosition: LyricFocusPosition;
  sleepTimerMode: SleepTimerMode;
  sleepTimerDeadline: number | null;
  sleepTimerDefaultSeconds: number;
  sleepTimerPauseWhenCompletion: boolean;
  onTab: (tab: InspectorTab) => void;
  onClose: () => void;
  onPlay: (track: Track) => void;
  onClearQueue: () => void;
  onEdit: (track: Track) => void;
  onLyricFont: (font: LyricFont) => void;
  onLyricAlignment: (alignment: LyricAlignment) => void;
  onLyricOffset: (offsetMs: number) => void;
  onLyricScale: (scale: number) => void;
  onLyricGap: (gap: number) => void;
  onLyricFocusPosition: (position: LyricFocusPosition) => void;
  onSleepTimerStart: (seconds: number, pauseWhenCompletion: boolean) => void;
  onSleepTimerCancel: () => void;
  onSleepTimerDefaultSeconds: (seconds: number) => void;
  onSleepTimerPauseWhenCompletion: (enabled: boolean) => void;
  onSeek: (value: number) => void;
}

function NowPlayingInspector({ track, tracks, tab, position, duration, lyricFont, lyricAlignment, lyricOffsetMs, lyricScale, lyricGap, lyricFocusPosition, sleepTimerMode, sleepTimerDeadline, sleepTimerDefaultSeconds, sleepTimerPauseWhenCompletion, onTab, onClose, onPlay, onClearQueue, onEdit, onLyricFont, onLyricAlignment, onLyricOffset, onLyricScale, onLyricGap, onLyricFocusPosition, onSleepTimerStart, onSleepTimerCancel, onSleepTimerDefaultSeconds, onSleepTimerPauseWhenCompletion, onSeek }: InspectorProps) {
  const { t } = useI18n();
  const currentIndex = tracks.findIndex((item) => item.syncId === track.syncId);
  const queue = (currentIndex >= 0
    ? [...tracks.slice(currentIndex + 1), ...tracks.slice(0, currentIndex)]
    : tracks
  ).slice(0, 8);
  const lyrics = useMemo(() => parseLyrics(track.lyrics), [track.lyrics]);
  const lyricsRef = useRef<HTMLDivElement>(null);
  const manualTimerRef = useRef<number | undefined>(undefined);
  const pointerLeftWhileManualRef = useRef(false);
  const [manualLyricScrolling, setManualLyricScrolling] = useState(false);
  let activeLyric = lyrics.length > 0 && lyrics[0].timeSeconds !== null ? 0 : -1;
  lyrics.forEach((line, index) => {
    if (line.timeSeconds !== null && line.timeSeconds <= position + lyricOffsetMs / 1000 + 0.08) {
      activeLyric = index;
    }
  });
  const scrollToActiveLyric = useCallback((behavior: ScrollBehavior = "smooth") => {
    const container = lyricsRef.current;
    const active = container?.querySelector<HTMLElement>("[data-active='true']");
    if (!container || !active) return;
    const containerBox = container.getBoundingClientRect();
    const activeBox = active.getBoundingClientRect();
    const activeCenterInScrollArea = container.scrollTop + activeBox.top - containerBox.top + activeBox.height / 2;
    const target = activeCenterInScrollArea
      - container.clientHeight * lyricFocusRatios[lyricFocusPosition];
    container.scrollTo({ top: Math.max(0, target), behavior });
  }, [lyricFocusPosition]);
  const returnToCurrentLyric = useCallback(() => {
    window.clearTimeout(manualTimerRef.current);
    pointerLeftWhileManualRef.current = false;
    setManualLyricScrolling(false);
    window.requestAnimationFrame(() => scrollToActiveLyric());
  }, [scrollToActiveLyric]);
  const enterManualLyricMode = useCallback(() => {
    setManualLyricScrolling(true);
    window.clearTimeout(manualTimerRef.current);
    manualTimerRef.current = window.setTimeout(returnToCurrentLyric, 5_000);
  }, [returnToCurrentLyric]);
  useEffect(() => {
    if (activeLyric >= 0 && !manualLyricScrolling) scrollToActiveLyric();
  }, [activeLyric, lyricGap, lyricScale, manualLyricScrolling, scrollToActiveLyric, track.syncId]);
  useEffect(() => {
    window.clearTimeout(manualTimerRef.current);
    pointerLeftWhileManualRef.current = false;
    setManualLyricScrolling(false);
    return () => window.clearTimeout(manualTimerRef.current);
  }, [track.syncId]);
  useEffect(() => {
    if (!manualLyricScrolling) return;
    const handleOutsidePointer = (event: PointerEvent) => {
      if (!pointerLeftWhileManualRef.current || lyricsRef.current?.contains(event.target as Node)) return;
      returnToCurrentLyric();
    };
    document.addEventListener("pointerdown", handleOutsidePointer, true);
    return () => document.removeEventListener("pointerdown", handleOutsidePointer, true);
  }, [manualLyricScrolling, returnToCurrentLyric]);
  return (
    <aside className="inspector">
      <div className="inspector__wash"><CoverArt track={track} decorative /></div>
      <header><span>{t("now.playing")}</span><div>
        {tab === "lyrics" ? <>
          <SleepTimerControl
            mode={sleepTimerMode}
            deadline={sleepTimerDeadline}
            defaultSeconds={sleepTimerDefaultSeconds}
            pauseWhenCompletion={sleepTimerPauseWhenCompletion}
            onStart={onSleepTimerStart}
            onCancel={onSleepTimerCancel}
            onDefaultSeconds={onSleepTimerDefaultSeconds}
            onPauseWhenCompletion={onSleepTimerPauseWhenCompletion}
          />
          <details className="lyric-panel-settings">
          <summary aria-label={t("settings.lyricFont")} title={t("settings.lyricFont")}><Settings2 size={17} /></summary>
          <section className="lyric-panel-settings__popover">
            <div className="lyric-panel-settings__row"><span>{t("settings.lyricFont")}</span><div className="lyric-panel-settings__options">
              {(["system", "yezi", "pingfang"] as LyricFont[]).map((font) => <button key={font} className={lyricFont === font ? "is-active" : ""} onClick={() => onLyricFont(font)} style={{ fontFamily: lyricFontFamilies[font] }}>{t(`settings.lyricFont.${font}` as MessageKey)}</button>)}
            </div></div>
            <div className="lyric-panel-settings__row"><span>{t("settings.lyricSize")}<em>{lyricScale}%</em></span><input type="range" aria-label={t("settings.lyricSize")} min="85" max="110" step="5" value={lyricScale} onChange={(event) => onLyricScale(Number(event.currentTarget.value))} style={{ "--range-progress": `${(lyricScale - 85) / 25 * 100}%` } as CSSProperties} /></div>
            <div className="lyric-panel-settings__row"><span>{t("settings.lyricGap")}<em>{lyricGap}px</em></span><input type="range" aria-label={t("settings.lyricGap")} min="7" max="13" step="1" value={lyricGap} onChange={(event) => onLyricGap(Number(event.currentTarget.value))} style={{ "--range-progress": `${(lyricGap - 7) / 6 * 100}%` } as CSSProperties} /></div>
            <div className="lyric-panel-settings__row"><span>{t("settings.lyricFocusPosition")}</span><div className="lyric-panel-settings__options">
              {(["upper", "center", "lower"] as LyricFocusPosition[]).map((focus) => <button key={focus} className={lyricFocusPosition === focus ? "is-active" : ""} onClick={() => onLyricFocusPosition(focus)}>{t(`settings.focus.${focus}` as MessageKey)}</button>)}
            </div></div>
            <div className="lyric-panel-settings__row"><span>{t("settings.lyricAlign")}</span><div className="lyric-panel-settings__options">
              {(["left", "center", "right"] as LyricAlignment[]).map((alignment) => <button key={alignment} className={lyricAlignment === alignment ? "is-active" : ""} onClick={() => onLyricAlignment(alignment)}>{t(`settings.align.${alignment}` as MessageKey)}</button>)}
            </div></div>
            <div className="lyric-panel-settings__row"><span>{t("settings.lyricOffset")}</span><div className="lyric-panel-settings__offset">
              <button onClick={() => onLyricOffset(lyricOffsetMs - 500)} disabled={lyricOffsetMs <= -5_000} aria-label={t("settings.offsetEarlier")}>−</button>
              <strong>{lyricOffsetMs > 0 ? "+" : ""}{(lyricOffsetMs / 1000).toFixed(1)} s</strong>
              <button onClick={() => onLyricOffset(lyricOffsetMs + 500)} disabled={lyricOffsetMs >= 5_000} aria-label={t("settings.offsetLater")}>＋</button>
            </div></div>
          </section>
          </details>
        </> : null}
        <button onClick={onClose} aria-label={t("search.showPosterWall")} title={t("search.showPosterWall")}><PanelRightOpen size={17} /></button>
      </div></header>
      <div className="inspector__cover" onContextMenu={(event) => editTrackFromContextMenu(event, track, onEdit)}><CoverArt track={track} /></div>
      <div className="inspector__title" onContextMenu={(event) => editTrackFromContextMenu(event, track, onEdit)}><div><h2>{track.title}</h2><p>{track.artist} · {track.album}</p></div><button onClick={() => onEdit(track)} aria-label={t("songs.editTags")} title={t("songs.editTags")}><PencilLine size={16} /></button></div>
      <div className="inspector__tabs"><button className={tab === "lyrics" ? "is-active" : ""} onClick={() => onTab("lyrics")}><Mic2 size={15} />{t("now.lyrics")}</button><button className={tab === "queue" ? "is-active" : ""} onClick={() => onTab("queue")}><ListMusic size={15} />{t("now.queue")} <span>{tracks.length}</span></button></div>
      {tab === "lyrics" ? <div className={`lyrics ${manualLyricScrolling ? "is-manual" : ""}`} ref={lyricsRef} onWheel={enterManualLyricMode} onMouseEnter={() => { pointerLeftWhileManualRef.current = false; }} onMouseLeave={() => { if (manualLyricScrolling) pointerLeftWhileManualRef.current = true; }}>{lyrics.length ? lyrics.map((line, index) => <button key={`${line.timeSeconds ?? "plain"}-${index}-${line.text}`} className={index === activeLyric ? "is-active" : ""} data-active={index === activeLyric} disabled={line.timeSeconds === null} onClick={() => line.timeSeconds !== null && onSeek(Math.max(0, line.timeSeconds - lyricOffsetMs / 1000))}><span className="lyrics__main">{line.text}</span>{line.translation ? <small className="lyrics__translation">{line.translation}</small> : null}</button>) : <p className="lyrics__empty">{t("now.noLyrics")}</p>}</div> : <div className="queue"><div className="queue__head"><span>{t("now.next")}</span><button onClick={onClearQueue} disabled={tracks.length === 0}>{t("now.clear")}</button></div>{queue.map((item, index) => <button key={item.syncId} onClick={() => onPlay(item)} onContextMenu={(event) => editTrackFromContextMenu(event, item, onEdit)}><span>{index + 1}</span><CoverArt track={item} decorative /><strong>{item.title}<small>{item.artist}</small></strong><MoreHorizontal size={16} /></button>)}</div>}
    </aside>
  );
}

interface PlayerBarProps {
  track: Track;
  isPlaying: boolean;
  position: number;
  duration: number;
  volume: number;
  isMuted: boolean;
  playMode: "repeat" | "shuffle";
  onTogglePlay: () => void;
  onPrevious: () => void;
  onNext: () => void;
  onSeek: (value: number) => void;
  onVolume: (value: number) => void;
  onMute: () => void;
  onPlayMode: () => void;
  onInspector: () => void;
  onEdit: (track: Track) => void;
}

function PlayerBar({ track, isPlaying, position, duration, volume, isMuted, playMode, onTogglePlay, onPrevious, onNext, onSeek, onVolume, onMute, onPlayMode, onInspector, onEdit }: PlayerBarProps) {
  const { t } = useI18n();
  const progress = duration ? Math.min(100, (position / duration) * 100) : 0;
  return (
    <div className="titlebar-player" data-tauri-drag-region>
      <button className="player-track" onClick={onInspector} onContextMenu={(event) => editTrackFromContextMenu(event, track, onEdit)}><CoverArt track={track} decorative /><span><strong>{track.title}</strong><small>{track.artist} · {track.album}</small></span></button>
      <div className="player-controls" data-tauri-drag-region><button onClick={onPlayMode} className={playMode === "shuffle" ? "is-active" : ""} aria-label={playMode === "shuffle" ? t("player.shuffle") : t("player.sequence")}>{playMode === "shuffle" ? <Shuffle size={17} /> : <Repeat2 size={17} />}</button><button onClick={onPrevious} aria-label={t("player.previous")}><SkipBack size={19} fill="currentColor" /></button><button className="player-controls__play" onClick={onTogglePlay} aria-label={isPlaying ? t("player.pause") : t("player.play")}>{isPlaying ? <Pause size={20} fill="currentColor" /> : <Play size={20} fill="currentColor" />}</button><button onClick={onNext} aria-label={t("player.next")}><SkipForward size={19} fill="currentColor" /></button></div>
      <div className="player-progress" data-tauri-drag-region><time data-tauri-drag-region>{formatDuration(position)}</time><label style={{ "--range-progress": `${progress}%` } as CSSProperties}><input type="range" min="0" max={Math.max(duration, 1)} step="0.1" value={Math.min(position, Math.max(duration, 1))} onChange={(event) => onSeek(Number(event.target.value))} aria-label={t("player.progress")} /></label><time data-tauri-drag-region>{formatDuration(duration)}</time></div>
      <div className="volume-control"><button onClick={onMute} aria-label={isMuted ? t("player.unmute") : t("player.mute")}>{isMuted || volume === 0 ? <VolumeX size={17} /> : <Volume2 size={17} />}</button><label style={{ "--range-progress": `${(isMuted ? 0 : volume) * 100}%` } as CSSProperties}><input type="range" min="0" max="1" step="0.01" value={isMuted ? 0 : volume} onChange={(event) => onVolume(Number(event.target.value))} aria-label={t("player.volume")} /></label></div>
    </div>
  );
}

function groupTracks(tracks: Track[], key: (track: Track) => string, fallback: string): Array<[string, Track[]]> {
  const groups = new Map<string, Track[]>();
  tracks.forEach((track) => {
    const name = key(track) || fallback;
    groups.set(name, [...(groups.get(name) || []), track]);
  });
  return Array.from(groups.entries());
}

function filterGroups(groups: Array<[string, Track[]]>, searchText: string) {
  const query = searchText.trim().toLocaleLowerCase();
  if (!query) return groups;
  return groups.filter(([name, tracks]) => name.toLocaleLowerCase().includes(query) || tracks.some((track) => track.title.toLocaleLowerCase().includes(query)));
}

function formatLastPlayed(value: string | undefined, language: Language, fallback: string): string {
  if (!value) return fallback;
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat(language, {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).format(date);
}

function coverBackground(track: Track): CSSProperties {
  const [a, b, c] = paletteFor(track);
  return { "--feature-a": a, "--feature-b": b, "--feature-c": c } as CSSProperties;
}

function seekTo(value: number, audioRef: RefObject<HTMLAudioElement>, duration: number, setPosition: (value: number) => void) {
  const next = Math.min(Math.max(value, 0), Math.max(duration, 0));
  if (audioRef.current && Number.isFinite(audioRef.current.duration)) audioRef.current.currentTime = next;
  setPosition(next);
}

export default App;
