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
spiked it and the answer was no, for a reason specific to this repo's two-compiler build:

- `StructuredTaskScope` is still a preview API in JDK 25 (JEP 505), so `javac` refuses it without
  `--enable-preview` and, once given the flag, marks the calling classfile preview (minor version
  65535) — a marking the JVM then enforces at load time.
- `playRun`, `playStart` and `playAutotest` all `dependsOn("compileJava")`, so every `app/` class
  passes through Gradle's `javac` before Play starts. Neither the play1 Gradle plugin nor
  `conf/application.conf` passes `--enable-preview` to the app JVM (`jvm.memory` is the only
  injection point, and the plugin adds just `--enable-native-access=ALL-UNNAMED`).
- The play1 fork's dev-mode compiler is ECJ, configured by `ApplicationCompiler`'s fixed settings
  map. That map has no preview option and no config knob — `java.source` only selects
  source/target/compliance. ECJ 3.46 refuses `--enable-preview` at source 25 outright
  ("Preview of features is supported only at the latest source level"), and compiles preview API
  use to an **unmarked** classfile on a warning.

So the same source yields differently-marked bytecode from the two compilers, and the runtime
guard that `--enable-preview` exists to provide is enforced on one path and silently absent on the
other. `utils.TaskScope` avoids the split entirely.

**Revisit when structured concurrency leaves preview.** At that point `StructuredTaskScope` needs
no flag from either compiler, but it still interrupts its unfinished subtasks when the scope is
canceled (under the default joiner, on the first failure) and when it closes. It can replace
`TaskScope` only at a call site whose forks never touch H2 or JPA; `TaskScope` stays for the rest.
