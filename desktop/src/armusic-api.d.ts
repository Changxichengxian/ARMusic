import type { ARMusicBridge } from "./types";

declare global {
  interface Window {
    armusic?: ARMusicBridge;
  }
}

export {};
