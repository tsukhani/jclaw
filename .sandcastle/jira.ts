// Host-side Jira access for the factory: intake of `afk` stories and the write-backs. The agent never
// touches Jira; it only sees the snapshot written here.
import * as fs from "node:fs";
import { PROJECT, blockersOf, intakeJql, mergeJql, notCandidate, openBlockersOf, ownsKey, unblocked as unblockedStory } from "./jira-intake.ts";
import { JIRA_ENV_FILE, STATE, readEnvFile } from "./paths.ts";
import { FOUND_LABEL, findingLocation, findingSummary, type Finding } from "./brief.ts";
import type { Blocker, Snapshot, Tracker } from "./tracker.ts";

// Only from FACTORY_HOME/jira.env, never .env, which the gateway mounts: no container ever holds the Jira token.
const env = readEnvFile(JIRA_ENV_FILE);
if (!env.JIRA_URL || !env.JIRA_PERSONAL_TOKEN) throw new Error(`no Jira credentials: put JIRA_URL and JIRA_PERSONAL_TOKEN in ${JIRA_ENV_FILE}`);
const BASE: string = env.JIRA_URL.replace(/\/$/, "");
const EPIC_LINK = "customfield_10002";

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

export const snapshot = async (key: string): Promise<Snapshot> => {
  const f = (await api(`/rest/api/2/issue/${key}?fields=summary,description,labels,comment,issuelinks,updated,${EPIC_LINK}`)).fields;
  const epicKey: string | null = f[EPIC_LINK];
  const epic = epicKey ? (await api(`/rest/api/2/issue/${epicKey}?fields=summary,description,labels`)).fields : null;
  return {
    key,
    summary: f.summary,
    description: f.description ?? "",
    labels: f.labels ?? [],
    comments: (f.comment?.comments ?? []).map((c: any) => ({ author: c.author?.displayName ?? "unknown", body: c.body })),
    parent: epic ? { key: epicKey!, summary: epic.summary, description: epic.description ?? "", labels: epic.labels ?? [] } : undefined,
    blockedBy: blockersOf(f.issuelinks),
    updated: f.updated,
    fetchedAt: new Date().toISOString(),
  };
};

// A story that blocks a candidate, without its description or comments.
export const blocker = async (key: string): Promise<Blocker> => {
  const f = (await api(`/rest/api/2/issue/${key}?fields=summary,status,labels,issuelinks,${EPIC_LINK}`)).fields;
  const epicKey: string | null = f[EPIC_LINK];
  const epicLabels = async (): Promise<string[]> => (epicKey ? ((await api(`/rest/api/2/issue/${epicKey}?fields=labels`)).fields.labels ?? []) : []);
  return {
    key,
    summary: f.summary,
    status: f.status.name,
    done: f.status.statusCategory.key === "done",
    reason: await notCandidate({ labels: f.labels ?? [], status: f.status.name }, epicLabels),
    blockedBy: openBlockersOf(f.issuelinks),
  };
};

// Every `afk` candidate in board order, each snapshot written to state/ (not in git: it holds ticket text).
// Which of them may run, and in what order, is the caller's plan.
export const intake = async (): Promise<Snapshot[]> => {
  const found = await api(`/rest/api/2/search?jql=${encodeURIComponent(intakeJql(await afkEpics()))}&fields=summary&maxResults=50`);
  return Promise.all(found.issues.map((issue: { key: string }) => snapshotToState(issue.key)));
};

// Open epics carrying `label`: each of their stories carries it without a label of its own.
const epicsLabelled = async (label: string): Promise<string[]> => {
  const jql = `project = ${PROJECT} AND issuetype = Epic AND labels = ${label} AND statusCategory != Done`;
  const found = await api(`/rest/api/2/search?jql=${encodeURIComponent(jql)}&fields=summary&maxResults=100`);
  return found.issues.map((issue: { key: string }) => issue.key);
};
const afkEpics = () => epicsLabelled("afk");

// Stories in review the operator has told the factory to merge.
export const mergeQueue = async (): Promise<string[]> => {
  const jql = mergeJql(await epicsLabelled("afk-merge"));
  const found = await api(`/rest/api/2/search?jql=${encodeURIComponent(jql)}&fields=summary&maxResults=100`);
  return found.issues.map((issue: { key: string }) => issue.key);
};

// afk stories awaiting human review: their branches are unmerged, so no new story may change the same files yet.
export const inReview = async (): Promise<string[]> => {
  const jql = `project = ${PROJECT} AND labels = afk AND status = Review`;
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

// Every story the factory filed for a pre-existing defect, by its location, Done ones included.
export const filedFindings = async (): Promise<Map<string, string>> => {
  const known = new Map<string, string>();
  const jql = encodeURIComponent(`project = ${PROJECT} AND labels = ${FOUND_LABEL}`);
  for (let startAt = 0; ; ) {
    const page = await api(`/rest/api/2/search?jql=${jql}&fields=summary&maxResults=200&startAt=${startAt}`);
    for (const issue of page.issues) {
      const location = findingLocation(issue.fields.summary);
      if (location && !known.has(location)) known.set(location, issue.key);
    }
    startAt += page.issues.length;
    if (page.issues.length === 0 || startAt >= page.total) return known;
  }
};

// Findings go to Jira whichever tracker the story came from: a GitHub issue on the public mirror would publish a defect,
// security ones included, before anyone has read it.
export const fileFinding = async (source: Snapshot, f: Finding): Promise<string> => {
  const fromJira = ownsKey(source.key);
  const description = [
    f.defect,
    "",
    `Found at {{${f.location}}} by the AFK factory's review of ${source.key} (${source.summary}). That story did not change these lines, so the factory left them as they were and filed this instead.`,
    "The finding is the review agent's and unverified: confirm it before building.",
  ].join("\n");
  const fields: Record<string, unknown> = {
    project: { key: PROJECT }, issuetype: { name: "Story" }, summary: findingSummary(f), description, labels: [FOUND_LABEL],
  };
  if (fromJira && source.parent) fields[EPIC_LINK] = source.parent.key;
  const created = await api("/rest/api/2/issue", { method: "POST", body: { fields } });
  if (fromJira) {
    await api("/rest/api/2/issueLink", { method: "POST", body: { type: { name: "Relates" }, inwardIssue: { key: created.key }, outwardIssue: { key: source.key } } });
  }
  return created.key;
};

// Stories this user's factory was working on when it stopped: it labels what it runs, so a story a human moved to
// In Progress is never mistaken for one, and it assigns what it runs, so another developer's harness keeps its own.
export const orphaned = async (): Promise<string[]> => {
  const jql = `project = ${PROJECT} AND labels = afk-running AND status = "In Progress" AND assignee = currentUser()`;
  const found = await api(`/rest/api/2/search?jql=${encodeURIComponent(jql)}&fields=summary&maxResults=100`);
  return found.issues.map((issue: { key: string }) => issue.key);
};

let myName: string | undefined;

// Several developers' harnesses may watch one sprint, and every Jira transition is global, so moving a story is no lock.
// A harness assigns the story to its user and reads it back after a pause longer than its own claim-to-transition step:
// a rival that assigned first is seen here, and one that assigns later finds the story already In Progress.
export const claim = async (key: string): Promise<boolean> => {
  myName ??= (await api("/rest/api/2/myself")).name as string;
  const holder = async () => (await api(`/rest/api/2/issue/${key}?fields=status,assignee`)).fields;
  const before = await holder();
  if (before.status.name !== "To Do" || (before.assignee && before.assignee.name !== myName)) return false;
  await api(`/rest/api/2/issue/${key}/assignee`, { method: "PUT", body: { name: myName } });
  await new Promise((resolve) => setTimeout(resolve, 2000));
  const after = await holder();
  return after.status.name === "To Do" && after.assignee?.name === myName;
};

export const jira: Tracker = {
  name: "Jira",
  header: FACTORY_HEADER,
  markup: { code: (t) => `{{${t}}}`, heading: (t) => `h4. ${t}`, bullet: (t) => `* ${t}`, met: "(/)", unmet: "(x)" },
  owns: ownsKey,
  intake,
  inReview,
  mergeQueue,
  orphaned,
  snapshotToState,
  blocker,
  claim,
  started: async (key) => {
    await transitionTo(key, "In Progress");
    await addLabel(key, "afk-running");
  },
  reviewing: async (key) => {
    await transitionTo(key, "Review");
    await removeLabel(key, "afk-running");
  },
  blocked: async (key) => {
    await addLabel(key, "afk-blocked");
    await removeLabel(key, "afk-running");
  },
  unblocked: async (key) => {
    const { status, labels } = (await api(`/rest/api/2/issue/${key}?fields=status,labels`)).fields;
    return unblockedStory({ status, labels });
  },
  requeued: async (key) => {
    await transitionTo(key, "To Do");
    await removeLabel(key, "afk-running");
  },
  merged: async (key) => {
    await transitionTo(key, "Done");
  },
  done: async (key) => (await api(`/rest/api/2/issue/${key}?fields=status`)).fields.status.statusCategory.key === "done",
  // The status is the state here, and reviewing() already dropped afk-running.
  settled: async () => {},
  comment: async (key, body) => {
    await comment(key, body);
  },
  addLabel: async (key, label) => {
    await addLabel(key, label);
  },
  removeLabel: async (key, label) => {
    await removeLabel(key, label);
  },
  mergeMessage: () => undefined,
};
