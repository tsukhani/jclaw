#!/usr/bin/env node
// Computes SonarQube-style blended coverage from jacoco.xml + lcov.info.
// Coverage = (covered_lines + covered_conditions) / (lines_to_cover + conditions_to_cover).
// Honors sonar.exclusions + sonar.coverage.exclusions from build.gradle.kts.

import { readFileSync, existsSync } from "node:fs";
import { resolve, relative, sep, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const PROJECT_ROOT = resolve(fileURLToPath(import.meta.url), "..", "..");

const SONAR_EXCLUSIONS = [
  "**/node_modules/**", "**/dist/**", "**/.nuxt/**", "**/.output/**",
  "**/public/spa/**", "**/precompiled/**", "**/workspace/**",
  "**/skills/**", "app/views/**", "frontend/test/**",
  "**/*.md", "**/*.txt", "**/*.sh", "**/*.xml", "**/*.yaml", "**/*.yml",
  "**/*.properties", "**/*.sql",
];

const SONAR_COVERAGE_EXCLUSIONS = [
  "frontend/components/ui/**",
  "frontend/tests/e2e/**",
  "app/services/LoadTestRunner.java",
  "app/tools/LoadTestSleepTool.java",
  "frontend/types/schemas.ts",
];

const ALL_EXCLUSIONS = [...SONAR_EXCLUSIONS, ...SONAR_COVERAGE_EXCLUSIONS];

function antGlobToRegex(glob) {
  let re = "^";
  let i = 0;
  while (i < glob.length) {
    const c = glob[i];
    if (c === "*" && glob[i + 1] === "*") {
      const next = glob[i + 2];
      if (next === "/") { re += "(?:.*/)?"; i += 3; }
      else { re += ".*"; i += 2; }
    } else if (c === "*") {
      re += "[^/]*"; i++;
    } else if (c === "?") {
      re += "[^/]"; i++;
    } else if (".+()[]{}|^$\\".includes(c)) {
      re += "\\" + c; i++;
    } else {
      re += c; i++;
    }
  }
  re += "$";
  return new RegExp(re);
}

const EXCLUSION_REGEXES = ALL_EXCLUSIONS.map(antGlobToRegex);

function isExcluded(path) {
  const normalized = path.split(sep).join("/");
  return EXCLUSION_REGEXES.some((re) => re.test(normalized));
}

function parseJacocoXml(xmlPath) {
  if (!existsSync(xmlPath)) return { files: [], present: false };
  const xml = readFileSync(xmlPath, "utf8");
  const files = [];

  const pkgRe = /<package\s+name="([^"]+)">([\s\S]*?)<\/package>/g;
  let pkgMatch;
  while ((pkgMatch = pkgRe.exec(xml)) !== null) {
    const pkgPath = pkgMatch[1];
    const pkgBody = pkgMatch[2];
    const sfRe = /<sourcefile\s+name="([^"]+)">([\s\S]*?)<\/sourcefile>/g;
    let sfMatch;
    while ((sfMatch = sfRe.exec(pkgBody)) !== null) {
      const fileName = sfMatch[1];
      const sfBody = sfMatch[2];
      const counters = { LINE: { missed: 0, covered: 0 }, BRANCH: { missed: 0, covered: 0 } };
      const counterRe = /<counter\s+type="(LINE|BRANCH)"\s+missed="(\d+)"\s+covered="(\d+)"\s*\/>/g;
      let cMatch;
      while ((cMatch = counterRe.exec(sfBody)) !== null) {
        counters[cMatch[1]] = { missed: +cMatch[2], covered: +cMatch[3] };
      }
      // Java sources live under app/ per sonar.sources; package paths are slash-delimited in the XML.
      const sourcePath = `app/${pkgPath}/${fileName}`;
      files.push({
        path: sourcePath,
        linesToCover: counters.LINE.missed + counters.LINE.covered,
        coveredLines: counters.LINE.covered,
        conditionsToCover: counters.BRANCH.missed + counters.BRANCH.covered,
        coveredConditions: counters.BRANCH.covered,
      });
    }
  }
  return { files, present: true };
}

function parseLcovInfo(lcovPath, baseDir) {
  if (!existsSync(lcovPath)) return { files: [], present: false };
  const text = readFileSync(lcovPath, "utf8");
  const files = [];
  let current = null;

  for (const rawLine of text.split("\n")) {
    const line = rawLine.trim();
    if (line.startsWith("SF:")) {
      const raw = line.slice(3);
      // Vitest emits SF paths relative to its cwd (frontend/), not the
      // project root. Resolve against baseDir, then re-relativize to root.
      const abs = raw.startsWith("/") ? raw : resolve(baseDir, raw);
      const rel = relative(PROJECT_ROOT, abs);
      current = {
        path: rel.split(sep).join("/"),
        linesToCover: 0, coveredLines: 0,
        conditionsToCover: 0, coveredConditions: 0,
      };
    } else if (!current) {
      continue;
    } else if (line.startsWith("LF:")) {
      current.linesToCover = +line.slice(3);
    } else if (line.startsWith("LH:")) {
      current.coveredLines = +line.slice(3);
    } else if (line.startsWith("BRF:")) {
      current.conditionsToCover = +line.slice(4);
    } else if (line.startsWith("BRH:")) {
      current.coveredConditions = +line.slice(4);
    } else if (line === "end_of_record") {
      files.push(current);
      current = null;
    }
  }
  return { files, present: true };
}

function summarize(files) {
  const t = { linesToCover: 0, coveredLines: 0, conditionsToCover: 0, coveredConditions: 0 };
  for (const f of files) {
    t.linesToCover += f.linesToCover;
    t.coveredLines += f.coveredLines;
    t.conditionsToCover += f.conditionsToCover;
    t.coveredConditions += f.coveredConditions;
  }
  return t;
}

function pct(num, den) { return den === 0 ? 0 : (num / den) * 100; }

function blendedCoverage(t) {
  const num = t.coveredLines + t.coveredConditions;
  const den = t.linesToCover + t.conditionsToCover;
  return { num, den, percent: pct(num, den) };
}

function fmt(n) { return n.toFixed(1); }

function main() {
  const args = process.argv.slice(2);
  const verbose = args.includes("--verbose") || args.includes("-v");
  const jsonOut = args.includes("--json");
  const jacocoArg = (args.find((a) => a.startsWith("--jacoco=")) || "").slice(9);
  const lcovArg = (args.find((a) => a.startsWith("--lcov=")) || "").slice(7);
  const lcovBaseArg = (args.find((a) => a.startsWith("--lcov-base=")) || "").slice(12);

  const jacocoPath = resolve(PROJECT_ROOT, jacocoArg || "jacoco.xml");
  const lcovPath = resolve(PROJECT_ROOT, lcovArg || "frontend/coverage/lcov.info");
  // Vitest runs from frontend/, so lcov SF paths are relative to it.
  // Default to the parent of the lcov file's parent (frontend/coverage/lcov.info -> frontend/).
  const lcovBaseDir = resolve(PROJECT_ROOT, lcovBaseArg || dirname(dirname(lcovPath)));

  const jacoco = parseJacocoXml(jacocoPath);
  const lcov = parseLcovInfo(lcovPath, lcovBaseDir);

  if (!jacoco.present && !lcov.present) {
    console.error(`error: neither report found\n  jacoco: ${jacocoPath}\n  lcov:   ${lcovPath}`);
    process.exit(2);
  }

  const allFiles = [...jacoco.files, ...lcov.files];
  const included = [];
  const excluded = [];
  for (const f of allFiles) {
    (isExcluded(f.path) ? excluded : included).push(f);
  }

  const backend = included.filter((f) => f.path.startsWith("app/"));
  const frontend = included.filter((f) => f.path.startsWith("frontend/"));

  const totals = summarize(included);
  const backendTotals = summarize(backend);
  const frontendTotals = summarize(frontend);

  const blend = blendedCoverage(totals);
  const result = {
    coverage_percent: +blend.percent.toFixed(2),
    line_coverage_percent: +pct(totals.coveredLines, totals.linesToCover).toFixed(2),
    branch_coverage_percent: +pct(totals.coveredConditions, totals.conditionsToCover).toFixed(2),
    totals,
    backend: { totals: backendTotals, files: backend.length },
    frontend: { totals: frontendTotals, files: frontend.length },
    excluded_files: excluded.length,
    reports: {
      jacoco: { path: jacocoPath, present: jacoco.present, files: jacoco.files.length },
      lcov: { path: lcovPath, present: lcov.present, files: lcov.files.length },
    },
  };

  if (jsonOut) {
    console.log(JSON.stringify(result, null, 2));
    return;
  }

  console.log("=== SonarQube-style blended coverage ===");
  console.log(`Combined: ${fmt(blend.percent)}%  (${blend.num} / ${blend.den})`);
  console.log(`  Line coverage:   ${fmt(pct(totals.coveredLines, totals.linesToCover))}%`
    + `  (${totals.coveredLines} / ${totals.linesToCover})`);
  console.log(`  Branch coverage: ${fmt(pct(totals.coveredConditions, totals.conditionsToCover))}%`
    + `  (${totals.coveredConditions} / ${totals.conditionsToCover})`);
  console.log("");
  console.log(`Backend (jacoco, ${backend.length} files):`);
  console.log(`  Line:   ${fmt(pct(backendTotals.coveredLines, backendTotals.linesToCover))}%`
    + `  (${backendTotals.coveredLines} / ${backendTotals.linesToCover})`);
  console.log(`  Branch: ${fmt(pct(backendTotals.coveredConditions, backendTotals.conditionsToCover))}%`
    + `  (${backendTotals.coveredConditions} / ${backendTotals.conditionsToCover})`);
  console.log("");
  console.log(`Frontend (lcov, ${frontend.length} files):`);
  console.log(`  Line:   ${fmt(pct(frontendTotals.coveredLines, frontendTotals.linesToCover))}%`
    + `  (${frontendTotals.coveredLines} / ${frontendTotals.linesToCover})`);
  console.log(`  Branch: ${fmt(pct(frontendTotals.coveredConditions, frontendTotals.conditionsToCover))}%`
    + `  (${frontendTotals.coveredConditions} / ${frontendTotals.conditionsToCover})`);
  console.log("");
  console.log(`Excluded files: ${excluded.length}`);
  if (verbose) {
    for (const f of excluded) console.log(`  - ${f.path}`);
  }
  if (!jacoco.present) console.log(`\nWARN: jacoco.xml missing at ${jacocoPath} — backend side absent.`);
  if (!lcov.present) console.log(`\nWARN: lcov.info missing at ${lcovPath} — frontend side absent.`);
}

main();
