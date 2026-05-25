---
name: radarr-library
icon: 📺
description: Check and clean the Radarr movie library. Find duplicates, search library, get stats. Delete only after user approval.
version: 1.0.3
author: main
tools: [exec]
commands: []
---
# Radarr Library Checker & Cleaner

A utility skill for managing the Radarr movie library.

## ⚠️ OWNER ONLY

**This skill is restricted to [PERSONAL_NAME] (owner) only.**

Before executing ANY command in this skill, verify the user:
- **Telegram ID:** `[TELEGRAM_ID]`
- **Slack ID:** `[SLACK_ID]`

**If the user is NOT [PERSONAL_NAME]:**
> ❌ Sorry, the library checker is restricted to the owner only.

**Do not proceed with any library operations for other users.**

---

## Credentials

```bash
# Agent exec runs with CWD = workspace root, so the credentials file
# (shared with the radarr skill) sits at a known workspace-relative
# path. Do NOT use $BASH_SOURCE — exec is `bash -c "…"`, where
# $BASH_SOURCE[0] is empty and would mis-resolve SCRIPT_DIR.
CREDS="skills/radarr/credentials/radarr.json"
RADARR_URL=$(jq -r '.radarr.url' "$CREDS")
RADARR_API_KEY=$(jq -r '.radarr.apiKey' "$CREDS")
```

---

## 1. Check if Movie Exists

**Trigger phrases:** "Do I have [movie]?", "Is [movie] in my library?", "Check if I have [movie]"

### Steps:
```bash
curl -s "$RADARR_URL/api/v3/movie" \
  -H "X-Api-Key: $RADARR_API_KEY" | \
  jq '.[] | select(.title | test("[SEARCH_TERM]"; "i")) | {title, year, hasFile, path, quality: .movieFile.quality.quality.name, size_gb: (.sizeOnDisk/1073741824 | . * 100 | floor / 100)}'
```

### Response format:
- **Found:** "✅ Yes! You have **[Movie] ([Year])** — [Quality], [Size] GB"
- **Not found:** "❌ **[Movie]** is not in your library. Want me to download it?"

---

## 2. Search Library

**Trigger phrases:** "What [genre] movies do I have?", "List my [actor] movies", "Search library for [term]"

### Steps:
```bash
curl -s "$RADARR_URL/api/v3/movie" \
  -H "X-Api-Key: $RADARR_API_KEY" | \
  jq '[.[] | select(.title | test("[SEARCH_TERM]"; "i")) | {title, year, hasFile, size_gb: (.sizeOnDisk/1073741824 | . * 100 | floor / 100)}]'
```

### Response format:
```
🔍 **Found [X] movies matching "[term]":**

1. [Movie 1] ([Year]) — [Size] GB
2. [Movie 2] ([Year]) — [Size] GB
...
```

---

## 3. Library Stats

**Trigger phrases:** "Library stats", "How many movies do I have?", "Library size"

### Steps:
```bash
curl -s "$RADARR_URL/api/v3/movie" \
  -H "X-Api-Key: $RADARR_API_KEY" | \
  jq '{
    total_movies: length,
    with_files: [.[] | select(.hasFile == true)] | length,
    missing_files: [.[] | select(.hasFile == false)] | length,
    total_size_gb: ([.[] | .sizeOnDisk] | add / 1073741824 | . * 100 | floor / 100)
  }'
```

### Response format:
```
📊 **Library Stats:**

| Metric | Value |
|--------|-------|
| Total Movies | X |
| With Files | X |
| Missing Files | X |
| Total Size | X GB |
```

---

## 4. Find Duplicates

**Trigger phrases:** "Find duplicates", "Check for duplicate movies", "Any duplicates in library?"

### Definition of Duplicate:
- Same movie title (case-insensitive)
- Same year
- Multiple entries OR multiple quality versions

### Steps:

```bash
# Find movies with same title+year
curl -s "$RADARR_URL/api/v3/movie" \
  -H "X-Api-Key: $RADARR_API_KEY" | \
  jq 'group_by(.title | ascii_downcase) | map(select(length > 1)) | .[] | {title: .[0].title, entries: [.[] | {id, year, hasFile, quality: .movieFile.quality.quality.name, size_gb: (.sizeOnDisk/1073741824 | . * 100 | floor / 100), path}]}'
```

### Response format:
```
🔍 **Duplicate Check Results:**

**[Movie Title]** — [X] entries found:

| # | Year | Quality | Size | Path |
|---|------|---------|------|------|
| 1 | 2024 | BluRay-1080p | 4.5 GB | /video/Movies/... |
| 2 | 2024 | WEBRip-720p | 1.2 GB | /video/Sync/... |

**Recommendation:** Keep #1 (higher quality), delete #2

---

⚠️ **No automatic deletion!** Reply with which entries to delete:
- "Delete [Movie] #2"
- "Delete all lower quality duplicates"
- "Keep all"
```

---

## 5. Clean Duplicates (User-Approved Only)

**⚠️ CRITICAL: NEVER auto-delete. Always show duplicates first and wait for user approval.**

### After user approves deletion:

```bash
# Delete specific movie by ID
curl -s -X DELETE "$RADARR_URL/api/v3/movie/[MOVIE_ID]?deleteFiles=true&addImportExclusion=false" \
  -H "X-Api-Key: $RADARR_API_KEY"
```

### Response format:
```
🗑️ **Deleted:**
- [Movie] ([Year]) — [Quality] from [Path]

✅ Kept:
- [Movie] ([Year]) — [Quality] (best version)
```

---

## 6. Find Missing Files

**Trigger phrases:** "Find missing movies", "Movies without files", "What's missing?"

### Steps:
```bash
curl -s "$RADARR_URL/api/v3/movie" \
  -H "X-Api-Key: $RADARR_API_KEY" | \
  jq '[.[] | select(.hasFile == false) | {id, title, year, monitored}] | sort_by(.title)'
```

### Response format:
```
📭 **Movies Without Files:** [X] found

1. [Movie 1] ([Year]) — Monitored: ✅/❌
2. [Movie 2] ([Year]) — Monitored: ✅/❌
...

**Options:**
- "Search for [movie]" — Find and download
- "Remove [movie]" — Delete from library
- "Remove all unmonitored missing" — Clean up
```

---

## Key Principles

1. **Never auto-delete** — Always show user what will be deleted first
2. **Confirm before action** — User must explicitly approve deletions
3. **Show recommendations** — Suggest which version to keep (higher quality, larger file)
4. **Preserve data** — When in doubt, keep rather than delete
