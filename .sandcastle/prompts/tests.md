# TASK

Write the acceptance tests for ticket {{KEY}}: {{SUMMARY}}. Tests only: write no production code.

You are on branch `{{SOURCE_BRANCH}}`, created from `main` by the harness that launched you; any story this one depends on is already merged there. AGENTS.md tells agents to work on `main` and not to create branches; in this run the harness owns branching, so stay on this branch and commit to it.

# TICKET

{{DESCRIPTION}}

# REVIEW OF YOUR EARLIER DRAFT

{{FEEDBACK}}

# LESSONS FROM PAST REVIEWS

Rules the reviewer asked for on earlier stories. Follow them.

{{LESSONS}}

# WHY THIS STEP EXISTS

The operator reads these tests before any code is written and approves them as what the ticket means. Otherwise the agent that reads a criterion also writes the test that judges its reading, and a misread criterion still goes green. So the tests must say what the operator asked for, in terms the operator can check against the ticket.

# WHAT TO WRITE

- One test per acceptance criterion, named after it. A backend behaviour is a `FunctionalTest` when a request is involved and a `UnitTest` otherwise; a frontend behaviour is a vitest file under `frontend/test/`; something only a browser shows is a Playwright spec under `frontend/tests/`.
- Assert what the criterion says the operator sees or gets, not how the code will do it. Do not mock the code the story changes.
- Each test must fail now, for the reason the criterion is unmet: run it and see. A test that passes before any change pins nothing; rewrite it until it fails for the right reason.
- Prefer tests that compile against today's code. Where a criterion needs an API that does not exist yet, write the test against the smallest API the criterion implies and say so in the commit body.
- A criterion no test can check (a decision to record, a manual step): write a line `Untestable: <criterion>: <why>` in the commit body instead.
- Touch nothing outside `test/`, `frontend/test/` and `frontend/tests/`. The harness refuses a branch that changes anything else.

# FEEDBACK LOOP

- `./jclaw.sh diagnostics` prints compile errors as a JSON array; `[]` means the tree compiles.
- `./gradlew playAutotest -Ptests=ClassA,ClassB` runs chosen test classes (about a minute). Never pipe it; afterwards check `ls test-result | grep failed.html`.
- `cd frontend && pnpm exec vitest run test/<file>` runs one frontend test file.
- Git prints `error: Unable to create '…/packed-refs.lock': Read-only file system` after commits and branch switches. That is expected: the repository's shared metadata is read-only in this sandbox, and the commit or switch still succeeded (`git log -1` shows it).
- Do NOT run the full suite (`play autotest` with no filter, `./jclaw.sh test`, `./jclaw.sh diagnostics --tests`).

# COMMIT

Commit on this branch with the subject `test: {{KEY}} acceptance tests`. In the body, one line per criterion: the criterion, the test that checks it, and how that test fails today. Add the `Untestable:` lines and any API a test assumes. The operator reviews from the diff and this body alone.

Do not push. Do not call Jira, GitHub or any other tracker. Do not contact anything on `host.docker.internal`.

When the tests are committed, output <promise>COMPLETE</promise>.
