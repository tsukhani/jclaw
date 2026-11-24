# Structured concurrency — `utils.TaskScope`

Fan-out with all-or-nothing semantics goes through `app/utils/TaskScope.java`: a
try-with-resources scope over a virtual-thread executor where the first task to fail cancels the
scope, `join()` rethrows that failure as an `ExecutionException`, and `close()` cancels the scope
and waits for every fork to end, so no forked task outlives the block. It is homogeneous — every
task in a scope returns the same type — which covers the fan-out shape the codebase actually has;
heterogeneous fan-out still uses plain futures. `EvalFanOut.mapCasesBounded` is the reference
call site.

Canceling never interrupts a fork. An interrupt closes the FileChannel under H2, and a fork may
be running an agent turn that commits to the live database (AGENTS.md, Known pitfalls). So
cancellation is a flag: `isCancelled()` turns true when a fork fails, when `join()` sees a failure
and on `close()`, and each future is marked with `cancel(false)`. A fork that loops checks
`isCancelled()` to stop early; any other fork runs to its end. `close()` waits for every fork even
when the closing thread is interrupted, and re-asserts that interrupt once the wait is over
(JF-332). So every wait inside a fork must end on its own, through its own timeout or something
certain to release it: a fork parked on a latch, a lock or a socket read never re-reads the flag,
and nothing can cut the closer's wait short.

The JDK's own `java.util.concurrent.StructuredTaskScope` is deliberately **not** used. JCLAW-1155
spiked it and the answer was no:

- It interrupts its forks when the scope is canceled or closes, and an interrupt kills the H2 file
  a fork may be writing.
- It is still a preview API in JDK 25 (JEP 505). Since 1.13.113 the play1 fork's one switch for
  preview features is `java.enablePreview=true` in `conf/application.conf`: Gradle's
  `compileJava` and `compileTestJava`, Play's own javac and every Play JVM then all take
  `--enable-preview`, and javac compiles preview features only at the running JDK's own release,
  so the whole application would be tied to one JDK. JClaw does not set it.

**Revisit when structured concurrency leaves preview.** At that point `StructuredTaskScope` needs
no flag, but it still interrupts its unfinished subtasks when the scope is
canceled (under the default joiner, on the first failure) and when it closes. It can replace
`TaskScope` only at a call site whose forks never touch H2 or JPA; `TaskScope` stays for the rest.
