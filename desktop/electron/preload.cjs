const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("armusic", {
  chooseMusicFolder: () => ipcRenderer.invoke("library:chooseFolder"),
  getLibraryState: () => ipcRenderer.invoke("library:getState"),
  startSyncServer: () => ipcRenderer.invoke("sync:start"),
  stopSyncServer: () => ipcRenderer.invoke("sync:stop"),
  getSyncStatus: () => ipcRenderer.invoke("sync:status"),
});
