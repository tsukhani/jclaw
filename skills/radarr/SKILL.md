---
name: radarr
icon: 📺
description: Download movies via Radarr API with automated download monitoring and import handling.
author: main
tools: [exec, subagent_spawn, subagent_yield, message, filesystem]
commands: []
version: 1.3.0
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

Sub-agents inherit the parent conversation's delivery context automatically (JCLAW-327 AC-5). When the main agent calls `subagent_spawn`, the child Conversation gets the same `channelType` + `peerId` as the parent. The sub-agent then sends progress updates with:

```
message(action="send", message="⬇ Twisters: 45%")
```

— with no `channel` or `target`. The `message` tool reads the sub-agent's active conversation, walks up to the user-facing root, and routes via the right path:

| User started the chat in | Where the `message` lands |
|---|---|
| Telegram | The Telegram chat (via Bot API) |
| Slack | The Slack channel (via Web API) |
| WhatsApp | The WhatsApp thread (via Cloud API) |
| JClaw web UI | The in-app conversation (chat UI poller picks it up live) |

Skills no longer hand-roll `DELIVERY_CHANNEL` / `DELIVERY_TARGET` plumbing into the sub-agent task string — that was the pre-JCLAW-327 workaround and is no longer needed.

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
# $BASH_SOURCE — exec runs via `bash -c "…"`, where $BASH_SOURCE[0] is
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

# Transmission RPC helper — get a session ID, then make the actual call.
# Why curl + RPC and not transmission-remote? The transmission-remote
# binary is not in JClaw's shell allowlist; curl + the RPC HTTP endpoint
# are. Same workflow, no extra binary needed.
#
# Session-ID handshake: any first request to /transmission/rpc returns
# 409 with an X-Transmission-Session-Id response header. Capture that
# header and retry the actual request with it set. Wrapper function:
trpc() {
  # $1 = JSON body for the actual RPC call (e.g. {"method":"torrent-get","arguments":{...}})
  local body="$1"
  local sid
  sid=$(curl -s -u "$TRANSMISSION_USER:$TRANSMISSION_PASS" -D - \
    "$TRANSMISSION_URL/transmission/rpc" -d '{}' -o /dev/null \
    | grep -i '^X-Transmission-Session-Id:' | sed 's/.*: //' | tr -d '\r\n')
  curl -s -u "$TRANSMISSION_USER:$TRANSMISSION_PASS" \
    -H "X-Transmission-Session-Id: $sid" \
    -H "Content-Type: application/json" \
    "$TRANSMISSION_URL/transmission/rpc" -d "$body"
}
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
2. **Immediately** call `subagent_spawn` with the monitor task body from Phase 3. Use `async: true` and `runTimeoutSeconds: 86400` (24h ceiling for slow downloads). Capture the returned `run_id`.
3. **Immediately** call `subagent_yield(runId=<run_id from step 2>, timeoutSeconds=0)`. The `timeoutSeconds: 0` disables the yield watchdog so the parent waits for the monitor without a separate clock racing it; the child is still bounded by its spawn `runTimeoutSeconds`.

**Anti-pattern (this is what's breaking the workflow):**
- ❌ Emit "Download started!" as assistant text and end the turn → monitor never spawns, no updates, workflow stalls.
- ❌ Spawn the monitor without yielding → the announce on completion will be SYSTEM-role and invisible to the next turn (see Phase 4 trigger rules above).

**Correct pattern:**
- ✅ One LLM turn: `message` → `subagent_spawn` → `subagent_yield` → turn ends silently. Monitor runs. When it terminates, the announce flips USER-role and re-enters the main agent's loop with `DOWNLOAD_COMPLETE` visible, triggering Phase 4.

### 2d. Release Swap (User wants to switch releases mid-download)

If user asks to switch to a different release while a download is in progress:

**Main agent handles all of this directly (no sub-agent):**

```bash
# 1. Cancel the old torrent in Transmission (via RPC + curl, not transmission-remote — see trpc() in Credentials)
trpc '{"method":"torrent-remove","arguments":{"ids":["'"$OLD_TORRENT_HASH"'"],"delete-local-data":true}}'

# 2. Grab the new release (same as 2a)
NEW_RELEASE=$(echo "$RELEASES" | jq '.['$NEW_INDEX']')
GUID=$(echo "$NEW_RELEASE" | jq -r '.guid')
INDEXER_ID=$(echo "$NEW_RELEASE" | jq '.indexerId')
curl -s -X POST "$RADARR_URL/api/v3/release" \
  -H "X-Api-Key: $RADARR_API_KEY" -H "Content-Type: application/json" \
  -d '{"guid": "'"$GUID"'", "indexerId": '$INDEXER_ID'}'

# 3. Wait for new torrent to appear, then get NEW hash from queue
#    ⚠️ IMPORTANT: Check QUEUE first (not history) — history still has the old grab!
sleep 5
QUEUE=$(curl -s "$RADARR_URL/api/v3/queue" -H "X-Api-Key: $RADARR_API_KEY")
QUEUE_ITEM=$(echo "$QUEUE" | jq '.records[] | select(.movieId == '$MOVIE_ID')')
NEW_TORRENT_HASH=$(echo "$QUEUE_ITEM" | jq -r '.downloadId')
NEW_DOWNLOAD_TITLE=$(echo "$QUEUE_ITEM" | jq -r '.title')

# 4. Spawn a NEW monitor sub-agent with the NEW hash and NEW download title
#    The old sub-agent will time out naturally (the old torrent no longer exists)
```

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

```
subagent_spawn with:
  task: "🎬 Radarr Download Monitor

TORRENT_HASH: [HASH]
MOVIE_TITLE: [TITLE]
MOVIE_YEAR: [YEAR]

Monitor download via Transmission RPC + curl. Loop every 30 seconds.

⚠️ CRITICAL: YOU (the sub-agent) must be the loop controller.
Do NOT spawn background exec processes or bash loops for monitoring.
Run each step as a separate, non-background exec call:

CREDENTIALS (load once at start — exec CWD is the workspace root,
so the credentials path is workspace-relative. Do NOT use
$BASH_SOURCE; it's empty under `bash -c` and would mis-resolve.
We use curl + Transmission RPC because transmission-remote is not
in JClaw's shell allowlist; curl is):
  CREDS=\"skills/radarr/credentials/radarr.json\"
  TRANSMISSION_URL=$(jq -r '.torrents.url' \"$CREDS\")
  TRANSMISSION_USER=$(jq -r '.torrents.username' \"$CREDS\")
  TRANSMISSION_PASS=$(jq -r '.torrents.password' \"$CREDS\")

  # Session-ID handshake helper. Captures the X-Transmission-Session-Id
  # header from a sacrificial 409 response, then makes the real call.
  trpc() {
    local body=\"$1\"
    local sid
    sid=$(curl -s -u \"$TRANSMISSION_USER:$TRANSMISSION_PASS\" -D - \\
      \"$TRANSMISSION_URL/transmission/rpc\" -d '{}' -o /dev/null \\
      | grep -i '^X-Transmission-Session-Id:' | sed 's/.*: //' | tr -d '\\r\\n')
    curl -s -u \"$TRANSMISSION_USER:$TRANSMISSION_PASS\" \\
      -H \"X-Transmission-Session-Id: $sid\" \\
      -H 'Content-Type: application/json' \\
      \"$TRANSMISSION_URL/transmission/rpc\" -d \"$body\"
  }

LOOP (repeat these steps — agent controls the loop, not bash):

  Step 1 — Fetch torrent status via RPC (exec, non-background). Ask for the
  exact fields we need so the payload is small:
    trpc '{\"method\":\"torrent-get\",\"arguments\":{\"ids\":[\"'\"$TORRENT_HASH\"'\"],\"fields\":[\"name\",\"percentDone\",\"status\",\"eta\",\"rateDownload\",\"rateUpload\",\"isFinished\"]}}'

  Step 2 — Parse the JSON response with jq:
    .arguments.torrents → array; empty array means the torrent is gone.
    Per-torrent fields:
      percentDone (0.0..1.0) → multiply by 100 for percent
      status (int):
        0=Stopped, 1=Check pending, 2=Checking,
        3=Download pending, 4=Downloading,
        5=Seed pending, 6=Seeding
      eta (seconds; -1 = unknown, -2 = N/A)
      rateDownload, rateUpload (bytes/sec; divide by 1048576 for MB/s)
      isFinished (boolean)

  Step 3 — Decide:
    If .arguments.torrents is empty → Return: TORRENT_CANCELLED (exit silently)
    If isFinished=true OR status>=5 OR percentDone>=1.0:
      FIRST emit a final user-visible completion message:
        message(action=\"send\", message=\"✅ [TITLE] download complete — starting import…\")
      THEN return DOWNLOAD_COMPLETE.
    WHY the final message: without it, the user's last visible event is
    the 99.x% progress line. The main agent's resumed turn does Phase 4
    silently (sync spawn + import work), so without this bookend the
    workflow looks stalled mid-download.

  Step 4 — Push progress to the user (no channel/target — inherited from parent):
    message(action=\"send\", message=\"⬇️ [TITLE]: [percent]% • [state] • ⬇[down] MB/s ⬆[up] MB/s • ETA: [eta]\")
    Map status int → label: Downloading, Stopped, Checking, Seeding, etc.
    Format ETA: if eta>0, convert seconds → 'Xm Ys' or 'Xh Ym'; else show '—'.

  Step 5 — Wait 30 seconds (exec, non-background):
    sleep 30

  Go to Step 1.

WHY THIS MATTERS: If you use 'exec background=true' with a bash loop,
that process keeps running even after your session ends. This causes
orphaned processes that send stale/empty progress messages forever.
By keeping the loop in the agent, it dies when the session ends."

  async: true
  runTimeoutSeconds: 86400      ← 24h ceiling; a 4K Bluray on a slow connection can take many hours
```

**Immediately after the spawn returns, yield on the run** so the main
agent's turn auto-resumes when the child terminates. Pass
`timeoutSeconds: 0` to disable the yield watchdog — the monitor is
already bounded by its `runTimeoutSeconds: 86400` ceiling above, so
the parent just waits for it without a second clock racing it:

```
subagent_yield with:
  runId: [the run_id from subagent_spawn above]
  timeoutSeconds: 0   ← no yield watchdog; child's runTimeoutSeconds is the bound
```

**Why `timeoutSeconds: 0`?** The yield default is 300s (5 min), which
is far shorter than a typical movie download. Without `0`, the yield
watchdog fires before the download finishes, the parent resumes with a
TIMEOUT outcome, and Phase 4 never triggers. The monitor sub-agent
keeps running and may even send the `✅ download complete` message
afterward, but the parent has already given up on it. Passing `0`
removes the yield clock entirely; the spawn's `runTimeoutSeconds: 86400`
is the only timeout that can fire, and that's long enough for any
realistic download.

**Why both async + yield?** Async lets the monitor sub-agent run on its
own virtual thread (independent of the main agent's tool-call carrier),
so progress `message(action="send", ...)` calls reach the user live
during the download. Yield then parks the main agent's current LLM
turn — which exits silently, no final assistant reply — until the
sub-agent terminates. At that point the announce row is flipped to
USER-role and `AgentRunner.runYieldResume` re-invokes the main agent's
loop with the announce visible to the LLM. The next assistant turn
reads `DOWNLOAD_COMPLETE` (or `TORRENT_CANCELLED`) from the announce
and proceeds to Phase 4.

⚠️ **What goes wrong without yield**: with `async=true` alone, the
sub-agent's terminal announce is posted as a SYSTEM-role row —
intentionally filtered out of LLM context by
`ConversationService.loadRecentMessages` (per JCLAW-270, so
fire-and-forget background work doesn't pollute later user turns).
The main agent's next turn would never see `DOWNLOAD_COMPLETE` and
the workflow would stop mid-flight, with the download done but the
import never triggered.

**The `message` tool reaches the user automatically.** No channel/target plumbing needed — the sub-agent's Conversation row inherits the parent's `channelType` + `peerId` at spawn time (JCLAW-327 AC-5), so `message(action="send", ...)` routes to wherever the user is watching (Telegram, Slack, WhatsApp, or the in-app web chat).

---

## Phase 4: Import & Cleanup (Main Agent → Import Sub-Agent)

**Main agent spawns the import sub-agent.** Same automatic delivery-context inheritance as Phase 3 — no channel/target plumbing needed:

```
subagent_spawn with:
  task: "🎬 Radarr Import & Cleanup

MOVIE_ID: [ID]
TORRENT_HASH: [HASH]
DOWNLOAD_TITLE: [DOWNLOAD_TITLE]
MOVIE_FOLDER: [FOLDER]

CREDENTIALS (load once at start; CWD is the workspace root, so the path
is workspace-relative — no $BASH_SOURCE / SCRIPT_DIR dance. We use
curl + Transmission RPC because transmission-remote is not in JClaw's
shell allowlist; curl is):
  CREDS=\"skills/radarr/credentials/radarr.json\"
  RADARR_URL=$(jq -r '.radarr.url' \"$CREDS\")
  RADARR_API_KEY=$(jq -r '.radarr.apiKey' \"$CREDS\")
  TRANSMISSION_URL=$(jq -r '.torrents.url' \"$CREDS\")
  TRANSMISSION_USER=$(jq -r '.torrents.username' \"$CREDS\")
  TRANSMISSION_PASS=$(jq -r '.torrents.password' \"$CREDS\")

  trpc() {
    local body=\"$1\"
    local sid
    sid=$(curl -s -u \"$TRANSMISSION_USER:$TRANSMISSION_PASS\" -D - \\
      \"$TRANSMISSION_URL/transmission/rpc\" -d '{}' -o /dev/null \\
      | grep -i '^X-Transmission-Session-Id:' | sed 's/.*: //' | tr -d '\\r\\n')
    curl -s -u \"$TRANSMISSION_USER:$TRANSMISSION_PASS\" \\
      -H \"X-Transmission-Session-Id: $sid\" \\
      -H 'Content-Type: application/json' \\
      \"$TRANSMISSION_URL/transmission/rpc\" -d \"$body\"
  }

⚠️ CRITICAL: The download folder on disk uses the TORRENT name (DOWNLOAD_TITLE),
NOT the clean movie folder name. Radarr handles the rename/move during import.

1. Trigger Radarr scan on the DOWNLOAD folder (using DOWNLOAD_TITLE as folder name):
   curl -s -X POST \"$RADARR_URL/api/v3/command\" -H \"X-Api-Key: $RADARR_API_KEY\" -H \"Content-Type: application/json\" -d '{\"name\":\"DownloadedMoviesScan\",\"path\":\"/volume1/downloads/complete/'$DOWNLOAD_TITLE'\"}'

   ⚠️ Use DOWNLOAD_TITLE here, NOT MOVIE_FOLDER!
   The download path is: /volume1/downloads/complete/<DOWNLOAD_TITLE>
   Radarr will automatically move/rename to: /volume1/video/Movies/<MOVIE_FOLDER>

2. Wait 5-10s, then verify hasFile=true:
   curl -s \"$RADARR_URL/api/v3/movie/$MOVIE_ID\" -H \"X-Api-Key: $RADARR_API_KEY\" | jq '.hasFile'

   If hasFile is still false after 10s, retry the scan once more and wait another 10s.

   ⚠️ Do NOT proceed to Step 3 until hasFile=true. Removing the torrent
   before Radarr has imported the file would orphan the download.

3. Cleanup torrent — REQUIRED, do not skip:
   trpc '{\"method\":\"torrent-remove\",\"arguments\":{\"ids\":[\"'\"$TORRENT_HASH\"'\"],\"delete-local-data\":true}}'

   This removes the torrent from Transmission AND deletes the seeding
   files from the download cache. Radarr already moved the imported
   file to the movie library in Step 1, so the download-cache copy
   is now redundant — leaving it behind wastes disk and keeps the
   torrent seeding indefinitely. Expected response: {\"result\":\"success\"}.

   Verify the removal succeeded by re-querying the torrent:
   trpc '{\"method\":\"torrent-get\",\"arguments\":{\"ids\":[\"'\"$TORRENT_HASH\"'\"],\"fields\":[\"name\"]}}'
   Expected: .arguments.torrents == [] (empty array; the torrent is gone).
   If the torrent still shows, re-run torrent-remove once more.

4. Get final movie details (quality, size, resolution, runtime, audio, path):
   curl -s \"$RADARR_URL/api/v3/movie/$MOVIE_ID\" -H \"X-Api-Key: $RADARR_API_KEY\" | jq '{
     quality: .movieFile.quality.quality.name,
     size_gb: ((.movieFile.size / 1073741824 * 100 | round) / 100),
     audio: .movieFile.mediaInfo.audioCodec,
     runtime: .movieFile.mediaInfo.runTime,
     path: .path
   }'

Return: IMPORT_COMPLETE|<quality>|<size_gb>|<runtime>|<audio>|<path>
Or: IMPORT_FAILED|<reason>"

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

**Why the `message` tool here and not assistant text?** Phase 5 runs on
the same resumed turn as Phase 4 (the sync import spawn). On
yield-resumed turns, trailing assistant text is not reliably routed to
the user-facing channel (especially Telegram/Slack/WhatsApp where the
delivery path goes through `DeliveryDispatcher`, which is only invoked
by the `message` tool). Calling `message` explicitly walks up the
sub-agent's parent-conversation chain to the user-facing root and
routes via the correct channel adapter (JCLAW-327 AC-5).

**Failure path:** if Phase 4 returns `IMPORT_FAILED|<reason>`, send a
failure message instead:

```
message(action="send", message="⚠️ Import failed for **<Title>**: <reason>. The download is still on disk; you can retry by asking 'reimport <Title>' or investigate manually.")
```

---

## Summary

| Phase | Who | Task |
|-------|-----|------|
| 1. Search & Present | **Main Agent** | Search Radarr, show options to user |
| 2. Grab | **Main Agent** | Grab release, get torrent hash, tell user "download started" |
| 3. Monitor | Sub-Agent (async + yielded) | Loop 30s, push progress via `message(action="send", ...)`, return DOWNLOAD_COMPLETE when done; main turn parks via `subagent_yield` so the announce auto-resumes it |
| 4. Import | Sub-Agent (sync) | Main emits `message("📥 Import starting…")`, then spawns import sub-agent which triggers Radarr scan, cleans up torrent, returns IMPORT_COMPLETE|details |
| 5. Notify | **Main Agent** | Emit `message("🍿 Ready to watch!…")` with synopsis + file details — **via the `message` tool, not assistant text** |

**Delivery is automatic:** sub-agents inherit the parent conversation's `channelType` + `peerId` at spawn time (JCLAW-327 AC-5), so any call to `message(action="send", message=...)` routes back to wherever the user is watching — Telegram, Slack, WhatsApp, or the in-app web chat — with no channel/target plumbing.

---

## Visual Flow

```
USER: "Download Twisters"
         │
         ▼
┌─────────────────────────────────────────┐
│           MAIN AGENT                     │
│  • Search Radarr API                     │
│  • Present 1080p + 4K options           │
│  • Wait for user pick                    │
└─────────────────────────────────────────┘
         │
USER: "5"
         │
         ▼
┌─────────────────────────────────────────┐
│           MAIN AGENT                     │
│  • Grab release #5                       │
│  • Get torrent hash                      │
│  • Say "⬇️ Download started!"           │
│  • Spawn monitor sub-agent              │
└────────────────┬────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────┐
│       MONITOR SUB-AGENT                  │
│  LOOP every 30s:                         │
│    • curl Transmission RPC torrent-get  │
│    • Output: "⬇️ 45% • ⬇2MB/s • ETA 5m" │
│  When 100%:                              │
│    • Return DOWNLOAD_COMPLETE           │
└────────────────┬────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────┐
│           MAIN AGENT (resumed turn)      │
│  • Receives DOWNLOAD_COMPLETE           │
│  • message("📥 Import starting…")       │
│  • subagent_spawn import (SYNC)          │
└────────────────┬────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────┐
│       IMPORT SUB-AGENT (sync)            │
│  • Trigger Radarr scan                  │
│  • Verify hasFile=true                   │
│  • Cleanup torrent via trpc()            │
│  • Return IMPORT_COMPLETE|details       │
└────────────────┬────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────┐
│           MAIN AGENT                     │
│  • message("🍿 Ready to watch!…")       │
│  • Include synopsis + file details      │
└─────────────────────────────────────────┘
```

**Main agent stays in control. Interactive parts are direct. Only long-running tasks use sub-agents.**
