# JCLAW-1228 — doc fragment

**Target AGENTS.md section:** `## Known pitfalls`, appended to the existing *Personal Edition*
paragraph (the one that enumerates what refuses the agent principal via
`RequestPrincipal.isAgentOriginated`). The prose below adds the **second** trust axis that
paragraph does not yet name: the channel sender.

---

## Proposed prose

Personal Edition has two trust axes, not one. The paragraph above is about the *agent* principal;
this is about the *sender*. An inbound channel turn is owner-initiated only when the channel
proved it: `TelegramAccessPolicy` (DM owner-only), `SlackAccessPolicy` once an `ownerUserId` is
configured, and nowhere else — a group member the bot was @mentioned by, every WhatsApp sender,
and every user of an owner-less Slack binding are all guests. `DangerousActionGate.withOwnerInitiated`
binds that answer at the three ingress sites and `DangerousActionGate.ownerInitiated()` reads it
back; unbound means guest, which is the safe polarity. Two places consume it beyond the
dangerous-action prompt:

- **Slash commands.** `Commands.execute`'s `ownerInitiated` overload refuses `/subagent`,
  `/prompt` and the `/model` *write* forms (`/model NAME`, `/model reset`) for a guest turn —
  those reach every subagent run on the instance, the operator's prompt library, and the
  conversation's model override respectively. The read forms (`/model`, `/model status`) and
  every lifecycle command stay open. The dispatcher reads the ThreadLocal rather than taking a
  new parameter because it is handed a `peerId` and never sees the sender id it would need to
  re-derive the answer. Web chat passes `true` explicitly: it sits behind `AuthCheck`, so the
  caller *is* the operator, and anything else would deny the operator their own chat.
- **Telegram reactions.** `TelegramReactionNotifier.reactorAllowed` drops a private-chat reaction
  whose `reactorId` is not the binding owner (a null reactor — anonymous or channel actor — is
  not the owner). The notify policy cannot express this: under the default `own` a DM reaction is
  admitted unconditionally, and a DM peer id resolves to the owner's own conversation, so a
  stranger's 👍 would run a turn over the owner's history and send the reply to the stranger.
  Group and supergroup reactions keep the `shouldNotifyReaction` policy — their peer id is the
  shared chat, not the owner's DM.

Reaction event text is labelled `[reaction]`, never `[system]`. The reactor's display name is
chosen by the reactor, and the old prefix lent attacker-supplied text the authority of a system
line.

Two consequences worth knowing before filing a bug against them. A Slack binding with no
`ownerUserId` now refuses those commands for everyone, including the operator — configure the
owner. And `/subagent info|log|kill` is deliberately **not** scoped to the calling agent's own
runs: one operator owns every agent here, so cross-agent reach by an *owner* turn is the posture,
not a defect.

---

## Verified while implementing (not from the ticket)

- The Telegram inline-keyboard path the ticket's model-switch clause worried about
  (`TelegramCallbackDispatcher.handleSelect` → `Commands.performModelSwitch`, which bypasses
  `execute`) is already owner-gated: both callers — `WebhookTelegramController.handleCallback`
  and `TelegramPollingRunner.handleCallback` — reject a `callback_query` whose `fromId` is not
  `binding.telegramUserId` before the dispatcher runs. A guest can still *summon* a keyboard with
  a bare `/model` in a group, but every tap on it is refused at the ingress. No change was made
  there, and a `ownerInitiated()` check inside the dispatcher would be actively wrong: nothing
  binds the ThreadLocal around callback dispatch, so it would read `false` and break the
  operator's own taps.
