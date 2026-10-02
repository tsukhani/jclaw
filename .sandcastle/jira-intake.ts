// Jira's intake query, apart from jira.ts so the offline checks load it without credentials.

// Labels that hold a story out of intake. `no-afk` exempts one story of an epic labelled `afk`.
const HELD = "afk-blocked, wont-do, no-afk";

// A story is a candidate through its own `afk` label or its epic's. A story with no labels fails a bare `labels != x`,
// and `"Epic Link" in ()` is a JQL error, so both are spelled out.
export const intakeJql = (afkEpics: string[]): string =>
  'project = JCLAW AND sprint in openSprints() AND issuetype not in (Epic, Sub-task) ' +
  `AND ${afkEpics.length > 0 ? `(labels = afk OR "Epic Link" in (${afkEpics.join(", ")}))` : "labels = afk"} ` +
  `AND (labels is EMPTY OR labels not in (${HELD})) AND status = "To Do" AND (assignee is EMPTY OR assignee = currentUser()) ` +
  'ORDER BY rank';
