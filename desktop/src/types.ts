export type TrackSource = "desktop" | "android";

export interface Track {
  syncId: string;
  title: string;
  artist: string;
  album: string;
  durationSeconds: number;
  sizeBytes: number;
  relativePath: string;
  playSeconds: number;
  lastPlayedAt?: string;
  source: TrackSource;
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
