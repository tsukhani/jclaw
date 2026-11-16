---
name: release-check
description: Before /deploy releases anything, make the next signed release commit (the version bump and its release notes) over main's HEAD without putting it on main, push it to a candidate/<sha> branch, and have Jenkins run the whole suite and Sonar on that very commit, passing only when Sonar shows 0 open issues, while this Mac runs the tests Jenkins cannot.
category: Release
tags: [release, jenkins, sonar, deploy]
argument-hint: "[empty | patch | minor | major | x.y.z]"
---

**Release Check Workflow**

This command makes the release commit `/deploy` will ship and tests that exact commit while a failure still costs nothing. The commit is the signed `Release vX.Y.Z`, with the version bump and the release notes, made over `main`'s HEAD in a detached worktree, so `main` does not move. Jenkins runs Build, Test, Sonar and Package on it from a `candidate/<sha>` branch, waits for the quality gate, and fails the build unless Sonar shows **0 open issues** (OPEN or CONFIRMED), the operator's bar for a GitHub Release. It then installs the last published release, seeds it with invented data, upgrades it to the commit's bundle and reads the data back (JF-450), and builds the commit's Docker image into a registry on the Jenkins host and boots it. A green candidate build is kept, because its zips and its image are exactly what a GitHub Release of this version publishes (JF-449). Meanwhile this Mac runs, on the same commit, the tests Jenkins cannot. `/deploy` then only fast-forwards `main` to the commit, pushes it and tags it (JF-448). A failed check leaves `main` and both remotes as they were, and the next check uses the same version number. Use `/usr/bin/git` for every git invocation (per project convention).

**Arguments**

`$ARGUMENTS` names the version, with `application.version` in `conf/application.conf` as the base:

- *(empty)* → bump the **patch**: `1.0.0` → `1.0.1`. Each JF sprint is named after the minor release it ships (`v1.0` holds 1.0.1, 1.0.2, …; `v1.1` the 1.1.x releases), so the first release of a new sprint is checked with `minor`, and every later one in it with no argument.
- `patch` / `minor` / `major` → bump that segment; lower segments reset to `0`. Examples: `minor` on `1.0.5` → `1.1.0`; `major` on `1.4.2` → `2.0.0`.
- An explicit version string matching `\d+\.\d+\.\d+` → set the version to exactly that. Any leading `v` is stripped.

Reject anything else with a clear message; do not guess. `/deploy` reads the version from the commit, so it needs no argument of its own.

---

**Preflights** (stop at the first that fails, and say which)

1. **The main checkout, on `main`.** The release commit is `HEAD` plus the bump, so uncommitted work stays out of the release: list what `/usr/bin/git status --porcelain` prints in the report, so nobody expects it to ship. Stop only if `conf/application.conf` is among it, since `/deploy`'s fast-forward would then fail.
2. **Nothing new on GitHub.** `/usr/bin/git fetch -q origin && /usr/bin/git fetch -q github`, then `/usr/bin/git merge-base --is-ancestor github/main HEAD`. If GitHub holds commits `main` lacks, the release must include them: run `/deploy`'s GitHub-merge preflight on its own first, then this command.
3. **Every commit is signed and committed by you.** Run `/deploy`'s signature preflight as written; a commit it lists fails the release at GitHub whatever the candidate says.
4. **The version.** Compute the new version from `application.version` and the argument. If the line is missing, stop: this command runs only against a project that uses that key.

**Write the release notes**

5. **The range the notes cover.** The commit body *is* the GitHub release notes: `Jenkinsfile`'s RELEASE stage copies it verbatim (minus the `Co-Authored-By:` trailer) into `gh release create`. So the audience is a reader on the Releases page, and the span they see is **from the last published Release**, not from the last tag. Those differ: `/deploy` tags every version whose build passes, while a Release is cut only when the operator asks for one, so several versions routinely sit between them.

    ```bash
    # Tag of the newest published Release — the reader's actual starting point.
    PREV=$(gh release view --repo tsukhani/jclaw --json tagName --jq .tagName 2>/dev/null)
    # Fall back to the previous tag if the repo has no Releases yet.
    [ -n "$PREV" ] || PREV=$(/usr/bin/git describe --tags --abbrev=0 HEAD 2>/dev/null)
    /usr/bin/git log "$PREV"..HEAD --no-merges --format='%h %s%n%b'
    ```

    Read that log properly. Subjects alone are not enough, because a subject can describe an approach a later commit reverses. Where two commits in the range supersede each other, chronicle **where it landed**, not the journey.

6. **Compose the message:**
    - Title line: `Release vNEW_VERSION` (e.g. `Release v0.7.6`).
    - Body: **reader-facing release notes covering the whole range from step 5**, in the shape the play1 fork uses (see `/opt/play1/.claude/commands/deploy.md` and any recent `gh release view --repo tsukhani/play1`):
      - `### Section` headings grouping the work by what a reader cares about — the feature area, `Fixes`, `Dependencies`. Not one heading per commit.
      - Under each, `- **Bold lead clause naming the change.**` followed by prose: what it does for the user, and the why or consequence where that isn't obvious. Name the ticket in parentheses when there is one.
      - Dependency bumps collapse to a single compact bullet list; don't give each its own paragraph.
      - A trailing `Note:` paragraph for anything genuinely surprising a reader would otherwise trip over.
      - Write for someone who has not read the diff and does not know the internals. No file paths, no function names, no "refactored X" — say what changed for them.
    - If nothing shipped but the bump, write `Version bump only; no code changes since v<OLD_VERSION>.`
    - Keep it publishable: this lands on the **public** GitHub mirror, so no credentials, customer specifics, or unreleased commercial plans (AGENTS.md, Commit Messages Are Public).
    - Trailer: the `Co-Authored-By:` line your standing instructions specify, which names the model actually authoring the commit. **Do not hardcode a model version in this file.** It goes stale at every model bump, and a wrong trailer breaks nothing, so the drift would survive. If your instructions specify no trailer, omit it rather than guessing a model name.

    `/deploy` ships this commit unchanged, so the notes are written once, here. A failed check discards them with the commit, and the next check writes them again over its own range.

**Make the release commit**

7. **A detached worktree at `HEAD`**, so neither `main` nor the shared checkout moves: `/usr/bin/git worktree add --detach .claude/worktrees/release/v<NEW_VERSION> HEAD`, and record `BASE=$(/usr/bin/git rev-parse HEAD)`. Its `post-checkout` hook seeds `certs/.env` with a test port of its own. Run steps 8 to 9 and 12 inside it.
8. **Bump and commit.** In the worktree's `conf/application.conf`, replace the `application.version=` line with the Edit tool, so exactly one line changes. Then create the **signed** commit with a HEREDOC so line breaks survive the shell:
    ```bash
    /usr/bin/git commit -S -am "$(cat <<'EOF'
    Release v<NEW_VERSION>

    <the release notes from step 6>

    <Co-Authored-By trailer per step 6>
    EOF
    )"
    ```
    Record `REL=$(/usr/bin/git rev-parse HEAD)`. Check its shape before anything goes out: `/usr/bin/git diff --name-only <BASE> <REL>` prints only `conf/application.conf`, and `/usr/bin/git log -1 --format='%G? %ce' <REL>` prints `G` and your committer email.
9. **The gates Jenkins never runs, on this commit.** Lint, Stylelint, the typecheck and the `bin/` tests run nowhere on Jenkins (JF-445). In the worktree, install the frontend from its lockfile (the pnpm store already holds it), then run both, and on a failure go to step 14:
   ```bash
   (cd frontend && pnpm install --frozen-lockfile)
   ./jclaw.sh check
   (unset $(/usr/bin/git rev-parse --local-env-vars) && node --test bin/*.test.mjs)
   ```

**Run the candidate**

10. Push the release commit from the **main checkout** with the full SHA written out, because the policy guard reads the command before the shell expands a variable:
    ```bash
    /usr/bin/git push origin <REL>:refs/heads/candidate/<REL's first 12>
    ```
    The guard passes this form only: a `Release vX.Y.Z` commit whose one parent is on `main` and which changes only `conf/application.conf`, pushed from the main checkout on `main` to `origin`, on a branch named for that commit, created once and never moved. The pre-push hook skips the local tests, which the candidate build and step 12 run. This branch is what keeps the commit once the worktree is gone.
11. **Start the build** in the `JClaw-candidate` job: `mcp__jenkins__triggerBuild` with `jobFullName: "JClaw-candidate"` and `parameters: {"CANDIDATE": "<REL>", "CANDIDATE_BRANCH": "candidate/<REL's first 12>"}`, then follow the queue item (`mcp__jenkins__getQueueItem`) to its build number. That job checks out the candidate branch, so the build runs the release commit's own Jenkinsfile and its Bitbucket status lands on that commit (JF-453). It names itself `v<NEW_VERSION>-rc.<N>`, N one past that version's earlier candidates, failed ones included (JF-465), and is analysed as the version the commit carries: the rc is a label, never in the artifact. If Jenkins has no such job, `node bin/candidate-job.mjs --apply` creates it from the `JClaw` job.
12. **Meanwhile, run on this Mac the tests the candidate cannot** (JF-447): the live-browser classes, which only `./jclaw.sh test` turns on, and the macOS sides of tests that branch on the OS, since Jenkins runs on Linux. It is the backend classes `bin/select-tests.mjs` picks from the range since `origin/main`, run in the worktree, so the Mac tests the release commit itself. Wait until `jcmd -l | grep playAutotest | grep -v -e -Ptests=` and `pgrep -fl '[.]githooks/pre-push'` print nothing, then run it in the background, redirected to a file and never piped:
    ```bash
    ./gradlew --quiet compileTestJava
    sel=$(node bin/select-tests.mjs --base origin/main --head HEAD)
    case "$sel" in NONE) scope=none ;; ALL) scope=all ;; *) scope=$sel ;; esac
    JCLAW_TEST_BACKEND="$scope" JCLAW_TEST_FRONTEND=0 ./jclaw.sh test > logs/release-check-local.log 2>&1
    ```
    Its first line says whether the Chromium build the Java Playwright pins is installed, and so whether the live-browser classes ran (JF-452). When it names that build as not installed, run `./gradlew playwrightInstall` and this step again before reporting the Mac run. The frontend run is the candidate's.
13. Wait for the candidate without polling: it takes about 30 minutes (Build, Test, Sonar, Package and the image). Start a background `sleep 1500`, then check every five minutes with `mcp__jenkins__getBuild` (`jobFullName: "JClaw-candidate"`, `tree: "number,building,result,displayName,description"`) until `building` is false.

**Report**

14. **When either fails:** say which, and what failed. For the local gates of step 9, the failing command's output. For the local run, its summary names each failed check's `logs/test-*.log`. For the candidate, the failing stage; for tests, `mcp__jenkins__getTestResults`; for Sonar, the build log lists each open issue under the stage (`mcp__jenkins__searchBuildLog` for `open Sonar issue`). Then drop the release commit: delete its candidate branch if step 10 pushed it (`/usr/bin/git push origin --delete candidate/<REL's first 12>`); if the candidate itself went green, un-keep its build so its files fall back under the job's retention (`node bin/candidate-keep.mjs --drop <REL>`); and remove the worktree from the main checkout (`/usr/bin/git worktree remove .claude/worktrees/release/v<NEW_VERSION>`, never `--force`; if it refuses, report what is left in it). `main` and both remotes are as they were, so fix the failure, or accept or mark the Sonar issues with `/sonar`'s verdicts, and run this command again with the same argument: it makes a new release commit for the same version.
15. **When both pass**, the candidate ending `SUCCESS` (its description reads `tested <REL>`) and the local run exiting 0: remove the worktree as in step 14, keeping the candidate branch. Say the candidate is green for v<NEW_VERSION> as its rc label and `<REL's first 12>`, which classes ran on the Mac, and any uncommitted work preflight 1 listed, and offer the operator both next steps: `/deploy`, which fast-forwards `main` to this commit, pushes it and tags it, or `/deploy release`, which also publishes it as a GitHub Release. The choice is theirs (AGENTS.md Policy). Either holds only while `main` stays at `<BASE>`: a commit landing on it first needs a new check, which makes a new release commit for the same version.

---

**Notes**

- Candidates build in `JClaw-candidate`, never in `JClaw`: the Bitbucket plugin posts a build's status on the commit its SCM checked out, and `JClaw`'s webhook starts a build only when polling against its last successful build's checkouts finds a change, so those must stay `main`'s (JF-453). `JClaw`'s Jenkinsfile refuses a candidate. `bin/README.md` § candidate-job.mjs has the job's shape.
- A candidate whose Sonar analysis does not finish fails, where a push build would only turn UNSTABLE: an unverified candidate must not let the release skip Sonar.
- The candidate's image waits in a registry on the Jenkins host, bound to `127.0.0.1`, so nothing off that host can pull an unreleased image, and it costs no GHCR storage. The Cleanup stage of a GitHub Release deletes the images of candidate builds no longer kept.
- `/deploy` deletes the candidate branches its release supersedes, after both of its pushes succeed.
- Never push anything else from this command, never to `github`, and never move a candidate branch once it exists.
