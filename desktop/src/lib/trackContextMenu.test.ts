import { describe, expect, it } from "vitest";
import { isNestedTrackControl } from "./trackContextMenu";

describe("track context menu", () => {
  it("allows the song surface itself", () => {
    const surface = {} as Element;
    expect(isNestedTrackControl(surface, surface)).toBe(false);
    expect(isNestedTrackControl(surface, null)).toBe(false);
  });

  it("ignores a nested playback or action control", () => {
    const surface = {} as Element;
    const action = {} as Element;
    expect(isNestedTrackControl(surface, action)).toBe(true);
  });
});
