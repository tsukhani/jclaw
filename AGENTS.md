# AGENTS.md

This file is the canonical, harness-agnostic guide for AI coding agents working in this repository — read by Claude Code, Codex, Cursor, and other coding harnesses. (`CLAUDE.md` points here.)

<!-- bmad:context -->
<!-- Verified 2026-10-31 against 1dd0a42e. Managed by bmad-project-context; edits inside this block are replaced on refresh. Keep anything you want preserved outside the markers. -->

## jclaw

AI agent platform on a Play 1.x fork (Java 25, virtual threads) with a Nuxt 4 SPA and seven Python sidecars the JVM spawns. Single-operator by design: one admin, no user entity. The product is JClaw: `ApiController.status` reports the build and the name, the sidebar brand and the Upgrade panel render that name, and neither holds one of its own. Those two values aside, Java, comments and this file say single-operator, never an edition name, and no build of this repo is JClaw Pro. This repo's tickets are Jira JF, and JCLAW holds JClaw Pro's backlog and this repo's older tickets; planning lives in `_bmad-output/`; every rule below carries its reason, or its topic guide does. Terms: a *turn* is one agent invocation, many messages and tool rounds; a *channel* is the origin (web, slack, telegram, whatsapp, voice, app), not a component; a *binding* attaches a channel to an agent; a *harness* is an external ACP coding agent, not this CLI; *Standing Orders* are the workspace files `SystemPromptAssembler` injects.

## Policy

- Never `git push` a commit: `/deploy` is the only push, run from the main checkout on `main`, never a worktree; `/renovate` deleting its merged `renovate/*` branches is the one other sanctioned push. Never `--no-verify`, never force. Stop at the local commit and report the hash.
- Never run a `./jclaw.sh` subcommand that stops the instance (`restart`, `stop`, `restore`, `repair`, `compact`, `upgrade`, `uninstall`) or clears its admin password (`reset`, which also restarts a Docker container), or kill the server PID, without asking — the instance may be serving live work; a task that mentions a restart is not the approval.
- `bin/policy-guard.mjs` enforces the push, hook-skipping, `git stash -u` and instance-stopping rules of this block, as a PreToolUse hook and in `.githooks/pre-push`. Ask the operator before a refused stop or restart, and only on their yes in this conversation rerun it prefixed `JCLAW_OPERATOR_APPROVED=1`.
- Every skill under `skills/` is local and edited normally, those first imported from outside included (`claude-designer`, `powerpoint-pptx`, `humanizer`, `youtube-transcript`, `git`, `frontend-design`, `ffmpeg-video-processing`, `in-depth-research`, `research-paper-writer-pro`): nothing re-imports a skill once it is in, and skills are meant to be adapted to this install. `docs/architecture/` is a snapshot nothing regenerates: correct a wrong fact in place, never expand or restructure it.
- Commit messages and tags are public on the GitHub mirror: no credentials, customer specifics, or unreleased plans.
- Implement every Jira ticket in a worktree of its own. Sessions sharing this clone then never share an index, a working tree or a test port.
  - **Create** it with `git worktree add -b <KEY> .claude/worktrees/<KEY> HEAD`. Branch from local `HEAD`, never `origin/main`, which lacks the unpushed commits the work builds on.
  - **Setup:**
    - The `post-checkout` hook seeds the worktree's `certs/.env` with its own `PLAY_TEST_PORT`.
    - `cd frontend && CI=true pnpm install` gives it the `node_modules` the pre-commit hook needs and the `.nuxt` vitest needs.
    - `EnterWorktree` skips the hook and branches from `origin/main`: run `./jclaw.sh init-worktree` in it and check its base.
  - **What stays in the main checkout:**
    - A spec under `_bmad-output/` is gitignored, so it stays in the main checkout and is addressed by absolute path.
    - The live instance builds from the main checkout, so a live check waits for the merge.
  - **Land** it:
    1. Rebase the branch onto `main`. If `main` moved, rerun the tests the change touches.
    2. From the main checkout, run `git merge --ff-only <KEY>`. If another session's uncommitted edits block the fast-forward, stop and ask.
    3. Only when the merge succeeded and `main` equals the branch, run `git worktree remove` and `git branch -d`. Never `-D`: it deletes an unmerged branch when another session has moved `main`.
  - **Everything else:** work that has no ticket stays on `main`, and make no other branch unless asked.
- Other sessions share the main checkout: stage only your own hunks there, and never `git stash -u`. A test run on `main` that another session's work fails twice, for a cause you can name, reruns in a temporary detached worktree holding `HEAD` plus only your diff. `/deploy` stages everything (`git add -A`), so check `git status` for other sessions' work before running it.
- The `jclaw_api` token exists only in the running process and the database keeps only its hash, so a restart mints a new one and there is no plaintext to SELECT. Read-only SELECTs on the live H2 (empty credentials, `AUTO_SERVER`) are fine. Third-party credentials cannot be hashed, so they are stored AES-GCM-encrypted (`enc:v1:`) under a key HKDF derives from `PLAY_SECRET` in `certs/.env` (`utils.CredentialCipher`, JCLAW-1455): the Config keys `ConfigService.encryptsAtRest` flags (outside `auth.`, a last word ending `key`, `keys` or `token`, or any word containing `secret` or `password`), the Slack, Telegram and WhatsApp binding token and secret columns, and the secret values in `McpServer` and `ChannelConfig` JSON. A SELECT returns ciphertext, but `certs/.env` opens it, and a data file not yet compacted (`jclaw.sh compact`) or a backup Settings marks Unencrypted still holds plaintext: never copy a value read from any of them into output, commits or tickets.

## Where things are

- Rules with their reasons: the sections below, and the topic guides in `docs/agents/` they link. Settled verdicts before re-proposing work: `docs/spikes/` — gitignored, so it holds only what this clone wrote; earlier verdicts are in git history (`git log -- docs/spikes`; the last tracked set is `git show f93376c5^:docs/spikes/`).
- Every kind of documentation, comments and Javadoc included, is cataloged in `docs/README.md`: what each must agree with, how a docs sweep treats it, and the gates an edit inside a source file runs.
- Config validation lives in `ConfigService.setWithSideEffects` (a non-null return is the 403; `deleteWithSideEffects` refuses no delete); `POST /api/config` writes any non-reserved key, so "not in the UI" is no reason to skip it. Never seed a key another selection already implies.
- The fixed-name agents `main`, `__loadtest__`, `__loadtest_tools__` and `__evaltest__` are provisioned by code — never seed or delete them in tests.
- The AFK factory (`.sandcastle/README.md`) works JF stories in the active sprint labelled `afk`, or in an epic labelled `afk`, plus GitHub issues labelled `afk` when `~/.jclaw-factory/github.env` exists, unattended and returns each as a local `agent/<KEY>` branch for review; a story or epic labelled `afk-merge` it also merges, signed, into the checkout's `main` and moves to Done, never pushing. Everything else it writes, bar the gitignored `.sandcastle/node_modules`, lives in `~/.jclaw-factory`.
- One implementation each, so a fix lands once: `jobs.RetentionDays` (all four cleanup windows), `AttachmentService.toView`/`toViews` (attachment JSON on the SSE frame and the REST read alike), `channels.TelegramInboundTurn` (the webhook and the poller run one turn), `SubagentSpawnArgs.batchChild`, `LocalSidecarDaemon.singleFlight` (every sidecar manager goes through it), `SubagentRegistry.deleteRun` (the single and the bulk run delete), `SubagentRegistry.deleteRunsStartedBy` (in front of every bulk delete of `TaskRun`; `TaskRunDeletionConformanceTest` fails a site that skips it), and `ConfigService.isDispatcherCapKey` on both the set and the delete arm. Grepping `app/` for `Mirrors|Same as|kept in sync|must match` is a weak proxy for duplication: its hits are deliberate cross-references — per-channel wire shapes, validation strings, doc pointers — so read for the twin rather than grep for it.

## Running and verifying

- Backend tests: `play autotest`, never `play test` (it starts an interactive test server). One class, or several comma-separated in one start: `./gradlew playAutotest -Ptests=<Class>[,<Class>]`, about 30 s a start; the full suite takes about 9–10 min. `./jclaw.sh test` runs all five checks to the end and prints a pass/fail summary naming each failed check's `logs/test-*.log`.
- A Jira ticket runs the tests of its own work, never the full suite. That means:
  - the test classes it wrote or changed, and those covering the code it changed, in one `./gradlew playAutotest -Ptests=…` start;
  - the vitest files likewise, with `cd frontend && pnpm exec vitest run <file>…`;
  - `./jclaw.sh check`, which runs no test.

  The wide run is `/deploy`'s:
  - its pre-push hook runs every test class the whole push reaches (`bin/select-tests.mjs`), plus the frontend checks when a frontend input changed;
  - Jenkins runs the full suite after the push.

  So a failure outside the ticket's own classes surfaces at `/deploy`, and is fixed before the push.

  Before starting those tests, wait until neither wide run is in progress on this machine, that is until both checks print nothing:
  - a full suite, in any checkout: `jcmd -l | grep playAutotest | grep -v -e -Ptests=` prints one line for each, with the checkout in its wrapper path;
  - `/deploy`'s pre-push hook, whose run is a `-Ptests=` list unless the push reaches every class, so the first check skips it: `pgrep -fl '[.]githooks/pre-push'` prints it for as long as it runs, and the brackets keep the pattern from matching the shell that runs it.

  A wide run executes up to 16 classes at once, so a run started beside it starves: tests with a timeout fail in both runs, through no fault in either change, and a red in the hook blocks the push.
- `./jclaw.sh check` runs what fails without running a test (the wildcard-import grep, spotlessCheck, compileJava with NullAway, ESLint, Stylelint, the typecheck), 23 s on a warm tree; `--backend-only` leaves the frontend out. `./jclaw.sh check-arch` runs the 18 ArchUnit and conformance classes alone, about 2.5 min.
- Test sources are the default package: test seams must be `public`. A test class must extend `play.test.UnitTest` or `FunctionalTest`; `TestDiscoveryConformanceTest` fails the build on one that does neither, which the runner would never reach. A test reaches a package-private method by reflection (`SubagentRunEventsTest` into `SubagentRunStore`), so changing such a signature compiles and fails only when that class runs: `git grep -n '"<method>"' -- test` first.
- Never pipe the suite (`| tail` returns tail's exit code); redirect to a file and check `test-result/*.failed.html`. Never compile while a run is in flight; `jcmd -l | grep -E "playAutotest|FirePhoque"` first. A `compileJava` that printed `UP-TO-DATE` measured nothing: `--rerun-tasks` when the compile is the evidence.
- Unit test classes run concurrently in one JVM, pure ones up to 16 at once beside one DB-touching class at a time, and functional classes then run one at a time: never flip a process-global without its lock ([testing](docs/agents/testing.md) lists them); scope shared-table assertions to your own rows; read a logged event with `EventLogger.captureForTest`, or `captureMatchingForTest` when another thread logs it, not from `event_log`, where a concurrent class's `EventLogger.clear()` can drop it first; a "seeded row missing" red may be `Fixtures.deleteDatabase` racing an earlier test's in-flight writes, not a regression.
- In a FunctionalTest, seed what your own HTTP request reads with a `commitInFreshTx` helper — each class keeps a private copy (e.g. `ApiAttachmentsControllerTest`) — because the body is already in a transaction and `Tx.run` joins it. Real HTTP against the autotest server 401s `password_unset` until `AuthFixture.seedAdminPassword` runs; the loadtest harness adds a warmup turn.
- Frontend: `cd frontend && pnpm test` after edits; `pnpm typecheck` is the TS gate, the LSP's `.vue` import errors are false. After a dependency bump, `rm -rf .nuxt node_modules/.vite && pnpm exec nuxi prepare` before believing a green. In a fresh worktree run `pnpm install` first (its postinstall runs `nuxt prepare`); after a lone `rm -rf .nuxt`, run `pnpm exec nuxi prepare` or every vitest file fails at transform.
- The live-browser test classes run only under `JCLAW_PLAYWRIGHT_TEST`, which only `./jclaw.sh test` sets, so a targeted run comes back green having run none: [browser runtime](docs/agents/browser-runtime.md).
- Pre-push runs `./jclaw.sh check --backend-only` before the suite: `./gradlew spotlessApply` after any import-moving change. A pre-commit `.distignore` warning on a new `app/**.java` or test file is expected — dist ships precompiled — don't edit `.distignore`.
- `compileJava` and `compileTestJava` run javac's doclint reference check, so a Javadoc `{@link}`, `@see`, `@param` or `@throws` in `app/` or `test/` that does not resolve fails the build with `reference not found` (`@param name not found` for a `@param`): link a class the file does not import by its fully qualified name, and after moving a method, fix the links that named its old class.
- Jenkins runs Sonar and coverage but never lint, typecheck or e2e.
- Detect a running instance with `./jclaw.sh status` or `lsof -nP -iTCP:9000 -sTCP:LISTEN`, never `ps | grep` (the ~34 KB classpath in its argv matches almost any pattern) and never bare `lsof -ti :PORT` to kill (it matches the app's own client sockets).

## Writing tests

- Assert the outcome a caller or the operator sees, exactly: `assertEquals` on the returned value, the response body or the rendered text, not "contains", "not null" or "did not throw". `SecretRedactorTest` checked that a secret was gone and passed while the mask ate the rest of the line or stopped short and leaked the tail (JF-17).
- Cover the behaviour, not only the bug in hand: the success path, each failure path (refused, missing, malformed, the upstream down) and both sides of every limit (`n` and `n + 1`, empty and one). `use-bulk-select.test.ts` had three tests for JCLAW-1221 and none showing that cancelling the confirmation deletes nothing.
- A bug fix starts with a test that fails on the unfixed code; say in the commit body that you saw it fail.
- Choose the layer by what the behaviour crosses: pure logic, a `UnitTest`; a route, a controller or the database, a `FunctionalTest`; a composable or component, a vitest file under `frontend/test/` that drives it as a page does (mount, act, assert the request sent and the state shown); a journey across pages, one Playwright spec under `frontend/tests/`.
- Stub only what crosses a process or network boundary (`HttpFactories.runWith`, `registerEndpoint`, a `*ForTest` seam), never the code under test: a test that stubs what it checks passes whatever the code does.
- A path the live instance also uses resolves through a `%test` setting (`grapheval.root` puts it under `tmp/test-graph-eval`), never by moving the real file aside and back: the suite runs beside a live instance, and a write landing in the gap is overwritten by the restore.
- Name a test after the behaviour it pins (`cancellingTheConfirmationDeletesNothing`), so a failure says what broke.

## Conventions that differ from defaults

- Config keys live in the Config DB; document an unseeded key in `conf/application.conf`, and never seed a key whose absence already means "off".
- `git grep -P`, never `-E`, for `\b`, `\d`, `\s` — macOS `git grep -E` drops them silently and matches nothing.
- `jclaw.sh` targets macOS bash 3.2 under `set -euo pipefail`: brace an interpolation before a non-ASCII character, guard a command substitution that can fail (`$(cmd || true)`), use an `EXIT` trap rather than `ERR`.
- New tool names are `<singular_noun>_<verb>` (`conversation_send`, `subagent_spawn`); older names such as `generate_image`, `task_manager` and `exec` predate the rule and keep their names.

## Known pitfalls

- Never `Thread.interrupt()` or `Future.cancel(true)` a thread that touches H2/JPA: the JDK closes the FileChannel and the file DB dies; use a volatile flag. `db.url` opens the file through H2's `retry:` file system, which reopens a channel an interrupt closed (JF-15): that is the net under this rule, not permission to interrupt. Never park many virtual threads on timed waits (JDK-8373224): schedule on a small platform-thread executor and block on an untimed `get()` — `ChannelTest.noRetrySchedulerFutureIsAwaitedWithATimeout` fails a timed wait on a `RetryScheduler` future.
- A job Play schedules (`@Every` or `@On`) extends `jobs.PeriodicJob`, not `Job`: Play still drains HTTP and fires jobs after SIGTERM, when H2's own exit hook can still close the database (the fork closes it after `stop()` since PF-201, except in the cases the `db.url` note in `conf/application.conf` lists), and the base skips a tick from then on and reports one the shutdown cut short in one INFO line (JF-23); `PeriodicJobConformanceTest` fails a scheduled job that extends anything else.
- A new NOT NULL entity column needs `@ColumnDefault` — the ALTER fails on populated rows, and the test DB (`%test.jpa.ddl=create`) never sees it. Raw-connection DDL must `commit()` explicitly; Hikari runs `autoCommit=false`.
- `Result` is a `RuntimeException`: a render inside `try {…} catch (Exception)` is swallowed into a 500 — assign in the try and render after it, or rethrow with `catch (Result r) { throw r; }` ahead of the broad catch.
- `Model.find().fetch()` is untyped (`<T> List<T>`, `T` inferred): copy elementwise. Fix a lazy N+1 with `@BatchSize`, not a manual IN query. `FunctionalTest` has no PATCH helper.
- Bulk and cascade deletes skip `@PostRemove` and orphan Lucene docs — evict explicitly. A Lucene codec-boundary bump: wipe `data/jclaw-lucene/` while pre-v1 rather than add backward-codecs, and verify through the live search endpoint.
- "Zero call sites" is not dead code: a retention Javadoc, `@SuppressWarnings("unused")` or a reflection test marks a deliberate keep. Every `renameKeyIfPresent` call stays once applied: an install can upgrade straight from any earlier release, and a rename it skipped must still run.
- JCLAW acceptance criteria forward-reference helpers and tickets that were never built: verify before designing around them.
- Log anything that can carry a command, a URL or tool arguments through `EventLogger`, never `play.Logger`, which bypasses `SecretRedactor`. When reading `logs/application.log` or `event_log` rows written before the mask, never print an `exec:` or `args:` line.
- `memory.jpa.vector.queryPrefix` and `memory.recall.minCosine` move together. A CSP, if enabled, must allow `'unsafe-inline'` for `script-src`; Nuxt inlines `window.__NUXT__`.
- The installer paths (Playwright Chromium, `uv run`, the JRE download) were audited and accepted on 2026-08-28; do not re-flag them.

<!-- /bmad:context -->

## Setup, commands and hooks

- After every fresh clone, re-clones included, run `./jclaw.sh setup`: it wires `.githooks/`, installs the frontend dependencies the pre-commit hook's `lint-staged` needs, and checks that the `origin` (Bitbucket) and `github` remotes exist. No clone carries `core.hooksPath` (it lives in `.git/config`) or `frontend/node_modules/`, so without it the hooks silently don't fire and `/deploy` fails on the github push.
- `./jclaw.sh e2e` runs the Playwright suite against whichever mode is listening, with `JCLAW_ADMIN_PASSWORD` from `certs/.env`. It is local UAT, not a gate, and needs a live server, so `./jclaw.sh test` leaves it out.

## Topic guides

Each guide holds one subsystem's rules with their reasons, and only the agent working on that subsystem needs it. The line after each link says when to read it and the rule an agent most often gets wrong without it.

- [Trust](docs/agents/trust.md) — before touching `@AgentAccess`, `RequestPrincipal`, `DangerousActionGate`, inbound `app/channels/` handling, tool-approval grants, persona files, or a URL, host or path a model or page supplies. The agent principal and the channel sender are separate axes; an unbound `ownerInitiated` means guest.
- [Test isolation](docs/agents/testing.md) — before a test flips a process-global, or drives a provider, circuit breaker or inbound fixture. Mint your own provider or server name.
- [Subagents and coding runs](docs/agents/subagents.md) — before touching `SubagentRun`, `SubagentRegistry`, `subagent_spawn`, `AgentRunner.taskFireParent` or `AgentService.delete`. A run has one parent, and its child agent may be the operator's, so never delete one unchecked.
- [Context pipeline](docs/agents/context-pipeline.md) — before touching `CompressionPipeline`, `CompactionGate`, `ContextWindowManager`, `ContentTypeDetector` or a tool's `verbatimResult`. A media rewrite goes after compaction; a SKILL.md stays under 20,000 characters.
- [Logging and retention](docs/agents/logging.md) — before touching `EventLogger.record`, `SecretRedactor` or a cleanup window. A non-positive retention is `DISABLED`, never a window.
- [Browser runtime](docs/agents/browser-runtime.md) — before touching `services.browser` or bumping Playwright. `PlaywrightNode.VERSION` must equal driver-bundle's node.
- [Release signing](docs/agents/release-signing.md) — before touching `bin/sign-release.sh`, `utils.ReleaseSignature`, the installers or the Release stage. Keep `ReleaseSignature` on the JDK alone.
- [Database maintenance](docs/agents/database-maintenance.md) — before touching `H2Maintenance`, `CredentialCopies` or `H2TraceMask`. Keep them on the JDK, H2 and Gson, and never let them read the clock.
- [Architecture](docs/agents/architecture.md) — before changing the build files, `.play-version`, `.githooks/` or the JVM options.
- [Outbound HTTP](docs/agents/outbound-http.md) — before adding an outbound call or choosing a client. Clients come from `utils.HttpFactories`: a provider-style URL an operator or agent can change takes a `*Guarded()` tier, and a web URL the model browses stays on `SsrfGuard.SAFE_DNS`.
- [Wall clock](docs/agents/wall-clock.md) — before reading the time, freezing it in a test, or forking a thread that must carry a binding. Every wall-clock read goes through `utils.AppClock` (`WallClockDisciplineTest`). A `ScopedValue` binding does not cross an executor, while an `InheritableThreadLocal` does, so a fork for a different sender opts out.
- [Structured concurrency](docs/agents/structured-concurrency.md) — before fanning out work. Use `utils.TaskScope`, never the JDK's preview `StructuredTaskScope`: javac and ECJ compile it to differently-marked bytecode, and it interrupts its forks. `TaskScope` never interrupts, so its `close()` waits for every fork, and each wait inside one must end on its own.
- [Telemetry](docs/agents/telemetry.md) — before touching `services.telemetry`, a span or a metric, or adding a thread hop. OTel `Context` is a `ThreadLocal`, so a new hop wraps with `Context.current().wrap`; a test using the capture seams holds `TelemetryTestSync`.
- [Nullness](docs/agents/nullness.md) — when NullAway fails a compile, or a package joins its scope. `@NullMarked` does not inherit, so each subpackage carries its own `package-info.java`; javac stops at 100 errors, so raise `-Xmaxerrs` before measuring.
- [Resource leaks](docs/agents/resource-leaks.md) — before adding an `AutoCloseable` or a Lucene test. Its constructor or factory carries `@MustBeClosed`; a Lucene test whose window fits one method uses `LuceneTestSync.openLease()`.
- [Capabilities](docs/agents/capabilities.md) — before code spawns a process, resolves a model-supplied path, opens a connection, adds an `/api` route or adds a `*ForTest` seam. `CapabilityRulesTest` and the frozen stores in `archunit_store/` are the allowlists; delete a stale store line by hand.
- [Chrome bridge](docs/agents/chrome-bridge.md) — before touching `ChromeBridge*`, `ChromeControlTool`, `ChromeBridgePolicy` or `chrome-extension/`. The agent drives the operator's signed-in Chrome: policy is judged on the JClaw side, and loopback and LAN stay blocked even for the main agent.
- [Process sandboxing](docs/agents/process-sandboxing.md) — before spawning a process model output can steer, or touching `shell.sandbox` or `subagent.acp.sandbox`. Such a process goes through `tools.HarnessSandbox`, which fails closed: never add a fallback that launches unconfined. The `browser` tool stays outside it by a measured verdict.
- [Sidecars](docs/agents/sidecars.md) — before touching `sidecar/` or a sidecar manager. A new sidecar goes through `LocalSidecarDaemon`, takes an unused literal default port and joins `ShutdownJob`'s list; the spawner store and two conformance tests catch each omission.
- [Property-based tests](docs/agents/property-tests.md) — before writing a jqwik `@Property`. Every one lives in `test/PropertyBasedTest.java`, with `tries` pinned and the generated inputs in the assertion message.
- [Evals](docs/agents/evals.md) — before editing `evals/` or running a capture. Suites are edited in place; `--capture` executes tools for real, so run it only against `__evaltest__`.
- [Diagnostics](docs/agents/diagnostics.md) — before driving a repair loop from `./jclaw.sh diagnostics`. `[]` is a clean tree, and exit 2 means the harness failed, not the build.
- [Frontend](docs/agents/frontend-toolchain.md) — before editing `frontend/` or `docs/user-guide/`, or changing the pnpm pin. An API write goes through `useApiMutation().mutate` or `useSaveAttempt().attempt`, never a bare `$fetch`.

## Prerequisites

- JDK 25+ (Zulu recommended).
- `play` in PATH: the fork at github.com/tsukhani/play1.
- Node.js 24.11+ or 26+, enforced by `./jclaw.sh`: Nuxt 4.5's `engines` range above this project's 24 floor, which excludes 25. The dev container and CI ship 26.
- pnpm 12+, installed standalone, never via corepack: [frontend](docs/agents/frontend-toolchain.md).

## graphify

Optional codebase knowledge graph in `graphify-out/`, local to each clone and gitignored. When the user types `/graphify`, use the installed graphify skill or instructions before anything else. It answers structural questions about a symbol you can already name, so get the name with one grep first. After modifying code, run `GRAPHIFY_NO_BACKUP=1 graphify update .`. Which command to use, and its traps: [graphify](docs/agents/graphify.md).

## Behavioral Guidelines

### 1. Communicate Concisely

The user follows the work as it happens, so make each message scannable in seconds:

- Lead with the answer or the action; skip the preamble.
- Break multi-step work into small confirmable steps. Each outcome may change the next, so don't commit to a plan whose inputs the user hasn't seen.
- After each meaningful step, say in a sentence or two what happened, what's next and what is blocked.
- Match length to the question: a one-line ask gets a one-line answer, a refactor gets the diff summary and the next step.
- Offer a longer explanation or a tangent ("want me to expand?") rather than writing it by default.

### 2. Know What Is Verified

Separate what you ran from what you inferred, and say which is which.

- **Claims about external systems need the external system.** How Slack renders an attachment, what a provider's API accepts or whether a client plays a codec are facts about someone else's software; reading docs or reasoning from adjacent behavior is inference. Label it, and never let it reach a commit message or a ticket as fact.
- **A green result you did not scope is not evidence.** A gate can pass because nothing was measured, a test because its fixture avoids the interesting case. Before reporting a pass, check that the thing you care about was exercised.
- **Validate a measurement harness against a known-zero case** (time a no-op, assert on an empty input) before trusting its numbers: plausible nonsense gets believed.
- **A flake is a defect until proven environmental, and "environmental" means naming the mechanism.** "Re-ran and it passed" is not a diagnosis. The known interference cases (a live app on the test port, parallel worktrees) have names; anything else is a real failure.

### 3. Reach For What Exists

Instrumentation, helpers and prior verdicts already in the repo beat improvising:

- Latency: `LatencyStats` / `LatencyTrace`, `/api/metrics/latency`, the `./jclaw.sh loadtest` harness and JProfiler, not a hand-rolled stopwatch.
- A static-analysis finding: check how the same rule was resolved elsewhere in this project. A verdict that contradicts an established one either changes both places or is wrong.
- Test fixtures and helpers: match the existing construction (`AgentService.create`, `commitInFreshTx`, the probe `setForTest` seams) rather than building a parallel one.

### 4. Comments Carry Reasoning, Not Narration — Tersely

**Never write a comment that restates the code. Default to no comment; when one is warranted, default to one line.** Redundant commenting is the most common defect in agent-written code, and it survives a general instruction to stop, so apply these checks mechanically, not by feel.

**The delete test, on every comment you write:** delete it and re-read the code. If the code still conveys everything the comment did, it stays deleted. A comment survives only by carrying what the code cannot: a reason, a constraint, a measurement or a bug.

**Never write these:**

```java
// Loop through the discovered printers        ← restates the next line
for (var p : printers) { ... }

// Added image types (JCLAW-911)               ← changelog; belongs in the commit message
// Now also deletes the transcode cache        ← narrates your change, not the code
// This is important because...                ← if it needs a paragraph, fix the code
// TODO: could be faster                       ← aspirational; open a ticket or delete it
```

**Write these:**

```java
// Canon rejects text/plain outright — negotiate the format before sending.
// A4 job against a Legal tray reports as spool-area-full, not as a size error.
// JIPP is Kotlin: getStatus() is @NotNull, so a null guard here is dead code.
```

**A comment earns its place only by recording one of:** why the code is this way and not the obvious way (name the ticket, benchmark or bug that decided it); a non-obvious invariant or precondition a caller has to hold; the specific failure a guard prevents; or an external contract the code cannot state itself (wire format, provider quirk, library nullability). A longer comment or a Javadoc block has its own bar: read [comments](docs/agents/comments.md) before writing one.

**Provenance for a change belongs in the commit message, not the code.** "Why this must stay" is a comment; "why I added this" is history. Explaining a fix in the code that fixes it duplicates the commit body and ages into noise within one refactor.

**During refactors, comments move with the logic they explain.** A comment recording why a check exists is often the only surviving record of a bug that was hit, and deleting it as clutter is how the bug comes back.

### 5. Commit Messages Are Public

`/deploy` pushes to both remotes, and the GitHub mirror (`github.com/tsukhani/jclaw`) is public, so commit bodies, tags and release notes are published. Write them factually (what changed, why, what was verified), which is also what makes them useful internally, and keep credentials, customer specifics and unreleased commercial plans out entirely.
