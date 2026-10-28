# Release swap (Phase 2d)

## Credentials: `trpc()`

```bash
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

## Swap commands

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
