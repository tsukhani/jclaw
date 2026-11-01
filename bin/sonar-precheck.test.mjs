// node --test bin/sonar-precheck.test.mjs
// The end-to-end cases run the real script in a scratch repository, with a `docker` on PATH that speaks MCP over stdio
// and raises an issue on every line holding `FLAG <rule> <impacts>`, so an issue's line number is the fixture's own.

import { deepStrictEqual, strictEqual } from "node:assert";
import { spawnSync } from "node:child_process";
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import { canFailGate, newCodeFindings, parseChangedLines, parseIgnoreRules, targetOf } from "./sonar-precheck.mjs";

const SCRIPT = resolve(dirname(fileURLToPath(import.meta.url)), "sonar-precheck.mjs");

const FAKE_DOCKER = `#!/usr/bin/env node
const { appendFileSync } = require("node:fs");
const log = (entry) => appendFileSync(process.env.FAKE_DOCKER_LOG, JSON.stringify(entry) + "\\n");
const args = process.argv.slice(2);
if (args[0] !== "run") process.exit(0);
log({ args, token: process.env.SONARQUBE_TOKEN, url: process.env.SONARQUBE_URL });
const reply = (id, result) => process.stdout.write(JSON.stringify({ jsonrpc: "2.0", id, result }) + "\\n");
const text = (id, body, isError = false) => reply(id, { isError, content: [{ type: "text", text: body }] });
let buffer = "";
process.stdin.on("end", () => process.exit(0));
process.stdin.on("data", (chunk) => {
  buffer += chunk;
  for (let end = buffer.indexOf("\\n"); end >= 0; end = buffer.indexOf("\\n")) {
    const message = JSON.parse(buffer.slice(0, end));
    buffer = buffer.slice(end + 1);
    if (message.method === "initialize") reply(message.id, {});
    if (message.method !== "tools/call") continue;
    const call = message.params.arguments;
    if (process.env.FAKE_DOCKER_MODE === "never-ready" || call.fileContent.includes("BROKEN")) {
      text(message.id, "Error while analyzing the code: java.util.concurrent.CancellationException", true);
      continue;
    }
    if (!call.fileContent.startsWith("export const a = 1")) log({ call });
    const issues = call.fileContent.split("\\n").flatMap((line, i) => {
      const flag = /FLAG (\\S+) (\\S+)/.exec(line);
      const [, span] = /SPAN (\\d+)/.exec(line) ?? [undefined, "0"];
      return flag ? [{
        ruleKey: flag[1], primaryMessage: "Fix this.", impacts: "{" + flag[2] + "}",
        textRange: { startLine: i + 1, endLine: i + 1 + Number(span) },
      }] : [];
    });
    text(message.id, JSON.stringify({ issues, issueCount: issues.length }));
  }
});
`;

const GRADLE = `sonar { properties {
  property("sonar.issue.ignore.multicriteria.e1.ruleKey", "java:S2925")
  property("sonar.issue.ignore.multicriteria.e1.resourceKey", "test/*.java")
} }
`;

function scratch(t) {
  const dir = mkdtempSync(join(tmpdir(), "sonar-precheck-"));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const repo = join(dir, "repo");
  const home = join(dir, "home");
  const bin = join(dir, "bin");
  for (const d of [repo, home, bin]) mkdirSync(d);
  writeFileSync(join(home, ".claude.json"), JSON.stringify({
    mcpServers: { sonarqube: { env: { SONARQUBE_TOKEN: "s3cret", SONARQUBE_URL: "https://sonar.example" } } },
  }));
  writeFileSync(join(bin, "docker"), FAKE_DOCKER);
  chmodSync(join(bin, "docker"), 0o755);

  const logFile = join(dir, "docker.log");
  const env = {
    ...process.env, HOME: home, PATH: `${bin}:${process.env.PATH}`, FAKE_DOCKER_LOG: logFile, SONAR_PRECHECK_READY_MS: "0",
  };
  const git = (...args) => {
    const r = spawnSync("git", args, { cwd: repo, encoding: "utf8", env });
    strictEqual(r.status, 0, r.stderr);
    return r.stdout.trim();
  };
  const write = (path, content) => {
    mkdirSync(dirname(join(repo, path)), { recursive: true });
    writeFileSync(join(repo, path), content);
  };
  const commit = () => {
    git("add", "-A");
    git("-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false", "commit", "-qm", "c");
    return git("rev-parse", "HEAD");
  };
  // Plain refs: a global tag.gpgsign would otherwise try to sign the fixture's tag.
  const release = (tag) => {
    git("update-ref", `refs/tags/${tag}`, "HEAD");
    git("update-ref", "refs/remotes/origin/main", "HEAD");
  };
  const run = (args = [], extraEnv = {}) => spawnSync(process.execPath, [SCRIPT, ...args], {
    cwd: repo, encoding: "utf8", env: { ...env, ...extraEnv },
  });
  const dockerLog = () => (existsSync(logFile) ? readFileSync(logFile, "utf8").trim().split("\n").map((l) => JSON.parse(l)) : []);

  git("init", "-q");
  write("build.gradle.kts", GRADLE);
  write("app/A.java", "class A {}\n");
  commit();
  release("v1.0.0");
  return { bin, dir, write, commit, release, run, dockerLog, git, env };
}

const HEADER = (checked, total, since) =>
  `Sonar pre-check: ${checked} of ${total} changed files checked since ${since}; duplication and coverage are not checked.`;

test("a test tree is TEST, and e2e specs are MAIN because they sit under sonar.sources", () => {
  deepStrictEqual(targetOf("test/ToolTest.java"), { language: "java", scope: "TEST" });
  deepStrictEqual(targetOf("frontend/test/chat.test.ts"), { language: "ts", scope: "TEST" });
  deepStrictEqual(targetOf("frontend/tests/e2e/laya.uat.spec.ts"), { language: "ts", scope: "MAIN" });
  deepStrictEqual(targetOf("app/tools/Tool.java"), { language: "java", scope: "MAIN" });
  deepStrictEqual(targetOf("frontend/pages/chat.vue"), { language: "vue", scope: "MAIN" });
});

test("a path Sonar does not analyze has no target", () => {
  strictEqual(targetOf("app/views/errors/404.html"), null);
  strictEqual(targetOf("frontend/public/spa/app.js"), null);
  strictEqual(targetOf("frontend/README.md"), null);
  strictEqual(targetOf("bin/select-tests.mjs"), null);
  strictEqual(targetOf("app/data/rows.sql"), null);
});

test("changed lines come from every hunk, and a pure deletion adds none", () => {
  const diff = [
    "diff --git a/app/A.java b/app/A.java",
    "--- a/app/A.java",
    "+++ b/app/A.java",
    "@@ -3 +3 @@ class A",
    "-old",
    "+new",
    "@@ -10,0 +11,2 @@",
    "+one",
    "+two",
    "diff --git a/app/B.java b/app/B.java",
    "--- a/app/B.java",
    "+++ b/app/B.java",
    "@@ -4,2 +3,0 @@",
    "-gone",
    "-gone too",
  ].join("\n");
  deepStrictEqual(parseChangedLines(diff), new Map([["app/A.java", new Set([3, 11, 12])], ["app/B.java", new Set()]]));
});

test("ignore rules read build.gradle.kts, and a single star stops at a directory", () => {
  const [rule] = parseIgnoreRules(GRADLE);
  strictEqual(rule.rule, "java:S2925");
  strictEqual(rule.path.test("test/FooTest.java"), true);
  strictEqual(rule.path.test("test/sub/FooTest.java"), false);
  strictEqual(rule.path.test("app/Foo.java"), false);
});

test("a double star matches at any depth, the top included", () => {
  const [rule] = parseIgnoreRules(`"sonar.issue.ignore.multicriteria.e2.ruleKey", "java:S125"
    "sonar.issue.ignore.multicriteria.e2.resourceKey", "**/*.java"`);
  strictEqual(rule.path.test("A.java"), true);
  strictEqual(rule.path.test("app/tools/A.java"), true);
  strictEqual(rule.path.test("app/tools/A.ts"), false);
});

test("an issue counts from its first changed line to its last, and not one line past either end", () => {
  const issue = (startLine, endLine) => ({ ruleKey: "java:S1", textRange: { startLine, endLine } });
  const changed = new Set([5, 6]);
  const inRange = [issue(4, 5), issue(5, 5), issue(6, 9), issue(1, 20)];
  deepStrictEqual(newCodeFindings([...inRange, issue(3, 4), issue(7, 9)], "app/A.java", changed, []), inRange);
});

test("an issue with no location, and the analyzer's empty-path S1598, are not findings", () => {
  const issues = [{ ruleKey: "java:S1" }, { ruleKey: "java:S1598", textRange: { startLine: 1, endLine: 1 } }];
  deepStrictEqual(newCodeFindings(issues, "app/A.java", new Set([1]), []), []);
});

test("reliability and security can fail the gate, and maintainability alone cannot", () => {
  strictEqual(canFailGate({ impacts: "{RELIABILITY=LOW}" }), true);
  strictEqual(canFailGate({ impacts: "{MAINTAINABILITY=LOW, SECURITY=MEDIUM}" }), true);
  strictEqual(canFailGate({ impacts: "{MAINTAINABILITY=HIGH}" }), false);
  strictEqual(canFailGate({}), false);
});

test("a security finding on an added line exits 1", (t) => {
  const { write, commit, run } = scratch(t);
  write("app/A.java", "class A {}\n// FLAG java:S6418 SECURITY=BLOCKER\n");
  commit();

  const result = run();

  strictEqual(result.status, 1);
  strictEqual(result.stdout, [
    HEADER(1, 1, "v1.0.0"),
    "Can fail the quality gate (1):",
    "  java:S6418 app/A.java:2 SECURITY=BLOCKER: Fix this.",
    "",
  ].join("\n"));
});

test("a finding on a line this release did not touch is left out", (t) => {
  const { write, commit, release, run } = scratch(t);
  write("app/A.java", "// FLAG java:S2259 RELIABILITY=MEDIUM\nclass A {}\n");
  commit();
  release("v1.1.0");
  write("app/A.java", "// FLAG java:S2259 RELIABILITY=MEDIUM\nclass A {}\n// clean\n");
  commit();

  const result = run();

  strictEqual(result.status, 0);
  strictEqual(result.stdout, `${HEADER(1, 1, "v1.1.0")}\n`);
});

test("a maintainability finding is listed without failing the check", (t) => {
  const { write, commit, run } = scratch(t);
  write("app/A.java", "class A {}\n// FLAG java:S3776 MAINTAINABILITY=HIGH\n");
  commit();

  const result = run();

  strictEqual(result.status, 0);
  strictEqual(result.stdout, [
    HEADER(1, 1, "v1.0.0"), "Other findings (1):", "  java:S3776 app/A.java:2 MAINTAINABILITY=HIGH: Fix this.", "",
  ].join("\n"));
});

test("a rule the build ignores for that path is dropped, and still counts on any other path", (t) => {
  const { write, commit, run } = scratch(t);
  write("test/FooTest.java", "// FLAG java:S2925 RELIABILITY=LOW\n");
  write("app/B.java", "// FLAG java:S2925 RELIABILITY=LOW\n");
  commit();

  const result = run();

  strictEqual(result.status, 1);
  strictEqual(result.stdout, [
    HEADER(2, 2, "v1.0.0"), "Can fail the quality gate (1):", "  java:S2925 app/B.java:1 RELIABILITY=LOW: Fix this.", "",
  ].join("\n"));
});

test("uncommitted edits and untracked files are checked, because /deploy ships them", (t) => {
  const { write, run } = scratch(t);
  write("app/A.java", "class A {}\n// FLAG java:S2077 SECURITY=MEDIUM\n");
  write("frontend/pages/new.vue", "<!-- FLAG Web:S6819 RELIABILITY=LOW -->\n");

  const result = run();

  strictEqual(result.status, 1);
  strictEqual(result.stdout, [
    HEADER(2, 2, "v1.0.0"),
    "Can fail the quality gate (2):",
    "  java:S2077 app/A.java:2 SECURITY=MEDIUM: Fix this.",
    "  Web:S6819 frontend/pages/new.vue:1 RELIABILITY=LOW: Fix this.",
    "",
  ].join("\n"));
});

test("code already on origin/main but in no passed version is checked: Sonar never saw it", (t) => {
  const { write, commit, git, run } = scratch(t);
  write("app/A.java", "class A {}\n// FLAG java:S5332 SECURITY=LOW\n");
  commit();
  git("update-ref", "refs/remotes/origin/main", "HEAD");

  const result = run();

  strictEqual(result.status, 1);
  strictEqual(result.stdout.includes("  java:S5332 app/A.java:2 SECURITY=LOW: Fix this."), true);
});

test("--base and --head compare two commits and read the head's content, not the working tree", (t) => {
  const { write, commit, run, git } = scratch(t);
  write("app/A.java", "class A {}\n// FLAG java:S6418 SECURITY=BLOCKER\n");
  const flagged = commit();
  write("app/A.java", "class A {}\n// clean\n");
  commit();
  const before = git("rev-parse", `${flagged}~1`);

  const result = run(["--base", before, "--head", flagged]);

  strictEqual(result.status, 1);
  strictEqual(result.stdout, [
    HEADER(1, 1, before),
    "Can fail the quality gate (1):",
    "  java:S6418 app/A.java:2 SECURITY=BLOCKER: Fix this.",
    "",
  ].join("\n"));
});

test("each file goes to the analyzer with Sonar's language and scope, in a container on its own volume", (t) => {
  const { write, commit, run, dockerLog } = scratch(t);
  write("frontend/tests/e2e/a.uat.spec.ts", "export const b = 1\n");
  write("test/FooTest.java", "class FooTest {}\n");
  commit();

  run();

  const [{ args, token, url }, ...calls] = dockerLog();
  deepStrictEqual(args, ["run", "--init", "-i", "--rm", "--name", "sonar-precheck", "-v", "sonarqube-mcp-precheck:/app/storage",
    "-e", "SONARQUBE_TOKEN", "-e", "SONARQUBE_URL", "mcp/sonarqube"]);
  strictEqual(token, "s3cret");
  strictEqual(url, "https://sonar.example");
  deepStrictEqual(calls.map(({ call }) => call), [
    { projectKey: "abundent:jclaw", language: "ts", scope: "MAIN", fileContent: "export const b = 1\n" },
    { projectKey: "abundent:jclaw", language: "java", scope: "TEST", fileContent: "class FooTest {}\n" },
  ]);
});

test("the token never reaches the output", (t) => {
  const { write, commit, run } = scratch(t);
  write("app/A.java", "class A {}\n// FLAG java:S6418 SECURITY=BLOCKER\n");
  commit();

  const result = run();

  strictEqual(`${result.stdout}${result.stderr}`.includes("s3cret"), false);
});

test("no analyzable change exits 0 without starting Docker", (t) => {
  const { write, commit, run, dockerLog } = scratch(t);
  write("docs/guide.md", "words\n");
  write("app/views/page.html", "<p>x</p>\n");
  commit();

  const result = run();

  strictEqual(result.status, 0);
  strictEqual(result.stdout, "Sonar pre-check: no analyzable file has changed since v1.0.0.\n");
  deepStrictEqual(dockerLog(), []);
});

test("a change of deletions alone has nothing to check", (t) => {
  const { write, commit, release, run, dockerLog } = scratch(t);
  write("app/A.java", "class A {}\n// gone\n");
  commit();
  release("v1.1.0");
  write("app/A.java", "class A {}\n");
  commit();

  const result = run();

  strictEqual(result.status, 0);
  strictEqual(result.stdout, "Sonar pre-check: no analyzable file has changed since v1.1.0.\n");
  deepStrictEqual(dockerLog(), []);
});

test("an analyzer that never becomes ready exits 2", (t) => {
  const { write, commit, run } = scratch(t);
  write("app/A.java", "class A {}\n// x\n");
  commit();

  const result = run([], { FAKE_DOCKER_MODE: "never-ready" });

  strictEqual(result.status, 2);
  strictEqual(result.stdout, "");
  strictEqual(
    result.stderr,
    "Sonar pre-check did not run: the analyzer never became ready: Error while analyzing the code: "
      + "java.util.concurrent.CancellationException\n",
  );
});

test("no docker on the path exits 2", (t) => {
  const { write, commit, run, dir } = scratch(t);
  write("app/A.java", "class A {}\n// x\n");
  commit();
  const noDocker = join(dir, "no-docker");
  mkdirSync(noDocker);
  symlinkSync(spawnSync("sh", ["-c", "command -v git"], { encoding: "utf8" }).stdout.trim(), join(noDocker, "git"));

  const result = run([], { PATH: noDocker });

  strictEqual(result.status, 2);
  strictEqual(result.stdout, "");
  strictEqual(result.stderr, "Sonar pre-check did not run: docker could not start: spawn docker ENOENT\n");
});

test("no sonarqube server in ~/.claude.json exits 2", (t) => {
  const { write, commit, run, dir } = scratch(t);
  write("app/A.java", "class A {}\n// x\n");
  commit();
  writeFileSync(join(dir, "home", ".claude.json"), "{}");

  const result = run();

  strictEqual(result.status, 2);
  strictEqual(result.stderr, "Sonar pre-check did not run: no sonarqube MCP server with a token in ~/.claude.json\n");
});

test("no version tag on origin/main exits 2 rather than checking nothing", (t) => {
  const { write, commit, run, git } = scratch(t);
  write("app/A.java", "class A {}\n// x\n");
  commit();
  git("update-ref", "-d", "refs/tags/v1.0.0");

  const result = run();

  strictEqual(result.status, 2);
  strictEqual(result.stdout, "");
  strictEqual(result.stderr.startsWith("Sonar pre-check did not run: git describe failed: "), true);
});

test("a file the analyzer fails on is named, and the check exits 2", (t) => {
  const { write, commit, run } = scratch(t);
  write("app/A.java", "class A {}\n// BROKEN\n");
  write("app/B.java", "class B {}\n");
  commit();

  const result = run();

  strictEqual(result.status, 2);
  strictEqual(result.stdout, [
    HEADER(1, 2, "v1.0.0"),
    "Not checked (1):",
    "  app/A.java: Error while analyzing the code: java.util.concurrent.CancellationException",
    "",
  ].join("\n"));
});

test("a finding that can fail the gate outranks a file that was not checked", (t) => {
  const { write, commit, run } = scratch(t);
  write("app/A.java", "class A {}\n// BROKEN\n");
  write("app/B.java", "// FLAG java:S2259 RELIABILITY=MEDIUM\n");
  commit();

  const result = run();

  strictEqual(result.status, 1);
  strictEqual(result.stdout, [
    HEADER(1, 2, "v1.0.0"),
    "Can fail the quality gate (1):",
    "  java:S2259 app/B.java:1 RELIABILITY=MEDIUM: Fix this.",
    "Not checked (1):",
    "  app/A.java: Error while analyzing the code: java.util.concurrent.CancellationException",
    "",
  ].join("\n"));
});

test("an unknown argument prints the usage and exits 2", (t) => {
  const { run } = scratch(t);

  const result = run(["--nope"]);

  strictEqual(result.status, 2);
  strictEqual(result.stderr.startsWith("Usage: node bin/sonar-precheck.mjs"), true);
});
