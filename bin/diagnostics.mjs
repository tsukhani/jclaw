#!/usr/bin/env node
// Emits compile errors, test failures and ArchUnit violations as one JSON array so an
// agent repair loop consumes facts instead of console output. Schema, kind vocabulary
// and exit codes: bin/README.md.

import { spawnSync } from "node:child_process";
import { existsSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const PROJECT_ROOT = resolve(fileURLToPath(import.meta.url), "..", "..");
const TEST_RESULT_DIR = join(PROJECT_ROOT, "test-result");
const WINDOWS = process.platform === "win32";

const USAGE = `Usage: node bin/diagnostics.mjs [--tests] [--out FILE]

  --tests      also run 'play autotest' and report its failures
  --out FILE   write the JSON document there instead of stdout

Exit: 0 nothing to report, 1 diagnostics reported, 2 the harness itself failed.`;

// Gradle repeats every javac diagnostic inside its "* What went wrong:" block, indented
// by two spaces; the leading \S anchor is what keeps each error from being counted twice.
const JAVAC_HEADER = /^(\S.*?\.java):(\d+): (error|warning): (.*)$/;
const JAVAC_DETAIL = /^\s+(symbol|location|required|found|reason):\s*(.*)$/;
const JAVAC_SUGGESTION = /^\s*Did you mean (.+?)\?\s*$/;

// Warnings are dropped: a tree that compiles must produce an empty array, and that
// contract is only worth anything if a green build reliably satisfies it.
export function parseCompileDiagnostics(text) {
  const lines = text.split(/\r?\n/);
  const records = [];
  for (let i = 0; i < lines.length; i++) {
    const header = JAVAC_HEADER.exec(lines[i]);
    if (!header || header[3] !== "error") continue;

    const detail = [];
    let fix;
    for (let j = i + 1; j < lines.length && !JAVAC_HEADER.test(lines[j]); j++) {
      const part = JAVAC_DETAIL.exec(lines[j]);
      if (part) { detail.push(`${part[1]}: ${part[2].trim()}`); continue; }
      const suggestion = JAVAC_SUGGESTION.exec(lines[j]);
      if (suggestion) { fix = suggestion[1]; continue; }
      if (/^\d+ (error|warning)s?$/.test(lines[j])) break;
    }

    records.push({
      kind: "compile",
      file: repoRelative(header[1]),
      line: Number(header[2]),
      message: [header[4], ...detail].join(" — "),
      ...(fix === undefined ? {} : { fix }),
    });
  }
  return records;
}

const TESTCASE = /<testcase\b[^>]*\bname="([^"]*)"[^>]*>([\s\S]*?)<\/testcase>/g;
const FAILURE = /<(failure|error)\b[^>]*\bmessage="([^"]*)"[^>]*>([\s\S]*?)<\/\1>/;
// The CDATA body carries play.test.TestEngine's sourceInfos line, which points at the
// assertion that blew up rather than at the suite file.
const SOURCE_INFO = /In\s+(\S+\.java),\s+line\s+(\d+)/;
const ARCH_RULE = /Rule '([\s\S]*?)' was violated/;
const ARCH_SITE = /\((\w[\w$]*\.java):(\d+)\)\s*$/;

// `locate` resolves ArchUnit's bare `Foo.java` to a repo-relative path.
export function parseTestSuiteXml(xml, suite, locate = () => undefined) {
  const records = [];
  for (const [, name, body] of xml.matchAll(TESTCASE)) {
    const failure = FAILURE.exec(body);
    if (!failure) continue;

    const message = decodeXml(failure[2]);
    const source = SOURCE_INFO.exec(failure[3]);
    const file = source ? source[1].replace(/^\//, "") : locate(`${suite}.java`) ?? null;
    const line = source ? Number(source[2]) : null;

    // One ArchUnit assertion names every offending site; reporting it as a single test
    // failure would hand the loop a rule to read instead of places to edit.
    if (ARCH_RULE.test(message)) {
      records.push(...parseArchViolations(message, { file, line }, locate));
      continue;
    }
    records.push({ kind: "test", file, line, message: `${suite}.${decodeXml(name)}: ${message}` });
  }
  return records;
}

export function parseArchViolations(message, fallback, locate = () => undefined) {
  const rule = ARCH_RULE.exec(message);
  const violations = message.split("\n").slice(1).map((l) => l.trim()).filter(Boolean);
  return violations.map((violation) => {
    const site = ARCH_SITE.exec(violation);
    return {
      kind: "arch",
      file: site ? locate(site[1]) ?? site[1] : fallback.file,
      // ArchUnit writes :0 for a site it has no line for (a return type, a field
      // declaration) — that is absence, not line zero.
      line: site ? Number(site[2]) || null : fallback.line,
      message: rule ? `Rule '${rule[1]}' violated: ${violation}` : violation,
    };
  });
}

function readTestResults(locate) {
  if (!existsSync(TEST_RESULT_DIR)) return [];
  const records = [];
  for (const entry of readdirSync(TEST_RESULT_DIR).sort()) {
    const suite = /^TEST-(.+)\.xml$/.exec(entry);
    if (!suite) continue;
    const xml = readFileSync(join(TEST_RESULT_DIR, entry), "utf8");
    records.push(...parseTestSuiteXml(xml, suite[1], locate));
  }
  return records;
}

function decodeXml(text) {
  return text
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&#(\d+);/g, (_, code) => String.fromCharCode(Number(code)))
    .replace(/&amp;/g, "&");
}

function repoRelative(path) {
  const prefix = `${PROJECT_ROOT}/`;
  return path.startsWith(prefix) ? path.slice(prefix.length) : path;
}

// Indexes app/ and test/ by file name so ArchUnit's bare `Foo.java` becomes a path an
// editor can open. A name owned by two files resolves to nothing rather than to a guess.
function sourceLocator() {
  const index = new Map();
  const walk = (dir, prefix) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const path = join(dir, entry.name);
      if (entry.isDirectory()) walk(path, `${prefix}/${entry.name}`);
      else if (entry.name.endsWith(".java")) {
        index.set(entry.name, index.has(entry.name) ? null : `${prefix}/${entry.name}`);
      }
    }
  };
  for (const root of ["app", "test"]) {
    const dir = join(PROJECT_ROOT, root);
    if (existsSync(dir) && statSync(dir).isDirectory()) walk(dir, root);
  }
  return (name) => index.get(name) ?? undefined;
}

function fail(message) {
  process.stderr.write(`${message}\n`);
  return 2;
}

function main(argv) {
  let runTests = false;
  let out;
  for (let i = 0; i < argv.length; i++) {
    switch (argv[i]) {
      case "--tests": runTests = true; break;
      case "--out":
        out = argv[++i];
        if (out === undefined || out.startsWith("-")) return fail("Error: --out needs a file path");
        break;
      case "--help": case "-h": process.stdout.write(`${USAGE}\n`); return 0;
      default: return fail(`Error: unknown option '${argv[i]}'\n${USAGE}`);
    }
  }

  process.stderr.write("==> Compiling (./gradlew compileTestJava)...\n");
  const gradlew = join(PROJECT_ROOT, WINDOWS ? "gradlew.bat" : "gradlew");
  const compile = spawnSync(gradlew, ["compileTestJava", "--console=plain"],
    { cwd: PROJECT_ROOT, encoding: "utf8" });
  if (compile.error) return fail(`Error: could not run ${gradlew} — ${compile.error.message}`);
  const records = parseCompileDiagnostics(`${compile.stdout}${compile.stderr}`);
  // A build that fails for a reason javac never printed (a Gradle configuration error, a
  // missing framework) would otherwise be reported as a clean tree.
  if (compile.status !== 0 && records.length === 0) {
    process.stderr.write(`${compile.stdout}${compile.stderr}`);
    return fail("Error: the compile failed but emitted no javac diagnostic — see above.");
  }

  if (runTests && records.length > 0) {
    process.stderr.write("==> Skipping tests — the tree does not compile.\n");
  } else if (runTests) {
    // A class deleted since the last run leaves its report behind and play never purges
    // it, so a stale report would be read back as a live failure.
    for (const entry of existsSync(TEST_RESULT_DIR) ? readdirSync(TEST_RESULT_DIR) : []) {
      if (/^TEST-.+\.xml$/.test(entry)) rmSync(join(TEST_RESULT_DIR, entry));
    }
    process.stderr.write("==> Running backend tests (play autotest)...\n");
    const tests = spawnSync("play", ["autotest"],
      { cwd: PROJECT_ROOT, stdio: ["ignore", 2, 2], shell: WINDOWS });
    if (tests.error) return fail(`Error: could not run 'play autotest' — ${tests.error.message}`);
    records.push(...readTestResults(sourceLocator()));
    if (tests.status !== 0 && records.length === 0) {
      return fail("Error: 'play autotest' failed but no test report records a failure.");
    }
  }

  const json = `${JSON.stringify(records, null, 2)}\n`;
  if (out) {
    writeFileSync(out, json);
    process.stderr.write(`==> ${records.length} diagnostic(s) written to ${out}\n`);
  } else {
    process.stdout.write(json);
  }
  return records.length === 0 ? 0 : 1;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  process.exit(main(process.argv.slice(2)));
}
