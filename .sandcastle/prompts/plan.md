# TASK

Predict which files each Jira story below will change in this repository, so the harness never runs two stories that change the same file before one of them is merged. Read the code each ticket points at before answering. Do not change files, do not commit, and do not call Jira.

# STORIES

{{STORIES}}

# ANSWER

For each story, list every repository path it will create or modify, tests included, relative to the repository root. Include every file a ticket names, and every file a story's branch already changes. When unsure, include the file: a missed file costs a merge conflict, an extra one only a delay.

Output exactly one block with every story key present, then <promise>COMPLETE</promise>:

<plan>{"JCLAW-1": ["app/utils/Example.java", "test/ExampleTest.java"]}</plan>
