const crypto = require("node:crypto");
const fs = require("node:fs");
const fsp = require("node:fs/promises");
const path = require("node:path");

const AUDIO_EXTENSIONS = new Set([
  ".mp3",
  ".flac",
  ".wav",
  ".m4a",
  ".aac",
  ".ogg",
  ".ape",
  ".wma",
]);

function isAudioFile(filePath) {
  return AUDIO_EXTENSIONS.has(path.extname(filePath).toLowerCase());
}

function normalizeRelativePath(filePath, rootPath) {
  return path.relative(rootPath, filePath).split(path.sep).join("/");
}

function guessTrackName(filePath) {
  const fileName = path.basename(filePath, path.extname(filePath)).trim();
  const parts = fileName.split(/\s+-\s+/);

  if (parts.length >= 2) {
    return {
      artist: parts[0].trim() || "未知歌手",
      title: parts.slice(1).join(" - ").trim() || fileName,
    };
  }

  return {
    artist: "未知歌手",
    title: fileName || "未命名歌曲",
  };
}

async function createSyncId(filePath, stat) {
  const hash = crypto.createHash("sha256");
  const chunkSize = 64 * 1024;
  const handle = await fsp.open(filePath, "r");

  try {
    hash.update(String(stat.size));
    hash.update(path.basename(filePath).toLowerCase());

    const first = Buffer.alloc(Math.min(chunkSize, stat.size));
    if (first.length > 0) {
      await handle.read(first, 0, first.length, 0);
      hash.update(first);
    }

    if (stat.size > chunkSize) {
      const lastSize = Math.min(chunkSize, stat.size);
      const last = Buffer.alloc(lastSize);
      await handle.read(last, 0, lastSize, Math.max(0, stat.size - lastSize));
      hash.update(last);
    }
  } finally {
    await handle.close();
  }

  return `sha256-${hash.digest("hex").slice(0, 32)}`;
}

async function createTrack(filePath, rootPath) {
  const stat = await fsp.stat(filePath);
  const guessed = guessTrackName(filePath);
  const relativePath = normalizeRelativePath(filePath, rootPath);

  return {
    syncId: await createSyncId(filePath, stat),
    title: guessed.title,
    artist: guessed.artist,
    album: path.basename(path.dirname(filePath)) || "本地音乐",
    durationSeconds: 0,
    sizeBytes: stat.size,
    relativePath,
    filePath,
    modifiedAt: stat.mtime.toISOString(),
    playSeconds: 0,
    source: "desktop",
  };
}

async function walkDirectory(rootPath, currentPath, files, maxFiles) {
  if (files.length >= maxFiles) {
    return;
  }

  const entries = await fsp.readdir(currentPath, { withFileTypes: true });

  for (const entry of entries) {
    if (entry.name.startsWith(".")) {
      continue;
    }

    const fullPath = path.join(currentPath, entry.name);

    if (entry.isDirectory()) {
      await walkDirectory(rootPath, fullPath, files, maxFiles);
    } else if (entry.isFile() && isAudioFile(fullPath)) {
      files.push(fullPath);
    }

    if (files.length >= maxFiles) {
      return;
    }
  }
}

async function scanMusicFolder(folderPath, options = {}) {
  const rootPath = path.resolve(folderPath);
  const maxFiles = options.maxFiles ?? 5000;
  const stat = await fsp.stat(rootPath);

  if (!stat.isDirectory()) {
    throw new Error("请选择一个音乐文件夹");
  }

  const files = [];
  await walkDirectory(rootPath, rootPath, files, maxFiles);

  const tracks = [];
  for (const filePath of files) {
    try {
      tracks.push(await createTrack(filePath, rootPath));
    } catch (error) {
      console.warn(`Failed to scan ${filePath}`, error);
    }
  }

  tracks.sort((a, b) => a.relativePath.localeCompare(b.relativePath, "zh-Hans-CN"));

  return {
    folderPath: rootPath,
    tracks,
    scannedAt: new Date().toISOString(),
  };
}

function trackToPublicTrack(track) {
  return {
    ...track,
    filePath: undefined,
  };
}

function createManifest(deviceName, tracks) {
  return {
    libraryId: `desktop-${deviceName}`,
    deviceName,
    generatedAt: new Date().toISOString(),
    tracks: tracks.map(trackToPublicTrack),
  };
}

module.exports = {
  createManifest,
  scanMusicFolder,
  trackToPublicTrack,
};
