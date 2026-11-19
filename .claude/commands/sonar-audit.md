---
name: sonar-audit
description: Read-only audit of every SonarQube issue abundent:jclaw holds as accepted or false positive — judge each mark against the code as it stands now and report which still hold, which carry the wrong status, and which should now be fixed. Changes nothing; the operator decides what to do from the report. Periodic, never part of a release.
category: Quality
tags: [sonarqube, quality, audit, triage, report]
argument-hint: "[empty | accepted | false-positive | <rule-key> | <path>]"
---

**SonarQube Resolved-Issue Audit (report only)**

`/sonar` resolves an issue one of three ways: a fix, or a mark, ACCEPT (won't fix: a deliberate, idiomatic or architecture-driven choice) or FALSE_POSITIVE (the rule misfires on this code). A mark is a judgement made once, against the code of that day. Sonar keeps it for as long as the flagged line survives in a form its tracker recognizes, while the code around it, the project's conventions and the languages in use keep moving. So marks go stale: a complexity that was accepted now has a clean split, a pattern accepted because Java had no idiom for it now has one, a false positive that was right for the old code is wrong for what the line holds today. This command finds those marks for `abundent:jclaw` and reports them. On 2026-11-19 Sonar held 354 accepted and 394 false-positive issues.

**It changes nothing**: no code, no Sonar status, no Jira ticket, no branch. Its output is a report, and the operator decides from it what to fix, re-mark or leave, and how (`/sonar` on a rule, a ticket, a batch). It is a periodic check, run between releases or from `/loop`, and never part of `/release-check` or `/deploy`. Use `/usr/bin/git` for every git invocation (project convention).

**Arguments** — `$ARGUMENTS` may be:

- *(empty)* → every issue resolved as ACCEPTED or FALSE_POSITIVE.
- `accepted` or `false-positive` → only that status.
- a rule key (e.g. `java:S3776`) → only that rule's marks, of both statuses.
- a path (e.g. `app/services`, `frontend/components/settings`) → only marks on files under it.

Reject anything else with a clear message; do not guess.

---

**Phase 1 — Fetch the marks**

1. With `mcp__sonarqube__search_sonar_issues_in_projects` (`projects: ["abundent:jclaw"]`, `issueStatuses` the statuses in scope, `ps: 500`), page through every result. A page can exceed the tool's output limit and land in a file: read it with `jq` there, not into the conversation. For each issue keep the key, rule, status as searched (ACCEPTED or FALSE_POSITIVE), file, line range, message and creation date.
2. Group by rule key, and within a rule by file. One rule's marks usually share a reason, which is why they are judged together, but each issue still gets its own verdict.
3. For every rule in scope, read `mcp__sonarqube__show_rule` once, and `/sonar`'s known triage knowledge (its "do NOT fix these" list) before judging it.

**Phase 2 — Judge every mark against today's code**

For each issue, open the flagged lines in the current tree, read-only, with enough context to see what they do, and assign one recommendation:

- **KEEP** — the mark still holds for the code there now: the rule still misfires on it (FALSE_POSITIVE), or the choice is still deliberate and still the codebase's idiom (ACCEPT). Give the one-line reason, naming what makes it hold: the Play behavior, the measured cost, the architecture rule or the ticket.
- **CORRECT** — right that nothing should change, under the wrong status: a "false positive" where the rule is accurate and the code is deliberate is an ACCEPT, and an "accepted" issue the rule simply misreads is a FALSE_POSITIVE.
- **FIX** — the mark no longer holds: the reason it gave is gone (the code was rewritten, the constraint lifted, the language gained the idiom), or it was wrong to begin with, and a contained, behavior-preserving change would resolve the rule. Say what the change is, the files it touches, the tests that cover them, and the risk, high for restructuring working code such as a `java:S3776` complexity split.
- **REDESIGN** — the mark no longer holds, but the right fix is larger than a contained change: a redesign, a cross-cutting rename, a public API change. Say what it would take.

Rules for judging:

- **Today's code, not the original reason.** Sonar's tracker can carry a mark onto a line rewritten since, so a mark is judged against what its line holds now. A line that no longer does what the mark was about is judged afresh.
- **`/sonar`'s known false positives are KEEP** while their reason holds: `java:S1612` method references on Play `Model` finders, `java:S2160` and `java:S3077` on Play idioms, `Web:AvoidCommentedOutCodeCheck` and `css:S4666` from `NOSONAR` outside Java, `Web:S6819` on custom interactive widgets where no native element fits, and the Play control-flow nulls (`badRequest()`, `notFound()`, … throw). Changing one of these reintroduces a real bug.
- **A recommendation that contradicts an established verdict** (the same rule resolved another way elsewhere in this project) says so, and names both places (AGENTS.md, Reach For What Exists).
- **Severity is a hint**, as in `/sonar`: a MINOR accepted for a reason that no longer holds is still a FIX.
- **Large groups.** A rule with dozens of marks (`java:S6126`, `java:S3776`) shares one reason only where each instance is the same pattern: check each, and split the group where they differ.
- The evaluation can be split across subagents, one rule group each, each returning its recommendations and reasons; none of them edits anything either.

**Phase 3 — Report**

4. Lead with the totals: issues audited, and how many are recommended KEEP, CORRECT, FIX and REDESIGN.
5. Then one table per status in scope, a row per rule: the count, the split across the four recommendations, and the shared reason for each KEEP group. Under it, every CORRECT, FIX and REDESIGN issue with its key, file and line, the reason, and for a FIX the change, the files, the covering tests and the risk.
6. When an earlier report exists in `tmp/sonar-audit/`, say what changed since it: marks added, marks Sonar has since closed, and recommendations that moved.
7. Save the full report as `tmp/sonar-audit/<YYYY-MM-DD>.md` (gitignored, so it stays in this clone), and print the totals and the FIX and REDESIGN lists in the conversation. End by offering the next steps the report supports, such as `/sonar <rule-key>` for a FIX group, without starting any of them.

---

**Hard rules**

- Read-only: never edit a file outside `tmp/sonar-audit/`, never change a Sonar status, never create a branch, a worktree, a commit or a ticket.
- Every issue in scope gets a recommendation and a reason; nothing is skipped because its group is large.
- Honor `/sonar`'s known false positives while their reason holds.
- Never run inside `/release-check` or `/deploy`.
