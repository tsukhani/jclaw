---
description: File an approved /code-audit or /resilience-audit backlog proposal into Jira JCLAW through REST — new epics, stories carrying priority, labels and epic link, links to the issues they extend or depend on, and comments on the open issues that already cover a finding. Writes only what the operator approved; estimates and sprints stay with /backlog-grooming.
argument-hint: "<backlog.json> [approved: all | ranks 1-N | refs ...] [afk: refs ...]"
---

# File an audit backlog

`$ARGUMENTS` names a proposal saved by `/code-audit` or `/resilience-audit` (`docs/spikes/audits/<source>-<date>-backlog.json`), then what the operator approved. Nothing is written to Jira without that approval, and nothing outside it.

## 1. Load and check the proposal

- It has `source`, `date`, `head`, `epics[]` and `stories[]`; every story's `epicRef` names an epic in the file, and ranks are unique. Refuse a file that fails either check.
- **A proposal from an earlier day is stale against Jira.** Its duplicate check ran then, and stories filed since may cover it. Say so and get an explicit go-ahead before filing one whose `date` is not today.
- **Approval** comes from the operator: everything, a rank cut (`ranks 1-20`), or named refs. The `afk` label goes only on the refs the operator names for it — never by default, and never on a story whose `afkCandidate` is false. The AFK factory works any story carrying it unattended.

## 2. Write through REST

Use the helper and the field table in `/backlog-grooming`'s **Jira access** section: copy the helper into the scratchpad, never print the token, and write wiki markup, since REST stores a description verbatim. The descriptions in the proposal are already wiki markup; send them unchanged. Before the first write, read `GET /rest/api/2/priority` and `GET /rest/api/2/issueLinkType` once, and stop if `Highest`, `High`, `Medium`, `Low`, `Relates` or `Blocks` is missing rather than guess a substitute.

In this order, recording each new key against its ref:

1. **New epics** (`key` is null): `POST /rest/api/2/issue` with `project` `JCLAW`, `issuetype` id `10000`, `summary`, `customfield_10004` (the Epic Name) and `description` → 201. Then `POST /rest/agile/1.0/epic/{key}` with `{"color": {"key": "color_N"}}`, using a colour no open epic uses where one is free, so its stories show a pill.
2. **Stories** whose `jira.verdict` is `new` or `extends`, in rank order: `POST /rest/api/2/issue` with `issuetype` `{"name": "Story"}`, `summary`, `description`, `priority` `{"name": <priority>}`, `labels` (plus `afk` where approved) and `customfield_10002` set to the epic's key → 201. No story points: `/backlog-grooming` estimates every unpointed story against Done anchors.
3. **Links.** `extends` → `POST /rest/api/2/issueLink` with type `Relates` between the new story and `jira.key`. `dependsOn` → type `Blocks`, the blocker in `inwardIssue` and the dependent in `outwardIssue`; read it back from the blocked story, where the blocker must show as "is blocked by".
4. **Covered** stories are not created. Post the story's description on `jira.key` as a comment headed `h3. Still present at <head> (/<source>, <date>)` — the open issue gains the fresh evidence instead of a duplicate.

## 3. Verify

- `labels = "audit" AND created >= startOfDay()` (`audit` is a reserved JQL word, so it is quoted) returns exactly the stories created, each with its epic in `customfield_10002`, its priority, and its labels.
- `GET /rest/api/2/issue/{key}?expand=renderedFields` on each shows no literal `h3.`, `{{` or `{noformat}`: markup that rendered.
- Each link reads correctly from its far side, and each covered issue carries its new comment.

## 4. Report

The created stories in rank order (`rank | key | priority | summary | epic`), the epics created, the comments added and the links made. Then point to `/backlog-grooming`, which estimates the new stories and places them into sprints — they count as NFR by their `audit` label.

## Rules

- No write before approval, and none beyond it.
- Never edit an existing issue's summary or description, transition anything, or touch a sprint. The only writes to existing issues are the covered-story comments and the links.
- A write that fails stops the run. Report what was created, so a retry creates only the rest.
