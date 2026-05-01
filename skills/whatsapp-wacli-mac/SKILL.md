---
name: whatsapp-wacli-mac
description: Use wacli (WhatsApp CLI) to sync, search, send messages, manage contacts/groups, and backfill chat history. REQUIRES AUTHENTICATION FIRST. macOS ARM64 binary.
version: 1.2.0
tools: [exec]
commands: [wacli]
icon: 🍎
---
# Commands

| Command | Description |
|---------|-------------|
| `auth` | Authenticate, check status, logout |
| `send` | Send text messages or files |
| `messages` | List, search, show, and get context around messages |
| `chats` | List and search chats |
| `contacts` | Search, show, and alias contacts |
| `groups` | List, info, rename, join, leave, and manage group invites/participants |
| `sync` | One-time or continuous sync with WhatsApp |
| `history` | Backfill older messages from the primary device |
| `media` | Download media from messages |
| `doctor` | Run diagnostics |

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
brew install steipete/tap/wacli
cp "$(brew --prefix)/bin/wacli" ./skills/whatsapp-wacli-mac/tools/wacli
chmod +x ./skills/whatsapp-wacli-mac/tools/wacli
```

### Option B: Build from source (requires Go 1.25+ with CGO enabled)

```bash
git clone --depth 1 https://github.com/steipete/wacli /tmp/wacli-src \
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

## Authentication Flow

The skill uses a `.authenticated` marker file in the skill directory (`./skills/whatsapp-wacli-mac/.authenticated`) to track authentication state and optimize subsequent calls.

### Authentication Decision Flow

Before executing any wacli command, follow this logic:

1. **Check for `.authenticated` marker** (`test -f ./skills/whatsapp-wacli-mac/.authenticated`)
   - **If EXISTS:** Proceed directly to execute the requested command
   - **If command fails with auth error:** Remove the marker file (`rm -f ./skills/whatsapp-wacli-mac/.authenticated`) and go to Step 2

2. **If `.authenticated` does NOT exist:** Check auth status via wacli
   ```bash
   ./skills/whatsapp-wacli/tools/wacli auth status
   ```
   - **If status shows authenticated:** Create marker file and execute command
     ```bash
     touch ./skills/whatsapp-wacli-mac/.authenticated
     # then execute requested command
     ```
   - **If status shows NOT authenticated:** Go to Step 3

3. **Full authentication flow:** Run interactive auth and create marker on success
   ```bash
   ./skills/whatsapp-wacli/tools/wacli auth
   # User scans QR code...
   # On success:
   touch ./skills/whatsapp-wacli-mac/.authenticated
   ```

### Complete Auth Logic Example

```python
# Step 1: Check if .authenticated marker exists
result = exec({"command": "test -f ./skills/whatsapp-wacli-mac/.authenticated && echo 'marker_exists' || echo 'no_marker'"})

if result contains 'marker_exists':
    # Proceed with command
    result = exec({"command": "./skills/whatsapp-wacli/tools/wacli <REQUESTED_COMMAND>"})
    
    # If command fails due to auth issues
    if result contains 'unauthorized' or 'not authenticated':
        exec({"command": "rm -f ./skills/whatsapp-wacli-mac/.authenticated"})
        # Fall through to check status
    else:
        # Command succeeded, return result
        return result

# Step 2: Check actual auth status
status = exec({"command": "./skills/whatsapp-wacli/tools/wacli auth status --json"})

if status contains '"status": "authenticated"':
    # User is already authenticated, create marker and execute
    exec({"command": "touch ./skills/whatsapp-wacli-mac/.authenticated"})
    result = exec({"command": "./skills/whatsapp-wacli/tools/wacli <REQUESTED_COMMAND>"})
    return result
else:
    # Step 3: Need to authenticate
    print("Please scan the QR code with WhatsApp to authenticate...")
    auth_result = exec({"command": "./skills/whatsapp-wacli/tools/wacli auth", "timeout": 120})
    
    # Verify auth succeeded
    verify = exec({"command": "./skills/whatsapp-wacli/tools/wacli auth status --json"})
    if verify contains '"status": "authenticated"':
        exec({"command": "touch ./skills/whatsapp-wacli-mac/.authenticated"})
        # Now execute the requested command
        result = exec({"command": "./skills/whatsapp-wacli/tools/wacli <REQUESTED_COMMAND>"})
        return result
    else:
        return "Authentication failed. Please try again."
```

### Authentication Management

**Check auth status:**
```bash
./skills/whatsapp-wacli/tools/wacli auth status
```

**Logout (also removes `.authenticated` marker):**
```bash
./skills/whatsapp-wacli/tools/wacli auth logout && rm -f ./skills/whatsapp-wacli-mac/.authenticated
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
|----------|----------|
| `WACLI_DEVICE_LABEL` | Linked device label shown in WhatsApp |
| `WACLI_DEVICE_PLATFORM` | Override device platform (default: CHROME) |

---

## Exec Tool Examples

```python
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
2. **Auth flow:** The skill follows a three-step authentication flow (marker check → status check → interactive auth) to optimize subsequent calls
3. **`.authenticated` marker file** — stored in `./skills/whatsapp-wacli-mac/.authenticated`; created after successful auth to bypass checks on future calls
4. **Auth failure handling** — If a command fails due to auth issues, the marker is automatically removed and the flow restarts
5. **Primary device must be online** — phone needs WhatsApp running for sync/send
6. **Store lock** — only one wacli process can run at a time. If you get a lock error, wait or kill the other process
7. **Rate limits** — respect WhatsApp's sending limits
8. **Third-party tool** — not affiliated with WhatsApp/Meta; uses WhatsApp Web protocol