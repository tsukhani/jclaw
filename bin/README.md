# bin/

Developer tooling that is neither Java nor frontend, so it lives outside both build
trees. Nothing here ships in a `play dist` tarball.

| Entry | What it is |
| --- | --- |
| `diagnostics.mjs` | Build and test failures as one JSON array — see below |
| `diagnostics.test.mjs` | Its parser tests: `node --test bin/diagnostics.test.mjs` |
| `coverage-blend.mjs` | SonarQube-style blended coverage from `jacoco.xml` + `lcov.info` |
| `jacocoagent.jar`, `jacococli.jar` | JaCoCo, invoked by the coverage workflow |

## diagnostics.mjs

```bash
./jclaw.sh diagnostics                        # compile errors, on stdout
./jclaw.sh diagnostics --tests                # also run play autotest (~7 min)
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
