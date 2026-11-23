import type { Track } from "./types";

const demo = (name: string) => import.meta.env.BASE_URL + "demo/" + name;

export const railVersions = [
  { label: "毡音键盘", url: demo("last-train.wav") },
  { label: "高八度钟琴", url: demo("last-train-bells.wav") },
];

export const demoTracks: Track[] = [
  { syncId: "demo-night-rail", title: "末班车经过山谷", artist: "ARMusic", album: "夜行支线",
    work: "夜行支线", genre: "动画短片原声", durationSeconds: 21.97, sizeBytes: 968902,
    relativePath: "demo/last-train.wav", playUrl: railVersions[0].url, coverUrl: demo("rail.jpg"),
    source: "desktop", playSeconds: 0 },
  { syncId: "demo-tide-atlas", title: "浅湾航线", artist: "ARMusic", album: "退潮图志",
    work: "退潮图志", genre: "探索游戏原声", durationSeconds: 23.43, sizeBytes: 1033112,
    relativePath: "demo/ebb.wav", playUrl: demo("ebb.wav"), coverUrl: demo("sail.jpg"),
    source: "desktop", playSeconds: 0 },
];
