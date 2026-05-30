import { spawn } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";
import electron from "electron";
import { createServer } from "vite";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const rootDir = path.resolve(__dirname, "..");

const server = await createServer({
  root: rootDir,
  server: {
    host: "127.0.0.1",
    port: 5173,
    strictPort: false,
  },
});

await server.listen();

const url = server.resolvedUrls?.local?.[0] ?? "http://127.0.0.1:5173/";
const child = spawn(electron, ["."], {
  cwd: rootDir,
  stdio: "inherit",
  env: {
    ...process.env,
    VITE_DEV_SERVER_URL: url,
  },
});

child.on("exit", async (code) => {
  await server.close();
  process.exit(code ?? 0);
});
