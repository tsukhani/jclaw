# AGENTS.md fragment — JCLAW-1149 (nullness gate)

**Target section:** `## Architecture` → `### Backend`, as a new subsection immediately after
the *Outbound HTTP — OkHttp 5* subsection. Heading: `### Nullness — NullAway on the Gradle compile`.

**Prose to merge verbatim:**

---

### Nullness — NullAway on the Gradle compile

`org.jspecify:jspecify` annotations (`@Nullable` / `@NonNull` / `@NullMarked`) are checked by
**NullAway**, running as an Error Prone plugin on the Gradle `compileJava` task. Inside the
annotated packages a type with no annotation means *not null*, and NullAway fails the build on
any dereference, return, assignment or argument that contradicts that.

**Where it runs.** `./gradlew compileJava` only — which is what `.githooks/pre-push` invokes
before the test suite, and what Sonar already depends on. It does **not** run under `play run`
or `play autotest`: Play 1.x compiles with ECJ inside the fork, and a javac plugin cannot load
there. That split is structural, not a gap to close — the Gradle compile is the single place a
javac plugin can see this codebase, the same layering Spotless uses.

**What is in scope.** The `NullAway:AnnotatedPackages` option in `build.gradle.kts` currently
lists `utils`, `llm` and `agents`, and each of those packages carries a `package-info.java` with
`@NullMarked`. `models` is excluded deliberately: JPA populates entity fields reflectively after
construction, so every non-null column would report as uninitialised.

**To widen it.** Add the package name to the `AnnotatedPackages` option, add a
`package-info.java` carrying `@NullMarked` to that package *and to each of its subpackages*
(`@NullMarked` is per-package and does not inherit), then run `./gradlew compileJava` and
annotate what it reports. `NullnessGateConformanceTest` fails if a package inside the scope has
no `@NullMarked` package-info, if the checker is downgraded below `ERROR`, or if `models`
reappears in the list.

**Suppressions.** `@SuppressWarnings("NullAway")` with a one-line reason, and rare — two exist
today (`ContextWindowManager.attemptTruncate`, `SkillLoader.parseSkillFile`), each recording an
invariant the checker cannot follow: a candidate list whose members were already filtered to
non-null, and a path whose parent every caller guarantees.

---

**Measured backlog (for whoever picks up the widening):** with the same options extended,
`compileJava` reports **264** further diagnostics for `tools` and **782** for `services`
(1,046 together). Those are contract annotations to write, not bugs to fix — the bulk are
`@Nullable` parameters and return types that the code already relies on but never declared.
