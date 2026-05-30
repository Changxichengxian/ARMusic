import type { DevicePeer, Track } from "../types";

export const localTracks: Track[] = [
  {
    syncId: "sha256-local-001",
    title: "春天的故事",
    artist: "董文华",
    album: "经典老歌",
    durationSeconds: 284,
    sizeBytes: 2706870,
    relativePath: "经典老歌/春天的故事.wma",
    playSeconds: 6310,
    lastPlayedAt: "今天 08:42",
    source: "desktop",
  },
  {
    syncId: "sha256-local-002",
    title: "外婆的澎湖湾",
    artist: "潘安邦",
    album: "民谣",
    durationSeconds: 214,
    sizeBytes: 1101990,
    relativePath: "民谣/外婆的澎湖湾.wma",
    playSeconds: 4210,
    lastPlayedAt: "昨天 22:18",
    source: "desktop",
  },
  {
    syncId: "sha256-shared-001",
    title: "南泥湾",
    artist: "郭兰英",
    album: "红色经典",
    durationSeconds: 205,
    sizeBytes: 3051064,
    relativePath: "红色经典/南泥湾.mp3",
    playSeconds: 2320,
    lastPlayedAt: "周三 19:20",
    source: "desktop",
  },
];

export const remoteTracks: Track[] = [
  {
    syncId: "sha256-phone-001",
    title: "大海啊故乡",
    artist: "朱明瑛",
    album: "经典老歌",
    durationSeconds: 196,
    sizeBytes: 3123200,
    relativePath: "经典老歌/大海啊故乡.mp3",
    playSeconds: 1250,
    lastPlayedAt: "今天 07:13",
    source: "android",
  },
  {
    syncId: "sha256-shared-001",
    title: "南泥湾",
    artist: "郭兰英",
    album: "红色经典",
    durationSeconds: 205,
    sizeBytes: 3051064,
    relativePath: "红色经典/南泥湾.mp3",
    playSeconds: 1960,
    lastPlayedAt: "今天 06:45",
    source: "android",
  },
];

export const peers: DevicePeer[] = [
  {
    id: "android-main",
    name: "ARMusic Android",
    address: "192.168.31.46:47832",
    lastSeen: "刚刚",
    trusted: true,
  },
];
