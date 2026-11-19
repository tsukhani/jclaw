---
name: deploy
description: Ship a release end to end without a question. Unless a green /release-check candidate already tested a release commit over main's HEAD, run /release-check's steps to make and test one; then fast-forward main to it, push it to both git remotes (origin + github), tag the version once its Jenkins build passes, record it in Jira as the Fix Version of the tickets it ships and move them to Done, and, given release, publish it as a GitHub Release.
category: Release
tags: [release, version, git, deploy]
argument-hint: "[patch | minor | major | x.y.z] [release]"
---

**Deploy Workflow**

Ship the release commit a `/release-check` candidate tested: fast-forward `main` to it, push it to both remotes, and tag it once Jenkins has built it green. When no green candidate has tested one over `main`'s HEAD, this command first runs `/release-check`'s steps in this session to make and test it. The commit that goes out is the commit the candidate build and the Mac run tested, by sha (JF-448), so nothing past the release commit is bumped or committed here.

It runs to the end without a question (JF-466): typing `/deploy` is the operator's yes for one release, and `release` their choice of a GitHub Release too. It stops only where nothing can safely ship, and each stop ends the run with step 12's report, naming the step that stopped it; what it had not yet published stays unpublished. Hard rules lists the stops. Use `/usr/bin/git` for every git invocation (per project convention).

**Arguments**

`$ARGUMENTS` holds up to two words, in either order: a version argument (`patch`, `minor`, `major` or `x.y.z`, with `/release-check`'s meaning), and `release`, which also publishes the tagged version as a GitHub Release (step 10). Without `release` nothing is published on GitHub, and an agent never adds it: whether to release is the operator's call, made by typing it (AGENTS.md Policy). Without it, the report says that a GitHub Release of this version run later is judged by the newest Sonar analysis, so one run after the next check is refused over that check's open issues.

The version is the one the release commit carries. A green candidate the preflight finds brings its own: with no version argument it ships as it is, and with one it counts only when it carries that version, so the check runs again for the version asked. When this command runs the check, it passes the version argument on, and none means the next patch. Reject anything else with a clear message; do not guess.

---

**Preflight: bring in the pull requests merged on GitHub**

Contributors' pull requests are merged on GitHub (CONTRIBUTING.md), so `github/main` can hold commits the local `main` lacks. A release without them leaves them out, and the push to `github` is refused as not a fast-forward. Fetch both remotes and compare:

```bash
/usr/bin/git fetch -q origin && /usr/bin/git fetch -q github
/usr/bin/git merge-base --is-ancestor github/main HEAD && echo "nothing new on GitHub"
```

- **Nothing new on GitHub:** go on to the next preflight.
- **New commits:** CONTRIBUTING.md takes a change only under the Developer Certificate of Origin, so every commit a contributor wrote must carry a `Signed-off-by` line. List what comes in, then the commits without one:
  ```bash
  /usr/bin/git log --no-merges --format='%h %an | %s' HEAD..github/main
  /usr/bin/git log --no-merges --invert-grep --grep='^Signed-off-by: ' --format='%h %an | %s' HEAD..github/main
  ```
  If the second prints anything, stop and name those commits: nothing is merged. Otherwise merge with a signed merge commit that names the pull requests it brings in:
  ```bash
  /usr/bin/git merge --no-ff -S -m "Merge GitHub's main: <the pull requests it brings in>" github/main
  ```
  On a conflict, run `/usr/bin/git merge --abort` and stop, with nothing committed, and report the conflicting files. Resolving a contributor's conflict is the user's call, never a release step's. A merge moves `main` past any candidate's parent, so the next preflight finds none and runs the check over the merge, whose notes then name each contributor's change. `/release-check` run alone asks for this preflight first for the same reason.

**Preflight: the release commit a green candidate tested**

`/release-check` made the signed `Release vX.Y.Z` commit over `main`'s HEAD and pushed it to `candidate/v<version>-rc.<N>` on `origin` (JF-471), where Jenkins ran the whole suite and Sonar on it, passing only when Sonar showed 0 open issues. Find it among the candidate branches by its parent:

```bash
/usr/bin/git fetch -q origin
head=$(/usr/bin/git rev-parse HEAD)
/usr/bin/git for-each-ref --format='%(refname:short)' refs/remotes/origin/candidate/ |
  while read -r b; do [ "$(/usr/bin/git rev-parse "$b^" 2>/dev/null)" = "$head" ] && /usr/bin/git log -1 --format='%H %s' "$b"; done
```

Each line is a release commit over this `HEAD`: its full SHA is `<REL>` and its subject names the version. Then find its build among the newest 30 of `JClaw-candidate`, where candidates build (JF-453) (`mcp__jenkins__getJob` with `jobFullName: "JClaw-candidate"` and `tree: "builds[number,result,displayName,description]{0,30}"`): `result` is `SUCCESS` and `description` is `tested <REL>`. Its `displayName`, such as `v1.0.22-rc.2`, is the label the report and the Jira version name (JF-465).

- **One line, with a green build:** that commit is the release. `/usr/bin/git status --porcelain -- $(/usr/bin/git diff --name-only HEAD <REL>)` must print nothing, or the fast-forward fails: those are the files the release changes, `conf/application.conf` and, when the release regenerated it, `THIRD-PARTY-NOTICES.md`. Other uncommitted work stays out of the release, so name it in the report.
- **Several lines:** a check ran more than once over this `HEAD`. Use one with a green build, of the version asked if a version argument was given, and of those the newest build's.
- **None, no green build, or none of the version asked:** run the check here. Run `/release-check` in this session with this command's version argument; its step 15 then removes its worktree and hands its release commit back as `<REL>` instead of offering `/deploy`, since the operator chose by typing this command. It takes about 30 minutes, the candidate build and the Mac run side by side. When the check fails, its step 14 has already dropped the release commit, with `main` and both remotes as they were: stop, with what failed in the report. A commit landing on `main` after a check leaves its release commit over an older `HEAD`, so it is no longer found here and the check runs again.

  **A candidate red only on open Sonar issues does not stop the run.** That is a candidate whose every other stage passed, and a Mac run that passed, but whose Sonar step listed open issues. Run `/sonar` on exactly those issues, in this session, as its "Run by /deploy" section says. It applies its own verdicts without a question: a fix lands on `main` by fast-forward, and a false positive or an accept is marked in Sonar with its reason. It never trades a fix it recommends for a mark. Then run the check again over the new `HEAD` (the version is unspent, so it takes the next rc) and carry on from its result. This happens at most twice in one run. A third candidate red on Sonar stops the run, with every verdict so far in the report, and so does a fix whose tests will not pass. The operator asked for this on 2026-11-19, so that a release never waits on them to accept `/sonar`'s recommendations.

  If Jenkins cannot be reached, the check cannot run: stop. Deploy without a candidate only when the operator's own message asks for it, which covers that release only: make the release commit as `/release-check` steps 4 to 8 do, remove its worktree, and go on with that commit as `<REL>`. Its push build then runs Test and Sonar itself, and a red one spends the version. Without a candidate there are no tested files to publish, so that version can never be a GitHub Release.

**Preflight: every commit either remote would receive is signed, and committed by you**

GitHub's `main` refuses unverified commits and Bitbucket does not, so one of them lets `origin` take the release after the pre-push tests while `github` refuses it, leaving the remotes apart. GitHub verifies a signature only when the committer email is a verified address of the key's own account, so a good local signature (`G`) is not enough. After the fetch above, check the range up to the release commit (`<REL>`, found by the next preflight) before anything moves:

```bash
me=$(/usr/bin/git config user.email)
for r in origin github; do /usr/bin/git log "$r/main..<REL>" --not github/main --format='%G? %ce %h %an | %s'; done | grep -vE "^(G $me|[GU] renovate@abundent\.com) " | sort -u
```

Commits already on `github/main` are left out: GitHub verified them when it took them, contributors' commits and GitHub's own merge commits alike, and this keyring cannot check their keys. Renovate's own commits pass as `U`: a good signature from the Renovate runner's key, which GitHub verifies but this keyring does not trust.

If that prints anything, stop and list it. The usual sources are an AFK factory branch merged without re-signing, and a factory landing from before the landing committed as the operator (`jclaw-factory-agent@localhost` as committer); `.sandcastle/README.md` gives the signed merge. A listed commit already on `origin` can only be re-signed by a force-push, so that is the user's decision.

**Phase 1: Fast-forward `main` to the release commit**

1. Confirm the main checkout is on `main` (`/usr/bin/git rev-parse --abbrev-ref HEAD`); releases go out only from `main`.
2. `/usr/bin/git merge --ff-only <REL>`, then check that `/usr/bin/git rev-parse HEAD` prints `<REL>`. The commit changes `conf/application.conf` and at most `THIRD-PARTY-NOTICES.md`, so the checkout moves by the version line and, when the release regenerated them, the notices. If git refuses because `main` has moved, another session committed since the check: nothing changed, so run the check again over the new `HEAD`, as the candidate preflight does, which makes a new release commit for the same version. Do it once: if `main` moves again before that commit's fast-forward, stop.

**Phase 2: Push the release commit to both remotes**

3. Confirm both remotes exist via `/usr/bin/git remote`. This project ships with two: `origin` (Bitbucket) and `github` (GitHub). If either is missing, stop and tell the user — do not silently push to only one.
4. Push the release commit to `origin` first, by name: `/usr/bin/git push origin <REL>:refs/heads/main`, with the full SHA written out, because the policy guard reads the command before the shell expands a variable. Not `HEAD`: other sessions commit to this checkout, and one landing while this push runs would make `HEAD` a commit that is not the release, which the guard refuses and which would reach one remote only. The pre-push hook finds the green candidate for this very commit and runs only the fast checks and the `bin/` tests, which Jenkins never runs. No tag goes with it: a tag marks a version Jenkins has built green, so it waits for Phase 3. Report the result.
5. Push it to `github` the same way: `/usr/bin/git push github <REL>:refs/heads/main`. `pre-push` remembers the commit it tested, so this push does not run the checks again. Report the result.
6. If either push fails, surface the error verbatim and stop — do **not** retry with force, do not skip hooks, do not rewrite history. If `pre-push` says `HEAD` has moved past the release commit, another session committed on top of it in the moments since the fast-forward, and the hook can vouch only for `HEAD`: nothing went out, and the release commit stays on local `main`, tested but unpushed. Stop and tell the operator: this version is never published, and the next `/release-check`, over the newer `HEAD`, releases the version after it. A failed push on one remote with a successful push on the other is a known-consistent state the user can recover from manually. If GitHub answers `GH006: Protected branch update failed … Commits must have verified signatures`, it lists the unsigned commits: the preflight should have caught them, so report them rather than bypass.
7. **Delete the Renovate branches this release merged**, only after both pushes succeeded. A `renovate/*` branch that the pushed release commit contains is now on Bitbucket's `main` and is dead; Renovate would prune it only at its next Monday run. Delete it now and not earlier: a branch deleted while its bump is still only local is recreated by that Monday run. A branch it does not contain is a pending bump, so leave it. The names go one per line into `xargs`, which needs no word-splitting (zsh, which the Bash tool runs, does none) and no zsh array, whose `${(f)…}` Claude Code's safety check cannot parse and refuses; `-r` skips the push when nothing merged:
    ```bash
    /usr/bin/git fetch -q origin --prune
    /usr/bin/git branch -r --list 'origin/renovate/*' | sed 's|^ *origin/||' |
      while read -r b; do /usr/bin/git merge-base --is-ancestor "origin/$b" "<REL>" && echo "$b"; done |
      xargs -r /usr/bin/git push origin --delete
    ```
    The pre-push hook skips the suite on a delete-only push, so this needs no bypass. Renovate pushes only to Bitbucket, so `github` has no such branches. A failed delete does not undo the release: report it.

    Then delete the `candidate/*` branches this release supersedes, the same way: its own, and any release commit `/release-check` made over a commit this release contains, which can no longer be released. A candidate over a newer commit cannot exist yet, so the test is the candidate's parent:
    ```bash
    /usr/bin/git branch -r --list 'origin/candidate/*' | sed 's|^ *origin/||' |
      while read -r b; do /usr/bin/git merge-base --is-ancestor "origin/$b^" "<REL>" && echo "$b"; done |
      xargs -r /usr/bin/git push origin --delete
    ```

    Then un-keep the green candidate builds this release supersedes: Jenkins keeps each one, with its zips, until a release no longer needs them (JF-449). Only this release's own candidate stays kept, for a GitHub Release of this version: `node bin/candidate-keep.mjs --keep <REL>`. A failed un-keep does not undo the release: report it.
8. **Delete the local branches this release merged, and list the worktrees it finished.** Only after both pushes succeeded. A branch landed outside the factory, such as an `agent/*` branch you merged yourself, stays behind otherwise; the factory deletes only the branches it lands. `git branch -d` refuses a branch that is not merged, so this cannot lose work, and a branch checked out in a worktree is skipped. A worktree is listed, never removed: one that is clean and merged can still belong to another session, mid-run, so removing it is the user's call (`/usr/bin/git worktree remove <path>`, then `/usr/bin/git branch -d <branch>`):
    ```bash
    /usr/bin/git for-each-ref --merged "<REL>" --format='%(refname:short) %(worktreepath)' refs/heads |
      while read -r b wt; do [ "$b" != main ] && [ -z "$wt" ] && echo "$b"; done |
      xargs -r /usr/bin/git branch -d
    /usr/bin/git worktree list --porcelain | while read -r key value; do
      case "$key" in
        worktree) wt=$value ;;
        branch) [ "$value" != refs/heads/main ] && /usr/bin/git merge-base --is-ancestor "$value" "<REL>" &&
          [ -z "$(/usr/bin/git -C "$wt" status --porcelain)" ] && echo "merged and clean: $wt (${value#refs/heads/})" ;;
      esac
    done
    ```
    Named shell variables only, never awk's numbered fields: Claude Code replaces a dollar sign followed by a digit with the command's arguments before the shell sees it.

**Phase 3: Tag the version once Jenkins passes it**

The tag waits for the release commit's own push build: a version whose build fails for a cause in the commit is never tagged, and a RELEASE build cannot run for it.

9. **Find the release commit's build.** The push to `origin` starts the `JClaw` job (a Bitbucket webhook on `main`). Its build is the newest one whose checked-out commit is `<REL>`: `mcp__jenkins__getBuild` with `jobFullName: "JClaw"` and `tree: "number,building,result,actions[lastBuiltRevision[SHA1]]"` returns it as `lastBuiltRevision.SHA1`. Its Setup names the candidate build that tested this commit and skips Test and Sonar, so it takes about five minutes: start a background `sleep 300`, check when it fires, and then every two minutes until `building` is false. A release made without a candidate runs Test and Sonar and takes 20–25 minutes, so wait `sleep 1320` first. If the Jenkins tools are unavailable, stop here and report the tag as pending, with the command from step 10.
10. **On `SUCCESS`, tag `<REL>` and push the tag to both remotes.** The lowercase `-s` makes the tag annotated and signed; the message stays short because the notes live in the commit body:
    ```bash
    /usr/bin/git tag -s "v<NEW_VERSION>" -m "Release v<NEW_VERSION>" "<REL>"
    /usr/bin/git tag -v "v<NEW_VERSION>"        # must print Good "git" signature
    /usr/bin/git push origin "refs/tags/v<NEW_VERSION>"
    /usr/bin/git push github "refs/tags/v<NEW_VERSION>"
    ```
    The pre-push hook skips a push of tags alone when each tagged commit is already on that remote, so these need no bypass. A RELEASE=true build needs this tag. One started earlier, such as RELEASE ticked straight after the push, waits up to 20 minutes in Setup for the tag to reach GitHub, then refuses before building anything and prints these commands for when the deploy session is gone; the Release stage's `gh release create --verify-tag` also refuses a version with no tag rather than minting an unsigned one at `main`'s head. That build skips Test and Sonar, because the green build recorded the commit as tested in its description, so a flaky test cannot fail a release whose commit already passed.

    **Then record the release in Jira** (JF-462), once both tag pushes succeeded, with or without `release`: the version is out, so the tickets it ships are done.
    1. List them: `node bin/jira-release.mjs tickets "$(/usr/bin/git describe --tags --abbrev=0 --match 'v*' <REL>^)" <REL>` prints the `JF-<n>` keys the commit subjects of the range name. A spent, untagged version's commits fall in this range, since they first ship here.
    2. Record the version: `node bin/jira-release.mjs record v<NEW_VERSION> <today, YYYY-MM-DD> "Release v<NEW_VERSION>: tag v<NEW_VERSION> on commit <REL's first 9>, tested by candidate build <its job> #<n> (<its rc label>), push build #<m>" <the keys>`. It creates the Jira version, released and dated, if Jira has none by that name, and adds it to each ticket's Fix Versions. It goes through Jira's REST API with the jira-confluence MCP server's URL and token, because that server's create-version call fails on this Jira.
    3. Read each listed ticket still in Review, with its comments and links (`mcp__jira-confluence__jira_get_issue`), for anything recorded as not yet verified, not yet run, still open or to be confirmed later. Leave out only a check you can confirm is covered: one this release settled, the candidate build, the push build and the Mac run being the evidence it has (a comment calling a check settled names the evidence to read, and is not that evidence), and one an open ticket already names, whether a comment names that ticket or it is linked to this one. For each other check, file a follow-up Task labelled `verification` that names the check and how to verify it, and link it "Relates" to the ticket. A check a comment says needs no follow-up is not left out: the release's checks of that kind go together into one Task labelled `verification`, listing each with its ticket and the reason given, linked "Relates" to each of those tickets, for the operator to accept or turn into work. The agent that did the work writes such a comment as readily as the operator, and Jira shows both under the operator's account, so a reason alone closes no check. A ticket never reaches Done over an open check without its follow-up (2026-11-16: JF-449's GitHub Release path had never run; of 25 such checks on eleven tickets, 4 were settled by the release, 6 already ticketed, 2 open, and 13 waived by the agent that did the work).
    4. `node bin/jira-release.mjs done v<NEW_VERSION> <the keys>` moves each ticket in Review that carries the version to Done, and leaves every other one as it is.

    A spike is not this step's: it goes to Done when its research is complete. A failure here does not undo the release: report it, with what is left to do.

    **With `release`, start the GitHub Release once both tag pushes succeeded:** `mcp__jenkins__triggerBuild` with `jobFullName: "JClaw"` and `parameters: {"RELEASE": true}`, then wait for it as in step 9; it skips Test, Sonar and Package for the tagged commit, and copies the candidate's image rather than building one. Its Release stage refuses while Sonar shows an open issue, and publishes the files and image the candidate build tested, never a rebuild: it refuses a version with no green candidate, or a file that does not match the sum the candidate recorded (JF-449). On `SUCCESS`, the release is at `https://github.com/tsukhani/jclaw/releases/tag/v<NEW_VERSION>`, and its candidate build is no longer needed: `node bin/candidate-keep.mjs --drop <REL>`. That build has already deleted the candidate's zips and its registry image, and `--drop` hands what is left, its log and test results, to Jenkins' retention (JF-465). On any other result, report the failing stage. The tag stands either way, and a later RELEASE build can publish the version. Without `release`, start nothing.
11. **On any other result** (`FAILURE`, `ABORTED`, `UNSTABLE`), create no tag yet, record nothing in Jira, so the release's tickets stay in Review, and report the failing stage (`mcp__jenkins__searchBuildLog`; `mcp__jenkins__getTestResults` when Test ran).
    - **A candidate tested this commit:** the push build ran only stages the candidate passed on the same commit, so look for a cause outside it, and name it: a controller restart (`Resuming build … after Jenkins restart`, then `script returned exit code -1`), a full disk, a lost agent. Once it is named, rebuild (`mcp__jenkins__rebuildBuild`), check that the new build's `lastBuiltRevision.SHA1` is `<REL>`, and wait and tag as in steps 9 and 10. The version is not spent. If no such cause can be named, or the rebuild fails too, stop: the version stays untagged, and the report names the failing stage.
    - **No candidate, or the cause is in the commit:** the version is spent. Its release commit stays on `main` untagged, and the next `/release-check` bumps past it; that release's notes still start from the last published Release, so nothing is lost. Never tag a later commit with this version.

**Phase 4: Report**

12. Summarize in one message: the version, the release commit's hash, the candidate build that tested it with its rc label, and whether this run made it, with which classes ran on the Mac (or the operator's request to go without one), each `/sonar` round an earlier candidate's open issues started, with every verdict, its reason and the fix commit, both push destinations with their reported ref updates, the push build and its result (and any rebuild, with its cause), the tag (or why there is none), the Jira version with the tickets it recorded, those moved to Done and each one `done` left, with its status, the follow-ups filed (the one holding the waived checks among them) and each check it filed none for with the evidence or ticket that covered it, the GitHub Release and its build when `release` was given, the Renovate and candidate branches step 7 deleted and the local branches step 8 deleted, if any, the worktrees step 8 listed, and any uncommitted work the preflight found, which did not ship. Close it with the release notes in full, the release commit's body as GitHub shows it, and their link (the GitHub Release, else the commit on `github.com/tsukhani/jclaw`): they went public with the push, and the operator reads them here first. Example:

    > Released **v0.7.6** as `a1b2c3d` on `main` (signed), the commit candidate build #1373 tested. Jenkins build #1374 passed; tagged `v0.7.6` (signed).
    > - origin (Bitbucket): `<old-sha>..a1b2c3d`, then tag `v0.7.6`
    > - github: `<old-sha>..a1b2c3d`, then tag `v0.7.6`

13. **If the release touched `frontend/` or `app/controllers/`, close the report by naming the post-deploy check** — one line, not a new step to perform:

    > This release touched the frontend. To verify what shipped: `./jclaw.sh restart`, then `/e2e-audit`.

    Only a reminder, deliberately. The e2e suite is **not** a release gate and must not become one: it needs a live instance, so at push time it would exercise whatever build is currently *running* — i.e. the one being replaced — and a stopped app would fail every spec and block the push on a false red. Everything in `./jclaw.sh test` is a pure function of the source tree; the e2e suite is a function of the tree *plus* deployed state, which makes it post-deploy verification rather than merge gating. Restarting first is what makes it meaningful. See JCLAW-1139.


---

**Hard rules**

- Ask nothing between the command and the report (JF-466). The run stops, with step 12's report, only at: GitHub commits without a sign-off, or a contributor's merge conflict; a commit that is unsigned or committed by someone else; uncommitted changes to `conf/application.conf`; a red check or a red Mac run (open Sonar issues alone go to `/sonar` instead, twice at most, as the candidate preflight says); `main` moving a second time; a missing remote or a refused push; a push build red for a cause in the commit or for none that can be named; Jenkins out of reach. What the run had not yet published stays unpublished, and the report says what is left to do.
- Never use `--force`, `--force-with-lease`, `--no-verify`, or any flag that bypasses hooks, signing, or history.
- Never amend, recreate or add to the release commit: what ships is the commit the candidate tested, by sha.
- Never push to `main` with `--force` even if a hook rejects; report the hook failure and let the user decide.
- Nothing uncommitted ships: the fast-forward moves only `conf/application.conf` and the regenerated `THIRD-PARTY-NOTICES.md`, and other work in the checkout stays out of the release.
- If the current branch is not `main`, stop before anything moves and report the branch — releases go out only from `main`.
- Never tag a release commit before its Jenkins build has passed, and never move or delete a tag once it is pushed.
