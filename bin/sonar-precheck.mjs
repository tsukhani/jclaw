#!/usr/bin/env node
// Sonar's rules over the files a release changes, so /deploy hears about a failing quality gate before Jenkins does.
// Contract and exit codes: bin/README.md.

import { spawn, spawnSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { homedir } from "node:os";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const USAGE = `Usage: node bin/sonar-precheck.mjs [--base REF] [--head REF]
  --base REF   compare against REF (default: the newest v* tag on origin/main)
  --head REF   check REF's files (default: the working tree, untracked files included)`;

const PROJECT_KEY = "abundent:jclaw";
const IMAGE = "mcp/sonarqube";
const CONTAINER = "sonar-precheck";
// Not the MCP server's sonarqube-mcp-cache: while a session's server holds that volume, every analysis in another
// container on it fails with CancellationException.
const VOLUME = "sonarqube-mcp-precheck";
const CALL_TIMEOUT_MS = 120_000;
const READY_POLL_MS = 3_000;
const READY_PROBE = { language: "ts", scope: "MAIN", fileContent: "export const a = 1\n" };

const LANGUAGES = {
  java: "java", ts: "ts", tsx: "tsx", js: "js", mjs: "js", cjs: "js", jsx: "jsx", vue: "vue", css: "css", scss: "scss",
  html: "html",
};
// The sonar.exclusions in build.gradle.kts that a language above can reach.
const EXCLUDED = /(^|\/)(node_modules|dist|\.nuxt|\.output|precompiled|workspace|public\/spa)\/|^app\/views\//;

/** How Sonar sees a path: its analyzer language and scope, or null when Sonar does not analyze it. */
export function targetOf(path) {
  const language = LANGUAGES[path.split(".").pop()];
  if (!language || EXCLUDED.test(path)) return null;
  if (/^(test|frontend\/test)\//.test(path)) return { language, scope: "TEST" };
  // frontend/tests/e2e is under sonar.sources, not sonar.tests. Sent as TEST, S5332 stays silent on the specs that
  // aborted build #1314.
  return /^(app|frontend)\//.test(path) ? { language, scope: "MAIN" } : null;
}

/** The new-side line numbers each file gains, from `git diff -U0`. A file with only deletions maps to an empty set. */
export function parseChangedLines(diff) {
  const changed = new Map();
  let lines = null;
  for (const line of diff.split("\n")) {
    const file = /^\+\+\+ b\/(.+?)\t?$/.exec(line);
    if (file) {
      lines = new Set();
      changed.set(file[1], lines);
      continue;
    }
    const hunk = /^@@ -\S+ \+(\d+)(?:,(\d+))? @@/.exec(line);
    if (!hunk || !lines) continue;
    const count = hunk[2] === undefined ? 1 : Number(hunk[2]);
    for (let i = 0; i < count; i++) lines.add(Number(hunk[1]) + i);
  }
  return changed;
}

const globToRegExp = (glob) => new RegExp(
  `^${glob.split("**/").map((part) => part.replace(/[.+?^${}()|[\]\\]/g, "\\$&").replaceAll("*", "[^/]*")).join("(?:.*/)?")}$`,
);

/** The sonar.issue.ignore.multicriteria entries of build.gradle.kts, which only the Gradle scanner applies. */
export function parseIgnoreRules(gradle) {
  const entries = new Map();
  const property = /"sonar\.issue\.ignore\.multicriteria\.(\w+)\.(ruleKey|resourceKey)",\s*"([^"]+)"/g;
  for (const [, id, field, value] of gradle.matchAll(property)) {
    entries.set(id, { ...entries.get(id), [field]: value });
  }
  return [...entries.values()].map((e) => ({ rule: e.ruleKey, path: globToRegExp(e.resourceKey) }));
}

/** The analyzer's issues Sonar would raise as new code: those touching a changed line and not ignored by the build. */
export function newCodeFindings(issues, path, changedLines, ignoreRules) {
  return issues.filter((issue) => {
    // Raised for the empty path the analyzer is given, never by a real scan.
    if (issue.ruleKey === "java:S1598") return false;
    if (ignoreRules.some((rule) => rule.rule === issue.ruleKey && rule.path.test(path))) return false;
    const { startLine, endLine = startLine } = issue.textRange ?? {};
    for (let line = startLine; line <= endLine; line++) if (changedLines.has(line)) return true;
    return false;
  });
}

/** The gate requires an A on both ratings, so one issue with either impact fails it; maintainability is a debt ratio. */
export function canFailGate(issue) {
  return /\b(RELIABILITY|SECURITY)=/.test(issue.impacts ?? "");
}

function git(...args) {
  const result = spawnSync("git", args, { encoding: "utf8", maxBuffer: 1 << 28 });
  if (result.status !== 0) throw new Error(`git ${args[0]} failed: ${(result.stderr || result.error?.message || "").trim()}`);
  return result.stdout;
}

function changedFiles(root, base, head) {
  const range = head ? [base, head] : [base];
  const paths = ["app", "frontend", "test"];
  const changed = parseChangedLines(git("-c", "core.quotePath=false", "diff", "-U0", "--diff-filter=d", ...range, "--", ...paths));
  if (head) return changed;
  const untracked = git("ls-files", "--others", "--exclude-standard", "--", ...paths).split("\n").filter(Boolean);
  for (const path of untracked) {
    if (!targetOf(path)) continue;
    const lineCount = readFileSync(join(root, path), "utf8").split("\n").length;
    changed.set(path, new Set(Array.from({ length: lineCount }, (_, i) => i + 1)));
  }
  return changed;
}

function sonarEnv() {
  const env = JSON.parse(readFileSync(join(homedir(), ".claude.json"), "utf8")).mcpServers?.sonarqube?.env;
  if (!env?.SONARQUBE_TOKEN) throw new Error("no sonarqube MCP server with a token in ~/.claude.json");
  return { SONARQUBE_TOKEN: env.SONARQUBE_TOKEN, SONARQUBE_URL: env.SONARQUBE_URL };
}

function openAnalyzer(env) {
  // A fixed name, so a run that crashed cannot leave a container holding the volume against the next one.
  const removeContainer = () => spawnSync("docker", ["rm", "-f", CONTAINER], { stdio: "ignore" });
  removeContainer();
  const child = spawn(
    "docker",
    ["run", "--init", "-i", "--rm", "--name", CONTAINER, "-v", `${VOLUME}:/app/storage`,
      "-e", "SONARQUBE_TOKEN", "-e", "SONARQUBE_URL", IMAGE],
    { env: { ...process.env, ...env }, stdio: ["pipe", "pipe", "ignore"] },
  );
  const pending = new Map();
  let failure = null;
  let nextId = 0;
  let buffer = "";

  const failAll = (error) => {
    failure ??= error;
    for (const waiter of pending.values()) waiter.reject(failure);
    pending.clear();
  };
  child.on("error", (error) => failAll(new Error(`docker could not start: ${error.message}`)));
  child.on("exit", () => failAll(new Error("the analyzer stopped before it answered: is Docker running?")));
  child.stdin.on("error", () => {});
  child.stdout.setEncoding("utf8").on("data", (chunk) => {
    buffer += chunk;
    for (let end = buffer.indexOf("\n"); end >= 0; end = buffer.indexOf("\n")) {
      const line = buffer.slice(0, end);
      buffer = buffer.slice(end + 1);
      let message;
      try {
        message = JSON.parse(line);
      } catch {
        continue;
      }
      pending.get(message.id)?.resolve(message);
      pending.delete(message.id);
    }
  });

  const send = (message) => child.stdin.write(`${JSON.stringify({ jsonrpc: "2.0", ...message })}\n`);
  const request = (method, params) => new Promise((settle, reject) => {
    if (failure) {
      reject(failure);
      return;
    }
    const id = ++nextId;
    const timer = setTimeout(() => {
      pending.delete(id);
      reject(new Error(`the analyzer did not answer ${method} within ${CALL_TIMEOUT_MS / 1000}s`));
    }, CALL_TIMEOUT_MS);
    const clearing = (finish) => (value) => {
      clearTimeout(timer);
      finish(value);
    };
    pending.set(id, { resolve: clearing(settle), reject: clearing(reject) });
    send({ id, method, params });
  });

  const analyze = async ({ language, scope, fileContent }) => {
    const response = await request("tools/call", {
      name: "analyze_code_snippet",
      arguments: { projectKey: PROJECT_KEY, language, scope, fileContent },
    });
    const text = response.error?.message ?? response.result?.content?.[0]?.text ?? "";
    if (response.error || response.result?.isError) throw new Error(text);
    return JSON.parse(text).issues ?? [];
  };

  return {
    analyze,
    async ready() {
      await request("initialize", {
        protocolVersion: "2025-06-18", capabilities: {}, clientInfo: { name: "sonar-precheck", version: "1" },
      });
      send({ method: "notifications/initialized" });
      const deadline = Date.now() + Number(process.env.SONAR_PRECHECK_READY_MS ?? CALL_TIMEOUT_MS);
      for (;;) {
        try {
          await analyze(READY_PROBE);
          return;
        } catch (error) {
          if (failure) throw failure;
          if (Date.now() >= deadline) throw new Error(`the analyzer never became ready: ${error.message}`);
          await new Promise((wake) => setTimeout(wake, READY_POLL_MS));
        }
      }
    },
    close() {
      removeContainer();
      child.kill();
    },
  };
}

const describe = ({ path, issue }) => `  ${issue.ruleKey} ${path}:${issue.textRange.startLine} ${issue.impacts.replace(/[{}]/g, "")}: ${issue.primaryMessage}`;

function section(title, rows) {
  if (rows.length > 0) console.log(`${title} (${rows.length}):\n${rows.join("\n")}`);
}

async function run({ base, head }) {
  const root = git("rev-parse", "--show-toplevel").trim();
  const since = base ?? git("describe", "--tags", "--abbrev=0", "--match", "v*", "origin/main").trim();
  const changed = changedFiles(root, since, head);
  const targets = [...changed].filter(([path, lines]) => lines.size > 0 && targetOf(path));
  if (targets.length === 0) {
    console.log(`Sonar pre-check: no analyzable file has changed since ${since}.`);
    return 0;
  }

  const ignoreRules = parseIgnoreRules(readFileSync(join(root, "build.gradle.kts"), "utf8"));
  const analyzer = openAnalyzer(sonarEnv());
  const findings = [];
  const unchecked = [];
  try {
    await analyzer.ready();
    for (const [path, lines] of targets) {
      try {
        const fileContent = head ? git("show", `${head}:${path}`) : readFileSync(join(root, path), "utf8");
        const issues = await analyzer.analyze({ ...targetOf(path), fileContent });
        for (const issue of newCodeFindings(issues, path, lines, ignoreRules)) findings.push({ path, issue });
      } catch (error) {
        unchecked.push(`  ${path}: ${error.message}`);
      }
    }
  } finally {
    analyzer.close();
  }

  const blocking = findings.filter(({ issue }) => canFailGate(issue));
  console.log(
    `Sonar pre-check: ${targets.length - unchecked.length} of ${targets.length} changed files checked since ${since}; `
      + "duplication and coverage are not checked.",
  );
  section("Can fail the quality gate", blocking.map(describe));
  section("Other findings", findings.filter(({ issue }) => !canFailGate(issue)).map(describe));
  section("Not checked", unchecked);
  if (blocking.length > 0) return 1;
  return unchecked.length > 0 ? 2 : 0;
}

function parseArgs(argv) {
  const options = {};
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === "--base" && argv[i + 1]) options.base = argv[++i];
    else if (argv[i] === "--head" && argv[i + 1]) options.head = argv[++i];
    else {
      console.error(USAGE);
      process.exit(argv[i] === "--help" ? 0 : 2);
    }
  }
  return options;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  run(parseArgs(process.argv.slice(2))).then(
    (code) => process.exit(code),
    (error) => {
      console.error(`Sonar pre-check did not run: ${error.message}`);
      process.exit(2);
    },
  );
}
