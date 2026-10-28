# Phase 4 import `task`

```
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
```
