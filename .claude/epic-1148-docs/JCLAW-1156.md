# AGENTS.md fragment — JCLAW-1156 (resource-leak gate)

**Target section:** `## Architecture` → `### Backend`, as a new subsection immediately after
the *Nullness — NullAway on the Gradle compile* subsection JCLAW-1149 adds. Heading:
`### Resource leaks — MustBeClosed on the Gradle compile`.

**Prose to merge verbatim:**

---

### Resource leaks — MustBeClosed on the Gradle compile

Error Prone's `MustBeClosedChecker` rides the same Gradle compile as NullAway, at `ERROR`, and
additionally on `compileTestJava` — which the nullness block deliberately excludes. A
constructor or factory annotated `com.google.errorprone.annotations.@MustBeClosed` may only be
called from a try-with-resources resource variable, or returned from another `@MustBeClosed`
method. Anything else fails the build.

**What carries the annotation.** Every concrete `AutoCloseable` in `app/`: `McpClient`,
`McpStdioTransport`, `McpStreamableHttpTransport`, `DirectLuceneMessageSearchRepository`'s
`LeasedSearcher`, `VoiceVad`, `VoiceSession`, and `LatencyTrace.bind`. The `McpTransport` and
`LatencyTrace.Binding` interfaces carry nothing — the annotation belongs on the thing that
hands out an instance, which is the implementations' constructors and `bind` respectively.
`ResourceLeakGateConformanceTest` fails if a new `AutoCloseable` class appears in `app/` with no
`@MustBeClosed` anywhere in its file, or if either compile task drops the check below `ERROR`.

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

**Suppressions.** `@SuppressWarnings("MustBeClosed")` with a one-line reason, and rare — three
exist today. `McpConnectionManager.doConnect` and `McpServerService.testConnection` build a
transport that a longer-lived owner closes; `VoiceController.initSession` hands its `VoiceVad`
to a `VoiceSession` that outlives the method, with a local `handedOff` flag closing it on every
path that never gets there. A fourth shape appears on the two `buildTransport` methods, which
are annotated `@MustBeClosed` *and* suppressed: the checker does not treat a `yield` from a
switch block arm as a return position (a plain `->` arm it does), so the suppression silences
the body while the contract still binds every caller.

---

**Note for whoever widens this:** `services.scanners.ScannerHttpClient.send` returns a fresh
`okhttp3.Response` whose Javadoc already tells callers to close it in try-with-resources, and is
the strongest remaining candidate for `@MustBeClosed` in `app/`. It was left alone here because
JCLAW-1156's acceptance criteria scope that clause to `HttpFactories` and the LLM driver. It is
a `@FunctionalInterface` implemented by lambdas, so the widening needs a check that Error Prone
accepts `@MustBeClosed` on a method implemented that way.
