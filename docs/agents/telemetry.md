# Telemetry — OpenTelemetry in-process

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
`otel.exporter.secretHeaders` (masked by the API), `otel.traces.sampler.ratio`,
`otel.service.name` and `otel.metrics.interval.seconds`, the two read at init — are
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
`ScopedValue` paragraph in [wall-clock.md](wall-clock.md) documents. `StreamingAgentRunner`'s `agent-stream` thread
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
