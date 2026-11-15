---
name: release-check
description: Before /deploy spends a version number, push main's HEAD to a candidate/<sha> branch and have Jenkins run the whole suite and Sonar on it as the version it will become, passing only when Sonar shows 0 open issues.
category: Release
tags: [release, jenkins, sonar, deploy]
argument-hint: "[empty | patch | minor | major | x.y.z]"
---

**Release Check Workflow**

`/deploy` spends a version number the moment it pushes: a release commit whose Jenkins build fails is never tagged, and the next `/deploy` bumps past it (v1.0.15 went this way on a Sonar reliability rating no local check could see). This command runs that build first, on the commit `/deploy` would release, while a failure costs nothing. Jenkins runs Build, Test and Sonar on a `candidate/<sha>` branch, waits for the quality gate, then fails the build unless Sonar shows **0 open issues** (OPEN or CONFIRMED), the operator's bar for a GitHub Release. A green candidate lets the release commit's own build skip Test and Sonar, since its tree differs only in the version line. Use `/usr/bin/git` for every git invocation (per project convention).

**Arguments**

`$ARGUMENTS` takes the version argument `/deploy` takes, without `release`, and must be the one the `/deploy` after it is given: the candidate is analysed as the version that release becomes, so Sonar's new-code period lines up with it. Empty means the next patch.

---

**Preflights** (stop at the first that fails, and say which)

1. **The main checkout, on `main`, with a clean tree.** `/deploy` stages everything (`git add -A`), so an uncommitted file would ship in the release while the candidate never saw it: `/usr/bin/git status --porcelain` must print nothing. If another session's work is in the tree, stop and say so.
2. **Nothing new on GitHub.** `/usr/bin/git fetch -q origin && /usr/bin/git fetch -q github`, then `/usr/bin/git merge-base --is-ancestor github/main HEAD`. If GitHub holds commits `main` lacks, `/deploy`'s merge would put a commit on top of the candidate that no candidate tested: run `/deploy`'s GitHub-merge preflight on its own first, then this command.
3. **Every commit is signed and committed by you.** Run `/deploy`'s signature preflight as written; a commit it lists fails the release at GitHub whatever the candidate says.
4. **The gates Jenkins never runs.** Lint, Stylelint, the typecheck and the `bin/` tests run nowhere on Jenkins, so without this step the release commit's push is their first run, after `/deploy` has bumped the version (JF-445). Run both, and stop at a failure, naming it, with nothing pushed:
   ```bash
   ./jclaw.sh check
   (unset $(/usr/bin/git rev-parse --local-env-vars) && node --test bin/*.test.mjs)
   ```
5. **The version.** Read `application.version` from `conf/application.conf` and compute the new version exactly as `/deploy` Phase 1 would. Nothing is written.

**Run the candidate**

6. Record the commit: `SHA=$(/usr/bin/git rev-parse HEAD)`, and the branch: `candidate/<the first 12 characters of SHA>`.
7. Push it to `origin` with the full SHA written out, because the policy guard reads the command before the shell expands a variable:
   ```bash
   /usr/bin/git push origin <SHA>:refs/heads/candidate/<SHA's first 12>
   ```
   The guard passes this form only: a commit on `main`, from the main checkout, to `origin`, on a branch named for that commit, created once and never moved. The pre-push hook skips the local tests, which the Jenkins build runs. A rerun for the same commit is a no-op push.
8. Start the build. A Jenkins build runs the Jenkinsfile on Bitbucket's `main`, so first ask whether the candidate changes it: `/usr/bin/git diff --quiet origin/main <SHA> -- Jenkinsfile`.
   - **Unchanged** (exit 0): `mcp__jenkins__triggerBuild` with `jobFullName: "JClaw"` and `parameters: {"CANDIDATE": "<SHA>", "CANDIDATE_VERSION": "<new version>"}`, then follow the queue item (`mcp__jenkins__getQueueItem`) to its build number.
   - **Changed:** `node bin/replay-candidate.mjs <SHA> <new version>`. It replays a recent build with the candidate's own Jenkinsfile, so the change runs here rather than first in the release's build, and touches no parameter defaults. Find the build among the newest by its name.

   Either way the build names itself `#<n> candidate <SHA's first 12>`.
9. **Meanwhile, run on this Mac the tests the candidate cannot** (JF-447): the live-browser classes, which only `./jclaw.sh test` turns on, and the macOS sides of tests that branch on the OS, since Jenkins runs on Linux. It is the run the old pre-push made, the backend classes that `bin/select-tests.mjs` picks from the range since `origin/main`, and the Mac would otherwise sit idle while the candidate builds. Wait until `jcmd -l | grep playAutotest | grep -v -e -Ptests=` and `pgrep -fl '[.]githooks/pre-push'` print nothing, then run it in the background, redirected to a file and never piped:
   ```bash
   ./gradlew --quiet compileTestJava
   sel=$(node bin/select-tests.mjs --base origin/main --head HEAD)
   case "$sel" in NONE) scope=none ;; ALL) scope=all ;; *) scope=$sel ;; esac
   JCLAW_TEST_BACKEND="$scope" JCLAW_TEST_FRONTEND=0 ./jclaw.sh test > logs/release-check-local.log 2>&1
   ```
   Its summary says whether Playwright's Chromium was found, and so whether the live-browser classes ran. The frontend run is the candidate's.
10. Wait for the candidate without polling: it takes about 25 minutes (Build, Test, Sonar and Package, which a release commit's build would otherwise be the first to run). Start a background `sleep 1200`, then check every five minutes with `mcp__jenkins__getBuild` (`tree: "number,building,result,displayName,description"`) until `building` is false.

**Report**

11. **When both pass**, the candidate ending `SUCCESS` (its description reads `tested <SHA>`) and the local run exiting 0: say the candidate is green for v<new version> and which classes ran on the Mac, and offer the operator both next steps: `/deploy <the same argument>`, which pushes and tags it, or `/deploy <the same argument> release`, which also publishes it as a GitHub Release. The choice is theirs (AGENTS.md Policy). Either holds only while `main` has not moved: a commit landing after this check needs a new one.
12. **When either fails:** say which. For the local run, its summary names each failed check's `logs/test-*.log`. For the candidate, report the failing stage. For tests, `mcp__jenkins__getTestResults`. For Sonar, the build log lists each open issue under the stage (`mcp__jenkins__searchBuildLog` for `open Sonar issue`). Fix them, or accept or mark them in Sonar with `/sonar`'s verdicts, then run this command again: no version was spent. Delete the failed candidate's branch, since nothing will release it: `/usr/bin/git push origin --delete candidate/<SHA's first 12>`.

---

**Notes**

- A triggered build runs the Jenkinsfile on Bitbucket's `main`, which is why step 8 replays a candidate that changes it. `bin/README.md` has the replay's contract.
- A candidate whose Sonar analysis does not finish fails, where a push build would only turn UNSTABLE: an unverified candidate must not let the release skip Sonar.
- `/deploy` deletes the candidate branches its release contains, after both of its pushes succeed.
- Never push anything else from this command, never to `github`, and never move a candidate branch once it exists.
