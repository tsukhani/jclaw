#!/usr/bin/env node
// Starts a /release-check candidate build that runs the candidate's own Jenkinsfile, by replaying a recent build
// (JF-445). Contract and exit codes: bin/README.md.

import { spawnSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

import { jenkinsFrom } from "./candidate-vouch.mjs";

const JOB = "JClaw";
const TIMEOUT_MS = 30_000;
const SHA = /^[0-9a-f]{40}$/;
const VERSION = /^\d+\.\d+\.\d+$/;

/**
 * {@code jenkinsfile} with each read of the two candidate parameters replaced by the candidate's values, so a replay
 * runs candidate mode whatever parameters the replayed build carries, and the job's parameter definitions stay as
 * they are. Throws when the Jenkinsfile reads neither, or a read is left that is not the whole parameter's name.
 */
export function candidateScript(jenkinsfile, sha, version) {
  const versionReads = /params\.CANDIDATE_VERSION\b/g;
  const shaReads = /params\.CANDIDATE\b/g;
  if (!versionReads.test(jenkinsfile) || !shaReads.test(jenkinsfile)) {
    throw new Error("the Jenkinsfile reads no candidate parameters, so it has no candidate mode to replay");
  }
  const script = jenkinsfile.replace(versionReads, `'${version}'`).replace(shaReads, `'${sha}'`);
  if (/params\.CANDIDATE/.test(script)) throw new Error("the Jenkinsfile reads a candidate parameter this cannot replace");
  return script;
}

const parameter = (build, name) => (build.actions ?? [])
  .flatMap((a) => a?.parameters ?? []).find((p) => p.name === name)?.value;

/** The newest finished build a candidate can replay: one neither releasing nor publishing the dev container. */
export function replayBase(builds) {
  return (builds ?? []).find((b) => b.building === false
    && parameter(b, "RELEASE") !== true && parameter(b, "PUBLISH_DEVCONTAINER") !== true) ?? null;
}

async function call(url, init) {
  return fetch(url, { ...init, redirect: "manual", signal: AbortSignal.timeout(TIMEOUT_MS) });
}

/** 0 when Jenkins took the replay, 2 when it did not or could not be asked. */
async function run(sha, version, configPath, cwd) {
  if (!SHA.test(sha ?? "") || !VERSION.test(version ?? "")) {
    return { code: 2, message: "give the candidate's full sha and the version it becomes, such as 1.0.20" };
  }
  const shown = spawnSync("git", ["show", `${sha}:Jenkinsfile`], { cwd, encoding: "utf8" });
  if (shown.status !== 0) return { code: 2, message: `no Jenkinsfile at ${sha}` };
  let script;
  try {
    script = candidateScript(shown.stdout, sha, version);
  } catch (error) {
    return { code: 2, message: error.message };
  }
  let jenkins;
  try {
    jenkins = jenkinsFrom(JSON.parse(readFileSync(configPath, "utf8")));
  } catch {
    jenkins = null;
  }
  if (!jenkins) return { code: 2, message: "no jenkins MCP server with an Authorization header in " + configPath };
  const auth = { Authorization: jenkins.authorization };
  try {
    const listed = await call(`${jenkins.base}/job/${JOB}/api/json?tree=builds[number,building,actions[parameters[name,value]]]{0,30}`,
      { headers: auth });
    if (!listed.ok) return { code: 2, message: `Jenkins answered ${listed.status} for the builds` };
    const base = replayBase((await listed.json()).builds);
    if (!base) return { code: 2, message: "no finished build without RELEASE or PUBLISH_DEVCONTAINER to replay" };
    const crumbed = await call(`${jenkins.base}/crumbIssuer/api/json`, { headers: auth });
    const headers = { ...auth, "Content-Type": "application/x-www-form-urlencoded" };
    if (crumbed.ok) {
      const crumb = await crumbed.json();
      headers[crumb.crumbRequestField] = crumb.crumb;
      const cookie = crumbed.headers.getSetCookie().map((c) => c.split(";")[0]).join("; ");
      if (cookie) headers.Cookie = cookie;
    }
    const replayed = await call(`${jenkins.base}/job/${JOB}/${base.number}/replay/run`, {
      method: "POST", headers, body: new URLSearchParams({ json: JSON.stringify({ mainScript: script }) }),
    });
    if (replayed.status !== 302 && replayed.status !== 303) {
      return { code: 2, message: `Jenkins answered ${replayed.status} to the replay of #${base.number}` };
    }
    return { code: 0, message: `replayed #${base.number} as candidate ${sha.slice(0, 12)} for v${version}` };
  } catch (error) {
    return { code: 2, message: `Jenkins could not be asked: ${error.message}` };
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const configPath = process.env.JCLAW_CLAUDE_JSON ?? join(homedir(), ".claude.json");
  const { code, message } = await run(process.argv[2], process.argv[3], configPath, process.cwd());
  (code === 0 ? process.stdout : process.stderr).write(`${message}\n`);
  process.exit(code);
}
