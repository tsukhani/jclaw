// node --test bin/replay-candidate.test.mjs
// The CLI runs against a local HTTP stub of Jenkins and a scratch git repository, so no test reaches the real one.

import { deepStrictEqual, strictEqual, throws } from "node:assert";
import { spawn, spawnSync } from "node:child_process";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import { candidateScript, replayBase } from "./replay-candidate.mjs";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const SCRIPT = join(ROOT, "bin", "replay-candidate.mjs");
const SHA = "0123456789abcdef0123456789abcdef01234567";
const VERSION = "7.8.9";
const AUTH = "Basic dXNlcjp0b2tlbg==";

const FIXTURE = `pipeline {
    stages {
        stage('Setup') {
            steps {
                script {
                    if (params.CANDIDATE) echo "candidate \${params.CANDIDATE.substring(0, 12)}"
                    env.MODE = params.CANDIDATE ? 'candidate' : 'push'
                }
            }
        }
    }
}
`;

test("each read of the candidate parameter becomes the candidate's sha, and nothing else changes", () => {
  strictEqual(candidateScript(FIXTURE, SHA), `pipeline {
    stages {
        stage('Setup') {
            steps {
                script {
                    if ('${SHA}') echo "candidate \${'${SHA}'.substring(0, 12)}"
                    env.MODE = '${SHA}' ? 'candidate' : 'push'
                }
            }
        }
    }
}
`);
});

test("the repo's own Jenkinsfile becomes a script that reads no candidate parameter and differs only in those reads", () => {
  const jenkinsfile = readFileSync(join(ROOT, "Jenkinsfile"), "utf8");
  const script = candidateScript(jenkinsfile, SHA);
  strictEqual(/params\.CANDIDATE/.test(script), false);
  strictEqual(script.includes(`'${SHA}'`), true);
  strictEqual(script.replaceAll(`'${SHA}'`, "params.CANDIDATE"), jenkinsfile);
  strictEqual(script.includes("string(name: 'CANDIDATE', defaultValue: '',"), true, "the parameter definitions stay");
});

test("a Jenkinsfile with no candidate mode, or with a candidate read this cannot fill, is refused", () => {
  const none = { message: "the Jenkinsfile reads no candidate parameter, so it has no candidate mode to replay" };
  throws(() => candidateScript("pipeline { }", SHA), none);
  throws(() => candidateScript("echo params.CANDIDATES", SHA), none);
  const unfilled = { message: "the Jenkinsfile reads a candidate parameter this cannot replace" };
  throws(() => candidateScript("params.CANDIDATE; params.CANDIDATE_VERSION", SHA), unfilled);
  throws(() => candidateScript("params.CANDIDATE; params.CANDIDATES", SHA), unfilled);
});

const build = (number, params = {}, building = false) => ({ number, building,
  actions: [{}, { parameters: Object.entries(params).map(([name, value]) => ({ name, value })) }] });

test("the replay takes the newest finished build that neither releases nor publishes the dev container", () => {
  deepStrictEqual(replayBase([build(1425, {}, true), build(1424, { RELEASE: true }),
    build(1423, { PUBLISH_DEVCONTAINER: true }), build(1422, { RELEASE: false, PUBLISH_DEVCONTAINER: false }),
    build(1421)]).number, 1422);
  strictEqual(replayBase([{ number: 1415, building: false, actions: [] }]).number, 1415);
  strictEqual(replayBase([build(1, { RELEASE: true }), build(2, {}, true)]), null);
  strictEqual(replayBase([]), null);
  strictEqual(replayBase(undefined), null);
});

const withJenkins = async (handle, body) => {
  const seen = [];
  const server = createServer((req, res) => {
    let data = "";
    req.on("data", (d) => { data += d; });
    req.on("end", () => {
      seen.push({ method: req.method, url: req.url, headers: req.headers, body: data });
      handle(req, res);
    });
  });
  await new Promise((ready) => server.listen(0, "127.0.0.1", ready));
  const dir = mkdtempSync(join(tmpdir(), "replay-candidate-"));
  try {
    const config = join(dir, "claude.json");
    writeFileSync(config, JSON.stringify({ mcpServers: { jenkins: { args: ["-y", "mcp-remote",
      `http://127.0.0.1:${server.address().port}/mcp-server/mcp`, "--header", `Authorization:${AUTH}`] } } }));
    const repo = join(dir, "repo");
    const env = { ...process.env, GIT_CONFIG_GLOBAL: "/dev/null", GIT_CONFIG_NOSYSTEM: "1" };
    const git = (...args) => spawnSync("git", args, { cwd: repo, env, encoding: "utf8" });
    spawnSync("git", ["init", "-q", "-b", "main", repo], { env });
    git("config", "user.name", "t");
    git("config", "user.email", "t@example.invalid");
    git("config", "commit.gpgsign", "false");
    writeFileSync(join(repo, "Jenkinsfile"), FIXTURE);
    mkdirSync(join(repo, "conf"));
    writeFileSync(join(repo, "conf", "application.conf"), `application.name=t\napplication.version=${VERSION}\n`);
    git("add", "-A");
    git("commit", "-q", "-m", `Release v${VERSION}`);
    const sha = git("rev-parse", "HEAD").stdout.trim();
    await body({ config, repo, sha, seen });
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

const jenkins = ({ builds = [build(1422)], replay = 302 } = {}) => (req, res) => {
  if (req.url.startsWith("/job/JClaw/api/json")) {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ builds }));
  } else if (req.url === "/crumbIssuer/api/json") {
    res.writeHead(200, { "Content-Type": "application/json", "Set-Cookie": "JSESSIONID.x=abc; Path=/; HttpOnly" });
    res.end(JSON.stringify({ crumbRequestField: "Jenkins-Crumb", crumb: "c-123" }));
  } else {
    res.writeHead(replay, { Location: "/job/JClaw/" });
    res.end();
  }
};

test("the CLI replays the chosen build with the candidate's script, its crumb and session, and exits 0", async () => {
  await withJenkins(jenkins({ builds: [build(1424, { RELEASE: true }), build(1422)] }), async ({ config, repo, sha, seen }) => {
    deepStrictEqual(await cli([sha], config, repo),
      { code: 0, stdout: `replayed #1422 as candidate ${sha.slice(0, 12)} for v${VERSION}\n`, stderr: "" });
    deepStrictEqual(seen.map((s) => `${s.method} ${s.url}`), [
      "GET /job/JClaw/api/json?tree=builds[number,building,actions[parameters[name,value]]]{0,30}",
      "GET /crumbIssuer/api/json",
      "POST /job/JClaw/1422/replay/run",
    ]);
    const post = seen[2];
    strictEqual(post.headers.authorization, AUTH);
    strictEqual(post.headers["jenkins-crumb"], "c-123");
    strictEqual(post.headers.cookie, "JSESSIONID.x=abc");
    deepStrictEqual(JSON.parse(new URLSearchParams(post.body).get("json")),
      { mainScript: candidateScript(FIXTURE, sha) });
  });
});

test("the CLI exits 2 without replaying when no build is fit to replay or Jenkins refuses the replay", async () => {
  await withJenkins(jenkins({ builds: [build(1424, { RELEASE: true })] }), async ({ config, repo, sha, seen }) => {
    deepStrictEqual(await cli([sha], config, repo),
      { code: 2, stdout: "", stderr: "no finished build without RELEASE or PUBLISH_DEVCONTAINER to replay\n" });
    strictEqual(seen.some((s) => s.method === "POST"), false);
  });
  await withJenkins(jenkins({ replay: 500 }), async ({ config, repo, sha }) => {
    deepStrictEqual(await cli([sha], config, repo),
      { code: 2, stdout: "", stderr: "Jenkins answered 500 to the replay of #1422\n" });
  });
});

test("the CLI exits 2 without asking Jenkins for a bad sha, a commit with no Jenkinsfile, or one with no version", async () => {
  await withJenkins(jenkins(), async ({ config, repo, seen }) => {
    const usage = "give the release commit's full sha\n";
    deepStrictEqual(await cli([SHA.slice(0, 12)], config, repo), { code: 2, stdout: "", stderr: usage });
    deepStrictEqual(await cli([], config, repo), { code: 2, stdout: "", stderr: usage });
    deepStrictEqual(await cli([SHA], config, repo), { code: 2, stdout: "", stderr: `no Jenkinsfile at ${SHA}\n` });
    const env = { ...process.env, GIT_CONFIG_GLOBAL: "/dev/null", GIT_CONFIG_NOSYSTEM: "1" };
    const git = (...args) => spawnSync("git", args, { cwd: repo, env, encoding: "utf8" });
    writeFileSync(join(repo, "conf", "application.conf"), "application.name=t\n");
    git("commit", "-q", "-am", "feat: no version");
    const unversioned = git("rev-parse", "HEAD").stdout.trim();
    deepStrictEqual(await cli([unversioned], config, repo),
      { code: 2, stdout: "", stderr: `no application.version in conf/application.conf at ${unversioned}\n` });
    deepStrictEqual(seen, []);
  });
});
