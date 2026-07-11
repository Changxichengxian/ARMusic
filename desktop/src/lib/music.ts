import type { Track } from "../types";

export const coverPalettes = [
  ["#6f1024", "#e4a6af", "#f6eee9"],
  ["#102a43", "#78b7d0", "#e9f1ef"],
  ["#4b2b20", "#d49d62", "#f4e7cf"],
  ["#24211f", "#a8a5a1", "#f0ede7"],
  ["#1e3a34", "#78a892", "#e8efe8"],
  ["#34245c", "#ad91d2", "#efe9f3"],
  ["#6f3011", "#f1a054", "#f5e6c8"],
  ["#202a57", "#8ea3df", "#ececf4"],
] as const;

export function stableNumber(value: string): number {
  let result = 0;
  for (let index = 0; index < value.length; index += 1) {
    result = (result * 31 + value.charCodeAt(index)) | 0;
  }
  return Math.abs(result);
}

export function paletteFor(track: Pick<Track, "syncId" | "title">) {
  return coverPalettes[stableNumber(`${track.syncId}:${track.title}`) % coverPalettes.length];
}

export function coverMark(title: string): string {
  const clean = title.replace(/[\s·・,.!?，。！？()（）\-]/g, "");
  return clean.slice(0, 2).toUpperCase() || "AR";
}

export function formatDuration(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds <= 0) return "--:--";
  const minutes = Math.floor(seconds / 60);
  const rest = Math.floor(seconds % 60);
  return `${minutes}:${rest.toString().padStart(2, "0")}`;
}

export function formatBytes(value: number): string {
  if (!value) return "—";
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(0)} KB`;
  return `${(value / 1024 / 1024).toFixed(1)} MB`;
}

export function formatListenTime(seconds: number, locale = "zh-CN"): string {
  const minutes = Number.isFinite(seconds) && seconds > 0 ? Math.max(1, Math.round(seconds / 60)) : 0;
  const useHours = seconds >= 3600;
  const value = useHours ? Number((seconds / 3600).toFixed(seconds < 36000 ? 1 : 0)) : minutes;
  const formatted = new Intl.NumberFormat(locale, { maximumFractionDigits: 1 }).format(value);
  if (locale === "en") return `${formatted} ${useHours ? (value === 1 ? "hour" : "hours") : (value === 1 ? "minute" : "minutes")}`;
  if (locale === "ja") return `${formatted} ${useHours ? "時間" : "分"}`;
  return `${formatted} ${useHours ? "小时" : "分钟"}`;
}

export function initials(name: string): string {
  return name.trim().slice(0, 1).toUpperCase() || "?";
}

export function normalizeScannedTracks(tracks: Track[], labels: { unknownArtist?: string; localMusic?: string } = {}): Track[] {
  return tracks.map((track) => ({
    ...track,
    artist: track.artist?.trim() || labels.unknownArtist || "未知歌手",
    album: track.album?.trim() || labels.localMusic || "本地音乐",
    work: track.work?.trim() || undefined,
    genre: track.genre?.trim() || undefined,
  }));
}
