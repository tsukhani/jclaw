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
| `candidate-vouch.mjs` | Whether a green `/release-check` candidate build tested a commit, for `.githooks/pre-push` — see below |
| `candidate-vouch.test.mjs` | Its tests, the hook's candidate skip among them: `node --test bin/candidate-vouch.test.mjs` |
| `replay-candidate.mjs` | Starts a `/release-check` candidate build with the candidate's own Jenkinsfile — see below |
| `replay-candidate.test.mjs` | Its tests: `node --test bin/replay-candidate.test.mjs` |
| `ghcr-prune.mjs` | Deletes the GHCR package versions no listed Release needs, in a release build's cleanup — see below |
| `ghcr-prune.test.mjs` | Its tests: `node --test bin/ghcr-prune.test.mjs` |
| `eslint.config.mjs` | ESLint's recommended rules for these scripts, which `./jclaw.sh check` runs; it loads ESLint from `frontend/`'s dependencies |
| `coverage-blend.mjs` | SonarQube-style blended coverage from `jacoco.xml` + `lcov.info` |
| `llms-full.mjs` | Writes the gitignored `docs/user-guide/llms-full.txt`, the guide's `llms.txt` followed by every page it lists: `node bin/llms-full.mjs`. Tested in `frontend/test/llms-txt.test.ts` |
| `jacocoagent.jar`, `jacococli.jar` | JaCoCo, invoked by the coverage workflow |
| `sign-release.sh` | Signs `SHA256SUMS` and `IMAGE_DIGEST` in the Release stage |
| `verify-debug-stripped.sh` | Fails the Package stage when a shipped zip's classes keep a LocalVariableTable |

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
`test-result/`. Parsing ~870 reports costs a few milliseconds against a compile measured
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

## ghcr-prune.mjs

```bash
node bin/ghcr-prune.mjs          # name what it would delete, and delete nothing
node bin/ghcr-prune.mjs --apply  # delete it, as the Cleanup Old Releases stage does
```

Images follow the Releases. It keeps the index of every listed Release and of `latest`, with their platform manifests, and
deletes every other package version of `ghcr.io/tsukhani/jclaw`, so each listed `IMAGE_DIGEST` stays pullable. The stage
prunes Releases first, so this sees the ones that survive.

A package version is one manifest, not one image: a multi-arch image is an index plus an untagged version per platform.
Keeping five versions kept two images, which left three of five Releases with a signed digest that returned 404.

It reads everything and decides before it deletes anything, so a failed lookup deletes nothing. With no Release listed it
refuses, because nothing then says what to keep. A Release whose image is already gone is named and skipped.

| Code | Meaning |
| --- | --- |
| 0 | Finished, or nothing to delete |
| 1 | Refused or stopped, and the reason is printed. A failed delete stops the run, and earlier deletions stand |

## candidate-vouch.mjs

```bash
node bin/candidate-vouch.mjs <full sha>   # prints the vouching build, such as #1418
```

The pre-push hook asks it about the commit `/deploy` pushes to `main`, which is the release commit `/release-check`
made and its candidate build tested (JF-444, JF-448). A finished, successful build of the `JClaw` job vouches when its
description reads `tested <sha>` and its display name ends `candidate <first 12 of sha>`, the record a candidate build
writes. A push build of the same commit does not count, since it may itself have skipped its tests on a candidate's
word, and neither does a candidate of the commit's parent: the shipped commit is the one tested. When a build
vouches, the hook runs only what Jenkins never runs, `./jclaw.sh check` (with the frontend gates when a frontend
input changed) and the `bin/` tests (when a `bin/` file changed), and skips the backend classes and the frontend
tests the candidate ran.

It asks Jenkins with the `--header` and URL of the `jenkins` MCP server in `~/.claude.json` (`JCLAW_CLAUDE_JSON`
names another file), so it needs no secret of its own, and gives up after 10 seconds. Exit 0 printed the build; exit
1 means no green candidate build tested the commit; exit 2 means it could not ask, for a sha that is not a full one,
a config without the server, a refusal or a Jenkins that is down. Anything but 0 runs every test as before.

## replay-candidate.mjs

```bash
node bin/replay-candidate.mjs <full sha>   # replayed #1422 as candidate 0123456789ab for v1.0.20
```

A candidate build that `/release-check` triggers runs the Jenkinsfile on Bitbucket's `main`, so a candidate that
changes the Jenkinsfile would first run the change in the release's own build (JF-445). This replays the newest
finished build that neither releases nor publishes the dev container, with the candidate's Jenkinsfile as its script.
In that script every read of `params.CANDIDATE` is replaced by the release commit's sha, so candidate mode runs
whatever parameters the replayed build carries, and the job's parameter definitions and defaults stay as they are.
The build is analysed as the version the commit carries, which the printed line names. It refuses a Jenkinsfile with
no candidate mode, or one that reads another candidate parameter, such as the `CANDIDATE_VERSION` of a Jenkinsfile
from before JF-448.

It reads Jenkins' URL and header as `candidate-vouch.mjs` does, and sends the replay with the crumb and session
Jenkins issues. Exit 0 printed the replayed build; exit 2 means nothing was replayed: a malformed sha, no Jenkinsfile
or no `application.version` at the commit, no build fit to replay, a config without the server, or a refusal.

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
- **stopping the instance**: the `jclaw.sh` subcommands the Policy block names, run directly or through the `jclaw`
  shim (`jclaw.cmd` on Windows), which reaches the test install too; `kill` aimed at the pid in `server.pid` or at
  ports 9000 or 9443; and `pkill` or `killall` that names `server.pid` or those ports, whose pattern matches `java` or,
  under `pkill -f`, the server's command line, or that gives no pattern but a user, group, parent or tty to match.
  `pkill -v` is judged inverted. Patterns are judged as JavaScript regexes, so one that does not compile, uses a POSIX
  class such as `[[:alpha:]]`, or still holds a shell expansion or substitution is refused. A `pkill -f` refused only
  through the server's command line gets its own message, which points at `pgrep -fl` first: that command line names
  every library JClaw loads, so a cleanup such as `pkill -f playwright` reaches it by accident. A signal-0 probe passes,
  and all of these pass when the command carries `JCLAW_OPERATOR_APPROVED=1`. The agent asks in chat and adds the
  prefix on the operator's yes; nothing stops it adding the prefix unasked, so this catches forgetting.

Both layers pass only a commit on `main` whose subject is exactly `Release vX.Y.Z`, pushed from the main checkout on
`main` to `origin` or `github` as `HEAD` or by its full SHA, a version tag pushed alone, the deletion of `renovate/*`
and `candidate/*` branches on `origin`, and `/release-check`'s candidate: its `Release vX.Y.Z` commit, whose one
parent is on `main` and which changes only `conf/application.conf`, pushed from the main checkout on `main` by its full
SHA to `origin`'s `candidate/<a prefix of that SHA>`, which the ref layer lets be created but never moved (JF-448). `pre-push` runs no tests for a push of candidates alone, since their Jenkins build runs the whole suite. The ref layer also requires a fast-forward of the remote's `main`, so a forced push cannot roll it
back. `/deploy` pushes by SHA, so a commit another session lands between its two pushes goes out in neither. `pre-push`
tests the checkout, so a named commit that is not HEAD passes only if it was already tested.

It reads flags, not text: a commit message or heredoc that mentions a refused flag passes. It judges HEAD when the call
starts, so `git commit … && git push` in one call is refused, and it fails closed on a `cd` it cannot resolve. It stops drift and accidents,
not an agent that writes a refused command into a script and runs it, though the ref layer still sees that script's push
unless it skips hooks. Not checked: a `sed` into a skill, Codex's `apply_patch` edits, `kill $(pgrep …)`, `kill` of
pid -1 (every process), a `jclaw.sh` a shell runs after options of its own (`bash -x jclaw.sh stop`) or through
`source`, and a `pkill` option after the pattern, which GNU's option ordering accepts on Linux. If the guard itself
fails, the hook refuses only calls that mention what it guards, and the ref layer refuses the push.
