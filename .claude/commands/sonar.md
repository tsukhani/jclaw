---
name: sonar
description: Triage and fix SonarQube issues AND security hotspots (blocker→info) for abundent:jclaw — fix legitimate ones in an isolated worktree, mark false positives/accepts/safe with justifications, and validate via ./jclaw.sh test on port 9300.
category: Quality
tags: [sonarqube, quality, triage, refactor, security, hotspots, worktree]
argument-hint: "[empty | blocker|critical|major|minor|info | <rule-key>]"
---

**SonarQube Triage & Fix Workflow**

Pull the open **issues and security hotspots** for the SonarQube project `abundent:jclaw`, triage each — issues as **fix / false-positive / accept**, hotspots as **fixed / safe / acknowledged** — against JClaw's idiomatic Java 25 conventions, design, and architecture, fix the legitimate ones in an **isolated git worktree**, and validate with `./jclaw.sh test` on **port 9300**. Use `/usr/bin/git` for every git invocation (project convention).

**Arguments** — `$ARGUMENTS` may be:
- *(empty)* → every severity: BLOCKER, CRITICAL, MAJOR, MINOR, INFO — plus every TO_REVIEW security hotspot.
- a severity word (`blocker`/`critical`/`major`/`minor`/`info`) → only that issue tier. Hotspots are still surfaced for awareness (they carry a `vulnerabilityProbability`, not a severity) but only acted on when in scope.
- a rule key (e.g. `java:S1612` for an issue, `java:S5852` for a hotspot) → only items of that rule. A hotspot rule key scopes the run to hotspots.

Reject anything else with a clear message; do not guess.

---

**Phase 1 — Fetch the issues and hotspots**

1. Project key is `abundent:jclaw` (from `build.gradle.kts` → `sonar.projectKey`).
2. With `mcp__sonarqube__search_sonar_issues_in_projects` (`projects: ["abundent:jclaw"]`, `issueStatuses: ["OPEN"]`), pull all **open** issues, scoped by the severity/rule argument. Page through every result — don't stop at the first page. For each, capture: rule key, severity, file:line, message. Call `mcp__sonarqube__show_rule` for any unfamiliar rule.
3. With `mcp__sonarqube__search_security_hotspots` (`projectKey: "abundent:jclaw"`, `status: ["TO_REVIEW"]`), pull all **to-review hotspots** (page through every result). For each, capture: rule key, security category, `vulnerabilityProbability`, file:line, message. Call `mcp__sonarqube__show_security_hotspot` for the code context/flows on any you can't triage from the message alone. The quality gate's `new_security_hotspots_reviewed` condition requires **100%** reviewed, so a green gate is impossible while any hotspot stays TO_REVIEW.
4. Optionally snapshot the gate with `mcp__sonarqube__get_project_quality_gate_status` so the report can show before/after (note which conditions — e.g. `new_reliability_rating`, `new_security_hotspots_reviewed` — are failing and which issues/hotspots drive them).
5. **Group by rule key** — issues (and hotspots) of one rule usually share a verdict.

**Phase 2 — Triage everything (verdict before any mutation)**

Assign every **issue** one verdict:

- **FIX** — a real bug, security, or genuine maintainability/idiom problem worth correcting.
- **FALSE_POSITIVE** — the rule misfires on this code. Mark it via `mcp__sonarqube__change_sonar_issue_status` (`falsepositive`) with a one-line justification.
- **ACCEPT** (won't-fix) — a deliberate, idiomatic, or architecture-driven choice. Mark it `accept` with a justification.

Assign every **hotspot** one verdict (mark via `mcp__sonarqube__change_security_hotspot_status`, `status: "REVIEWED"` + a resolution + comment):

- **FIXED** — a genuine risk; harden the code, then mark `FIXED` **only after the fix lands** in the worktree.
- **SAFE** — reviewed and not exploitable given JClaw's trust boundary (single-operator Personal Edition, bounded input, no external multi-tenant attack surface). Mark `SAFE` with the reasoning.
- **ACKNOWLEDGED** — a real-but-accepted residual risk (rare — prefer FIXED or SAFE).

Apply JClaw's **known triage knowledge — do NOT "fix" these** (doing so reintroduces real bugs or churns intentional code):
- `java:S1612` method references on Play `Model` finders → bind to the base `GenericModel` method and break at runtime. Keep the lambda. **FALSE_POSITIVE**.
- `java:S2160` (override `equals` on Play entities) and `java:S3077` (volatile on reference holders) → framework idioms. **FALSE_POSITIVE**.
- `Web:AvoidCommentedOutCodeCheck` / `css:S4666` from `NOSONAR` → NOSONAR only works in Java; in CSS/Vue it's ignored and triggers FPs. **FALSE_POSITIVE**.
- `Web:S6819` (ARIA roles) and `css:S7924` (contrast) on the frontend → intentional. **FALSE_POSITIVE**.
- `java:S5852` (regex ReDoS hotspot) on the `services.compression` detection/normalization regexes → the polynomial cores are the **overlapping quantifier pairs** (`[ ]+` before `$`; `\s+`/`.*` before another whitespace-overlapping quantifier) and are reachable from tool-fetched (semi-untrusted) content, so they're legitimate. Harden with **possessive quantifiers** (e.g. `[ ]++`, `\s++`) or atomic groups — behavior-preserving, no match-outcome change because the hardened quantifiers span disjoint character classes — then mark **FIXED**. Don't blanket-SAFE them.

Triage the rest on merits, biased toward JClaw's conventions:
- **Idiomatic Java 25** — records, pattern matching / `instanceof` binding, unnamed patterns (`_`), `var`, switch expressions, sealed types, virtual threads, text blocks, `Math.clamp`, `StringBuilder.isEmpty()`. Prefer these where a MAJOR/MINOR rule nudges toward them.
- **Architecture** — Play 1.x static controllers + `Model` entities; the OkHttp-5, no-native-deps stack; single-operator Personal Edition (no multi-tenancy, no speculative owner FKs); `services.compression` SPI + `agents` pipeline. When a rule conflicts with an established codebase idiom, **ACCEPT** with a rationale rather than churn.
- Play 1.x control-flow methods (`badRequest()`, `error()`, `notFound()`, `forbidden()`, `redirect()`) **throw and never return** — Sonar doesn't model this, so any `S2259`/null-deref "after" such a guard is a **FALSE_POSITIVE**.
- Severity / `vulnerabilityProbability` is a hint, not a mandate — a CRITICAL that's a framework FP is still a FALSE_POSITIVE; a MINOR that's a real null-deref is still a FIX.

Present a **triage table** (rule, severity/probability, count, verdict, one-line rationale) covering both issues and hotspots, and get a quick confirmation before mutating Sonar statuses or writing code.

**Phase 3 — Fix the FIX/FIXED items in an isolated worktree**

6. Create a dedicated worktree off `main` so the fixes never touch the primary working tree:
   ```bash
   /usr/bin/git worktree add ../jclaw-sonar -b sonar-fixes
   ```
   The `post-checkout` hook runs `init-worktree` (seeds `certs/.env`). Pin the test port to 9300:
   ```bash
   grep -q '^PLAY_TEST_PORT=' ../jclaw-sonar/certs/.env || printf 'PLAY_TEST_PORT=9300\n' >> ../jclaw-sonar/certs/.env
   ```
   If 9300 is already bound (another worktree), pick the next free port and note it.
7. Apply the fixes **inside the worktree**, smallest-diff-first, grouped by rule, matching the surrounding style. This covers both FIX issues and FIXED hotspots (e.g. the ReDoS hardening). Never blanket-suppress with `// NOSONAR` to silence a rule you didn't triage as a verified FP. Remove only orphans your own change creates.
8. Mark the non-code verdicts in Sonar with their Phase 2 justifications: FALSE_POSITIVE / ACCEPT issues via `mcp__sonarqube__change_sonar_issue_status`; SAFE / ACKNOWLEDGED hotspots via `mcp__sonarqube__change_security_hotspot_status`. Mark FIXED hotspots only **after** the hardening lands and tests pass.

**Phase 4 — Validate on port 9300**

9. From the worktree, run the suite isolated on the pinned `PLAY_TEST_PORT`:
   ```bash
   cd ../jclaw-sonar && ./jclaw.sh test
   ```
   (Or `play autotest` for backend-only; `cd frontend && pnpm test` when only `frontend/**` changed.) Fix every failure the changes introduce; re-run until green. Regex hardening (possessive quantifiers) must keep the compression/detection tests green — if a match outcome changed, the quantifier choice was wrong; revisit it.
   - **Env-flake guard:** if a broad batch of *unrelated* controller/functional tests fails (401s, FK violations, search-scope mismatches), that's the known live-app/parallel-worktree interference — confirm the test port is isolated (9300, free) and re-run; do not chase it as a real failure.
10. Commit the fixes in the worktree with a clear message referencing the rules/hotspots addressed. **Do not push or merge.**

**Phase 5 — Report**

11. Summarize: a table of rules with (fixed / marked-FP / accepted) issue counts and (fixed / safe / acknowledged) hotspot counts + rationale, the worktree path (`../jclaw-sonar`) and branch (`sonar-fixes`), the commit hash(es), the test result, and the expected gate impact (which failing conditions the run should clear). Leave the branch for the user to review and merge or `/deploy` explicitly.

---

**Hard rules**
- Triage BEFORE mutating — no bulk status changes or code edits without the per-rule verdict and a quick confirmation.
- Fixes live in the `../jclaw-sonar` worktree only; never edit the primary working tree.
- Never `git push` or merge — that's the user's call (`/deploy` or a manual merge).
- Every FALSE_POSITIVE / ACCEPT / SAFE / ACKNOWLEDGED carries a justification; never silently suppress.
- Mark a hotspot FIXED only after its code fix actually lands and tests pass — never pre-emptively.
- Honor the known-FP list — "fixing" those reintroduces real bugs.
- When done with the worktree, remove it only on the user's say-so (`/usr/bin/git worktree remove ../jclaw-sonar`).
