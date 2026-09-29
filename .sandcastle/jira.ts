// Host-side Jira access for the factory: intake of `afk` stories and the write-backs. The agent never
// touches Jira; it only sees the snapshot written here.
import * as fs from "node:fs";
import * as os from "node:os";
import { STATE } from "./paths.ts";

const env = JSON.parse(fs.readFileSync(`${os.homedir()}/.claude.json`, "utf8")).mcpServers["jira-confluence"].env;
const BASE: string = env.JIRA_URL.replace(/\/$/, "");
const EPIC_LINK = "customfield_10002";

export const INTAKE_JQL =
  'project = JCLAW AND sprint in openSprints() AND issuetype not in (Epic, Sub-task) ' +
  'AND labels = afk AND labels != afk-blocked AND status = "To Do" ORDER BY rank';

// Jira's REST shapes are read field by field below, so the payload stays untyped.
const api = async (path: string, init: { method?: string; body?: unknown } = {}): Promise<any> => {
  const res = await fetch(BASE + path, {
    method: init.method ?? "GET",
    headers: { Authorization: `Bearer ${env.JIRA_PERSONAL_TOKEN}`, "Content-Type": "application/json" },
    body: init.body === undefined ? undefined : JSON.stringify(init.body),
  });
  if (!res.ok) throw new Error(`Jira ${init.method ?? "GET"} ${path}: ${res.status} ${(await res.text()).slice(0, 300)}`);
  return res.status === 204 ? undefined : res.json();
};

export type Snapshot = {
  key: string;
  summary: string;
  description: string;
  comments: { author: string; body: string }[];
  parent?: { key: string; summary: string; description: string };
  blockedBy: { key: string; status: string; done: boolean }[];
  updated: string;
  fetchedAt: string;
};

export const snapshot = async (key: string): Promise<Snapshot> => {
  const f = (await api(`/rest/api/2/issue/${key}?fields=summary,description,comment,issuelinks,updated,${EPIC_LINK}`)).fields;
  const epicKey: string | null = f[EPIC_LINK];
  const epic = epicKey ? (await api(`/rest/api/2/issue/${epicKey}?fields=summary,description`)).fields : null;
  return {
    key,
    summary: f.summary,
    description: f.description ?? "",
    comments: (f.comment?.comments ?? []).map((c: any) => ({ author: c.author?.displayName ?? "unknown", body: c.body })),
    parent: epic ? { key: epicKey!, summary: epic.summary, description: epic.description ?? "" } : undefined,
    // On issue A, a Blocks link carrying an inwardIssue B reads "A is blocked by B".
    blockedBy: (f.issuelinks ?? [])
      .filter((l: any) => l.type.name === "Blocks" && l.inwardIssue)
      .map((l: any) => ({
        key: l.inwardIssue.key,
        status: l.inwardIssue.fields.status.name,
        done: l.inwardIssue.fields.status.statusCategory.key === "done",
      })),
    updated: f.updated,
    fetchedAt: new Date().toISOString(),
  };
};

// Every `afk` candidate in board order, each snapshot written to state/ (gitignored: it holds ticket text).
// Which of them may run, and in what order, is the caller's plan.
export const intake = async (): Promise<Snapshot[]> => {
  const found = await api(`/rest/api/2/search?jql=${encodeURIComponent(INTAKE_JQL)}&fields=summary&maxResults=50`);
  return Promise.all(found.issues.map((issue: { key: string }) => snapshotToState(issue.key)));
};

// afk stories awaiting human review: their branches are unmerged, so no new story may change the same files yet.
export const inReview = async (): Promise<string[]> => {
  const jql = "project = JCLAW AND labels = afk AND status = Review";
  const found = await api(`/rest/api/2/search?jql=${encodeURIComponent(jql)}&fields=summary&maxResults=100`);
  return found.issues.map((issue: { key: string }) => issue.key);
};

export const snapshotToState = async (key: string): Promise<Snapshot> => {
  const snap = await snapshot(key);
  fs.mkdirSync(STATE, { recursive: true });
  fs.writeFileSync(`${STATE}/${snap.key}.json`, JSON.stringify(snap, null, 2));
  return snap;
};

// Every comment the factory posts starts with this; the reviewer's own comments never do.
export const FACTORY_HEADER = "h3. AFK factory";

// A story back in To Do after the factory offered it for review was sent back: the reviewer's comments since the last
// offer are the feedback ("" when there are none), skipping the factory's own. undefined means it was never offered.
export const rejectionFeedback = (s: Snapshot): string | undefined => {
  let offer = -1;
  s.comments.forEach((c, i) => {
    if (c.body.startsWith(`${FACTORY_HEADER}: ready for review`)) offer = i;
  });
  if (offer < 0) return undefined;
  return s.comments
    .slice(offer + 1)
    .filter((c) => !c.body.startsWith(FACTORY_HEADER))
    .map((c) => `${c.author}: ${c.body}`)
    .join("\n\n");
};

// Everything the agent should read, as one block for the prompt's {{DESCRIPTION}}.
export const promptContext = (s: Snapshot): string =>
  [
    s.description,
    s.comments.length ? `\n### Comments on the ticket\n${s.comments.map((c) => `- ${c.author}: ${c.body}`).join("\n")}` : "",
    s.parent ? `\n### Parent epic ${s.parent.key}: ${s.parent.summary}\n${s.parent.description}` : "",
  ].join("\n");

export const transitionTo = async (key: string, status: string) => {
  const { transitions } = await api(`/rest/api/2/issue/${key}/transitions`);
  const t = transitions.find((x: any) => x.to.name === status);
  if (!t) throw new Error(`${key} has no transition to ${status}`);
  await api(`/rest/api/2/issue/${key}/transitions`, { method: "POST", body: { transition: { id: t.id } } });
};

export const comment = (key: string, body: string) =>
  api(`/rest/api/2/issue/${key}/comment`, { method: "POST", body: { body } });

export const addLabel = (key: string, label: string) =>
  api(`/rest/api/2/issue/${key}`, { method: "PUT", body: { update: { labels: [{ add: label }] } } });

export const removeLabel = (key: string, label: string) =>
  api(`/rest/api/2/issue/${key}`, { method: "PUT", body: { update: { labels: [{ remove: label }] } } });

// Stories a factory process was working on when it stopped: it labels what it runs, so a story a human moved to
// In Progress is never mistaken for one.
export const orphaned = async (): Promise<string[]> => {
  const jql = 'project = JCLAW AND labels = afk-running AND status = "In Progress"';
  const found = await api(`/rest/api/2/search?jql=${encodeURIComponent(jql)}&fields=summary&maxResults=100`);
  return found.issues.map((issue: { key: string }) => issue.key);
};
