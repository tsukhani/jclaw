# Background

## Delivery routing

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

## Why async + yield

**Why `timeoutSeconds: 0`?** The yield default is 300s (5 min), which
is far shorter than a typical movie download. Without `0`, the yield
watchdog fires before the download finishes, the parent resumes with a
TIMEOUT outcome, and Phase 4 never triggers. The monitor sub-agent
keeps running and may even send the `✅ download complete` message
afterward, but the parent has already given up on it. Passing `0`
removes the yield clock entirely; `subagent.maxWallClockSeconds`
(seeded 1800) still caps the run.

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
sub-agent's terminal announce is posted without resuming the main
agent, so nothing acts on `DOWNLOAD_COMPLETE` until the user writes
again, with the download done but the import never triggered.

**The `message` tool reaches the user automatically.** No channel/target plumbing needed — the sub-agent's Conversation row inherits the parent's `channelType` + `peerId` at spawn time (JCLAW-327 AC-5), so `message(action="send", ...)` routes to wherever the user is watching (Telegram, Slack, WhatsApp, or the in-app web chat).

## Why Phase 5 uses `message`

**Why the `message` tool here and not assistant text?** Phase 5 runs on
the same resumed turn as Phase 4 (the sync import spawn). On
yield-resumed turns, trailing assistant text is not reliably routed to
the user-facing channel (especially Telegram/Slack/WhatsApp where the
delivery path goes through `DeliveryDispatcher`, which is only invoked
by the `message` tool). Calling `message` explicitly walks up the
sub-agent's parent-conversation chain to the user-facing root and
routes via the correct channel adapter (JCLAW-327 AC-5).
