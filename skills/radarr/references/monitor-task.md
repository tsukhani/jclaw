# Phase 3 monitor `task`

```
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
$BASH_SOURCE; it's empty under `sh -c` and would mis-resolve.
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
```
