import { describe, expect, it } from "vitest";
import type { Track } from "../types";
import { adjacentTrack, queueTracks, workPlaybackQueue } from "./workQueue";

function track(syncId: string, work: string): Track {
  return {
    syncId,
    title: syncId,
    artist: "artist",
    album: "album",
    work,
    durationSeconds: 180,
    sizeBytes: 1,
    relativePath: `${syncId}.mp3`,
    playSeconds: 0,
    source: "desktop",
  };
}

describe("work playback queue", () => {
  const unrelated = track("outside", "另一作品");
  const first = track("one", "作品 A");
  const second = track("two", "作品 A");
  const third = track("three", "作品 A");
  const library = [unrelated, first, second, third];
  const queue = workPlaybackQueue("作品 A", [first, second, third]);

  it("keeps every work track visible in its original work order", () => {
    expect(queueTracks(library, queue).map((item) => item.syncId)).toEqual([
      "one",
      "two",
      "three",
    ]);
  });

  it("advances inside the work before returning to unrelated library songs", () => {
    expect(adjacentTrack(library, "one", 1, queue)?.syncId).toBe("two");
    expect(adjacentTrack(library, "two", 1, queue)?.syncId).toBe("three");
    expect(adjacentTrack(library, "three", 1, queue)?.syncId).toBe("one");
  });

  it("uses the same work queue for previous and deterministic shuffle", () => {
    expect(adjacentTrack(library, "one", -1, queue)?.syncId).toBe("three");
    expect(adjacentTrack(library, "one", 1, queue, true, () => 0)?.syncId).toBe("two");
  });

  it("keeps an explicitly cleared queue empty", () => {
    const cleared = { id: "cleared", trackIds: [] };
    expect(queueTracks(library, cleared)).toEqual([]);
    expect(adjacentTrack(library, "one", 1, cleared)).toBeUndefined();
  });
});
