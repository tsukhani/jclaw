# Backend Java Audit - 2026-04-22

Scope: backend Java/Play only. This audit intentionally excludes the alpha-stage multi-user authentication/authorization regime, but includes dependency, shell, file, network, process, webhook, tool-security, performance, coverage, and maintainability risks.

Java target observed: 25.0.2.

## Executive Answers

### 1. Are all jars declared in the Java process actually being used or called?

No. The core runtime jars are mostly justified, but the runtime classpath is carrying broad parser, test, and tooling transitive dependencies that are not statically referenced by backend code.

The biggest confirmed dependency cost is Playwright's `driver-bundle-1.52.0.jar`, at 191 MB out of a 249 MB `lib/` directory. It is justified only while browser automation remains a runtime feature. The next biggest optimization area is Apache Tika's standard parser package, which intentionally pulls broad parser modules and service providers for many file families. `DocumentsTool` uses `AutoDetectParser`, so these jars are runtime/dynamic rather than statically visible, but the current parser set is wider than the product's likely document surface.

No jar should be deleted immediately from this audit alone. Static import scans, `jdeps`, and `META-INF/services` inspection support several removal candidates, but `play auto-test` could not complete in this environment because Play failed to bind the test HTTP port. Runtime class-load evidence is therefore incomplete.

### 2. Are we optimally creating threads, objects, processes, and clients?

Partially. The backend is directionally aligned with Java 25 virtual-thread practices: shared HTTP clients exist, Play's worker pool is auto-sized, tool parallelism defaults to conservative safety, task polling uses virtual threads, and browser sessions are cached per agent.

The highest-risk gap is unbounded fan-out. `TaskPollerJob` can create one virtual thread per due task, and `AgentRunner` creates several ad hoc virtual threads for streaming, persistence, queue drain, and tool calls. Virtual threads reduce blocking cost but do not remove downstream limits such as JDBC connections, LLM provider quotas, browser processes, filesystem locks, and queue pressure.

Existing load-test JSONs show the default concurrent-50 path is materially slower than await mode: default concurrent-50 averages 3718 ms with a 9793 ms max, while concurrent-50 await mode averages 1133 ms with a 2116 ms max. That is a strong signal to profile queueing/backpressure behavior before treating current defaults as optimal.

### 3. Do we have proper coverage for critical regions?

There is strong test intent, but coverage is not yet objectively measurable. The backend has 117 app Java files, 62 Java test files, and 936 `@Test` methods. Critical areas such as shell execution, workspace containment, SSRF guardrails, skill promotion, streaming, task polling, channels, metrics, and session compaction all have focused tests.

However, no active numeric coverage report is configured, and this audit could not run a clean `play auto-test` baseline because the test server failed to bind. Coverage should therefore be judged as "good targeted behavioral coverage, incomplete tooling and runtime validation." The most important gaps are hardlink sandbox tests, binary-scanner fail-closed behavior, task-poller fan-out limits, document parser fixture coverage, and performance regression gates.

### 4. Are we adhering to DRY, YAGNI, SOLID, GRASP, and clean-code practices?

Partially. Many small services are well factored, and security boundaries are centralized in places like `AgentService`, `SsrfGuard`, `VirtualThreads`, `HttpClients`, `ToolRegistry`, and `WebhookUtil`.

The main clean-code risk is that several load-bearing classes have accumulated too many responsibilities:

- `AgentRunner.java`: 1674 LOC, orchestration, tool loops, streaming, usage accounting, persistence, queue drain, compaction, and channel dispatch.
- `FileSystemTools.java`: 1230 LOC, JSON dispatch, read/write/edit operations, patch parsing, lock management, rollback, and skill-specific version semantics.
- `TelegramStreamingSink.java`: 922 LOC, buffering, formatting, chunking, edit/send behavior, and Telegram API details.
- `LlmProvider.java`, `Commands.java`, `SkillPromotionService.java`, `TelegramChannel.java`, `ShellExecTool.java`, `AgentService.java`, and `ApiChatController.java` are also high-risk enough to prioritize for review before lower-traffic code.

## Evidence Collected

### Repository and test inventory

- Backend app Java files: 117.
- Backend Java test files: 62.
- Test methods: 936 `@Test` annotations.
- App Java LOC: 23972.
- `lib/`: 152 jars, 249 MB.
- Largest jars:
  - `driver-bundle-1.52.0.jar`: 191 MB.
  - `poi-ooxml-lite-5.4.1.jar`: 5.7 MB.
  - `biz.aQute.bndlib-6.4.1.jar`: 3.0 MB.
  - `poi-5.4.1.jar`: 2.9 MB.
  - `telegrambots-meta-9.5.0.jar`: 2.3 MB.
  - `xmlbeans-5.3.0.jar`: 2.1 MB.
  - `openpdf-1.3.35.jar`: 2.1 MB.
  - `commons-math3-3.6.1.jar`: 2.1 MB.
  - `pdfbox-3.0.5.jar`: 2.0 MB.
  - `lombok-1.18.34.jar`: 2.0 MB.

### Analysis commands and limitations

- `jdeps --multi-release 25 --ignore-missing-deps --class-path 'lib/*' -summary precompiled/java`
- Source import scans over `app/` and `test/`.
- `jar tf` service-provider checks for `META-INF/services`.
- Existing `docs/perf/*.json` comparison.
- Attempted runtime class-load sampling with `JAVA_TOOL_OPTIONS='-Xlog:class+load=info:file=/tmp/jclaw-classload.log' play auto-test`.

The test/class-load attempt did not reach a successful test baseline:

- `play auto-test` failed with `Could not bind on port 9100`.
- `play auto-test --http.port=9200` failed with `Could not bind on port 9200`.
- `lsof` did not identify listeners on those ports from inside this environment.
- An escalated retry was requested but rejected by the approval flow.
- Class-load logs were still produced, but only captured startup before the bind failure.

## Jar Findings

### Direct dependency classification

| Dependency | Classification | Evidence | Finding |
| --- | --- | --- | --- |
| `play` | direct runtime/framework | Play 1.x app conventions and test base classes require it. | Required. |
| `play -> docviewer` | tooling/runtime module | Play module dependency. | Keep unless production dist proves it is not packaged or needed. |
| `org.jsoup:jsoup` | direct runtime | `org.jsoup.Jsoup` imported. | Required, but declaration says 1.18.3 while resolved jar is 1.21.2. Align the pin or document Ivy conflict resolution. |
| `com.microsoft.playwright:playwright` | direct runtime/dynamic | Browser tool imports Playwright classes. `driver-bundle` dominates jar size. | Required while browser tool is shipped. Consider lazy/optional packaging if browser automation can be optional. |
| `org.apache.tika:tika-core` | direct runtime/dynamic | `AutoDetectParser`, `BodyContentHandler`, and Tika metadata imports. | Required for document extraction. |
| `org.apache.tika:tika-parsers-standard-package` | runtime/dynamic, needs slimming experiment | Used through Tika service providers, not direct imports. | High-value dependency optimization. Replace broad package with narrower parser modules only after fixture tests prove supported formats. |
| `com.vladsch.flexmark:flexmark` and extensions | direct runtime | 51 `com.vladsch` imports and `jdeps` hits. | Required. |
| `org.xhtmlrenderer:flying-saucer-pdf-openpdf` | direct runtime | `ITextRenderer` import and `jdeps` hits. | Required for PDF writing. |
| `org.hdrhistogram:HdrHistogram` | direct runtime | `AtomicHistogram` import and `jdeps` hit. | Required for metrics. |
| `org.junit.vintage:junit-vintage-engine` | test-only | Needed by Play/JUnit compatibility; not app imports. | Keep in test classpath. Avoid production packaging if possible. |
| `org.telegram:telegrambots-client` | direct runtime | Telegram channel imports. | Required while Telegram channel is enabled. |
| `org.telegram:telegrambots-longpolling` | direct runtime | Telegram long-polling imports. | Required while Telegram channel is enabled. |
| `com.squareup.okhttp3:okhttp-jvm` | direct runtime | `SsrfGuard` uses `OkHttpClient`, `Request`, and `Dns`. | Required for guarded fetch/search clients. |

### Major transitive jar classification

| Jar or family | Classification | Evidence | Recommendation |
| --- | --- | --- | --- |
| `driver-bundle-1.52.0.jar` | runtime/dynamic | Pulled by Playwright; browser lifecycle depends on driver. | Keep for now. Explore optional/lazy browser-tool packaging if jar footprint matters. |
| Tika parser modules: `tika-parser-*` | runtime/dynamic, needs experiment | Service provider entries under `META-INF/services`; used by `AutoDetectParser`. | Build document fixture suite, then replace standard package with narrowed modules. |
| PDF/image parser transitive jars: `pdfbox`, `fontbox`, `jbig2-imageio`, `jai-imageio-*` | runtime/dynamic | Tika PDF/image parser services and document extraction. | Keep unless fixture suite drops PDF/image extraction support. |
| POI/OOXML jars: `poi`, `poi-ooxml`, `poi-ooxml-lite`, `xmlbeans`, `commons-math3` | runtime/dynamic | Tika Office parsers and `DocumentWriter`/OOXML use. | Keep, but verify exact formats required. |
| `openpdf` | direct runtime | `jdeps` and Flying Saucer PDF rendering. | Required. |
| `jackson-*` | runtime/framework/transitive | Runtime class-load logs and service providers. | Required by libraries; do not remove manually. |
| `gson` | runtime/framework | Play/framework usage and source imports. | Required. |
| `log4j-*` | runtime/framework | Heavy startup class-load counts. | Required by Play fork/runtime. |
| `slf4j-simple` | runtime/tooling transitive risk | Service provider present, launchers often force provider selection. | Exclude if safe, or add preflight check so bare `play run` cannot accidentally bind the simple logger. |
| `mockito-*`, `mockwebserver`, `assertj-core`, `byte-buddy-agent`, `objenesis`, `hamcrest` | test-only/candidate remove from runtime packaging | No app/test imports found except JUnit. | Candidate exclude after green `play auto-test` and class-load sampling. |
| `jmh-*` | tooling-only/candidate remove from runtime packaging | No source imports found. | Candidate exclude after dependency graph confirms no runtime consumer. |
| `lombok` | tooling-only/candidate remove from runtime packaging | No Lombok annotations found in source. | Candidate exclude after compile/test verification. |
| `flexmark-test-*`, `pdf-test` | test-only/candidate remove from runtime packaging | Dependency comments reference flexmark test transitive behavior. | Candidate exclude from runtime packaging after green tests. |
| `kotlin-stdlib` | transitive runtime | Pulled by Telegram or OkHttp ecosystem. | Keep unless dependency tree proves unused after class-load sampling. |
| `biz.aQute.bndlib` and OSGi support jars | transitive needs experiment | Startup class-load shows OSGi classes. | Do not remove without graph and runtime proof. |

### Dependency conclusions

There are likely jar optimization wins, but no safe immediate deletions:

1. Browser automation is the jar-size outlier. Make Playwright optional only if the browser tool can be optional.
2. Tika's standard parser package is the largest avoidable dependency surface after Playwright.
3. Several test/tooling jars appear to be leaking onto the broad `lib/` classpath.
4. `jsoup` version drift should be cleaned up so declared dependencies match runtime reality.
5. ServiceLoader-driven dependencies must be treated as live until fixture tests and runtime class-load logs say otherwise.

## Concurrency and Performance Findings

### Strengths

- `PlayPoolAutoSizer` auto-sizes the Play worker pool to at least 8 threads and considers cgroup CPU limits.
- `VirtualThreads` centralizes scheduled virtual-thread executors and graceful shutdown.
- `HttpClients` shares Java HTTP clients instead of constructing them per request.
- `ApiChatController` uses a shared SSE scheduler and shuts it down through a Play job hook.
- `TaskPollerJob` uses virtual threads for task execution and an atomic PENDING-to-RUNNING update to avoid duplicate claims.
- `ToolRegistry` defaults tools to `parallelSafe=false`, which is a conservative concurrency default.
- `AgentRunner` preserves result order while grouping non-parallel-safe tools by name.
- `PlaywrightBrowserTool` caches per-agent browser sessions, synchronizes per page, and has cleanup/shutdown hooks.
- `ConversationQueue` has per-conversation state, bounded queues, and queue/collect/interrupt behavior.

### Risks

| Area | Finding | Impact |
| --- | --- | --- |
| Task polling | Due tasks can fan out into one virtual thread per task without a configured concurrency cap. | JDBC, provider, browser, and filesystem resources can be saturated even though virtual threads are cheap. |
| Agent runner | Many `Thread.ofVirtual().start(...)` calls are created ad hoc. | Harder observability, shutdown accounting, naming, and backpressure. |
| Queueing | Concurrent-50 default load-test results are much worse than await mode. | User-perceived latency may degrade under load or queue mode. |
| Shell execution | Terminal-image early return can leave subprocesses alive until timeout. | Useful for QR flows, but it should be tracked as an interactive/background process rather than an unaccounted process. |
| Browser automation | Cached Playwright sessions are necessary but process-heavy. | Needs caps, idle cleanup, and load tests for multiple active agents. |
| SSE JSON escaping | `ApiChatController.writeSseToken` manually escapes a subset of JSON string control characters. | Low-probability malformed SSE frame or injection-style robustness issue when tokens contain other control chars. |

### Existing load-test signals

| Scenario | Total | Success | Wall ms | Avg ms | Max ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| concurrent-10 default | 50 | 50 | 5686 | 1136 | 1378 |
| concurrent-10 await | 50 | 50 | 5636 | 1126 | 1133 |
| concurrent-10 tools | 50 | 50 | 2968 | 593 | 602 |
| concurrent-30 tools | 90 | 90 | 1780 | 591 | 598 |
| concurrent-50 default | 150 | 150 | 14737 | 3718 | 9793 |
| concurrent-50 await | 150 | 150 | 4261 | 1133 | 2116 |

Follow-up scenarios should compare queue, collect, interrupt, and await modes at 10/30/50/100 concurrent conversations, with task-poller due-task floods, browser-tool sessions, and provider latency injected separately.

## Coverage Matrix

Coverage is evaluated by critical behavior protection, not raw test count.

| Critical region | Existing evidence | Assessment | Gaps |
| --- | --- | --- | --- |
| Shell execution | `ShellExecToolTest`, `ToolSystemTest`, `AgentRunnerDedupTest`. | Strong coverage for allowlist, main-agent bypass identity, env filtering, symlink escape, timeout, and QR/image dedup behavior. | Add explicit tests for terminal-image early return lifecycle and background process cleanup/cancel behavior. |
| Filesystem sandboxing | `ToolSystemTest`, `AgentSystemTest`, `ControllerApiTest`, `ShellExecToolTest`, `TelegramOutboundPlannerTest`. | Strong coverage for traversal, symlink containment, controller boundaries, and workspace file serving. | Add explicit hardlink rejection test and double-resolve race-oriented regression test. |
| SSRF/fetch/search/browser URLs | `SsrfGuardTest`, `ToolSystemTest`, `PlaywrightToolTest`. | Strong unit coverage for unsafe schemes, literal metadata IPs, redirect validation, and Playwright URL checks. | Add integration-style DNS/proxy/rebinding fixture when test networking can be controlled. |
| Skill promotion/scanning/versioning | `SkillPromotionServiceTest`, `SkillBinaryScannerTest`, `SkillAllowlistTest`, `SkillVersionManagerTest`, `SkillIconTest`. | Good coverage of skill copy/promotion/versioning/security policies. | Add fail-closed scanner mode, scanner-disabled status tests, and LLM sanitizer failure-path tests. |
| Agent runner/tool loop/streaming | `AgentRunnerCoreTest`, `AgentRunnerDedupTest`, `AgentRunnerToolCallIdTest`, `AgentRunnerUsageTest`, `StreamingToolRoundTest`, `ParallelToolExecutionTest`. | Good behavioral coverage around tool calls, usage, streaming loops, and result ordering. | Add full runtime class-load/test baseline once port bind is fixed; add provider fault-injection cases. |
| Queueing and concurrency | `ConversationQueueTest`, `PerformanceFixesTest`, `VirtualThreadsTest`, `ProviderRegistryAtomicTest`, `TxTest`. | Good recent regression coverage. | Add sustained load tests and queue-mode latency gates. |
| Task poller and schedules | `TaskPollerJobTest`, `TaskPollerTxTest`, `TaskSchedulingTest`, `JobLifecycleTest`. | Good unit coverage for task lifecycle and transactions. | Add due-task fan-out cap tests and retry/backoff load behavior. |
| Providers | `LlmProvider` tests through runner/provider-related tests. | Some coverage via integration seams. | Add focused tests for provider timeout, cancellation, retry, malformed streaming, and quota/backoff behavior. |
| Channels and webhooks | `ChannelTest`, `WebhookControllerTest`, `MockTelegramSinkIntegrationTest`, `TelegramStreamingSinkTest`, `TelegramChannelTest`, `TelegramModelSelectorTest`, `TelegramOutboundPlannerTest`, `IntegrationTest`. | Strong mocked coverage for Telegram/channel/webhook flows. | Add live contract tests behind opt-in environment variables only. |
| Memory and session compaction | `MemoryStoreTest`, `SessionCompactorTest`, `AgentRunnerCoreTest`. | Good coverage for current JPA/local memory path. | Neo4j or alternate memory backends remain unvalidated if enabled later. |
| Document writing and extraction | `DocumentWriterTest`, `DocumentsToolTest`. | Moderate. Document writing is tested; extraction appears less fixture-rich. | Add real fixtures for PDF, DOCX, XLSX, PPTX, HTML, TXT, RTF/ODT if supported. |
| Metrics and load harness | `ApiMetricsControllerTest`, `LatencyStatsTest`, `LoadTestHarnessTest`, `docs/perf/*.json`. | Good local evidence. | No automated perf regression gate or threshold checks in CI. |
| Config side effects and tool registration | `ConfigServiceTest`, `JobLifecycleTest`, `ToolCatalogTest`, `ControllerApiTest`. | Good. | Add config-drift and dependency-version smoke checks. |

## Clean-Code Findings

| Class | Finding | Recommendation |
| --- | --- | --- |
| `AgentRunner` | Too many reasons to change: orchestration, streaming, tool loop, queue drain, compaction, persistence, channel dispatch, usage accounting. | Split along stable responsibilities: prompt/session preparation, LLM turn loop, tool execution, persistence/usage, queue drain, channel emission. Keep public behavior under current tests while extracting. |
| `FileSystemTools` | Tool dispatch, file editing, patch parsing, locking, rollback, and skill-version semantics are co-located. | Extract file operation service, line editor, patch applier, and skill `SKILL.md` policy. |
| `TelegramStreamingSink` | Formatting, chunking, buffering, edit/send decisions, and API calls are tightly coupled. | Separate message formatting/chunk planning from Telegram transport. |
| `LlmProvider` | Provider concerns are broad and high-risk. | Extract request building, response parsing, streaming parsing, retry/timeout policy, and pricing/usage mapping where practical. |
| `Commands` | Slash command surface is large and likely to grow. | Move command implementations into handlers with a small registry. |
| `SkillPromotionService` | Promotion, validation, scanning, copying, and sanitization coordination are concentrated. | Split validation/scanning/copying/sanitization into collaborators with narrow tests. |
| `TelegramChannel` | Channel lifecycle, command handling, and Telegram transport behavior are mixed. | Keep channel orchestration thin; delegate transport and command parsing. |
| `ShellExecTool` | Security checks are good, but process lifecycle and terminal-image behavior deserve clearer separation. | Extract command policy, process runner, terminal-image renderer, and result mapper. |
| `AgentService` | Security-critical sandbox logic is valuable and should stay centralized, but the service also owns agent lifecycle and deletion cascade. | Keep path containment centralized; consider extracting workspace lifecycle/deletion cleanup. |
| `ApiChatController` | Controller includes SSE scheduling and manual JSON token escaping. | Use a shared JSON writer/escaper and push streaming mechanics behind a service. |

## Security and Performance Risk Register

| Priority | Risk | Evidence | Validation path |
| --- | --- | --- | --- |
| P1 | Task poller unbounded virtual-thread fan-out can overload bounded downstream resources. | Virtual threads are launched per due task; no cap was observed. | Add cap, unit test many due tasks, run load test with simulated slow provider/JDBC pressure. |
| P1 | Binary scanner can fail open or no-op when scanner API keys are absent. | Current behavior supports alpha/dev convenience. | Add required/fail-closed config and status tests. |
| P1 | Tika standard parser package expands attack surface and jar size. | Tika services include many parser families beyond likely document needs. | Build fixture suite, switch to narrowed parser dependencies, compare class-load and extraction results. |
| P2 | Ad hoc virtual-thread creation reduces observability and backpressure control. | Multiple direct `Thread.ofVirtual().start(...)` call sites. | Centralize naming/metrics/accounting and add shutdown tests. |
| P2 | SSE token manual escaping is incomplete for all JSON control characters. | Manual escaping handles common characters but not every control char below `0x20`. | Replace with Gson/Jackson/string writer and add control-character tests. |
| P2 | Hardlink sandbox defense lacks obvious explicit regression coverage. | Code rejects `nlink > 1`; tests found for symlink/path traversal. | Add POSIX-only hardlink test with skip on unsupported filesystems. |
| P2 | Shell terminal-image early return can leave subprocesses alive until timeout. | Early result is returned for terminal-rendered images. | Add lifecycle registry/cancel path and process cleanup tests. |
| P2 | Runtime classpath contains likely test/tooling jars. | No source imports for Mockito, MockWebServer, AssertJ, JMH, Lombok, ByteBuddy, Objenesis. | After test-port fix, exclude candidates one group at a time and run `play auto-test` with class-load sampling. |
| P2 | Default concurrent-50 latency is much worse than await mode. | `docs/perf` JSONs show 3718 ms average and 9793 ms max for default concurrent-50. | Profile queue modes and add perf threshold gate. |
| P3 | Declared `jsoup` version differs from resolved jar. | `dependencies.yml` declares 1.18.3; `lib/` has 1.21.2. | Align declaration or document override. |
| P3 | `slf4j-simple` service provider can surprise bare runtime launches. | Service provider jar present. | Exclude if safe or add startup/preflight warning. |
| P3 | Dependency vulnerability scan was not performed in this offline run. | Network-restricted environment. | Add OSV/OWASP dependency audit step to release checklist/CI. |

## Prioritized Remediation Backlog

| Priority | Area | Finding | Evidence | Proposed change | Expected impact | Regression tests | Type |
| --- | --- | --- | --- | --- | --- | --- | --- |
| P1 | Coverage/tooling | No active numeric backend coverage report. | 936 tests exist, but no coverage output was configured. | Add JaCoCo-based coverage reporting compatible with Play 1.x test execution; start with report-only thresholds, then gate critical packages. | Makes coverage gaps visible and prevents regressions. | `play auto-test` plus generated coverage report in CI/local script. | tooling |
| P1 | Test infrastructure | `play auto-test` cannot bind test port in this environment. | Failures on 9100 and 9200; no listener found by `lsof`. | Add documented test-port override/fallback script and diagnose Play test server binding behavior. | Restores reliable baseline and runtime class-load sampling. | `play auto-test` succeeds on default or discovered free port. | tooling |
| P1 | Dependency optimization | Tika standard parser package is broad. | Service providers include many parser families; used dynamically by `AutoDetectParser`. | Add fixture suite, then replace standard package with only required parser modules. | Reduces jar size, attack surface, classpath noise. | Fixture extraction tests for supported formats plus class-load comparison. | spike |
| P1 | Concurrency/performance | Task poller has no visible max concurrency cap. | Due tasks can launch one virtual thread each. | Add `tasks.poller.maxConcurrent` and batch size config; enforce with tests. | Prevents resource exhaustion under task floods. | Many-due-task test proves cap and completion behavior. | quick fix |
| P1 | Tool security | Binary scanner can fail open/no-op when not configured. | Scanner behavior supports alpha convenience. | Add `skills.scanner.requiredForPromotion` or equivalent fail-closed production mode plus visible disabled status. | Prevents unscanned binary promotion in hardened environments. | Scanner-disabled, scanner-failure, scanner-success tests. | quick fix |
| P2 | Threading/observability | Virtual threads are created ad hoc in high-risk paths. | Multiple direct starts in runner/controller/task flows. | Centralize named virtual-thread launch helpers with metrics/accounting. | Easier profiling, cancellation, and shutdown validation. | Unit tests for naming/accounting and shutdown. | refactor |
| P2 | Clean code | `AgentRunner` has too many responsibilities. | 1674 LOC and many behavioral domains. | Extract prompt/session preparation, turn loop, tool execution, persistence/usage, queue drain, and channel emission. | Lower change risk and better test isolation. | Existing runner tests plus focused collaborator tests. | refactor |
| P2 | Clean code | `FileSystemTools` combines dispatch, editing, locking, patch parsing, rollback, and skill policies. | 1230 LOC. | Extract line editor, patch applier, file operation service, and skill-version policy. | Makes filesystem security easier to audit and extend. | Existing filesystem tests plus patch/line-editor unit tests. | refactor |
| P2 | SSE/security | Manual SSE token JSON escaping is incomplete. | `writeSseToken` escapes common cases only. | Use a shared JSON writer/escaper for SSE payloads. | Removes malformed JSON/control-char edge cases. | Tokens containing `\b`, `\f`, NUL-like controls, quotes, slashes, and Unicode separators. | quick fix |
| P2 | Filesystem security | Hardlink rejection needs explicit tests. | Hardlink defense exists in containment logic; obvious direct test not found. | Add POSIX hardlink escape regression test with skip if unsupported. | Protects a subtle sandbox invariant. | Hardlink inside workspace pointing to external file is rejected. | quick fix |
| P2 | Shell/process lifecycle | Terminal-image early return leaves process lifecycle implicit. | Early return exists for terminal-rendered QR/images. | Track interactive/background processes with explicit cancel/timeout policy. | Avoids hidden process/resource leaks. | QR/background command test proves cleanup or registry behavior. | refactor |
| P2 | Dependency cleanup | Test/tooling jars appear on broad classpath. | No imports for Mockito, MockWebServer, AssertJ, JMH, Lombok, ByteBuddy, Objenesis. | Exclude candidates one family at a time after test baseline is fixed. | Smaller runtime classpath and lower startup/security surface. | `play auto-test` and class-load log before/after each exclusion. | tooling |
| P2 | Performance | Default concurrent-50 path regresses versus await mode. | Existing perf JSON: 3718 ms avg/9793 ms max default vs 1133 ms avg/2116 ms max await. | Add queue-mode profiling and perf thresholds; tune default queue/await behavior. | Better p95/p99 latency under load. | Automated load scenarios at 10/30/50/100 concurrency. | spike |
| P2 | Documents | Document extraction fixture coverage is thin. | Tika is dynamic and broad; current tests do not prove all supported formats. | Add fixture matrix for PDF, DOCX, XLSX, PPTX, HTML, TXT, and any promised formats. | Enables safe parser slimming and protects extraction quality. | Golden extraction assertions per fixture. | tooling |
| P3 | Dependency drift | `jsoup` declaration differs from resolved jar. | Declared 1.18.3, resolved 1.21.2. | Align `dependencies.yml` with resolved version or add explicit override comment. | Reduces dependency ambiguity. | `play deps --sync` keeps expected jar. | quick fix |
| P3 | Logging dependency | `slf4j-simple` provider is present. | Service provider found in jar scan. | Exclude if compatible, or add launcher/preflight guard for provider choice. | Prevents unexpected logging provider behavior. | Startup smoke test verifies intended provider. | tooling |
| P3 | Dependency security | No vulnerability scan was run. | Offline/sandboxed audit environment. | Add OSV/OWASP/dependency-check job for `lib/` and `dependencies.yml`. | Catches known CVEs in transitive jars. | CI job produces machine-readable report and fails on configured severity. | tooling |

## Recommended Next Steps

1. Fix the `play auto-test` bind issue first. Without this, dependency removal and coverage claims remain partly speculative.
2. Add coverage reporting before broad refactors, so `AgentRunner` and `FileSystemTools` extractions are guarded by objective data.
3. Run the Tika/document fixture spike before excluding parser modules.
4. Add the small P1/P2 safety fixes: task-poller cap, scanner fail-closed mode, SSE JSON writer, and hardlink test.
5. Re-run class-load sampling after the baseline passes, then remove test/tooling jars one family at a time.

## Final Position

The backend is already doing many important Java 25-era things correctly: virtual-thread adoption is thoughtful, security-sensitive filesystem and SSRF boundaries are centralized, and there is substantial behavioral test coverage. The main risk is not a missing security pattern; it is that high-responsibility classes and a broad classpath make the system harder to reason about as it grows.

The cleanest path is to restore a reliable test baseline, add coverage tooling, then make targeted dependency and concurrency changes under measurement rather than shrinking jars or refactoring orchestration blind.
