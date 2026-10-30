# Wall clock — AppClock

Every wall-clock read in `app/` goes through **`utils.AppClock`** (JCLAW-1150). `AppClock.now()`
returns the current `Instant`; `AppClock.clock()` hands back the `java.time.Clock` behind it for
call sites that need a zone or a `Clock`-taking API. Production never binds anything — the unbound
default is `Clock.systemUTC()`.

A test overrides it for the duration of a call with `AppClock.runWith(clock, body)` or
`AppClock.callWith(clock, body)`, which bind a `ScopedValue` (final in JDK 25, JEP 506). The binding
is deliberately **not** a static setter: the play1 fork runs test classes concurrently, so a
process-global flip would leak a frozen clock into whatever else happens to be running.

The seam itself is guarded: see **Test-only seams** in [capabilities.md](capabilities.md).

**Propagation boundary.** A `ScopedValue` binding is visible to the binding thread and is inherited
by `StructuredTaskScope` forks. It is *not* inherited by a thread from
`Executors.newVirtualThreadPerTaskExecutor()`, `CompletableFuture.supplyAsync`, a raw
`Thread.start()`, or db-scheduler's worker pool — those read the unbound default, the system clock.
Code under test that crosses one of those boundaries must read the clock before spawning or rebind
inside the task. `WallClockDisciplineTest.theBindingDoesNotCrossAVirtualThreadExecutor` pins that
behaviour so the boundary is a tested fact rather than a comment. The same boundary governs
`HttpFactories.runWith` ([outbound-http.md](outbound-http.md)) and any future `ScopedValue` seam.

`InheritableThreadLocal` has the *opposite* boundary, and that asymmetry has bitten once.
`DangerousActionGate`'s `OWNER_INITIATED` and the task-fire origin are the only two in `app/`, and
both are deliberately inheritable so a subagent fork inherits the turn's trust as a floor. But
`Thread.ofVirtual()` inherits them by default, so a fork that starts work for a *different* sender
must opt out: `QueueDrainOrchestrator.startDrainThread` builds the `agent-drain` thread with
`inheritInheritableThreadLocals(false)`, because a guest message queued behind an owner's turn
would otherwise be drained carrying the owner's identity (JCLAW-1226). A new fork that crosses a
sender boundary needs the same flag.

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
