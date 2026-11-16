#!/usr/bin/env node
// The rc number /release-check names its next candidate branch with, candidate/v<version>-rc.<N> (JF-471). Contract and
// exit codes: bin/README.md.

import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

import { jenkinsFrom } from "./candidate-vouch.mjs";

const JOB = "JClaw-candidate";
const TIMEOUT_MS = 10_000;
const VERSION = /^\d+\.\d+\.\d+$/;

/**
 * One past the highest rc of {@code version} among its candidate builds' names and its candidate branches on origin.
 * Both count, failed attempts included: a build is named only once its Setup runs, and a branch whose build never
 * started is kept, so no number shown once is used again.
 */
export function nextRc(buildNames, branchRefs, version) {
  const v = version.replaceAll(".", "\\.");
  const named = new RegExp(`^v${v}-rc\\.(\\d+)$`);
  const branch = new RegExp(`^refs/heads/candidate/v${v}-rc\\.(\\d+)$`);
  const used = [...buildNames.map((n) => n?.match(named)?.[1]), ...branchRefs.map((r) => r.match(branch)?.[1])];
  return 1 + Math.max(0, ...used.filter(Boolean).map(Number));
}

/** 0 and the number, or 2 when the version is malformed or Jenkins or origin cannot be asked. */
async function run(version, configPath, cwd) {
  if (!VERSION.test(version ?? "")) return { code: 2, message: "give the release's version, such as 1.0.22" };
  let jenkins;
  try {
    jenkins = jenkinsFrom(JSON.parse(readFileSync(configPath, "utf8")));
  } catch {
    jenkins = null;
  }
  if (!jenkins) return { code: 2, message: "no jenkins MCP server with an Authorization header in " + configPath };
  let names;
  try {
    const response = await fetch(`${jenkins.base}/job/${JOB}/api/json?tree=allBuilds[displayName]`, {
      headers: { Authorization: jenkins.authorization },
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
    if (!response.ok) return { code: 2, message: `Jenkins answered ${response.status}` };
    names = ((await response.json()).allBuilds ?? []).map((b) => b.displayName);
  } catch (error) {
    return { code: 2, message: `Jenkins could not be asked: ${error.message}` };
  }
  let refs;
  try {
    refs = execFileSync("git", ["ls-remote", "--heads", "origin", "refs/heads/candidate/*"],
      { cwd, encoding: "utf8", stdio: ["ignore", "pipe", "pipe"], timeout: 30_000 })
      .split("\n").filter(Boolean).map((line) => line.split("\t")[1]);
  } catch (error) {
    return { code: 2, message: `origin's candidate branches could not be listed: ${(error.stderr ?? error.message).trim()}` };
  }
  return { code: 0, message: `${nextRc(names, refs, version)}` };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const configPath = process.env.JCLAW_CLAUDE_JSON ?? join(homedir(), ".claude.json");
  const { code, message } = await run(process.argv[2], configPath, process.cwd());
  (code === 0 ? process.stdout : process.stderr).write(`${message}\n`);
  process.exit(code);
}
