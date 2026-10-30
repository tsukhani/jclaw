# Nullness — NullAway on the Gradle compile

`org.jspecify:jspecify` annotations (`@Nullable` / `@NonNull` / `@NullMarked`) are checked by
**NullAway**, running as an Error Prone plugin on the Gradle `compileJava` task. Inside the
annotated packages a type with no annotation means *not null*, and NullAway fails the build on
any dereference, return, assignment or argument that contradicts that.

**Where it runs.** On the Gradle `compileJava` task, which `.githooks/pre-push` invokes before
the test suite and which Sonar already depends on. The `play` CLI is a Gradle wrapper whose
`playRun` and `playAutotest` tasks depend on `compileJava`, so a violation fails `play run` and
`play autotest` as well — before Play's own compile starts. What the checker cannot see is that
second compile: Play 1.x recompiles `app/` with ECJ inside the fork for dev-mode reload and for
the test runner, and a javac plugin cannot load there. That split is structural, not a gap to
close — the Gradle compile is the single place a javac plugin can see this codebase, the same
layering Spotless uses.

**What is in scope.** The `NullAway:AnnotatedPackages` option in `build.gradle.kts` lists
`utils`, `llm`, `agents`, `tools`, `services`, `controllers`, `channels`, `jobs`, `slash`, `mcp`
and `memory`. Each carries a `package-info.java` with `@NullMarked`, and so must every
subpackage, since the annotation does not inherit.

**`models` and `com` are the only deliberate exclusions, and both are permanent.** JPA populates
entity fields reflectively after construction, so every non-null column would report as
uninitialised. `com.aspose.words` is a compatibility shim for the WhatsApp-Web library, not
JClaw code.

Getting there took three stories and a sweep. JCLAW-1149 covered the first five. JCLAW-1160 added
`controllers`, `jobs` and `slash`, because that gap was itself the defect: a parameter whose only
null-passing caller lived in one of them was declared non-null and nothing objected, so the
declaration was a lie no gate could catch. One of those, a null printer protocol reaching
`defaultPort()`, had been 500ing the settings page for two months. JCLAW-1161 added
`channels`. A docs sweep then found `mcp` and `memory` still unlisted, and so unchecked: 94
violations between them and their callers in `tools`, `controllers` and `agents`.

**Measure a widening before attempting it, and raise javac's error cap first.** `-Xmaxerrs`
defaults to 100, so an unmodified `./gradlew compileJava` reports exactly 100 and stops — a count
that looks like a total and is not. The truncation is in compilation order, so whole packages can
read as clean: the first measurement for JCLAW-1160 showed `channels` contributing **zero** when
it in fact held **414** of the 581 violations across the four candidate packages. The tell was
that the count sat at exactly 100 across three fix-and-recompile rounds. Measure with an init
script instead:

```kotlin
allprojects { tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xmaxerrs", "5000")) } }
```
```bash
./gradlew compileJava --console=plain -I <path-to-that-script>
```

**What the `channels` widening taught (JCLAW-1161).** In the wire layer a null almost always
means "the provider omitted this field", so annotate for the **provider's documented message
shape**, not for what the current happy path produces. A Telegram `Message` carries no `text`
when it is a photo; a Slack event has no `thread_ts` outside a thread; a Meta Cloud API webhook
makes `messages[]`, `caption` and `context` all conditional. A false `@Nullable` costs one guard;
a false non-null costs a production NPE on the first unusual payload.

**To widen it.** Add the package name to the `AnnotatedPackages` option, add a
`package-info.java` carrying `@NullMarked` to that package *and to each of its subpackages*
(`@NullMarked` is per-package and does not inherit; `AnnotatedPackages` itself is prefix-matched,
so one name covers a whole tree), then run `./gradlew compileJava` and annotate what it reports.
`NullnessGateConformanceTest` fails if a package inside the scope has no `@NullMarked`
package-info, if the checker is downgraded below `ERROR`, if `models` reappears in the list, or
if a top-level `app/` package holding Java sources is neither listed nor one of the two
exclusions — so a new package cannot land silently unchecked.

**Writing the annotations.** Two spellings catch people out, because `@Nullable` is a
`TYPE_USE` annotation. On a qualified nested type it goes on the simple name
(`SkillLoader.@Nullable FrontmatterSplit`, not `@Nullable SkillLoader.FrontmatterSplit`), and on
an array it goes after the element type (`float @Nullable [] vector` annotates the array;
`@Nullable float[]` annotates the elements and leaves the array non-null).

**Result carriers.** The commonest false positive is a record that means "either a value or an
error" — NullAway cannot see that a null `error()` implies a non-null payload. The convention
here is an accessor that asserts the invariant and names it in one line, rather than a
suppression: `FsPaths.TargetPath.resolvedTarget()`, `FsSupport.LoadedFile.resolvedContent()`,
`DeliverySpec.resolvedTool()`, `ScrapeObservation.resolvedError()` and more than twenty siblings all
follow that shape. Prefer it — a `resolvedX()` throws where a suppression would return null.

**Suppressions.** `@SuppressWarnings("NullAway")` with a one-line reason, and rare — one exists
today (`SkillLoader.parseSkillFile`), recording an invariant the checker cannot follow: a path
whose parent every caller guarantees. Note that NullAway does not analyse a suppressed method's
body at all, so a nullness contract on one is unverified by construction.
