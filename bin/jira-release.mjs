#!/usr/bin/env node
// Records a release in Jira for /deploy: the tickets a range of commits ships, the release as their Fix Version, and
// Done for those still in Review (JF-462). Closes a ticket no commit names, with its reason, and audits which tickets
// are out of step with main (JF-504). Contract and exit codes: bin/README.md.

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
const USAGE = "give tickets <from> <to>, record <vX.Y.Z> <YYYY-MM-DD> <description> [<key>…], done <vX.Y.Z> <key>…, "
  + "close <key> <reason>, or audit [<from>]";
const PAGE = 100;

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
    const failed = await moveToDone(call, key);
    if (failed) {
      lines.push(failed);
      return { code: 2, lines };
    }
    lines.push(`${key}: Review to Done.`);
  }
  return { code: 0, lines };
}

/** Moves the ticket to Done by the transition that leads there: null when it moved, else what Jira answered. */
async function moveToDone(call, key) {
  const offered = await call("GET", `/rest/api/2/issue/${key}/transitions`);
  const toDone = offered.status === 200 ? offered.json().transitions?.find((t) => t.to?.name === "Done") : undefined;
  if (!toDone) return `Jira offers ${key} no transition to Done (answered ${offered.status})`;
  const moved = await call("POST", `/rest/api/2/issue/${key}/transitions`, { transition: { id: toDone.id } });
  return moved.status === 204 ? null : `Jira answered ${moved.status} to moving ${key} to Done`;
}

/**
 * Closes a ticket no commit names (a spike, a duplicate, a verification task, a won't-do): the reason goes on it as a
 * comment first, since a transition drops the comment it is given, then it moves to Done. The caller has checked git.
 */
async function close(call, [key, reason]) {
  const issue = await call("GET", `/rest/api/2/issue/${key}?fields=status`);
  if (issue.status !== 200) return { code: 2, lines: [`Jira answered ${issue.status} for ${key}`] };
  const status = issue.json().fields?.status?.name;
  if (status === "Done") return { code: 0, lines: [`${key}: left as Done.`] };
  const posted = await call("POST", `/rest/api/2/issue/${key}/comment`,
    { body: `Closed without a release, since no commit names ${key}: ${reason}` });
  if (posted.status !== 201) return { code: 2, lines: [`Jira answered ${posted.status} to posting the reason on ${key}`] };
  const failed = await moveToDone(call, key);
  return failed ? { code: 2, lines: [failed] } : { code: 0, lines: [`${key}: ${status} to Done, with the reason posted.`] };
}

const releasedIn = (fields) => (fields?.fixVersions ?? []).filter((v) => v.released).map((v) => v.name);

/**
 * Lists each ticket out of step with main and changes nothing: one the range's subjects name belongs in Review until
 * a release carries it, and one in Review belongs to the range. A Done ticket a release already carries may be named
 * again by a follow-up commit.
 */
async function audit(call, { from, keys }) {
  const lines = [];
  for (const key of keys) {
    const issue = await call("GET", `/rest/api/2/issue/${key}?fields=status,fixVersions`);
    if (issue.status === 404) {
      lines.push(`${key}: named by a commit since ${from}, but Jira has no such ticket.`);
      continue;
    }
    if (issue.status !== 200) return { code: 2, lines: [`Jira answered ${issue.status} for ${key}`] };
    const fields = issue.json().fields ?? {};
    const status = fields.status?.name;
    if (status === "Done") {
      if (releasedIn(fields).length === 0) lines.push(`${key}: Done, though no release carries the commit since ${from} that names it.`);
    } else if (status !== "Review") {
      lines.push(`${key}: ${status}, though a commit since ${from} names it.`);
    }
  }
  const named = new Set(keys);
  const jql = encodeURIComponent(`project = ${PROJECT} AND status = Review ORDER BY key ASC`);
  for (let startAt = 0, total = 1; startAt < total;) {
    const page = await call("GET", `/rest/api/2/search?jql=${jql}&fields=fixVersions&startAt=${startAt}&maxResults=${PAGE}`);
    if (page.status !== 200) return { code: 2, lines: [`Jira answered ${page.status} for the tickets in Review`] };
    const { issues = [], total: all = 0 } = page.json();
    for (const { key, fields } of issues) {
      if (named.has(key)) continue;
      const released = releasedIn(fields);
      lines.push(released.length > 0 ? `${key}: in Review, though ${released.join(", ")} released it.`
        : `${key}: in Review, though no commit since ${from} names it.`);
    }
    if (issues.length === 0) break;
    startAt += issues.length;
    total = all;
  }
  if (lines.length > 0) return { code: 1, lines };
  return { code: 0, lines: [`Every ticket named since ${from} is in Review, and every ticket in Review is named since then.`] };
}

function subjectsOf(cwd, revisions) {
  const log = spawnSync("git", ["log", "--format=%s", ...revisions], { cwd, encoding: "utf8" });
  return log.status === 0 ? { subjects: log.stdout.split("\n") }
    : { error: `git log ${revisions.join(" ")} failed: ${log.stderr.trim()}` };
}

// What close and audit read from git before Jira is asked: a refusal or a failure as a result, else what they need.
function fromGit(command, rest, cwd) {
  if (command === "close") {
    // Branches and their remote copies, not the stash or other refs: a commit on a branch is work that ships.
    const log = subjectsOf(cwd, ["--branches", "--remotes"]);
    if (log.error) return { result: { code: 2, lines: [log.error] } };
    const naming = log.subjects.find((s) => ticketKeys([s]).includes(rest[0]));
    if (naming === undefined) return {};
    return { result: { code: 1, lines: [`${rest[0]}: not closed, since "${naming}" names it: `
      + "/deploy moves it to Done when a release ships it."] } };
  }
  let from = rest[0];
  if (from === undefined) {
    const tag = spawnSync("git", ["describe", "--tags", "--abbrev=0", "--match", "v*", "HEAD"], { cwd, encoding: "utf8" });
    if (tag.status !== 0) return { result: { code: 2, lines: [`no version tag to start from: ${tag.stderr.trim()}`] } };
    from = tag.stdout.trim();
  }
  const log = subjectsOf(cwd, [`${from}..HEAD`]);
  if (log.error) return { result: { code: 2, lines: [log.error] } };
  return { range: { from, keys: ticketKeys(log.subjects) } };
}

/**
 * 0 when everything asked was done or audit found everything in step, 1 when close refused or audit listed a ticket,
 * 2 when an argument was malformed or git or Jira refused or could not be asked.
 */
export async function run(args, { configPath, cwd }) {
  const [command, ...rest] = args;
  if (command === "tickets") {
    if (rest.length !== 2) return { code: 2, lines: [USAGE] };
    const log = subjectsOf(cwd, [`${rest[0]}..${rest[1]}`]);
    return log.error ? { code: 2, lines: [log.error] } : { code: 0, lines: [ticketKeys(log.subjects).join(" ")] };
  }
  const recordArgs = command === "record" && rest.length >= 3 && VERSION.test(rest[0]) && DATE.test(rest[1])
    && rest[2].trim() !== "" && rest.slice(3).every((k) => KEY.test(k));
  const doneArgs = command === "done" && rest.length >= 2 && VERSION.test(rest[0]) && rest.slice(1).every((k) => KEY.test(k));
  const closeArgs = command === "close" && rest.length === 2 && KEY.test(rest[0]) && rest[1].trim() !== "";
  const auditArgs = command === "audit" && rest.length <= 1;
  if (!recordArgs && !doneArgs && !closeArgs && !auditArgs) return { code: 2, lines: [USAGE] };
  const { result, range } = closeArgs || auditArgs ? fromGit(command, rest, cwd) : {};
  if (result) return result;
  let jira;
  try {
    jira = jiraFrom(JSON.parse(readFileSync(configPath, "utf8")));
  } catch {
    jira = null;
  }
  if (!jira) return { code: 2, lines: [`no jira-confluence MCP server with JIRA_URL and JIRA_PERSONAL_TOKEN in ${configPath}`] };
  const call = client(jira);
  try {
    if (recordArgs) return await record(call, rest);
    if (doneArgs) return await done(call, rest);
    return await (closeArgs ? close(call, rest) : audit(call, range));
  } catch (error) {
    return { code: 2, lines: [`Jira could not be asked: ${error.message}`] };
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const configPath = process.env.JCLAW_CLAUDE_JSON ?? join(homedir(), ".claude.json");
  const { code, lines } = await run(process.argv.slice(2), { configPath, cwd: process.cwd() });
  (code === 2 ? process.stderr : process.stdout).write(`${lines.join("\n")}\n`);
  process.exit(code);
}
