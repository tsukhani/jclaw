// node --test bin/candidate-vouch.test.mjs
// The CLI runs against a local HTTP stub of Jenkins, so no test reaches the real one or reads ~/.claude.json.

import { deepStrictEqual, strictEqual } from "node:assert";
import { spawn, spawnSync } from "node:child_process";
import { chmodSync, copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import { jenkinsFrom, vouchingBuild } from "./candidate-vouch.mjs";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const SCRIPT = join(ROOT, "bin", "candidate-vouch.mjs");
const SHA = "a".repeat(40);
const AUTH = "Basic dXNlcjp0b2tlbg==";
const mcp = (args) => ({ mcpServers: { jenkins: { command: "npx", args } } });
const ARGS = (url) => ["-y", "mcp-remote", url, "--header", `Authorization:${AUTH}`];
const candidate = (over = {}) => ({ number: 1418, result: "SUCCESS", building: false,
  displayName: `#1418 candidate ${SHA.slice(0, 12)}`, description: `tested ${SHA}`, ...over });

test("Jenkins' address and header come from the jenkins MCP server's arguments", () => {
  deepStrictEqual(jenkinsFrom(mcp(ARGS("https://jenkins.example.com/mcp-server/mcp"))),
    { base: "https://jenkins.example.com", authorization: AUTH });
  deepStrictEqual(jenkinsFrom(mcp(["mcp-remote", "https://ci.example.com/mcp-server/mcp/", "--header", "authorization: Bearer t"])),
    { base: "https://ci.example.com", authorization: "Bearer t" });
});

test("a config without the server, its URL or an Authorization header gives no Jenkins", () => {
  strictEqual(jenkinsFrom({}), null);
  strictEqual(jenkinsFrom(null), null);
  strictEqual(jenkinsFrom(mcp(["mcp-remote", "https://jenkins.example.com/mcp-server/mcp"])), null);
  strictEqual(jenkinsFrom(mcp(["mcp-remote", "--header", `Authorization:${AUTH}`])), null);
  strictEqual(jenkinsFrom(mcp(["mcp-remote", "https://j.example.com/mcp-server/mcp", "--header", "X-Other:1"])), null);
  strictEqual(jenkinsFrom(mcp(["mcp-remote", "https://j.example.com/mcp-server/mcp", "--header"])), null);
});

test("only a finished, green candidate build of the commit vouches for it", () => {
  deepStrictEqual(vouchingBuild([candidate({ number: 1419, description: "tested " + "b".repeat(40) }), candidate()], SHA),
    candidate());
  for (const over of [{ result: "FAILURE" }, { result: null, building: true }, { building: true },
    { description: `tested ${"b".repeat(40)}` }, { description: `tested ${SHA.slice(0, 12)}` }, { description: null }]) {
    strictEqual(vouchingBuild([candidate(over)], SHA), null, JSON.stringify(over));
  }
  strictEqual(vouchingBuild([], SHA), null);
  strictEqual(vouchingBuild(undefined, SHA), null);
});

test("a candidate job's build vouches whatever its name, and a legacy JClaw build only when named for the commit", () => {
  for (const displayName of ["v1.0.22-rc.2", "#7", undefined]) {
    deepStrictEqual(vouchingBuild([candidate({ displayName })], SHA), candidate({ displayName }), String(displayName));
  }
  const legacy = candidate({ job: "JClaw" });
  deepStrictEqual(vouchingBuild([legacy], SHA), legacy);
  for (const displayName of ["#1427", "#1418 candidate bbbbbbbbbbbb", "v1.0.20-rc.1"]) {
    strictEqual(vouchingBuild([candidate({ job: "JClaw", displayName })], SHA), null, displayName);
  }
});

const withJenkins = async (respond, body) => {
  const seen = [];
  const server = createServer((req, res) => {
    seen.push({ url: req.url, authorization: req.headers.authorization });
    respond(req, res);
  });
  await new Promise((ready) => server.listen(0, "127.0.0.1", ready));
  const dir = mkdtempSync(join(tmpdir(), "candidate-vouch-"));
  try {
    const config = join(dir, "claude.json");
    writeFileSync(config, JSON.stringify(mcp(ARGS(`http://127.0.0.1:${server.address().port}/mcp-server/mcp`))));
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

const json = (payload) => (req, res) => {
  res.writeHead(200, { "Content-Type": "application/json" });
  res.end(JSON.stringify(payload));
};

test("the CLI prints the vouching build and exits 0, asking for the job's last 30 builds with the header", async () => {
  await withJenkins(json({ builds: [candidate()] }), async ({ config, seen }) => {
    deepStrictEqual(await cli([SHA], config), { code: 0, stdout: "#1418\n", stderr: "" });
    deepStrictEqual(seen, [{ url: "/job/JClaw-candidate/api/json?tree=builds[number,result,building,displayName,description]{0,30}",
      authorization: AUTH }]);
  });
});

test("the CLI exits 1 when no green candidate build tested the commit", async () => {
  await withJenkins(json({ builds: [candidate({ result: "FAILURE" })] }), async ({ config }) => {
    deepStrictEqual(await cli([SHA], config), { code: 1, stdout: "", stderr: "no green candidate build tested it\n" });
  });
});

test("the CLI exits 2, never 0, when Jenkins refuses, answers what is no JSON, or is not there", async () => {
  await withJenkins((req, res) => { res.writeHead(401); res.end(); }, async ({ config }) => {
    deepStrictEqual(await cli([SHA], config), { code: 2, stdout: "", stderr: "Jenkins answered 401\n" });
  });
  await withJenkins((req, res) => { res.writeHead(200); res.end("<html>"); }, async ({ config }) => {
    const result = await cli([SHA], config);
    strictEqual(result.code, 2);
    strictEqual(result.stderr.startsWith("Jenkins could not be asked: "), true, result.stderr);
  });
  let closedPort;
  await withJenkins(json({}), async ({ dir }) => {
    const server = createServer();
    await new Promise((ready) => server.listen(0, "127.0.0.1", ready));
    closedPort = server.address().port;
    await new Promise((closed) => server.close(closed));
    const config = join(dir, "gone.json");
    writeFileSync(config, JSON.stringify(mcp(ARGS(`http://127.0.0.1:${closedPort}/mcp-server/mcp`))));
    const result = await cli([SHA], config);
    strictEqual(result.code, 2);
    strictEqual(result.stderr.startsWith("Jenkins could not be asked: "), true, result.stderr);
  });
});

test("the CLI exits 2 without asking Jenkins for a sha that is not a full one or a config it cannot read", async () => {
  await withJenkins(json({ builds: [candidate()] }), async ({ config, dir, seen }) => {
    deepStrictEqual(await cli([SHA.slice(0, 12)], config), { code: 2, stdout: "", stderr: "give the commit's full sha\n" });
    deepStrictEqual(await cli([], config), { code: 2, stdout: "", stderr: "give the commit's full sha\n" });
    const missing = join(dir, "missing.json");
    deepStrictEqual(await cli([SHA], missing), { code: 2, stdout: "",
      stderr: `no jenkins MCP server with an Authorization header in ${missing}\n` });
    deepStrictEqual(seen, []);
  });
});

// The real pre-push hook, policy guard and vouch script in a scratch repository, with ./jclaw.sh and ./gradlew stubbed
// to log how they were called: the suites they run are the boundary, and what the hook decides is under test.
const STUB = `#!/bin/sh
echo "$(basename "$0") $*" >> "$STUB_LOG"
[ "$1" = check ] && exit "\${STUB_CHECK_RC:-0}"
exit 0
`;

const inReleaseRepo = (respond, body) => withJenkins(respond, async ({ config, dir, seen }) => {
  const stubLog = join(dir, "stub.log");
  const env = { ...process.env, GIT_CONFIG_GLOBAL: "/dev/null", GIT_CONFIG_NOSYSTEM: "1", JCLAW_CLAUDE_JSON: config,
    STUB_LOG: stubLog };
  delete env.JCLAW_SKIP_TESTS;
  const git = (cwd, ...args) => spawnSync("git", args, { cwd, env, encoding: "utf8" });
  const work = join(dir, "work");
  git(dir, "init", "-q", "--bare", "-b", "main", "origin.git");
  git(dir, "init", "-q", "-b", "main", work);
  for (const [k, v] of [["user.name", "t"], ["user.email", "t@example.invalid"], ["commit.gpgsign", "false"],
    ["core.hooksPath", ".githooks"]]) git(work, "config", k, v);
  for (const d of [".githooks", "bin", "conf", "frontend"]) mkdirSync(join(work, d));
  copyFileSync(join(ROOT, ".githooks", "pre-push"), join(work, ".githooks", "pre-push"));
  for (const f of ["policy-guard.mjs", "candidate-vouch.mjs"]) copyFileSync(join(ROOT, "bin", f), join(work, "bin", f));
  writeFileSync(join(work, "bin", "ok.test.mjs"), 'import { test } from "node:test";\ntest("ok", () => {});\n');
  for (const f of ["jclaw.sh", "gradlew"]) {
    writeFileSync(join(work, f), STUB);
    chmodSync(join(work, f), 0o755);
  }
  chmodSync(join(work, ".githooks", "pre-push"), 0o755);
  writeFileSync(join(work, "conf", "application.conf"), "application.name=t\napplication.version=1.0.0\n");
  git(work, "add", "-A");
  git(work, "commit", "-q", "-m", "Release v1.0.0");
  git(work, "remote", "add", "origin", join(dir, "origin.git"));
  spawnSync("git", ["push", "-q", "origin", "HEAD"], { cwd: work, env: { ...env, JCLAW_SKIP_TESTS: "1" } });
  const commit = (message, files) => {
    for (const [path, text] of Object.entries(files)) writeFileSync(join(work, path), text);
    git(work, "add", "-A");
    git(work, "commit", "-q", "-m", message);
    return git(work, "rev-parse", "HEAD").stdout.trim();
  };
  const push = (extra = {}) => new Promise((settle) => {
    const child = spawn("git", ["push", "origin", "HEAD"], { cwd: work, env: { ...env, ...extra } });
    let out = "";
    child.stdout.on("data", (d) => { out += d; });
    child.stderr.on("data", (d) => { out += d; });
    child.on("close", (code) => settle({ code, lines: out.split("\n").filter((l) => l.startsWith("[jclaw pre-push]")) }));
  });
  const stubbed = () => (existsSync(stubLog) ? readFileSync(stubLog, "utf8").trim().split("\n") : []);
  const onOrigin = () => git(join(dir, "origin.git"), "rev-parse", "main").stdout.trim();
  await body({ commit, push, stubbed, onOrigin, seen });
});

const BUMP = { "conf/application.conf": "application.name=t\napplication.version=1.0.1\n" };
const testedAs = (sha, extra = {}) => candidate({ displayName: `#1418 candidate ${sha.slice(0, 12)}`, description: `tested ${sha}`,
  ...extra });
const EVERY_TEST = ["jclaw.sh check --backend-only", "gradlew --quiet compileTestJava", "jclaw.sh test"];

test("the release commit a green candidate tested runs only the fast checks and the bin/ tests, and says why", async () => {
  let builds = [];
  await inReleaseRepo((req, res) => json({ builds })(req, res), async ({ commit, push, stubbed, onOrigin, seen }) => {
    commit("feat: a page and a script", { "frontend/a.ts": "a\n", "bin/b.mjs": "b\n" });
    const release = commit("Release v1.0.1", BUMP);
    builds = [testedAs(release, { number: 1418 })];
    const pushed = await push();
    strictEqual(pushed.code, 0, pushed.lines.join("\n"));
    deepStrictEqual(pushed.lines, [
      `[jclaw pre-push] Candidate build #1418 passed ${release} whole: skipping the backend and frontend tests.`,
      "[jclaw pre-push] Running the fast checks...",
      "[jclaw pre-push] Running the bin/ script tests...",
      "[jclaw pre-push] Checks passed — proceeding with push.",
    ]);
    deepStrictEqual(stubbed(), ["jclaw.sh check"]);
    strictEqual(onOrigin(), release);
    strictEqual(seen.length, 1);
  });
});

test("over a candidate whose range touched neither the frontend nor bin/, only the backend fast checks run", async () => {
  let builds = [];
  await inReleaseRepo((req, res) => json({ builds })(req, res), async ({ commit, push, stubbed }) => {
    commit("feat: a backend change", { "conf/routes": "GET / x\n" });
    const release = commit("Release v1.0.1", BUMP);
    builds = [testedAs(release)];
    const pushed = await push();
    strictEqual(pushed.code, 0, pushed.lines.join("\n"));
    strictEqual(pushed.lines.includes("[jclaw pre-push] Running the bin/ script tests..."), false);
    deepStrictEqual(stubbed(), ["jclaw.sh check --backend-only"]);
  });
});

test("with no green candidate for the commit, or Jenkins down, the push runs every test as before", async () => {
  let builds = [];
  await inReleaseRepo((req, res) => json({ builds })(req, res), async ({ commit, push, stubbed }) => {
    commit("feat: x", { "frontend/a.ts": "a\n" });
    const release = commit("Release v1.0.1", BUMP);
    builds = [testedAs(release, { result: "FAILURE" })];
    const pushed = await push();
    strictEqual(pushed.code, 0, pushed.lines.join("\n"));
    strictEqual(pushed.lines[0],
      `[jclaw pre-push] No green candidate vouches for ${release} (no green candidate build tested it): running the tests.`);
    deepStrictEqual(stubbed(), EVERY_TEST);
  });
  await inReleaseRepo((req, res) => { res.writeHead(503); res.end(); }, async ({ commit, push, stubbed }) => {
    commit("feat: x", { "frontend/a.ts": "a\n" });
    const release = commit("Release v1.0.1", BUMP);
    const pushed = await push();
    strictEqual(pushed.lines[0], `[jclaw pre-push] No green candidate vouches for ${release} (Jenkins answered 503): running the tests.`);
    deepStrictEqual(stubbed(), EVERY_TEST);
  });
});

test("a green candidate of the release commit's parent vouches for nothing, so the push runs every test", async () => {
  let builds = [];
  await inReleaseRepo((req, res) => json({ builds })(req, res), async ({ commit, push, stubbed, seen }) => {
    const feature = commit("feat: x", { "frontend/a.ts": "a\n" });
    const release = commit("Release v1.0.1", BUMP);
    builds = [testedAs(feature)];
    const pushed = await push();
    strictEqual(pushed.code, 0, pushed.lines.join("\n"));
    strictEqual(pushed.lines[0],
      `[jclaw pre-push] No green candidate vouches for ${release} (no green candidate build tested it): running the tests.`);
    deepStrictEqual(stubbed(), EVERY_TEST);
    strictEqual(seen.length, 1);
  });
});

test("over a green candidate, failed fast checks still refuse the push", async () => {
  let builds = [];
  await inReleaseRepo((req, res) => json({ builds })(req, res), async ({ commit, push, onOrigin }) => {
    const before = onOrigin();
    commit("feat: x", { "frontend/a.ts": "a\n" });
    const release = commit("Release v1.0.1", BUMP);
    builds = [testedAs(release)];
    const pushed = await push({ STUB_CHECK_RC: "1" });
    strictEqual(pushed.code, 1);
    strictEqual(pushed.lines.at(-1), "[jclaw pre-push] The fast checks failed: see the output above.");
    strictEqual(onOrigin(), before);
  });
});
