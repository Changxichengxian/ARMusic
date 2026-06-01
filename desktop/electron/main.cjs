const { app, BrowserWindow, dialog, ipcMain, net, protocol } = require("electron");
const os = require("node:os");
const path = require("node:path");
const { pathToFileURL } = require("node:url");
const { createManifest, importTrackStream, scanMusicFolder } = require("./library.cjs");
const { createSyncServer } = require("./syncServer.cjs");

let mainWindow = null;
let libraryFolder = null;
let libraryTracks = [];

protocol.registerSchemesAsPrivileged([
  {
    scheme: "armusic-track",
    privileges: {
      standard: true,
      secure: true,
      stream: true,
      supportFetchAPI: true,
    },
  },
]);

function publicTrack(track) {
  return {
    ...track,
    playUrl: `armusic-track://${encodeURIComponent(track.syncId)}`,
    filePath: undefined,
  };
}

function getManifest() {
  return createManifest(os.hostname(), libraryTracks);
}

async function importTrackFromSync(track, inputStream) {
  if (!libraryFolder) {
    throw new Error("请先在桌面端选择音乐文件夹");
  }

  const importedTrack = await importTrackStream(libraryFolder, track, inputStream);
  const scanResult = await scanMusicFolder(libraryFolder);
  libraryTracks = scanResult.tracks;

  return {
    folderPath: libraryFolder,
    scannedAt: scanResult.scannedAt,
    track: publicTrack(importedTrack),
    trackCount: libraryTracks.length,
  };
}

const syncServer = createSyncServer({
  getManifest,
  getTracks: () => libraryTracks,
  importTrack: importTrackFromSync,
});

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1240,
    height: 780,
    minWidth: 980,
    minHeight: 640,
    title: "ARMusic",
    backgroundColor: "#f4f7f1",
    webPreferences: {
      preload: path.join(__dirname, "preload.cjs"),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });

  if (process.env.VITE_DEV_SERVER_URL) {
    mainWindow.loadURL(process.env.VITE_DEV_SERVER_URL);
  } else {
    mainWindow.loadFile(path.join(__dirname, "..", "dist", "index.html"));
  }
}

app.whenReady().then(() => {
  app.setName("ARMusic");

  protocol.handle("armusic-track", (request) => {
    const url = new URL(request.url);
    const syncId = decodeURIComponent(url.hostname);
    const track = libraryTracks.find((item) => item.syncId === syncId);

    if (!track?.filePath) {
      return new Response("Track not found", { status: 404 });
    }

    return net.fetch(pathToFileURL(track.filePath).toString());
  });

  ipcMain.handle("library:chooseFolder", async () => {
    const result = await dialog.showOpenDialog(mainWindow, {
      title: "选择音乐文件夹",
      properties: ["openDirectory"],
    });

    if (result.canceled || result.filePaths.length === 0) {
      return { canceled: true, folderPath: libraryFolder, tracks: libraryTracks.map(publicTrack) };
    }

    const scanResult = await scanMusicFolder(result.filePaths[0]);
    libraryFolder = scanResult.folderPath;
    libraryTracks = scanResult.tracks;

    return {
      canceled: false,
      folderPath: libraryFolder,
      scannedAt: scanResult.scannedAt,
      tracks: libraryTracks.map(publicTrack),
    };
  });

  ipcMain.handle("library:getState", () => ({
    folderPath: libraryFolder,
    tracks: libraryTracks.map(publicTrack),
  }));

  ipcMain.handle("sync:start", () => syncServer.start());
  ipcMain.handle("sync:stop", () => syncServer.stop());
  ipcMain.handle("sync:status", () => syncServer.status());

  createWindow();

  app.on("activate", () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow();
    }
  });
});

app.on("window-all-closed", async () => {
  await syncServer.stop();

  if (process.platform !== "darwin") {
    app.quit();
  }
});
