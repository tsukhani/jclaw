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

**What is in scope.** The `NullAway:AnnotatedPackages` option in `build.gradle.kts` lists
`utils`, `llm`, `agents`, `tools` and `services`; every package and subpackage under those roots
carries a `package-info.java` with `@NullMarked`. `models` is excluded deliberately: JPA
populates entity fields reflectively after construction, so every non-null column would report as
uninitialised. `controllers`, `channels` and `jobs` are simply not annotated yet.

**To widen it.** Add the package name to the `AnnotatedPackages` option, add a
`package-info.java` carrying `@NullMarked` to that package *and to each of its subpackages*
(`@NullMarked` is per-package and does not inherit; `AnnotatedPackages` itself is prefix-matched,
so one name covers a whole tree), then run `./gradlew compileJava` and annotate what it reports.
`NullnessGateConformanceTest` fails if a package inside the scope has no `@NullMarked`
package-info, if the checker is downgraded below `ERROR`, or if `models` reappears in the list.

**Writing the annotations.** Two spellings catch people out, because `@Nullable` is a
`TYPE_USE` annotation. On a qualified nested type it goes on the simple name
(`SkillLoader.@Nullable FrontmatterSplit`, not `@Nullable SkillLoader.FrontmatterSplit`), and on
an array it goes after the element type (`float @Nullable [] vector` annotates the array;
`@Nullable float[]` annotates the elements and leaves the array non-null).

**Result carriers.** The commonest false positive is a record that means "either a value or an
error" — NullAway cannot see that a null `error()` implies a non-null payload. The convention
here is an accessor that asserts the invariant and names it in one line, rather than a
suppression: `FsPaths.TargetPath.resolvedTarget()`, `FsSupport.LoadedFile.resolvedContent()`,
`DeliverySpec.resolvedTool()`, `ScrapeObservation.resolvedError()` and a dozen siblings all
follow that shape. Prefer it — a `resolvedX()` throws where a suppression would return null.

**Suppressions.** `@SuppressWarnings("NullAway")` with a one-line reason, and rare — two exist
today (`ContextWindowManager.attemptTruncate`, `SkillLoader.parseSkillFile`), each recording an
invariant the checker cannot follow: a candidate list whose members were already filtered to
non-null, and a path whose parent every caller guarantees. Note that NullAway does not analyse a
suppressed method's body at all, so a nullness contract on one is unverified by construction.

---
