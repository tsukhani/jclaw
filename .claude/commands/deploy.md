---
name: deploy
description: Bump application.version (defaults to the active JCLAW sprint) in conf/application.conf, commit all changes, and push to both git remotes (origin + github).
category: Release
tags: [release, version, git, deploy]
argument-hint: "[empty | patch | minor | major | x.y.z]"
---

**Deploy Workflow**

Bump the project version, commit every pending change alongside it, and push the result to both remotes. Use `/usr/bin/git` for every git invocation (per project convention).

**Arguments**

`$ARGUMENTS` may be one of:

- *(empty)* → **sprint-aware default**. Derive the version from the active sprint on the JCLAW board (see Phase 1, step 3): if the sprint's `MAJOR.MINOR` is ahead of the current version, bump to `<sprint>.0`; if it matches, bump the **patch**. This encodes the "version prefix = active sprint" convention.
- `patch` / `minor` / `major` → bump that segment explicitly; lower segments reset to `0`. **Overrides** sprint auto-detection. Examples: `minor` on `0.7.5` → `0.8.0`; `major` on `0.7.5` → `1.0.0`.
- An explicit version string matching `\d+\.\d+\.\d+` (e.g. `1.0.0-rc.1`) → set the version to exactly that. **Overrides** sprint auto-detection. Any leading `v` is stripped.

Reject anything else with a clear message; do not guess.

---

**Phase 1: Bump the version**

1. Read `conf/application.conf` and find the single line that starts with `application.version=`. If missing, stop and tell the user — this command only runs against a project that already uses that key.
2. Parse the current value (right-hand side of `=`). Split on `.` to get the `MAJOR.MINOR.PATCH` triple; preserve any trailing `-suffix` pre-release tag by dropping it before bumping (explicit-version arg is the only way to set one back).
3. **Determine the new version:**
   - **Explicit version, or `patch`/`minor`/`major`** — apply directly per the argument rules above (these override sprint detection).
   - **Empty (sprint-aware default)** — derive from the active sprint on the JCLAW board:
     a. Query the active sprint name. The Jira PAT lives in `~/.claude.json` → `mcpServers["jira-confluence"].env`; the JCLAW board is discovered by project key (currently id 30):
        ```bash
        python3 - <<'PY'
        import json, urllib.request, os
        env=json.load(open(os.path.expanduser("~/.claude.json")))["mcpServers"]["jira-confluence"]["env"]
        BASE,TOKEN=env["JIRA_URL"].rstrip("/"),env["JIRA_PERSONAL_TOKEN"]
        def api(p):
            r=urllib.request.Request(BASE+p); r.add_header("Authorization","Bearer "+TOKEN)
            return json.loads(urllib.request.urlopen(r).read().decode())
        board=next(b for b in api("/rest/agile/1.0/board?projectKeyOrId=JCLAW")["values"])
        sp=api(f"/rest/agile/1.0/board/{board['id']}/sprint?state=active").get("values") or []
        print(sp[0]["name"] if sp else "")
        PY
        ```
     b. Parse `SMAJOR.SMINOR` from the sprint name — strip a leading `v` and take the leading `\d+\.\d+` (e.g. `v0.15` → `0.15`). If the query fails, returns empty, or the name has no parseable prefix, **fall back to a patch bump and warn prominently** that sprint auto-detection failed — do not block the release on a Jira hiccup.
     c. Compare the sprint's `(SMAJOR, SMINOR)` tuple to the current `(MAJOR, MINOR)`:
        - **ahead** (sprint > current) → new version = `SMAJOR.SMINOR.0` (e.g. current `0.14.52`, sprint `v0.15` → `0.15.0`).
        - **equal** → **patch** bump → `MAJOR.MINOR.(PATCH+1)`.
        - **behind** (sprint < current — should not happen) → **stop and warn**; never silently downgrade.
4. Replace the line in place via the Edit tool — do **not** rewrite the file. The change must touch exactly one line.
5. Record both the old and new version; you'll use both in the commit message. If sprint auto-detection drove the bump, name the sprint in the final report (Phase 4).

**Phase 2: Stage everything and commit**

6. Run `/usr/bin/git status` to confirm there are changes to commit. The version bump itself counts; if `git status` shows only that, proceed — a deploy that ships just the bump is valid. If the working tree was already clean *before* the bump and the bump is the only change, that's still a valid release-only commit.
7. Run `/usr/bin/git diff` to review the full set of changes (including the version bump) so the commit message reflects reality.
8. Run `/usr/bin/git log --oneline -5` to match the repository's commit-message style.
9. Stage every change with `/usr/bin/git add -A`.
10. **Establish the range the notes must cover.** The body of this commit *is* the GitHub release notes — `Jenkinsfile`'s RELEASE stage copies it verbatim (minus the `Co-Authored-By:` trailer) into `gh release create`. So the audience is a reader on the Releases page, and the span they see is **from the last published Release**, not from the last tag. Those differ: `/deploy` tags every version, while a Release is cut only when someone ticks the Jenkins `RELEASE` toggle, so several versions routinely sit between them.

    ```bash
    # Tag of the newest published Release — the reader's actual starting point.
    PREV=$(gh release view --repo tsukhani/jclaw --json tagName --jq .tagName 2>/dev/null)
    # Fall back to the previous tag if the repo has no Releases yet.
    [ -n "$PREV" ] || PREV=$(/usr/bin/git describe --tags --abbrev=0 HEAD 2>/dev/null)
    /usr/bin/git log "$PREV"..HEAD --no-merges --format='%h %s%n%b'
    ```

    Read that log properly — subjects alone are not enough, because a subject can describe an approach a later commit reverses. Where two commits in the range supersede each other, chronicle **where it landed**, not the journey.

11. Compose a commit message:
    - Title line: `Release vNEW_VERSION` (e.g. `Release v0.7.6`).
    - Body: **reader-facing release notes covering the whole range from step 10**, in the shape the play1 fork uses (see `/opt/play1/.claude/commands/deploy.md` and any recent `gh release view --repo tsukhani/play1`):
      - `### Section` headings grouping the work by what a reader cares about — the feature area, `Fixes`, `Dependencies`. Not one heading per commit.
      - Under each, `- **Bold lead clause naming the change.**` followed by prose: what it does for the user, and the why or consequence where that isn't obvious. Name the ticket in parentheses when there is one.
      - Dependency bumps collapse to a single compact bullet list; don't give each its own paragraph.
      - A trailing `Note:` paragraph for anything genuinely surprising a reader would otherwise trip over.
      - Write for someone who has not read the diff and does not know the internals. No file paths, no function names, no "refactored X" — say what changed for them.
    - If nothing shipped but the bump, write `Version bump only; no code changes since v<OLD_VERSION>.`
    - Keep it publishable: this lands on the **public** GitHub mirror, so no credentials, customer specifics, or unreleased commercial plans (AGENTS.md §9).
    - Trailer: the `Co-Authored-By:` line your standing instructions specify, which names the model actually authoring the commit. **Do not hardcode a model version in this file.** It goes stale at every model bump, and a wrong trailer breaks nothing — no hook, test, or push rejects it — so the drift survives indefinitely. If your instructions specify no trailer, omit it rather than guessing a model name.

    The body is write-once: once pushed, correcting it would mean rewriting published history, and Jenkins reads whatever is in the commit. Get it right here.
12. Create the **signed** commit using a HEREDOC so line breaks survive the shell. The `-S` flag is explicit even though `commit.gpgsign=true` is set globally — this documents the workflow's intent in the file and survives if the global config is ever disabled or the deploy runs on a machine missing it:
    ```bash
    /usr/bin/git commit -S -m "$(cat <<'EOF'
    Release v<NEW_VERSION>

    <body paragraph>

    <Co-Authored-By trailer per step 10 — do not paste a model version from this file>
    EOF
    )"
    ```
13. Create a **signed annotated tag** pointing at the new commit. The lowercase `-s` flag makes it both annotated AND signed (lightweight tags can't be signed — they're just refs). Same defense-in-depth rationale as step 12. The tag message stays short because the full release notes live in the commit body:
    ```bash
    /usr/bin/git tag -s "v<NEW_VERSION>" -m "Release v<NEW_VERSION>"
    ```
    Verify the tag was created and signed: `/usr/bin/git tag -v "v<NEW_VERSION>"` should print `Good "git" signature`.

**Phase 3: Push to both remotes**

14. Confirm both remotes exist via `/usr/bin/git remote`. This project ships with two: `origin` (Bitbucket) and `github` (GitHub). If either is missing, stop and tell the user — do not silently push to only one.
15. Push to `origin` first: `/usr/bin/git push --follow-tags origin HEAD`. The `--follow-tags` flag pushes both the branch HEAD and any annotated tags reachable from it (i.e., the `v<NEW_VERSION>` tag we just created), so the commit and tag land in one atomic operation per remote. Report the result.
16. Push to `github`: `/usr/bin/git push --follow-tags github HEAD`. Report the result.
17. If either push fails, surface the error verbatim and stop — do **not** retry with force, do not skip hooks, do not rewrite history. A failed push on one remote with a successful push on the other is a known-consistent state the user can recover from manually. If the failure is `required_signatures hook declined` from GitHub, that means the commit isn't signed — fix local signing config (see CLAUDE.md / SSH signing setup) and re-run; do NOT bypass.

**Phase 4: Report**

18. Summarize in one message: new version, commit hash, tag name, branch name, and both push destinations with their reported ref updates. Example:

    > Released **v0.7.6** as `a1b2c3d` on `main` (signed, tagged `v0.7.6`).
    > - origin (Bitbucket): `<old-sha>..a1b2c3d` + tag `v0.7.6`
    > - github: `<old-sha>..a1b2c3d` + tag `v0.7.6`

19. **If the release touched `frontend/` or `app/controllers/`, close the report by naming the post-deploy check** — one line, not a new step to perform:

    > This release touched the frontend. To verify what shipped: `./jclaw.sh restart`, then `/e2e-audit`.

    Only a reminder, deliberately. The e2e suite is **not** a release gate and must not become one: it needs a live instance, so at push time it would exercise whatever build is currently *running* — i.e. the one being replaced — and a stopped app would fail every spec and block the push on a false red. Everything in `./jclaw.sh test` is a pure function of the source tree; the e2e suite is a function of the tree *plus* deployed state, which makes it post-deploy verification rather than merge gating. Restarting first is what makes it meaningful. See JCLAW-1139.

---

**Hard rules**

- Never use `--force`, `--force-with-lease`, `--no-verify`, or any flag that bypasses hooks, signing, or history.
- Never amend a prior commit to fold in the version bump — always create a new commit.
- Never push to `main` with `--force` even if a hook rejects; report the hook failure and let the user decide.
- Never modify any file other than `conf/application.conf` as part of the bump itself. Whatever else is in the working tree ships as-is.
- If the current branch is not `main`, proceed anyway but note the branch name prominently in the final summary so the user doesn't assume they're releasing from `main`.
