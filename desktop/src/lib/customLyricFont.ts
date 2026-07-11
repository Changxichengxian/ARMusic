export const CUSTOM_LYRIC_FONT_FAMILY = "ARMusic Custom Lyrics";

const DATABASE_NAME = "armusic-desktop-assets";
const STORE_NAME = "fonts";
const CUSTOM_FONT_KEY = "custom-lyrics";
let activeFace: FontFace | null = null;

function openDatabase(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DATABASE_NAME, 1);
    request.onupgradeneeded = () => {
      if (!request.result.objectStoreNames.contains(STORE_NAME)) {
        request.result.createObjectStore(STORE_NAME);
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error ?? new Error("无法打开字体存储"));
  });
}

function readStoredFont(): Promise<Blob | null> {
  return openDatabase().then((database) => new Promise((resolve, reject) => {
    const transaction = database.transaction(STORE_NAME, "readonly");
    const request = transaction.objectStore(STORE_NAME).get(CUSTOM_FONT_KEY);
    request.onsuccess = () => resolve(request.result instanceof Blob ? request.result : null);
    request.onerror = () => reject(request.error ?? new Error("无法读取自定义字体"));
    transaction.oncomplete = () => database.close();
  }));
}

function saveFont(file: File): Promise<void> {
  return openDatabase().then((database) => new Promise((resolve, reject) => {
    const transaction = database.transaction(STORE_NAME, "readwrite");
    transaction.objectStore(STORE_NAME).put(file, CUSTOM_FONT_KEY);
    transaction.oncomplete = () => {
      database.close();
      resolve();
    };
    transaction.onerror = () => {
      database.close();
      reject(transaction.error ?? new Error("无法保存自定义字体"));
    };
  }));
}

async function installFont(source: Blob): Promise<void> {
  const face = new FontFace(CUSTOM_LYRIC_FONT_FAMILY, await source.arrayBuffer());
  await face.load();
  if (activeFace) document.fonts.delete(activeFace);
  document.fonts.add(face);
  activeFace = face;
}

export async function loadStoredCustomLyricFont(): Promise<boolean> {
  const stored = await readStoredFont();
  if (!stored) return false;
  await installFont(stored);
  return true;
}

export async function importCustomLyricFont(file: File): Promise<void> {
  if (!/\.(?:ttf|otf|woff2?)$/i.test(file.name)) {
    throw new Error("unsupported-font");
  }
  if (file.size <= 0 || file.size > 50 * 1024 * 1024) {
    throw new Error("invalid-font-size");
  }
  await installFont(file);
  await saveFont(file);
}
