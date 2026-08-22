import type { Track } from "../types";

export const DEFAULT_POSTER_ROWS = 8;

export function posterRowsForViewport(
  width: number,
  height: number,
  columns: number,
  gap = 10,
  horizontalPadding = 20,
): number {
  if (width <= 0 || height <= 0 || columns <= 0) return DEFAULT_POSTER_ROWS;
  const usableWidth = Math.max(1, width - horizontalPadding - gap * Math.max(0, columns - 1));
  const tileStep = usableWidth / columns + gap;
  return Math.max(5, Math.min(12, Math.ceil(height / tileStep) + 2));
}

export function posterLibrarySignature(tracks: readonly Track[]): string {
  return tracks.map((track) => [
    track.syncId,
    track.title,
    track.artist,
    track.album,
    track.work || "",
    track.coverUrl || "",
    track.year || "",
  ].join("\u001d")).join("\u001e");
}

export function shuffledPosterBatch<T>(
  source: readonly T[],
  identity: (item: T) => string,
  avoidFirstIdentity?: string,
  random: () => number = Math.random,
): T[] {
  const batch = [...source];
  for (let index = batch.length - 1; index > 0; index -= 1) {
    const rawIndex = Math.floor(random() * (index + 1));
    const swapIndex = Math.max(0, Math.min(index, rawIndex));
    [batch[index], batch[swapIndex]] = [batch[swapIndex], batch[index]];
  }

  if (batch.length > 1 && avoidFirstIdentity && identity(batch[0]) === avoidFirstIdentity) {
    batch.push(batch.shift()!);
  }
  return batch;
}
