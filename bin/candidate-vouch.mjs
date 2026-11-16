#!/usr/bin/env node
// Whether a green /release-check candidate build tested a commit, for .githooks/pre-push (JF-444). Contract and exit
// codes: bin/README.md.

import { readFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const JOB = "JClaw-candidate";
const TIMEOUT_MS = 10_000;
const SHA = /^[0-9a-f]{40}$/;

/**
 * Jenkins' address and its Authorization header, from the jenkins MCP server Claude Code is configured with: the
 * server's URL less its /mcp-server/mcp path, and the value of its --header argument. Null when it holds neither.
 */
export function jenkinsFrom(claudeJson) {
  const args = claudeJson?.mcpServers?.jenkins?.args;
  if (!Array.isArray(args)) return null;
  const header = args[args.indexOf("--header") + 1];
  const url = args.find((a) => typeof a === "string" && /^https?:\/\//.test(a));
  if (args.indexOf("--header") < 0 || typeof header !== "string" || !url) return null;
  const colon = header.indexOf(":");
  if (colon < 0 || header.slice(0, colon).trim().toLowerCase() !== "authorization") return null;
  return { base: url.replace(/\/mcp-server\/mcp\/?$/, ""), authorization: header.slice(colon + 1).trim() };
}

/**
 * The headers a POST to Jenkins needs: the Authorization header, and the crumb with the session cookie it was issued
 * under, since Jenkins checks the crumb against that session. A Jenkins with CSRF protection off issues none.
 */
export async function postHeaders(jenkins, contentType = "application/x-www-form-urlencoded") {
  const auth = { Authorization: jenkins.authorization };
  const crumbed = await fetch(`${jenkins.base}/crumbIssuer/api/json`,
    { headers: auth, redirect: "manual", signal: AbortSignal.timeout(30_000) });
  const headers = { ...auth, "Content-Type": contentType };
  if (crumbed.ok) {
    const crumb = await crumbed.json();
    headers[crumb.crumbRequestField] = crumb.crumb;
    const cookie = crumbed.headers.getSetCookie().map((c) => c.split(";")[0]).join("; ");
    if (cookie) headers.Cookie = cookie;
  }
  return headers;
}

/**
 * The build that vouches for {@code sha}: a finished, successful candidate build of it, which ran the whole suite
 * and Sonar on its tree. Its description is the record the Jenkinsfile writes; a push build of the same commit, which
 * may itself have skipped its tests on a candidate's word, does not count.
 */
export function vouchingBuild(builds, sha) {
  return (builds ?? []).find((b) => b.result === "SUCCESS" && b.building === false
    && b.description === `tested ${sha}` && (b.displayName ?? "").endsWith(`candidate ${sha.slice(0, 12)}`)) ?? null;
}

/** 0 and the build's name when one vouches, 1 when none does, 2 when Jenkins could not be asked. */
async function run(sha, configPath) {
  if (!SHA.test(sha ?? "")) return { code: 2, message: "give the commit's full sha" };
  let jenkins;
  try {
    jenkins = jenkinsFrom(JSON.parse(readFileSync(configPath, "utf8")));
  } catch {
    jenkins = null;
  }
  if (!jenkins) return { code: 2, message: "no jenkins MCP server with an Authorization header in " + configPath };
  const url = `${jenkins.base}/job/${JOB}/api/json?tree=builds[number,result,building,displayName,description]{0,30}`;
  let builds;
  try {
    const response = await fetch(url, {
      headers: { Authorization: jenkins.authorization },
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
    if (!response.ok) return { code: 2, message: `Jenkins answered ${response.status}` };
    builds = (await response.json()).builds;
  } catch (error) {
    return { code: 2, message: `Jenkins could not be asked: ${error.message}` };
  }
  const build = vouchingBuild(builds, sha);
  return build ? { code: 0, message: `#${build.number}` } : { code: 1, message: "no green candidate build tested it" };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const configPath = process.env.JCLAW_CLAUDE_JSON ?? join(homedir(), ".claude.json");
  const { code, message } = await run(process.argv[2], configPath);
  (code === 0 ? process.stdout : process.stderr).write(`${message}\n`);
  process.exit(code);
}
