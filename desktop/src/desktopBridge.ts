import type {
  ARMusicBridge,
  AdbDevice,
  AdbSyncPreview,
  ExecuteAdbSyncRequest,
  ExecuteAdbSyncResult,
  HistorySyncPayload,
  LibraryScanResult,
  ListeningSession,
  PlaylistsPayload,
  PlaylistsSaveRequest,
  RecordListeningRequest,
  TagSaveResult,
  Track,
  TrackTagData,
  UpdateTrackTagsRequest,
  WishlistCategoryData,
  WishlistPayload,
  WishlistSaveRequest,
} from "./types";

declare global {
  interface Window {
    __TAURI_INTERNALS__?: unknown;
  }
}

type BackendTrack = Track & {
  localPath?: string | null;
  coverPath?: string | null;
};

let tauriCorePromise: Promise<typeof import("@tauri-apps/api/core")> | null = null;

function loadTauriCore() {
  tauriCorePromise ??= import("@tauri-apps/api/core");
  return tauriCorePromise;
}

async function normalizeTrack(track: BackendTrack): Promise<Track> {
  if (!track.localPath && !track.coverPath) return track;
  const { convertFileSrc } = await loadTauriCore();
  return {
    ...track,
    playUrl: track.playUrl ?? (track.localPath ? convertFileSrc(track.localPath) : undefined),
    coverUrl: track.coverUrl ?? (track.coverPath ? convertFileSrc(track.coverPath) : undefined),
  };
}

async function normalizeLibrary(result: LibraryScanResult): Promise<LibraryScanResult> {
  return {
    ...result,
    tracks: await Promise.all(result.tracks.map((track) => normalizeTrack(track as BackendTrack))),
  };
}

function createTauriBridge(): ARMusicBridge {
  async function invoke<T>(command: string, args?: Record<string, unknown>): Promise<T> {
    const { invoke: tauriInvoke } = await loadTauriCore();
    return tauriInvoke<T>(command, args);
  }

  return {
    chooseMusicFolder: () => invoke<LibraryScanResult>("choose_music_folder").then(normalizeLibrary),
    getLibraryState: () => invoke<LibraryScanResult>("get_library_state").then(normalizeLibrary),
    startSyncServer: () => invoke("start_sync_server"),
    stopSyncServer: () => invoke("stop_sync_server"),
    getSyncStatus: () => invoke("get_sync_status"),
    getTrackTags: (syncId) => invoke<TrackTagData>("get_track_tags", { syncId }),
    saveTrackTags: (request: UpdateTrackTagsRequest) =>
      invoke<TagSaveResult>("save_track_tags", { request }).then(async (result) => ({
        ...result,
        library: await normalizeLibrary(result.library),
      })),
    recordListeningSession: (request: RecordListeningRequest) =>
      invoke<ListeningSession>("record_listening_session", { request }),
    getListeningHistory: () => invoke<HistorySyncPayload>("get_listening_history"),
    getWishlist: () => invoke<WishlistPayload>("get_wishlist"),
    saveWishlist: (request: WishlistSaveRequest) =>
      invoke<WishlistPayload>("save_wishlist", { request }),
    migrateLegacyWishlist: (categories: WishlistCategoryData[]) =>
      invoke<WishlistPayload>("migrate_legacy_wishlist", { categories }),
    getPlaylists: () => invoke<PlaylistsPayload>("get_playlists"),
    savePlaylists: (request: PlaylistsSaveRequest) =>
      invoke<PlaylistsPayload>("save_playlists", { request }),
    listAdbDevices: () => invoke<AdbDevice[]>("list_adb_devices"),
    previewAdbSync: (serial?: string) => invoke<AdbSyncPreview>("preview_adb_sync", { serial }),
    executeAdbSync: (request: ExecuteAdbSyncRequest) =>
      invoke<ExecuteAdbSyncResult>("execute_adb_sync", { request }),
    openExternalUrl: (url: string) => invoke<void>("open_external_url", { url }),
  };
}

export function createDesktopBridge(): ARMusicBridge | undefined {
  if (window.armusic) return window.armusic;
  if (window.__TAURI_INTERNALS__) return createTauriBridge();
  return undefined;
}
