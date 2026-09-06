# JCLAW-1155 — doc fragment

**Merge into `AGENTS.md`, in the `## Architecture` section, as a new `###` subsection placed
immediately after `### Outbound HTTP — OkHttp 5`.** It is a peer of that subsection: one stack,
one entry point, and the recorded reason the obvious alternative is not used.

---

### Structured concurrency — `utils.TaskScope`

Fan-out with all-or-nothing semantics goes through `app/utils/TaskScope.java`: a
try-with-resources scope over a virtual-thread executor where the first task to fail cancels its
siblings, `join()` rethrows that failure as an `ExecutionException`, and `close()` cancels
whatever is still running so no forked task outlives the block. It is homogeneous — every task
in a scope returns the same type — which covers the fan-out shape the codebase actually has;
heterogeneous fan-out still uses plain futures. `EvalRunner.mapCasesBounded` is the reference
call site.

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

**Revisit when structured concurrency finalises — JDK 26 at the earliest.** At that point
`StructuredTaskScope` needs no flag from either compiler, and `TaskScope` should be deleted rather
than kept beside it.
