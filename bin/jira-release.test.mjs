// node --test bin/jira-release.test.mjs
// The CLI runs against a local HTTP stub of Jira and a scratch git repository, so no test reaches the real one.

import { deepStrictEqual, strictEqual } from "node:assert";
import { spawn, spawnSync } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import { jiraFrom, ticketKeys } from "./jira-release.mjs";

const SCRIPT = join(resolve(dirname(fileURLToPath(import.meta.url))), "jira-release.mjs");
const TOKEN = "stand-in-token-123";
const USAGE = "give tickets <from> <to>, record <vX.Y.Z> <YYYY-MM-DD> <description> [<key>…], or done <vX.Y.Z> <key>…\n";

test("the keys are the JF tickets the subjects name, each once, in number order", () => {
  deepStrictEqual(ticketKeys([
    "fix(release): candidates build in a job of their own (JF-453)",
    "feat(release): a release candidate rehearses an upgrade (JF-450, JF-9)",
    "Release v1.0.21",
    "test(browser): a follow-up (JF-450)",
    "chore: JCLAW-12, JF-12a and XJF-3 are not JF keys",
    "",
  ]), ["JF-9", "JF-450", "JF-453"]);
  deepStrictEqual(ticketKeys([]), []);
});

test("Jira's address and token come from the jira-confluence MCP server's env, or nothing does", () => {
  const config = (env) => ({ mcpServers: { "jira-confluence": { command: "docker", env } } });
  deepStrictEqual(jiraFrom(config({ JIRA_URL: "https://jira.example.test/", JIRA_PERSONAL_TOKEN: TOKEN })),
    { base: "https://jira.example.test", token: TOKEN });
  strictEqual(jiraFrom(config({ JIRA_URL: "https://jira.example.test" })), null);
  strictEqual(jiraFrom(config({ JIRA_URL: "jira.example.test", JIRA_PERSONAL_TOKEN: TOKEN })), null);
  strictEqual(jiraFrom(config({ JIRA_URL: "https://jira.example.test", JIRA_PERSONAL_TOKEN: "" })), null);
  strictEqual(jiraFrom({ mcpServers: {} }), null);
  strictEqual(jiraFrom(undefined), null);
});

const withJira = async (handle, body) => {
  const seen = [];
  const server = createServer((req, res) => {
    let data = "";
    req.on("data", (d) => { data += d; });
    req.on("end", () => {
      seen.push({ method: req.method, url: req.url, auth: req.headers.authorization, body: data ? JSON.parse(data) : undefined });
      const [status, json] = handle(req.method, req.url);
      res.writeHead(status, { "Content-Type": "application/json" });
      res.end(json === undefined ? "" : JSON.stringify(json));
    });
  });
  await new Promise((ready) => server.listen(0, "127.0.0.1", ready));
  const dir = mkdtempSync(join(tmpdir(), "jira-release-"));
  try {
    const config = join(dir, "claude.json");
    writeFileSync(config, JSON.stringify({ mcpServers: { "jira-confluence": { env: {
      JIRA_URL: `http://127.0.0.1:${server.address().port}`, JIRA_PERSONAL_TOKEN: TOKEN } } } }));
    await body({ config, dir, seen });
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
  child.on("close", (code) => {
    strictEqual(`${stdout}${stderr}`.includes(TOKEN), false, "the token is never printed");
    settle({ code, stdout, stderr });
  });
});

const RECORD = ["record", "v1.0.21", "2026-11-17", "Release v1.0.21: tag v1.0.21 on commit 0123456789a"];

test("record creates the release's version when Jira has none, then adds it to each ticket's Fix Versions", async () => {
  const jira = (method, url) => {
    if (method === "GET") return [200, [{ name: "v1.0" }, { name: "v1.0.20" }]];
    if (url === "/rest/api/2/version") return [201, { id: "1" }];
    return [204];
  };
  await withJira(jira, async ({ config, seen }) => {
    deepStrictEqual(await cli([...RECORD, "JF-453", "JF-462"], config), { code: 0, stderr: "",
      stdout: "Created version v1.0.21, released 2026-11-17.\nJF-453: Fix Version v1.0.21.\nJF-462: Fix Version v1.0.21.\n" });
    deepStrictEqual(seen, [
      { method: "GET", url: "/rest/api/2/project/JF/versions", auth: `Bearer ${TOKEN}`, body: undefined },
      { method: "POST", url: "/rest/api/2/version", auth: `Bearer ${TOKEN}`, body: { name: "v1.0.21", project: "JF",
        released: true, releaseDate: "2026-11-17", description: RECORD[3] } },
      { method: "PUT", url: "/rest/api/2/issue/JF-453", auth: `Bearer ${TOKEN}`,
        body: { update: { fixVersions: [{ add: { name: "v1.0.21" } }] } } },
      { method: "PUT", url: "/rest/api/2/issue/JF-462", auth: `Bearer ${TOKEN}`,
        body: { update: { fixVersions: [{ add: { name: "v1.0.21" } }] } } },
    ]);
  });
});

test("record leaves a version that exists as it is, and a release with no tickets still records its version", async () => {
  await withJira((method) => (method === "GET" ? [200, [{ name: "v1.0.21" }]] : [204]), async ({ config, seen }) => {
    deepStrictEqual(await cli([...RECORD, "JF-453"], config),
      { code: 0, stderr: "", stdout: "Version v1.0.21 exists.\nJF-453: Fix Version v1.0.21.\n" });
    strictEqual(seen.some((s) => s.method === "POST"), false);
  });
  await withJira((method) => (method === "GET" ? [200, []] : [201, {}]), async ({ config, seen }) => {
    deepStrictEqual(await cli(RECORD, config), { code: 0, stderr: "", stdout: "Created version v1.0.21, released 2026-11-17.\n" });
    strictEqual(seen.length, 2);
  });
});

test("record stops with exit 2 at the first refusal, after naming what it did", async () => {
  await withJira((method) => (method === "GET" ? [200, []] : [400, {}]), async ({ config, seen }) => {
    deepStrictEqual(await cli([...RECORD, "JF-453"], config), { code: 2, stdout: "", stderr: "Jira answered 400 to creating v1.0.21\n" });
    strictEqual(seen.some((s) => s.method === "PUT"), false);
  });
  await withJira((method, url) => (method === "GET" ? [200, [{ name: "v1.0.21" }]] : [url.endsWith("JF-462") ? 403 : 204]),
    async ({ config }) => {
      deepStrictEqual(await cli([...RECORD, "JF-453", "JF-462", "JF-463"], config), { code: 2, stdout: "",
        stderr: "Version v1.0.21 exists.\nJF-453: Fix Version v1.0.21.\nJira answered 403 to adding v1.0.21 to JF-462\n" });
    });
  await withJira(() => [503], async ({ config }) => {
    deepStrictEqual(await cli([...RECORD, "JF-453"], config), { code: 2, stdout: "", stderr: "Jira answered 503 for JF's versions\n" });
  });
});

const fixed = { fixVersions: [{ name: "v1.0.21" }] };
const fieldsOf = {
  "JF-450": { status: { name: "Done" }, ...fixed },
  "JF-453": { status: { name: "Review" }, ...fixed },
  "JF-457": { status: { name: "To Do" } },
  "JF-462": { status: { name: "Review" }, fixVersions: [{ name: "v1.0.20" }] },
};
const transitions = { transitions: [{ id: "21", to: { name: "In Progress" } }, { id: "31", to: { name: "Done" } }] };
const STATUS = "?fields=status,fixVersions";

test("done moves each ticket in Review that carries the release to Done, by the transition that leads there", async () => {
  const jira = (method, url) => {
    const key = url.match(/JF-\d+/)[0];
    if (method === "GET" && url.endsWith(STATUS)) return [200, { fields: fieldsOf[key] }];
    if (method === "GET") return [200, transitions];
    return [204];
  };
  await withJira(jira, async ({ config, seen }) => {
    deepStrictEqual(await cli(["done", "v1.0.21", "JF-450", "JF-453", "JF-457", "JF-462"], config), { code: 0, stderr: "",
      stdout: "JF-450: left as Done.\nJF-453: Review to Done.\nJF-457: left as To Do.\n"
        + "JF-462: left in Review, since it carries no Fix Version v1.0.21.\n" });
    deepStrictEqual(seen.filter((s) => s.method === "POST"),
      [{ method: "POST", url: "/rest/api/2/issue/JF-453/transitions", auth: `Bearer ${TOKEN}`, body: { transition: { id: "31" } } }]);
    strictEqual(seen.find((s) => s.url.startsWith("/rest/api/2/issue/JF-450")).url, `/rest/api/2/issue/JF-450${STATUS}`);
  });
});

test("done stops with exit 2 when Jira offers no way to Done, refuses the move, or cannot read the ticket", async () => {
  const review = [200, { fields: fieldsOf["JF-453"] }];
  await withJira((method, url) => (url.endsWith(STATUS) ? review : [200, { transitions: [transitions.transitions[0]] }]),
    async ({ config, seen }) => {
      deepStrictEqual(await cli(["done", "v1.0.21", "JF-453"], config), { code: 2, stdout: "",
        stderr: "Jira offers JF-453 no transition to Done (answered 200)\n" });
      strictEqual(seen.some((s) => s.method === "POST"), false);
    });
  await withJira((method, url) => (method === "POST" ? [400] : url.endsWith(STATUS) ? review : [200, transitions]),
    async ({ config }) => {
      deepStrictEqual(await cli(["done", "v1.0.21", "JF-453"], config), { code: 2, stdout: "",
        stderr: "Jira answered 400 to moving JF-453 to Done\n" });
    });
  await withJira(() => [404], async ({ config }) => {
    deepStrictEqual(await cli(["done", "v1.0.21", "JF-453"], config), { code: 2, stdout: "", stderr: "Jira answered 404 for JF-453\n" });
  });
});

test("a malformed argument or a config without the server answers 2 without asking Jira", async () => {
  await withJira(() => [200, []], async ({ config, dir, seen }) => {
    for (const args of [[], ["done"], ["done", "JF-1"], ["done", "v1.0.21"], ["done", "v1.0.21", "JF-1x"],
      ["done", "v1.0.21", "JCLAW-12"], ["record", "1.0.21", "2026-11-17", "d"],
      ["record", "v1.0.21", "17-11-2026", "d"], ["record", "v1.0.21", "2026-11-17", " "], ["record", "v1.0.21", "2026-11-17"],
      [...RECORD, "jf-1"], ["tickets", "v1.0.20"], ["undo", "JF-1"]]) {
      deepStrictEqual(await cli(args, config), { code: 2, stdout: "", stderr: USAGE }, args.join(" "));
    }
    strictEqual(seen.length, 0);
    const empty = join(dir, "empty.json");
    writeFileSync(empty, "{}");
    deepStrictEqual(await cli(["done", "v1.0.21", "JF-1"], empty), { code: 2, stdout: "",
      stderr: `no jira-confluence MCP server with JIRA_URL and JIRA_PERSONAL_TOKEN in ${empty}\n` });
  });
});

test("tickets lists the keys in the subjects of the range's commits, and a range git cannot read answers 2", async () => {
  const repo = mkdtempSync(join(tmpdir(), "jira-release-repo-"));
  const git = (...args) => spawnSync("git", args, { cwd: repo, encoding: "utf8",
    env: { ...process.env, GIT_AUTHOR_NAME: "t", GIT_AUTHOR_EMAIL: "t@example.test", GIT_COMMITTER_NAME: "t",
      GIT_COMMITTER_EMAIL: "t@example.test" } });
  try {
    git("init", "-q");
    git("commit", "-q", "--allow-empty", "--no-gpg-sign", "-m", "feat: the old release's work (JF-440)");
    git("-c", "tag.gpgSign=false", "tag", "v1.0.20");
    git("commit", "-q", "--allow-empty", "--no-gpg-sign", "-m", "fix: a fix (JF-453)", "-m", "The body names JF-17, which is not shipped here.");
    git("commit", "-q", "--allow-empty", "--no-gpg-sign", "-m", "feat: a story (JF-462, JF-453)");
    git("commit", "-q", "--allow-empty", "--no-gpg-sign", "-m", "Release v1.0.21");
    deepStrictEqual(await cli(["tickets", "v1.0.20", "HEAD"], "/nonexistent", repo), { code: 0, stdout: "JF-453 JF-462\n", stderr: "" });
    const bad = await cli(["tickets", "v9.9.9", "HEAD"], "/nonexistent", repo);
    strictEqual(bad.code, 2);
    strictEqual(bad.stderr.startsWith("git log v9.9.9..HEAD failed: "), true, bad.stderr);
  } finally {
    rmSync(repo, { recursive: true, force: true });
  }
});
