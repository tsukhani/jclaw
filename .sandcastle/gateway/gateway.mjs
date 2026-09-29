// Egress gateway for AFK agent containers. It is the only route off the internal Docker network:
//   :3128  forward proxy — CONNECT to allowlisted hostnames on 443 only; everything else is refused
//   :8080  Anthropic API route — the real credential is injected here, so it never enters an agent container
import * as fs from "node:fs";
import * as http from "node:http";
import * as https from "node:https";
import * as net from "node:net";

// A read-only mount, not the environment: Docker keeps a container's environment in its config, where `docker inspect`
// shows it to anyone with Docker access.
const CREDENTIAL_FILE = "/run/factory/credential.env";
const vars = Object.fromEntries(
  fs.readFileSync(CREDENTIAL_FILE, "utf8").split("\n").map((l) => l.trim()).filter((l) => l.includes("=") && !l.startsWith("#"))
    .map((l) => [l.slice(0, l.indexOf("=")), l.slice(l.indexOf("=") + 1)]),
);
const credential = vars.CLAUDE_CODE_OAUTH_TOKEN || vars.ANTHROPIC_API_KEY;
if (!credential) throw new Error(`${CREDENTIAL_FILE} needs CLAUDE_CODE_OAUTH_TOKEN or ANTHROPIC_API_KEY`);

// One hostname per line; a leading dot matches that domain's subdomains.
const allowlist = fs
  .readFileSync(process.env.EGRESS_ALLOWLIST ?? "/gateway/egress-allowlist.txt", "utf8")
  .split("\n")
  .map((l) => l.replace(/#.*/, "").trim().toLowerCase())
  .filter(Boolean);
const allowed = (host) =>
  net.isIP(host) === 0 &&
  allowlist.some((e) => (e.startsWith(".") ? host.endsWith(e) : host === e));

const log = (entry) => console.log(JSON.stringify({ t: new Date().toISOString(), ...entry }));

const proxy = http.createServer((req, res) => {
  // Plain-HTTP proxying is refused outright: every registry the factory needs speaks HTTPS.
  log({ route: "proxy", method: req.method, target: req.url, allowed: false });
  res.writeHead(403).end("egress gateway: plain HTTP is not proxied\n");
});
proxy.on("connect", (req, client, head) => {
  const [host, portText] = (req.url ?? "").toLowerCase().split(":");
  const ok = portText === "443" && allowed(host);
  log({ route: "connect", target: req.url, allowed: ok });
  if (!ok) {
    client.end("HTTP/1.1 403 Forbidden\r\n\r\n");
    return;
  }
  const upstream = net.connect(443, host, () => {
    client.write("HTTP/1.1 200 Connection Established\r\n\r\n");
    upstream.write(head);
    upstream.pipe(client);
    client.pipe(upstream);
  });
  upstream.on("error", () => client.destroy());
  client.on("error", () => upstream.destroy());
});

// Model calls only: every other Anthropic endpoint would run on the operator's credential too.
const MODEL_ROUTES = new Set(["/v1/messages", "/v1/messages/count_tokens"]);
const HOP_BY_HOP = new Set(["connection", "keep-alive", "proxy-authorization", "proxy-connection", "te", "trailer", "upgrade", "host"]);
const anthropic = http.createServer((req, res) => {
  if (req.method !== "POST" || !MODEL_ROUTES.has(new URL(req.url ?? "/", "http://gateway").pathname)) {
    log({ route: "anthropic", method: req.method, path: req.url, allowed: false });
    res.writeHead(403).end();
    return;
  }
  const headers = Object.fromEntries(
    Object.entries(req.headers).filter(([k]) => !HOP_BY_HOP.has(k) && k !== "authorization" && k !== "x-api-key"),
  );
  if (credential.startsWith("sk-ant-oat")) {
    headers.authorization = `Bearer ${credential}`;
    // OAuth tokens are accepted only alongside this beta flag, which Claude Code omits in gateway mode.
    const beta = new Set(String(headers["anthropic-beta"] ?? "").split(",").map((s) => s.trim()).filter(Boolean));
    beta.add("oauth-2025-04-20");
    headers["anthropic-beta"] = [...beta].join(",");
  } else {
    headers["x-api-key"] = credential;
  }
  const upstream = https.request(
    { host: "api.anthropic.com", port: 443, method: req.method, path: req.url, headers },
    (up) => {
      log({ route: "anthropic", method: req.method, path: req.url, status: up.statusCode });
      res.writeHead(up.statusCode ?? 502, up.headers);
      up.pipe(res);
    },
  );
  upstream.on("error", (e) => {
    log({ route: "anthropic", method: req.method, path: req.url, error: e.message });
    if (!res.headersSent) res.writeHead(502);
    res.end();
  });
  req.pipe(upstream);
});

proxy.listen(3128, "0.0.0.0");
anthropic.listen(8080, "0.0.0.0");
log({ started: true, allowlist });
