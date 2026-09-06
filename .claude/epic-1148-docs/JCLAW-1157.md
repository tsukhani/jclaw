# AGENTS.md fragment — JCLAW-1157

**Target section:** `## Development Commands`, as a new `### Diagnostics` subsection placed
immediately after the existing `### Evals` subsection and before `### Running Both Together`.

---

### Diagnostics

```bash
./jclaw.sh diagnostics                        # compile errors, as JSON on stdout
./jclaw.sh diagnostics --tests                # also run play autotest (~7 min)
./jclaw.sh diagnostics --tests --out d.json   # write the document to a file
```

The same facts `./jclaw.sh test` prints for a human, as one JSON array for an agent
repair loop: an array of `{kind, file, line, message, fix?}` records, where `kind` is
`compile` (a javac error), `test` (a failure in a `test-result/TEST-*.xml` report) or
`arch` (one violated site from an ArchUnit rule). `bin/README.md` is the schema
contract; `bin/diagnostics.mjs` is the implementation and `node --test
bin/diagnostics.test.mjs` its parser tests.

It parses, it does not add builds. It runs `./gradlew compileTestJava` — with `--tests`,
`play autotest` as well — and reads javac's output plus the xunit reports already on
disk. Parsing the 514 reports costs milliseconds against a compile measured in seconds
and a suite measured in minutes.

Three contracts make the output safe to believe. A clean tree prints `[]`, never nothing:
empty output means the command did not run. Without `--tests` the reports in
`test-result/` are not read at all, because they belong to whenever the suite last ran —
and with `--tests` they are deleted before the suite runs, so a class deleted since the
last run cannot be read back as still failing. And exit 2 is reserved for the harness
failing rather than the build: a compile that broke without javac printing a diagnostic,
or a suite that failed with no report recording a failure, exits 2 with the underlying
output on stderr instead of a green-looking `[]`. Exit 0 is a clean tree, exit 1 is
diagnostics reported.

One asymmetry is deliberate: a tree that does not compile skips the suite and reports
only `compile` records. javac gates the test run, so the reports on disk would describe
the previous build. This also means a single run never mixes `compile` records with
`test`/`arch` ones — not a limitation of the parser, but of what a broken tree can
physically produce.
