# Architecture

Read this before changing `build.gradle.kts`, `settings.gradle.kts`, `.play-version`, `.githooks/`, `aot_prepare` in `jclaw.sh` or the JVM options, or bumping the play1 fork.

## Project overview

JClaw is an AI-powered automation platform built on **Play Framework 1.x** (Java) with a **Nuxt 4** (Vue 3 + TypeScript) SPA frontend. It combines OpenClaw agent orchestration and JavaClaw job scheduling into a single Java-first platform.

**Status**: released; 1.0.0 was the first stable version.

## Backend
- **Play 1.x** conventions: controllers are static methods in `app/controllers/`, models in `app/models/`, views (Groovy templates) in `app/views/`
- Routes defined in `conf/routes` — uses Play's `{controller}.{action}` catch-all pattern
- Configuration in `conf/application.conf` — supports environment prefixes (`%prod.`, `%test.`)
- Dependencies managed via `build.gradle.kts` using the `org.playframework.play1` plugin from the `/opt/play1` fork. `settings.gradle.kts` resolves that plugin from a flat `file:///opt/play1/framework/gradle-plugin-repo` Maven repo — **not** `includeBuild("/opt/play1")`, which builds the plugin from source and needs write access into the fork, so it fails on read-only installs. The plugin version is read from the installed fork's `framework/src/play/version`, and `build.gradle.kts` cross-checks that against `.play-version` (plus a pinned range), failing the build on drift. To bump the fork: edit `.play-version` **and** check out the matching version in `/opt/play1`.
  - Consequence: `/opt/play1` is a separate root build with its own Gradle wrapper, so builds there start their own daemon.
- Every resolvable configuration resolves from the committed `gradle.lockfile` in STRICT mode (JF-176), the playClasspath and playBundle classpaths included: a version above the lock, a module the lock lacks, or a lock entry nothing resolves fails the build. A dependency change rewrites the lock with `./gradlew dependencies --write-locks`, and the rewrite goes in the same commit. A declaration below the locked version resolves to the lock's version without a word, so a downgrade needs the rewrite too. sherpa-onnx's native-lib jar is left out of the lock, being the one module whose name depends on the build host.
- Tests in `test/` — JUnit 6 (Jupiter 6.1.3, bundled by the play1 fork in `framework/lib`), extending Play's `UnitTest` or `FunctionalTest`
- Test mode uses H2 in-memory database (`%test.db.url` in application.conf)

## Frontend
- Nuxt 4 SPA in `frontend/` with Tailwind CSS v4
- API proxy: dev requests to `/api/*` are forwarded to the Play backend via Nitro devProxy (see `frontend/nuxt.config.ts`)
- Reads use Nuxt's auto-imported `useFetch` / `$fetch`, with `fetchParsed` in `frontend/composables/useApiParsed.ts` for schema-validated reads (JCLAW-287); writes go through `useApiMutation` or `useSaveAttempt`, and the lint fails a bare write `$fetch`
- Package manager: **pnpm 12+**, pinned in `frontend/package.json`'s `packageManager` field. To bump the pin: `cd frontend && pnpm self-update <version>`, then commit `frontend/package.json` **and** `frontend/pnpm-lock.yaml` — the lockfile carries the new binaries' integrity. The reasons, and the Node 25+ test flag: [frontend-toolchain.md](frontend-toolchain.md).

## Fork and production start

- Bumping the fork is two-sided: `.play-version` and `/opt/play1` at the same tag, rebuilt (`framework/src/play/version` is a build artifact); a mismatch fails Gradle configure, which pre-push reports as "spotlessCheck failed without reporting format violations". Then rewrite `gradle.lockfile`: the plugin leaves every library framework/lib ships out of the graph, so a fork that starts or stops shipping one the graph reaches fails resolution against the old lock.
- A production start keeps the JDK's AOT cache current (`aot_prepare` in `jclaw.sh`; the Docker `CMD` calls it as `jclaw.sh aot-cache`): when the JDK, the JVM options or the jars changed, it first trains a cache with a full start and stop of the real instance on spare ports (`BACKEND_PORT` + 1000 up, so 9000 and 9443 stay dark), and it never uses one on a JDK without JDK-8377932's fix (25.0.4, 26.0.2, 27), where a stale cache serves old library classes. A changed application class is still loaded fresh, so a code-only restart keeps the cache. `JCLAW_AOT=off` starts without one.

## Serving and git hooks

- `play run` serves the backend on :9000; `cd frontend && pnpm dev` serves the SPA on :3000 and proxies `/api/**` to it. The frontend's scripts are in `frontend/package.json`.
- `pre-commit` runs `lint-staged` (ESLint and Stylelint `--fix`) over staged `frontend/**` files and re-stages what it fixed: fix what it cannot and commit again, and if a rule is wrong, fix the rule rather than bypass it. `pre-push` runs only the backend test classes the push reaches (`bin/select-tests.mjs`), and the frontend checks only when a frontend input changed, cached per HEAD across the two remotes; Jenkins runs the full suite on every push, so its failure is an integration failure to fix, not a flake to rerun. A push publishes to both — which is why only `/deploy` pushes, so a release is a deliberate act rather than the side effect of finishing a task. `post-checkout` seeds a new worktree's `certs/.env` and `PLAY_TEST_PORT`. The hook bypasses in [DEVELOPMENT.md](../../DEVELOPMENT.md#git-hooks) are for a human, never an agent.
- A broken `test/` class fails `./jclaw.sh dist|bundle`: precompile compiles tests without running them.
