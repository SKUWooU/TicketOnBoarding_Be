import assert from "node:assert/strict";
import http from "node:http";
import test from "node:test";

import { assertLoopbackTarget, startLoopbackProxy } from "./loopback-proxy.mjs";

function listenServer(label) {
  return new Promise((resolve) => {
    const server = http.createServer((_, response) => {
      response.writeHead(200, { "content-type": "text/plain" });
      response.end(label);
    });
    server.listen(0, "127.0.0.1", () => resolve(server));
  });
}

function request(port) {
  return new Promise((resolve, reject) => {
    http.get(`http://127.0.0.1:${port}/health`, (response) => {
      let body = "";
      response.on("data", (chunk) => { body += chunk; });
      response.on("end", () => resolve({ body, headers: response.headers }));
    }).on("error", reject);
  });
}

test("rejects non-loopback proxy targets", () => {
  assert.throws(() => assertLoopbackTarget("https://example.com:443"), /loopback/);
  assert.throws(() => assertLoopbackTarget("http://127.0.0.1"), /port/);
  assert.equal(assertLoopbackTarget("http://[::1]:18080").hostname, "[::1]");
});

test("alternates only between loopback upstreams and reports its target", async (context) => {
  const first = await listenServer("instance-a");
  const second = await listenServer("instance-b");
  const firstPort = first.address().port;
  const secondPort = second.address().port;
  const proxy = await startLoopbackProxy({
    targets: [`http://127.0.0.1:${firstPort}`, `http://127.0.0.1:${secondPort}`],
    port: 0,
  });
  const proxyPort = proxy.address().port;
  context.after(() => Promise.all([first, second, proxy].map((server) => new Promise((resolve) => server.close(resolve)))));

  const firstResponse = await request(proxyPort);
  const secondResponse = await request(proxyPort);

  assert.equal(firstResponse.body, "instance-a");
  assert.equal(secondResponse.body, "instance-b");
  assert.equal(firstResponse.headers["x-onticket-proxy-target"], `127.0.0.1:${firstPort}`);
  assert.equal(secondResponse.headers["x-onticket-proxy-target"], `127.0.0.1:${secondPort}`);
});
