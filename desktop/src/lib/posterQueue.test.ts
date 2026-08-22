import { describe, expect, it } from "vitest";
import type { Track } from "../types";
import { DEFAULT_POSTER_ROWS, posterLibrarySignature, posterRowsForViewport, shuffledPosterBatch } from "./posterQueue";

function track(overrides: Partial<Track> = {}): Track {
  return {
    syncId: "song-1",
    title: "Song",
    artist: "Artist",
    album: "Album",
    work: "Work",
    durationSeconds: 180,
    sizeBytes: 1,
    relativePath: "Song.mp3",
    playSeconds: 0,
    source: "desktop",
    coverUrl: "cover://one",
    ...overrides,
  };
}

describe("shuffledPosterBatch", () => {
  it("uses every poster exactly once in a batch", () => {
    const source = ["a", "b", "c", "d", "e"];
    const batch = shuffledPosterBatch(source, (item) => item, undefined, () => 0.37);

    expect(batch).toHaveLength(source.length);
    expect(new Set(batch)).toEqual(new Set(source));
    expect(source).toEqual(["a", "b", "c", "d", "e"]);
  });

  it("does not repeat the previous boundary poster when a new batch begins", () => {
    const batch = shuffledPosterBatch(["a", "b", "c"], (item) => item, "b", () => 0);

    expect(batch[0]).not.toBe("b");
    expect(new Set(batch)).toEqual(new Set(["a", "b", "c"]));
  });
});

describe("posterLibrarySignature", () => {
  it("ignores listening checkpoints but reacts to visual metadata changes", () => {
    const initial = posterLibrarySignature([track()]);
    const checkpoint = posterLibrarySignature([track({ playSeconds: 5, lastPlayedAt: "2026-07-11T06:00:00.000Z" })]);
    const retagged = posterLibrarySignature([track({ title: "Renamed Song" })]);

    expect(checkpoint).toBe(initial);
    expect(retagged).not.toBe(initial);
  });
});

describe("posterRowsForViewport", () => {
  it("keeps only the visible rows plus a small scrolling buffer", () => {
    expect(posterRowsForViewport(1200, 900, 7)).toBe(8);
    expect(posterRowsForViewport(1200, 900, 10)).toBe(10);
    expect(posterRowsForViewport(3700, 2160, 4)).toBe(5);
  });

  it("uses a safe default before the wall has a measurable size", () => {
    expect(posterRowsForViewport(0, 0, 7)).toBe(DEFAULT_POSTER_ROWS);
  });
});
