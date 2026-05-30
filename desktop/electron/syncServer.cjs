const http = require("node:http");
const os = require("node:os");
const fs = require("node:fs");

function getLanAddresses(port) {
  const interfaces = os.networkInterfaces();
  const addresses = [];

  for (const values of Object.values(interfaces)) {
    for (const item of values ?? []) {
      if (item.family === "IPv4" && !item.internal) {
        addresses.push(`http://${item.address}:${port}`);
      }
    }
  }

  if (addresses.length === 0) {
    addresses.push(`http://127.0.0.1:${port}`);
  }

  return addresses;
}

function writeJson(res, statusCode, payload) {
  const body = JSON.stringify(payload, null, 2);
  res.writeHead(statusCode, {
    "Access-Control-Allow-Origin": "*",
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": Buffer.byteLength(body),
  });
  res.end(body);
}

function createSyncServer({ getManifest, getTracks }) {
  let server = null;
  let port = null;

  async function start(preferredPort = 49321) {
    if (server && port) {
      return status();
    }

    server = http.createServer((req, res) => {
      const url = new URL(req.url ?? "/", "http://127.0.0.1");

      if (req.method === "OPTIONS") {
        res.writeHead(204, {
          "Access-Control-Allow-Origin": "*",
          "Access-Control-Allow-Methods": "GET, OPTIONS",
          "Access-Control-Allow-Headers": "Content-Type",
        });
        res.end();
        return;
      }

      if (req.method !== "GET") {
        writeJson(res, 405, { error: "只支持 GET" });
        return;
      }

      if (url.pathname === "/health") {
        writeJson(res, 200, { ok: true, name: "ARMusic Desktop", time: new Date().toISOString() });
        return;
      }

      if (url.pathname === "/manifest") {
        writeJson(res, 200, getManifest());
        return;
      }

      if (url.pathname.startsWith("/tracks/")) {
        const syncId = decodeURIComponent(url.pathname.slice("/tracks/".length));
        const track = getTracks().find((item) => item.syncId === syncId);

        if (!track?.filePath) {
          writeJson(res, 404, { error: "没有找到这首歌" });
          return;
        }

        res.writeHead(200, {
          "Access-Control-Allow-Origin": "*",
          "Content-Type": "application/octet-stream",
          "Content-Disposition": `attachment; filename*=UTF-8''${encodeURIComponent(track.relativePath)}`,
        });
        fs.createReadStream(track.filePath).pipe(res);
        return;
      }

      writeJson(res, 404, { error: "未知接口" });
    });

    try {
      await listen(server, preferredPort);
    } catch (error) {
      if (error?.code !== "EADDRINUSE" || preferredPort === 0) {
        server = null;
        throw error;
      }

      await listen(server, 0);
    }

    port = server.address().port;
    return status();
  }

  async function stop() {
    if (!server) {
      return status();
    }

    await new Promise((resolve) => server.close(resolve));
    server = null;
    port = null;
    return status();
  }

  function status() {
    return {
      running: Boolean(server && port),
      port,
      addresses: port ? getLanAddresses(port) : [],
    };
  }

  return {
    start,
    stop,
    status,
  };
}

function listen(server, targetPort) {
  return new Promise((resolve, reject) => {
    const onError = (error) => {
      server.off("listening", onListening);
      reject(error);
    };
    const onListening = () => {
      server.off("error", onError);
      resolve();
    };

    server.once("error", onError);
    server.once("listening", onListening);
    server.listen(targetPort, "0.0.0.0");
  });
}

module.exports = {
  createSyncServer,
};
