// Jira's intake rules and the reading of its Blocks links, apart from jira.ts so the offline checks load them without
// credentials.
import type { Snapshot } from "./tracker.ts";

// Labels that hold a story out of intake. `no-afk` exempts one story of an epic labelled `afk`.
const HELD = ["afk-blocked", "wont-do", "no-afk"];

// A story is a candidate through its own `afk` label or its epic's. A story with no labels fails a bare `labels != x`,
// and `"Epic Link" in ()` is a JQL error, so both are spelled out.
export const intakeJql = (afkEpics: string[]): string =>
  'project = JCLAW AND sprint in openSprints() AND issuetype not in (Epic, Sub-task) ' +
  `AND ${afkEpics.length > 0 ? `(labels = afk OR "Epic Link" in (${afkEpics.join(", ")}))` : "labels = afk"} ` +
  `AND (labels is EMPTY OR labels not in (${HELD.join(", ")})) AND status = "To Do" AND (assignee is EMPTY OR assignee = currentUser()) ` +
  'ORDER BY rank';

// Why an open story is no candidate, for the board's list of blockers the factory will not build. The light fetch
// carries no sprint or assignee, so what passes every rule here is outside intake on one of those. `epicLabels` is a
// read of the story's epic, called only when the story's own labels neither hold it nor carry `afk`.
export const notCandidate = async (s: { labels: string[]; status: string }, epicLabels: () => Promise<string[]>): Promise<string> => {
  const held = HELD.find((label) => s.labels.includes(label));
  if (held) return `labelled ${held}`;
  if (!s.labels.includes("afk") && !(await epicLabels()).includes("afk")) return "not labelled afk";
  if (s.status !== "To Do") return s.status;
  return "outside intake";
};

// On issue A, a Blocks link carrying an inwardIssue B reads "A is blocked by B". Jira's payload is read field by field.
export const blockersOf = (links: any[] | undefined): Snapshot["blockedBy"] =>
  (links ?? [])
    .filter((l: any) => l.type.name === "Blocks" && l.inwardIssue)
    .map((l: any) => ({
      key: l.inwardIssue.key,
      status: l.inwardIssue.fields.status.name,
      done: l.inwardIssue.fields.status.statusCategory.key === "done",
    }));

// The keys of the stories that block issue A and are not Done.
export const openBlockersOf = (links: any[] | undefined): string[] => blockersOf(links).filter((b) => !b.done).map((b) => b.key);

// Stories in review the factory may merge: labelled `afk-merge` themselves or through their epic, unless `no-afk-merge`.
export const mergeJql = (mergeEpics: string[]): string =>
  'project = JCLAW AND labels = afk AND status = Review ' +
  `AND ${mergeEpics.length > 0 ? `(labels = afk-merge OR "Epic Link" in (${mergeEpics.join(", ")}))` : "labels = afk-merge"} ` +
  'AND labels not in (no-afk-merge) ORDER BY rank';
