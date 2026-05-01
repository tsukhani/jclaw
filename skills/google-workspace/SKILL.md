---
name: google-workspace
description: Interact with Google Workspace services (Drive, Gmail, Sheets, Docs, Calendar, etc.) using the Google Workspace CLI
version: 1.0.0
tools: [exec]
icon: 🗂️
---

# Google Workspace

Use this skill to interact with Google Workspace services including Drive, Gmail, Sheets, Docs, Calendar, and more through the `@googleworkspace/cli` tool.

## Prerequisites

- OAuth credentials are pre-configured in `credentials.json`
- Before using, you must authenticate by running the login command below

### Authentication Setup

```bash
export GOOGLE_WORKSPACE_CLI_CREDENTIALS_FILE="skills/google-workspace/credentials.json"
npx @googleworkspace/cli auth login
```

The OAuth credentials stored in `credentials.json`:
- **Client ID:** `[OAUTH_CLIENT_ID]`
- **Type:** Desktop OAuth app
- **Name:** [APP_NAME] (Shared OAuth Desktop app for Google Workspace MCP)

## Available Services

- `drive` — Manage files, folders, and shared drives
- `sheets` — Read and write spreadsheets
- `gmail` — Send, read, and manage email
- `calendar` — Manage calendars and events
- `docs` — Read and write Google Docs
- `slides` — Read and write presentations
- `tasks` — Manage task lists and tasks
- `people` — Manage contacts and profiles
- `chat` — Manage Chat spaces and messages
- `keep` — Manage Google Keep notes
- `meet` — Manage Google Meet conferences
- `forms` — Read and write Google Forms

## Usage Pattern

```
gws <service> <resource> [sub-resource] <method> [flags]
```

## Environment Variables

```bash
export GOOGLE_WORKSPACE_CLI_CREDENTIALS_FILE="skills/google-workspace/credentials.json"
export GOOGLE_WORKSPACE_CLI_TOKEN="<access_token>"  # Optional: pre-obtained token
export GOOGLE_WORKSPACE_PROJECT_ID="<project_id>"    # Optional: for quota/billing
```

## Common Operations

### Google Drive

**List files:**
```bash
gws drive files list --params '{"pageSize": 10}'
```

**Get file details:**
```bash
gws drive files get --params '{"fileId": "FILE_ID"}'
```

**Create a folder:**
```bash
gws drive files create --json '{"name": "New Folder", "mimeType": "application/vnd.google-apps.folder"}'
```

### Google Sheets

**Get spreadsheet data:**
```bash
gws sheets spreadsheets get --params '{"spreadsheetId": "SHEET_ID"}'
```

**Read a specific range:**
```bash
gws sheets spreadsheets.values get --params '{"spreadsheetId": "SHEET_ID", "range": "Sheet1!A1:D10"}'
```

**Update a range:**
```bash
gws sheets spreadsheets.values update --params '{"spreadsheetId": "SHEET_ID", "range": "Sheet1!A1"}' --json '{"values": [["Hello", "World"]]}'
```

### Gmail

**List recent emails:**
```bash
gws gmail users messages list --params '{"userId": "me", "maxResults": 10}'
```

**Get a specific message:**
```bash
gws gmail users messages get --params '{"userId": "me", "id": "MESSAGE_ID"}'
```

**Send an email:**
```bash
gws gmail users.messages send --params '{"userId": "me"}' --json '{"raw": "BASE64_ENCODED_EMAIL"}'
```

### Google Calendar

**List calendars:**
```bash
gws calendar calendarList list
```

**List events:**
```bash
gws calendar events list --params '{"calendarId": "primary", "maxResults": 10}'
```

**Create an event:**
```bash
gws calendar events insert --params '{"calendarId": "primary"}' --json '{"summary": "Meeting", "start": {"dateTime": "2026-04-09T10:00:00"}, "end": {"dateTime": "2026-04-09T11:00:00"}}'
```

### Google Docs

**Get document content:**
```bash
gws docs documents get --params '{"documentId": "DOC_ID"}'
```

## Flags Reference

| Flag | Description |
|------|-------------|
| `--params <JSON>` | URL/Query parameters as JSON |
| `--json <JSON>` | Request body as JSON (POST/PATCH/PUT) |
| `--upload <PATH>` | Local file to upload |
| `--upload-content-type <MIME>` | MIME type of uploaded file |
| `--output <PATH>` | Output file path for binary responses |
| `--format <FMT>` | Output format: json (default), table, yaml, csv |
| `--page-all` | Auto-paginate results |
| `--page-limit <N>` | Max pages to fetch |

## Response Formatting

Use `--format table` for human-readable tabular output, or `--format json` (default) for structured data that can be parsed programmatically.

## Error Handling

| Exit Code | Meaning |
|-----------|----------|
| 0 | Success |
| 1 | API error — Google returned an error |
| 2 | Auth error — credentials missing or invalid |
| 3 | Validation — bad arguments |
| 4 | Discovery — could not fetch API schema |
| 5 | Internal — unexpected failure |

## Examples

### Complete Workflow: Create and Populate a Spreadsheet

1. Set up credentials:
```bash
export GOOGLE_WORKSPACE_CLI_CREDENTIALS_FILE="skills/google-workspace/credentials.json"
```

2. Create a new spreadsheet:
```bash
gws sheets spreadsheets create --json '{"properties": {"title": "Sales Report"}}'
```

3. Note the returned `spreadsheetId` and update cells:
```bash
gws sheets spreadsheets.values update --params '{"spreadsheetId": "ID", "range": "Sheet1!A1:C3", "valueInputOption": "RAW"}' --json '{"values": [["Product", "Qty", "Price"], ["Widget", 10, 99.99], ["Gadget", 5, 149.99]]}'
```

### Upload a File to Drive

```bash
gws drive files create --json '{"name": "report.pdf", "mimeType": "application/pdf"}' --upload ./report.pdf
```

## Best Practices

1. Set `GOOGLE_WORKSPACE_CLI_CREDENTIALS_FILE` environment variable before running commands
2. Run `auth login` once to authenticate with your Google account
3. Use `--format table` for user-facing output and `--format json` when processing data
4. Use `--page-all` with a reasonable `--page-limit` when listing large numbers of resources
5. Store file IDs from responses for follow-up operations
6. For complex workflows, use `--format json` and parse results with `jq`
