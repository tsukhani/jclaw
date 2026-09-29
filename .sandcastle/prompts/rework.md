# TASK

The human reviewer sent Jira ticket {{KEY}} ({{SUMMARY}}) back from review. You are on its branch `{{SOURCE_BRANCH}}`, which holds the rejected submission. Address every point of the feedback on this branch. AGENTS.md tells agents to work on `main` and not to create branches; in this run the harness owns branching, so stay on this branch and commit to it.

# REVIEWER FEEDBACK

{{FEEDBACK}}

# TICKET

{{DESCRIPTION}}

# LESSONS FROM PAST REVIEWS

Rules the reviewer asked for on earlier stories. Follow them.

{{LESSONS}}

# THE REJECTED SUBMISSION

!`git log main..HEAD --format='%h %s%n%b---'`

# FEEDBACK LOOP

- `./jclaw.sh diagnostics` prints compile errors as a JSON array; `[]` means the tree compiles.
- `./gradlew playAutotest -Ptests=ClassA,ClassB` runs chosen test classes (about a minute). Never pipe it; afterwards check `ls test-result | grep failed.html`.
- `./gradlew spotlessApply` after any change that moves imports.
- Git prints `error: Unable to create '…/packed-refs.lock': Read-only file system` after commits and branch switches. That is expected: the repository's shared metadata is read-only in this sandbox, and the commit or switch still succeeded (`git log -1` shows it).
- Do NOT run the full suite (`play autotest` with no filter, `./jclaw.sh test`, `./jclaw.sh diagnostics --tests`). The harness runs it after you finish and will hand you any failures.

# COMMIT

Commit the rework on this branch with the ticket key in the subject, following AGENTS.md's commit conventions. Add commits; do not rewrite or squash the earlier ones. Do not push. Do not call Jira or any other tracker. Do not contact anything on `host.docker.internal`.

# LESSON

Last, state the general rule that would have prevented this rejection on any story, not only this one, in one or two sentences inside <lesson>...</lesson>. If the feedback concerns only this story, output <lesson>none</lesson>.

When the work is committed and the lesson is written, output <promise>COMPLETE</promise>.
