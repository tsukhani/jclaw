# bin/

Developer tooling that is neither Java nor frontend, so it lives outside both build
trees. Nothing here ships in a `play dist` tarball.

| Entry | What it is |
| --- | --- |
| `diagnostics.mjs` | Build and test failures as one JSON array — see below |
| `diagnostics.test.mjs` | Its parser tests: `node --test bin/diagnostics.test.mjs` |
| `select-tests.mjs` | The backend test classes a change can reach, for the pre-push and factory gates — see below |
| `select-tests.test.mjs` | Its tests: `node --test bin/select-tests.test.mjs` |
| `policy-guard.mjs` | Enforces AGENTS.md's Policy block for Claude Code, Codex and `.githooks/pre-push` — see below |
| `policy-guard.test.mjs` | Its tests: `node --test bin/policy-guard.test.mjs` |
| `coverage-blend.mjs` | SonarQube-style blended coverage from `jacoco.xml` + `lcov.info` |
| `llms-full.mjs` | Writes the gitignored `docs/user-guide/llms-full.txt`, the guide's `llms.txt` followed by every page it lists: `node bin/llms-full.mjs`. Tested in `frontend/test/llms-txt.test.ts` |
| `jacocoagent.jar`, `jacococli.jar` | JaCoCo, invoked by the coverage workflow |

## diagnostics.mjs

```bash
./jclaw.sh diagnostics                        # compile errors, on stdout
./jclaw.sh diagnostics --tests                # also run play autotest (~10 min)
./jclaw.sh diagnostics --tests --out d.json   # write the document to a file
./jclaw.sh diagnostics --tests --select main  # run only the classes select-tests.mjs picks for main...HEAD
```

`./jclaw.sh diagnostics` is the wrapper; `node bin/diagnostics.mjs` is the same thing
without the toolchain checks. Both emit one JSON document: an array of records.

The command parses, it does not add builds. It runs `./gradlew compileTestJava` — with
`--tests`, `play autotest` as well — and reads javac's output plus the xunit reports in
`test-result/`. Parsing ~750 reports costs a few milliseconds against a compile measured
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
| `file` | string \| null | Repo-relative path (arch: may be bare) |
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

## select-tests.mjs

```bash
node bin/select-tests.mjs                          # origin/main...HEAD, the -Ptests list on stdout
node bin/select-tests.mjs --base main --json       # the selection with a reason for each class
./gradlew playAutotest -Ptests="$(node bin/select-tests.mjs)"
```

It needs compiled classes (`./gradlew compileJava compileTestJava`): it reads the class graph with `jdeps`, which
takes about a second. It prints one of three things:

- **`NONE`**: nothing in the change reaches a backend test. Examples are frontend, docs, `.sandcastle/` and root
  `*.md` files, or an `application.conf` edit to the version line or comments only.
- **`ALL`**: the change touches something no class graph can follow: `conf/` other than `application.conf`, the
  build files, `.play-version`, `lib/`, or a non-Java file under `app/`.
- **A comma-separated class list**, made of:
  - every test class within `--depth` hops (default 2) above a changed `app/` or `test/` class;
  - for a changed controller, the tests that call its `conf/routes` paths over HTTP;
  - for any other changed file, the tests whose source names it;
  - the rule checks: `ArchitectureTest`, `CapabilityRulesTest`, `WallClockDisciplineTest` and every
    `*ConformanceTest`.

A deleted class selects nothing by itself, because the compile catches whatever still referred to it. The full
suite is Jenkins' job: anything further than the selected depth is caught there.

`--rules` prints the rule checks alone, for `./jclaw.sh check-arch`, and needs no compiled classes.

Exit 0 printed a selection; exit 2 means there were no compiled classes, or git or jdeps failed.

## policy-guard.mjs

```bash
node bin/policy-guard.mjs hook               # a PreToolUse event on stdin
node bin/policy-guard.mjs pre-push REMOTE    # git's pre-push ref lines on stdin
```

Two layers share one set of rules. `hook` is the command layer: `.claude/settings.json` and `.codex/hooks.json` run it
before each tool call, and it prints a deny decision or nothing. Codex runs a project hook only after the operator
trusts it once with `/hooks`, and again after it changes. `pre-push` is the ref layer: it sees what the command layer
cannot, the refs a push really carries, including names fed through `xargs` and a push from a script. It exits 1 to
refuse, and runs ahead of `JCLAW_SKIP_TESTS`, which skips the tests and never the guard.

The command layer refuses:

- **`git push`**, except the forms below;
- **skipping hooks**: `--no-verify`, `git commit -n`, any change to `core.hooksPath`, and `JCLAW_SKIP_TESTS`;
- **`git stash` with untracked files**;
- **stopping the instance**: the `jclaw.sh` subcommands the Policy block names, and `kill` aimed at the pid in
  `server.pid` or at ports 9000 or 9443, unless the command carries `JCLAW_OPERATOR_APPROVED=1`. The agent asks in chat
  and adds the prefix on the operator's yes; nothing stops it adding the prefix unasked, so this catches forgetting;
- **an edit under the seven upstream-imported skills**, through Claude Code's edit tools.

Both layers pass only `git push origin|github HEAD` from the main checkout on `main` when HEAD's subject is exactly
`Release vX.Y.Z`, a version tag pushed alone, and the deletion of `renovate/*` branches on `origin`.

It reads flags, not text: a commit message or heredoc that mentions a refused flag passes. It judges HEAD when the call
starts, so `git commit … && git push` in one call is refused, and it fails closed on a `cd` it cannot resolve. It stops drift and accidents,
not an agent that writes a refused command into a script and runs it, though the ref layer still sees that script's push
unless it skips hooks. `pkill` and `killall`, a `sed` into a skill and Codex's `apply_patch` edits are not checked. If
the guard itself fails, the hook refuses only calls that mention what it guards, and the ref layer refuses the push.
