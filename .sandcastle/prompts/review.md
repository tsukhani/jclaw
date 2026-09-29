# TASK

Review branch `{{SOURCE_BRANCH}}`, which implements Jira ticket {{KEY}}: {{SUMMARY}}. Improve it where a concrete improvement is warranted; do not restyle what is already correct.

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
!`git diff main...HEAD`
```

# REVIEW

1. Check every acceptance criterion against the diff, not against the commit message.
2. Check AGENTS.md's conventions on the changed lines: comment discipline (section 8), nullness annotations, American spelling, test base classes and locks.
3. Check that the tests pin the new behaviour and would fail without it.
4. Check that every point of the human reviewer's feedback, if any, is addressed, and that the lessons are followed.

Git prints `error: Unable to create '…/packed-refs.lock': Read-only file system` after commits: expected, since the repository's shared metadata is read-only here, and the commit still succeeded.

If you change code, run `./jclaw.sh diagnostics` and the affected test classes with `./gradlew playAutotest -Ptests=...`, then commit on this branch. Do not run the full suite, do not push, and do not call Jira.

When done, output <promise>COMPLETE</promise>.
