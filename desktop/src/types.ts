export type TrackSource = "desktop" | "android";

export interface Track {
  syncId: string;
  title: string;
  artist: string;
  album: string;
  durationSeconds: number;
  sizeBytes: number;
  relativePath: string;
  playUrl?: string;
  modifiedAt?: string;
  playSeconds: number;
  lastPlayedAt?: string;
  source: TrackSource;
  localPath?: string;
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
}

export interface ARMusicBridge {
  chooseMusicFolder: () => Promise<LibraryScanResult>;
  getLibraryState: () => Promise<LibraryScanResult>;
  startSyncServer: () => Promise<SyncServerStatus>;
  stopSyncServer: () => Promise<SyncServerStatus>;
  getSyncStatus: () => Promise<SyncServerStatus>;
}
