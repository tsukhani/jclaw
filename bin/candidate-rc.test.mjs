// node --test bin/candidate-rc.test.mjs
// The CLI runs against a local HTTP stub of Jenkins and a scratch repository whose origin is a local bare one, so no test
// reaches the real Jenkins or Bitbucket, or reads ~/.claude.json.

import { deepStrictEqual, strictEqual } from "node:assert";
import { spawn, spawnSync } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import { nextRc } from "./candidate-rc.mjs";

const SCRIPT = join(resolve(dirname(fileURLToPath(import.meta.url))), "candidate-rc.mjs");
const AUTH = "Basic dXNlcjp0b2tlbg==";
const ref = (name) => `refs/heads/candidate/${name}`;

test("the next rc is one past the highest the version's builds were named and its branches carry", () => {
  deepStrictEqual(nextRc([], [], "1.0.22"), 1);
  deepStrictEqual(nextRc(["v1.0.22-rc.1"], [], "1.0.22"), 2);
  deepStrictEqual(nextRc([], [ref("v1.0.22-rc.2")], "1.0.22"), 3, "a branch whose build never started still counts");
  deepStrictEqual(nextRc(["v1.0.22-rc.3", "v1.0.22-rc.1"], [ref("v1.0.22-rc.2")], "1.0.22"), 4);
});

test("other versions, a still-unnamed build and a sha-named branch take no number", () => {
  deepStrictEqual(nextRc(["v1.0.21-rc.7", "v1.0.220-rc.9", "v1.0.2-rc.5", "#5", undefined, "v1.0.22-rc.x"],
    [ref("v1.0.21-rc.8"), ref("a478b5604a0a"), "refs/heads/main", ref("v1.0.22-rc.1-old")], "1.0.22"), 1);
});

const git = (cwd, ...args) => spawnSync("git", args, { cwd, encoding: "utf8" });

const withOrigin = async (branches, respond, body) => {
  const dir = mkdtempSync(join(tmpdir(), "candidate-rc-"));
  const server = createServer(respond);
  await new Promise((ready) => server.listen(0, "127.0.0.1", ready));
  try {
    const work = join(dir, "work");
    git(dir, "init", "-q", "--bare", "origin.git");
    git(dir, "init", "-q", "-b", "main", "work");
    git(work, "-c", "user.name=t", "-c", "user.email=t@example.com", "commit", "-q", "--allow-empty", "--no-gpg-sign",
      "-m", "base");
    git(work, "remote", "add", "origin", join(dir, "origin.git"));
    for (const b of branches) git(dir, "--git-dir=origin.git", "fetch", "-q", join(dir, "work"), `main:refs/heads/${b}`);
    const config = join(dir, "claude.json");
    writeFileSync(config, JSON.stringify({ mcpServers: { jenkins: { args: ["-y", "mcp-remote",
      `http://127.0.0.1:${server.address().port}/mcp-server/mcp`, "--header", `Authorization:${AUTH}`] } } }));
    await body({ config, dir, work });
  } finally {
    await new Promise((closed) => server.close(closed));
    rmSync(dir, { recursive: true, force: true });
  }
};

const cli = (args, config, cwd) => new Promise((settle) => {
  const child = spawn(process.execPath, [SCRIPT, ...args], { cwd, env: { ...process.env, JCLAW_CLAUDE_JSON: config } });
  let stdout = "";
  let stderr = "";
  child.stdout.on("data", (d) => { stdout += d; });
  child.stderr.on("data", (d) => { stderr += d; });
  child.on("close", (code) => settle({ code, stdout, stderr }));
});

const builds = (...names) => (req, res) => {
  if (req.url !== "/job/JClaw-candidate/api/json?tree=allBuilds[displayName]" || req.headers.authorization !== AUTH) {
    res.writeHead(404);
    res.end();
    return;
  }
  res.writeHead(200, { "Content-Type": "application/json" });
  res.end(JSON.stringify({ allBuilds: names.map((displayName) => ({ displayName })) }));
};

test("the CLI prints the number from the candidate job's builds and origin's branches together", async () => {
  await withOrigin(["candidate/v1.0.22-rc.3", "candidate/v1.0.21-rc.9"], builds("v1.0.22-rc.1", "v1.0.22-rc.2", "#9"),
    async ({ config, work }) => {
      deepStrictEqual(await cli(["1.0.22"], config, work), { code: 0, stdout: "4\n", stderr: "" });
      deepStrictEqual(await cli(["1.0.23"], config, work), { code: 0, stdout: "1\n", stderr: "" });
    });
});

test("the CLI exits 2, never 0, when Jenkins refuses or is not there, or origin cannot be listed", async () => {
  await withOrigin([], (req, res) => { res.writeHead(401); res.end(); }, async ({ config, work }) => {
    deepStrictEqual(await cli(["1.0.22"], config, work), { code: 2, stdout: "", stderr: "Jenkins answered 401\n" });
  });
  await withOrigin([], builds(), async ({ dir, work }) => {
    const server = createServer();
    await new Promise((ready) => server.listen(0, "127.0.0.1", ready));
    const gone = server.address().port;
    await new Promise((closed) => server.close(closed));
    const config = join(dir, "gone.json");
    writeFileSync(config, JSON.stringify({ mcpServers: { jenkins: { args: [`http://127.0.0.1:${gone}/mcp-server/mcp`,
      "--header", `Authorization:${AUTH}`] } } }));
    const result = await cli(["1.0.22"], config, work);
    strictEqual(result.code, 2);
    strictEqual(result.stderr.startsWith("Jenkins could not be asked: "), true, result.stderr);
  });
  await withOrigin([], builds("v1.0.22-rc.1"), async ({ config, work }) => {
    git(work, "remote", "set-url", "origin", join(work, "no-such-remote.git"));
    const result = await cli(["1.0.22"], config, work);
    strictEqual(result.code, 2);
    strictEqual(result.stderr.startsWith("origin's candidate branches could not be listed: "), true, result.stderr);
  });
});

test("the CLI exits 2 without asking anything for a malformed version or a config it cannot read", async () => {
  let asked = 0;
  await withOrigin([], (req, res) => { asked++; builds()(req, res); }, async ({ config, dir, work }) => {
    for (const version of [[], ["v1.0.22"], ["1.0"], ["1.0.22.1"]]) {
      deepStrictEqual(await cli(version, config, work),
        { code: 2, stdout: "", stderr: "give the release's version, such as 1.0.22\n" }, version.join(" "));
    }
    const missing = join(dir, "missing.json");
    deepStrictEqual(await cli(["1.0.22"], missing, work),
      { code: 2, stdout: "", stderr: `no jenkins MCP server with an Authorization header in ${missing}\n` });
  });
  strictEqual(asked, 0);
});
