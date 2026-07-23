---
name: jira-operations
description: Perform JIRA operations using the MCP server as primary, with automatic fallback to REST API for operations that don't work via MCP (e.g., story points via Agile estimation endpoint, epic linking via parent field). Includes sprint workflow helpers.
version: 1.0.4
author: main
tools: [mcp_jira-confluence, exec, filesystem]
commands: []
icon: 🎯
---
# Jira Operations

Perform JIRA operations with intelligent MCP/REST API fallback. Uses the `jira-confluence` MCP server as primary, but falls back to direct REST API calls when MCP commands fail or don't support the operation.

## Configuration

Credentials are read from `~/.claude.json` (the same config that drives the MCP server):
- `JIRA_URL` — base URL (e.g., `[JIRA_URL]`)
- `JIRA_PERSONAL_TOKEN` — PAT for Bearer auth

The skill reads this file directly via `filesystem` to construct `curl` fallback calls.

## Core Principle: MCP First, REST Fallback

For every operation:
1. **Try MCP first** — it's cleaner, typed, and handles edge cases
2. **If MCP fails with a known limitation**, fall back to REST API
3. **Report which path succeeded**

## Operations

### Update Story Points

**MCP path:** `jira_update_issue` with `{"customfield_10006": N}`  
**Problem:** Fails with "not on appropriate screen" for many issue types  
**Fallback:** Agile estimation endpoint

```bash
curl -sS -X PUT \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"value": N}' \
  "$JIRA_URL/rest/agile/1.0/issue/{issueKey}/estimation?boardId={boardId}"
```

**Procedure:**
1. Try `jira_update_issue` with `fields={"customfield_10006": N}`
2. If error contains "cannot be set" / "not on the appropriate screen":
   a. Get the board ID for the project via `jira_get_agile_boards(project_key)`
   b. Call the Agile estimation endpoint via `exec` + `curl`
   c. Verify with `jira_get_issue(fields="customfield_10006")`
3. Report which path succeeded

### Link Issue to Epic

**MCP path:** `jira_link_to_epic(issue_key, epic_key)`  
**Problem:** Works for most cases, but may not set the underlying Epic Link custom field on some Jira Server/DC instances  
**Fallback:** Direct REST API with `customfield_10002` (Epic Link field)

**How Epic Linking Works on Jira Server/DC:**
- Epic Link is stored in `customfield_10002` (issue key of the Epic)
- The `parent` field is for subtasks, not epic links
- The `jira_link_to_epic` MCP command calls the Greenhopper API which may use `customfield_10002` internally

**Procedure:**
1. **Try `jira_link_to_epic(issue_key, epic_key)` first**
   - This is the cleanest approach and works for most cases
2. **If that fails or you need to verify the underlying field**:
   a. Use direct REST API to set `customfield_10002`:
      ```bash
      curl -sS -X PUT \
        -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json" \
        -d '{"fields": {"customfield_10002": "EPIC-123"}}' \
        "$JIRA_URL/rest/api/2/issue/{issueKey}"
      ```
   b. Verify via `jira_get_issue(fields="customfield_10002")`
3. **For label-based tracking** (optional):
   - Add epic name as label: `jira_update_issue` with `fields={"labels": ["epic-EPIC-123", ...existing_labels]}`
   - This enables JQL filtering like `labels = epic-EPIC-123`

**Important:** Do NOT use `{"parent": "EPIC-123"}` — that creates a subtask relationship, not an epic link.

### Add to Active Sprint

**MCP path:** `jira_add_issues_to_sprint(sprint_id, issue_keys)`  
**Always use MCP** — this works reliably.

**Procedure:**
1. Find the board: `jira_get_agile_boards(project_key)`
2. Get the active sprint: `jira_get_sprints_from_board(board_id, state="active")`
3. Add the issue: `jira_add_issues_to_sprint(sprint_id, issue_keys)`

**Important:** Do NOT probe sprint IDs one-by-one with `jira_get_sprint_issues`. Use `jira_get_sprints_from_board(board_id, state="active")` directly.

### Create Story with Epic Link

**Always use MCP** — `jira_create_issue` supports `additional_fields={"epicKey": "EPIC-123"}` or `{"parent": "EPIC-123"}`.

### Update Issue Fields (General)

**MCP path:** `jira_update_issue(issue_key, fields)`  
**Only fall back to REST if:**
- The field is not on the edit screen
- The field requires a special endpoint (like estimation)
- The MCP returns "cannot be set"

## REST API Helper Functions

When falling back to REST, construct curl calls as follows:

```bash
# Read token from ~/.claude.json (do NOT print it)
TOKEN=$(jq -r '.mcpServers["jira-confluence"].env.JIRA_PERSONAL_TOKEN' ~/.claude.json)
JIRA_URL=$(jq -r '.mcpServers["jira-confluence"].env.JIRA_URL' ~/.claude.json)

# Use -K config file to keep token off argv
CONFIG_FILE=$(mktemp)
cat > "$CONFIG_FILE" <<EOF
header = "Authorization: Bearer $TOKEN"
header = "Content-Type: application/json"
EOF
chmod 600 "$CONFIG_FILE"

curl -sS -K "$CONFIG_FILE" -X PUT -d '{"value": 5}' \
  "$JIRA_URL/rest/agile/1.0/issue/ISSUE-KEY/estimation?boardId=BOARD_ID"

rm -f "$CONFIG_FILE"
```

**Security rules:**
- Never put the token on the curl command line (use `-K` config file)
- Never echo request headers (avoid `-v`, use `-i` for response only if needed)
- Delete the temp config file immediately after use
- Do NOT return the token in any response

## Error Handling

| MCP Error | Meaning | Fallback |
|---|---|---|
| "cannot be set. It is not on the appropriate screen" | Field not on edit screen | REST API alternative endpoint |
| "not editable on issue due to its issue type" | Issue type restriction | Check issue type; may need different approach |
| 401 / auth errors | Token issue | Cannot fallback — report to user |
| "Field 'customfield_X' cannot be set" | Unknown field ID | Verify field ID via `jira_search_fields` |

## Verification

After any update, verify the result:
- `jira_get_issue(issue_key, fields="customfield_10006,labels,customfield_10002")` to confirm changes
- Cross-check with `jira_get_sprint_issues(sprint_id)` for sprint membership
- For epic links, also check: `jira_search(jql="'Epic Link' = EPIC-123")` to find all stories in an epic

## Instance-Specific Field Reference ([JIRA_URL])

| Field | Custom Field ID | Notes |
|---|---|---|
| Story Points | `customfield_10006` | Set via Agile estimation endpoint (not on edit screen) |
| Epic Link | `customfield_10002` | Stores epic issue key as string |
| Epic Name | `customfield_10004` | Epic's display name |
| Epic Status | `customfield_10003` | Epic workflow status |
| Epic Color | `customfield_10005` | Epic color indicator |

Always verify field IDs on new instances via: `jira_search_fields(keyword="Epic")` or `GET /rest/api/2/field`
