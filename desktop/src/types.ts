export type TrackSource = "desktop" | "android";

export interface Track {
  syncId: string;
  legacySyncIds?: string[];
  revisionHash?: string;
  title: string;
  artist: string;
  album: string;
  work?: string;
  durationSeconds: number;
  sizeBytes: number;
  relativePath: string;
  playUrl?: string;
  modifiedAt?: string;
  playSeconds: number;
  lastPlayedAt?: string;
  source: TrackSource;
  localPath?: string;
  coverPath?: string;
  genre?: string;
  year?: string;
  coverUrl?: string;
  lyrics?: string;
}

export interface DevicePeer {
  id: string;
  name: string;
  address: string;
  lastSeen: string;
  trusted: boolean;
}

export interface SyncManifest {
  libraryId: string;
  deviceName: string;
  generatedAt: string;
  tracks: Track[];
}

export interface SyncPlan {
  download: Track[];
  upload: Track[];
  conflicts: Track[];
}

export interface LibraryScanResult {
  canceled?: boolean;
  folderPath?: string;
  scannedAt?: string;
  tracks: Track[];
}

export interface SyncServerStatus {
  running: boolean;
  port: number | null;
  addresses: string[];
  pairingToken?: string;
}

export interface ListeningSession {
  eventId: string;
  syncId: string;
  sourceDevice: string;
  startedAtMs: number;
  durationMs: number;
  repeatCount: number;
}

export interface HistorySyncPayload {
  schema: "armusic-listening-history-v2";
  deviceId: string;
  generatedAt: string;
  snapshotId: string;
  snapshotComplete: boolean;
  coveredSyncIds: string[];
  sessions: ListeningSession[];
}

export interface RecordListeningRequest {
  syncId: string;
  /** Stable for one play/pause lifecycle; later checkpoints update instead of double-counting. */
  sessionId: string;
  startedAtMs: number;
  durationMs: number;
  repeatCount?: number;
}

export interface AdbDevice {
  serial: string;
  model: string;
}

export interface AdbTrackSummary {
  syncId: string;
  title: string;
  artist: string;
  relativePath: string;
  sizeBytes: number;
}

export interface AdbSyncConflict {
  syncId: string;
  title: string;
  desktopRevisionHash?: string;
  phoneRevisionHash?: string;
  desktopModifiedAt?: string;
  phoneModifiedAt?: string;
  recommendedResolution?: ConflictResolution;
  reason: string;
}

export type ConflictResolution = "desktopToPhone" | "phoneToDesktop" | "skip";

export interface AdbSyncPreview {
  device: AdbDevice;
  desktopTrackCount: number;
  phoneTrackCount: number;
  uploadToPhone: AdbTrackSummary[];
  downloadToDesktop: AdbTrackSummary[];
  conflicts: AdbSyncConflict[];
  phoneHistoryCount: number;
  phoneHistorySnapshotId: string;
  phoneRawHistoryCount: number;
  phoneRawSnapshotId: string;
  wishlistSupported: boolean;
  desktopWishlistCategoryCount: number;
  desktopWishlistItemCount: number;
  phoneWishlistCategoryCount: number;
  phoneWishlistItemCount: number;
  wishlistCategoriesAddToDesktop: number;
  wishlistItemsAddToDesktop: number;
  wishlistCategoriesAddToPhone: number;
  wishlistItemsAddToPhone: number;
  wishlistSnapshotIdAfterMerge: string;
  playlistsSupported: boolean;
  desktopPlaylistCount: number;
  desktopPlaylistTrackCount: number;
  phonePlaylistCount: number;
  phonePlaylistTrackCount: number;
  playlistsAddToDesktop: number;
  playlistTracksAddToDesktop: number;
  playlistsAddToPhone: number;
  playlistTracksAddToPhone: number;
  playlistsDeleteFromDesktop: number;
  playlistsDeleteFromPhone: number;
  playlistsSnapshotIdAfterMerge: string;
  desktopIgnoredSyncTrackCount: number;
  phoneIgnoredSyncTrackCount: number;
  syncNotice: string;
}

export type HistorySyncMode = "keepOnBoth" | "desktopOnly";

export interface ExecuteAdbSyncRequest {
  serial?: string;
  syncSongs?: boolean;
  syncWishlist?: boolean;
  syncPlaylists?: boolean;
  historyMode?: HistorySyncMode;
  /** Backend rejects desktopOnly unless this is true; UI should ask twice. */
  confirmDeletePhoneHistory?: boolean;
  confirmedPhoneSnapshotId?: string;
  confirmedPhoneHistoryCount?: number;
  confirmedPhoneRawSnapshotId?: string;
  confirmedPhoneRawHistoryCount?: number;
  /** Opt-in only. Omitted/false means every unresolved conflict is skipped. */
  applyNewerConflicts?: boolean;
  conflictResolutions?: Record<string, ConflictResolution>;
}

export interface ExecuteAdbSyncResult {
  preview: AdbSyncPreview;
  uploadedToPhone: number;
  downloadedToDesktop: number;
  phoneImportedHistories: number;
  phoneDuplicateHistories: number;
  phoneClearedHistories: number;
  desktopHistorySessions: number;
  desktopHistorySnapshotId: string;
  conflictsLeftUntouched: number;
  warnings: string[];
  phoneHistoryBackupPath?: string;
  wishlistSynced: boolean;
  wishlistCategories: number;
  wishlistItems: number;
  wishlistSnapshotId: string;
  wishlistCategoriesAddedToDesktop: number;
  wishlistItemsAddedToDesktop: number;
  wishlistCategoriesAddedToPhone: number;
  wishlistItemsAddedToPhone: number;
  playlistsSynced: boolean;
  playlistCount: number;
  playlistTrackCount: number;
  playlistsSnapshotId: string;
  playlistsAddedToDesktop: number;
  playlistTracksAddedToDesktop: number;
  playlistsAddedToPhone: number;
  playlistTracksAddedToPhone: number;
  playlistsDeletedFromDesktop: number;
  playlistsDeletedFromPhone: number;
}

export type CoverAction = "keep" | "remove" | "replace";

export interface TrackTagData {
  syncId: string;
  fileName: string;
  relativePath: string;
  title: string;
  artist: string;
  album: string;
  work: string;
  sameSongGroup: string;
  genre: string;
  date: string;
  lyrics: string;
  hasEmbeddedCover: boolean;
  coverDataUrl?: string;
}

export interface UpdateTrackTagsRequest {
  syncId: string;
  title: string;
  artist: string;
  album: string;
  work: string;
  sameSongGroup: string;
  genre: string;
  date: string;
  lyrics: string;
  coverAction: CoverAction;
  coverDataBase64?: string;
}

export interface TagSaveResult {
  previousSyncId: string;
  newSyncId: string;
  warning?: string;
  library: LibraryScanResult;
}

export interface WishlistCategoryData {
  id: string;
  title: string;
  color: number;
  items: string[];
}

export interface WishlistPayload {
  schema: "armusic-wishlist-v2";
  deviceId: string;
  generatedAt: string;
  snapshotId: string;
  phoneBaselineEstablished?: boolean;
  categories: WishlistCategoryData[];
}

export interface WishlistSaveRequest {
  expectedSnapshotId: string;
  categories: WishlistCategoryData[];
}

export interface PlaylistData {
  id: string;
  title: string;
  subTitle: string;
  coverUri: string;
  createTime: number;
  modifyTime: number;
  trackIds: string[];
}

export interface PlaylistsPayload {
  schema: "armusic-playlists-v1";
  deviceId: string;
  generatedAt: string;
  snapshotId: string;
  phoneBaselineEstablished?: boolean;
  playlists: PlaylistData[];
  deletedPlaylists?: Array<{ id: string; deletedAt: number }>;
  removedTracks?: Array<{ playlistId: string; trackId: string; removedAt: number }>;
}

export interface PlaylistsSaveRequest {
  expectedSnapshotId: string;
  playlists: PlaylistData[];
}

export interface ARMusicBridge {
  chooseMusicFolder: () => Promise<LibraryScanResult>;
  getLibraryState: () => Promise<LibraryScanResult>;
  startSyncServer: () => Promise<SyncServerStatus>;
  stopSyncServer: () => Promise<SyncServerStatus>;
  getSyncStatus: () => Promise<SyncServerStatus>;
  getTrackTags: (syncId: string) => Promise<TrackTagData>;
  saveTrackTags: (request: UpdateTrackTagsRequest) => Promise<TagSaveResult>;
  recordListeningSession: (request: RecordListeningRequest) => Promise<ListeningSession>;
  getListeningHistory: () => Promise<HistorySyncPayload>;
  getWishlist: () => Promise<WishlistPayload>;
  saveWishlist: (request: WishlistSaveRequest) => Promise<WishlistPayload>;
  migrateLegacyWishlist: (categories: WishlistCategoryData[]) => Promise<WishlistPayload>;
  getPlaylists: () => Promise<PlaylistsPayload>;
  savePlaylists: (request: PlaylistsSaveRequest) => Promise<PlaylistsPayload>;
  listAdbDevices: () => Promise<AdbDevice[]>;
  previewAdbSync: (serial?: string) => Promise<AdbSyncPreview>;
  executeAdbSync: (request: ExecuteAdbSyncRequest) => Promise<ExecuteAdbSyncResult>;
  openExternalUrl: (url: string) => Promise<void>;
}

export type ViewId =
  | "home"
  | "songs"
  | "works"
  | "artists"
  | "history"
  | "playlists"
  | "wishlist"
  | "sync"
  | "settings";

export type InspectorTab = "lyrics" | "queue";
