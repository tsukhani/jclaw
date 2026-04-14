---
name: whatsapp-wacli-mac
description: Use wacli (WhatsApp CLI) to sync, search, send messages, manage contacts/groups, and backfill chat history. REQUIRES AUTHENTICATION FIRST. macOS ARM64 binary.
version: 1.0.1
tools: [exec]
---

# WhatsApp wacli Skill

Binary location: `./skills/whatsapp-wacli-mac/tools/wacli`

Always use this path when invoking commands.

---

## Bootstrap (First-time setup)

**Before running any wacli command**, check whether the binary is present:

```bash
test -x ./skills/whatsapp-wacli-mac/tools/wacli && echo "ready" || echo "needs install"
```

If it prints `needs install`, build or install it using **one** of the methods below, then proceed.

### Option A: Homebrew (recommended — no Go toolchain required)

```bash
brew install [USERNAME]/tap/wacli
cp "$(brew --prefix)/bin/wacli" ./skills/whatsapp-wacli-mac/tools/wacli
chmod +x ./skills/whatsapp-wacli-mac/tools/wacli
```

### Option B: Build from source (requires Go 1.25+ with CGO enabled)

```bash
git clone --depth 1 https://github.com/[USERNAME]/wacli /tmp/wacli-src \
  && cd /tmp/wacli-src \
  && CGO_ENABLED=1 go build -tags sqlite_fts5 -o /tmp/wacli-bin ./cmd/wacli \
  && cp /tmp/wacli-bin ./skills/whatsapp-wacli-mac/tools/wacli \
  && chmod +x ./skills/whatsapp-wacli-mac/tools/wacli \
  && rm -rf /tmp/wacli-src /tmp/wacli-bin
```

> Note: builds natively for the current architecture (darwin/arm64 on Apple Silicon).

Once the binary is present, proceed to Authentication below.

---

## CRITICAL: Phone Number Format

Phone numbers must be digits only with country code, **without** the `+` prefix:
- Correct: `[PHONE_NUMBER]`
- Wrong: `+[PHONE_NUMBER]` (causes timeout/resolution errors)

For groups, use JID format: `[PHONE_NUMBER]@g.us`

Use `./skills/whatsapp-wacli/tools/wacli chats list` or `contacts search` to find JIDs.

---

## Authentication (Required First)

```bash
./skills/whatsapp-wacli/tools/wacli auth
```

This displays a QR code. The user must scan it with WhatsApp (Settings > Linked Devices > Link a Device). The command stays running while waiting for the scan and initial sync. Use a timeout of at least 120 seconds.

**Check auth status:**
```bash
./skills/whatsapp-wacli/tools/wacli auth status
```

**Logout:**
```bash
./skills/whatsapp-wacli/tools/wacli auth logout
```

**Diagnostics:**
```bash
./skills/whatsapp-wacli/tools/wacli doctor
```

---

## Sending Messages

### Text message
```bash
./skills/whatsapp-wacli/tools/wacli send text --to [PHONE_NUMBER] --message "Hello!"
```

### File (image/video/audio/document)
```bash
./skills/whatsapp-wacli/tools/wacli send file --to [PHONE_NUMBER] --file /path/to/photo.jpg
```

Optional flags:
- `--caption "Description"` — caption for images/videos/documents
- `--filename report.pdf` — override the display filename
- `--mime image/jpeg` — override auto-detected MIME type

---

## Messages

### List messages in a chat
```bash
./skills/whatsapp-wacli/tools/wacli messages list --chat [PHONE_NUMBER]@s.whatsapp.net --limit 20
```

Optional: `--after 2025-01-01` `--before 2025-12-31`

### Search messages (full-text)
```bash
./skills/whatsapp-wacli/tools/wacli messages search "keyword" --limit 20
```

Optional filters:
- `--chat <JID>` — restrict to a specific chat
- `--from <JID>` — restrict to a specific sender
- `--after 2025-01-01` / `--before 2025-12-31` — date range
- `--type image|video|audio|document` — media type filter

### Show a specific message
```bash
./skills/whatsapp-wacli/tools/wacli messages show --chat <JID> --id <MESSAGE_ID>
```

### Show context around a message
```bash
./skills/whatsapp-wacli/tools/wacli messages context --chat <JID> --id <MESSAGE_ID> --before 5 --after 5
```

---

## Chats

### List chats
```bash
./skills/whatsapp-wacli/tools/wacli chats list --limit 20
```

### Search chats by name/number
```bash
./skills/whatsapp-wacli/tools/wacli chats list --query "name or number"
```

### Show a specific chat
```bash
./skills/whatsapp-wacli/tools/wacli chats show --jid [PHONE_NUMBER]@s.whatsapp.net
```

---

## Contacts

### Search contacts
```bash
./skills/whatsapp-wacli/tools/wacli contacts search "name" --limit 20
```

### Show a specific contact
```bash
./skills/whatsapp-wacli/tools/wacli contacts show --jid [PHONE_NUMBER]@s.whatsapp.net
```

### Set a local alias for a contact
```bash
./skills/whatsapp-wacli/tools/wacli contacts alias set --jid [PHONE_NUMBER]@s.whatsapp.net --alias "Mom"
```

### Remove an alias
```bash
./skills/whatsapp-wacli/tools/wacli contacts alias rm --jid [PHONE_NUMBER]@s.whatsapp.net
```

### Refresh contacts from session store
```bash
./skills/whatsapp-wacli/tools/wacli contacts refresh
```

---

## Groups

### List groups
```bash
./skills/whatsapp-wacli/tools/wacli groups list
```

### Refresh groups (live from WhatsApp)
```bash
./skills/whatsapp-wacli/tools/wacli groups refresh
```

### Get group info
```bash
./skills/whatsapp-wacli/tools/wacli groups info --jid <GROUP_JID>
```

### Rename a group
```bash
./skills/whatsapp-wacli/tools/wacli groups rename --jid <GROUP_JID> --name "New Name"
```

### Join a group by invite code
```bash
./skills/whatsapp-wacli/tools/wacli groups join --code <INVITE_CODE>
```

### Leave a group
```bash
./skills/whatsapp-wacli/tools/wacli groups leave --jid <GROUP_JID>
```

### Manage group invite links
```bash
./skills/whatsapp-wacli/tools/wacli groups invite --jid <GROUP_JID>
```

### Manage group participants
```bash
./skills/whatsapp-wacli/tools/wacli groups participants --jid <GROUP_JID>
```

---

## Sync

### One-time sync (sync until idle, then exit)
```bash
./skills/whatsapp-wacli/tools/wacli sync --once
```

### Continuous sync (keeps running)
```bash
./skills/whatsapp-wacli/tools/wacli sync --follow
```

Optional flags:
- `--download-media` — download media files during sync
- `--refresh-contacts` — refresh contacts from session store
- `--refresh-groups` — refresh group list from WhatsApp
- `--idle-exit 30s` — exit after idle period (with `--once`)

---

## History Backfill

Request older messages for a specific chat from the primary device:

```bash
./skills/whatsapp-wacli/tools/wacli history backfill --chat <JID> --requests 10 --count 50
```

Flags:
- `--count 50` — messages per request (recommended: 50)
- `--requests 10` — number of requests to attempt
- `--wait 1m` — time to wait per request for a response
- `--idle-exit 5s` — exit after idle

**Note:** Best-effort. Primary device must be online. WhatsApp may not return full history.

---

## Media

### Download media from a message
```bash
./skills/whatsapp-wacli/tools/wacli media download --chat <JID> --id <MESSAGE_ID>
```

Optional: `--output /path/to/save`

---

## Global Flags (available on all commands)

| Flag | Purpose |
|------|---------|
| `--json` | Output JSON instead of human-readable text |
| `--store DIR` | Custom store directory (default: `~/.wacli`) |
| `--timeout 5m` | Command timeout for non-sync commands |

## Environment Variables

| Variable | Purpose |
|----------|---------|
| `WACLI_DEVICE_LABEL` | Linked device label shown in WhatsApp |
| `WACLI_DEVICE_PLATFORM` | Override device platform (default: CHROME) |

---

## Exec Tool Examples

```python
# Authenticate (use timeout=120 for QR scan)
exec({"command": "./skills/whatsapp-wacli/tools/wacli auth", "timeout": 120})

# Send text (NO + prefix on phone number)
exec({"command": "./skills/whatsapp-wacli/tools/wacli send text --to [PHONE_NUMBER] --message 'Hello!'"})

# Send file
exec({"command": "./skills/whatsapp-wacli/tools/wacli send file --to [PHONE_NUMBER] --file /tmp/report.pdf --caption 'Monthly report'"})

# Search messages (JSON for parsing)
exec({"command": "./skills/whatsapp-wacli/tools/wacli messages search 'invoice' --json"})

# List recent chats
exec({"command": "./skills/whatsapp-wacli/tools/wacli chats list --limit 10"})

# One-time sync
exec({"command": "./skills/whatsapp-wacli/tools/wacli sync --once", "timeout": 60})

# Check auth status
exec({"command": "./skills/whatsapp-wacli/tools/wacli auth status"})
```

---

## Important Notes

1. **Phone numbers: digits only, no + prefix** — e.g., `[PHONE_NUMBER]` not `+[PHONE_NUMBER]`
2. **Auth required first** — run `auth` and scan QR before any other command
3. **Primary device must be online** — phone needs WhatsApp running for sync/send
4. **Store lock** — only one wacli process can run at a time. If you get a lock error, wait or kill the other process
5. **Rate limits** — respect WhatsApp's sending limits
6. **Third-party tool** — not affiliated with WhatsApp/Meta; uses WhatsApp Web protocol