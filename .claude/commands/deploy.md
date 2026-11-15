---
name: deploy
description: Fast-forward main to the release commit /release-check made and its candidate build tested, push it to both git remotes (origin + github), tag the version once its Jenkins build passes, and, given release, publish it as a GitHub Release.
category: Release
tags: [release, version, git, deploy]
argument-hint: "[release]"
---

**Deploy Workflow**

Ship the release commit `/release-check` made and tested: fast-forward `main` to it, push it to both remotes, and tag it once Jenkins has built it green. The commit that goes out is the commit the candidate build and the Mac run tested, by sha (JF-448), so nothing is bumped or committed here. Use `/usr/bin/git` for every git invocation (per project convention).

**Arguments**

`$ARGUMENTS` may be `release`, which also publishes the tagged version as a GitHub Release (step 10). Without it nothing is released. Whether to release is the operator's call: offer both forms, and never add `release` they did not give (AGENTS.md Policy). Say, too, that a release is best chosen now: Sonar keeps one result for the project, so a RELEASE build run after the next `/release-check` is judged by that check's analysis of newer code, and its open issues refuse this version.

The version is the one the release commit carries, chosen when `/release-check` ran. A version argument (`patch`, `minor`, `major` or `x.y.z`, with `/release-check`'s meaning) is used only on the path without a candidate (preflight 2); given alongside a tested release commit, it must name that commit's version, or stop. Reject anything else with a clear message; do not guess.

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
  On a conflict, run `/usr/bin/git merge --abort` and stop, with nothing committed, and report the conflicting files. Resolving a contributor's conflict is the user's call, never a release step's. A merge moves `main` past the release commit's parent, so stop after it and run `/release-check` again: its notes then name each contributor's change. `/release-check` runs this preflight on its own for the same reason.

**Preflight: the release commit a green candidate tested**

`/release-check` made the signed `Release vX.Y.Z` commit over `main`'s HEAD and pushed it to `candidate/<its first 12>` on `origin`, where Jenkins ran the whole suite and Sonar on it, passing only when Sonar showed 0 open issues. Find it among the candidate branches by its parent:

```bash
/usr/bin/git fetch -q origin
head=$(/usr/bin/git rev-parse HEAD)
/usr/bin/git for-each-ref --format='%(refname:short)' refs/remotes/origin/candidate/ |
  while read -r b; do [ "$(/usr/bin/git rev-parse "$b^" 2>/dev/null)" = "$head" ] && /usr/bin/git log -1 --format='%H %s' "$b"; done
```

Each line is a release commit over this `HEAD`: its full SHA is `<REL>` and its subject names the version. Then find its build among the newest 30 of `JClaw` (`mcp__jenkins__getJob` with `tree: "builds[number,result,displayName,description]{0,30}"`): `displayName` ends `candidate <REL's first 12>`, `result` is `SUCCESS`, and `description` is `tested <REL>`.

- **One line, with a green build:** that commit is the release. `/usr/bin/git status --porcelain -- conf/application.conf` must print nothing, or the fast-forward fails. Other uncommitted work stays out of the release, so name it in the report.
- **Several lines:** a check ran more than once over this `HEAD`. Use the one with a green build; if more than one has, ask which.
- **None, or no green build:** run `/release-check` first, and go on only once it passes. A commit landing on `main` after a check leaves its release commit over an older `HEAD`, so it is no longer found here and needs a new check. Deploy without a candidate only on the user's explicit yes, which covers this release only: make the release commit as `/release-check` steps 4 to 8 do, remove its worktree, and go on with that commit as `<REL>`. Its push build then runs Test and Sonar itself, and a red one spends the version.

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
2. `/usr/bin/git merge --ff-only <REL>`, then check that `/usr/bin/git rev-parse HEAD` prints `<REL>`. The commit changes only `conf/application.conf`, so the checkout moves by that one line. If git refuses because `main` has moved, another session committed since the check: nothing changed, so run `/release-check` again; it makes a new release commit for the same version.

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

    **With `release`, start the GitHub Release once both tag pushes succeeded:** `mcp__jenkins__triggerBuild` with `jobFullName: "JClaw"` and `parameters: {"RELEASE": true}`, then wait for it as in step 9; it skips Test and Sonar for the tagged commit, and took 7 minutes for v1.0.18 (#1415). Its Release stage refuses while Sonar shows an open issue. On `SUCCESS`, the release is at `https://github.com/tsukhani/jclaw/releases/tag/v<NEW_VERSION>`; on any other result, report the failing stage. The tag stands either way, and a later RELEASE build can publish the version. Without `release`, start nothing.
11. **On any other result** (`FAILURE`, `ABORTED`, `UNSTABLE`), create no tag yet, and report the failing stage (`mcp__jenkins__searchBuildLog`; `mcp__jenkins__getTestResults` when Test ran).
    - **A candidate tested this commit:** the push build ran only stages the candidate passed on the same commit, so look for a cause outside it, and name it: a controller restart (`Resuming build … after Jenkins restart`, then `script returned exit code -1`), a full disk, a lost agent. Once it is named, rebuild (`mcp__jenkins__rebuildBuild`), check that the new build's `lastBuiltRevision.SHA1` is `<REL>`, and wait and tag as in steps 9 and 10. The version is not spent.
    - **No candidate, or the cause is in the commit:** the version is spent. Its release commit stays on `main` untagged, and the next `/release-check` bumps past it; that release's notes still start from the last published Release, so nothing is lost. Never tag a later commit with this version.

**Phase 4: Report**

12. Summarize in one message: the version, the release commit's hash, the candidate build that tested it (or the user's yes to go without one), both push destinations with their reported ref updates, the push build and its result (and any rebuild, with its cause), the tag (or why there is none), the GitHub Release and its build when `release` was given, the Renovate and candidate branches step 7 deleted and the local branches step 8 deleted, if any, the worktrees step 8 listed, and any uncommitted work the preflight found, which did not ship. Example:

    > Released **v0.7.6** as `a1b2c3d` on `main` (signed), the commit candidate build #1373 tested. Jenkins build #1374 passed; tagged `v0.7.6` (signed).
    > - origin (Bitbucket): `<old-sha>..a1b2c3d`, then tag `v0.7.6`
    > - github: `<old-sha>..a1b2c3d`, then tag `v0.7.6`

13. **If the release touched `frontend/` or `app/controllers/`, close the report by naming the post-deploy check** — one line, not a new step to perform:

    > This release touched the frontend. To verify what shipped: `./jclaw.sh restart`, then `/e2e-audit`.

    Only a reminder, deliberately. The e2e suite is **not** a release gate and must not become one: it needs a live instance, so at push time it would exercise whatever build is currently *running* — i.e. the one being replaced — and a stopped app would fail every spec and block the push on a false red. Everything in `./jclaw.sh test` is a pure function of the source tree; the e2e suite is a function of the tree *plus* deployed state, which makes it post-deploy verification rather than merge gating. Restarting first is what makes it meaningful. See JCLAW-1139.


---

**Hard rules**

- Never use `--force`, `--force-with-lease`, `--no-verify`, or any flag that bypasses hooks, signing, or history.
- Never amend, recreate or add to the release commit: what ships is the commit the candidate tested, by sha.
- Never push to `main` with `--force` even if a hook rejects; report the hook failure and let the user decide.
- Nothing uncommitted ships: the fast-forward moves only `conf/application.conf`, and other work in the checkout stays out of the release.
- If the current branch is not `main`, stop before anything moves and report the branch — releases go out only from `main`.
- Never tag a release commit before its Jenkins build has passed, and never move or delete a tag once it is pushed.
