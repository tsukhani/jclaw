// node --test bin/candidate-keep.test.mjs
// The CLI runs against a local HTTP stub of Jenkins, so no test reaches the real one.

import { deepStrictEqual, strictEqual } from "node:assert";
import { spawn } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import { toUnkeep } from "./candidate-keep.mjs";

const SCRIPT = join(resolve(dirname(fileURLToPath(import.meta.url))), "candidate-keep.mjs");
const AUTH = "Basic dXNlcjp0b2tlbg==";
const REL = "a".repeat(40);
const OLD = "b".repeat(40);
// A candidate job's build carries an rc label (JF-465); a legacy JClaw candidate is named for its commit.
const kept = (number, sha, keepLog = true) => ({ number, displayName: `v1.0.${number}-rc.1`, description: `tested ${sha}`, keepLog });
const legacyKept = (number, sha, keepLog = true) => ({ number, displayName: `#${number} candidate ${sha.slice(0, 12)}`,
  description: `tested ${sha}`, keepLog });

test("--keep un-keeps every kept candidate but the release's, by the commit it tested, and touches no other build", () => {
  const builds = [kept(3, REL), kept(2, OLD), kept(1, "c".repeat(40), false), { number: 4, displayName: "v1.0.4-rc.1", keepLog: true },
    { number: 5, displayName: "v1.0.5-rc.1", description: `tested ${OLD.slice(0, 12)}`, keepLog: true }];
  deepStrictEqual(toUnkeep(builds, { keep: REL }, "JClaw-candidate").map((b) => b.number), [2]);
});

test("in the legacy JClaw job only a kept build named for its commit is a candidate", () => {
  const builds = [legacyKept(1426, OLD), { number: 1427, displayName: "#1427", description: `tested ${OLD}`, keepLog: true },
    kept(1428, OLD)];
  deepStrictEqual(toUnkeep(builds, { keep: REL }, "JClaw").map((b) => b.number), [1426]);
});

test("--drop un-keeps the build of that commit alone, and nothing when it is not kept", () => {
  const builds = [kept(3, REL), kept(2, OLD)];
  deepStrictEqual(toUnkeep(builds, { drop: OLD }, "JClaw-candidate").map((b) => b.number), [2]);
  deepStrictEqual(toUnkeep([kept(2, OLD, false)], { drop: OLD }, "JClaw-candidate"), []);
  deepStrictEqual(toUnkeep(undefined, { drop: OLD }, "JClaw-candidate"), []);
});

const withJenkins = async (handle, body) => {
  const seen = [];
  const server = createServer((req, res) => {
    seen.push({ method: req.method, url: req.url, headers: req.headers });
    handle(req, res);
  });
  await new Promise((ready) => server.listen(0, "127.0.0.1", ready));
  const dir = mkdtempSync(join(tmpdir(), "candidate-keep-"));
  try {
    const config = join(dir, "claude.json");
    writeFileSync(config, JSON.stringify({ mcpServers: { jenkins: { args: ["-y", "mcp-remote",
      `http://127.0.0.1:${server.address().port}/mcp-server/mcp`, "--header", `Authorization:${AUTH}`] } } }));
    await body({ config, dir, seen });
  } finally {
    await new Promise((closed) => server.close(closed));
    rmSync(dir, { recursive: true, force: true });
  }
};

const cli = (args, config) => new Promise((settle) => {
  const child = spawn(process.execPath, [SCRIPT, ...args], { env: { ...process.env, JCLAW_CLAUDE_JSON: config } });
  let stdout = "";
  let stderr = "";
  child.stdout.on("data", (d) => { stdout += d; });
  child.stderr.on("data", (d) => { stderr += d; });
  child.on("close", (code) => settle({ code, stdout, stderr }));
});

// The candidate job's builds, and the older JClaw job's, which held candidates before JF-453.
const jenkins = ({ candidates = [], legacy = [], toggle = 302 }) => (req, res) => {
  if (req.url.startsWith("/job/JClaw-candidate/api/json") || req.url.startsWith("/job/JClaw/api/json")) {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ builds: req.url.startsWith("/job/JClaw-candidate/") ? candidates : legacy }));
  } else if (req.url === "/crumbIssuer/api/json") {
    res.writeHead(200, { "Content-Type": "application/json", "Set-Cookie": "JSESSIONID.x=abc; Path=/; HttpOnly" });
    res.end(JSON.stringify({ crumbRequestField: "Jenkins-Crumb", crumb: "c-123" }));
  } else {
    res.writeHead(toggle, { Location: "/job/JClaw-candidate/" });
    res.end();
  }
};

test("the CLI un-keeps each chosen build in either job, with the crumb and its session, names it, and exits 0", async () => {
  const candidates = [kept(3, REL), kept(2, OLD)];
  const legacy = [legacyKept(1426, "d".repeat(40)), legacyKept(1424, "e".repeat(40), false)];
  await withJenkins(jenkins({ candidates, legacy }), async ({ config, seen }) => {
    deepStrictEqual(await cli(["--keep", REL], config), { code: 0, stderr: "",
      stdout: "Un-kept JClaw-candidate #2 (v1.0.2-rc.1)\n"
        + `Un-kept JClaw #1426 (#1426 candidate ${"d".repeat(12)})\n` });
    deepStrictEqual(seen.map((s) => `${s.method} ${s.url}`), [
      "GET /job/JClaw-candidate/api/json?tree=builds[number,displayName,description,keepLog]",
      "GET /job/JClaw/api/json?tree=builds[number,displayName,description,keepLog]",
      "GET /crumbIssuer/api/json",
      "POST /job/JClaw-candidate/2/toggleLogKeep",
      "POST /job/JClaw/1426/toggleLogKeep",
    ]);
    strictEqual(seen[3].headers.authorization, AUTH);
    strictEqual(seen[3].headers["jenkins-crumb"], "c-123");
    strictEqual(seen[3].headers.cookie, "JSESSIONID.x=abc");
  });
});

test("with nothing kept to un-keep the CLI says so, posts nothing, and exits 0", async () => {
  await withJenkins(jenkins({ candidates: [kept(3, REL), kept(2, OLD, false)] }), async ({ config, seen }) => {
    deepStrictEqual(await cli(["--keep", REL], config), { code: 0, stdout: "No kept candidate build to un-keep.\n", stderr: "" });
    strictEqual(seen.some((s) => s.method === "POST"), false);
  });
});

test("a refused un-keep stops the run with exit 2, after naming what it did un-keep", async () => {
  const candidates = [kept(3, REL), kept(2, OLD)];
  await withJenkins(jenkins({ candidates, toggle: 403 }), async ({ config, seen }) => {
    deepStrictEqual(await cli(["--drop", OLD], config), { code: 2, stdout: "",
      stderr: "Jenkins answered 403 to un-keeping JClaw-candidate #2\n" });
    strictEqual(seen.filter((s) => s.method === "POST").length, 1);
  });
});

test("the CLI exits 2 when Jenkins refuses the listing or is not there", async () => {
  await withJenkins((req, res) => { res.writeHead(503); res.end(); }, async ({ config }) => {
    deepStrictEqual(await cli(["--keep", REL], config), { code: 2, stdout: "",
      stderr: "Jenkins answered 503 for JClaw-candidate's builds\n" });
  });
  await withJenkins(jenkins({}), async ({ config, dir }) => {
    const away = join(dir, "away.json");
    writeFileSync(away, JSON.stringify({ mcpServers: { jenkins: { args: ["http://127.0.0.1:9/mcp-server/mcp",
      "--header", `Authorization:${AUTH}`] } } }));
    const result = await cli(["--keep", REL], away);
    strictEqual(result.code, 2);
    strictEqual(result.stderr.startsWith("Jenkins could not be asked: "), true, result.stderr);
    strictEqual((await cli(["--keep", REL], config)).code, 0);
  });
});

test("the CLI exits 2 without asking Jenkins for a bad argument or a config it cannot read", async () => {
  await withJenkins(jenkins({}), async ({ config, dir, seen }) => {
    const usage = "give --keep <full sha> to un-keep every other candidate, or --drop <full sha> to un-keep that one\n";
    for (const args of [[], ["--keep"], ["--keep", REL.slice(0, 12)], ["--both", REL], ["--keep", REL, "extra"]]) {
      deepStrictEqual(await cli(args, config), { code: 2, stdout: "", stderr: usage }, args.join(" "));
    }
    const missing = join(dir, "missing.json");
    deepStrictEqual(await cli(["--drop", REL], missing), { code: 2, stdout: "",
      stderr: `no jenkins MCP server with an Authorization header in ${missing}\n` });
    deepStrictEqual(seen, []);
  });
});
