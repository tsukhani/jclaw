#!/usr/bin/env node
// Un-keeps the green /release-check candidate builds no release will publish, so their zips fall back under the job's
// artifact retention (JF-449). Contract and exit codes: bin/README.md.

import { readFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

import { jenkinsFrom, postHeaders } from "./candidate-vouch.mjs";

// JClaw holds the candidates built before JF-453 gave them a job of their own, and a later release un-keeps them too.
export const JOBS = ["JClaw-candidate", "JClaw"];
const TIMEOUT_MS = 30_000;
const SHA = /^[0-9a-f]{40}$/;
const USAGE = "give --keep <full sha> to un-keep every other candidate, or --drop <full sha> to un-keep that one";
const CANDIDATE = /candidate ([0-9a-f]{12})$/;

/**
 * The kept candidate builds to un-keep: with {@code keep}, every one but the build of that commit, which the release
 * on main still needs; with {@code drop}, the build of that commit alone.
 */
export function toUnkeep(builds, { keep, drop }) {
  return (builds ?? []).filter((b) => {
    const short = (b.displayName ?? "").match(CANDIDATE)?.[1];
    if (b.keepLog !== true || !short) return false;
    return keep ? short !== keep.slice(0, 12) : short === drop.slice(0, 12);
  });
}

function parse(args) {
  if (args.length !== 2 || !SHA.test(args[1])) return null;
  if (args[0] === "--keep") return { keep: args[1] };
  if (args[0] === "--drop") return { drop: args[1] };
  return null;
}

/** 0 when every build it chose is un-kept, 2 when one is not or Jenkins could not be asked. */
async function run(args, configPath) {
  const choice = parse(args);
  if (!choice) return { code: 2, lines: [USAGE] };
  let jenkins;
  try {
    jenkins = jenkinsFrom(JSON.parse(readFileSync(configPath, "utf8")));
  } catch {
    jenkins = null;
  }
  if (!jenkins) return { code: 2, lines: ["no jenkins MCP server with an Authorization header in " + configPath] };
  const call = (url, init = {}) => fetch(url, { ...init, redirect: "manual", signal: AbortSignal.timeout(TIMEOUT_MS) });
  const lines = [];
  try {
    const doomed = [];
    for (const job of JOBS) {
      const listed = await call(`${jenkins.base}/job/${job}/api/json?tree=builds[number,displayName,keepLog]`,
        { headers: { Authorization: jenkins.authorization } });
      if (!listed.ok) return { code: 2, lines: [`Jenkins answered ${listed.status} for ${job}'s builds`] };
      doomed.push(...toUnkeep((await listed.json()).builds, choice).map((build) => ({ job, build })));
    }
    if (doomed.length === 0) return { code: 0, lines: ["No kept candidate build to un-keep."] };
    const headers = await postHeaders(jenkins);
    for (const { job, build } of doomed) {
      // Jenkins only toggles, so each build is one it just listed as kept.
      const toggled = await call(`${jenkins.base}/job/${job}/${build.number}/toggleLogKeep`, { method: "POST", headers });
      if (toggled.status !== 302 && toggled.status !== 303 && !toggled.ok) {
        lines.push(`Jenkins answered ${toggled.status} to un-keeping ${job} #${build.number}`);
        return { code: 2, lines };
      }
      lines.push(`Un-kept ${job} #${build.number} (${build.displayName})`);
    }
    return { code: 0, lines };
  } catch (error) {
    lines.push(`Jenkins could not be asked: ${error.message}`);
    return { code: 2, lines };
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const configPath = process.env.JCLAW_CLAUDE_JSON ?? join(homedir(), ".claude.json");
  const { code, lines } = await run(process.argv.slice(2), configPath);
  (code === 0 ? process.stdout : process.stderr).write(`${lines.join("\n")}\n`);
  process.exit(code);
}
