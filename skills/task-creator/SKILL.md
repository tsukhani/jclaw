---
name: task-creator
description: >-
  Design, create, change or review a scheduled task so it runs unattended and does exactly its job: steps that
  name each tool and MCP action with its exact arguments, a tool list holding only what the steps need, the things
  the task must never do, delivery, safe re-runs, and a checked first run. Use whenever you are asked to create,
  schedule, change, fix or review a recurring or one-off task with task_manager. A plain reminder needs only its
  own short section here.
metadata:
  version: "1.0.0"
  author: Tarun Sukhani
  jclaw-icon: "⏰"
  jclaw-tools: task_manager jclaw_api filesystem
---

# Task Creator

A task runs with nobody watching. No one answers its questions, approves a step or notices it improvising. Everything it does comes from three things you set when you create it: its **steps** (the instructions every run executes), its **tool list** (`enabledToolNames`) and its **delivery**. Write the steps for a model that has never seen this conversation.

Follow the procedure below for every task you create or change with `task_manager`. Worked examples of each rule, good and bad, are in `skills/task-creator/references/examples.md`: read it with the `filesystem` tool's readFile before your first task in a conversation.

## Rules you always follow

1. **Name every action exactly.** Each step names the tool or MCP action it uses, spelled as the server lists it, with its exact argument names. Never write an action you have not confirmed exists.
2. **Always set a tool list.** Never leave `enabledToolNames` empty: an empty list gives the task every tool its agent has.
3. **Only what the steps need.** The list holds each tool a step names and nothing else. Each MCP server is narrowed to the actions the steps name, written `mcp_<server>/<action>`.
4. **Say what the task must never do.** The first step states the tools the task uses and what it must never do (send, delete, upload, buy, run a command, start a subagent), whatever a later step seems to need.
5. **Stop and report.** The first step also says that when an action fails, the run stops and reports the error. It never works around a failure with another action, a script, a copied or re-encoded file, or a test message.
6. **Delivery does the sending.** Output goes out through `delivery`, never through a "send it to..." step.
7. **Ask before a test run reaches anyone.** You may run a task once to check it when the run stays with the operator. Ask the operator first when a run would email, post to or message anyone else, or change data other people use.
8. **No secrets in steps.** Never write a key, token or password into a task's steps. A skill's secrets stay in its Secrets panel.

## A plain reminder

A reminder (`payloadType: "reminder"`) skips the model: its `description` is the exact text the operator sees, one or two short lines. Give it `name`, `description`, `schedule` and, when it should go somewhere other than this chat, `delivery`. It needs no steps, tool list or test run, and the rest of this skill does not apply.

## Procedure

### 1. Understand the job

Before writing anything, know:

- what the task produces, and for whom
- when it runs: once, at a time, every N minutes, or on a calendar rule
- where its output goes
- what it reads, and what it changes: mail, files, sheets, messages, money
- what a run must never do

Ask the operator about anything you cannot answer from the request.

### 2. Look for an existing task

Call `task_manager` with `listRecurringTasks` (for a one-off reminder, `listReminders`). If a task already does this job, change it with `updateTask` rather than add a second one, and cancel any earlier attempt with a similar name.

### 3. List the tools and actions

Walk the job step by step and write down the tool each step needs. For each step that uses an MCP server:

- Call the server's handle, `mcp_<server>`, with `{"describe": ["<action>", ...]}` for the actions you plan to use. Use only names it describes. A name it reports under `unknown` does not exist: never write it into a step.
- Note each action's argument names exactly as its input schema gives them (for example `range_name`, not `range`), and which arguments are required.

### 4. Write the steps

`description` is a JSON array of step strings, in order, even for one step.

- **Step 1 is the constraints step.** It names the tools the task uses, lists what it must never do, and states the stop-and-report rule (rules 4 and 5).
- **Every later step does one thing.** It names the action with its arguments written out, `action_name(argument: value, ...)`, with every fixed value spelled in full: IDs, folder IDs, sheet ranges, labels, addresses and file paths. Say what to do with the result.
- **Write the conditions down.** If a step depends on an earlier result, say so ("if nothing matched, stop with no output").
- **A subagent's step says what it may touch.** A subagent inherits the task's whole tool list. When a subagent needs a tool the task itself must not use (`exec` to run a script, say), step 1 says the task never calls that tool itself. The subagent's step then says what it may do and that it must not touch anything else, through a tool or a script. JClaw does not enforce this split: it is an instruction, so keep it short and explicit.

### 5. Set the tool list

Build `enabledToolNames` from your list in step 3:

| Include | When |
| --- | --- |
| each native tool a step names | always |
| `mcp_<server>/<action>` | one entry per MCP action a step names; never the bare `mcp_<server>`, which allows every action of that server |
| `ccr_retrieve` | always: it reads back a tool result that was too large to show in full |
| `datetime` | only when a step asks for the time; every run already gets the current time |
| `subagent_spawn` | only when a step delegates to a subagent |
| `exec` | only when a step, or a subagent's step, runs a command |

Leave out `message`: delivery sends the output. Leave out `jclaw_api` unless a step calls a JClaw route. A tool that is "nice to have" stays out: a run that needs a tool its list lacks gets a clear refusal, while a tool it never needed is only a way to go wrong.

### 6. Delivery and schedule

- `delivery`: `<channel>:<target>` (for example `slack:C0123` or `telegram:12345`), `tool:<toolName>` to send through a tool (email is `tool:send_gmail_message`), or `none` to keep the output on the Tasks page. Leave it out to deliver to the chat you are in.
- `schedule`: `now`; a duration such as `2h` for once, that long from now; an ISO date-time such as `2026-06-13T15:00` for once at that moment; `every <duration>` for an interval; or a 6-field cron for a calendar rule. In cron, `L` after a weekday means the last one in the month: `0 0 17 * * 5L` is the last Friday at 17:00.
- `timezone`: an IANA name for a cron or a date-time, when the operator's default timezone is not the right one.

### 7. Make a second run safe

A task may run twice: a retry, a manual run, an overlap. Say in the steps what keeps a second run from doing the work again:

- a label it applies and searches past
- a status column it sets and checks
- a cleanup step that removes the previous run's output first

If nothing can, tell the operator before you create the task.

### 8. Create it

Call `task_manager` with `createTask`:

- `name`: a short kebab-case identifier
- `description`: the steps
- `schedule`
- `delivery`
- `enabledToolNames`: the list
- `timezone`, if needed

Only `main` may set `modelProvider` and `modelId`, and only when the operator asks for a specific model.

### 9. Check the first run

When the run stays with the operator (their own inbox, sheet, channel or files), call `task_manager` with `runNow` and wait for the notice that the run ended. When it would reach anyone else, ask the operator first (rule 7). Offer a copy of the task that sends only to the operator.

Then read what the run did, with `jclaw_api`:

1. `GET /api/tasks/{id}/runs` for the run's status and its id.
2. `GET /api/task-runs/{id}/messages` for every tool call and result.

Check that it called only the actions its steps name, with the arguments they give. Then look for the messages in the next section. Fix the steps or the list, and run it again until a run is clean.

### 10. Report

Tell the operator:

- the task's name and schedule
- where its output goes
- its tool list
- what its first run did, and anything you could not check

## Reading a run's errors

| A tool result that says | Means | Fix |
| --- | --- | --- |
| `Error: Tool 'X' is not enabled` | `X` is not on the task's list, or not on its agent | Add it if a step needs it; otherwise fix the step |
| `Error: action 'A' of MCP server 'S' is not on task '...''s tool list` | the step uses an action the list leaves out | Add `mcp_S/A` if a step needs it; otherwise fix the step |
| `MCP server 'S' has no action named 'A'` | a step names an action that does not exist | `describe` the server and correct the step |
| `validation error ... Missing` or `Unexpected keyword` | wrong or missing argument names | `describe` the action and write its arguments into the step |
| the run used a script or a copied file to finish a step | the model worked around a failure | Make the failing step exact, and repeat the stop-and-report rule in step 1 |

On the Logs page, a warning that a task "names MCP actions their servers do not offer" means a server was updated and renamed or dropped an action. Update the task's steps and its list together.

## Changing or reviewing a task

Apply the same rules. Before changing a task, read its last few runs (step 9) for the messages above: they show what its steps or list already get wrong. Change the steps and the list in one `updateTask`, so each always matches the other, then check one run.
