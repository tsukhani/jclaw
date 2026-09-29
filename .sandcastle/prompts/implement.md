# TASK

Implement Jira ticket {{KEY}}: {{SUMMARY}}

You are on branch `{{SOURCE_BRANCH}}`, created from `main` by the harness that launched you; any story this one depends on is already merged there. AGENTS.md tells agents to work on `main` and not to create branches; in this run the harness owns branching, so stay on this branch and commit to it.

# TICKET

{{DESCRIPTION}}

# LESSONS FROM PAST REVIEWS

Rules the reviewer asked for on earlier stories. Follow them.

{{LESSONS}}

# CONTEXT

AGENTS.md is the project guide and is already loaded. Read the files the ticket names, and their tests, before editing. Upstream sources the ticket refers to can be fetched with `curl`.

Recent history on this branch:

!`git log -n 8 --format='%h %s'`

# FEEDBACK LOOP

- `./jclaw.sh diagnostics` prints compile errors as a JSON array; `[]` means the tree compiles.
- `./gradlew playAutotest -Ptests=ClassA,ClassB` runs chosen test classes (about a minute). Never pipe it; afterwards check `ls test-result | grep failed.html`.
- `./gradlew spotlessApply` after any change that moves imports.
- Git prints `error: Unable to create '…/packed-refs.lock': Read-only file system` after commits and branch switches. That is expected: the repository's shared metadata is read-only in this sandbox, and the commit or switch still succeeded (`git log -1` shows it).
- Do NOT run the full suite (`play autotest` with no filter, `./jclaw.sh test`, `./jclaw.sh diagnostics --tests`). The harness runs it after you finish and will hand you any failures.

# COMMIT

Commit on this branch following AGENTS.md's commit conventions, with the ticket key in the subject. When the ticket asks for a decision to be made or recorded, make it and write it in the commit body as a line starting `Decision:`; the harness records it on the ticket.

Do not push. Do not call Jira or any other tracker. Do not contact anything on `host.docker.internal`.

When the work is committed, output <promise>COMPLETE</promise>.
