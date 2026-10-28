# Summary and visual flow

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
