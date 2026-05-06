# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## Project Overview

JClaw is an AI-powered automation platform built on **Play Framework 1.x** (Java) with a **Nuxt 3** (Vue 3 + TypeScript) SPA frontend. It combines OpenClaw agent orchestration and JavaClaw job scheduling into a single Java-first platform.

**Status**: v0.1.0-alpha, work in progress.

## Development Commands

### Backend (Play 1.x)
```bash
play run                  # Start dev server on :9000
play test                 # Run all tests (unit + functional)
play auto-test            # Run tests with auto-reload
play dist                 # Build production distribution
```

### Frontend (Nuxt 3)
```bash
cd frontend
pnpm install              # Install dependencies
pnpm dev                  # Dev server on :3000
pnpm build                # Production build
pnpm preview              # Preview production build
```

### Running Both Together
Start the Play backend (`play run`) and the Nuxt frontend (`cd frontend && pnpm dev`) in separate terminals. The frontend proxies `/api/**` requests to `localhost:9000`.

## Architecture

### Backend
- **Play 1.x** conventions: controllers are static methods in `app/controllers/`, models in `app/models/`, views (Groovy templates) in `app/views/`
- Routes defined in `conf/routes` — uses Play's `{controller}.{action}` catch-all pattern
- Configuration in `conf/application.conf` — supports environment prefixes (`%prod.`, `%test.`)
- Dependencies managed via `build.gradle.kts` using the `org.playframework.play1` plugin from the `/opt/play1` fork (composite build wired in `settings.gradle.kts`)
- Tests in `test/` — JUnit 5, extending Play's `UnitTest` or `FunctionalTest`
- Test mode uses H2 in-memory database (`%test.db.url` in application.conf)

### Frontend
- Nuxt 3 SPA in `frontend/` with Tailwind CSS
- API proxy: dev requests to `/api/*` are forwarded to the Play backend via Nitro devProxy (see `frontend/nuxt.config.ts`)
- `useApi<T>(path)` composable in `frontend/composables/useApi.ts` wraps `useFetch` for backend calls
- Package manager: **pnpm** (pinned at 10.8.0)

### API Contract
Backend exposes JSON endpoints under `/api/` (e.g., `ApiController.status` at `GET /api/status`). The frontend consumes these through the proxy — no CORS configuration needed.

## Prerequisites

- JDK 25+ (Zulu recommended)
- `play` command in PATH (custom fork: github.com/tsukhani/play1)
- Node.js 20+
- pnpm


<claude-mem-context>
# Memory Context

# [jclaw] recent context, 2026-04-23 1:54am GMT+8

Legend: 🎯session 🔴bugfix 🟣feature 🔄refactor ✅change 🔵discovery ⚖️decision
Format: ID TIME TYPE TITLE
Fetch details: get_observations([IDs]) | Search: mem-search skill

Stats: 50 obs (25,522t read) | 621,733t work | 96% savings

### Apr 20, 2026
S3384 JCLAW-26 Web Chat Conversation Reset Fix Deployed to Production (Apr 20 at 10:43 PM)
S3387 JClaw v0.9.48 Deployed to Bitbucket with Pre-Push Test Validation (Apr 20 at 10:55 PM)
### Apr 21, 2026
S3388 Telegram Channel Explicitly Passes Null LatencyTrace to AgentRunner (Apr 21 at 1:16 AM)
S3390 Confirming whether other pages need modifications to comply with the layout positioning fix (Apr 21 at 1:21 AM)
### Apr 22, 2026
S3391 Committed layout positioning fix to git repository (Apr 22 at 1:19 PM)
18064 1:20p ✅ Deployed layout fix to production serving directory
18065 " 🔵 SPA deployment requires nuxt generate, not nuxt build
18066 1:21p ✅ Successfully generated and deployed static SPA with layout fix
18067 " 🔴 Layout positioning fix verified working in production
18068 1:22p ✅ Committed layout positioning fix to git repository
S3392 Fix layout positioning issue by adding position:relative to main element and deploy to production (Apr 22 at 1:22 PM)
S3393 Agent workspace editor now tracks dirty state to control save button (Apr 22 at 1:22 PM)
18069 1:31p 🟣 Release v0.10.2 with agent workspace configuration
18070 1:44p 🔵 Workspace file save button lacks disabled state binding
18071 1:46p 🟣 Added dirty-tracking state for agent form and workspace files
18072 " 🟣 Implemented workspace file dirty-tracking with baseline synchronization
18073 1:47p 🟣 Wired dirty-tracking to save button disabled states in agents page UI
18074 2:01p 🔵 Workspace file narrative order and initialization strategy
18075 2:02p 🔵 Main agent workspace files tracked in version control
18076 2:11p 🔴 DefaultConfigJob no longer overwrites workspace files on restart
18077 " 🔵 Workspace initialization architecture uses overwrite flag pattern
18078 " 🟣 Regression tests added for workspace persistence across restarts
18079 2:12p 🔵 JCLAW-129 implementation validated by full test suite pass
18080 2:14p 🟣 Agent workspace editor now tracks dirty state to control save button
S3395 Port mismatch caused by settings.json override not applied to health checks (Apr 22 at 2:14 PM)
18081 2:28p 🔵 Stale claude-mem worker-service daemon cleaned up and upgraded
18082 " 🔵 claude-mem worker-service bound to wrong port 37777 instead of 37701
18083 2:29p 🔵 Port mismatch caused by settings.json override not applied to health checks
S3396 Configure Claude Code statusline to mirror robbyrussell/Starship prompt style (Apr 22 at 2:29 PM)
18086 3:03p 🔵 JCLAW-130 Test Coverage Expansion Scope Identified
18087 3:10p ✅ Phase 1 Test Coverage Implementation Started with Structured Task Breakdown
18088 3:11p 🔵 FunctionalTest Harness Pattern Documented from Existing Test Files
18089 3:14p 🟣 ApiChatControllerTest Implemented with Parameter Binding and Error-Response Coverage
18090 3:15p 🔄 ApiChatControllerTest Filename Sanitization Test Simplified
18091 3:19p 🟣 AgentServiceTest Implemented with Comprehensive Service-Layer Coverage
18092 3:20p 🔴 AgentServiceTest Cascade Delete Test Fixed for Correct Model Field Names and Enum Types
18093 3:28p ⚖️ SOUL.md Expansion - Comprehensive Assistant Behavioral Framework
18094 3:29p 🔵 WebhookControllerTest Uses Virtual Thread Transaction Pattern
18095 3:30p 🔴 ApiAgentsControllerTest.createMainAgent Fixed Transaction Visibility
18096 " 🔴 serveWorkspaceFileReturnsBinaryWithContentType Test Adapted for Binary Pipeline
18097 3:31p ✅ Backend Test Suite Expanded to 67 Tests - All Passing
18098 3:32p ✅ FunctionalTest Transaction Isolation Pattern Documented in Project Memory
S3397 FunctionalTest Transaction Isolation Pattern Documented in Project Memory (Apr 22 at 3:32 PM)
18099 3:37p ⚖️ Backend Test Audit Phases 2-4 Task Breakdown Created
18100 3:38p 🔵 ModelDiscoveryService Ollama Native Discovery Already Well-Tested
18101 3:41p 🟣 LLM Provider Test Suite Implemented with Reflection-Based Request Serialization Tests
18102 3:42p 🟣 SystemPromptAssemblerTest Validates JCLAW-128 Cache Boundary and Workspace File Ordering
18103 " 🔵 OllamaProviderTest Compilation Failure - extractReasoningFromDelta Not Visible
18104 3:45p 🟣 Phase 2 Backend Test Suite Passes - LLM Provider and System Prompt Tests Green
18105 3:49p 🟣 Frontend Chat Page Streaming State Machine Tests Implemented
18106 3:53p 🟣 Phases 2-4 Backend Test Audit Completed - 2,880 Lines of New Test Coverage Green
18107 3:55p 🔵 Claude Code Plugin Uninstall Procedure Verified
### Apr 23, 2026
18108 1:50a 🔵 Comprehensive Backend Java Audit Report Found
**18109** 1:52a 🔵 **Task Poller Unbounded Virtual Thread Fan-Out Confirmed**
Code inspection confirmed the audit's P1 finding about unbounded task poller concurrency. TaskPollerJob.doJob() at line 42 creates a fresh virtual-thread-per-task executor for each 30-second polling cycle and submits all pending due tasks to executor.invokeAll() without any cap. While virtual threads are cheap for blocking I/O, downstream resources like JDBC connections, LLM provider quotas, browser processes, and filesystem locks remain bounded.

The application does have good virtual thread infrastructure: VirtualThreads.java provides centralized factory methods with graceful shutdown support, and PlayPoolAutoSizer intelligently sizes the Play worker pool based on available processors with cgroup awareness. However, TaskPollerJob bypasses the centralized helpers and creates ad-hoc executors.

A broader pattern emerged: the codebase has 50+ call sites using Thread.ofVirtual().start() directly across AgentRunner (5 sites), ApiChatController, TelegramChannel, multiple webhook controllers, ConversationService, and test files. While this approach works, it reduces observability since these threads lack consistent naming, metrics tracking, or centralized accounting. The audit correctly identified this as a P2 observability and backpressure risk.
~531t 🔍 20,710

18110 " 🔵 SSE Manual JSON Escaping Incomplete in ApiChatController
18111 " 🔵 Strong OOP Patterns with Modern Java 25 Features
**18112** 1:53a 🔵 **Binary Scanner Fail-Open Behavior Confirmed in Code**
Code inspection confirmed the audit's P1 binary scanner fail-open finding. SkillBinaryScanner.scan() performs an early-return at lines 76-78 when the activeScanners list is empty, which occurs when none of the three configured scanners (MalwareBazaar, MetaDefender, VirusTotal) have API keys set. This path returns an empty violations list with no logged warning, allowing skill promotion to proceed as if scanning succeeded.

The fail-open behavior is intentional and documented in the class javadoc (lines 36-38): "This class never throws on I/O or scanner errors — scanning failures fail open to preserve availability." Individual file scan errors (hash failures, network issues) are logged as warnings but never block the operation. This design prioritizes development velocity and availability over security hardening.

The audit correctly identified this as a P1 gap for production deployments. The recommended fix is to add a required/fail-closed configuration mode (e.g., `skills.scanner.requiredForPromotion=true`) that enforces at least one active scanner and treats empty scanner lists as an error rather than a silent pass. The existing scanner infrastructure is well-designed with proper SHA-256 hashing, OR-semantics composition, and audit logging—it just needs a production-ready enforcement mode.
~544t 🔍 12,180

**18113** " 🔵 **Hardlink Sandbox Defense Has Explicit Test Coverage**
Code inspection revealed that the audit's P2 assessment "Hardlink sandbox defense lacks obvious explicit regression coverage" was incorrect. AgentService.acquireContained() includes a unix:nlink attribute check at lines 431-434 that rejects regular files with more than one hard link, and ToolSystemTest contains an explicit hardlink escape regression test at lines 495-516.

The test creates a file outside the workspace, uses Files.createLink() to hardlink it into the workspace (only works on same filesystem since hardlinks cannot cross mount boundaries), and verifies that the Read tool properly rejects access with an error message mentioning "hardlink" or "nlink". This provides clear regression protection for the hardlink attack vector.

The implementation is well-designed: it includes graceful degradation for non-POSIX filesystems, applies the check only to regular files (directories legitimately have nlink > 1 due to subdirectory entries), and is documented with a clear threat model explanation. The hardlink defense is one of four layers in acquireContained's defense-in-depth strategy alongside lexical validation, canonical path resolution, and double-resolve TOCTOU protection.

This finding upgrades the hardlink defense from "needs test" to "has good test coverage and thoughtful implementation." The audit should remove this item from the P2 quick-fix list.
~580t 🔍 12,180

**18114** " 🔵 **JaCoCo Coverage Tooling Configured But Cannot Execute**
Code and configuration inspection confirmed the audit's P1 finding about coverage tooling. JaCoCo is properly configured across three integration points: the Play test agent configuration (application.conf), the XML report generation (Jenkinsfile), and SonarQube upload (sonar.properties). The infrastructure exists and appears to work in Jenkins CI, but cannot execute in the current environment.

The `play test` command failed with a JDWP (Java Debug Wire Protocol) transport error: "bind failed: Operation not permitted." This suggests the environment has restrictions on opening debug ports, preventing the test framework from starting. The framework attempts to use JPDA port 8100 and mentions "Will try to use any free port" but still fails during initialization before any tests run.

This confirms the audit's assessment that coverage is "good targeted behavioral coverage, incomplete tooling and runtime validation." The test suite exists (1139 @Test methods), the coverage instrumentation is configured, and jdeps proves the compiled artifacts exist, but the inability to run `play auto-test` or `play test` makes it impossible to generate numeric coverage reports or validate that all tests still pass.

The audit correctly prioritized fixing the test-port binding issue as P1 since it blocks both coverage measurement and runtime class-load sampling needed for dependency optimization.
~574t 🔍 12,180

**18115** " 🔵 **Dependency Analysis Confirms Core Runtime Jar Usage**
Static dependency analysis via jdeps confirmed the audit's jar optimization findings. The tool identified 29 jars with direct static references from compiled application code, validating that core runtime dependencies are actively used. The analysis supports the audit's conclusions about jar size distribution and optimization opportunities.

Playwright's driver-bundle-1.52.0.jar dominates the dependency footprint at 191MB (77% of total), confirming it as the primary optimization target. However, this jar is justified: it contains browser automation drivers needed for PlaywrightBrowserTool's runtime functionality. The jar can only be removed if browser automation becomes optional.

Apache Tika presents the next optimization opportunity. DocumentsTool uses AutoDetectParser (line 304), which dynamically loads parser modules via ServiceLoader rather than static imports. This means jdeps cannot see the transitive Tika parser jars, making them appear "unused" even though they're required at runtime. The audit correctly identified this as requiring fixture tests to prove which document formats are actually supported before narrowing the parser dependencies.

The presence of test-only jars (lombok, assertj, mockito) in the static jdeps output's absence suggests they're leaking onto the runtime classpath as the audit suspected. These are good candidates for exclusion after the test environment is fixed and class-load sampling can confirm they're truly unused at runtime.
~581t 🔍 12,180


Access 622k tokens of past work via get_observations([IDs]) or mem-search skill.
</claude-mem-context>