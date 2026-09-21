---
name: renovate
description: Audit what Renovate cannot see — version caps whose lift conditions have expired, and vendored specs with no artifact to bump — then discover origin/renovate/* branches, merge each into local main one at a time, and validate by ecosystem — play autotest for backend bumps, pnpm test/lint/typecheck/stylelint for frontend bumps (with a full lockfile regen). Stops at the local merges; hand off to /deploy to push.
category: Maintenance
tags: [renovate, dependencies, merge, git, frontend, backend, tests]
argument-hint: "[empty | caps | frontend | backend | <branch-substring>]"
---

**Renovate Merge Workflow**

Incorporate the dangling `renovate/*` dependency-bump branches that Renovate pushes to **origin** (Bitbucket) but that never auto-merge (Jenkins doesn't gate-build them). Merge each into **local `main` one at a time**, and validate each by its ecosystem: **backend** bumps run `play autotest`; **frontend** bumps run the frontend gate (`pnpm test` + `lint` + `typecheck` + `stylelint`). Use `/usr/bin/git` for every git invocation (project convention).

This works on `main` directly — the user wants the bumps *merged with main*, so there is **no worktree**. Stop at the local merge commits; **never push** — pushing is `/deploy`'s job.

**Arguments** — `$ARGUMENTS` may be:
- *(empty)* → every `renovate/*` branch on origin that isn't already merged.
- `frontend` → only branches that touch `frontend/**`.
- `backend` → only branches that touch gradle/Java build files.
- `caps` → run **Phase 0 only**: audit the version caps and the vendored specs, and stop. No branches are merged.
- a substring (e.g. `okhttp`, `nuxt`) → only `renovate/*` branches whose name matches.

Reject anything else with a clear message; do not guess. If no matching renovate branches exist, say so and stop — there's nothing to do.

---

**Phase 0 — Audit what Renovate cannot see (always, unless `$ARGUMENTS` names a substring)**

Two kinds of dependency are invisible to Renovate: a version cap, which suppresses the update it would otherwise propose (0a–0e), and a specification jclaw has vendored because there is no artifact to depend on (0f–0g). Both drift silently.

A version cap suppresses the PR *entirely*, so a cap whose cause has expired is invisible: there is no branch to notice, and the config keeps describing a world that has moved on. Renovate will never tell you. This phase is the only thing that will.

0a. **Enumerate every blocker in `renovate.json5`**, and sort them into the two kinds — they are not the same thing and only the first is in scope here:
   - **Blocking:** `allowedVersions` with an upper bound (`<7`), and any `enabled: false` rule. These stop the PR being opened at all.
   - **Not blocking:** `allowedVersions` with a *lower* bound (`>4.4.5` is a floor — it blocks nothing going forward), `automerge: false`, `minimumReleaseAge`, and group rules. These only shape *how* an update lands. List them for completeness and move on.

0b. **For each blocking cap, test its lift condition against the installed tree — not against the comment.** The comment records why the cap was added, which is not the same as whether it still applies. Two rules, both learned the hard way:
   - **Check the property, never a proxy for it.** A condition written as a version number ("lift once vue-tsc > 3.3.7") or as an expected release shape ("lift when @nuxt/test-utils ships a major") *will* drift: the first green-lights a release that still carries the breaking call, and the second never fires when the fix arrives in a patch. Restate the condition as something greppable — *is the `typescript/lib/tsc` shim still in `vue-tsc/index.js`?*, *does `peerDependencies.vitest` admit 5?* — and answer that.
   - **Validate the check before believing a negative.** `grep -r` does **not** follow symlinks, and pnpm links every package into `node_modules/.pnpm/`, so a recursive grep over `node_modules/<scope>/` silently returns zero. Use `find <pkg> -name "*.d.ts" -exec grep -l …`, and run it once against a token you know is present. A sweeping "absent" that would imply the library deleted its own API is a broken check, not a finding. The same applies to a registry fetch: a 404 can return valid JSON, so read the status or the raw body before parsing.

0c. **Report the verdict per cap, and say what lifting would cost** — this is the difference between a decision and a guess:
   - **STILL VALID** — name the evidence (`vue-tsc 3.3.11 index.js:73 still has the shim`). No action.
   - **STALE, and lifting is a bump** — the dependency moves and nothing else does. Cheap.
   - **STALE, and lifting is a migration** — calling code has to change too. Name the blast radius (which files, how many call sites) before offering it.
   Then **ask the user which stale caps to act on, and let them decline.** Never lift a cap autonomously: the cap is a decision someone made, and re-taking it is theirs. If the user says yes, remove the cap and do the upgrade **in the same commit** — a lifted cap with no bump behind it is just a hole.

0d. **When a cap is lifted, delete its comment with it, along with any sibling rule that existed only to announce the unblock** (e.g. a `prBodyNotes` rule watching for the release that was supposed to be the signal). Leaving either behind is a changelog in the config; the reasoning goes in the commit message (AGENTS.md §8).

0e. **If a cap's *stated reason* turns out to be wrong** — not merely expired, but never accurate — say so plainly in the report and the commit message. Both caps lifted on 2026-09-21 had mis-specified lift conditions, and that is worth more to the next reader than the bump itself.

0f. **Check the vendored GenAI semantic conventions against upstream.** jclaw owns `services.telemetry.GenAiAttributes` and `GenAiMetrics` because the GenAI conventions moved to a repository that publishes no Java artifact, so there is nothing for Renovate to bump — a key renamed upstream leaves jclaw emitting the old one, silently. This has already happened once: `gen_ai.usage.cache_creation.input_tokens` became `gen_ai.usage.cache_write.input_tokens`.
   - **Source:** `open-telemetry/semantic-conventions-genai`, `model/gen-ai/registry.yaml` for attributes and `model/gen-ai/metrics.yaml` for metrics, on `main` — the repository cuts no releases. Name the path exactly: other directories (`model/aws-bedrock/`) carry their own `registry.yaml`, and taking the first match reads the wrong file.
   - **Schema:** the Weaver format keys attributes by `key:` and metrics by `name:` — not `id:` or `metric_name:`. An enum attribute's `type:` nests a `members:` list; it is a string on the wire, not a type mismatch.
   - **Validate before believing a "missing".** Confirm a key you know is present (`gen_ai.request.model`) resolves first. A broken parser reports every key missing, which reads exactly like wholesale drift — and each of the three mistakes above produced a confident false drift report on 2026-09-21.
   - **Compare** every `GenAiAttributes` key by name and type, and every `GenAiMetrics` constant by name and unit (a collector aggregates by both).

0g. **Report drift; don't apply it.** For each difference, say whether it is a rename (same meaning under a new key — read the upstream `brief` to confirm), a removal, or a type or unit change. A wire-name change breaks any dashboard or query keyed on the old name, so the user decides. When they choose to align, change `GenAiAttributes`/`GenAiMetrics` and `GenAiWireNamesTest` together — the pin test fails until both move — and update the pinned commit in `GenAiAttributes`' Javadoc to the one you checked. Rename only the emitted OTel key, never by blind search-and-replace: a provider's response field can share the old spelling. Anthropic's `cache_creation_input_tokens`, which `LlmProvider` reads, is not the OTel attribute and must not change.

With `$ARGUMENTS` = `caps`, stop here. Otherwise continue to Phase 1; a stale cap and a pending branch are independent, and the run handles both.

---

**Phase 1 — Discover & classify**

1. `/usr/bin/git fetch origin --prune` to refresh the remote branch list and drop deleted ones.
2. List candidates: `/usr/bin/git branch -r --list 'origin/renovate/*'`. Drop any already merged: skip a branch where `/usr/bin/git merge-base --is-ancestor origin/renovate/<b> main` is true (nothing new to bring in). Apply the `$ARGUMENTS` filter.
3. **Classify each surviving branch by its changed files** (authoritative — branch names are only a hint): `/usr/bin/git diff --name-only main...origin/renovate/<b>`.
   - Touches any `frontend/**` (typically `frontend/package.json`, `frontend/pnpm-lock.yaml`) → **FRONTEND**.
   - Touches `build.gradle.kts`, `settings.gradle.kts`, `gradle/**`, `*.gradle.kts`, or `gradle.properties` → **BACKEND**.
   - Touches both → **BOTH** (run both suites).
   - Touches neither code path (e.g. only `.github/**` or a renovate config) → **DOCS/CONFIG** (merge, no suite — note it).
4. Present a **plan table** — branch, ecosystem, what it bumps (read the `package.json` / `build.gradle.kts` hunk to name the dependency + version), already-merged-skips — and get a quick confirmation before any merge.

**Phase 2 — Pre-flight**

5. Confirm the primary tree is on `main` with a **clean working tree** (`/usr/bin/git status -sb`). If dirty, stop and tell the user — don't merge onto uncommitted work.
6. **A live instance does not need stopping — do not ask to.** `play autotest` resolves its port from `certs/.env`'s `PLAY_TEST_PORT` and binds that, not `:9000`. Measured 2026-09-21: an instance live on `:9000` survived a full suite run untouched while the test server held `:9300` for the duration. The earlier wording here claimed the suite needs a clean `:9000`; it does not, and acting on that claim costs a stop, a restart and a decision for nothing.
   What the suite *does* do is truncate `logs/system.out` and redirect the test server's output into it, so a live instance loses its own log every run — and with it the evidence if it exits for an unrelated reason. `./jclaw.sh test` snapshots it to `logs/system.out.pre-test` first; bare `play autotest` does not. If you need to stop the instance for some *other* reason, confirm with the user first — never stop it autonomously (AGENTS.md).

**Phase 3 — Merge & validate (backend first, then frontend)**

Process **BACKEND** branches first, then **FRONTEND** — each ecosystem is batch-merged and validated with **one suite run** (dependency bumps within an ecosystem rarely interact; one suite per ecosystem is the cost/confidence sweet spot).

7. **Backend: merge all, then one suite** —
   - Merge every backend branch in sequence: `/usr/bin/git merge --no-edit origin/renovate/<b>`. On a `build.gradle.kts` version-pin conflict, resolve toward the renovate bump (take the higher/incoming version) and note it; on a non-trivial code conflict, stop and surface it.
   - After the last backend merge, run `play autotest` **once**. **Pass** → all backend bumps are in. **Fail** → bisect by peeling merges off the top (`/usr/bin/git reset --hard HEAD~1`, re-run the suite) until it passes, marking each peeled branch **FAILED/skipped**; with ≤3 branches, peeling newest-first converges in at most N−1 extra runs and only pays that cost on the rare failure. Never guess the culprit without a passing baseline.

8. **Frontend: merge all, regen, then one gate** — Renovate frontend branches are usually **lockfile-only** (`^x.y.z` ranges in `package.json` already cover minor/patch), so they conflict with each other in sequence:
   - Merge each: `/usr/bin/git merge --no-edit origin/renovate/<b>`, falling back to `/usr/bin/git merge --no-edit -X theirs origin/renovate/<b>` on a `pnpm-lock.yaml` conflict.
   - **After all frontend branches are merged, fully regenerate the lockfile — do NOT trust plain `pnpm install` to repair it.** `-X theirs` can leave inner snapshot refs pointing at versions absent from the lockfile body, and pnpm's surface-hash check happily skips re-resolution ("Already up to date" in ~110ms). The only reliable regen:
     ```bash
     cd frontend && rm -rf node_modules .nuxt pnpm-lock.yaml && pnpm install
     ```
     (`.nuxt` is cleared too — a stale `.nuxt`/vite cache after a dep bump produces FALSE GREENS.)
   - **Validate strictly + run the frontend gate** (from `frontend/`):
     ```bash
     pnpm install --frozen-lockfile   # Jenkins parity (Jenkinsfile:57) — must be "Already up to date" in ~110ms, no errors
     pnpm test
     pnpm lint
     pnpm typecheck
     pnpm stylelint
     ```
   - **Commit the regenerated lockfile** once the gate is green (one suite run for the whole frontend batch, mirroring the backend phase): `/usr/bin/git add frontend/pnpm-lock.yaml && /usr/bin/git commit -S -m "chore(deps): regenerate pnpm-lock.yaml after renovate frontend merges"`. The `-S` is explicit even though `commit.gpgsign=true` is set globally — it documents the intent here and survives if that config is ever disabled or the run happens on a machine missing it. If the gate fails, report it and let the user decide which bump to drop — don't guess which branch broke the batch.

   Note: the full regen may pick up patches slightly newer than each branch targeted (e.g. a transitive `17.10.0 → 17.11.0`), all within the existing `^x.y.z` ranges — that's the same "latest within range" intent Renovate had, done in one pass. Fine.

**Phase 4 — Report & hand off**

9. Summarize in a table: branch · ecosystem · dependency bumped · merged/skipped · test result. State how far `main` is now ahead of `origin/main` (these merge commits are **local and unpushed**).
10. **Stop here.** Hand off to the user for `/deploy` (the only path that pushes). Do not push, do not run `/deploy` yourself.
11. **Optional cleanup, only if the user asks:** delete the merged remote branches. Renovate runs as a **weekly** Jenkins job (not continuously), so this is safe immediately and they won't bounce back; once `/deploy` lands the bumps on `origin/main`, Renovate won't recreate them. This is the one push allowed outside `/deploy` (it touches no commits on `main`). The delete-push still fires `.githooks/pre-push` (full suite on HEAD) — since you just validated, pass `JCLAW_SKIP_TESTS=1`:
    ```bash
    JCLAW_SKIP_TESTS=1 /usr/bin/git push origin --delete renovate/<a> renovate/<b> …
    ```

---

**Hard rules**
- Merge onto `main` directly — never a worktree (the bumps must land on main).
- **Never `git push` or run `/deploy`** as part of this command — stop at the local merge commits. The lone exception is the Phase-4 branch-deletion push, and only when the user explicitly asks for cleanup.
- Never `--no-verify`, `--force`, or any hook/signing bypass (except the documented `JCLAW_SKIP_TESTS=1` on the cleanup delete-push).
- The frontend **lockfile regen is mandatory** after the merge cascade — a plain `pnpm install` is not sufficient and gives false greens.
- Validate by ecosystem: backend → `play autotest`; frontend → `pnpm test` + `lint` + `typecheck` + `stylelint` (+ `pnpm install --frozen-lockfile`). A BOTH branch runs both.
- Confirm before stopping jclaw for the backend suite; restart it afterward only if it was running.
- A failing backend bump is undone (`reset --hard HEAD~1`) and skipped, not forced through; a failing frontend batch is reported for the user to triage.
- **Never lift a version cap without the user choosing it.** Report the verdict and the cost; the decision is theirs. A lift and the upgrade behind it land in one commit, never separately.
- Never apply vendored-spec drift unasked: a renamed wire key breaks whatever keys on the old one. Report it (0g) and let the user choose.
- Never trust a negative from an unvalidated check — a sweeping "absent" across a dependency's whole API is a broken grep (symlinks, `.pnpm/`) far more often than it is a real finding.
