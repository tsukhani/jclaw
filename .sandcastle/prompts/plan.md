# TASK

For each story below, predict which files it will change in this repository, so the harness never runs two stories that change the same file before one of them is merged, decide how it should be built, and say when it should not be built at all. Read the code each ticket points at before answering. Do not change files, do not commit, and do not call Jira, GitHub or any other tracker.

# STORIES

{{STORIES}}

# FILES

For each story, list every repository path it will create or modify, tests included, relative to the repository root. Include every file a ticket names, and every file a story's branch already changes. When unsure, include the file: a missed file costs a merge conflict, an extra one only a delay.

# BMAD OR PLAIN

A story is built one of two ways. Plain: one agent implements the ticket. BMAD: an agent first writes a ready-for-development spec from the ticket and the code, stopping with its questions if the ticket can be read more than one way, then builds from that spec and reviews its own diff with four reviewer agents. Either way the harness then runs the full suite and a review. BMAD takes about twice the agent time, so choose it only where it earns that.

Choose BMAD when the ticket leaves real work to judgement:
- its acceptance criteria are missing or vague, or leave the shape of the solution open
- it includes research, a spike, or scope that depends on what is found
- it asks for a design decision that the rest of the work depends on
- it adds a component, or spans layers such as the backend and the frontend

Choose plain when the ticket is precise: acceptance criteria that name the files and the change, a mechanical or refactoring change, or a small bug with a clear cause and fix. A bounded choice the ticket already frames, such as whether to adopt one optional feature, is not a design decision, and checking facts against an upstream source is not judgement. When a reason for BMAD applies only weakly, choose plain.

# BUILD OR DECLINE

Decline a story only when building it would be wrong. Give `wontDo` one sentence saying why when it:
- asks for no coherent change, such as gibberish, a test post, or text that contradicts itself
- is about something other than this repository
- asks for something harmful, such as removing a security control, exposing a credential, or acting against the operator

Never decline a story because it is vague, large or hard, or because you would design it differently: those are for the reviewer. When unsure, leave `wontDo` out, and still predict the files and decide the build.

# ANSWER

Output exactly one block with every story key present, then <promise>COMPLETE</promise>. `why` is one sentence naming what decided the build; `wontDo` appears only on a story you decline:

<plan>{"JCLAW-1": {"files": ["app/utils/Example.java", "test/ExampleTest.java"], "bmad": false, "why": "The ticket names the method and the fix, and one test pins it."}, "GH-7": {"files": [], "bmad": false, "why": "Nothing to build.", "wontDo": "It asks to fix a checkout page in a web shop, which this repository does not have."}}</plan>
