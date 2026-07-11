import type { Track } from "../types";

export interface PlaybackQueue {
  id: string;
  trackIds: string[];
}

export function workPlaybackQueue(workName: string, tracks: Track[]): PlaybackQueue {
  return {
    id: `work:${workName}`,
    trackIds: [...new Set(tracks.map((track) => track.syncId))],
  };
}

export function queueTracks(library: Track[], queue?: PlaybackQueue | null): Track[] {
  if (!queue) return library;
  if (!queue.trackIds.length) return [];
  const byId = new Map(library.map((track) => [track.syncId, track]));
  const resolved = queue.trackIds.flatMap((id) => {
    const track = byId.get(id);
    return track ? [track] : [];
  });
  return resolved.length ? resolved : library;
}

export function adjacentTrack(
  library: Track[],
  currentTrackId: string,
  direction: 1 | -1,
  queue?: PlaybackQueue | null,
  shuffle = false,
  random: () => number = Math.random,
): Track | undefined {
  const candidates = queueTracks(library, queue);
  if (!candidates.length) return undefined;
  const currentIndex = candidates.findIndex((track) => track.syncId === currentTrackId);
  if (shuffle && candidates.length > 1) {
    const current = Math.max(0, currentIndex);
    const offset = 1 + Math.floor(random() * (candidates.length - 1));
    return candidates[(current + offset) % candidates.length];
  }
  const start = currentIndex >= 0 ? currentIndex : direction > 0 ? -1 : 0;
  return candidates[(start + direction + candidates.length) % candidates.length];
}
