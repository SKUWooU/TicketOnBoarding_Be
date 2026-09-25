import http from "node:http";
import { fileURLToPath } from "node:url";

const LOOPBACK_TARGET_HOSTS = new Set(["127.0.0.1", "localhost", "[::1]"]);
const LOOPBACK_BIND_HOSTS = new Set(["127.0.0.1", "localhost", "::1"]);

export function assertLoopbackTarget(target) {
  const url = new URL(target);
  if (url.protocol !== "http:" || !LOOPBACK_TARGET_HOSTS.has(url.hostname)) {
    throw new Error(`Only loopback http targets are allowed: ${target}`);
  }
  if (!url.port) {
    throw new Error(`A target port is required: ${target}`);
  }
  return url;
}

export function createRoundRobinProxy({ targets, logger = console }) {
  if (!Array.isArray(targets) || targets.length < 2) {
    throw new Error("At least two loopback targets are required.");
  }

  const upstreams = targets.map(assertLoopbackTarget);
  let nextTarget = 0;

  return http.createServer((request, response) => {
    const target = upstreams[nextTarget % upstreams.length];
    nextTarget += 1;

    const upstream = http.request(
      {
        host: target.hostname,
        port: target.port,
        method: request.method,
        path: request.url,
        headers: { ...request.headers, host: target.host },
      },
      (upstreamResponse) => {
        response.writeHead(upstreamResponse.statusCode ?? 502, {
          ...upstreamResponse.headers,
          "x-onticket-proxy-target": target.host,
        });
        upstreamResponse.pipe(response);
      },
    );

    upstream.on("error", (error) => {
      logger.error(`loopback proxy upstream failure (${target.host}): ${error.message}`);
      if (!response.headersSent) {
        response.writeHead(502, { "content-type": "application/json" });
      }
      response.end(JSON.stringify({ error: "local proxy upstream unavailable" }));
    });

    request.pipe(upstream);
  });
}

export function startLoopbackProxy({
  targets,
  host = "127.0.0.1",
  port = 18090,
  logger = console,
}) {
  if (!LOOPBACK_BIND_HOSTS.has(host)) {
    throw new Error(`Only a loopback bind address is allowed: ${host}`);
  }

  const server = createRoundRobinProxy({ targets, logger });
  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(port, host, () => {
      server.off("error", reject);
      resolve(server);
    });
  });
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const targets = (process.env.ONTICKET_PROXY_TARGETS ?? "http://127.0.0.1:18080,http://127.0.0.1:18082")
    .split(",")
    .map((target) => target.trim())
    .filter(Boolean);
  const port = Number(process.env.ONTICKET_PROXY_PORT ?? "18090");
  const server = await startLoopbackProxy({ targets, port });
  console.log(`loopback two-instance proxy listening on 127.0.0.1:${port}`);
  const stop = () => server.close(() => process.exit(0));
  process.once("SIGINT", stop);
  process.once("SIGTERM", stop);
}
