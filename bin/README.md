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
| `candidate-keep.mjs` | Un-keeps the green `/release-check` candidate builds no release will publish — see below |
| `candidate-keep.test.mjs` | Its tests: `node --test bin/candidate-keep.test.mjs` |
| `candidate-registry.mjs` | Deletes from the Jenkins host's candidate registry the images of builds no longer kept — see below |
| `candidate-registry.test.mjs` | Its tests: `node --test bin/candidate-registry.test.mjs` |
| `candidate-vouch.mjs` | Whether a green `/release-check` candidate build tested a commit, for `.githooks/pre-push` — see below |
| `candidate-vouch.test.mjs` | Its tests, the hook's candidate skip among them: `node --test bin/candidate-vouch.test.mjs` |
| `candidate-builds.mjs` | The green and the kept candidate builds, read from the build records on the Jenkins host, for the Jenkinsfile — see below |
| `candidate-builds.test.mjs` | Its tests: `node --test bin/candidate-builds.test.mjs` |
| `candidate-job.mjs` | Creates or updates the `JClaw-candidate` Jenkins job from the `JClaw` job's config — see below |
| `candidate-job.test.mjs` | Its tests: `node --test bin/candidate-job.test.mjs` |
| `jira-release.mjs` | Records a release in Jira for `/deploy`: the tickets it ships, the release as their Fix Version, Done — see below |
| `jira-release.test.mjs` | Its tests: `node --test bin/jira-release.test.mjs` |
| `playwright-chromium.mjs` | Whether the Chromium build the Java Playwright pins is installed, for `./jclaw.sh test`'s live-browser gate — see below |
| `playwright-chromium.test.mjs` | Its tests, the gate's lines among them: `node --test bin/playwright-chromium.test.mjs` |
| `ghcr-prune.mjs` | Deletes the GHCR package versions no listed Release needs, in a release build's cleanup — see below |
| `ghcr-prune.test.mjs` | Its tests: `node --test bin/ghcr-prune.test.mjs` |
| `eslint.config.mjs` | ESLint's recommended rules for these scripts, which `./jclaw.sh check` runs; it loads ESLint from `frontend/`'s dependencies |
| `coverage-blend.mjs` | SonarQube-style blended coverage from `jacoco.xml` + `lcov.info` |
| `llms-full.mjs` | Writes the gitignored `docs/user-guide/llms-full.txt`, the guide's `llms.txt` followed by every page it lists: `node bin/llms-full.mjs`. Tested in `frontend/test/llms-txt.test.ts` |
| `jacocoagent.jar`, `jacococli.jar` | JaCoCo, invoked by the coverage workflow |
| `sign-release.sh` | Signs `SHA256SUMS` and `IMAGE_DIGEST` in the Release stage |
| `upgrade-rehearsal.sh` | A candidate's upgrade rehearsal: the last release, seeded, upgraded to the candidate's bundle — see below |
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

## playwright-chromium.mjs

```bash
node bin/playwright-chromium.mjs   # Playwright 1.64.0's Chromium 1248 is installed in ~/Library/Caches/ms-playwright
```

`./jclaw.sh test` turns the live-browser tests on only when this prints 0 (JF-452). The pinned revisions come from
`driver/package/browsers.json` in the Playwright `driver` jar of the version `gradle.lockfile` resolves, read from the
Gradle cache (`GRADLE_USER_HOME`, else `~/.gradle`) with `unzip`. The build is installed when `chromium`,
`chromium_headless_shell` and `ffmpeg` each hold `INSTALLATION_COMPLETE` at those revisions under
`PLAYWRIGHT_BROWSERS_PATH`, else Playwright's macOS or Linux cache: `BrowserSetup.chromiumInstalledAt`'s rule, by
which the browser tool decides whether to install. Another revision does not count, since only the pinned one
launches.

It prints one line. Exit 0 means the build is installed. Exit 1 means it is not, and the line names the cache's
complete headless-shell revisions, if any; `./gradlew playwrightInstall` installs it. Exit 2 means it cannot tell:
no lockfile, no driver line, the jar not yet in the Gradle cache, `unzip` failing, a `browsers.json` that pins no
Chromium, or `PLAYWRIGHT_BROWSERS_PATH=0`. Anything but 0 leaves the live-browser tests skipped.

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

## candidate-keep.mjs

```bash
node bin/candidate-keep.mjs --keep <full sha>   # un-keep every kept candidate build but this commit's
node bin/candidate-keep.mjs --drop <full sha>   # un-keep this commit's candidate build alone
```

A green candidate build keeps itself (`currentBuild.keepLog`), because a GitHub Release of its version publishes its
archived zips and its image, and the job otherwise keeps only the last 5 builds' files (JF-449). `/deploy` runs
`--keep` with the release commit once both pushes succeeded, since every other candidate is then superseded, and
`--drop` once the release's GitHub Release is published; `/release-check` runs `--drop` for a green candidate whose
check failed on the Mac. A candidate is a build whose display name ends `candidate <first 12 of sha>`, in the
`JClaw-candidate` job or, for one built before JF-453 gave candidates that job, in `JClaw`.

It reads Jenkins' URL and header as `candidate-vouch.mjs` does, and posts each `toggleLogKeep` with the crumb and
session that `candidate-vouch.mjs`'s `postHeaders` gets. Jenkins only toggles, so it toggles only builds it has just
listed as kept. Exit 0 named each build it un-kept, or said there was none; exit 2 means a malformed argument, a
config without the server, a refusal (the builds named before it stay un-kept) or a Jenkins that is down.

## candidate-registry.mjs

```bash
node bin/candidate-registry.mjs prune --keep <short sha>[,<short sha>…]   # as the Cleanup Old Releases stage runs it
```

A candidate's image waits in `jclaw-candidate-registry`, a `registry:3` container on the Jenkins host bound to
`127.0.0.1:5000`, so nothing off the host can pull an unreleased image and it uses no GHCR storage. The Release stage
copies it to GHCR by digest. The Cleanup stage names the kept candidate builds and this deletes every other tag's
manifest, by the digest the registry gives for it; the registry's `garbage-collect` then frees the layers nothing
tags. `JCLAW_CANDIDATE_REGISTRY` points it at another registry. Exit 0 deleted each doomed image or found none,
including a registry that never took a candidate; exit 1 means a malformed argument, or a request the registry
refused or never answered, and stops the run.

## candidate-vouch.mjs

```bash
node bin/candidate-vouch.mjs <full sha>   # prints the vouching build, such as #1418
```

The pre-push hook asks it about the commit `/deploy` pushes to `main`, which is the release commit `/release-check`
made and its candidate build tested (JF-444, JF-448). A finished, successful build of the `JClaw-candidate` job vouches when its
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

## candidate-builds.mjs

```bash
node bin/candidate-builds.mjs green <full sha>   # prints "JClaw-candidate 3": the green candidate build of the commit
node bin/candidate-builds.mjs kept               # prints the kept candidates' first 12 of sha, comma-separated
```

Candidates build in `JClaw-candidate` (JF-453), and the Jenkinsfile's sandbox cannot walk another job's builds, so
the `JClaw` job's builds read these records on the host they share. Setup asks `green` whether a candidate tested the
pushed commit, and skips Test and Sonar if one did. The Release stage asks it for the build whose archived zips it
publishes. Cleanup asks `kept` which candidates' images the registry keeps. It reads each
`$JENKINS_HOME/jobs/<job>/builds/<n>/build.xml`, newest first, in `JClaw-candidate` and then in `JClaw`, which built the
candidates before JF-453. It takes a record's own `result`, `description`, `displayName` and `keepLog`, never the
descriptions its parameters nest, and judges `green` by `candidate-vouch.mjs`'s rule.

Exit 0 printed the answer; `green` exits 1 when no green candidate tested the commit. Exit 2 means a malformed
argument, no `JENKINS_HOME`, or no job's builds to list. `kept` then answers nothing rather than an empty list, which
would prune every candidate image.

## candidate-job.mjs

```bash
node bin/candidate-job.mjs           # says what it would create or update, and writes nothing
node bin/candidate-job.mjs --apply   # creates JClaw-candidate, or updates it when it exists
```

`JClaw-candidate` is the `JClaw` job's pipeline, with four changes:
- no Bitbucket webhook trigger;
- its definition checks out `refs/heads/${CANDIDATE_BRANCH}`;
- a `CANDIDATE_BRANCH` parameter, declared before the first build expands it;
- its own description.

So a candidate build runs the release commit's own Jenkinsfile, with no replay. The Bitbucket plugin posts the
build's status on that commit, under `refs/heads/candidate/<first 12>`. The plugin posts on the commit its SCM checked
out. `JClaw`'s webhook builds only when polling, against the checkouts of its last successful build, finds a change.
A candidate checked out in `JClaw` would therefore put its result on `main`'s head, and a candidate checked out there
without `main` would leave `/deploy`'s push with no build (JF-453). Rerun with `--apply` after a change to `JClaw`'s
SCM or parameters. It refuses a config of any other shape rather than guess. It reads Jenkins' URL and header as
`candidate-vouch.mjs` does. Exit 0 said what it did or would do; exit 2 means a malformed argument, a config without
the server, a refusal, or a `JClaw` config of another shape.

## jira-release.mjs

```bash
node bin/jira-release.mjs tickets v1.0.20 <REL>                    # JF-453 JF-462: the keys the range's subjects name
node bin/jira-release.mjs record v1.0.21 2026-11-17 "<description>" JF-453 JF-462
node bin/jira-release.mjs done v1.0.21 JF-453 JF-462               # each in Review with that Fix Version goes to Done
```

`/deploy` runs it once a release is tagged (JF-462), so Jira says which release shipped each ticket and nothing that
shipped waits in Review. `tickets` prints the `JF-<n>` keys the commit subjects of a range name, each once, in number
order. It reads subjects alone, since a ticket's commits put its key in the subject, while a body can name any
ticket. `record` creates the version, released and dated, when Jira has none by that name. It then adds it to each
ticket's Fix Versions, with an add rather than a set, so a ticket keeps the release it first shipped in. `done` moves
each ticket in Review that carries the version to Done, by whichever transition Jira offers to Done. It leaves any
other ticket as it is, a key passed by mistake included.

It calls Jira's REST API with the `JIRA_URL` and `JIRA_PERSONAL_TOKEN` of the `jira-confluence` MCP server in
`~/.claude.json` (`JCLAW_CLAUDE_JSON` names another file), so it holds no secret of its own, and it never prints the
token. That server's own create-version call fails on this Jira. Exit 0 did everything asked. Exit 2 means a
malformed argument, a range git cannot read, a config without the server, or a refusal, which stops the run after
the lines for what it did.

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

## upgrade-rehearsal.sh

```bash
bin/upgrade-rehearsal.sh dist/jclaw-bundle.zip   # as the candidate build's Upgrade Rehearsal stage runs it
```

The test database is created fresh for every run (`%test.jpa.ddl=create`), so the suite never applies a schema
change to populated rows, which is where a new NOT NULL column without `@ColumnDefault` fails. Every candidate
build therefore upgrades a populated install, the way users upgrade (JF-450):

1. **The last published release.** It downloads that GitHub Release's `jclaw-bundle.zip` with `gh` and checks it
   against the release's `SHA256SUMS`.
2. **Its install, booted.** It unzips the bundle into a scratch directory and starts it on the first free port from
   19400.
3. **Invented data, never a copy of the live instance's.** It sets an admin password, made up for the run and never
   printed, then creates:
   - an agent, a prompt and a config key, through the release's own API;
   - three conversations, each opened with `/new` and holding two chat turns. A stand-in OpenAI-compatible provider,
     a Python server the script starts on loopback, answers each turn with fixed text. So the turns run through the
     release's own chat code, and nothing sweeps them away afterwards, as the load test's cleanup would.

   What the API reads back of all of it is the snapshot.
4. **The release's own upgrade.** That release's `jclaw.sh upgrade` installs the candidate's bundle, through
   `JCLAW_UPGRADE_ASSET_URL`. It stops the install, backs up the database, swaps the tree and boots the candidate,
   rolling back if the candidate does not answer. The installed `jclaw.sh` must then be the candidate's, so the swap
   is proven even when the two carry the same version.
5. **The checks.** The candidate must report its version on `/api/status`. Its boot must log no failed schema
   change: Hibernate logs one and boots on regardless. The snapshot must read back unchanged.

A failure names the step and the evidence: the log line of a failed schema change, else the failed boot's first
error, or the difference in what read back. The scratch install, its processes and the stand-in provider go when the
script exits. `JCLAW_REHEARSAL_REPO` names another repository to take the release from.

It took 36 s on the Jenkins host on 2026-11-16, upgrading v1.0.19 to candidate #1424's bundle: 25 s to download, boot
and seed, and 11 s to upgrade. A bundle with a planted `@Column(nullable = false)` and no default failed it, naming
`Error executing DDL "alter table if exists agent add column … not null" … NULL not allowed`.

| Code | Meaning |
| --- | --- |
| 0 | The release upgraded to the candidate and read back everything it stored |
| 1 | A step failed, named with its evidence |
| 2 | Not given a bundle that exists |

