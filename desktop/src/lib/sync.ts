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
  const ids = (track: Track) => [track.syncId, ...(track.legacySyncIds ?? [])];
  const index = (tracks: Track[]) => {
    const result = new Map<string, Track>();
    tracks.forEach((track) => ids(track).forEach((id) => {
      if (id && !result.has(id)) result.set(id, track);
    }));
    return result;
  };
  const matching = (track: Track, candidates: Map<string, Track>) =>
    ids(track).map((id) => candidates.get(id)).find(Boolean);
  const basename = (path: string) => path.split(/[\\/]/).filter(Boolean).at(-1) ?? "";
  const localById = index(local.tracks);
  const remoteById = index(remote.tracks);
  return {
    download: remote.tracks.filter((track) => !matching(track, localById)),
    upload: local.tracks.filter((track) => !matching(track, remoteById)),
    conflicts: local.tracks.filter((track) => {
      const remoteTrack = matching(track, remoteById);
      return Boolean(
        remoteTrack &&
          ((remoteTrack.revisionHash && track.revisionHash && remoteTrack.revisionHash !== track.revisionHash) ||
            basename(remoteTrack.relativePath) !== basename(track.relativePath) ||
            remoteTrack.title !== track.title ||
            remoteTrack.artist !== track.artist ||
            remoteTrack.album !== track.album),
      );
    }),
  };
}
