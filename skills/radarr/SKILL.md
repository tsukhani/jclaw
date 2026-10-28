---
name: radarr
description: >-
  Download movies via Radarr API with automated download monitoring and import handling.
metadata:
  version: "1.4.0"
  author: Tarun Sukhani
  jclaw-icon: "📺"
  jclaw-tools: exec subagent_spawn subagent_yield message filesystem
  jclaw-commands: ""
---
# Radarr Movie Download (API-Only)

Pure API workflow — no browser needed. Main agent handles search/grab, sub-agents handle monitoring/import.

## Workflow Overview

```
User Request → MAIN AGENT: Search → Present releases       [turn 1 ends]

User Picks  → MAIN AGENT (single turn, no stopping):
                • Grab release → get torrent hash
                • message(action="send", "⬇️ Download started!")
                • subagent_spawn (monitor, async=true)
                • subagent_yield (runId)                    [turn 2 parks]

              MONITOR SUB-AGENT runs on its own VT:
                • Loop every 30s → message() progress updates
                • On completion → returns DOWNLOAD_COMPLETE

              Announce auto-resumes turn 2 (USER-role row):
                • MAIN AGENT sees DOWNLOAD_COMPLETE
                • subagent_spawn (import, sync)
                • Import sub-agent: scan + verify + cleanup
                • MAIN AGENT: "🍿 Ready to watch!"          [turn 2 ends]
```

**Key principle:** Main agent handles interactive parts. Sub-agents handle long-running tasks. **Grab and spawn are not separate turns — they run together; ending the turn after the grab is a bug, not a checkpoint.**

---

## Delivery (Automatic — no plumbing needed)

Call `message` with no `channel` or `target`. For per-channel routing, or when a `message` never arrives, read `skills/radarr/references/background.md` with `readFile`.

---

## Sub-Agent Completion Handling

### How it works
When an **async** sub-agent completes, JClaw posts a **SYSTEM message** to the main agent's conversation containing the sub-agent's return value:

```
Subagent completed (Radarr Monitor): DOWNLOAD_COMPLETE
```

**OR** if the torrent was cancelled:

```
Subagent completed (Radarr Monitor): TORRENT_CANCELLED
```

### What the main agent MUST do on every turn
When the main agent's next turn begins, it sees this SYSTEM message in its conversation history. **The LLM must explicitly check for sub-agent completion BEFORE answering user questions.** Follow this priority order:

1. **Scan for sub-agent SYSTEM messages** — look for messages containing `"Subagent completed"`
2. **If DOWNLOAD_COMPLETE** → immediately proceed to Phase 4 (spawn import sub-agent). Do NOT wait for the user to ask "is it done?"
3. **If TORRENT_CANCELLED** → stop; no import needed. Inform the user quietly if appropriate
4. **If the user asked something else** (e.g., "What's the weather?") → answer that **AND ALSO** act on the sub-agent completion immediately after

### Why this matters
If the user sends a message while the monitor is running (e.g., "What's the weather?"), the LLM's next turn sees **both** the user's question **AND** the SYSTEM announce. Without explicit ordering, the LLM might answer the user's question and **ignore the announce**, leaving the movie un-imported and the workflow stalled.

### Phase 4 trigger rules
```
IF announce message (USER or SYSTEM role) contains
   "Subagent completed (Radarr Monitor): DOWNLOAD_COMPLETE"
  AND (user hasn't sent a new request OR user sent an unrelated message)
THEN, in the SAME turn, in this order:
  1. message(action="send", message="📥 Import starting — moving files into the library…")
  2. subagent_spawn(<Phase 4 import task>, runTimeoutSeconds=180)   ← sync, blocking
  3. When the sync spawn returns IMPORT_COMPLETE|<details>, parse it
     and emit Phase 5 via message(action="send", …) — see Phase 5 below.
END IF
```

**⚠️ ALL user-facing output uses `message(action="send", ...)`, never plain
assistant text.** Plain text on a yield-resumed turn does not reliably
surface in non-web channels (Telegram, Slack, WhatsApp) and is brittle
even in web. The `message` tool is the canonical user-facing path for
every workflow phase.

---

## Credentials

```bash
# Agent exec runs with CWD = workspace root (workspace/<agent>/), so the
# credentials file is a known workspace-relative path. Do NOT use
# $BASH_SOURCE — exec runs via `sh -c "…"`, where $BASH_SOURCE[0] is
# empty and a SCRIPT_DIR-style lookup collapses to the CWD, missing the
# skill subdirectory entirely.
CREDS="skills/radarr/credentials/radarr.json"
RADARR_URL=$(jq -r '.radarr.url' "$CREDS")
RADARR_API_KEY=$(jq -r '.radarr.apiKey' "$CREDS")
TRANSMISSION_URL=$(jq -r '.torrents.url' "$CREDS")
TRANSMISSION_USER=$(jq -r '.torrents.username' "$CREDS")
TRANSMISSION_PASS=$(jq -r '.torrents.password' "$CREDS")
NAS_URL=$(jq -r '.synology.url' "$CREDS")
NAS_USER=$(jq -r '.synology.username' "$CREDS")
NAS_PASS=$(jq -r '.synology.password' "$CREDS")

# trpc() (curl + RPC; transmission-remote is not allowlisted) is in skills/radarr/references/release-swap.md: read it before a release swap.
```

---

## Phase 1: Search & Present (Main Agent - Direct)

When user requests a movie, **main agent does this directly** (no sub-agent):

### 1a. Search Radarr

```bash
# Search for movie
curl -s "$RADARR_URL/api/v3/movie/lookup?term=MOVIE+NAME" -H "X-Api-Key: $RADARR_API_KEY" | jq '.[0:3] | .[] | {tmdbId, title, year, overview}'
```

### 1b. Check if in Library

```bash
# Check library by tmdbId
curl -s "$RADARR_URL/api/v3/movie" -H "X-Api-Key: $RADARR_API_KEY" | jq '.[] | select(.tmdbId == TMDB_ID)'
```

**If IN LIBRARY with hasFile=true:** Tell user movie is already available, show details.

**If IN LIBRARY with hasFile=false or NOT in library:** Continue to add/get releases.

### 1c. Add to Radarr (if needed)

```bash
ROOT_PATH=$(curl -s "$RADARR_URL/api/v3/rootfolder" -H "X-Api-Key: $RADARR_API_KEY" | jq -r '.[0].path')
QUALITY_ID=$(curl -s "$RADARR_URL/api/v3/qualityprofile" -H "X-Api-Key: $RADARR_API_KEY" | jq '.[0].id')

curl -s -X POST "$RADARR_URL/api/v3/movie" \
  -H "X-Api-Key: $RADARR_API_KEY" -H "Content-Type: application/json" \
  -d '{
    "title": "TITLE",
    "year": YEAR,
    "tmdbId": TMDB_ID,
    "rootFolderPath": "'"$ROOT_PATH"'",
    "qualityProfileId": '"$QUALITY_ID"',
    "monitored": true,
    "addOptions": {"searchForMovie": false}
  }'
```

### 1d. Get Releases & Present Options

```bash
MOVIE_ID=$(curl -s "$RADARR_URL/api/v3/movie" -H "X-Api-Key: $RADARR_API_KEY" | jq '.[] | select(.tmdbId == TMDB_ID) | .id')
RELEASES=$(curl -s "$RADARR_URL/api/v3/release?movieId=$MOVIE_ID" -H "X-Api-Key: $RADARR_API_KEY")
```

**Sorting priority (within each resolution group):**
1. **Age** (youngest first) — use `age` field (days since publish)
2. **Provider** (favor Torznab) — Torznab indexers rank above piratebay/others
3. **Seeders** (most seeders) — tiebreaker

```bash
# Sort releases: age ASC, then Torznab first, then seeders DESC
echo "$RELEASES" | jq '
  [.[] | select(.quality.quality.resolution == 1080)] |
  sort_by(.age, (if .indexer == "Torznab" then 0 else 1 end), -.seeders)'
```

**Present to user (format):**

**⚠️ Numbering rule:** Every release line MUST start with the marker
`[N]` (square brackets around the sequence number) followed by a space.
Numbering is **continuous across resolution groups** — 1080p uses
`[1]..[N]`, then 4K continues at `[N+1]`. The user picks by number, so
a missing or reset number breaks selection.

**Why square brackets, not `1)` or `1.`:** the web chat renders messages
through a markdown parser. `1)` and `1.` are both markdown ordered-list
syntax and get parsed into a `<ol>` element whose markers may be
hidden, restyled, or renumbered by the chat's CSS. `[1]` is plain text
to every markdown flavor — renders identically in web, Telegram,
Slack, and WhatsApp.

```
🎬 **Movie Title** (Year)

📖 **Synopsis:** [overview]

📺 **1080p** (sorted by 📅 age → 🛰️ provider → 🌱 seeders)
[1] 🟦 Bluray-1080p • 8 GB • 📅 2d • 🌱 150 • 🛰️ Torznab • 🏷️ SbR
[2] 🟦 WEBDL-1080p • 3 GB • 📅 5d • 🌱 120 • 🛰️ Torznab • 🏷️ FLUX
[3] 🟦 WEBDL-1080p • 4 GB • 📅 5d • 🌱 90 • 🛰️ piratebay • 🏷️ YIFY
[4] 🟦 Bluray-1080p • 17 GB • 📅 7d • 🌱 80 • 🛰️ IPTorrents • 🏷️ KNiVES
[5] 🟦 WEBRip-1080p • 1.7 GB • 📅 9d • 🌱 6 ⚠️ slow • 🛰️ piratebay • 🏷️ LAMA

🎥 **4K / 2160p** (sorted by 📅 age → 🛰️ provider → 🌱 seeders)
[6] 🟪 WEBDL-2160p • 18 GB • 📅 1d • 🌱 70 • 🛰️ Torznab • 🏷️ FLUX
[7] 🟪 Remux-2160p • 35 GB • 📅 3d • 🌱 55 • 🛰️ Torznab • 🏷️ MainFrame
[8] 🟪 WEBDL-2160p • 14 GB • 📅 5d • 🌱 35 • 🛰️ IPTorrents • 🏷️ NorTekst
[9] 🟪 Remux-2160p • 54 GB • 📅 8d • 🌱 25 • 🛰️ IPTorrents • 🏷️ playBD
[10] 🟪 Bluray-2160p • 37 GB • 📅 12d • 🌱 22 • 🛰️ IPTorrents • 🏷️ SPHD

🏆 Quick picks: "best 1080" or "best 4k" or number (1-10)
```

**User selection:** the user may say `"1"`, `"#1"`, `"[1]"`, or even
`"pick 1"` — all map to the entry tagged `[1]`. Don't require they
type the brackets.

**Anti-pattern (do NOT do this):**
- ❌ Use `1)` `2)` `3)` instead of `[1] [2] [3]` — the web chat
  markdown renderer treats `1)` as an ordered list, the CSS hides the
  markers, and 1080p entries appear without any visible number.
- ❌ Use `1.` `2.` `3.` — same problem (also markdown ordered list
  syntax).
- ❌ Drop the marker on 1080p entries while keeping it on 4K. The 4K
  entries will start at `[6]` with no 1-5 visible to the user, and
  "pick #3" becomes unanswerable.
- ❌ Restart numbering at 1 inside the 4K group (so 1080p has [1]-[5]
  AND 4K has [1]-[5]). Numbering must be continuous across the whole
  list.

**Quick picks line:** the number range in the parenthetical must match
the actual count shown (`(1-10)` for ten entries, `(1-7)` if you only
show seven, etc.). Don't hardcode `(1-10)` regardless of what shipped.

**Age display format:**
- `< 1d` for releases less than 24 hours old
- `Xd` for days (e.g., `2d`, `14d`)
- `Xw` for 7+ days (e.g., `2w` for 14 days) — optional, days is fine too

**⚠️ Low-seeder warning:** If a release has < 10 seeders, append ` ⚠️ slow` immediately after the seeder count — see entry #5 in the example above (`🌱 6 ⚠️ slow`). When the user picks a low-seeder release, warn them: "Only X seeders — might be slow. Want to go ahead or pick a faster one?"

**Store for later:** movie_id, title, year, synopsis, releases array

---

## Phase 2: Grab Release (Main Agent - Direct)

When user picks a release:

### 2a. Grab the Release

```bash
# Find release by index and grab
RELEASE=$(echo "$RELEASES" | jq '.['$INDEX']')
GUID=$(echo "$RELEASE" | jq -r '.guid')
INDEXER_ID=$(echo "$RELEASE" | jq '.indexerId')

curl -s -X POST "$RADARR_URL/api/v3/release" \
  -H "X-Api-Key: $RADARR_API_KEY" -H "Content-Type: application/json" \
  -d '{"guid": "'"$GUID"'", "indexerId": '$INDEXER_ID'}'
```

### 2b. Get Torrent Hash

```bash
sleep 3

# Try queue first
QUEUE=$(curl -s "$RADARR_URL/api/v3/queue" -H "X-Api-Key: $RADARR_API_KEY")
QUEUE_ITEM=$(echo "$QUEUE" | jq '.records[] | select(.movieId == '$MOVIE_ID')')

if [ -n "$QUEUE_ITEM" ]; then
  TORRENT_HASH=$(echo "$QUEUE_ITEM" | jq -r '.downloadId')
  DOWNLOAD_TITLE=$(echo "$QUEUE_ITEM" | jq -r '.title')
else
  # Check history
  HISTORY=$(curl -s "$RADARR_URL/api/v3/history/movie?movieId=$MOVIE_ID" -H "X-Api-Key: $RADARR_API_KEY")
  TORRENT_HASH=$(echo "$HISTORY" | jq -r '.[0].data.torrentInfoHash')
  DOWNLOAD_TITLE=$(echo "$HISTORY" | jq -r '.[0].sourceTitle')
fi

MOVIE_FOLDER=$(curl -s "$RADARR_URL/api/v3/movie/$MOVIE_ID" -H "X-Api-Key: $RADARR_API_KEY" | jq -r '.path | split("/") | .[-1]')
```

### 2c. Tell User & Spawn Monitor (SAME TURN — do not stop)

**⚠️ CRITICAL: Phase 2c, Phase 3 spawn, and the Phase 3 yield ALL happen in the same LLM turn as the grab. Do NOT end your turn after the grab — the monitor MUST be spawned before the turn ends, or no progress updates will ever reach the user.**

Sequence inside this single turn, in order:

1. Send the kickoff message via the `message` tool (do NOT just emit assistant text — the user is watching a channel, the message tool routes there automatically):
   ```
   message(action="send", message="⬇️ Download started! I'll update you on progress.")
   ```
2. **Immediately** call `subagent_spawn` with the monitor task body from Phase 3. Use `async: true` and `runTimeoutSeconds: 86400`. Capture the returned `run_id`.
3. **Immediately** call `subagent_yield(runId=<run_id from step 2>, timeoutSeconds=0)`. The `timeoutSeconds: 0` disables the yield watchdog so the parent waits for the monitor without a separate clock racing it; the child is still bounded by its spawn `runTimeoutSeconds`.

**Anti-pattern (this is what's breaking the workflow):**
- ❌ Emit "Download started!" as assistant text and end the turn → monitor never spawns, no updates, workflow stalls.
- ❌ Spawn the monitor without yielding → the announce on completion does not resume the turn (see Phase 4 trigger rules above).

**Correct pattern:**
- ✅ One LLM turn: `message` → `subagent_spawn` → `subagent_yield` → turn ends silently. Monitor runs. When it terminates, the announce flips USER-role and re-enters the main agent's loop with `DOWNLOAD_COMPLETE` visible, triggering Phase 4.

### 2d. Release Swap (User wants to switch releases mid-download)

If user asks to switch to a different release while a download is in progress:

**Main agent handles all of this directly (no sub-agent):**

Read `skills/radarr/references/release-swap.md` with `readFile` first; it holds `trpc()` and the four commented steps.

**Key points:**
- Always cancel the old torrent BEFORE grabbing the new one
- Get the new hash from the **queue** (not history — history still shows the old grab)
- Wait 5s after grabbing for the new torrent to appear in the queue
- Spawn a fresh monitor sub-agent with the new hash + new download title
- The old monitor sub-agent will detect the torrent is gone (RPC `torrent-get` returns an empty `torrents` array for the missing hash) and exit silently with TORRENT_CANCELLED — no stale progress messages

**⚠️ Low-seeder warning:** When presenting releases, warn the user if a release has < 10 seeders — it may be very slow. Suggest higher-seeded alternatives.

---

## Phase 3: Monitor Download (continues in SAME TURN as Phase 2)

**This is not a separate user-prompted phase — it executes as steps 2 and 3 inside the Phase 2c turn.** The main agent spawns async so the monitor runs on its own virtual thread, then yields so the parent turn parks until the announce arrives:

In this same turn, read `skills/radarr/references/monitor-task.md` with `readFile`, then pass its `task` verbatim with the placeholders filled:

```
subagent_spawn with:
  task: [the WHOLE task text from monitor-task.md, placeholders filled — never the file name]

  async: true
  runTimeoutSeconds: 86400      ← idle budget; subagent.maxWallClockSeconds caps the run
```

**Immediately after the spawn returns, yield on the run** so the main
agent's turn auto-resumes when the child terminates. Pass
`timeoutSeconds: 0` to disable the yield watchdog — the monitor is
already bounded by `subagent.maxWallClockSeconds`, so
the parent just waits for it without a second clock racing it:

```
subagent_yield with:
  runId: [the run_id from subagent_spawn above]
  timeoutSeconds: 0   ← no yield watchdog; the child's own limits bound it
```

If the yield timed out or the turn never resumed, read `skills/radarr/references/background.md` with `readFile`.

---

## Phase 4: Import & Cleanup (Main Agent → Import Sub-Agent)

**Main agent spawns the import sub-agent.** Same automatic delivery-context inheritance as Phase 3 — no channel/target plumbing needed:

In this same turn, read `skills/radarr/references/import-task.md` with `readFile`, then pass its `task` verbatim with the placeholders filled:

```
subagent_spawn with:
  task: [the WHOLE task text from import-task.md, placeholders filled — never the file name]

  runTimeoutSeconds: 180
```

---

## Phase 5: Final Notification (Main Agent — via `message` tool)

When the Phase 4 sync spawn returns `IMPORT_COMPLETE|<quality>|<size_gb>|<runtime>|<audio>|<path>`, parse the pipe-delimited fields and emit the success notification via the `message` tool — **never as plain assistant text**:

```
message(action="send", message="🍿 **<Title>** (<Year>) is ready to watch!

📖 **Synopsis:** <synopsis stored from Phase 1>

📂 **File Details:**
🎛️ Quality: <quality>
📦 Size: <size_gb> GB
🔊 Audio: <audio>
⏱️ Runtime: <runtime>

📁 Location: <path>

Enjoy the movie! 🎬")
```

If this notice never reached the user, read `skills/radarr/references/background.md` with `readFile`.

**Failure path:** if Phase 4 returns `IMPORT_FAILED|<reason>`, send a
failure message instead:

```
message(action="send", message="⚠️ Import failed for **<Title>**: <reason>. The download is still on disk; you can retry by asking 'reimport <Title>' or investigate manually.")
```

---

## Summary

For the phase table and a diagram of the whole run, read `skills/radarr/references/flow.md` with `readFile` when unsure which phase you are in.

**Main agent stays in control. Interactive parts are direct. Only long-running tasks use sub-agents.**
