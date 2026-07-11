import { describe, expect, it } from "vitest";
import { mediaListeningDeltaMs } from "./listeningClock";

describe("mediaListeningDeltaMs", () => {
  it("counts the full media progress when a hidden timer is throttled", () => {
    expect(mediaListeningDeltaMs({
      previousMediaTimeSeconds: 10,
      mediaTimeSeconds: 130,
      wallElapsedMs: 120_000,
      isPlaying: true,
      isSeeking: false,
    })).toBe(120_000);
  });

  it("does not count system sleep when the media clock did not move", () => {
    expect(mediaListeningDeltaMs({
      previousMediaTimeSeconds: 10,
      mediaTimeSeconds: 10,
      wallElapsedMs: 3_600_000,
      isPlaying: true,
      isSeeking: false,
    })).toBe(0);
  });

  it("rejects seeks and bounds a delayed seek event", () => {
    const sample = {
      previousMediaTimeSeconds: 10,
      mediaTimeSeconds: 110,
      wallElapsedMs: 1_000,
      isPlaying: true,
    };
    expect(mediaListeningDeltaMs({ ...sample, isSeeking: true })).toBe(0);
    expect(mediaListeningDeltaMs({ ...sample, isSeeking: false })).toBe(2_500);
  });

  it("does not count paused or backwards media", () => {
    expect(mediaListeningDeltaMs({
      previousMediaTimeSeconds: 10,
      mediaTimeSeconds: 9,
      wallElapsedMs: 1_000,
      isPlaying: true,
      isSeeking: false,
    })).toBe(0);
    expect(mediaListeningDeltaMs({
      previousMediaTimeSeconds: 10,
      mediaTimeSeconds: 11,
      wallElapsedMs: 1_000,
      isPlaying: false,
      isSeeking: false,
    })).toBe(0);
  });
});
