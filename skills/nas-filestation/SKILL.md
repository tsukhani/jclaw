---
name: nas-filestation
description: Access a Synology NAS over the FileStation HTTP API via the bundled nas.sh script — list shares, browse folders, upload files, create folders, delete, and stat files. Use for any task that needs to store or retrieve files on the operator's Synology NAS.
author: main
tools: [exec, filesystem]
commands: [nas.sh]
icon: 🗄️
version: 1.0.0
---
# Synology NAS FileStation Access

Talk to a Synology NAS directly through its FileStation web API using the bundled `tools/nas.sh` shell script — no MCP server required.

## Setup (one-time, per deployment)

The script reads all connection details from `credentials/nas.env` in this skill folder. Each person/agent using this skill configures their own:

1. Copy `credentials/nas.env.example` to `credentials/nas.env` (keep the existing one if it is already configured).
2. Fill in the five values:
   - `NAS_URL` — e.g. `https://nas.example.com`
   - `NAS_USER` / `NAS_PASS` — a DSM user account with FileStation permission. **Important:** `NAS_PASS` must be URL-encoded (e.g. `+` → `%2B`, `|` → `%7C`, `%` → `%25`) because it is passed verbatim in the login query string.
   - `PROXY_USER` / `PROXY_PASS` — Basic-auth credentials if the NAS sits behind a reverse proxy; leave empty if not.
3. Never commit or share `credentials/nas.env` — only the `.example` template.

You can read the credentials file with the `filesystem` tool to verify it exists before running commands, but do not print the password values into chat.

## Usage

Run all commands with the `exec` tool, invoking the script as `skills/nas-filestation/tools/nas.sh`:

```bash
# List top-level shared folders
skills/nas-filestation/tools/nas.sh shares

# List a folder (JSON with size and time info)
skills/nas-filestation/tools/nas.sh list /homes/api

# Upload a file (optionally renaming it)
skills/nas-filestation/tools/nas.sh upload /path/to/local.pdf /teams/Reports
skills/nas-filestation/tools/nas.sh upload /path/to/local.pdf /teams/Reports renamed.pdf

# Create a folder (parents created implicitly on upload)
skills/nas-filestation/tools/nas.sh mkdir /teams/Reports/2026

# Stat a single file
skills/nas-filestation/tools/nas.sh size /teams/Reports/file.pdf

# Delete a path recursively — DESTRUCTIVE, confirm with the user first
skills/nas-filestation/tools/nas.sh delete /teams/Reports/old
```

All commands return raw JSON on stdout. Parse it before presenting results to the user — don't dump raw JSON into chat.

## Behaviour and error handling

| Command | Notes |
|---|---|
| `upload` | Overwrites existing files by default; `create_parents=true` so missing destination folders are created. |
| `list` / `size` | Include `size` and `time` additional info. |
| `delete` | Recursive. **Always get explicit user approval before deleting anything.** |
| `mkdir` | Uses `SYNO.FileStation.CreateFolder`; fails if the folder already exists. |

- On login failure the script exits non-zero with `Login failed:` and the API response on stderr — report the failure, check the credentials file, and do not retry blindly.
- `set -euo pipefail` is on: any curl/auth failure aborts the command. If a task depends on NAS access, treat a non-zero exit as fatal and abort the task (do not continue with partial data).
- Downloads: FileStation has a dedicated download endpoint not wrapped by the script yet — if a download is needed, log in with the same auth pattern (`SYNO.FileStation.Download`, method=download) using `curl` with `-u "$PROXY_USER:$PROXY_PASS" -b "id=$SID"` after sourcing `credentials/nas.env`.

## Safety

- `delete` is irreversible. Confirm with the user and check for running jobs that may be writing to the target path first.
- Never echo `NAS_PASS` or `PROXY_PASS` into chat, logs, or task output.
- When sharing this skill with others, share the folder **without** `credentials/nas.env` — recipients generate their own from the `.example` template.
