#!/usr/bin/env node
// Picks the backend test classes a change can reach, for the pre-push and factory gates; the full suite runs in
// Jenkins. Contract and exit codes: bin/README.md.

import { spawnSync } from "node:child_process";
import { existsSync, readdirSync, readFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const PROJECT_ROOT = resolve(fileURLToPath(import.meta.url), "..", "..");
const MAIN_CLASSES = join(PROJECT_ROOT, "build", "classes", "java", "main");
const TEST_CLASSES = join(PROJECT_ROOT, "build", "classes", "java", "test");

const USAGE = `Usage: node bin/select-tests.mjs [--base REF] [--head REF] [--depth N] [--json]

  --base REF   compare against this commit (default origin/main); the diff is base...head
  --head REF   the change to test (default HEAD)
  --depth N    follow dependents N hops above a changed class (default 2; Infinity for all)
  --json       print {mode, tests, fullBecause, reasons} instead of the -Ptests list

Prints ALL, NONE, or a comma-separated class list for ./gradlew playAutotest -Ptests=.
Exit: 0 printed a selection, 2 the selection could not be made (no compiled classes, git failed).`;

// Rule checks guard the whole tree, not one class, so every gate runs them.
const ALWAYS = ["ArchitectureTest", "CapabilityRulesTest", "WallClockDisciplineTest"];
const ALWAYS_SUFFIX = "ConformanceTest";

// A change to any of these reaches tests no class graph shows.
const FULL_PATHS = [/^conf\/(?!application\.conf$)/, /^build\.gradle\.kts$/, /^settings\.gradle\.kts$/, /^\.play-version$/,
  /^gradle\//, /^lib\//, /^app\/(?!.*\.java$)/];
// No backend test reads these; the frontend gate and the harness's own checks cover them.
const NO_BACKEND = [/^frontend\//, /^docs\//, /^\.sandcastle\//, /^[^/]+\.md$/, /^\.github\//, /^_bmad/];

/** The top-level class a jdeps name belongs to: inner and anonymous classes count as their outer class. */
export function outerClass(name) {
  return name.split("$")[0];
}

/** source → targets from `jdeps -verbose:class` output, outer classes only, self-edges dropped. */
export function parseJdeps(text) {
  const edges = new Map();
  for (const line of text.split("\n")) {
    const m = line.match(/^\s+(\S+)\s+->\s+(\S+)\s/);
    if (!m) continue;
    const from = outerClass(m[1]);
    const to = outerClass(m[2]);
    if (from === to) continue;
    if (!edges.has(from)) edges.set(from, new Set());
    edges.get(from).add(to);
  }
  return edges;
}

/** Every class within {@code maxDepth} hops above one of {@code changed} through {@code edges}, the changed ones included. */
export function dependents(edges, changed, maxDepth = Infinity) {
  const reverse = new Map();
  for (const [from, targets] of edges) {
    for (const to of targets) {
      if (!reverse.has(to)) reverse.set(to, new Set());
      reverse.get(to).add(from);
    }
  }
  const seen = new Set(changed);
  let frontier = [...changed];
  for (let depth = 0; depth < maxDepth && frontier.length > 0; depth++) {
    const next = [];
    for (const cls of frontier) {
      for (const up of reverse.get(cls) ?? []) {
        if (!seen.has(up)) {
          seen.add(up);
          next.push(up);
        }
      }
    }
    frontier = next;
  }
  return seen;
}

/** The class an app/ or test/ Java path declares, or undefined for any other path. */
export function classOfPath(path) {
  if (/^app\/.+\.java$/.test(path)) return path.slice("app/".length, -".java".length).replaceAll("/", ".");
  if (/^test\/.+\.java$/.test(path)) return path.slice("test/".length, -".java".length).replaceAll("/", ".");
  return undefined;
}

/** The URL paths conf/routes binds to {@code controller}, a simple class name such as ApiMemoryController. */
export function routesOf(routesText, controller) {
  const paths = new Set();
  for (const line of routesText.split("\n")) {
    const m = line.match(/^\s*[A-Z*]+\s+(\/\S*)\s+([\w.]+)\.\w+/);
    if (m && m[2].split(".").pop() === controller) paths.add(m[1].replace(/\{[^}]*\}.*$/, "").replace(/\/$/, ""));
  }
  return [...paths].filter((p) => p.length > 1);
}

/**
 * The selection for {@code changes}, a list of {status, path} from `git diff --name-status`.
 * {@code graph} gives the jdeps edges, the test class names, each test's source, and conf/routes.
 */
export function select(changes, graph, versionOnlyConf, maxDepth = Infinity) {
  const fullBecause = [];
  const changedClasses = new Set();
  const reasons = new Map();
  const note = (test, why) => {
    if (!reasons.has(test)) reasons.set(test, why);
  };
  for (const { status, path } of changes) {
    if (NO_BACKEND.some((re) => re.test(path))) continue;
    if (path === "conf/application.conf") {
      if (!versionOnlyConf) fullBecause.push(path);
      continue;
    }
    if (FULL_PATHS.some((re) => re.test(path))) {
      fullBecause.push(path);
      continue;
    }
    const cls = classOfPath(path);
    if (cls !== undefined) {
      if (status !== "D") changedClasses.add(cls);
      continue;
    }
    // Any other file (jclaw.sh, bin/, sidecar/, evals/, archunit_store/): the tests that name it.
    const base = path.split("/").pop();
    for (const [test, source] of graph.testSources) {
      if (source.includes(path) || (base.includes(".") && base.length > 8 && source.includes(base))) note(test, `names ${path}`);
    }
  }
  if (fullBecause.length > 0) return { mode: "all", tests: [], fullBecause, reasons: {} };

  const reach = dependents(graph.edges, changedClasses, maxDepth);
  for (const test of graph.tests) {
    if (reach.has(test)) note(test, changedClasses.has(test) ? "changed" : "depends on a changed class");
  }
  // A FunctionalTest reaches a controller over HTTP, which no class edge records.
  for (const cls of reach) {
    if (!/^controllers\.\w+Controller$/.test(cls)) continue;
    for (const route of routesOf(graph.routes, cls.split(".").pop())) {
      for (const [test, source] of graph.testSources) {
        if (source.includes(`"${route}`)) note(test, `calls ${route}`);
      }
    }
  }
  if (reasons.size === 0 && changedClasses.size === 0) return { mode: "none", tests: [], fullBecause, reasons: {} };
  for (const test of graph.tests) {
    if (ALWAYS.includes(test) || test.endsWith(ALWAYS_SUFFIX)) note(test, "rule check");
  }
  const tests = [...reasons.keys()].sort();
  return { mode: "some", tests, fullBecause, reasons: Object.fromEntries([...reasons].sort()) };
}

function git(...args) {
  const r = spawnSync("/usr/bin/git", args, { cwd: PROJECT_ROOT, encoding: "utf8", maxBuffer: 64 * 1024 * 1024 });
  if (r.status !== 0) fail(`git ${args.join(" ")} failed: ${r.stderr.trim()}`);
  return r.stdout;
}

function jdeps(...dirs) {
  const r = spawnSync("jdeps", ["-verbose:class", "-cp", MAIN_CLASSES, ...dirs], { encoding: "utf8", maxBuffer: 256 * 1024 * 1024 });
  if (r.status !== 0) fail(`jdeps failed: ${r.stderr.trim()}`);
  return r.stdout;
}

function testClassNames() {
  return readdirSync(join(PROJECT_ROOT, "test"))
    .filter((f) => f.endsWith("Test.java"))
    .map((f) => f.slice(0, -".java".length));
}

function loadGraph() {
  if (!existsSync(MAIN_CLASSES) || !existsSync(TEST_CLASSES)) fail("no compiled classes: run ./gradlew compileJava compileTestJava first");
  const edges = parseJdeps(jdeps(MAIN_CLASSES) + jdeps(TEST_CLASSES));
  const tests = testClassNames();
  const testSources = new Map(tests.map((t) => [t, readFileSync(join(PROJECT_ROOT, "test", `${t}.java`), "utf8")]));
  const routes = readFileSync(join(PROJECT_ROOT, "conf", "routes"), "utf8");
  return { edges, tests, testSources, routes };
}

/** Whether conf/application.conf changed only in its application.version line and its comments. */
function versionOnly(base, head) {
  const diff = git("diff", "-U0", `${base}...${head}`, "--", "conf/application.conf");
  const lines = diff.split("\n").filter((l) => /^[+-](?![+-])/.test(l));
  return lines.length > 0 && lines.every((l) => /^[+-](application\.version=|\s*#|\s*$)/.test(l));
}

function fail(message) {
  process.stderr.write(`select-tests: ${message}\n`);
  process.exit(2);
}

function main(argv) {
  let base = "origin/main";
  let head = "HEAD";
  let json = false;
  // Measured over 39 changes (2026-10-30): unlimited picked 71% of the suite for a typical backend change,
  // 15.6 min median; depth 2 picked a median 2.4 min and still covered a test driving the class through a caller.
  let depth = 2;
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === "--base") base = argv[++i];
    else if (argv[i] === "--head") head = argv[++i];
    else if (argv[i] === "--json") json = true;
    else if (argv[i] === "--depth") depth = Number(argv[++i]);
    else {
      process.stderr.write(`${USAGE}\n`);
      process.exit(argv[i] === "--help" ? 0 : 2);
    }
  }
  const changes = git("diff", "--name-status", "--no-renames", `${base}...${head}`)
    .split("\n").filter(Boolean)
    .map((l) => { const [status, path] = l.split("\t"); return { status, path }; });
  const result = select(changes, loadGraph(), versionOnly(base, head), depth);
  if (json) process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
  else process.stdout.write(`${result.mode === "all" ? "ALL" : result.mode === "none" ? "NONE" : result.tests.join(",")}\n`);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main(process.argv.slice(2));
