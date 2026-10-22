# bin/

Developer tooling that is neither Java nor frontend, so it lives outside both build
trees. Nothing here ships in a `play dist` tarball.

| Entry | What it is |
| --- | --- |
| `diagnostics.mjs` | Build and test failures as one JSON array — see below |
| `diagnostics.test.mjs` | Its parser tests: `node --test bin/diagnostics.test.mjs` |
| `mutation.mjs` | Mutation testing of what changed since a git ref — see below |
| `mutation.test.mjs` | Its report and guard tests: `node --test bin/mutation.test.mjs` |
| `mutation.init.gradle.kts` | Resolves PIT for `mutation.mjs` without adding it to the build |
| `comment-review.mjs` | Advisory scoring of added comments against AGENTS.md section 8 — see below |
| `comment-review.test.mjs` | Its diff-parsing tests: `node --test bin/comment-review.test.mjs` |
| `coverage-blend.mjs` | SonarQube-style blended coverage from `jacoco.xml` + `lcov.info` |
| `jacocoagent.jar`, `jacococli.jar` | JaCoCo, invoked by the coverage workflow |

## diagnostics.mjs

```bash
./jclaw.sh diagnostics                        # compile errors, on stdout
./jclaw.sh diagnostics --tests                # also run play autotest (~10 min)
./jclaw.sh diagnostics --tests --out d.json   # write the document to a file
```

`./jclaw.sh diagnostics` is the wrapper; `node bin/diagnostics.mjs` is the same thing
without the toolchain checks. Both emit one JSON document: an array of records.

The command parses, it does not add builds. It runs `./gradlew compileTestJava` — with
`--tests`, `play autotest` as well — and reads javac's output plus the xunit reports in
`test-result/`. Parsing 514 reports costs a few milliseconds against a compile measured
in seconds and a suite measured in minutes.

### Record schema

```json
{
  "kind": "compile",
  "file": "app/utils/HttpFactories.java",
  "line": 42,
  "message": "cannot find symbol — symbol: method newClient() — location: class HttpFactories",
  "fix": "'HttpFactories.general()'"
}
```

| Field | Type | Meaning |
| --- | --- | --- |
| `kind` | `"compile"` \| `"test"` \| `"arch"` | Which of the three sources reported it |
| `file` | string \| null | Repo-relative path where it can be fixed |
| `line` | number \| null | 1-based line in that file |
| `message` | string | What is wrong, self-contained |
| `fix` | string | Present only when the compiler suggested one |

`file` and `line` are null only when the source could not name a location — an ArchUnit
site with no line number, a failure ArchUnit or play1 reported without source info. A
consumer must handle null rather than assume a location.

### Kinds

- **`compile`** — a javac error, from the Gradle compile of `app/` and `test/`. Warnings
  are not reported: a tree that compiles must produce an empty array, and that contract
  is worth nothing if a green build fails it. `fix` carries Error Prone's
  `Did you mean …?` suggestion when there is one.
- **`test`** — a `<failure>` or `<error>` in a `test-result/TEST-*.xml` report. `file` and
  `line` point at the assertion that threw, not at the suite's declaration. `message` is
  prefixed with `Suite.testName()` so a record identifies the test to re-run on its own.
- **`arch`** — one violated site from an ArchUnit rule. A single failing ArchUnit
  assertion names every offending class, so it expands into one record per site rather
  than one record per test; `file` is the offending source, not `ArchitectureTest.java`,
  and `message` carries the rule (including its `because` clause) alongside the site.

### Contracts worth relying on

- **A clean tree prints `[]`, never nothing.** Empty output means the command did not
  run; an empty array means it ran and found nothing.
- **Without `--tests`, `test-result/` is not read at all.** Those reports belong to
  whenever the suite last ran; presenting them as current findings is exactly the
  plausible nonsense this command exists to avoid. `--tests` deletes them before running
  the suite so a class deleted since the last run cannot be reported as still failing.
- **A tree that does not compile skips the suite** and reports only compile records —
  the reports on disk would describe the previous build.
- **Human output goes to stderr.** With `--out`, stdout stays empty.

### Exit codes

| Code | Meaning |
| --- | --- |
| 0 | Nothing to report; the document is `[]` |
| 1 | At least one diagnostic; the document lists them |
| 2 | The harness itself failed — no document was produced |

Exit 2 covers the case a green-looking empty array would otherwise hide: a build that
failed without javac printing a diagnostic (a Gradle configuration error, a missing
framework), or a suite that failed with no report recording a failure. The underlying
output is echoed to stderr so the cause is visible.

## mutation.mjs

```bash
./jclaw.sh mutation HEAD~1   # what the last commit and the working tree changed
./jclaw.sh mutation main     # everything on this branch
```

Line coverage says which lines ran; a mutation score says how many small deliberate breaks the
tests caught. The command plants them in what changed since the ref (committed, staged or
untracked) and prints a score per tool. A score never fails it: reviewers and the factory read
the reports, and nothing gates on them (JF-17).

| Changed | Tool | Tests run against it | Report |
| --- | --- | --- | --- |
| `app/**.java` | PIT | the unit test classes whose source names the class | `build/reports/mutation/pit/index.html` |
| `frontend/**.{ts,vue}` | Stryker, command runner | the `frontend/test` files that name the file's module | `build/reports/mutation/stryker/<file>/mutation.html` |

### What it does about the traps a mutation run falls into

- **Stale tests.** `play autotest` compiles `test/` with Play's own compiler and never writes
  `build/classes/java/test`, where PIT reads tests. PIT then runs the old tests and repeats the
  old score. The command runs `./gradlew compileTestJava` first and refuses a run whose selected
  test classes are still older than their sources.
- **Booting Play.** The fork's `PlayJUnitExtension` boots Play in every test JVM, and the fork has
  no switch to skip it. A no-op class of the same name goes first on PIT's classpath only, so a
  test that needs no running app runs under PIT. One that does (the database, `Fixtures`) fails
  without it and PIT skips it (`--skipFailingTests`); a `FunctionalTest` is never selected.
  Booting Play under PIT is no way round it: Play compiles `app/` with its own compiler and
  loads that copy, so the boot fails beside the compiled classes on PIT's classpath, and a
  mutant PIT plants in that classpath copy would never reach the code the test runs (tried
  on `ConversationServiceTest`, 2026-10-22). A class only such tests cover reports "detected
  none" and exits 2.
- **A harness that measures nothing.** Stryker's vitest runner reports every mutant Survived
  without running a test under vitest 5, a 0% that reads like a result. The command uses the
  command runner, one `vitest run` of the related test files per mutant, and treats survivors
  with `testsCompleted` 0 as a harness fault, as it does a run that detected no mutant at all.
- **The shared checkout.** Stryker writes instrumented sources in place, so it works in a
  temporary copy of `frontend/` (about 7 s to install from the store), with vitest told to
  skip the backup copies of the test files Stryker keeps in `.stryker-tmp`.

Every run measures every mutant of the changed classes again. PIT 1.30 moved incremental
analysis, which skips mutants whose class and tests did not change, into the commercial
Arcmutate history plugin; open-source PIT refuses `--historyInputLocation` without it.

### Exit codes

| Code | Meaning |
| --- | --- |
| 0 | Every harness that ran measured something; scores are printed |
| 2 | A harness measured nothing (no mutant detected, survivors that ran no test, no mutants generated, stale test classes) or could not run |

A changed file no test names is reported as skipped, not as a fault: there is nothing to run.

## comment-review.mjs

```bash
./jclaw.sh comment-review HEAD~1   # the comments the last commit and the working tree add
```

AGENTS.md section 8 calls redundant comments the commonest defect in agent-written code, and no
gate can judge one: whether a comment restates its code is a reading, not a pattern. The command
finds each comment a diff adds (`//`, `/* */`, `#` and trailing forms, consecutive lines joined),
sends them with their hunks and the section's text to `claude -p` with every tool disabled, and
prints a verdict per comment: `keep`, `cut` (the delete test removes it) or `shorten`, with the part
of the rule behind it and, for `shorten`, a shorter version.

It reports and never gates (JF-17). The section is read from AGENTS.md on each run, so the rubric
cannot drift from the rule. Generated files, `archunit_store/` and `test/approved/` are skipped. A
diff too large for one prompt (150,000 characters) is reviewed from its first comments, and the
command says how many it left out. Fifty comments take about four minutes.

| Code | Meaning |
| --- | --- |
| 0 | Reviewed, whatever the verdicts |
| 2 | The review could not run: no `claude` on PATH, a failed call, or an answer without verdicts |
