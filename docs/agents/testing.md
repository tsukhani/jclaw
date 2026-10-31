# Test isolation

Read this before a test flips a process-global, drives a provider, circuit breaker or stream, or carries an inbound fixture.

Each process-global has a lock: `LuceneTestSync`, `ShellSandboxSync`, `ToolRegistrySync`, `TelemetryTestSync`, `LoadTestHarnessSync`, `SlackWebApiTestSync`, `JevBreakerTestSync`, `FactoryRunnerSync` for `FactoryProcess.setRunnerForTest`, `ScrapeClientSwap` for `WebScrapeTool.CLIENT` and `RobotsCache`, and `ScrapeConfigGuard` for the scrape config keys, taken after `ScrapeClientSwap`; `CompactionTriggerSync` for every `chat.compaction.*` key and the history limits.

Circuit breakers live in a process-global registry keyed by provider or MCP server name, so a test that drives `chat()`, a stream or `callTool` mints its own name and `CircuitBreakers.remove`s it — never `test-provider`, which another class may be tripping. A registered provider is as global: `ProviderRegistry` keeps it cached 60 s past `Fixtures.deleteDatabase`, where a later class's agent naming it, as its provider or fallback, still reaches it, so seed one at `http://127.0.0.1:1/v1`, never a real host, and a functional class unregisters its own in `@AfterEach` (delete the keys, then `ProviderRegistry.refresh()`). Likewise no inbound fixture may carry a real Slack file URL or a WhatsApp access token: the reply or download would reach Slack or Meta.

A pool drain destroys the H2 in-memory test DB with its last connection; hold one connection across it (`TelemetryDataSource.switchTo` is the reference).
