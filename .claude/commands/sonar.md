---
name: sonar
description: Triage and fix SonarQube issues (blocker→info) for abundent:jclaw — fix legitimate ones in an isolated worktree, mark false positives/accepts with justifications, and validate via ./jclaw.sh test on port 9300.
category: Quality
tags: [sonarqube, quality, triage, refactor, worktree]
argument-hint: "[empty | blocker|critical|major|minor|info | <rule-key>]"
---

**SonarQube Triage & Fix Workflow**

Pull the open issues for the SonarQube project `abundent:jclaw`, triage each as **fix / false-positive / accept** against JClaw's idiomatic Java 25 conventions, design, and architecture, fix the legitimate ones in an **isolated git worktree**, and validate with `./jclaw.sh test` on **port 9300**. Use `/usr/bin/git` for every git invocation (project convention).

**Arguments** — `$ARGUMENTS` may be:
- *(empty)* → every severity: BLOCKER, CRITICAL, MAJOR, MINOR, INFO.
- a severity word (`blocker`/`critical`/`major`/`minor`/`info`) → only that tier.
- a rule key (e.g. `java:S1612`) → only issues of that rule.

Reject anything else with a clear message; do not guess.

---

**Phase 1 — Fetch the issues**

1. Project key is `abundent:jclaw` (from `build.gradle.kts` → `sonar.projectKey`).
2. With `mcp__sonarqube__search_sonar_issues_in_projects` (`projects: ["abundent:jclaw"]`), pull all **open** issues (exclude already-resolved/closed), scoped by the severity/rule argument. Page through every result — don't stop at the first page. For each, capture: rule key, severity, file:line, message. Call `mcp__sonarqube__show_rule` for any unfamiliar rule.
3. Optionally snapshot the gate with `mcp__sonarqube__get_project_quality_gate_status` so the report can show before/after.
4. **Group by rule key** — issues of one rule usually share a verdict.

**Phase 2 — Triage each issue (verdict before any mutation)**

Assign every issue one verdict:

- **FIX** — a real bug, security, or genuine maintainability/idiom problem worth correcting.
- **FALSE_POSITIVE** — the rule misfires on this code. Mark it via `mcp__sonarqube__change_sonar_issue_status` (`FALSE_POSITIVE`) with a one-line justification.
- **ACCEPT** (won't-fix) — a deliberate, idiomatic, or architecture-driven choice. Mark it `WONT_FIX`/accepted with a justification.

Apply JClaw's **known triage knowledge — do NOT "fix" these** (doing so reintroduces real bugs or churns intentional code):
- `java:S1612` method references on Play `Model` finders → bind to the base `GenericModel` method and break at runtime. Keep the lambda. **FALSE_POSITIVE**.
- `java:S2160` (override `equals` on Play entities) and `java:S3077` (volatile on reference holders) → framework idioms. **FALSE_POSITIVE**.
- `Web:AvoidCommentedOutCodeCheck` / `css:S4666` from `NOSONAR` → NOSONAR only works in Java; in CSS/Vue it's ignored and triggers FPs. **FALSE_POSITIVE**.
- `Web:S6819` (ARIA roles) and `css:S7924` (contrast) on the frontend → intentional. **FALSE_POSITIVE**.

Triage the rest on merits, biased toward JClaw's conventions:
- **Idiomatic Java 25** — records, pattern matching / `instanceof` binding, `var`, switch expressions, sealed types, virtual threads, text blocks. Prefer these where a MAJOR/MINOR rule nudges toward them.
- **Architecture** — Play 1.x static controllers + `Model` entities; the OkHttp-5, no-native-deps stack; single-operator Personal Edition (no multi-tenancy, no speculative owner FKs); `services.compression` SPI + `agents` pipeline. When a rule conflicts with an established codebase idiom, **ACCEPT** with a rationale rather than churn.
- Severity is a hint, not a mandate — a CRITICAL that's a framework FP is still a FALSE_POSITIVE; a MINOR that's a real null-deref is still a FIX.

Present a **triage table** (rule, severity, count, verdict, one-line rationale) and get a quick confirmation before mutating Sonar statuses or writing code.

**Phase 3 — Fix the FIX-verdict issues in an isolated worktree**

5. Create a dedicated worktree off `main` so the fixes never touch the primary working tree:
   ```bash
   /usr/bin/git worktree add ../jclaw-sonar -b sonar-fixes
   ```
   The `post-checkout` hook runs `init-worktree` (seeds `certs/.env`). Pin the test port to 9300:
   ```bash
   grep -q '^PLAY_TEST_PORT=' ../jclaw-sonar/certs/.env || printf 'PLAY_TEST_PORT=9300\n' >> ../jclaw-sonar/certs/.env
   ```
   If 9300 is already bound (another worktree), pick the next free port and note it.
6. Apply the fixes **inside the worktree**, smallest-diff-first, grouped by rule, matching the surrounding style. Never blanket-suppress with `// NOSONAR` to silence a rule you didn't triage as a verified FP. Remove only orphans your own change creates.
7. Mark the FALSE_POSITIVE / ACCEPT issues in Sonar (Phase 2 verdicts) via `mcp__sonarqube__change_sonar_issue_status`, each with its justification.

**Phase 4 — Validate on port 9300**

8. From the worktree, run the suite isolated on the pinned `PLAY_TEST_PORT`:
   ```bash
   cd ../jclaw-sonar && ./jclaw.sh test
   ```
   (Or `play autotest` for backend-only; `cd frontend && pnpm test` when only `frontend/**` changed.) Fix every failure the changes introduce; re-run until green.
   - **Env-flake guard:** if a broad batch of *unrelated* controller/functional tests fails (401s, FK violations, search-scope mismatches), that's the known live-app/parallel-worktree interference — confirm the test port is isolated (9300, free) and re-run; do not chase it as a real failure.
9. Commit the fixes in the worktree with a clear message referencing the rules addressed. **Do not push or merge.**

**Phase 5 — Report**

10. Summarize: a table of rules with (fixed / marked-FP / accepted) counts + rationale, the worktree path (`../jclaw-sonar`) and branch (`sonar-fixes`), the commit hash(es), and the test result. Leave the branch for the user to review and merge or `/deploy` explicitly.

---

**Hard rules**
- Triage BEFORE mutating — no bulk status changes or code edits without the per-rule verdict and a quick confirmation.
- Fixes live in the `../jclaw-sonar` worktree only; never edit the primary working tree.
- Never `git push` or merge — that's the user's call (`/deploy` or a manual merge).
- Every FALSE_POSITIVE / ACCEPT carries a justification; never silently suppress.
- Honor the known-FP list — "fixing" those reintroduces real bugs.
- When done with the worktree, remove it only on the user's say-so (`/usr/bin/git worktree remove ../jclaw-sonar`).
