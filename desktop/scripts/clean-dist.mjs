import { existsSync, lstatSync, readdirSync, rmdirSync, unlinkSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const desktopRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const dist = resolve(desktopRoot, "dist");

function removeTree(path) {
  if (!existsSync(path)) return;
  if (!lstatSync(path).isDirectory()) {
    unlinkSync(path);
    return;
  }

  for (const entry of readdirSync(path)) {
    removeTree(join(path, entry));
  }
  rmdirSync(path);
}

removeTree(dist);
