---
name: renovate
description: Discover origin/renovate/* branches, merge each into local main one at a time, and validate by ecosystem — play autotest for backend bumps, pnpm test/lint/typecheck/stylelint for frontend bumps (with a full lockfile regen). Stops at the local merges; hand off to /deploy to push.
category: Maintenance
tags: [renovate, dependencies, merge, git, frontend, backend, tests]
argument-hint: "[empty | frontend | backend | <branch-substring>]"
---

**Renovate Merge Workflow**

Incorporate the dangling `renovate/*` dependency-bump branches that Renovate pushes to **origin** (Bitbucket) but that never auto-merge (Jenkins doesn't gate-build them). Merge each into **local `main` one at a time**, and validate each by its ecosystem: **backend** bumps run `play autotest`; **frontend** bumps run the frontend gate (`pnpm test` + `lint` + `typecheck` + `stylelint`). Use `/usr/bin/git` for every git invocation (project convention).

This works on `main` directly — the user wants the bumps *merged with main*, so there is **no worktree**. Stop at the local merge commits; **never push** — pushing is `/deploy`'s job.

**Arguments** — `$ARGUMENTS` may be:
- *(empty)* → every `renovate/*` branch on origin that isn't already merged.
- `frontend` → only branches that touch `frontend/**`.
- `backend` → only branches that touch gradle/Java build files.
- a substring (e.g. `okhttp`, `nuxt`) → only `renovate/*` branches whose name matches.

Reject anything else with a clear message; do not guess. If no matching renovate branches exist, say so and stop — there's nothing to do.

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
6. `play autotest` needs a clean `:9000`. If jclaw is live there (`lsof -nP -iTCP:9000 -sTCP:LISTEN`), **confirm with the user before stopping it** (the backend may be serving other work) — then `./jclaw.sh stop`, and offer to restart at the end. Never stop it autonomously.

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
   - **Commit the regenerated lockfile** once the gate is green (one suite run for the whole frontend batch, mirroring the backend phase): `/usr/bin/git add frontend/pnpm-lock.yaml && /usr/bin/git commit -m "chore(deps): regenerate pnpm-lock.yaml after renovate frontend merges"` (signed via the global config). If the gate fails, report it and let the user decide which bump to drop — don't guess which branch broke the batch.

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
