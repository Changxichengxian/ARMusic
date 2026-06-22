import type { ARMusicBridge, LibraryScanResult, Track } from "./types";

declare global {
  interface Window {
    __TAURI_INTERNALS__?: unknown;
  }
}

type BackendTrack = Track & {
  localPath?: string | null;
};

let tauriCorePromise: Promise<typeof import("@tauri-apps/api/core")> | null = null;

function loadTauriCore() {
  tauriCorePromise = tauriCorePromise ?? import("@tauri-apps/api/core");
  return tauriCorePromise;
}

async function normalizeTrack(track: BackendTrack): Promise<Track> {
  if (!track.localPath) {
    return track;
  }

  const { convertFileSrc } = await loadTauriCore();
  return {
    ...track,
    playUrl: track.playUrl ?? convertFileSrc(track.localPath),
    localPath: undefined,
  };
}

async function normalizeLibrary(result: LibraryScanResult): Promise<LibraryScanResult> {
  return {
    ...result,
    tracks: await Promise.all(result.tracks.map((track) => normalizeTrack(track as BackendTrack))),
  };
}

function createTauriBridge(): ARMusicBridge {
  async function invoke<T>(command: string): Promise<T> {
    const { invoke } = await loadTauriCore();
    return invoke<T>(command);
  }

  return {
    chooseMusicFolder: () => invoke<LibraryScanResult>("choose_music_folder").then(normalizeLibrary),
    getLibraryState: () => invoke<LibraryScanResult>("get_library_state").then(normalizeLibrary),
    startSyncServer: () => invoke("start_sync_server"),
    stopSyncServer: () => invoke("stop_sync_server"),
    getSyncStatus: () => invoke("get_sync_status"),
  };
}

export function createDesktopBridge(): ARMusicBridge | undefined {
  if (window.armusic) {
    return window.armusic;
  }

  if (window.__TAURI_INTERNALS__) {
    return createTauriBridge();
  }

  return undefined;
}
