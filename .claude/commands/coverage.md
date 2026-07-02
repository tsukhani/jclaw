---
description: Raise blended (Sonar-style) code coverage to a target (default 75%) with meaningful tests over critical functionality and edge cases — measured locally, no coverage gaming.
---

**Coverage Workflow**

Raise JClaw's **blended coverage** — the SonarQube-style `(covered_lines + covered_conditions) / (lines_to_cover + conditions_to_cover)` across backend + frontend — to the target by writing tests that verify real behavior. The exit condition is the number reported by `node bin/coverage-blend.mjs` (the local oracle that mirrors Sonar's metric and exclusions), never self-assessment. Use `/usr/bin/git` for every git invocation (project convention).

**Arguments**

`$ARGUMENTS` may be:

- *(empty)* → target **75%** blended coverage.
- a number `50`–`95` (optional `%` suffix) → that target.
- `report` (or `check`) → **measure-and-rank only**: produce the coverage report and the prioritized gap list, write no tests, commit nothing.

Reject anything else with a clear message; do not guess. Never *lower* an already-met target: if the measured baseline already exceeds the requested target, say so and stop (or continue only on the user's explicit say-so).

---

**Phase 1 — Measure ground truth (never trust a stale number)**

1. **Delete the stale dump first**: `rm -f jacoco.exec`. The JaCoCo agent (`%test.javaagent.path` in application.conf) runs with the default `append=true`, so `jacoco.exec` accumulates across historical runs — measuring from an appended file silently inflates coverage. Every measurement cycle starts from a fresh dump.
2. Make sure no dev/prod JClaw instance is running (`./jclaw.sh status`) — a live app skews functional tests (known interference; see CLAUDE.md). Do **not** stop a running instance yourself; ask the user.
3. Run the backend suite: `play autotest`. The test JVM writes `jacoco.exec` at shutdown. The suite must be green — a red suite is reported and fixed (if your change broke it) or surfaced to the user (if pre-existing) before any coverage work continues.
4. Refresh classfiles, then render the XML report (stale classfiles mis-map lines):
   ```bash
   play precompile
   java -jar bin/jacococli.jar report jacoco.exec --classfiles precompiled/java --sourcefiles app --xml jacoco.xml
   ```
5. Run the frontend suite with coverage: `cd frontend && pnpm test --coverage` (exactly that form — `pnpm test -- --coverage` makes vitest treat the flag as a test-file pattern). This produces `frontend/coverage/lcov.info`.
6. Compute the blended number and the gap ranking:
   ```bash
   node bin/coverage-blend.mjs --top=30
   ```
   Record: blended %, backend line/branch %, frontend line/branch %, and the top-30 files by uncovered lines+branches. In `report` mode, present this (with a short commentary on which gaps are worth closing) and **stop**.

**Phase 2 — Pick targets by value, not by cheapness**

7. From the ranked gap list, choose the next batch (3–6 files) prioritizing **critical business logic**: `app/services/`, `app/agents/`, `app/memory/`, `app/channels/`, `app/tools/`, controllers with logic, and frontend composables/utilities with real branching. Prefer a file where uncovered lines are *decision-making code* (error paths, dialect branches, parsing, state transitions) over one with more raw uncovered lines of glue.
8. For each candidate, read the uncovered regions (the `--top` fractions tell you how much is missing; read the file to see *what*). Classify each region:
   - **Testable behavior** → write tests (Phase 3).
   - **Unreachable / defensive-only / requires-live-external-service** → skip and record it in the report with one line of reasoning. Do NOT add it to any exclusion list — flagging is the deliverable, silencing is not.

**Phase 3 — Write meaningful tests (the anti-gaming contract)**

9. Every test must **assert observable behavior** — return values, state changes, emitted events, thrown exceptions. A test that merely executes lines without meaningful assertions is forbidden, even though it would move the number.
10. Cover the **edge cases** of each target, not just the happy path: null/blank/absent inputs, boundary values, error and fallback branches, dialect/config splits (H2 vs Postgres, vector on/off), and concurrency contracts where the class documents one.
11. Follow the house test idioms — violating these produces flaky false-reds:
    - Backend: JUnit 6 extending Play's `UnitTest`/`FunctionalTest`; seed HTTP-visible data via `commitInFreshTx` (+ `awaitCommitted` where reads race); Lucene-touching tests use `LuceneTestSync` (`openForTest`/`closedForTest`/`release`); never flip process-global statics (unit + functional lanes run concurrently); no `Thread.interrupt` on DB-touching threads.
    - Frontend: Vitest + jsdom; `clearNuxtData()` in `beforeEach` when varying endpoints across mounts; match existing `frontend/test/` structure and naming.
12. Fast feedback loop: compile new backend tests with `./gradlew compileTestJava` before paying for a full `play autotest`.
13. **Hard prohibitions** — any of these invalidates the run:
    - editing `sonar.coverage.exclusions` (build.gradle.kts), `conf/jacoco-agent.properties`, vitest `coverage` config, or `bin/coverage-blend.mjs` exclusion lists;
    - adding `NOSONAR` or coverage-suppressing annotations;
    - assertion-free or tautological tests (`assertNotNull(new Foo())`), tests of trivial getters/setters/DTOs farmed for percentage, or tests that restate the implementation line-by-line;
    - deleting or hollowing out production code to shrink the denominator;
    - weakening an existing test to make a new one pass.

**Phase 4 — Loop on the number**

14. After each batch: re-run the **full Phase 1 measurement** (fresh `rm -f jacoco.exec` included) and compare against the target.
15. Commit each green batch locally with a clear message (e.g. `test(memory): cover JpaMemoryStore dialect fallback + KNN edge cases`), so progress survives context limits and a re-invocation of `/coverage` resumes from the last commit. **Never push** — `/deploy` owns that.
16. Repeat Phases 2–4 until the blended number is **≥ target** with both suites green. If the remaining gap consists only of regions classified unreachable/flagged in Phase 2, stop and report the ceiling honestly rather than manufacturing tests for untestable code.

**Phase 5 — Adversarial quality pass (independent of the number)**

17. Once the target is met, spawn a review subagent over **only the tests added in this run** with the brief: "Find tests that would still pass if the code under test were broken — missing/weak assertions, mirrored implementations, mocks that assert the mock. Report file:line + why." Fix everything it confirms; re-run the affected suites. The number gate says *enough* testing exists; this gate says the testing is *real*.

**Phase 6 — Report**

18. Summarize: baseline → final blended/backend/frontend percentages; files covered with the behaviors + edge cases now locked in (one line each); regions flagged untestable and why; commits created; adversarial-pass findings and fixes; and the remaining top-10 gap list for a future run. Leave everything as local commits for the user to review and `/deploy`.

---

**Hard rules**

- The exit condition is `bin/coverage-blend.mjs` output — never a subjective "this feels covered", never the last Sonar analysis (it only updates on push).
- Always `rm -f jacoco.exec` before a measurement run; always re-`play precompile` before rendering the report.
- Suites must be green at every commit. Never `--no-verify`, never `JCLAW_SKIP_TESTS=1`.
- Never touch exclusion lists, thresholds, or the blend script. Flag untestable code; don't silence it.
- Tests assert behavior and cover edge cases, or they don't ship — a covered line with a hollow test is worse than an uncovered line, because it lies.
- Stop at local commits. Pushing is `/deploy`'s job, on the user's explicit invocation.
- If a *pre-existing* test failure blocks measurement, report it and stop — don't paper over someone else's red to keep going.
