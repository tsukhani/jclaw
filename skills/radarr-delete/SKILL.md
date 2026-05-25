---
name: radarr-delete
icon: 📺
description: Delete movies from Radarr library. Owner-only with full verification and cleanup.
version: 1.0.5
author: main
tools: [exec]
commands: []
---
# Radarr Movie Delete Workflow

**⚠️ RESTRICTED: This workflow is ONLY available to [PERSONAL_NAME] (owner ID: [OWNER_ID])**

When a user requests to delete a movie, follow this workflow.

## Credentials

```bash
# Agent exec runs with CWD = workspace root, so the credentials file
# (shared with the radarr skill) sits at a known workspace-relative
# path. Do NOT use $BASH_SOURCE — exec is `bash -c "…"`, where
# $BASH_SOURCE[0] is empty and would mis-resolve SCRIPT_DIR.
CREDS="skills/radarr/credentials/radarr.json"
RADARR_URL=$(jq -r '.radarr.url' "$CREDS")
RADARR_API_KEY=$(jq -r '.radarr.apiKey' "$CREDS")
TRANSMISSION_URL=$(jq -r '.torrents.url' "$CREDS")
TRANSMISSION_USER=$(jq -r '.torrents.username' "$CREDS")
TRANSMISSION_PASS=$(jq -r '.torrents.password' "$CREDS")
SYNOLOGY_URL=$(jq -r '.synology.url' "$CREDS")
SYNOLOGY_USER=$(jq -r '.synology.username' "$CREDS")
SYNOLOGY_PASS=$(jq -r '.synology.password' "$CREDS")
```

---

## Authorization Check

**Before proceeding, verify the user is [PERSONAL_NAME]:**
- Telegram ID: `[OWNER_ID]`
- Slack ID: `[SLACK_ID]`

If not authorized:
> ❌ Movie deletion is restricted to the owner only.

---

## Delete Workflow

### Step 1: Search for Movie in Library

**📢 Tell user:** "🔍 Searching for '[Movie Title]' in library..."

```bash
curl -s "$RADARR_URL/api/v3/movie" \
  -H "X-Api-Key: $RADARR_API_KEY" | \
  jq '.[] | select(.title | test("[search term]"; "i")) | {id, title, year, path, hasFile, sizeOnDisk: (.sizeOnDisk/1073741824 | . * 100 | floor / 100)}'
```

- **Multiple matches:** List all and ask user to specify
- **No matches:** "❌ Movie '[Title]' not found in library."

### Step 2: Confirm Deletion

**📢 Tell user:**
```
🎬 Found: [Movie Title] ([Year])
📁 Path: [path]
📦 Size: [X.XX] GB
🗂️ Has File: [Yes/No]

⚠️ Are you sure you want to delete this movie? This will:
- Remove from Radarr library
- Delete movie files from disk
- Remove any associated torrents

Reply "yes" to confirm deletion.
```

**Do NOT proceed until user confirms with "yes"!**

### Step 3: Pre-Deletion Checks

#### 3a. Check Transmission for Active Torrent

```bash
SESSION=$(curl -s -u "$TRANSMISSION_USER:$TRANSMISSION_PASS" "$TRANSMISSION_URL/transmission/rpc" 2>&1 | grep -oP 'X-Transmission-Session-Id: \K[^\<]+')

curl -s -u "$TRANSMISSION_USER:$TRANSMISSION_PASS" "$TRANSMISSION_URL/transmission/rpc" \
  -H "X-Transmission-Session-Id: $SESSION" \
  -d '{"method":"torrent-get","arguments":{"fields":["name","hashString","percentDone"]}}' | \
  jq '.arguments.torrents[] | select(.name | test("[movie title]"; "i"))'
```

**If torrent found:** Remove it first
```bash
curl -s -u "$TRANSMISSION_USER:$TRANSMISSION_PASS" "$TRANSMISSION_URL/transmission/rpc" \
  -H "X-Transmission-Session-Id: $SESSION" \
  -d '{"method":"torrent-remove","arguments":{"ids":["HASH"],"delete-local-data":true}}'
```

**📢 Tell user:** "🗑️ Removed torrent from download client..."

#### 3b. Check Radarr Download Queue

```bash
curl -s "$RADARR_URL/api/v3/queue?includeMovie=true" \
  -H "X-Api-Key: $RADARR_API_KEY" | \
  jq '.records[] | select(.movieId == MOVIE_ID) | {id, title, status}'
```

**If in queue:** Remove from queue first
```bash
curl -s -X DELETE "$RADARR_URL/api/v3/queue/[QUEUE_ID]?removeFromClient=true&blocklist=false" \
  -H "X-Api-Key: $RADARR_API_KEY"
```

**📢 Tell user:** "🗑️ Removed from download queue..."

### Step 4: Delete Movie via Radarr API

The Radarr UI requires a browser session that gets blocked by SSRF/redirect, so we use the API directly for deletion.

**Steps:**
1. Call the Radarr API delete endpoint **with** file removal:
```bash
curl -s -X DELETE "$RADARR_URL/api/v3/movie/[MOVIE_ID]?deleteFiles=true&addImportExclusion=false" \
  -H "X-Api-Key: $RADARR_API_KEY" \
  -w "\nHTTP_CODE: %{http_code}\n"
```

   Expected: `HTTP_CODE: 200`

2. Verify the movie is gone:
```bash
curl -s "$RADARR_URL/api/v3/movie/[MOVIE_ID]" \
  -H "X-Api-Key: $RADARR_API_KEY"
```
   Expected: `HTTP_CODE: 404` with body `Not Found`

**📢 Tell user:** "🗑️ Deleted movie from Radarr library..."

### Step 5: Verify Deletion

#### 5a. Verify Removed from Radarr
```bash
curl -s "$RADARR_URL/api/v3/movie/[MOVIE_ID]" \
  -H "X-Api-Key: $RADARR_API_KEY"
```
Should return 404 or error.

#### 5b. Verify Files Deleted from Library
```bash
#### 5b. Verify Files Deleted from Library

Radarr's `deleteFiles=true` should have removed the files from disk. Confirm by checking the local filesystem (if the agent has access to the same filesystem):

```bash
ls -ld [Movie Path] 2>/dev/null && echo "❌ Folder still exists" || echo "✅ Folder deleted"
```

If the folder still exists, delete it manually:
```bash
rm -rf [Movie Path]
```

#### 5c. Verify Downloads Folder Clean

Check the downloads/complete folder for any leftover files:

```bash
ls -la /downloads/complete/ | grep -i "[movie title]"
```

**If leftover files found:** Delete them
```

---

## Communication Summary

| Step | Message |
|------|---------|
| 1 | "🔍 Searching for '[Title]' in library..." |
| 2 | Show details + ask for confirmation |
| 3a | "🗑️ Removed torrent from download client..." (if applicable) |
| 3b | "🗑️ Removed from download queue..." (if applicable) |
| 4 | "🗑️ Deleted movie from Radarr library..." |
| 5 | (Silent verification) |
| 6 | "✅ Movie '[Title]' has been completely deleted!" |
