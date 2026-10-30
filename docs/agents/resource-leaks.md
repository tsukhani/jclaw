# Resource leaks — MustBeClosed on the Gradle compile

Error Prone's `MustBeClosedChecker` rides the same Gradle compile as NullAway, at `ERROR`, and
additionally on `compileTestJava` — which the nullness block ([nullness.md](nullness.md)) deliberately excludes. A
constructor or factory annotated `com.google.errorprone.annotations.@MustBeClosed` may only be
called from a try-with-resources resource variable, or returned from another `@MustBeClosed`
method. Anything else fails the build.

**What carries the annotation.** Every concrete `AutoCloseable` in `app/`: `McpClient`,
`McpStdioTransport`, `McpStreamableHttpTransport`, `DirectLuceneMessageSearchRepository`'s
`LeasedSearcher`, `VoiceVad`, `VoiceSession`, `TaskScope`, `BrowserScreenProxy`,
`LatencyTrace.bind`, `TurnCancellation.register`, `SubagentRegistry.waitingOn`, the three
delegating telemetry leaves `DelegatingSampler`, `DelegatingSpanExporter` and
`DelegatingMetricExporter` (OTel's `Sampler` and both exporter interfaces extend `Closeable`),
and `GenAiSpans.Call.makeCurrent`, which hands out a `Scope`. The
`McpTransport` and `LatencyTrace.Binding` interfaces carry nothing — the annotation belongs on
the thing that hands out an instance, which is the implementations' constructors and `bind`
respectively. `ResourceLeakGateConformanceTest` walks the imported bytecode for every concrete
class assignable to `AutoCloseable` (so `implements McpTransport` counts) and fails if one appears
in `app/` with no `@MustBeClosed` outside a comment in its file, or if either compile task drops
the check below `ERROR`.

`HttpFactories` is deliberately untouched: its methods hand back six shared `OkHttpClient`
singletons (the three tiers and their SSRF-guarded variants), not per-call resources. The
resource on that path is the `Response`, which belongs to whoever executes the call —
`OkHttpLlmHttpDriver.send` already closes its own in try-with-resources, and `streamSse` blocks
to completion rather than returning a live stream handle, so neither returns anything a caller
could leak.

**The Lucene test lock.** `LuceneTestSync` guards a JVM-global index behind one
`ReentrantLock`; an acquire with no matching `release()` does not fail — it hangs every later
Lucene test on that lock, which surfaces as a suite-wide timeout with no pointer to the cause.
`LuceneTestSync.openLease()` / `closedLease()` return an `@MustBeClosed` `Lease`, so a window
that lives inside one method body is compiler-enforced. Windows that span JUnit lifecycle hooks
keep the older `openForTest()` / `release()` pair, because `@MustBeClosed` accepts only a
resource variable or a return and a `@BeforeEach`/`@AfterEach` pair is neither; those callers
are covered instead by a source scan in `ResourceLeakGateConformanceTest` that fails on an
acquire whose `release()` is not in an `@AfterEach`/`@AfterAll` hook or a `finally` block — a
release at the end of a test body never runs once an assertion above it fails. **Prefer the
lease** for any new Lucene test whose window fits in one method.

**Suppressions.** `@SuppressWarnings("MustBeClosed")` with a short reason, and rare — eleven
sites today. `McpConnectionManager.newClient` and `McpServerService.testClient` build a
transport that a longer-lived owner closes, and
`PlaywrightBrowserTool.launchSession` is the same shape with the owner written down: the
`BrowserScreenProxy` it opens becomes a component of the `BrowserSession` record it returns, and
`destroySession` closes it — with the method's own guarded teardown closing it on a launch that
throws partway, since a throw leaves no reference for anything else to close; `VoiceController.initSession` hands its `VoiceVad`
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
