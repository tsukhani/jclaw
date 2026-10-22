#!/usr/bin/env node
// Mutation testing of what changed since a git ref; scores are reported, never gated (JF-17).
// Exit codes and report paths: bin/README.md.

import { spawnSync } from "node:child_process";
import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, statSync, symlinkSync, writeFileSync } from "node:fs";
import { availableParallelism, tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const PROJECT_ROOT = resolve(fileURLToPath(import.meta.url), "..", "..");
const REPORTS = join(PROJECT_ROOT, "build", "reports", "mutation");
const WINDOWS = process.platform === "win32";
const WORKERS = String(Math.max(1, Math.floor(availableParallelism() / 2)));

const USAGE = `Usage: node bin/mutation.mjs <git-ref>

Runs PIT on the app/ classes and Stryker on the frontend/ files changed since <git-ref>,
committed or not, against the tests that name them, and prints each score.

Exit: 0 measured, 2 a harness measured nothing or could not run.`;

// The fork's PlayJUnitExtension boots Play in every test JVM; put first on PIT's classpath, this
// lets a test that needs no running app run under PIT. A test that does need one fails and is skipped.
const NO_BOOT_EXTENSION = `package play.test;
public class PlayJUnitExtension implements org.junit.jupiter.api.extension.Extension {}
`;

export function backendTargets(files) {
  return files
    .filter((f) => /^app\/.+\.java$/.test(f) && !f.endsWith("/package-info.java"))
    .map((f) => f.slice("app/".length, -".java".length).replaceAll("/", "."));
}

export function frontendTargets(files) {
  return files
    .filter((f) => /^frontend\/(?!test\/|tests\/|eslint\/|public\/).+\.(ts|vue)$/.test(f)
      && !f.endsWith(".d.ts") && !f.endsWith(".config.ts"))
    .map((f) => f.slice("frontend/".length));
}

/** Test classes whose source names one of {@code classNames}; FunctionalTests need a server PIT cannot give them. */
export function testsNaming(classNames, testSources) {
  const tests = new Set();
  for (const name of classNames) {
    const word = new RegExp(`\\b${name.slice(name.lastIndexOf(".") + 1)}\\b`);
    for (const [test, source] of testSources) {
      if (word.test(source) && !/\bextends\s+FunctionalTest\b/.test(source)) tests.add(test);
    }
  }
  return [...tests].sort();
}

/** Test files that name {@code file}'s module, by import or, for an auto-import, by use. */
export function testsImporting(file, testSources) {
  const module = file.slice(file.lastIndexOf("/") + 1).replace(/\.(ts|vue)$/, "");
  const word = new RegExp(`\\b${module}\\b`);
  return [...testSources].filter(([, source]) => word.test(source)).map(([test]) => test).sort();
}

/** Test classes whose compiled class is missing or older than its source: PIT would run the old test. */
export function staleTestClasses(tests, testDir, classDir) {
  return tests.filter((test) => {
    const classFile = join(classDir, `${test}.class`);
    return !existsSync(classFile) || statSync(classFile).mtimeMs < statSync(join(testDir, `${test}.java`)).mtimeMs;
  });
}

const MUTATION = /<mutation\b[^>]*\bdetected='(true|false)'[^>]*\bstatus='([A-Z_]+)'[^>]*>/g;

export function summarizePit(xml) {
  const statuses = {};
  let total = 0;
  let detected = 0;
  for (const [, isDetected, status] of xml.matchAll(MUTATION)) {
    total++;
    if (isDetected === "true") detected++;
    statuses[status] = (statuses[status] ?? 0) + 1;
  }
  return { tool: "PIT", total, detected, statuses };
}

// Stryker's own score leaves out mutants that did not compile or crashed the run.
const STRYKER_VALID = new Set(["Killed", "Timeout", "Survived", "NoCoverage"]);

export function summarizeStryker(report) {
  const statuses = {};
  let total = 0;
  let detected = 0;
  let survivorsWithoutTests = 0;
  for (const file of Object.values(report.files)) {
    for (const mutant of file.mutants) {
      statuses[mutant.status] = (statuses[mutant.status] ?? 0) + 1;
      if (!STRYKER_VALID.has(mutant.status)) continue;
      total++;
      if (mutant.status === "Killed" || mutant.status === "Timeout") detected++;
      if (mutant.status === "Survived" && mutant.testsCompleted === 0) survivorsWithoutTests++;
    }
  }
  return { tool: "Stryker", total, detected, statuses, survivorsWithoutTests };
}

/** Why a summary measured nothing; empty when it measured something. */
export function harnessFaults(summary) {
  if (summary.total === 0) return [`${summary.tool} generated no mutants`];
  const faults = [];
  if (summary.detected === 0) {
    faults.push(`${summary.tool} detected none of ${summary.total} mutants: the tests never reached them`);
  }
  if (summary.survivorsWithoutTests > 0) {
    faults.push(`${summary.survivorsWithoutTests} mutants reported as Survived ran no test (testsCompleted 0)`);
  }
  return faults;
}

export function score(summary) {
  return `${((100 * summary.detected) / summary.total).toFixed(1)}% (${summary.detected} of ${summary.total} detected)`;
}

function run(command, args, options = {}) {
  return spawnSync(command, args, { cwd: PROJECT_ROOT, encoding: "utf8", maxBuffer: 64 * 1024 * 1024, ...options });
}

function lines(text) {
  return text.split(/\r?\n/).map((l) => l.trim()).filter(Boolean);
}

function sources(dir, pattern, name) {
  const found = new Map();
  const walk = (at) => {
    for (const entry of readdirSync(at, { withFileTypes: true })) {
      const path = join(at, entry.name);
      if (entry.isDirectory()) walk(path);
      else if (pattern.test(entry.name)) found.set(name(path), readFileSync(path, "utf8"));
    }
  };
  walk(dir);
  return found;
}

function changedFiles(ref) {
  const diff = run("git", ["diff", "--name-only", "--diff-filter=d", ref]);
  if (diff.status !== 0) throw new Error(`git diff ${ref} failed: ${diff.stderr.trim()}`);
  const untracked = run("git", ["ls-files", "--others", "--exclude-standard"]);
  return [...new Set([...lines(diff.stdout), ...lines(untracked.stdout)])].sort();
}

function gradle(args) {
  return run(join(PROJECT_ROOT, WINDOWS ? "gradlew.bat" : "gradlew"), [...args, "--console=plain"]);
}

function runPit(classes, report) {
  const tests = testsNaming(classes, sources(join(PROJECT_ROOT, "test"), /\.java$/, (p) => p.slice(p.lastIndexOf("/") + 1, -5)));
  if (tests.length === 0) {
    process.stderr.write(`==> PIT skipped: no unit test names ${classes.join(", ")}\n`);
    return [];
  }
  process.stderr.write(`==> PIT: ${classes.length} class(es), ${tests.length} test class(es); compiling (./gradlew compileTestJava)...\n`);
  // play autotest compiles test/ with Play's own compiler and never writes build/classes/java/test.
  const compile = gradle(["compileTestJava"]);
  if (compile.status !== 0) return [`./gradlew compileTestJava failed:\n${compile.stdout}${compile.stderr}`];
  const stale = staleTestClasses(tests, join(PROJECT_ROOT, "test"), join(PROJECT_ROOT, "build", "classes", "java", "test"));
  if (stale.length > 0) return [`compiled test classes are older than their sources: ${stale.join(", ")}`];

  const playClasspath = gradle(["-q", "playClasspath"]);
  const pitClasspath = gradle(["-q", "-I", join(PROJECT_ROOT, "bin", "mutation.init.gradle.kts"), "pitestClasspath"]);
  if (playClasspath.status !== 0 || pitClasspath.status !== 0) {
    return [`could not resolve the PIT classpath:\n${playClasspath.stderr}${pitClasspath.stderr}`];
  }
  const appJars = lines(playClasspath.stdout);
  const pitJars = lines(pitClasspath.stdout);

  const work = mkdtempSync(join(tmpdir(), "jclaw-pit-"));
  try {
    const shimSource = join(work, "src", "play", "test", "PlayJUnitExtension.java");
    mkdirSync(join(work, "src", "play", "test"), { recursive: true });
    writeFileSync(shimSource, NO_BOOT_EXTENSION);
    const jupiterApi = appJars.find((p) => /junit-jupiter-api-[^/]*\.jar$/.test(p));
    const javac = run("javac", ["-cp", jupiterApi ?? "", "-d", join(work, "shim"), shimSource]);
    if (javac.status !== 0) return [`could not compile the no-boot extension: ${javac.stderr}`];

    const junit5Plugin = pitJars.filter((p) => p.includes("pitest-junit5-plugin"));
    const classPathFile = join(work, "classpath.txt");
    writeFileSync(classPathFile, [join(work, "shim"), join(PROJECT_ROOT, "build", "classes", "java", "test"),
      ...appJars, ...junit5Plugin].join("\n"));

    const reportDir = join(report, "pit");
    rmSync(reportDir, { recursive: true, force: true });
    process.stderr.write("==> Running PIT...\n");
    const pit = run("java", ["-cp", pitJars.join(WINDOWS ? ";" : ":"),
      "org.pitest.mutationtest.commandline.MutationCoverageReport",
      "--reportDir", reportDir, "--timestampedReports=false", "--outputFormats", "HTML,XML",
      "--targetClasses", classes.flatMap((c) => [c, `${c}$*`]).join(","),
      "--targetTests", tests.join(","),
      "--sourceDirs", join(PROJECT_ROOT, "app"),
      "--classPathFile", classPathFile,
      "--jvmArgs=--enable-native-access=ALL-UNNAMED,--add-modules=jdk.incubator.vector,-Dplay.id=test",
      "--threads", WORKERS, "--skipFailingTests"], { stdio: ["ignore", "pipe", "pipe"] });
    const xml = join(reportDir, "mutations.xml");
    if (!existsSync(xml)) return [`PIT wrote no report (exit ${pit.status}):\n${pit.stdout.slice(-4000)}${pit.stderr.slice(-4000)}`];
    const summary = summarizePit(readFileSync(xml, "utf8"));
    if (summary.total > 0) process.stdout.write(`PIT: ${score(summary)} — ${join(reportDir, "index.html")}\n`);
    return harnessFaults(summary);
  } finally {
    rmSync(work, { recursive: true, force: true });
  }
}

const NOT_COPIED = new Set(["node_modules", ".nuxt", ".output", ".stryker-tmp", "coverage", "dist",
  "playwright-report", "reports", "test-report", "test-results"]);

function runStryker(files, report) {
  const frontend = join(PROJECT_ROOT, "frontend");
  if (!existsSync(join(frontend, "node_modules", "@stryker-mutator", "core"))) {
    return ["Stryker is not installed: run pnpm install in frontend/"];
  }
  const testSources = sources(join(frontend, "test"), /\.test\.ts$/, (p) => p.slice(frontend.length + 1));
  const plan = files.map((file) => ({ file, tests: testsImporting(file, testSources) }));
  for (const { file } of plan.filter((p) => p.tests.length === 0)) {
    process.stderr.write(`==> Stryker skipped ${file}: no test names it\n`);
  }
  const measured = plan.filter((p) => p.tests.length > 0);
  if (measured.length === 0) return [];

  // Other sessions share the checkout, and Stryker writes instrumented sources in place.
  const work = mkdtempSync(join(tmpdir(), "jclaw-stryker-"));
  const copy = join(work, "frontend");
  try {
    process.stderr.write(`==> Stryker: copying frontend/ to ${copy} and installing...\n`);
    cpSync(frontend, copy, { recursive: true, filter: (src) => !NOT_COPIED.has(src.slice(src.lastIndexOf("/") + 1)) });
    // nuxt.config imports the user guide from ../docs.
    symlinkSync(join(PROJECT_ROOT, "docs"), join(work, "docs"));
    const install = run("pnpm", ["install", "--frozen-lockfile", "--prefer-offline"], { cwd: copy });
    if (install.status !== 0) return [`pnpm install in the copy failed:\n${install.stdout}${install.stderr}`];

    const faults = [];
    for (const { file, tests } of measured) {
      const out = join(report, "stryker", file.replace(/\.(ts|vue)$/, "").replaceAll("/", "-"));
      rmSync(out, { recursive: true, force: true });
      const config = join(copy, "stryker.mutation.json");
      writeFileSync(config, JSON.stringify({
        testRunner: "command",
        // Stryker's in-place backup holds a copy of each test file; vitest's file filter would match it too.
        commandRunner: { command: `pnpm exec vitest run ${tests.join(" ")} --reporter=dot --exclude '**/.stryker-tmp/**'` },
        mutate: [file],
        coverageAnalysis: "off",
        inPlace: true,
        concurrency: Number(WORKERS),
        reporters: ["html", "json"],
        htmlReporter: { fileName: join(out, "mutation.html") },
        jsonReporter: { fileName: join(out, "mutation.json") },
      }));
      process.stderr.write(`==> Running Stryker on ${file} (${tests.join(", ")})...\n`);
      const stryker = run("pnpm", ["exec", "stryker", "run", config], { cwd: copy, stdio: ["ignore", "pipe", "pipe"] });
      const json = join(out, "mutation.json");
      if (!existsSync(json)) {
        faults.push(`Stryker wrote no report for ${file} (exit ${stryker.status}):\n${stryker.stdout.slice(-4000)}${stryker.stderr.slice(-4000)}`);
        continue;
      }
      const summary = summarizeStryker(JSON.parse(readFileSync(json, "utf8")));
      if (summary.total > 0) process.stdout.write(`Stryker ${file}: ${score(summary)} — ${join(out, "mutation.html")}\n`);
      faults.push(...harnessFaults(summary).map((f) => `${file}: ${f}`));
    }
    return faults;
  } finally {
    rmSync(work, { recursive: true, force: true });
  }
}

function main(argv) {
  if (argv[0] === "--help" || argv[0] === "-h") {
    process.stdout.write(`${USAGE}\n`);
    return 0;
  }
  if (argv.length !== 1 || argv[0].startsWith("-")) {
    process.stderr.write(`${USAGE}\n`);
    return 2;
  }
  let changed;
  try {
    changed = changedFiles(argv[0]);
  } catch (e) {
    process.stderr.write(`Error: ${e.message}\n`);
    return 2;
  }
  const classes = backendTargets(changed);
  const files = frontendTargets(changed);
  if (classes.length === 0 && files.length === 0) {
    process.stderr.write(`==> Nothing under app/ or frontend/ changed since ${argv[0]}.\n`);
    return 0;
  }
  mkdirSync(REPORTS, { recursive: true });
  const faults = [
    ...(classes.length > 0 ? runPit(classes, REPORTS) : []),
    ...(files.length > 0 ? runStryker(files, REPORTS) : []),
  ];
  for (const fault of faults) process.stderr.write(`Error: ${fault}\n`);
  return faults.length > 0 ? 2 : 0;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  process.exit(main(process.argv.slice(2)));
}
