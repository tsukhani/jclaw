---
name: deploy
description: Bump application.version in conf/application.conf, commit all changes, and push to both git remotes (origin + github).
category: Release
tags: [release, version, git, deploy]
argument-hint: "[empty | patch | minor | major | x.y.z]"
---

**Deploy Workflow**

Bump the project version, commit every pending change alongside it, and push the result to both remotes. Use `/usr/bin/git` for every git invocation (per project convention).

**Arguments**

`$ARGUMENTS` may be one of:

- *(empty)* → bump the **patch** segment (default). `0.7.5` → `0.7.6`.
- `patch` / `minor` / `major` → bump that segment; lower segments reset to `0`. Examples: `minor` on `0.7.5` → `0.8.0`; `major` on `0.7.5` → `1.0.0`.
- An explicit version string matching `\d+\.\d+\.\d+` (e.g. `1.0.0-rc.1`) → set the version to exactly that. Any leading `v` is stripped.

Reject anything else with a clear message; do not guess.

---

**Phase 1: Bump the version**

1. Read `conf/application.conf` and find the single line that starts with `application.version=`. If missing, stop and tell the user — this command only runs against a project that already uses that key.
2. Parse the current value (right-hand side of `=`). Split on `.` to get the `MAJOR.MINOR.PATCH` triple; preserve any trailing `-suffix` pre-release tag by dropping it before bumping (explicit-version arg is the only way to set one back).
3. Compute the new version per the argument rules above.
4. Replace the line in place via the Edit tool — do **not** rewrite the file. The change must touch exactly one line.
5. Record both the old and new version; you'll use both in the commit message.

**Phase 2: Stage everything and commit**

6. Run `/usr/bin/git status` to confirm there are changes to commit. The version bump itself counts; if `git status` shows only that, proceed — a deploy that ships just the bump is valid. If the working tree was already clean *before* the bump and the bump is the only change, that's still a valid release-only commit.
7. Run `/usr/bin/git diff` to review the full set of changes (including the version bump) so the commit message reflects reality.
8. Run `/usr/bin/git log --oneline -5` to match the repository's commit-message style.
9. Stage every change with `/usr/bin/git add -A`.
10. Compose a commit message:
    - Title line: `Release vNEW_VERSION` (e.g. `Release v0.7.6`).
    - Body: one short paragraph summarizing the *why* of the changes being shipped — inferred from the staged diff, not just the version bump. If there are no meaningful changes other than the version, write `Version bump only; no code changes since v<OLD_VERSION>.`
    - Trailer: `Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>`
11. Create the **signed** commit using a HEREDOC so line breaks survive the shell. The `-S` flag is explicit even though `commit.gpgsign=true` is set globally — this documents the workflow's intent in the file and survives if the global config is ever disabled or the deploy runs on a machine missing it:
    ```bash
    /usr/bin/git commit -S -m "$(cat <<'EOF'
    Release v<NEW_VERSION>

    <body paragraph>

    Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
    EOF
    )"
    ```
12. Create a **signed annotated tag** pointing at the new commit. The lowercase `-s` flag makes it both annotated AND signed (lightweight tags can't be signed — they're just refs). Same defense-in-depth rationale as step 11. The tag message stays short because the full release notes live in the commit body:
    ```bash
    /usr/bin/git tag -s "v<NEW_VERSION>" -m "Release v<NEW_VERSION>"
    ```
    Verify the tag was created and signed: `/usr/bin/git tag -v "v<NEW_VERSION>"` should print `Good "git" signature`.

**Phase 3: Push to both remotes**

13. Confirm both remotes exist via `/usr/bin/git remote`. This project ships with two: `origin` (Bitbucket) and `github` (GitHub). If either is missing, stop and tell the user — do not silently push to only one.
14. Push to `origin` first: `/usr/bin/git push --follow-tags origin HEAD`. The `--follow-tags` flag pushes both the branch HEAD and any annotated tags reachable from it (i.e., the `v<NEW_VERSION>` tag we just created), so the commit and tag land in one atomic operation per remote. Report the result.
15. Push to `github`: `/usr/bin/git push --follow-tags github HEAD`. Report the result.
16. If either push fails, surface the error verbatim and stop — do **not** retry with force, do not skip hooks, do not rewrite history. A failed push on one remote with a successful push on the other is a known-consistent state the user can recover from manually. If the failure is `required_signatures hook declined` from GitHub, that means the commit isn't signed — fix local signing config (see CLAUDE.md / SSH signing setup) and re-run; do NOT bypass.

**Phase 4: Report**

17. Summarize in one message: new version, commit hash, tag name, branch name, and both push destinations with their reported ref updates. Example:

    > Released **v0.7.6** as `a1b2c3d` on `main` (signed, tagged `v0.7.6`).
    > - origin (Bitbucket): `<old-sha>..a1b2c3d` + tag `v0.7.6`
    > - github: `<old-sha>..a1b2c3d` + tag `v0.7.6`

---

**Hard rules**

- Never use `--force`, `--force-with-lease`, `--no-verify`, or any flag that bypasses hooks, signing, or history.
- Never amend a prior commit to fold in the version bump — always create a new commit.
- Never push to `main` with `--force` even if a hook rejects; report the hook failure and let the user decide.
- Never modify any file other than `conf/application.conf` as part of the bump itself. Whatever else is in the working tree ships as-is.
- If the current branch is not `main`, proceed anyway but note the branch name prominently in the final summary so the user doesn't assume they're releasing from `main`.
