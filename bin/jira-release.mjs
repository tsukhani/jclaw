#!/usr/bin/env node
// Records a release in Jira for /deploy: the tickets a range of commits ships, the release as their Fix Version, and
// Done for those still in Review (JF-462). Contract and exit codes: bin/README.md.

import { spawnSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const PROJECT = "JF";
const TIMEOUT_MS = 30_000;
const VERSION = /^v\d+\.\d+\.\d+$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;
const KEY = /^JF-\d+$/;
const USAGE = "give tickets <from> <to>, record <vX.Y.Z> <YYYY-MM-DD> <description> [<key>…], or done <vX.Y.Z> <key>…";

/**
 * Jira's address and token, from the env of the jira-confluence MCP server Claude Code is configured with, so this
 * holds no secret of its own. Null when it has neither.
 */
export function jiraFrom(claudeJson) {
  const env = claudeJson?.mcpServers?.["jira-confluence"]?.env;
  if (typeof env?.JIRA_URL !== "string" || !/^https?:\/\//.test(env.JIRA_URL)) return null;
  if (typeof env.JIRA_PERSONAL_TOKEN !== "string" || !env.JIRA_PERSONAL_TOKEN) return null;
  return { base: env.JIRA_URL.replace(/\/+$/, ""), token: env.JIRA_PERSONAL_TOKEN };
}

/** The JF keys the commit subjects name, each once, in number order: a ticket's commits put its key in the subject. */
export function ticketKeys(subjects) {
  const numbers = new Set();
  for (const subject of subjects) {
    for (const match of subject.matchAll(/\bJF-(\d+)\b/g)) numbers.add(Number(match[1]));
  }
  return [...numbers].sort((a, b) => a - b).map((n) => `${PROJECT}-${n}`);
}

function client(jira) {
  const headers = { Authorization: `Bearer ${jira.token}`, Accept: "application/json", "Content-Type": "application/json" };
  return async (method, path, body) => {
    const response = await fetch(jira.base + path, {
      method, headers, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(TIMEOUT_MS),
    });
    const text = await response.text();
    return { status: response.status, json: () => JSON.parse(text) };
  };
}

/** Creates the release's version if Jira has none by that name, then adds it to each ticket's Fix Versions. */
async function record(call, [version, date, description, ...keys]) {
  const lines = [];
  const listed = await call("GET", `/rest/api/2/project/${PROJECT}/versions`);
  if (listed.status !== 200) return { code: 2, lines: [`Jira answered ${listed.status} for ${PROJECT}'s versions`] };
  if (listed.json().some((v) => v.name === version)) {
    lines.push(`Version ${version} exists.`);
  } else {
    const created = await call("POST", "/rest/api/2/version",
      { name: version, project: PROJECT, released: true, releaseDate: date, description });
    if (created.status !== 201) return { code: 2, lines: [`Jira answered ${created.status} to creating ${version}`] };
    lines.push(`Created version ${version}, released ${date}.`);
  }
  for (const key of keys) {
    // An add, not a set: a ticket a later release also touches keeps the version it first shipped in.
    const added = await call("PUT", `/rest/api/2/issue/${key}`, { update: { fixVersions: [{ add: { name: version } }] } });
    if (added.status !== 204) {
      lines.push(`Jira answered ${added.status} to adding ${version} to ${key}`);
      return { code: 2, lines };
    }
    lines.push(`${key}: Fix Version ${version}.`);
  }
  return { code: 0, lines };
}

/**
 * Moves each ticket in Review that carries the release as a Fix Version to Done, by the transition that leads there,
 * and leaves every other one alone: a key passed by mistake, of a ticket this release did not record, stays put.
 */
async function done(call, [version, ...keys]) {
  const lines = [];
  for (const key of keys) {
    const issue = await call("GET", `/rest/api/2/issue/${key}?fields=status,fixVersions`);
    if (issue.status !== 200) {
      lines.push(`Jira answered ${issue.status} for ${key}`);
      return { code: 2, lines };
    }
    const fields = issue.json().fields ?? {};
    const status = fields.status?.name;
    if (status !== "Review") {
      lines.push(`${key}: left as ${status}.`);
      continue;
    }
    if (!(fields.fixVersions ?? []).some((v) => v.name === version)) {
      lines.push(`${key}: left in Review, since it carries no Fix Version ${version}.`);
      continue;
    }
    const offered = await call("GET", `/rest/api/2/issue/${key}/transitions`);
    const toDone = offered.status === 200 ? offered.json().transitions?.find((t) => t.to?.name === "Done") : undefined;
    if (!toDone) {
      lines.push(`Jira offers ${key} no transition to Done (answered ${offered.status})`);
      return { code: 2, lines };
    }
    const moved = await call("POST", `/rest/api/2/issue/${key}/transitions`, { transition: { id: toDone.id } });
    if (moved.status !== 204) {
      lines.push(`Jira answered ${moved.status} to moving ${key} to Done`);
      return { code: 2, lines };
    }
    lines.push(`${key}: Review to Done.`);
  }
  return { code: 0, lines };
}

/** 0 when everything asked was done, 2 when an argument was malformed or Jira refused or could not be asked. */
export async function run(args, { configPath, cwd }) {
  const [command, ...rest] = args;
  if (command === "tickets") {
    if (rest.length !== 2) return { code: 2, lines: [USAGE] };
    const log = spawnSync("git", ["log", "--format=%s", `${rest[0]}..${rest[1]}`], { cwd, encoding: "utf8" });
    if (log.status !== 0) return { code: 2, lines: [`git log ${rest[0]}..${rest[1]} failed: ${log.stderr.trim()}`] };
    return { code: 0, lines: [ticketKeys(log.stdout.split("\n")).join(" ")] };
  }
  const recordArgs = command === "record" && rest.length >= 3 && VERSION.test(rest[0]) && DATE.test(rest[1])
    && rest[2].trim() !== "" && rest.slice(3).every((k) => KEY.test(k));
  const doneArgs = command === "done" && rest.length >= 2 && VERSION.test(rest[0]) && rest.slice(1).every((k) => KEY.test(k));
  if (!recordArgs && !doneArgs) return { code: 2, lines: [USAGE] };
  let jira;
  try {
    jira = jiraFrom(JSON.parse(readFileSync(configPath, "utf8")));
  } catch {
    jira = null;
  }
  if (!jira) return { code: 2, lines: [`no jira-confluence MCP server with JIRA_URL and JIRA_PERSONAL_TOKEN in ${configPath}`] };
  try {
    return await (recordArgs ? record(client(jira), rest) : done(client(jira), rest));
  } catch (error) {
    return { code: 2, lines: [`Jira could not be asked: ${error.message}`] };
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const configPath = process.env.JCLAW_CLAUDE_JSON ?? join(homedir(), ".claude.json");
  const { code, lines } = await run(process.argv.slice(2), { configPath, cwd: process.cwd() });
  (code === 0 ? process.stdout : process.stderr).write(`${lines.join("\n")}\n`);
  process.exit(code);
}
