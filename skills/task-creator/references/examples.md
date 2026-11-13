# Worked examples

Each example is a real failure, then the version that works. Server and action names are the Google Workspace MCP server's; the same rules hold for any server.

## Contents

1. A tool list wider than the job
2. An action that does not exist
3. A step that needs a tool the list lacks
4. Guessed arguments
5. A workaround instead of a report
6. A send step instead of delivery
7. A second run that repeats the work

## 1. A tool list wider than the job

**Bad.** A task that labels incoming mail had `enabledToolNames: ["mcp_google-workspace", "datetime"]` and steps that never mentioned sending. When a search found nothing to label, the model loaded `send_gmail_message` on its own and emailed the operator test messages so it would have something to label.

**Why.** The bare `mcp_google-workspace` allows every one of the server's 119 actions, and nothing in the steps forbade sending.

**Good.**

```json
["Constraints - This task uses only search_gmail_messages, get_gmail_messages_content_batch and batch_modify_gmail_message_labels. It never sends, drafts, replies to or forwards an email, never deletes or archives one, and never creates a label. If an action fails, stop and report the error.",
 "Call search_gmail_messages(query: \"is:unread -label:Processed\", page_size: 50). If it returns no emails, stop with no output.",
 "..."]
```

with `enabledToolNames: ["mcp_google-workspace/search_gmail_messages", "mcp_google-workspace/get_gmail_messages_content_batch", "mcp_google-workspace/batch_modify_gmail_message_labels", "ccr_retrieve"]`.

## 2. An action that does not exist

**Bad.** "Get today's calendar events using mcp_google-workspace tool calendar_list_events." The server has no such action. Every run failed that call, then guessed until it found `get_events`.

**Good.** Before writing the step, call `mcp_google-workspace` with `{"describe": ["calendar_list_events", "get_events"]}`. The first comes back under `unknown`, the second with its input schema. The step then reads "Call get_events(...)" with the arguments from that schema.

## 3. A step that needs a tool the list lacks

**Bad.** A step converted each receipt's amount to the home currency "using the exchange rate from api.frankfurter.app", but the task's list had no `web_fetch`. Every conversion failed, and the run called a Workspace action named `web_fetch`, which does not exist either.

**Good.** Walk each step and add the tool it needs: here `web_fetch`, and name it in the step ("fetch the rate with web_fetch from ...").

## 4. Guessed arguments

**Bad.** "Use read_sheet_values on spreadsheet 1zL...". The model called it with `range`, which the action rejects, since its argument is `range_name`. "Use create_drive_file to upload the PDF" led to a call missing three required fields.

**Good.** Write the call out from the schema `describe` returned:

```text
Call read_sheet_values(spreadsheet_id: "1zL...", range_name: "A1:I200").
Call create_drive_file(file_name: "<the PDF's file name>", folder_id: "0AI...", mime_type: "application/pdf", fileUrl: "file://<its absolute path, spaces as %20>").
```

## 5. A workaround instead of a report

**Bad.** An upload was refused because the file sat in a folder the server would not read. The task's subagent, which held `exec` to run a stamping script, wrote another script that uploaded the file to Drive directly. The run reported success, and the failure went unnoticed for two months.

**Why.** Nothing said to stop. And a subagent inherits the task's whole tool list, so a subagent that needs `exec` for one script can use it for anything.

**Good.** In the constraints step: "If any action fails, stop and report the error: never work around it with another action, a script, a copied or re-encoded file." In the subagent's step: "The subagent only stamps: it must not upload, move, rename or delete any file, and must not reach Drive, Gmail or any other service, through a tool or a script. It returns the signed PDF's path." Then fix the cause: here, the folder the server reads.

## 6. A send step instead of delivery

**Bad.** The last step said "Post the summary to the #reports Slack channel", and the list included `message`.

**Good.** Drop the step and the tool, and set `delivery: "slack:<channel id>"`. The run's final reply is posted for it, once, after the run ends. For email, use `delivery: "tool:send_gmail_message"`.

## 7. A second run that repeats the work

**Bad.** "Find receipts from the last 7 days and add each to the sheet." A retry added every receipt twice.

**Good.** Give the work a marker the next run skips:

- search with `-label:Expense_Processed`, and label every email the run looked at
- or check the sheet for the receipt's ID before adding a row
- or start with a cleanup step that removes the previous run's output, as a task that regenerates files does
