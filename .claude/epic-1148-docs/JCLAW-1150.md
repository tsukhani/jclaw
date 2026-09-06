Target AGENTS.md section: `## Architecture` — insert as a new `### Wall clock — AppClock` subsection, immediately after `### Outbound HTTP — OkHttp 5`.

---

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
behaviour so the boundary is a tested fact rather than a comment.

**The gate.** `test/WallClockDisciplineTest` is an ArchUnit rule banning `Instant.now()`,
`LocalDate.now()`, `LocalDateTime.now()`, `OffsetDateTime.now()`, `ZonedDateTime.now()` and
`System.currentTimeMillis()` anywhere in `app/` outside `AppClock` itself. It is a `FreezingArchRule`,
because the `System.currentTimeMillis()` sites are not all wall-clock reads: 50 of them are interval
baselines for a throttle, a rate-limit window or a duration, where rewriting them would change
throttle behaviour for no gain. Those 50 are recorded in the committed `archunit_store/`, so the
exception list is explicit and shrinkable while a *new* read of either kind still fails the build.
`System.nanoTime()` is not matched at all — it has no relation to wall-clock time and is the correct
primitive for the interval measurement in `utils.LatencyStats`.

`conf/archunit.properties` pins `freeze.store.default.allowStoreCreation=false` so a run can never
silently mint a fresh baseline and turn the rule into a no-op. To regenerate deliberately, flip that
one line for a single run and flip it back — `playAutotest` does not forward `-D` to the Play JVM, so
the properties file is the only lever. The store key is the rule's description, so editing the
rule's `because(...)` text orphans the baseline.

**Writing a time-dependent test.** Bind a fixed clock instead of sleeping or asserting on a ±1s
window: `TaskSchedulingServiceTest` and `TaskSchedulingTest.cronEveryMinute` are the worked examples.
