---
name: sonar
description: Triage and fix SonarQube issues, security hotspots (blocker→info), AND excessive code duplication (>60% file density) for abundent:jclaw — fix legitimate ones in an isolated worktree, mark false positives/accepts/safe with justifications, and validate via ./jclaw.sh test on port 9300.
category: Quality
tags: [sonarqube, quality, triage, refactor, security, hotspots, duplication, worktree]
argument-hint: "[empty | blocker|critical|major|minor|info | duplication | <rule-key>]"
---

**SonarQube Triage & Fix Workflow**

Pull the open **issues and security hotspots**, plus the **most-duplicated files** (>60% duplicated-lines density), for the SonarQube project `abundent:jclaw`, triage each — issues as **fix / false-positive / accept**, hotspots as **fixed / safe / acknowledged**, duplicated files as **remediate / accept** — against JClaw's idiomatic Java 25 conventions, design, and architecture, fix the legitimate ones in an **isolated git worktree**, and validate with `./jclaw.sh test` on **port 9300**. Use `/usr/bin/git` for every git invocation (project convention).

**Arguments** — `$ARGUMENTS` may be:
- *(empty)* → every severity: BLOCKER, CRITICAL, MAJOR, MINOR, INFO — plus every TO_REVIEW security hotspot, plus the duplicated-files pass (>60% density).
- a severity word (`blocker`/`critical`/`major`/`minor`/`info`) → only that issue tier. Hotspots are still surfaced for awareness (they carry a `vulnerabilityProbability`, not a severity) but only acted on when in scope. The duplication pass is skipped unless it's the empty run or `duplication` is named.
- `duplication` → scope the run to the duplicated-files pass only: enumerate files above the 60% density threshold and triage each for remediation. No issues or hotspots are touched.
- a rule key (e.g. `java:S1612` for an issue, `java:S5852` for a hotspot) → only items of that rule. A hotspot rule key scopes the run to hotspots.

Reject anything else with a clear message; do not guess.

---

**Phase 1 — Fetch the issues, hotspots, and duplicated files**

1. Project key is `abundent:jclaw` (from `build.gradle.kts` → `sonar.projectKey`).
2. With `mcp__sonarqube__search_sonar_issues_in_projects` (`projects: ["abundent:jclaw"]`, `issueStatuses: ["OPEN"]`), pull all **open** issues, scoped by the severity/rule argument. Page through every result — don't stop at the first page. For each, capture: rule key, severity, file:line, message. Call `mcp__sonarqube__show_rule` for any unfamiliar rule.
3. With `mcp__sonarqube__search_security_hotspots` (`projectKey: "abundent:jclaw"`, `status: ["TO_REVIEW"]`), pull all **to-review hotspots** (page through every result). For each, capture: rule key, security category, `vulnerabilityProbability`, file:line, message. Call `mcp__sonarqube__show_security_hotspot` for the code context/flows on any you can't triage from the message alone. The quality gate's `new_security_hotspots_reviewed` condition requires **100%** reviewed, so a green gate is impossible while any hotspot stays TO_REVIEW.
4. **Duplication pass** (empty run or `duplication` arg only — skip when a severity word or issue rule scopes the run). With `mcp__sonarqube__search_duplicated_files` (`projectKey: "abundent:jclaw"`), enumerate every file carrying duplication, then keep only those whose **duplicated-lines density is > 60%** — the file-level `Duplicated Lines (%)` column. Confirm the percentage with `mcp__sonarqube__get_component_measures` (`metricKeys: ["duplicated_lines_density"]`) if the search payload omits it. This 60% bar is a *consideration gate for remediation candidacy* — far stricter than the gate's new-code duplication condition — so it surfaces only the genuinely egregious files (e.g. on the current analysis the >60% cluster is the local-engine probe family: `OllamaLocalProbe`, `LmStudioProbe`, `FfmpegProbe`, `FluxSidecarProbe`). For each qualifying file, call `mcp__sonarqube__get_duplications` (`key: "abundent:jclaw:<path>"`) to pull the actual duplicated blocks **and which files/ranges they mirror** — that cross-file picture is what distinguishes remediate from accept.
5. Optionally snapshot the gate with `mcp__sonarqube__get_project_quality_gate_status` so the report can show before/after (note which conditions — e.g. `new_reliability_rating`, `new_security_hotspots_reviewed`, `new_duplicated_lines_density` — are failing and which issues/hotspots/duplication drive them).
6. **Group by rule key** (and, for duplication, **by the shared block**) — issues and hotspots of one rule, and files mirroring the same block, usually share a verdict.

**Phase 2 — Triage everything (verdict before any mutation)**

Assign every **issue** one verdict:

- **FIX** — a real bug, security, or genuine maintainability/idiom problem worth correcting.
- **FALSE_POSITIVE** — the rule misfires on this code. Mark it via `mcp__sonarqube__change_sonar_issue_status` (`falsepositive`) with a one-line justification.
- **ACCEPT** (won't-fix) — a deliberate, idiomatic, or architecture-driven choice. Mark it `accept` with a justification.

Assign every **hotspot** one verdict (mark via `mcp__sonarqube__change_security_hotspot_status`, `status: "REVIEWED"` + a resolution + comment):

- **FIXED** — a genuine risk; harden the code, then mark `FIXED` **only after the fix lands** in the worktree.
- **SAFE** — reviewed and not exploitable given JClaw's trust boundary (single-operator Personal Edition, bounded input, no external multi-tenant attack surface). Mark `SAFE` with the reasoning.
- **ACKNOWLEDGED** — a real-but-accepted residual risk (rare — prefer FIXED or SAFE).

Assign every **duplicated file** (>60% density) one verdict. Duplication is a *measure*, not an issue/hotspot — there's **no Sonar status to mark**, so the verdict drives code only, and an ACCEPT is documented in the report rather than mutated in Sonar:

- **REMEDIATE** — the duplicated span is incidental *mechanism* (boilerplate HTTP probe/health-check, JSON parsing, config plumbing, process/builder setup) that can be lifted to a shared base class, helper, or SPI hook **without coupling distinct concerns**. The engine-specific logic stays put; only the genuinely identical scaffolding moves. Fix it in the worktree (Phase 3), behavior-preserving, tests green.
- **ACCEPT** — the similarity is coincidental *parallel structure* (e.g. each `*Probe`/`*SidecarManager` mirrors the others by deliberate symmetry across distinct local engines) where a forced shared abstraction would be leaky or over-coupled. **Duplication is cheaper than the wrong abstraction** — prefer ACCEPT over a contrived base class that every engine has to fight. Document the rationale; nothing to mark in Sonar.

The judgment call for JClaw's current >60% cluster (the `app/services/**` probe/manager family): read each duplicated block and ask whether it's *mechanism* (lift it once, cleanly) or *per-engine symmetry* (keep it). When in doubt, ACCEPT — a 60%-similar file that reads clearly is healthier than a base class that hides three engines' divergent quirks behind template-method overrides.

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

Present a **triage table** (rule/file, severity/probability/density, count, verdict, one-line rationale) covering issues, hotspots, **and the >60% duplicated files**, and get a quick confirmation before mutating Sonar statuses or writing code.

**Phase 3 — Fix the FIX/FIXED/REMEDIATE items in an isolated worktree**

7. Create a dedicated worktree off `main` so the fixes never touch the primary working tree:
   ```bash
   /usr/bin/git worktree add ../jclaw-sonar -b sonar-fixes
   ```
   The `post-checkout` hook runs `init-worktree` (seeds `certs/.env`). Pin the test port to 9300:
   ```bash
   grep -q '^PLAY_TEST_PORT=' ../jclaw-sonar/certs/.env || printf 'PLAY_TEST_PORT=9300\n' >> ../jclaw-sonar/certs/.env
   ```
   If 9300 is already bound (another worktree), pick the next free port and note it.
8. Apply the fixes **inside the worktree**, smallest-diff-first, grouped by rule, matching the surrounding style. This covers FIX issues, FIXED hotspots (e.g. the ReDoS hardening), and REMEDIATE duplicated files — for the latter, lift the shared block to a base class/helper/SPI hook, keep the per-engine logic in place, and re-run the affected tests so the behavior is provably unchanged. Never blanket-suppress with `// NOSONAR` to silence a rule you didn't triage as a verified FP. Remove only orphans your own change creates.
9. Mark the non-code verdicts in Sonar with their Phase 2 justifications: FALSE_POSITIVE / ACCEPT issues via `mcp__sonarqube__change_sonar_issue_status`; SAFE / ACKNOWLEDGED hotspots via `mcp__sonarqube__change_security_hotspot_status`. Mark FIXED hotspots only **after** the hardening lands and tests pass. Duplication carries no Sonar status — ACCEPT verdicts live in the report only; REMEDIATE verdicts simply land as code.

**Phase 4 — Validate on port 9300**

10. From the worktree, run the suite isolated on the pinned `PLAY_TEST_PORT`:
   ```bash
   cd ../jclaw-sonar && ./jclaw.sh test
   ```
   (Or `play autotest` for backend-only; `cd frontend && pnpm test` when only `frontend/**` changed.) Fix every failure the changes introduce; re-run until green. Regex hardening (possessive quantifiers) must keep the compression/detection tests green — if a match outcome changed, the quantifier choice was wrong; revisit it.
   - **Env-flake guard:** if a broad batch of *unrelated* controller/functional tests fails (401s, FK violations, search-scope mismatches), that's the known live-app/parallel-worktree interference — confirm the test port is isolated (9300, free) and re-run; do not chase it as a real failure.
11. Commit the fixes in the worktree with a clear message referencing the rules/hotspots/duplicated-files addressed. **Do not push or merge.**

**Phase 5 — Report**

12. Summarize: a table of rules with (fixed / marked-FP / accepted) issue counts, (fixed / safe / acknowledged) hotspot counts, and (remediated / accepted) duplicated-file counts + rationale, the worktree path (`../jclaw-sonar`) and branch (`sonar-fixes`), the commit hash(es), the test result, and the expected gate impact (which failing conditions the run should clear — including `new_duplicated_lines_density` if remediation touched new code). Leave the branch for the user to review and merge or `/deploy` explicitly.

---

**Hard rules**
- Triage BEFORE mutating — no bulk status changes or code edits without the per-rule verdict and a quick confirmation.
- Fixes live in the `../jclaw-sonar` worktree only; never edit the primary working tree.
- Never `git push` or merge — that's the user's call (`/deploy` or a manual merge).
- Every FALSE_POSITIVE / ACCEPT / SAFE / ACKNOWLEDGED carries a justification; never silently suppress.
- Mark a hotspot FIXED only after its code fix actually lands and tests pass — never pre-emptively.
- Duplication >60% is a *consideration gate*, not an auto-fix mandate — REMEDIATE only when a clean, non-coupling shared abstraction genuinely exists; otherwise ACCEPT (the wrong abstraction is worse than the duplication) and document why. Any remediation must be behavior-preserving and leave tests green.
- Honor the known-FP list — "fixing" those reintroduces real bugs.
- When done with the worktree, remove it only on the user's say-so (`/usr/bin/git worktree remove ../jclaw-sonar`).
