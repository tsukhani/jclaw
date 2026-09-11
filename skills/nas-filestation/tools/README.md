# nas.sh — Synology FileStation helper

Wraps the Synology FileStation HTTP API in curl. No MCP server needed.

## Config

Reads `../credentials/nas.env` (relative to the script). Environment variables
of the same names override the file; `NAS_ENV_FILE` overrides the file path.
Copy `../credentials/nas.env.example` to `../credentials/nas.env` to configure.

## Commands

| Command | Arguments | Description |
|---|---|---|
| `upload` | `<local-file> <nas-dest-dir> [nas-filename]` | Upload a file; overwrites, creates parent folders |
| `list` | `<nas-path>` | List a folder with size/time info (JSON) |
| `shares` | — | List top-level shared folders (JSON) |
| `mkdir` | `<nas-path>` | Create a folder |
| `delete` | `<nas-path>` | Delete recursively — destructive, confirm first |
| `size` | `<nas-path>` | Stat a single file (JSON) |

## Requirements

- `bash`, `curl`, `node` (for parsing the login response)
- Network access to the NAS URL
