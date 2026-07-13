---
name: quality
description: Four-pass source-quality sweep of app/ — trim verbose/redundant comments, remove provably-dead code, modernize to Java 25 idiom, and drop unused dependencies — each behavior-preserving, applied in an isolated worktree, and gated on spotlessApply + compile + play autotest. Destructive passes (dead code, deps) present evidence and confirm before removing.
category: Quality
tags: [quality, refactor, cleanup, dead-code, java25, modernization, dependencies, comments, worktree]
argument-hint: "[empty | comments | deadcode | modernize | deps] [path-scope]"
---

**Code Quality Sweep Workflow**

Run up to four behavior-preserving cleanup passes over JClaw's production Java: **(1)** trim verbose/redundant comments, **(2)** remove code that is *provably* dead, **(3)** modernize stale syntax to idiomatic Java 25, and **(4)** remove dependencies that are *provably* unused. Every pass runs in an **isolated git worktree**, is validated with `./gradlew spotlessApply && ./gradlew compileJava compileTestJava` and — for the passes that change behavior surface (dead code, modernization, deps) — a full `play autotest`, and lands as its **own commit**. Nothing is pushed. Use `/usr/bin/git` for every git invocation (project convention).

The bias throughout is **conservatism**: this codebase already scored A-/A on Clean Code and Imports & Idiom in the JCLAW-717 audit — its comments are largely intentional "why"s, its idiom is already broadly Java-25, and its deps are lean. So the job is to find the genuine residue, not to churn healthy code. **When in doubt, leave it and report it** rather than change it.

**Arguments** — `$ARGUMENTS` may be:
- *(empty)* → all four passes in order (comments → dead code → modernize → deps), scoped to `app/`.
- a pass word — `comments` | `deadcode` | `modernize` | `deps` → run only that pass.
- an optional **path scope** as the last token (e.g. `deadcode app/services`, `modernize app/tools/TaskTool.java`, or just `app/channels` to sweep that subtree). Defaults to `app/`.

**Scope & the deps pass.** The comments, dead-code, and modernize passes honor the path scope. The **deps pass (Phase 4) is whole-tree by nature** — it analyzes `build.gradle.kts` against usage everywhere — so it runs **only when the scope is the full production tree**: an empty/default run, or an explicit `app/` / `app`. A narrower subtree scope (`app/utils`, a single file, …) runs passes 1–3 and **skips Phase 4 with a one-line note** pointing the user at `/quality deps`. Naming the pass explicitly (`/quality deps`) always analyzes the whole tree, ignoring any trailing path.

Reject anything else with a clear message; do not guess.

**Always out of scope** (never touch): the vendored `app/com/aspose/**` blob, anything under `precompiled/` or generated, `conf/routes` semantics (read it, don't rewrite it), and the entire `frontend/` tree. The `test/` tree is read (to prove usage) and only edited when a pass legitimately requires it (e.g. a test referencing code you're modernizing).

---

**Phase 0 — Setup: isolated worktree + scope**

1. Create a dedicated worktree off `main` so nothing touches the primary tree (its dev server keeps running):
   ```bash
   /usr/bin/git worktree add ../jclaw-quality -b quality-sweep
   ```
   The `post-checkout` hook seeds `certs/.env`. Pin the test port to 9350 (or the next free port if 9350 is bound — note which):
   ```bash
   grep -q '^PLAY_TEST_PORT=' ../jclaw-quality/certs/.env || printf 'PLAY_TEST_PORT=9350\n' >> ../jclaw-quality/certs/.env
   ```
   Do all work in `../jclaw-quality`. Establish a green baseline first (`cd ../jclaw-quality && play autotest`) so any later red is attributable to the sweep, not a pre-existing flake.
2. Resolve the **scope set**: the `.java` files under the path argument (default `app/`), minus the always-out-of-scope paths above. Report the file count so the run's breadth is visible.

---

**Phase 1 — Trim verbose / redundant comments**

Goal: comments become concise; genuinely unnecessary ones are removed. This is the lowest-risk pass (comments don't affect compilation) but the **highest-judgment** one — the audit explicitly praised this codebase's comment culture ("comments explain *why*, anchored to JCLAW ticket IDs, capturing threat models and perf rationale"). Do not strip that value.

3. **Remove or tighten** — comments that restate the code (`// increment i`, `// return the result`), redundant Javadoc that only echoes the signature, commented-out dead code, stale TODOs already done, and multi-sentence preambles that could be one line. Collapse a 5-line comment that makes one point into that one line.
4. **Keep (do NOT trim)** — anything that explains *why* over *what*: threat-model notes, performance/allocation rationale, framework-quirk warnings (Play control-flow-throws, JDK bug refs, hot-path caveats), `JCLAW-xxx` ticket anchors, non-obvious invariants, and `catch (X _)` intentional-ignore justifications. When a comment's value is ambiguous, keep it.
5. Apply edits across the scope set, then verify nothing else moved: `./gradlew spotlessApply` and `./gradlew compileJava compileTestJava` (comments shouldn't break compilation — if they do, an edit strayed into code). Commit:
   ```
   docs(app): tighten verbose/redundant comments
   ```
   Report roughly how many comments were trimmed vs removed, and name any you deliberately kept despite verbosity (with why).

---

**Phase 2 — Remove dead / redundant code**

Goal: remove code **definitely** not in use. The standard is *proof of non-use*, not *absence of an obvious caller* — Play reaches a lot of code indirectly.

6. **Candidate discovery** — private methods/fields with zero references, unreferenced classes, unreachable branches, redundant local variables, dead `else`/guard arms, and duplicated helpers superseded by a shared one. Unused *imports* are handled by `spotlessApply` — don't hand-remove them.
7. **Prove non-use before proposing removal** — a symbol is only a candidate if it survives ALL of these:
   - `grep` the whole tree (app + test) for every reference, including string-literal references.
   - **Not** reachable via Play's indirection: a public method on a controller invoked by `conf/routes`; a `Model` finder / lifecycle callback; an `@OnApplicationStart` / `@Every` / `@On` job; a `@Before`/`@After`/`@Catch` interceptor.
   - **Not** loaded reflectively: `Class.forName`, `ServiceLoader`/`META-INF/services`, the `services.compression` SPI, tool/channel/harness registries (a tool wired into a registry map *looks* unreferenced but is dispatched by name).
   - **Not** a serialization/JSON/DTO field a serializer reads reflectively (Gson/Jackson), nor an entity column mapped by JPA.
   - **Not** consumed only by the frontend over an HTTP endpoint.
   If a symbol is reachable by any of these, it is **not** dead — leave it. If evidence is merely *thin* (can't find a caller but can't rule out reflection), classify it **"possibly dead — needs human confirmation"** and report it; do not remove it.
8. **Present the candidate list** (symbol, file:line, the non-use evidence) and get a quick confirmation before deleting anything. Then remove the confirmed-dead code, plus only the orphans your own deletion creates.
9. Verify: `./gradlew spotlessApply`, `./gradlew compileJava compileTestJava`, then `play autotest`. A compile error or a newly-red test means the code wasn't dead — restore it and reclassify. Commit:
   ```
   refactor(app): remove dead/unreachable code
   ```

---

**Phase 3 — Modernize to Java 25 idiom**

Goal: upgrade stale syntax to modern Java 25, behavior-preserving. This codebase is *already* broadly modern (records, sealed hierarchies, arrow-switch, text blocks, `var`, pattern-`instanceof`, `_` are pervasive) — so target the **residue**, and never re-churn code that's already idiomatic.

10. **Modernization targets** (apply only where it's a clear, behavior-preserving win):
    - Anonymous inner classes → lambdas / method refs (mind the Play `Model` finder trap: `S1612` method refs on finders bind to `GenericModel` and break at runtime — keep those as lambdas).
    - Old `switch` statements → arrow `switch` expressions; add record-deconstruction patterns where a chain of `instanceof`+cast begs for it.
    - `instanceof X x` binding to drop explicit casts; unnamed `_` for ignored bindings/catch vars.
    - Verbose `StringBuilder` concatenation → **text blocks** where a multi-line literal is clearer.
    - Explicit generic types → `var` where the RHS makes the type obvious (don't `var` where it hurts readability).
    - JDK helpers over hand-rolled equivalents: `Math.clamp`, `HashMap.newHashMap`, `List/Map.of`, `String.isBlank`/`strip`, `Optional` at internal boundaries (the JCLAW-705 convention), Sequenced-Collection accessors, `StringBuilder.isEmpty()`.
    - Interface constants/utility classes → `record`s / `sealed` where a real closed hierarchy or value carrier is hiding in old class-based code.
    Do **not**: introduce virtual threads (opt-in, config-gated — out of scope here), change public API shapes, or "modernize" purely for style where the old form is equally clear.
11. Apply per file, `./gradlew compileJava compileTestJava` frequently (catch a bad rewrite early), then across the scope `./gradlew spotlessApply` and `play autotest` — a modernization that changes a test outcome changed behavior and must be reverted. Commit:
    ```
    refactor(app): modernize to Java 25 idiom
    ```
    Report the transformations by kind and count.

---

**Phase 4 — Remove dead dependencies** *(whole-tree scope only)*

**Gate:** run this pass only when the scope is the full production tree — an empty/default run, or an explicit `app/` / `app` (or a direct `deps` invocation). For any **narrower subtree scope**, do not attempt a partial dependency analysis: skip the pass and report one line — *"Deps analysis is whole-tree; skipped for this subtree scope — run `/quality deps` to sweep dependencies."*

Goal: remove `build.gradle.kts` dependencies **definitely** unused. This is the highest-risk pass — a dep can be needed at *runtime* with no compile-time import, so a dropped dep may only fail under `play autotest`, not `compileJava`.

12. **Enumerate** the declared dependencies (per configuration: `implementation`, `testImplementation`, `runtimeOnly`, `compileOnly`, annotation processors, and the composite `/opt/play1` plugin deps). For each, search app + test for imports and reflective/string usage of its packages.
13. **A dependency is a removal candidate only if** it has zero compile-time imports **and** is not a known runtime-only kind:
    - JDBC drivers (H2, Postgres), logging backends/bridges, SPI/`ServiceLoader` providers, Play 1.x plugins, Gradle plugins, annotation processors (used at build time, not imported), and anything pulled in a resource/config file (`application.conf`, `META-INF/services`).
    - Framework-provided deps: much of the stack (JPA, Play, JUnit) is transitively provided by the `play1` plugin — don't "remove" something the framework owns.
    If a dep is only *possibly* unused, classify it **"possibly unused — needs human confirmation"** and report it; do not remove it.
14. **Present the candidate list** (dependency, configuration, the zero-usage evidence, why it's not runtime-only) and get confirmation. Remove confirmed-dead deps one at a time.
15. Verify after **each** removal: `./gradlew compileJava compileTestJava` **and a full `play autotest`** (the runtime-only failure mode only surfaces in tests). Any red → the dep was live; restore it. `./gradlew spotlessApply`, then commit:
    ```
    build(deps): drop unused dependencies
    ```

---

**Phase 5 — Validate & report**

16. Final gate from the worktree: `cd ../jclaw-quality && ./gradlew spotlessApply && play autotest`. Confirm the JCLAW-684 green signal — the log contains `~ All tests passed` **and** there are no `test-result/*.class.failed.html` sentinels (exit code alone can lie). 
    - **Env-flake guard:** if a broad batch of *unrelated* controller/functional tests fails (401s, FK violations, `awaitCommitted` timeouts), that's the known live-app / load interference (the primary tree's dev server adds load) — confirm the port is isolated (9350) and re-run once; don't chase it as a real failure.
17. Summarize per pass: comments trimmed/removed (+ any kept-despite-verbosity), dead-code symbols removed (+ any "possibly dead" left for the human), modernizations by kind, and deps dropped (+ any "possibly unused" left) **or the "skipped — subtree scope" note** — plus the worktree path (`../jclaw-quality`), branch (`quality-sweep`), the per-pass commit hashes, and the final test result. Leave the branch for the user to review and merge or `/deploy`. **If no pass produced a commit** (e.g. an already-clean subtree, as `app/utils` is post-audit), say so plainly and remove the empty worktree (`/usr/bin/git worktree remove ../jclaw-quality`) rather than leaving an empty branch to review.

---

**Hard rules**
- **Proof, not guesswork.** Remove code or a dependency only when non-use is *proven* against Play's indirection (routes, reflection, SPI, lifecycle, serialization, frontend). Anything merely-probably-unused is reported as "needs human confirmation," never removed.
- **Confirm before the destructive passes.** Present the dead-code and dead-dep candidate lists with evidence and get a quick OK before deleting; comments and modernization apply directly (they're behavior-preserving and git-revertable) but are still reported.
- **Every pass is behavior-preserving and gated.** `spotlessApply` + `compileJava`/`compileTestJava` after every pass; a full `play autotest` after the dead-code, modernization, and dependency passes. Red means the change wasn't safe — revert it, don't force it.
- **Always `spotlessApply` before each commit** — the pre-push gate rejects import-order drift, and these passes (especially dead-code and modernization) churn imports.
- Work in the `../jclaw-quality` worktree only; never edit the primary working tree. **Never `git push` or merge** — that's the user's call (`/deploy`). Remove the worktree only on the user's say-so (`/usr/bin/git worktree remove ../jclaw-quality`).
- **Preserve the "why".** Keep threat-model, perf, framework-quirk, and `JCLAW-xxx`-anchored comments even when terse trimming is tempting; only *what*-restating and dead-commented code go.
- **Don't over-modernize.** No virtual-thread introduction, no public-API reshaping, no style-only rewrites of already-idiomatic code, and keep the known Java-25 traps in mind (`S1612` method-refs on Play finders stay lambdas).
- One commit per pass, each independently revertable. Never run an interactive `jshell` to "probe" — it blocks on stdin and orphans; use `./gradlew compileJava`.
