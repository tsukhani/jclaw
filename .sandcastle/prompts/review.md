# TASK

Review branch `{{SOURCE_BRANCH}}`, which implements ticket {{KEY}}: {{SUMMARY}}. Improve it where a concrete improvement is warranted; do not restyle what is already correct.

# TICKET

{{DESCRIPTION}}

# HUMAN REVIEWER'S FEEDBACK ON THE PREVIOUS SUBMISSION

{{FEEDBACK}}

# LESSONS FROM PAST REVIEWS

{{LESSONS}}

# COMMITS ON THE BRANCH

!`git log main..HEAD --format='%h %s%n%b---'`

# DIFF TO main

```diff
!`d=$(git diff main...HEAD); if [ ${#d} -le 150000 ]; then printf '%s\n' "$d"; else git diff --stat main...HEAD; printf '\nToo large to inline: read each file with git diff main...HEAD -- <path>.\n'; fi`
```

# REVIEW

1. Check every acceptance criterion against the diff, not against the commit message.
2. Check AGENTS.md's conventions on the changed lines: comment discipline (Comments Carry Reasoning), nullness annotations, American spelling, test base classes and locks.
   Then read each guide in AGENTS.md's Topic guides whose trigger names something the diff touches, and check the changed lines against it. A guide holds rules AGENTS.md does not, and the security rules live in one.
3. For each defect you find, first settle whether the branch introduced it.
   - A defect in lines the branch did not change (`git diff main...HEAD -- <path>` shows no change there) predates the branch: leave it, and keep its `path:line` and what is wrong for the brief, which lists it as pre-existing. Fixing it here grows a story that did not cause it.
   - A defect the branch introduced, or one on `main` the branch now relies on (a new caller of a broken helper), is the branch's to fix. Search the branch's changes for every other instance of the same mistake and fix them together, not only the one you saw first.
4. For each acceptance criterion, find the test that proves it and hold it to AGENTS.md's Writing tests: it asserts the outcome exactly, covers the failure paths and both sides of any limit involved, and would fail without the change. Where a criterion has no such test, write it, and run it before you commit: if it fails because the code falls short of the criterion, fix the code in the same commit. The harness runs the suite once after review, with no repair rounds, and a red result blocks the story.
5. Check that every point of the human reviewer's feedback, if any, is addressed, and that the lessons are followed.

Git prints `error: Unable to create '…/packed-refs.lock': Read-only file system` after commits: expected, since the repository's shared metadata is read-only here, and the commit still succeeded.

If you change code, run `./jclaw.sh check --backend-only`, `./jclaw.sh diagnostics` and the affected test classes with `./gradlew playAutotest -Ptests=...`, then commit on this branch. Do not run the full suite, do not push, and do not call Jira, GitHub or any other tracker.

When done, output <promise>COMPLETE</promise>.
