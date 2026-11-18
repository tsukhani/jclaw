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
const USAGE = "give tickets <from> <to>, record <vX.Y.Z> <YYYY-MM-DD> <description> [<key>…], done <vX.Y.Z> <key>…, "
  + "close <key> <reason>, or audit [<from>]\n";

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
      [...RECORD, "jf-1"], ["tickets", "v1.0.20"], ["undo", "JF-1"], ["close"], ["close", "JF-1"], ["close", "JF-1", " "],
      ["close", "JCLAW-12", "a duplicate"], ["close", "JF-1", "a", "duplicate"], ["audit", "v1.0.20", "HEAD"]]) {
      deepStrictEqual(await cli(args, config), { code: 2, stdout: "", stderr: USAGE }, args.join(" "));
    }
    strictEqual(seen.length, 0);
    const empty = join(dir, "empty.json");
    writeFileSync(empty, "{}");
    deepStrictEqual(await cli(["done", "v1.0.21", "JF-1"], empty), { code: 2, stdout: "",
      stderr: `no jira-confluence MCP server with JIRA_URL and JIRA_PERSONAL_TOKEN in ${empty}\n` });
  });
});

// A scratch repository holding one commit that names no ticket, so every range and branch walk has a start.
const inRepo = async (body) => {
  const repo = mkdtempSync(join(tmpdir(), "jira-release-repo-"));
  const git = (...args) => spawnSync("git", args, { cwd: repo, encoding: "utf8",
    env: { ...process.env, GIT_AUTHOR_NAME: "t", GIT_AUTHOR_EMAIL: "t@example.test", GIT_COMMITTER_NAME: "t",
      GIT_COMMITTER_EMAIL: "t@example.test" } });
  const commit = (...messages) => git("commit", "-q", "--allow-empty", "--no-gpg-sign", ...messages.flatMap((m) => ["-m", m]));
  const tag = (name) => git("-c", "tag.gpgSign=false", "tag", name);
  // A commit on no branch, under the ref given, so a test can place one where a branch walk does or does not look.
  const refTo = (ref, subject) => {
    const sha = git("commit-tree", "--no-gpg-sign", "-p", "HEAD", "-m", subject, "HEAD^{tree}").stdout.trim();
    git("update-ref", ref, sha);
  };
  try {
    git("init", "-q");
    commit("chore: start");
    await body({ repo, git, commit, tag, refTo });
  } finally {
    rmSync(repo, { recursive: true, force: true });
  }
};

test("tickets lists the keys in the subjects of the range's commits, and a range git cannot read answers 2", async () => {
  await inRepo(async ({ repo, commit, tag }) => {
    commit("feat: the old release's work (JF-440)");
    tag("v1.0.20");
    commit("fix: a fix (JF-453)", "The body names JF-17, which is not shipped here.");
    commit("feat: a story (JF-462, JF-453)");
    commit("Release v1.0.21");
    deepStrictEqual(await cli(["tickets", "v1.0.20", "HEAD"], "/nonexistent", repo), { code: 0, stdout: "JF-453 JF-462\n", stderr: "" });
    const bad = await cli(["tickets", "v9.9.9", "HEAD"], "/nonexistent", repo);
    strictEqual(bad.code, 2);
    strictEqual(bad.stderr.startsWith("git log v9.9.9..HEAD failed: "), true, bad.stderr);
  });
});

test("close refuses with exit 1, asking Jira nothing, when a commit on a branch or a remote-tracking branch names it", async () => {
  await withJira(() => [200, {}], async ({ config, seen }) => {
    await inRepo(async ({ repo, git, commit, refTo }) => {
      commit("feat: a story (JF-389)");
      commit("fix: its follow-up (JF-389)");
      git("checkout", "-q", "-b", "side");
      commit("fix: on a branch (JF-410)");
      git("checkout", "-q", "-");
      refTo("refs/remotes/origin/feature", "chore: from origin (JF-420)");
      for (const [key, subject] of [["JF-389", "fix: its follow-up (JF-389)"], ["JF-410", "fix: on a branch (JF-410)"],
        ["JF-420", "chore: from origin (JF-420)"]]) {
        deepStrictEqual(await cli(["close", key, "Folded into JF-1."], config, repo), { code: 1, stderr: "",
          stdout: `${key}: not closed, since "${subject}" names it: /deploy moves it to Done when a release ships it.\n` }, key);
      }
      strictEqual(seen.length, 0);
    });
  });
});

const toDo = [200, { fields: { status: { name: "To Do" } } }];

test("close posts the reason and then moves a ticket no branch names to Done, and leaves one already Done", async () => {
  const jira = (method, url) => {
    if (method === "GET" && url.endsWith("?fields=status")) {
      return url.includes("JF-432") ? [200, { fields: { status: { name: "Done" } } }] : toDo;
    }
    if (method === "GET") return [200, transitions];
    return url.endsWith("/comment") ? [201, { id: "1" }] : [204];
  };
  await withJira(jira, async ({ config, seen }) => {
    await inRepo(async ({ repo, commit, refTo }) => {
      commit("feat: a story (JF-389)");
      refTo("refs/stash", "WIP on main: a stash is no branch (JF-431)");
      deepStrictEqual(await cli(["close", "JF-431", "Folded into JF-410."], config, repo),
        { code: 0, stderr: "", stdout: "JF-431: To Do to Done, with the reason posted.\n" });
      deepStrictEqual(seen.filter((s) => s.method === "POST"), [
        { method: "POST", url: "/rest/api/2/issue/JF-431/comment", auth: `Bearer ${TOKEN}`,
          body: { body: "Closed without a release, since no commit names JF-431: Folded into JF-410." } },
        { method: "POST", url: "/rest/api/2/issue/JF-431/transitions", auth: `Bearer ${TOKEN}`, body: { transition: { id: "31" } } },
      ]);
      seen.length = 0;
      deepStrictEqual(await cli(["close", "JF-432", "A duplicate of JF-410."], config, repo),
        { code: 0, stderr: "", stdout: "JF-432: left as Done.\n" });
      deepStrictEqual(seen.map((s) => `${s.method} ${s.url}`), ["GET /rest/api/2/issue/JF-432?fields=status"]);
    });
  });
});

test("close stops with exit 2 when Jira refuses the reason or the move, or git cannot read the branches", async () => {
  await withJira((method) => (method === "GET" ? toDo : [403]), async ({ config, seen }) => {
    await inRepo(async ({ repo }) => {
      deepStrictEqual(await cli(["close", "JF-431", "A duplicate."], config, repo),
        { code: 2, stdout: "", stderr: "Jira answered 403 to posting the reason on JF-431\n" });
      strictEqual(seen.some((s) => s.url.includes("/transitions")), false);
    });
  });
  const jira = (method, url) => {
    if (url.endsWith("/comment")) return [201, {}];
    if (method === "POST") return [400];
    return url.endsWith("?fields=status") ? toDo : [200, transitions];
  };
  await withJira(jira, async ({ config }) => {
    await inRepo(async ({ repo }) => {
      deepStrictEqual(await cli(["close", "JF-431", "A duplicate."], config, repo),
        { code: 2, stdout: "", stderr: "Jira answered 400 to moving JF-431 to Done\n" });
    });
  });
  await withJira(() => [404], async ({ config }) => {
    await inRepo(async ({ repo }) => {
      deepStrictEqual(await cli(["close", "JF-431", "A duplicate."], config, repo),
        { code: 2, stdout: "", stderr: "Jira answered 404 for JF-431\n" });
    });
  });
  const notRepo = mkdtempSync(join(tmpdir(), "jira-release-plain-"));
  try {
    const out = await cli(["close", "JF-431", "A duplicate."], "/nonexistent", notRepo);
    strictEqual(out.code, 2);
    strictEqual(out.stderr.startsWith("git log --branches --remotes failed: "), true, out.stderr);
  } finally {
    rmSync(notRepo, { recursive: true, force: true });
  }
});

const REVIEW_SEARCH = `/rest/api/2/search?jql=${encodeURIComponent("project = JF AND status = Review ORDER BY key ASC")}`
  + "&fields=fixVersions&startAt=";
const auditJira = (fields, pages) => (method, url) => {
  if (url.startsWith("/rest/api/2/search")) return [200, pages[Number(url.match(/startAt=(\d+)/)[1])]];
  const key = url.match(/JF-\d+/)[0];
  return fields[key] ? [200, { fields: fields[key] }] : [404, { errorMessages: ["Issue Does Not Exist"] }];
};

test("audit lists each ticket named since the last version tag that is not in Review, and each in Review none names", async () => {
  const fields = {
    "JF-440": { status: { name: "Done" }, fixVersions: [{ name: "v1.0.20", released: true }] },
    "JF-450": { status: { name: "Review" }, fixVersions: [] },
    "JF-451": { status: { name: "In Progress" }, fixVersions: [] },
    "JF-452": { status: { name: "To Do" }, fixVersions: [] },
    "JF-453": { status: { name: "Done" }, fixVersions: [] },
    "JF-454": { status: { name: "Done" }, fixVersions: [{ name: "v1.3", released: false }] },
  };
  const review = { 0: { startAt: 0, total: 3, issues: [
    { key: "JF-450", fields: { fixVersions: [] } },
    { key: "JF-460", fields: { fixVersions: [{ name: "v1.0.19", released: true }, { name: "v1.0.20", released: true }] } },
    { key: "JF-461", fields: { fixVersions: [{ name: "v1.3", released: false }] } },
  ] } };
  await withJira(auditJira(fields, review), async ({ config, seen }) => {
    await inRepo(async ({ repo, commit, tag }) => {
      commit("feat: the old release's work (JF-440)");
      tag("v1.0.20");
      for (const subject of ["feat: a (JF-450)", "fix: b (JF-451)", "feat: c (JF-452, JF-453)", "chore: d (JF-454)",
        "fix: a follow-up to a released ticket (JF-440)", "feat: e (JF-455)"]) commit(subject);
      deepStrictEqual(await cli(["audit"], config, repo), { code: 1, stderr: "", stdout: [
        "JF-451: In Progress, though a commit since v1.0.20 names it.",
        "JF-452: To Do, though a commit since v1.0.20 names it.",
        "JF-453: Done, though no release carries the commit since v1.0.20 that names it.",
        "JF-454: Done, though no release carries the commit since v1.0.20 that names it.",
        "JF-455: named by a commit since v1.0.20, but Jira has no such ticket.",
        "JF-460: in Review, though v1.0.19, v1.0.20 released it.",
        "JF-461: in Review, though no commit since v1.0.20 names it.",
      ].map((line) => `${line}\n`).join("") });
      strictEqual(seen.every((s) => s.method === "GET"), true, "audit changes nothing");
      deepStrictEqual(seen.filter((s) => s.url.startsWith("/rest/api/2/search")).map((s) => s.url), [`${REVIEW_SEARCH}0&maxResults=100`]);
    });
  });
});

test("audit answers 0 when every ticket is in step, reads every page of Review, and starts from the ref it is given", async () => {
  const fields = {
    "JF-450": { status: { name: "Review" }, fixVersions: [] },
    "JF-451": { status: { name: "In Progress" }, fixVersions: [] },
    "JF-456": { status: { name: "Review" }, fixVersions: [] },
  };
  const review = {
    0: { startAt: 0, total: 2, issues: [{ key: "JF-450", fields: { fixVersions: [] } }] },
    1: { startAt: 1, total: 2, issues: [{ key: "JF-456", fields: { fixVersions: [] } }] },
  };
  await withJira(auditJira(fields, review), async ({ config, seen }) => {
    await inRepo(async ({ repo, commit, tag }) => {
      commit("fix: b (JF-451)");
      tag("v1.0.20");
      commit("feat: a (JF-450)");
      commit("feat: f (JF-456)");
      deepStrictEqual(await cli(["audit"], config, repo), { code: 0, stderr: "",
        stdout: "Every ticket named since v1.0.20 is in Review, and every ticket in Review is named since then.\n" });
      deepStrictEqual(seen.filter((s) => s.url.startsWith("/rest/api/2/search")).map((s) => s.url),
        [`${REVIEW_SEARCH}0&maxResults=100`, `${REVIEW_SEARCH}1&maxResults=100`]);
      deepStrictEqual(await cli(["audit", "HEAD~3"], config, repo),
        { code: 1, stderr: "", stdout: "JF-451: In Progress, though a commit since HEAD~3 names it.\n" });
    });
  });
});

test("audit answers 2 when no version tag precedes HEAD, git cannot read the range, or Jira cannot be asked", async () => {
  await inRepo(async ({ repo }) => {
    const untagged = await cli(["audit"], "/nonexistent", repo);
    strictEqual(untagged.code, 2);
    strictEqual(untagged.stderr.startsWith("no version tag to start from: "), true, untagged.stderr);
    const bad = await cli(["audit", "v9.9.9"], "/nonexistent", repo);
    strictEqual(bad.code, 2);
    strictEqual(bad.stderr.startsWith("git log v9.9.9..HEAD failed: "), true, bad.stderr);
  });
  const review = { 0: { startAt: 0, total: 0, issues: [] } };
  await withJira((method, url) => (url.startsWith("/rest/api/2/search") ? [503] : [200, { fields: { status: { name: "Review" } } }]),
    async ({ config }) => {
      await inRepo(async ({ repo, commit, tag }) => {
        tag("v1.0.20");
        commit("feat: a (JF-450)");
        deepStrictEqual(await cli(["audit"], config, repo), { code: 2, stdout: "", stderr: "Jira answered 503 for the tickets in Review\n" });
      });
    });
  await withJira((method, url) => (url.startsWith("/rest/api/2/search") ? [200, review[0]] : [500]), async ({ config }) => {
    await inRepo(async ({ repo, commit, tag }) => {
      tag("v1.0.20");
      commit("feat: a (JF-450)");
      deepStrictEqual(await cli(["audit"], config, repo), { code: 2, stdout: "", stderr: "Jira answered 500 for JF-450\n" });
    });
  });
});
