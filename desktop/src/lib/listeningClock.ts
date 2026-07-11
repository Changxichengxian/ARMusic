export interface MediaClockSample {
  previousMediaTimeSeconds: number | null;
  mediaTimeSeconds: number;
  wallElapsedMs: number;
  isPlaying: boolean;
  isSeeking: boolean;
}

/**
 * Counts actual media progress while bounding unannounced jumps by elapsed wall time.
 * This remains accurate when hidden-window timers are throttled and contributes zero
 * when a sleeping machine's media clock did not advance.
 */
export function mediaListeningDeltaMs(sample: MediaClockSample): number {
  if (!sample.isPlaying || sample.isSeeking || sample.previousMediaTimeSeconds === null) return 0;
  if (!Number.isFinite(sample.mediaTimeSeconds) || !Number.isFinite(sample.previousMediaTimeSeconds)) return 0;
  const mediaElapsedMs = (sample.mediaTimeSeconds - sample.previousMediaTimeSeconds) * 1_000;
  if (mediaElapsedMs <= 0) return 0;
  return Math.min(mediaElapsedMs, Math.max(0, sample.wallElapsedMs) + 1_500);
}
