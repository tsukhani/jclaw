// Host-side GitHub access for the factory: open issues of the checkout's GitHub repository that its owner labelled
// `afk`, and the write-backs. GitHub has no statuses, so the factory's states are labels: `afk-running` while it works,
// `afk-review` once it offers a branch, `afk-blocked` when it gives up, `afk-merged` once it merged an `afk-merge`
// issue's branch. Closing the issue is Done, which the merge commit's "Closes #N" does when /deploy pushes it.
import * as fs from "node:fs";
import { execFileSync } from "node:child_process";
import { GITHUB_ENV_FILE, REPO_ROOT, STATE, readEnvFile } from "./paths.ts";
import type { Snapshot, Tracker } from "./tracker.ts";

const API = "https://api.github.com";
const HEADER = "### AFK factory";
// Labels that take an issue out of intake: the factory's states, and a won't-do verdict awaiting the owner.
const STATE_LABELS = ["afk-running", "afk-review", "afk-blocked", "afk-merged", "wont-do"];

type Login = { login: string } | null;
type Comment = { author: Login; body: string; createdAt: string; lastEditedAt: string | null; editor: Login };
type TimelineEvent = { __typename: string; createdAt?: string; actor?: Login; label?: { name: string } };

// The GraphQL shape fetched for one issue.
export type Issue = {
  number: number;
  title: string;
  body: string;
  updatedAt: string;
  author: Login;
  lastEditedAt: string | null;
  editor: Login;
  labels: { nodes: { name: string }[] };
  assignees: { nodes: { login: string }[] };
  comments: { nodes: Comment[] };
  timelineItems: { nodes: TimelineEvent[] };
};

const ISSUE_QUERY = `query($owner: String!, $name: String!, $number: Int!) {
  repository(owner: $owner, name: $name) { issue(number: $number) {
    number title body updatedAt author { login } lastEditedAt editor { login }
    labels(first: 50) { nodes { name } }
    assignees(first: 10) { nodes { login } }
    comments(last: 100) { nodes { author { login } body createdAt lastEditedAt editor { login } } }
    timelineItems(itemTypes: [LABELED_EVENT, RENAMED_TITLE_EVENT], last: 100) { nodes { __typename
      ... on LabeledEvent { createdAt actor { login } label { name } }
      ... on RenamedTitleEvent { createdAt actor { login } } } }
  } }
}`;

// On a public repository anyone can write an issue or comment on it, but only the owner can label it. So the story is
// what the owner approved: the issue as it stood when the owner applied `afk`, plus the owner's own later comments. A
// title or body someone else changed since is refused until the owner labels it again. undefined: the owner never
// labelled it, so it is not a story.
export const vetIssue = (issue: Issue, owner: string, fetchedAt: string): Snapshot | undefined => {
  const who = (login: Login | undefined) => login?.login ?? "ghost";
  const labelled = issue.timelineItems.nodes
    .filter((e) => e.__typename === "LabeledEvent" && e.label?.name === "afk" && who(e.actor) === owner)
    .map((e) => e.createdAt ?? "")
    .sort()
    .at(-1);
  if (!labelled) return undefined;
  // GitHub's timestamps are all UTC ISO-8601 of one width, so they order as strings.
  const after = (t: string | null | undefined) => t != null && t > labelled;
  const comments = issue.comments.nodes
    .filter((c) => !(after(c.lastEditedAt) && who(c.editor) !== owner) && (who(c.author) === owner || !after(c.createdAt)))
    .map((c) => ({ author: who(c.author), body: c.body }));
  const renamed = issue.timelineItems.nodes.find(
    (e) => e.__typename === "RenamedTitleEvent" && after(e.createdAt) && who(e.actor) !== owner,
  );
  const refused =
    after(issue.lastEditedAt) && who(issue.editor) !== owner
      ? `${who(issue.editor)} edited the issue after you labelled it afk`
      : renamed
        ? `${who(renamed.actor)} renamed the issue after you labelled it afk`
        : undefined;
  return {
    key: `GH-${issue.number}`,
    summary: issue.title,
    description: issue.body,
    labels: issue.labels.nodes.map((l) => l.name),
    comments,
    blockedBy: [],
    updated: issue.updatedAt,
    fetchedAt,
    ...(refused ? { refused } : {}),
  };
};

// Whether `label` stands as the owner's: its most recent application was by the owner.
export const ownerApplied = (issue: Issue, owner: string, label: string): boolean =>
  issue.timelineItems.nodes
    .filter((e) => e.__typename === "LabeledEvent" && e.label?.name === label)
    .sort((a, b) => (a.createdAt ?? "").localeCompare(b.createdAt ?? ""))
    .at(-1)?.actor?.login === owner;

// The repository the checkout's `github` remote names, unless github.env sets GITHUB_REPO.
const repositoryOf = (configured: string | undefined): string | undefined => {
  if (configured) return configured;
  try {
    const url = execFileSync("/usr/bin/git", ["-C", REPO_ROOT, "remote", "get-url", "github"], {
      encoding: "utf8",
      stdio: ["ignore", "pipe", "ignore"],
    }).trim();
    return url.match(/github\.com[:/]([^/]+\/[^/]+?)(?:\.git)?$/)?.[1];
  } catch {
    return undefined;
  }
};

// The GitHub source, or undefined when FACTORY_HOME/github.env holds no token: GitHub is optional, Jira is not.
export const githubTracker = (): Tracker | undefined => {
  // Only from FACTORY_HOME/github.env, never .env, which the gateway mounts: no container ever holds the token.
  const env = readEnvFile(GITHUB_ENV_FILE);
  const token = env.GITHUB_TOKEN;
  const repo = repositoryOf(env.GITHUB_REPO);
  if (!token || !repo) return undefined;
  const [repoOwner, repoName] = repo.split("/");

  // GitHub's REST and GraphQL shapes are read field by field below, so the payload stays untyped.
  const api = async (path: string, init: { method?: string; body?: unknown; missingOk?: boolean } = {}): Promise<any> => {
    const res = await fetch(API + path, {
      method: init.method ?? "GET",
      headers: {
        Authorization: `Bearer ${token}`,
        Accept: "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "Content-Type": "application/json",
      },
      body: init.body === undefined ? undefined : JSON.stringify(init.body),
    });
    if (init.missingOk && res.status === 404) return undefined;
    if (!res.ok) throw new Error(`GitHub ${init.method ?? "GET"} ${path}: ${res.status} ${(await res.text()).slice(0, 300)}`);
    return res.status === 204 ? undefined : res.json();
  };

  let me: string | undefined;
  const owner = async (): Promise<string> => (me ??= (await api("/user")).login as string);
  const number = (key: string) => Number(key.slice("GH-".length));
  const issuesPath = `/repos/${repo}/issues`;

  // Open issues carrying `label`; the issues API lists pull requests too.
  const open = async (label: string): Promise<any[]> =>
    ((await api(`${issuesPath}?state=open&labels=${encodeURIComponent(label)}&per_page=100`)) as any[]).filter(
      (i) => !i.pull_request,
    );

  const fetchIssue = async (key: string): Promise<Issue> => {
    const result = await api("/graphql", {
      method: "POST",
      body: { query: ISSUE_QUERY, variables: { owner: repoOwner, name: repoName, number: number(key) } },
    });
    if (result.errors) throw new Error(`GitHub GraphQL for ${key}: ${JSON.stringify(result.errors).slice(0, 300)}`);
    return result.data.repository.issue as Issue;
  };

  const fetchSnapshot = async (key: string): Promise<Snapshot | undefined> => {
    const snap = vetIssue(await fetchIssue(key), await owner(), new Date().toISOString());
    if (snap) {
      fs.mkdirSync(STATE, { recursive: true });
      fs.writeFileSync(`${STATE}/${snap.key}.json`, JSON.stringify(snap, null, 2));
    }
    return snap;
  };

  const addLabel = async (key: string, label: string) => {
    await api(`${issuesPath}/${number(key)}/labels`, { method: "POST", body: { labels: [label] } });
  };
  const removeLabel = async (key: string, label: string) => {
    await api(`${issuesPath}/${number(key)}/labels/${encodeURIComponent(label)}`, { method: "DELETE", missingOk: true });
  };

  // An issue the factory may take: open, in no factory state, and assigned to nobody else.
  const free = async (issue: any) => {
    const mine = await owner();
    return (
      issue.state === "open" &&
      !issue.labels.some((l: { name: string }) => STATE_LABELS.includes(l.name)) &&
      issue.assignees.every((a: { login: string }) => a.login === mine)
    );
  };

  return {
    name: "GitHub",
    header: HEADER,
    markup: { code: (t) => `\`${t}\``, heading: (t) => `#### ${t}`, bullet: (t) => `- ${t}`, met: "✅", unmet: "❌" },
    owns: (key) => /^GH-\d+$/.test(key),
    intake: async () => {
      const candidates: any[] = [];
      for (const issue of await open("afk")) if (await free(issue)) candidates.push(issue);
      const snaps = await Promise.all(candidates.map((i) => fetchSnapshot(`GH-${i.number}`)));
      return snaps.filter((s): s is Snapshot => s !== undefined);
    },
    inReview: async () => (await open("afk-review")).map((i) => `GH-${i.number}`),
    mergeQueue: async () => {
      const mine = await owner();
      const labelled = (await open("afk-review")).filter((i) => i.labels.some((l: { name: string }) => l.name === "afk-merge"));
      const keys = labelled.map((i) => `GH-${i.number}`);
      const approved = await Promise.all(keys.map(async (k) => ownerApplied(await fetchIssue(k), mine, "afk-merge")));
      return keys.filter((_, i) => approved[i]);
    },
    orphaned: async () => {
      const mine = await owner();
      return (await open("afk-running"))
        .filter((i) => i.assignees.some((a: { login: string }) => a.login === mine))
        .map((i) => `GH-${i.number}`);
    },
    snapshotToState: async (key) => {
      const snap = await fetchSnapshot(key);
      if (!snap) throw new Error(`${key} is not labelled afk by ${await owner()}`);
      return snap;
    },
    // As with Jira, assignment is the claim: a rival harness that assigned first is seen after the pause.
    claim: async (key) => {
      const issue = async () => api(`${issuesPath}/${number(key)}`);
      if (!(await free(await issue()))) return false;
      await api(`${issuesPath}/${number(key)}/assignees`, { method: "POST", body: { assignees: [await owner()] } });
      await new Promise((resolve) => setTimeout(resolve, 2000));
      const after = await issue();
      return (await free(after)) && after.assignees.length === 1;
    },
    started: async (key) => addLabel(key, "afk-running"),
    reviewing: async (key) => {
      await addLabel(key, "afk-review");
      await removeLabel(key, "afk-running");
    },
    blocked: async (key) => {
      await addLabel(key, "afk-blocked");
      await removeLabel(key, "afk-running");
    },
    requeued: async (key) => removeLabel(key, "afk-running"),
    merged: async (key) => {
      await addLabel(key, "afk-merged");
      await removeLabel(key, "afk-review");
    },
    done: async (key) => (await api(`${issuesPath}/${number(key)}`)).state === "closed",
    comment: async (key, body) => {
      await api(`${issuesPath}/${number(key)}/comments`, { method: "POST", body: { body } });
    },
    addLabel,
    removeLabel,
    // Pushed to main by /deploy, the merge commit closes the issue.
    mergeMessage: (key) => `Closes #${number(key)}`,
  };
};
