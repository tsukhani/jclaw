# AGENTS.md

This file is the canonical, harness-agnostic guide for AI coding agents working in this repository — read by Claude Code, Codex, Cursor, and other coding harnesses. (`CLAUDE.md` points here.)

<!-- bmad:context -->
<!-- Verified 2026-09-09 against f32b0cbc. Managed by bmad-project-context; edits inside this block are replaced on refresh. Keep anything you want preserved outside the markers. -->

## jclaw

AI agent platform on a Play 1.x fork (Java 25, virtual threads) with a Nuxt 4 SPA and seven Python sidecars the JVM spawns. Personal Edition: one admin operator, no user entity. Tickets are Jira JCLAW; planning lives in `_bmad-output/`; every rule below has its reasons in the sections that follow this block. Terms: a *turn* is one agent invocation, many messages and tool rounds; a *channel* is the origin (web, slack, telegram, whatsapp, voice, app), not a component; a *binding* attaches a channel to an agent; a *harness* is an external ACP coding agent, not this CLI; *Standing Orders* are the workspace files `SystemPromptAssembler` injects.

## Policy

- Never `git push`; `/deploy` is the only push, run from the main checkout on `main`, never a worktree. Never `--no-verify`, never force. Stop at the local commit and report the hash.
- Never `./jclaw.sh restart|stop`, or kill the server PID, without asking — the instance may be serving live work; a task that mentions a restart is not the approval.
- Never hand-edit `docs/architecture/` (generated), `skills/**` (vendored), or `workspace/main/*.md` (runtime persona).
- Commit messages and tags are public on the GitHub mirror: no credentials, customer specifics, or unreleased plans.
- Don't create worktrees or branches unless asked; work on `main`. Other sessions share this checkout: stage only your own hunks, never `git stash -u`.
- Ask before reading the `jclaw_api` token out of the Config table; read-only SELECTs on the live H2 (empty credentials, `AUTO_SERVER`) are fine.

## Where things are

- Rules with their reasons: the sections below. Settled verdicts before re-proposing work: `docs/spikes/`.
- Config validation lives in `ConfigService.setWithSideEffects` (a non-null return is the 403); `POST /api/config` writes any non-reserved key, so "not in the UI" is no reason to skip it. Never seed a key another selection already implies.
- The fixed-name agents `main`, `__loadtest__`, `__evaltest__` are provisioned by code — never seed or delete them in tests.

## Running and verifying

- Backend tests: `play autotest`, never `play test` (interactive). One class: `./gradlew playAutotest -Ptests=<Class>`, about 30 s; the full suite is 5–8 min. `./jclaw.sh test` runs all five checks to the end and prints the failure at the bottom.
- A test class runs only if it extends `play.test.UnitTest` or `FunctionalTest`; a plain JUnit class compiles, runs nothing, and the suite stays green — check `test-result/<Class>.class.passed.html` exists. Test sources are the default package: test seams must be `public`.
- Never pipe the suite (`| tail` returns tail's exit code); redirect to a file and check `test-result/*.failed.html`. Never compile while a run is in flight; `jcmd -l | grep -E "playAutotest|FirePhoque"` first. A `compileJava` that printed `UP-TO-DATE` measured nothing: `--rerun-tasks` when the compile is the evidence.
- Test classes run concurrently in one JVM: never flip a process-global without its lock (`LuceneTestSync`, `ShellSandboxSync`, `ToolRegistrySync`, `TelemetryTestSync`, `LoadTestHarnessSync`); scope shared-table assertions to your own rows; a "seeded row missing" red is usually the `Fixtures.deleteDatabase` race, not a regression.
- In a FunctionalTest, seed what your own HTTP request reads with `commitInFreshTx` — the body is already in a transaction and `Tx.run` joins it. Real HTTP against the autotest server 401s `password_unset` until `AuthFixture.seedAdminPassword` runs; the loadtest harness adds a warmup turn.
- Frontend: `cd frontend && pnpm test` after edits; `pnpm typecheck` is the TS gate, the LSP's `.vue` import errors are false. After a dependency bump, `rm -rf .nuxt node_modules/.vite && pnpm exec nuxi prepare` before believing a green; in a fresh worktree run `nuxi prepare` first or the suite false-REDs with zero tests.
- `pnpm test --coverage`, never `pnpm test -- --coverage` (vitest reads it as a filename and passes with no coverage). `pnpm test` never runs e2e; `./jclaw.sh e2e` needs a live instance answering `/api/status`.
- Pre-push runs a wildcard-import grep, `spotlessCheck` and `compileJava` before the suite: `./gradlew spotlessApply` after any import-moving change. A pre-commit `.distignore` warning on a new `app/**.java` is expected — dist ships precompiled — don't edit `.distignore`.
- Jenkins runs Sonar and coverage but never lint, typecheck or e2e; its release notes are the commit body only when the subject is exactly `Release vX.Y.Z`, which `/deploy` writes.
- Bumping the fork is two-sided: `.play-version` and `/opt/play1` at the same tag, rebuilt (`framework/src/play/version` is a build artifact); a mismatch fails Gradle configure, which reads as a spotless failure at pre-push. `./gradlew --stop` here does not stop the fork's daemon.
- Detect a running instance with `./jclaw.sh status` or `lsof -nP -iTCP:9000 -sTCP:LISTEN`, never `ps | grep` (macOS truncates argv) and never bare `lsof -ti :PORT` to kill (it matches the app's own client sockets).
- A broken `test/` class fails `./jclaw.sh dist|bundle`: precompile compiles tests without running them.

## Conventions that differ from defaults

- Config keys live in the Config DB and are documented in `conf/application.conf`; a key whose absence already means "off" is never seeded.
- `git grep -P`, never `-E`, for `\b`, `\d`, `\s` — macOS `git grep -E` drops them silently and matches nothing.
- `jclaw.sh` targets macOS bash 3.2 under `set -euo pipefail`: brace an interpolation before a non-ASCII character, put `|| true` inside `$( )`, use an `EXIT` trap rather than `ERR`.
- Tool names are `<singular_noun>_<verb>`: `conversation_send`, `subagent_spawn`.
- Frontend deps are gated on compile and tests, not release age: keep `minimumReleaseAge: 0`, pin Nuxt exactly, smoke `nuxt dev` on a Nuxt bump.
- A Settings panel reads a sidecar-status endpoint with `useLazyFetch`, never a top-level `await useFetch` — it cold-boots the sidecar and suspends the panel. Tests that remount a page call `clearNuxtData()` in `beforeEach`. Never add a `uv.lock` gate for the sidecars.

## Known pitfalls

- Never `Thread.interrupt()` or `Future.cancel(true)` a thread that touches H2/JPA: the JDK closes the FileChannel and the file DB dies; use a volatile flag. Never park many virtual threads on timed waits (JDK-8373224): schedule on a small platform-thread executor and block on an untimed `get()`.
- A pool drain destroys the H2 in-memory test DB with its last connection; hold one connection across it (`TelemetryDataSource.switchTo` is the reference).
- A new NOT NULL entity column needs `@ColumnDefault` — the ALTER fails on populated rows and the create-drop test DB never sees it. Raw-connection DDL must `commit()` explicitly; Hikari runs `autoCommit=false`.
- `Result` is a `RuntimeException`: a render inside `try {…} catch (Exception)` is swallowed into a 500 — assign in the try, render after it.
- `Model.find().fetch()` is raw: copy elementwise. Fix a lazy N+1 with `@BatchSize`, not a manual IN query. `FunctionalTest` has no PATCH helper.
- Bulk and cascade deletes skip `@PostRemove` and orphan Lucene docs — evict explicitly. A Lucene codec-boundary bump: wipe `data/jclaw-lucene/` while pre-v1 rather than add backward-codecs, and verify through the live search endpoint.
- ArchUnit rules import `build/classes/java/main`, never the running JVM's `CodeSource` — stale precompiled bytecode under FirePhoque manufactures violations.
- "Zero call sites" is not dead code: a retention Javadoc, `@SuppressWarnings("unused")` or a reflection test marks a deliberate keep. Drop an applied `renameKeyIfPresent` call site, keep the helper.
- `graphify update .` re-clusters and can dissolve curated communities; recover from the dated backup in `graphify-out/<date>/` and delete `.graphify_labels.json.sig`.
- JCLAW acceptance criteria forward-reference helpers and tickets that were never built: verify before designing around them.
- `memory.jpa.vector.queryPrefix` and `memory.recall.minCosine` move together. A CSP, if enabled, must allow `'unsafe-inline'` for `script-src`; Nuxt inlines `window.__NUXT__`. The scrape ladder never escalates a `POLICY_BLOCK`. A task without a pinned model follows its agent's current model.
- Personal Edition: `findById` without owner scoping is not IDOR, and the agent-reachable `/api/config`, `/api/providers`, `/api/mcp-servers`, `/api/channels` are deliberate — remediate at the seam (SsrfGuard, masking, the dangerous-verb gate), never by blocking the endpoint. Installer paths (Playwright Chromium, `uv run`, the JRE download) were audited and accepted 2026-08-28.

<!-- /bmad:context -->

## Project Overview

JClaw is an AI-powered automation platform built on **Play Framework 1.x** (Java) with a **Nuxt 4** (Vue 3 + TypeScript) SPA frontend. It combines OpenClaw agent orchestration and JavaClaw job scheduling into a single Java-first platform.

**Status**: pre-v1 (beta), work in progress.

## First-time setup

After every fresh clone (including `rm -rf` + re-clone cycles), run:

```bash
./jclaw.sh setup
```

This wires git hooks (`.githooks/`), installs frontend dependencies (so the pre-commit hook's `lint-staged` is available), and verifies both `origin` (Bitbucket) and `github` remotes are configured. Idempotent — safe to re-run any time.

Why it's needed: `.git/config` lives inside `.git/` which git refuses to track, and `frontend/node_modules/` is gitignored. So `core.hooksPath`, the `github` remote, and `lint-staged` itself don't survive a fresh clone. Without the setup, hooks silently don't fire and `/deploy` fails on the github push.

## Development Commands

### Backend (Play 1.x)
```bash
play run                  # Start dev server on :9000
play autotest             # Run all tests (unit + functional)
play dist                 # Build production distribution
```

### Property-based tests (jqwik)

Every jqwik `@Property` in the backend lives in **one class**, `test/PropertyBasedTest.java`. That
is a hard constraint, not a style preference: the play1 fork runs pure unit-test classes on a
16-way parallel lane and gives each class its own `LauncherFactory.create()`, while jqwik's engine
keeps process-global mutable state (`StoreRepository.current` is a plain static). Two
property-bearing classes executing at the same time corrupt each other. Measured on jqwik 1.10.1:
four property classes run together failed two runs in three, with `ConcurrentModificationException`,
a `StoreRepository` NPE, `CannotFindArbitraryException` and `IllegalArgumentException: List length
= -1` — all spurious, none reproducible when the classes run alone. The same properties gathered
into one class passed five consecutive runs against 13-, 61- and 101-class slices. Lifting the
constraint needs a change in the fork: `TestEngine` would have to run property-bearing classes on
the serial (`D:`) lane, or `FirePhoque` would need a third lane for them.

**Reach for a property when the invariant is easier to state than the examples are to enumerate** —
a round trip (`render → parse → apply` reproduces the input), an idempotence (`f(f(x)) == f(x)`), a
bound that must hold for every input (no chunk exceeds the API's cap), or an order that must survive
a transformation. **Reach for an example test for everything else**: a specific edge case, an exact
error string, a regression a ticket named, or anything with fixtures. Properties and examples are
complements — `UnifiedPatchParserTest`, `TelegramOutboundPlannerTest` and `UtilsFilenamesTest` still
own the named cases and the error messages; the properties cover the arithmetic between them.

Two mechanics the fork forces:

- **Pin `tries` and write the wall-time budget next to the annotation.** The suite's critical path
  is what everyone pays; an unpinned property silently grows it. The current set costs ~290 ms.
- **Put the generated inputs in the assertion's message supplier.** jqwik publishes its sample
  report through JUnit Platform reporting entries, and the fork's `TestEngine.Listener` does not
  implement `reportingEntryPublished` — it records `Throwable.getMessage()` and nothing else. A
  message supplier is therefore the only route a shrunk counterexample has into `test-result`. It
  works well: a deliberately falsified `n < 500` reported `falsified at n=500`, the exact boundary.

`play autotest` writes jqwik's failure database to `.jqwik-database` at the repo root on every run;
it is gitignored.

### Frontend (Nuxt 4)
```bash
cd frontend
pnpm install              # Install dependencies
pnpm dev                  # Dev server on :3000
pnpm build                # Production build
pnpm generate             # nuxt generate — static output
pnpm preview              # Preview production build

pnpm lint                 # ESLint (logic + Vue + TS + a11y + stylistic)
pnpm format               # eslint --fix — stylistic auto-fix across the tree
pnpm stylelint            # Stylelint on .css + <style> blocks
pnpm stylelint:fix        # stylelint --fix
pnpm typecheck            # vue-tsc --noEmit via nuxi typecheck
pnpm audit                # pnpm audit --prod --audit-level=moderate
pnpm test                 # Vitest (unit)
pnpm test:watch           # Vitest in watch mode
pnpm test:e2e             # Playwright e2e — needs a running server; prefer ./jclaw.sh e2e
pnpm test:e2e:ui          # Playwright UI mode
pnpm test:e2e:headed      # Playwright headed, one worker, PWSLOWMO=500
```

### Evals
```bash
./jclaw.sh evals                                       # validate the eval dataset
./jclaw.sh evals --responses run.json --out rep.json   # score a recorded agent run
./jclaw.sh evals --capture run.json --agent __evaltest__ --suite <id>   # drive a live agent
```

Agent-behaviour datasets live in `evals/suites/` (`<id>.json`);
`evals/README.md` is the format contract. Validating and scoring are offline —
no backend, no model call, no DB — and `play autotest` validates the dataset via
`EvalSuiteConformanceTest`, so a malformed suite fails the build.

Suites are measuring sticks, so edit them in place and let git hold the history:
every run records a content fingerprint (`tool-selection@6e7927aeefe4`), and
comparing two runs whose fingerprints differ says so before printing anything
else. The fingerprint covers case inputs and checks, not `rubric`/`description`,
so clarifying prose never invalidates a baseline. JCLAW-883 replaced the older
`<id>.v<N>.json` convention, which nothing enforced — an in-place edit under an
unchanged version was undetectable.

`--capture` is the exception: it drives real agent turns, so it needs the
backend running and it spends model calls. It POSTs to `/api/evals/capture`
behind the same loopback + `X-Loadtest-Auth` gate as the loadtest endpoints, and
`--agent` is required rather than defaulted. Capture turns leave no
conversation, no history and no memories behind, and are never recorded into the
Chat Performance histograms — see `evals/README.md` for why each of those holds.

What capture does **not** isolate is tool side effects: tools execute for real,
so a suite case that provokes a `task_manager` call creates an actual scheduled
task. Run sweeps against `__evaltest__` — the eval sibling of `__loadtest__`,
auto-provisioned on first capture, for which **every tool is opt-in**. Each suite
declares its own `requiredTools` and capture grants exactly those before the
sweep, revoking the rest, so a run is reproducible from the repo rather than from
whatever was last clicked in the agent editor. Only `__evaltest__` is calibrated
this way — an agent you configured yourself is never rewritten. Deleting the eval
agent cleans up everything a sweep created.

Related, and not eval-specific: per-agent tool config used to be schema-only —
it hid a tool from the model but did not stop it running, so a model that guessed
a real tool name bypassed the operator's configuration. JCLAW-883 added the
execution guard in `ToolRegistry.execute`/`executeRich` for native tools; MCP
tools already gated through `AgentSkillAllowedTool`.

### Diagnostics
```bash
./jclaw.sh diagnostics                        # compile errors, as JSON on stdout
./jclaw.sh diagnostics --tests                # also run play autotest (~7 min)
./jclaw.sh diagnostics --tests --out d.json   # write the document to a file
```

The same facts `./jclaw.sh test` prints for a human, as one JSON array for an agent
repair loop: an array of `{kind, file, line, message, fix?}` records, where `kind` is
`compile` (a javac error), `test` (a failure in a `test-result/TEST-*.xml` report) or
`arch` (one violated site from an ArchUnit rule). `bin/README.md` is the schema
contract; `bin/diagnostics.mjs` is the implementation and `node --test
bin/diagnostics.test.mjs` its parser tests.

It parses, it does not add builds. It runs `./gradlew compileTestJava` — with `--tests`,
`play autotest` as well — and reads javac's output plus the xunit reports already on
disk. Parsing the 514 reports costs milliseconds against a compile measured in seconds
and a suite measured in minutes.

Three contracts make the output safe to believe. A clean tree prints `[]`, never nothing:
empty output means the command did not run. Without `--tests` the reports in
`test-result/` are not read at all, because they belong to whenever the suite last ran —
and with `--tests` they are deleted before the suite runs, so a class deleted since the
last run cannot be read back as still failing. And exit 2 is reserved for the harness
failing rather than the build: a compile that broke without javac printing a diagnostic,
or a suite that failed with no report recording a failure, exits 2 with the underlying
output on stderr instead of a green-looking `[]`. Exit 0 is a clean tree, exit 1 is
diagnostics reported.

One asymmetry is deliberate: a tree that does not compile skips the suite and reports
only `compile` records. javac gates the test run, so the reports on disk would describe
the previous build. This also means a single run never mixes `compile` records with
`test`/`arch` ones — not a limitation of the parser, but of what a broken tree can
physically produce.

### E2E
```bash
./jclaw.sh e2e                                # Playwright suite against an already-running server
```

Separate from `./jclaw.sh test` by design: it needs a live server and `JCLAW_ADMIN_PASSWORD` from
`certs/.env`, and runs `pnpm test:e2e` against whichever mode is listening. Local UAT, not a gate.

### Running Both Together
Start the Play backend (`play run`) and the Nuxt frontend (`cd frontend && pnpm dev`) in separate terminals. The frontend proxies `/api/**` requests to `localhost:9000`.

## Git Hooks

Checked-in hooks live in `.githooks/`. They're wired up automatically by `./jclaw.sh setup` (see [First-time setup](#first-time-setup) above). The underlying command, for reference or manual use:

```bash
git config core.hooksPath .githooks
```

Three hooks. The first two run on the commit/push path, layered by speed:

- **`pre-commit`** — runs `lint-staged` on staged frontend files only (ESLint + Stylelint `--fix`). Target: < 5 s typical. Auto-fixes formatting and re-stages; blocks the commit if a non-fixable rule violates. Short-circuits instantly when no `frontend/**` file is staged, so backend-only commits pay zero cost. Requires `cd frontend && pnpm install` first; before that, the hook fails open with a note.
- **`pre-push`** — runs the full backend + frontend test suite. Caches per-HEAD so the two-remote deploy flow (origin + github) only pays the 5–8 min cost once.
- **`post-checkout`** — fires `./jclaw.sh init-worktree` when `git worktree add` (or a fresh clone) creates a working tree, seeding its `certs/.env` secret and a deterministic `PLAY_TEST_PORT` so parallel `play autotest` runs across worktrees don't collide. No-op on routine branch switches.

Bypass for a single commit / push (use sparingly):

```bash
git commit --no-verify -m "…"          # skip pre-commit
JCLAW_SKIP_TESTS=1 git push …          # skip pre-push tests
```

Disable per-worktree (overrides the checked-in hook for this clone only):

```bash
git config --unset core.hooksPath      # opts out entirely
# or, to keep pre-push but disable pre-commit:
echo '#!/bin/sh' > .git/hooks/pre-commit && chmod +x .git/hooks/pre-commit
git config core.hooksPath .git/hooks
```

## Commit and Push Workflow

The rule itself is in the block above (Policy): stop at the local commit, report the hash, never push — `/deploy` is the only push. The `pre-commit` hook runs at the commit; fix every issue it surfaces and re-commit until it succeeds, and if a rule is wrong, fix the rule rather than bypass it.

Why this matters: every push to `main` triggers the `pre-push` hook's full backend + frontend suite, occupies the two-remote deploy flow, and mutates shared state. Keeping push behind `/deploy` makes releases a deliberate act rather than a side-effect of finishing a task.

## Architecture

### Backend
- **Play 1.x** conventions: controllers are static methods in `app/controllers/`, models in `app/models/`, views (Groovy templates) in `app/views/`
- Routes defined in `conf/routes` — uses Play's `{controller}.{action}` catch-all pattern
- Configuration in `conf/application.conf` — supports environment prefixes (`%prod.`, `%test.`)
- Dependencies managed via `build.gradle.kts` using the `org.playframework.play1` plugin from the `/opt/play1` fork. `settings.gradle.kts` resolves that plugin from a flat `file:///opt/play1/framework/gradle-plugin-repo` Maven repo — **not** `includeBuild("/opt/play1")`, which builds the plugin from source and needs write access into the fork, so it fails on read-only installs. The plugin version is read from the installed fork's `framework/src/play/version`, and `build.gradle.kts` cross-checks that against `.play-version` (plus a pinned range), failing the build on drift. To bump the fork: edit `.play-version` **and** check out the matching version in `/opt/play1`.
  - Consequence: `/opt/play1` is a separate root build with its own Gradle wrapper, so builds there start their own daemon. `./gradlew --stop` in this repo does not stop it — run it from `/opt/play1` too.
- Tests in `test/` — JUnit 6 (Jupiter 6.1.3, bundled by the play1 fork in `framework/lib`), extending Play's `UnitTest` or `FunctionalTest`
- Test mode uses H2 in-memory database (`%test.db.url` in application.conf)

### Outbound HTTP — OkHttp 5
JClaw uses **OkHttp 5.x** (with `okhttp-sse` for streaming) as its single outbound HTTP stack. The JDK `java.net.http.HttpClient` is no longer used in `app/` (only mentioned in Javadoc comparisons). The migration was JCLAW-185 through JCLAW-188; the rationale stack:

- **No h2c upgrade on cleartext** — OkHttp doesn't send `Upgrade: h2c` on plain HTTP, so the LM Studio Express upgrade-event hang the JDK client had to dodge with a routing rule is structurally absent. One client config, no per-provider HTTP-version pin needed.
- **Virtual-thread-clean** — Okio 3 has no synchronized blocks in its read path (verified by zero `monitorenter` in disassembled `RealBufferedSource.class` plus zero `Thread is pinned` events under `-Djdk.tracePinnedThreads=full` during a 50-stream c=10 sweep). Pointing OkHttp's `Dispatcher` at `Executors.newVirtualThreadPerTaskExecutor()` gives blocking socket reads the same unmount-during-block behavior the JDK client gets via `AsynchronousSocketChannel`.
- **Ergonomic SSE** — `okhttp-sse`'s `EventSourceListener` model is more direct than parsing `data:` lines off a `BufferedReader` from the JDK client. Streaming chat ships through a single `OkHttpLlmHttpDriver.streamSse` call.
- **Performance parity** — cloud median 0.94× JDK avg across 7 c=10 runs (kimi-k2.5, qwen3.5, gemini-3-flash-preview); local with `OLLAMA_NUM_PARALLEL=8` at 0.85×. AC3 ±5% met at the median.

All HTTP-client provisioning lives in `app/utils/HttpFactories.java` — a single class that exposes named factory methods (`llmStreaming()`, `llmSingleShot()`, `general()`) so call sites declare *intent* rather than reach into named static fields. Internally it shares two connection pools (LLM/64-slot, general/32-slot) and two dispatchers (LLM uses a virtual-thread executor, general uses OkHttp's default cached pool — request volume on the non-LLM path doesn't justify VT scheduling). The Telegram SDK and `WebFetchTool`/`SsrfGuard` build their own clients with stack-specific tuning that doesn't fit any of the three `HttpFactories` tiers (the SDK has its own internal usage; `SsrfGuard` plugs in a per-request DNS allow-list against tool-fetch SSRF).

Because call sites reach the transport through those factory methods, it is substitutable in one place: `HttpFactories.runWith(client, body)` — and its value-returning twin `callWith` — binds a `ScopedValue` that all six accessors (the three tiers plus their SSRF-guarded variants) honour for the dynamic extent of `body`, so a test installs a canned-response OkHttp interceptor instead of standing up a mock server on a port. Nothing binds it in production, and a `ScopedValue` does not follow an unrelated thread, so one test class cannot leak a transport into another that play1 is running concurrently — but for that same reason the binding does not reach Play's own request threads, and a `FunctionalTest` driving a controller still needs a per-collaborator seam such as `WhatsAppCloudApiProbe.installForTest`. The seam also stops at the accessor: nine sites derive a tuned client from `general().newBuilder()`, most of them into a static field at class-init (the rendered and impersonated fetchers, the Telegram/Slack/WhatsApp file downloaders through `StagedDownload`, the Slack uploader, the image-sidecar progress client) or at construction (the sidecar clients through `SidecarHttpClient`), and a binding made later cannot reach a reference captured that early.

### Wall clock — AppClock

Every wall-clock read in `app/` goes through **`utils.AppClock`** (JCLAW-1150). `AppClock.now()`
returns the current `Instant`; `AppClock.clock()` hands back the `java.time.Clock` behind it for
call sites that need a zone or a `Clock`-taking API. Production never binds anything — the unbound
default is `Clock.systemUTC()`.

A test overrides it for the duration of a call with `AppClock.runWith(clock, body)` or
`AppClock.callWith(clock, body)`, which bind a `ScopedValue` (final in JDK 25, JEP 506). The binding
is deliberately **not** a static setter: the play1 fork runs test classes concurrently, so a
process-global flip would leak a frozen clock into whatever else happens to be running.

**Propagation boundary.** A `ScopedValue` binding is visible to the binding thread and is inherited
by `StructuredTaskScope` forks. It is *not* inherited by a thread from
`Executors.newVirtualThreadPerTaskExecutor()`, `CompletableFuture.supplyAsync`, a raw
`Thread.start()`, or db-scheduler's worker pool — those read the unbound default, the system clock.
Code under test that crosses one of those boundaries must read the clock before spawning or rebind
inside the task. `WallClockDisciplineTest.theBindingDoesNotCrossAVirtualThreadExecutor` pins that
behaviour so the boundary is a tested fact rather than a comment. The same boundary governs
`HttpFactories.runWith` above and any future `ScopedValue` seam.

**The gate.** `test/WallClockDisciplineTest` is an ArchUnit rule banning `Instant.now()`,
`LocalDate.now()`, `LocalDateTime.now()`, `OffsetDateTime.now()`, `ZonedDateTime.now()` and
`System.currentTimeMillis()` anywhere in `app/` outside `AppClock` itself, along with `LocalTime`,
`Year` and `YearMonth.now()`, `new Date()`, `Calendar.getInstance()` and the `Clock.system*`
factories. It matches accesses rather than calls, so a method reference such as `Instant::now`
fails it too. It is a `FreezingArchRule`, because the `System.currentTimeMillis()` sites are not
all wall-clock reads: 42 of them are interval baselines for a throttle, a rate-limit window or a
duration, where rewriting them would change throttle behaviour for no gain. Those 42 are recorded
in the committed `archunit_store/wall-clock-interval-baselines`, so the exception list is explicit
and shrinkable while a *new* read of either kind still fails the build. `System.nanoTime()` is not
matched at all — it has no relation to wall-clock time and is the correct primitive for the
interval measurement in `utils.LatencyStats`.

`conf/archunit.properties` pins both `freeze.store.default.allowStoreCreation=false` and
`freeze.store.default.allowStoreUpdate=false`, so a run can neither mint a fresh baseline nor
rewrite the committed one. That cuts both ways: a new read fails, and a listed read that migrates
to `AppClock` also fails (`Updating frozen violations is disabled`) until its line is deleted from
the store by hand. To regenerate deliberately, flip both lines for a single run and flip them
back — `playAutotest` does not forward `-D` to the Play JVM, so the properties file is the only
lever. The store key is the rule's description, so editing the rule's `because(...)` text fails
the next run loudly rather than orphaning the baseline silently.

**Writing a time-dependent test.** Bind a fixed clock instead of sleeping or asserting on a ±1s
window: `TaskSchedulingServiceTest` and `TaskSchedulingTest.cronEveryMinute` are the worked examples.

### Structured concurrency — `utils.TaskScope`

Fan-out with all-or-nothing semantics goes through `app/utils/TaskScope.java`: a
try-with-resources scope over a virtual-thread executor where the first task to fail cancels its
siblings, `join()` rethrows that failure as an `ExecutionException`, and `close()` cancels
whatever is still running so no forked task outlives the block. It is homogeneous — every task
in a scope returns the same type — which covers the fan-out shape the codebase actually has;
heterogeneous fan-out still uses plain futures. `EvalRunner.mapCasesBounded` is the reference
call site.

The JDK's own `java.util.concurrent.StructuredTaskScope` is deliberately **not** used. JCLAW-1155
spiked it and the answer was no, for a reason specific to this repo's two-compiler build:

- `StructuredTaskScope` is still a preview API in JDK 25 (JEP 505), so `javac` refuses it without
  `--enable-preview` and, once given the flag, marks the calling classfile preview (minor version
  65535) — a marking the JVM then enforces at load time.
- `playRun`, `playStart` and `playAutotest` all `dependsOn("compileJava")`, so every `app/` class
  passes through Gradle's `javac` before Play starts. Neither the play1 Gradle plugin nor
  `conf/application.conf` passes `--enable-preview` to the app JVM (`jvm.memory` is the only
  injection point, and the plugin adds just `--enable-native-access=ALL-UNNAMED`).
- The play1 fork's dev-mode compiler is ECJ, configured by `ApplicationCompiler`'s fixed settings
  map. That map has no preview option and no config knob — `java.source` only selects
  source/target/compliance. ECJ 3.46 refuses `--enable-preview` at source 25 outright
  ("Preview of features is supported only at the latest source level"), and compiles preview API
  use to an **unmarked** classfile on a warning.

So the same source yields differently-marked bytecode from the two compilers, and the runtime
guard that `--enable-preview` exists to provide is enforced on one path and silently absent on the
other. `utils.TaskScope` avoids the split entirely.

**Revisit when structured concurrency finalises — JDK 26 at the earliest.** At that point
`StructuredTaskScope` needs no flag from either compiler, and `TaskScope` should be deleted rather
than kept beside it.

### Telemetry — OpenTelemetry in-process

Traces and metrics leave the process over OTLP from `app/services/telemetry/` (JCLAW-34), and
nothing leaves until an operator turns it on. `OtelRuntime.init()` builds the SDK once, from
`jobs.TelemetryBootstrapJob` rather than a plugin start hook: it reads the Config DB, and a
`conf/play.plugins` entry is ECJ-compiled before the app, so the plugin may reach nothing in
`app/` beyond its own package (`TelemetryState` is the three volatile fields it reads). Only three
leaves ever change — `DelegatingSampler`, `DelegatingSpanExporter`, `DelegatingMetricExporter` —
and `applyConfig()` swaps them from `ConfigService.setWithSideEffects` on every `otel.*` write,
force-flushing the meter and tracer providers to the exporter being replaced first (bounded at
2 s so a config write cannot hang a request thread on a dead collector). Export off is a sampler
that refuses every span plus empty exporter leaves, so the disabled cost is one volatile read per
span start. The keys — `otel.enabled`, `otel.exporter.endpoint` (default
`http://localhost:4318`), `otel.exporter.protocol` (`http/protobuf` | `grpc`),
`otel.exporter.secretHeaders` (masked by the API), `otel.service.name`,
`otel.traces.sampler.ratio`, and `otel.metrics.interval.seconds`, the one read at init — are
documented in `conf/application.conf` and never seeded.

**Sources.** `OtelPlayPlugin` (`conf/play.plugins` slot 450) opens one SERVER span per action
invocation, bracketed by `beforeActionInvocation` / `onActionInvocationFinally` and named
`METHOD route`. `GenAiSpans` opens one CLIENT span per model call at the two `LlmProvider` chat
dispatch points and the embeddings one, following the GenAI semantic conventions — provider,
model, usage including cache reads, time to first chunk; prompt and completion text are never
recorded — plus the `gen_ai.client.*` histograms with semconv bucket advice. `LatencyTrace` owns
the `turn` span (marks become events, counters attributes, `bind()` makes it current);
`TurnMetrics` bridges `LatencyStats.record` into `jclaw.turn.segment.duration`;
`RuntimeTelemetry` registers `jvm.*`; `OtelRuntime.traced()` wraps the LLM driver's OkHttp
clients. JDBC rides `db.factory=services.telemetry.OtelHikariDataSourceFactory` (needs the
fork's PF-174 loader fix, 1.13.70): the pool stays a `HikariDataSource` drawing connections from
`TelemetryDataSource`, which switches between the raw datasource and a `JdbcTelemetry`-wrapped
one on each toggle with a soft-evict that holds one connection across the eviction — an H2
in-memory database is dropped with its last connection. Its static initializer sets
`otel.semconv-stability.opt-in=database` unless already set.

**Propagation boundary.** OTel `Context` is a `ThreadLocal`, with exactly the boundary the
`ScopedValue` paragraph under AppClock documents. `StreamingAgentRunner`'s `agent-stream` thread
and `TaskScope` wrap with `Context.current().wrap`, and `LlmProvider`'s `llm-stream` thread runs
under the call's context; a new thread hop needs the same.

**Agent mode.** With the OpenTelemetry Java agent attached (`javaagent.path` or
`JAVA_TOOL_OPTIONS`), `init()` adopts `GlobalOpenTelemetry`, the keys are read once at start,
the plugin renames the agent's server span from the route instead of opening its own,
`traced()` returns the raw client, and JDBC/JVM instrumentation stays off — the agent owns
those. Verified with agent 2.31.1 beside the framework's enhancer agent on Netty 4.2.

**Test seams.** `OtelRuntime.captureForTest(Runnable)` routes every span into an
`InMemorySpanExporter` with the sampler forced on; `captureMetricsForTest` does the same for
metrics; `agentAttachedForTest` flags agent mode without an agent. The runtime is
process-global, so a test using any of them holds `TelemetryTestSync`. The closeables above are
covered by `ResourceLeakGateConformanceTest`.

### Nullness — NullAway on the Gradle compile

`org.jspecify:jspecify` annotations (`@Nullable` / `@NonNull` / `@NullMarked`) are checked by
**NullAway**, running as an Error Prone plugin on the Gradle `compileJava` task. Inside the
annotated packages a type with no annotation means *not null*, and NullAway fails the build on
any dereference, return, assignment or argument that contradicts that.

**Where it runs.** On the Gradle `compileJava` task, which `.githooks/pre-push` invokes before
the test suite and which Sonar already depends on. The `play` CLI is a Gradle wrapper whose
`playRun` and `playAutotest` tasks depend on `compileJava`, so a violation fails `play run` and
`play autotest` as well — before Play's own compile starts. What the checker cannot see is that
second compile: Play 1.x recompiles `app/` with ECJ inside the fork for dev-mode reload and for
the test runner, and a javac plugin cannot load there. That split is structural, not a gap to
close — the Gradle compile is the single place a javac plugin can see this codebase, the same
layering Spotless uses.

**What is in scope.** The `NullAway:AnnotatedPackages` option in `build.gradle.kts` lists
`utils`, `llm`, `agents`, `tools`, `services`, `controllers`, `channels`, `jobs` and `slash` —
every package under `app/` except one. Each carries a `package-info.java` with `@NullMarked`, and
so must every subpackage, since the annotation does not inherit.

**`models` is the only exclusion, and it is permanent.** JPA populates entity fields reflectively
after construction, so every non-null column would report as uninitialised. Nothing else in
`app/` is exempt, which is what stops a null contract going unchecked simply because the caller
sat in an unannotated package.

Getting there took three stories. JCLAW-1149 covered the first five. JCLAW-1160 added
`controllers`, `jobs` and `slash`, because that gap was itself the defect: a parameter whose only
null-passing caller lived in one of them was declared non-null and nothing objected, so the
declaration was a lie no gate could catch. One of those, a null printer protocol reaching
`defaultPort()`, had been 500ing the settings page for two months. JCLAW-1161 finished the job
with `channels`.

**Measure a widening before attempting it, and raise javac's error cap first.** `-Xmaxerrs`
defaults to 100, so an unmodified `./gradlew compileJava` reports exactly 100 and stops — a count
that looks like a total and is not. The truncation is in compilation order, so whole packages can
read as clean: the first measurement for JCLAW-1160 showed `channels` contributing **zero** when
it in fact held **414** of the 581 violations across the four candidate packages. The tell was
that the count sat at exactly 100 across three fix-and-recompile rounds. Measure with an init
script instead:

```kotlin
allprojects { tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xmaxerrs", "5000")) } }
```
```bash
./gradlew compileJava --console=plain -I <path-to-that-script>
```

**What the `channels` widening taught (JCLAW-1161).** In the wire layer a null almost always
means "the provider omitted this field", so annotate for the **provider's documented message
shape**, not for what the current happy path produces. A Telegram `Message` carries no `text`
when it is a photo; a Slack event has no `thread_ts` outside a thread; a Meta Cloud API webhook
makes `messages[]`, `caption` and `context` all conditional. A false `@Nullable` costs one guard;
a false non-null costs a production NPE on the first unusual payload.

**To widen it.** Add the package name to the `AnnotatedPackages` option, add a
`package-info.java` carrying `@NullMarked` to that package *and to each of its subpackages*
(`@NullMarked` is per-package and does not inherit; `AnnotatedPackages` itself is prefix-matched,
so one name covers a whole tree), then run `./gradlew compileJava` and annotate what it reports.
`NullnessGateConformanceTest` fails if a package inside the scope has no `@NullMarked`
package-info, if the checker is downgraded below `ERROR`, or if `models` reappears in the list.

**Writing the annotations.** Two spellings catch people out, because `@Nullable` is a
`TYPE_USE` annotation. On a qualified nested type it goes on the simple name
(`SkillLoader.@Nullable FrontmatterSplit`, not `@Nullable SkillLoader.FrontmatterSplit`), and on
an array it goes after the element type (`float @Nullable [] vector` annotates the array;
`@Nullable float[]` annotates the elements and leaves the array non-null).

**Result carriers.** The commonest false positive is a record that means "either a value or an
error" — NullAway cannot see that a null `error()` implies a non-null payload. The convention
here is an accessor that asserts the invariant and names it in one line, rather than a
suppression: `FsPaths.TargetPath.resolvedTarget()`, `FsSupport.LoadedFile.resolvedContent()`,
`DeliverySpec.resolvedTool()`, `ScrapeObservation.resolvedError()` and a dozen siblings all
follow that shape. Prefer it — a `resolvedX()` throws where a suppression would return null.

**Suppressions.** `@SuppressWarnings("NullAway")` with a one-line reason, and rare — one exists
today (`SkillLoader.parseSkillFile`), recording an invariant the checker cannot follow: a path
whose parent every caller guarantees. Note that NullAway does not analyse a suppressed method's
body at all, so a nullness contract on one is unverified by construction.

### Resource leaks — MustBeClosed on the Gradle compile

Error Prone's `MustBeClosedChecker` rides the same Gradle compile as NullAway, at `ERROR`, and
additionally on `compileTestJava` — which the nullness block deliberately excludes. A
constructor or factory annotated `com.google.errorprone.annotations.@MustBeClosed` may only be
called from a try-with-resources resource variable, or returned from another `@MustBeClosed`
method. Anything else fails the build.

**What carries the annotation.** Every concrete `AutoCloseable` in `app/`: `McpClient`,
`McpStdioTransport`, `McpStreamableHttpTransport`, `DirectLuceneMessageSearchRepository`'s
`LeasedSearcher`, `VoiceVad`, `VoiceSession`, `TaskScope`, `LatencyTrace.bind`, the three
delegating telemetry leaves `DelegatingSampler`, `DelegatingSpanExporter` and
`DelegatingMetricExporter` (OTel's `Sampler` and both exporter interfaces extend `Closeable`),
and `GenAiSpans.Call.makeCurrent`, which hands out a `Scope`. The
`McpTransport` and `LatencyTrace.Binding` interfaces carry nothing — the annotation belongs on
the thing that hands out an instance, which is the implementations' constructors and `bind`
respectively. `ResourceLeakGateConformanceTest` walks the imported bytecode for every concrete
class assignable to `AutoCloseable` (so `implements McpTransport` counts) and fails if one appears
in `app/` with no `@MustBeClosed` outside a comment in its file, or if either compile task drops
the check below `ERROR`.

`HttpFactories` is deliberately untouched: its methods hand back the three shared
`OkHttpClient` singletons, not per-call resources. The resource on that path is the `Response`,
which belongs to whoever executes the call — `OkHttpLlmHttpDriver.send` already closes its own
in try-with-resources, and `streamSse` blocks to completion rather than returning a live stream
handle, so neither returns anything a caller could leak.

**The Lucene test lock.** `LuceneTestSync` guards a JVM-global index behind one
`ReentrantLock`; an acquire with no matching `release()` does not fail — it hangs every later
Lucene test on that lock, which surfaces as a suite-wide timeout with no pointer to the cause.
`LuceneTestSync.openLease()` / `closedLease()` return an `@MustBeClosed` `Lease`, so a window
that lives inside one method body is compiler-enforced. Windows that span JUnit lifecycle hooks
keep the older `openForTest()` / `release()` pair, because `@MustBeClosed` accepts only a
resource variable or a return and a `@BeforeEach`/`@AfterEach` pair is neither; those callers
are covered instead by a source scan in `ResourceLeakGateConformanceTest` that fails on an
acquire with no release in the same file. **Prefer the lease** for any new Lucene test whose
window fits in one method.

**Suppressions.** `@SuppressWarnings("MustBeClosed")` with a short reason, and rare — ten
sites today. `McpConnectionManager.doConnect` and `McpServerService.testConnection` build a
transport that a longer-lived owner closes; `VoiceController.initSession` hands its `VoiceVad`
to a `VoiceSession` that outlives the method, with a local `handedOff` flag closing it on every
path that never gets there and a repeated init closing the session it displaces. The five
telemetry sites are the `McpConnectionManager` shape again: the three delegating leaves
`OtelRuntime` holds in static fields are closed by its `shutdown()`, `OtelPlayPlugin`'s request
`Scope` by `onActionInvocationFinally` on the same thread, and the turn `Scope` inside
`LatencyTrace.bind` by the `Binding` it returns. A fourth shape
appears on the two `buildTransport` methods, which
are annotated `@MustBeClosed` *and* suppressed: the checker does not treat a `yield` from a
switch block arm as a return position (a plain `->` arm it does), so the suppression silences
the body while the contract still binds every caller.

**To widen it.** `services.scanners.ScannerHttpClient.send` returns a fresh `okhttp3.Response`
whose Javadoc already tells callers to close it in try-with-resources, and is the strongest
remaining candidate in `app/`. JCLAW-1156 scoped its clause to `HttpFactories` and the LLM
driver and left it alone; it is a `@FunctionalInterface` implemented by lambdas, so the widening
needs a check that Error Prone accepts `@MustBeClosed` on a method implemented that way.

### Capabilities

Java 25 cannot express a capability in a signature, and JEP 486 removed the
SecurityManager, so nothing confines one at runtime either — which class may spawn a
process or reach the database is invisible to both javac and the JVM. `ArchUnit`
stands in for that at test time: `test/CapabilityRulesTest.java` holds four
allowlists, one per authority the codebase actually exercises, and a class that picks
up an authority it was never granted fails `play autotest` with a `because` clause
naming the capability.

| Capability | Holders |
| --- | --- |
| Spawn an OS process | 17 files — the sidecar supervisors, media transcoders, harness runners and `tools.ShellExecTool`; enumerated in `archunit_store/shell-process-spawners` |
| Resolve a model-controlled path | `tools.FsPaths` → `utils.WorkspacePathGuard`; the sites under `tools..` predating that seam are listed in `archunit_store/filesystem-tool-paths` |
| Open an outbound connection | `utils.HttpFactories`, plus `utils.SsrfGuard` and `channels.TelegramBotApiHttpClients` for their own tuned clients; raw sockets only in `services.printing..` and `services.LocalSidecarDaemon` |
| Reach the database | Everything except the subsystems `jobs.ShutdownJob` stops — teardown that needs a connection has no useful recovery when it cannot get one (JCLAW-1143) |

Shell and filesystem carry pre-existing holders, so they run as `FreezingArchRule`s
and the checked-in store under `archunit_store/` *is* the allowlist: a listed site
passes and a new one fails. Both store switches are pinned off in
`conf/archunit.properties`, so a listed site that stops matching fails too, until its
line is deleted by hand — deleting an entry is how the list shrinks; editing a rule is
not. The predicates match accesses, so a method reference such as `ProcessBuilder::start`
or `Path::of` counts the same as a call. Each frozen rule is paired with a floor on its
live match count, which guards the deliberate regeneration run: with the switches on, a
predicate that had silently stopped matching would prune the store to empty and pass
forever.

The filesystem authority is deliberately the narrow one: it covers paths resolved from
tool arguments, since those are the only ones an operator does not control.
Application-internal file access — config, caches, sidecar working directories — is not
this capability and is not gated.

### Process sandboxing

The table above says which classes may start a process; this says what one can reach once
started. Every process JClaw spawns that can be steered by model output goes through
`app/tools/HarnessSandbox.java` — one class holding both platform profile builders, so the
coding-harness boundary and the native-tool boundary cannot drift apart:

- **macOS** — `sandbox-exec -p '<inline Seatbelt profile>'`: allow-default, deny all writes,
  then grant back the one write root plus `/private/tmp`, `/private/var/folders` and `/dev`;
  deny reads of `~/.ssh`, `~/.aws`, `~/.gnupg`, `~/.config/gcloud`, `~/.kube`, `~/.netrc`.
- **Linux** — `bwrap --ro-bind / / --tmpfs $HOME --bind <writeRoot> <writeRoot>`: the visible
  filesystem is built from nothing, so secrets are *absent* rather than merely denied. Mount
  order is load-bearing: bwrap applies mounts in argument order and a tmpfs over an ancestor
  hides every earlier bind beneath it, so the `$HOME` tmpfs must precede the write-root bind
  (the workspace lives under `$HOME` on every non-container install).
  `HarnessSandboxTest.linuxBindsTheWriteRootAfterTheHomeTmpfs` pins that order on every host.

Two independent tri-state keys drive it, both `false` by default, both accepting
`true` (confine every run) or `untrusted` (confine only runs whose origin channel is not the
operator's own web chat):

| Key | Covers | Write root |
| --- | --- | --- |
| `subagent.acp.sandbox` | ACP coding-harness processes | the run's session directory |
| `shell.sandbox` | `exec` (`/bin/sh -c`), `diarize_audio`'s ffmpeg extraction, and `LlmAudio`'s ffmpeg transcode of an audio attachment | the agent's resolved workspace for `exec`; the temp directory for the two ffmpeg runs |

They are separate on purpose: confining a coding harness is not the same operator decision as
confining every shell command. Neither key is seeded into the Config DB — an absent key already
means "off", so both are documented in `conf/application.conf` and left unset.

**Both fail closed.** With a key on and no mechanism (native Windows; a host missing
`sandbox-exec`/`bwrap`), `HarnessSandbox.wrap` throws `SandboxUnavailableException` and the caller
aborts the run rather than launching unconfined. A `bwrap` that is present but cannot create its
namespaces (a WSL2 kernel with unprivileged user namespaces disabled) fails inside `bwrap`
instead, which surfaces as the command's non-zero exit — still no unconfined launch. Never add
a fallback that launches anyway.

**What the shell sandbox does and does not change.** It bounds *reach*, not grammar. `exec`'s
first-token allowlist is still a UX guardrail rather than a metacharacter defence — `echo hi;
rm -rf ~/Documents` still passes it and still runs both statements — but with `shell.sandbox`
on, the `rm` fails on every path outside the workspace. Do not "harden" the allowlist into
per-token gating; `ShellExecToolTest.commandCompositionRunsBothCommands` pins that posture
deliberately.

**The `browser` tool sits outside the boundary**, and this is a measured verdict rather than an
omission. Two independent reasons, either sufficient:

1. Playwright's Java client spawns its own driver, which spawns Chromium — JClaw never builds a
   Chromium argv, so there is nothing for `wrap` to prefix.
2. Under a macOS Seatbelt profile, Chromium's own child-process sandbox cannot initialize
   (`sandbox initialization failed: Operation not permitted`) and the browser aborts with
   `GPU process isn't usable. Goodbye.` It launches only with `--no-sandbox`, which trades the
   per-renderer confinement that actually defends against a hostile page for a coarse
   filesystem jail. (Under Linux `bwrap` it does launch — the block is macOS-specific, but
   reason 1 is not.)

Confining the browser means confining the whole JVM (a container, firejail), which is the
operator-side mitigation `ShellExecTool`'s security-posture Javadoc already names.

**Testing note.** `shell.sandbox` is process-global Config-DB state read on every
`ShellExecTool.execute`, and play1 runs test classes concurrently. Any test that flips it must
take `ShellSandboxSync.acquire()` / `release()`, the sibling of `LuceneTestSync` and
`LoadTestHarnessSync`. A real confined run needs `@EnabledOnOs(OS.MAC)` and must target a
genuinely-denied path — **not** the temp tree, which the profile grants for `TMPDIR`.

### Sidecars — local Python daemons

Seven Python services live under `sidecar/<name>/` — `asr`, `diarize`, `fetch`, `image`,
`stealth`, `tts`, `video` — each a `serve.py` plus `pyproject.toml`, run through `uv`
(`uv.lock` is per machine and gitignored). The JVM owns their lifecycle: each has a manager
under `app/services` holding one static `LocalSidecarDaemon`, built from a `Config` naming the
directory, the cache under `data/`, the Config-DB prefix, the default port and the startup
budget. The daemon spawns `uv run serve.py --port … --idle-timeout-min …` with a per-process
`SIDECAR_TOKEN` that every request echoes as `X-Sidecar-Token`, drains stdout and stderr on
virtual threads, polls `/health` until the startup budget runs out, and stops with `destroy()`
then `destroyForcibly()`. A spawn is single-flight under one lock (JCLAW-830): a second starter
waits, re-checks health and short-circuits, because a double spawn on a fixed port poisons the
spawn-failure cooldown of the healthy process. An idle sidecar exits on its own timer and is
respawned by the next call.

Keys are read live under each manager's prefix — `<prefix>.port`, `.timeoutSeconds`,
`.idleTimeoutMinutes` (default 15), `.startupTimeoutSeconds`, and `.hfToken` where a gated
model needs one — and each sidecar's README documents its own. Default ports: image 9527,
video 9528, asr 9529, diarize 9530, tts 9531, stealth 9532, fetch 9533.
`SidecarDefaultPortsConformanceTest` fails on any two managers sharing one: tts and fetch both
compiled in 9531 until JCLAW-1172, and whichever spawned second died on the bind.

Two supporting pieces. `services.sidecar.SidecarHttpClient` is the shared skeleton for the
inference clients (ASR, diarization, TTS): each of those sidecars handles one inference at a
time and answers 409 when busy, so calls are serialized JVM-wide through a fair lock owned by
each subclass (JCLAW-828) and concurrent conversations queue instead of surfacing the busy
condition. `services.SidecarCapabilityProbe` runs the image and video sidecars' one-shot
`serve.py --probe` on a background thread to detect the GPU and free VRAM for the Settings
"can this machine run it?" gate. The daemon and the probe are the sidecar entries in
`archunit_store/shell-process-spawners` (see Capabilities above); a new sidecar goes through
`LocalSidecarDaemon` rather than adding a spawner of its own.

### Frontend
- Nuxt 4 SPA in `frontend/` with Tailwind CSS v4
- API proxy: dev requests to `/api/*` are forwarded to the Play backend via Nitro devProxy (see `frontend/nuxt.config.ts`)
- Backend calls use Nuxt's auto-imported `useFetch` / `$fetch` directly; `frontend/composables/` adds `useApiParsed` (schema-validated reads, JCLAW-287) and `useApiMutation` (POST/PUT/DELETE) as consistent wrappers
- Package manager: **pnpm 12+**, version pinned in `frontend/package.json`'s `packageManager` field. pnpm installs standalone (`curl -fsSL https://get.pnpm.io/install.sh | sh -`) and switches itself to the pinned version on first use — **not** through corepack, which cannot launch pnpm 12 (per-platform native binary, no `bin/pnpm.cjs`) and which Node 25+ no longer ships.

  **Where the integrity check lives.** The pin is a bare version; a `+sha512-...` suffix, when one is present, is what corepack verified against, and pnpm ignores it — measured, not assumed. pnpm records its own per-platform releases in `frontend/pnpm-lock.yaml` under `packageManagerDependencies` and refuses to run one whose bytes do not match a published, signed npm release (`ERR_PNPM_PNPM_ENGINE_IDENTITY_MISMATCH`, reproduced by tampering with one integrity line). That is a stronger guarantee than the old one — provenance rather than agreement with a locally-edited string — and it is committed, reviewable in a diff, and covers every platform rather than whichever one last ran `corepack use`.

  Two layers keep it honest:
  1. The `.githooks/pre-commit` guard refuses to commit a `frontend/package.json` that pins pnpm while `frontend/pnpm-lock.yaml` carries no `packageManagerDependencies` block — the block's absence is what would silently disable the gate.
  2. `./jclaw.sh start` (dev and prod) resolves the pin before any pnpm step and hard-fails when it is missing.

  To bump the pin: `cd frontend && pnpm self-update <version>`, then commit `frontend/package.json` **and** `frontend/pnpm-lock.yaml` — the lockfile carries the new binaries' integrity.

  **Node 25+ and the test suite.** Node 25 enabled its own Web Storage API by default, and that partial implementation shadows jsdom's: `localStorage` lands undefined and 93 specs fail on what looks like a jsdom bug. `frontend/vitest.config.ts` sets `--no-webstorage` via `NODE_OPTIONS`, gated on the running Node major because Node 24 rejects the flag outright. Keep the gate while anything still builds on Node 24 — CI is on `node-26` now, but local clones and older checkouts are not, and an ungated flag is a hard `node: bad option` failure there. Tracking: vitest-dev/vitest#8757.

### API Contract
Backend exposes JSON endpoints under `/api/` (e.g., `ApiController.status` at `GET /api/status`). The frontend consumes these through the proxy — no CORS configuration needed.

## Prerequisites

- JDK 25+ (Zulu recommended)
- `play` command in PATH (custom fork: github.com/tsukhani/play1)
- Node.js 24.11+ or 26+ — the range Nuxt 4.5's `engines` allows, intersected with this project's 24 floor, enforced by `./jclaw.sh`. Node 25 is excluded because Nuxt excludes it; the dev container and CI both ship 26. `frontend/vitest.config.ts` handles the 25+ Web Storage change itself.
- pnpm 12+, installed standalone: `curl -fsSL https://get.pnpm.io/install.sh | sh -`. Not via corepack — it cannot launch pnpm 12, and Node 25+ does not ship it.

## graphify

Optional codebase knowledge graph, installed per-machine with `uv tool install graphifyy` (the CLI is `graphify`). Build it with `/graphify .`; `graphify-out/` is gitignored, so the graph is local to each clone and never committed.

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

When the user types `/graphify`, use the installed graphify skill or instructions before doing anything else.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- Dirty graphify-out/ files are expected after hooks or incremental updates; dirty graph files are not a reason to skip graphify. Only skip graphify if the task is about stale or incorrect graph output, or the user explicitly says not to use it.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).

## Behavioral Guidelines

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

### 1. Communicate Concisely

**Direct answers. Digestible nuggets. Progressive exchange.**

The user is tracking the work as it happens, not reading a report at the end. Make it easy to follow.

- Lead with the answer or the action. Skip the preamble. The user can ask for more if they want it.
- Break multi-step work into small confirmable units rather than dumping a full multi-stage plan up front. Each step's outcome may change the next; don't pre-commit to plans the user hasn't seen the inputs for.
- After each meaningful step, state in one or two sentences: what just happened, what's next, what (if anything) is blocked. So the user can track progress without re-reading prior messages.
- Match response length to the size of the question. A one-line ask gets a one-line answer. A multi-step refactor gets the diff summary plus a "next step" hint, not a five-paragraph essay.
- Keep insights and tangents brief. If a longer explanation might help, offer it ("want me to expand?") rather than emitting it by default.

The signal you're getting this right: the user can scan your message in seconds and know exactly what state things are in.

### 2. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

### 3. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.
- If you need a paragraph-long comment to justify why a workaround is OK, the code is wrong — fix the code.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

### 4. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

### 5. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

### 6. Know What Is Verified

**Separate what you ran from what you inferred. Say which is which.**

- **Claims about external systems need the external system.** How Slack renders an audio attachment, what a provider's API accepts, whether a client plays a codec — these are facts about someone else's software, and reading docs or reasoning from adjacent behaviour is inference, not verification. Infer freely; just label it, and don't let an inference reach a commit message or a ticket as fact.
- **A green result you did not scope is not evidence.** A quality gate can pass because nothing was measured; a test can pass because its fixture avoids the interesting case. Before reporting a pass, check that the thing you care about was actually exercised.
- **Validate a measurement harness against a known-zero case** before trusting a number it produces. Time a no-op; assert on an empty input. A harness that reports plausible nonsense is worse than no measurement, because it gets believed.
- **A flake is a defect until proven environmental — and "environmental" means naming the mechanism.** "Re-ran and it passed" is not a diagnosis. There are known interference cases here (a live app on the test port, parallel worktrees); those are identifiable by name, and anything else is a real failure.

### 7. Reach For What Exists

**Instrumentation, helpers, and prior verdicts already in the repo beat improvising.**

- Latency questions have `LatencyStats` / `LatencyTrace`, `/api/metrics`, the `./jclaw.sh loadtest` harness, and JProfiler. Prefer them over a hand-rolled stopwatch.
- Before deciding a static-analysis finding, check how the same rule was already resolved elsewhere in this project. A verdict that contradicts an established one either needs to change both places or is wrong.
- Match the existing construction for test fixtures and helpers (`AgentService.create`, `commitInFreshTx`, the probe `setForTest` seams) rather than building a parallel one.

### 8. Comments Carry Reasoning, Not Narration — Tersely

**Never write a comment that restates the code. Default to no comment; when one is warranted, default to one line.**

Redundant commenting is the most common defect in agent-written code, and the pull toward it is strong enough to survive a general instruction to stop. So this section is checks and examples rather than adjectives — apply it mechanically, not by feel.

**The delete test — run it on every comment you write.** Delete the comment and re-read the code. If the code still conveys everything the comment did, it stays deleted. A comment survives only by carrying what the code cannot: a reason, a constraint, a measurement, or a bug.

**Never write these:**

```java
// Loop through the discovered printers        ← restates the next line
for (var p : printers) { ... }

// Added image types (JCLAW-911)               ← changelog; belongs in the commit message
// Now also deletes the transcode cache        ← narrates your change, not the code
// This is important because...                ← if it needs a paragraph, fix the code (§3)
// TODO: could be faster                       ← aspirational; open a ticket or delete it
```

**Write these:**

```java
// Canon rejects text/plain outright — negotiate the format before sending.
// A4 job against a Legal tray reports as spool-area-full, not as a size error.
// JIPP is Kotlin: getStatus() is @NotNull, so a null guard here is dead code.
```

**A comment earns its place only by recording one of:**

- why the code is this way and not the obvious way — name the ticket, benchmark, or bug that decided it
- a non-obvious invariant or precondition a caller has to hold
- the specific failure a guard prevents
- an external contract the code cannot state itself: wire format, provider quirk, library nullability

**Length is one line.** A second line requires a reader who would otherwise get it wrong. A fifth means the code is unclear — fix the code (§3), don't explain it. Match the density around you: a new entry in a list of one-line comments gets one line. In `SettingsUnmanagedBanner.vue`'s prefix list, `playwright.` and `jtokkit.` run several lines because one is a deliberately-retained retired namespace and the other is written autonomously by a job — both genuinely surprising. Every ordinary entry beside them is a single line, and a new one that isn't will be sent back.

**Javadoc is held to the same bar.** Stating a public contract — parameters, return, thrown conditions, threading — earns its length. Prose rationale *inside* a Javadoc block does not: it faces the same delete test as any other comment, and a private helper usually needs no Javadoc at all. A ten-line block explaining why a two-line method exists is the same defect as a ten-line inline comment.

**Provenance for a *change* belongs in the commit message, not the code.** "Why this must stay" is a comment; "why I added this" is history. Explaining the bug you just fixed, in the code that fixes it, duplicates the commit body and ages into noise within one refactor.

**During refactors, comments move with the logic they explain.** A comment recording *why* a check exists is frequently the only surviving record of a bug that was actually hit, and deleting it as clutter is how the bug comes back.

### 9. Commit Messages Are Public

`/deploy` pushes to **both** remotes, and the GitHub mirror (`github.com/tsukhani/jclaw`) is **public**. Commit bodies, tags and release notes are published artifacts, not internal notes.

Write them factually: what changed, why, what was verified. That is also what makes them useful internally — the constraint costs nothing. Keep genuinely internal material (credentials, customer specifics, unreleased commercial plans) out of them entirely.

---

**These guidelines are working if:** fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, comments that stay one line, and clarifying questions come before implementation rather than after mistakes.
