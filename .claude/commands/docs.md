---
name: docs
description: Detect and fix documentation drift across every kind of documentation docs/README.md catalogs — README.md, AGENTS.md, the user guide, the component READMEs, and the comments and Javadoc in source and config files — fixing stale and incorrect text in place, correcting wrong facts in the architecture snapshot without expanding it, and reporting drift in text that ships as behaviour.
category: Documentation
tags: [docs, documentation, drift, comments, javadoc, maintenance]
argument-hint: "[check] [empty | <path|cluster> | comments]"
---

**Docs Drift Workflow**

Sweep JClaw's documentation and realign it with what the code actually does. Documentation here means every kind that [`docs/README.md`](../../docs/README.md) catalogs: the Markdown, the comments and Javadoc in source and config files, and the text the product ships (UI copy, CLI help, tool descriptions, prompts). Look for two defects: **stale** text, true once until the code moved under it, and **incorrect** text, which the code contradicts however it got that way. Triage before editing, fix the legitimate items surgically, and stop at a local commit. Use `/usr/bin/git` for every git invocation (project convention).

**Arguments**

`$ARGUMENTS` may be one of the following, optionally preceded by `check`:

- *(empty)* → full sweep: every prose doc in full, plus the mechanical and change-driven passes over documentation in source files (Phase 2b) and over shipped text (Phase 2c).
- a file path or cluster (e.g. `AGENTS.md`, `README`, `docs/user-guide/`, `sidecar`, `app/services/`, `frontend/composables/`, `jclaw.sh`) → scope the run to it. A source path gets the **full** comment pass: every comment and Javadoc block in it is verified, not only those near changed code. That is the pass that finds a comment that was never true.
- `comments` → only documentation in source and config files, across the whole tree: the mechanical and change-driven passes.
- `check` (or `--check` / `dry-run`) in front of any of these → **detect-and-report only**: produce the full drift table and make **no edits and no commit**.

Reject anything else with a clear message; do not guess.

---

**Scope — `docs/README.md` is the registry**

Read `docs/README.md` first. Its tables list every kind of documentation with where it lives, what it must agree with, and its **Handling**, which decides what this command may do:

- **fix** — reference prose (README, AGENTS.md, CONTRIBUTING.md facts, the user guide, the component READMEs): edit the drifted words directly.
- **fix + gate** — documentation in source and config files (Javadoc, code comments, docstrings, script, config and build comments): edit, then run that file type's gate from `docs/README.md` § Gates.
- **facts only** — the architecture snapshot, `docs/architecture/*.md`: a snapshot nothing regenerates (`/bmad-document-project`, which produced it, is deprecated), so a wrong fact is corrected in place. A wrong fact is one the code now contradicts: a version, a renamed or removed class, path, command or endpoint, a wrong default or count. Omissions are not drift: the snapshot predates later features, so never add coverage, sections or explanation. Skip plan documents (e.g. `jclaw-agentic-refactor.md`): their proposals are history, not a description of the code.
- **report** — text that ships as behaviour (UI copy, CLI help, tool descriptions, API summaries, prompt text, local skills, factory prompts, eval prose, test-enforced rationale) and policy statements: list with evidence; edit only the items the user approves one by one in Phase 3.
- **skip** — never touch: the seven upstream skills, `workspace/`, `.claude/commands/` and `.claude/workflows/`, planning and history, legal text, data files, and directives in comment syntax.

The catalog itself can drift. A tracked doc file that matches no row (`git ls-files '*.md' '*.txt'` against the rows' paths) is a finding: report it and propose its row, and leave it unswept until the row exists.

**Phase 1 — Establish ground truth (read code before touching any doc)**

Build a small "truth sheet" of the facts docs most often get wrong, sourced from the canonical files — never from another doc:

1. `conf/application.conf` → `application.version` (the live version) and any status line.
2. `frontend/package.json` → Nuxt major version, the `packageManager` pnpm pin (and its `+sha512` hash), Node `engines`, and the `scripts` block (the real `pnpm` targets: `dev`, `build`, `lint`, `test`, `typecheck`, `stylelint`, …).
3. `build.gradle.kts` + `settings.gradle.kts` → JDK level, the Play 1.x fork wiring, and notable dependency choices (e.g. the OkHttp-5 outbound stack, Lucene version).
4. `jclaw.sh` → the real subcommands (`setup`, `init-worktree`, `start`, `stop`, `restart`, `status`, `test`, `dist`, `bundle`, `loadtest`, …) and the **`play autotest`** convention (`play test` is interactive — never document it as the CI command).
5. `conf/routes` + `app/controllers/` → the API surface under `/api/**` that contracts/feature docs reference.
6. `.githooks/` → actual `pre-commit` (lint-staged on staged frontend files) and `pre-push` (full suite, per-HEAD cache) behavior.
7. `frontend/nuxt.config.ts`, `frontend/pages/`, `frontend/composables/` → the frontend feature surface the user guide describes (channels, chat, agents, subagents, tasks, reminders, settings, logs/dashboard, skills/tools/MCP).
8. **The baselines.** Prose: the last full sweep, `/usr/bin/git log -1 --format=%h --grep='^docs: realign the docs with the codebase'`. Comments: `/usr/bin/git log -1 --format=%h --grep='^docs(comments): realign the comments with the codebase'`, falling back to the prose baseline. `/usr/bin/git log --no-merges <baseline>..HEAD` and `/usr/bin/git diff <baseline>..HEAD` show what moved under the docs since; check the sections those changes touch first.

Treat **CLAUDE.md as a strong but fallible source**: it is usually the convention of record, but it can drift too — verify its claims against the code rather than assuming it is correct, and never propagate a CLAUDE.md fact into another doc without confirming it in the codebase.

**Phase 2 — Detect drift**

Fan the work out: one read-only auditor per cluster (a prose cluster, or one top-level package of `app/`, `frontend/`, `sidecar/` and the scripts). Give each auditor the truth sheet, its scope and the rules below, and have it return findings as text: the harness refuses report files written by subagents. Each finding is `file:line · current (short exact quote) · correct (minimal replacement) · evidence (code file:line) · confidence`, keeping only high and medium confidence.

*2a — Prose (fix, facts only).* For each in-scope file, cross-check its claims against the truth sheet and the code. Look for:

- **Stale version / status** strings (e.g. `v0.1.0-alpha`, an old `0.x.y`).
- **Wrong framework versions** (e.g. `Nuxt 3` where the code is on Nuxt 4; outdated JDK or Play levels).
- **Renamed / removed commands** (e.g. `play test` / `play auto-test` → `play autotest`; pnpm script names; `./jclaw.sh` subcommands that changed).
- **Broken file / directory references** — verify every path a doc names still exists.
- **Endpoint / feature mismatches** — feature lists or `/api` endpoints that no longer match `conf/routes`, controllers, or frontend pages.
- **On-screen strings** — the user guide quotes them exactly, so the `.vue` source is the truth for every label, button, heading and empty state it quotes.
- **Coverage gaps** — shipped capabilities with no doc coverage (e.g. a new channel or feature with no user-guide page). Flag these; do not silently auto-author whole new pages.
- **Broken internal markdown links** between docs, and `/guide#<page id>-<slug>` links (the slug rules are in `frontend/components/guide/GuideRenderer.vue`).

*2b — Documentation in source and config files (fix + gate).* Three passes, cheapest first:

- **Mechanical, whole tree, every run.**
  - Javadoc references need no pass: `compileJava` and `compileTestJava` run doclint (`docs/README.md` § Checking Javadoc references), so a tree that compiles has none.
  - Check the names comments cite outside doclint's reach — `Class.member`, `Class#member` and backticked identifiers, paths, config keys, `jclaw.sh` subcommands — in `//` comments, `{@code}` spans, and TypeScript, Python and script comments. Extract them with a script and confirm each exists with `git grep`, skipping JDK and library names.
- **Change-driven, since the comments baseline.** For each changed code hunk, read the documentation that describes it:
  - the Javadoc of every changed declaration;
  - comments inside the hunk or directly above it;
  - comments anywhere that name a symbol, key or default the diff renamed, removed or changed (take the old names from the `-` lines and grep for them);
  - comments that claim to match something else (`Mirrors`, `Same as`, `kept in sync`, `must match`) where either side changed: read the twin and check both still agree.

  A comment the diff left alone while the code under it changed is the prime suspect. Check the facts such comments state — numbers, defaults, limits, ports, durations, versions, counts — against the constant or config they describe.
- **Full, for a source-path scope.** The change-driven reading applied to every comment and Javadoc block in the path.
- **The first comments sweep has no baseline.** The fallback, the last prose sweep, misses everything older, so run it in full, one auditor per top-level package.

What counts: a comment or Javadoc block is **stale** when the code it describes changed and it did not, **incorrect** when the code contradicts it, and **dangling** when it names a symbol, file, key or command that moved or is gone. What does not count: a comment that is true but verbose, restates the code, or is spelled the British way. That is `/quality`'s comments and spelling passes, so report nothing here for style and never trim.

When a comment and the code disagree and the **code** looks wrong — the comment states the intent and the code reads like a defect — the verdict is REPORT: never rewrite a comment to bless a bug.

*2c — Text that ships as behaviour (report).* Apply the change-driven reading to the UI copy, CLI help, tool descriptions, API summaries, prompt text, local skills, factory prompts and eval prose in the catalog. A stale string there misleads an operator or a model at runtime, so it is worth finding, but it is behaviour and its fix needs the user's per-item approval.

Assign each item a verdict from its catalog row: **FIX** (fix, fix + gate, or a facts-only wrong fact) or **REPORT** (report rows, facts-only omissions, code that looks wrong, catalog gaps). Group by file.

**Phase 3 — Present the drift table & confirm**

Present a table (file · drift item · `current → correct` · handling · FIX/REPORT) covering every finding, and get a quick confirmation before editing — same triage-before-mutate discipline as `/sonar`. List REPORT items on their own so the user can approve individual shipped-text fixes. In `check` mode, stop here: this table **is** the deliverable.

**Phase 4 — Apply fixes (surgical)**

9. Edit only the drifted lines. Preserve each doc's voice, heading structure, and formatting. Do **not** rewrite whole sections, restyle prose that isn't drifted, or add speculative content. Every changed line must trace to a confirmed drift item.
10. Keep `CLAUDE.md` and `AGENTS.md` consistent on **shared** facts (versions, commands, framework levels).
11. For a genuine **coverage gap** that warrants a new user-guide page or section, confirm scope with the user (in Phase 3) before authoring — don't fabricate a page unprompted.
12. **Facts only:** in `docs/architecture/*.md`, change only the wrong words and numbers, keeping each doc's voice, tables and structure. Re-measure a count rather than copying it from the report.
13. **Comments and Javadoc:**
    - Make the claim true and no longer than it was, following AGENTS.md's Comments Carry Reasoning. Never write provenance ("now", "updated", "previously", the ticket of this change): that belongs in the commit message. If the claim cannot be made true in the same space, delete the stale clause rather than grow the comment.
    - Point a dangling `{@link}` at the symbol's new home (`/usr/bin/git log -S'<name>'` finds it). If the symbol is gone, rewrite the clause without the link.
    - Never edit a directive, an annotation, or an ArchUnit `because(…)`. A `*/` inside Javadoc text, such as a cron glob, closes the comment: rephrase it.
14. **Approved shipped text:** before editing, find the tests that pin the string (`/usr/bin/git grep -nF '<old text>' -- test frontend/test frontend/tests`) and update them in the same change.

**Phase 5 — Validate**

15. Re-verify that every command, script, subcommand, and file path the **edited** docs now reference actually exists (`./jclaw.sh` subcommands, `pnpm` scripts in `package.json`, routes/controllers, on-disk paths).
16. Confirm internal markdown and `/guide#` links in edited files resolve to real targets. Prove the link checker can fail first: feed it a copy with one bad anchor.
17. For every source or config file edited, run its gate from `docs/README.md` § Gates. Check first that no test run is in flight (`jcmd -l | grep -E "playAutotest|FirePhoque"`): never compile under one. The Java gate's compile also runs doclint, so a Javadoc edit that breaks a reference fails there.
18. For approved shipped-text edits, run the tests found in step 14.

**Phase 6 — Commit locally & report (stop here; never push)**

19. Stage only the files you actually changed with `/usr/bin/git add <paths>`, after checking `/usr/bin/git status` for other sessions' work.
20. Create **signed** local commits: one for prose, one for documentation in source files, so the source change reviews apart. The subjects double as the next run's baselines (Phase 1 step 8), so keep their prefixes exact: a full prose sweep is `docs: realign the docs with the codebase (v<VERSION>)`, a scoped one `docs: realign <scope> with the codebase (v<VERSION>)`, and the source-file commit `docs(comments): realign the comments with the codebase (v<VERSION>)`. The `-S` flag is explicit even though `commit.gpgsign=true` is set globally — this documents the workflow's intent in the file and survives if the global config is ever disabled or the run happens on a machine missing it. For the trailer, use the `Co-Authored-By:` line your standing instructions specify, which names the model actually authoring the commit. **Do not hardcode a model version in this file.** It goes stale at every model bump, and a wrong trailer breaks nothing — no hook, test, or push rejects it — so the drift survives indefinitely. If your instructions specify no trailer, omit it rather than guessing a model name.
    ```bash
    /usr/bin/git commit -S -m "$(cat <<'EOF'
    docs: realign the docs with the codebase (v<VERSION>)

    <one short paragraph: what drifted and what was corrected — e.g. version
    bump propagation, Nuxt 3→4, play test→autotest, fixed file references.>

    <Co-Authored-By trailer per step 20 — do not paste a model version from this file>
    EOF
    )"
    ```
    A commit staging `frontend/**` runs the `pre-commit` lint-staged hook; fix what it reports and commit again.
21. Report:
    - the prose files with their fixed-item counts;
    - the comments and Javadoc fixed, by package;
    - the facts-only corrections;
    - every REPORT item left: shipped text, code that looks wrong, policy, catalog gaps;
    - the commit hashes, and any coverage gaps deferred for a follow-up.

    **Do not push** — `/deploy` is the only path that ships docs to the remotes; leave the commits for the user to review and release.

---

**Hard rules**

- Triage before mutating — present the drift table and get a quick confirmation before editing any file (skip straight to the table in `check` mode and stop).
- Stop at the **local commit**. Never `git push`, tag, or release — `/deploy` owns that.
- Use `/usr/bin/git` for every git invocation. Never `--no-verify`.
- **Surgical edits only**: change drifted facts, never rewrite, reflow, or restyle documentation that isn't drifted. Match the surrounding voice.
- **Truth, not style**: fix what is stale or incorrect; never trim a comment for length or respell it. That is `/quality`.
- **Never rewrite a doc or comment to match code that looks wrong** — report it.
- **In `docs/architecture/*.md`, correct wrong facts only** — never expand, restructure or add coverage, and never edit a plan document.
- **Shipped text is behaviour**: edit UI copy, CLI help, tool descriptions, API summaries, prompts and local skills only with the user's per-item approval, and run the tests that pin them.
- **Never touch** what `docs/README.md` marks **skip**: the seven upstream skills, `workspace/`, `.claude/commands/` and `.claude/workflows/`, directives in comment syntax.
- **Never redirect a compile task's output** to run a check: Gradle deletes that task's previous outputs, and for `compileJava` that empties `build/classes/java/main`, which `ArchitectureTest` reads. Run javac directly into a scratch directory instead.
- **Verify before asserting**: confirm a command, script, endpoint, or path exists in the code before writing it into a doc. Never copy a claim from one doc into another without checking the codebase — and treat CLAUDE.md as fallible, not gospel.
- Don't auto-author entire new pages for coverage gaps without confirming scope first.
