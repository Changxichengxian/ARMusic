import type { SyncManifest, SyncPlan, Track } from "../types";

export function createManifest(deviceName: string, tracks: Track[]): SyncManifest {
  return {
    libraryId: `desktop-${deviceName.toLowerCase().replace(/\s+/g, "-")}`,
    deviceName,
    generatedAt: new Date().toISOString(),
    tracks,
  };
}

export function buildSyncPlan(local: SyncManifest, remote: SyncManifest): SyncPlan {
  const localById = new Map(local.tracks.map((track) => [track.syncId, track]));
  const remoteById = new Map(remote.tracks.map((track) => [track.syncId, track]));

  const download = remote.tracks.filter((track) => !localById.has(track.syncId));
  const upload = local.tracks.filter((track) => !remoteById.has(track.syncId));
  const conflicts = local.tracks.filter((track) => {
    const remoteTrack = remoteById.get(track.syncId);
    if (!remoteTrack) return false;

    return (
      remoteTrack.relativePath !== track.relativePath ||
      remoteTrack.title !== track.title ||
      remoteTrack.artist !== track.artist ||
      remoteTrack.album !== track.album
    );
  });

  return { download, upload, conflicts };
}

export function formatBytes(value: number): string {
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  return `${(value / 1024 / 1024).toFixed(1)} MB`;
}

export function formatDuration(seconds: number): string {
  const minutes = Math.floor(seconds / 60);
  const rest = seconds % 60;
  return `${minutes}:${rest.toString().padStart(2, "0")}`;
}
