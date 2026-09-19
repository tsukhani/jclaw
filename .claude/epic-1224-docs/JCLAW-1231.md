# JCLAW-1231 — one implementation each for the drifted twins

Branch `epic-1224/jclaw-1231`, rebased on JCLAW-1269 (`fcfd376b`).

## What was collapsed, and what each twin had drifted into

| Twin | One implementation | Divergence fixed |
|---|---|---|
| `EventLogCleanupJob` / `TaskCleanupJob` / `LatencyMetricCleanupJob` resolvers | `jobs.RetentionDays` | zero handling (already fixed by 1269); negative now disables on all three instead of two |
| `ConfigService.setWithSideEffects` / `deleteWithSideEffects` | `isDispatcherCapKey` + the arm in both | a deleted `dispatcher.llm.*` cap stayed live until restart |
| `SubagentSpawnTool.executeBatch` / `SubagentSpawnArgs.parse` | `SubagentSpawnArgs.batchChild` | `modelProvider`/`modelId` were dropped for batch children |
| `ParallelToolExecutor` / `ApiConversationsController` attachment JSON | `AttachmentService.toView` / `toViews` | the SSE frame was missing `deleted` |
| `DangerousActionGate.buildPrompt` / `buildSlackPrompt` | per-grammar escaping, `SlackMarkdownFormatter.escape` widened to public | the Slack prompt escaped nothing, and args could close its code fence |
| `WebhookTelegramController.processMessage` / `TelegramPollingRunner.dispatchMerged` | `channels.TelegramInboundTurn` | a failed polling turn only logged; the sender was never told |
| `DiarizeSidecarManager.ensureRunning` | `LocalSidecarDaemon.singleFlight` | none — an unfinished extraction; `lock()` is now deleted |

## Decisions worth recording

**The resolver's negative case.** 1269 made `logs.retentionDays` treat any non-positive value as
"disabled"; the two siblings treated a negative as out-of-range and fell back to their 30/14-day
default — i.e. they kept deleting. One resolver cannot hold both, and a negative window is not a
window: the shared rule is `<= 0` → disabled, with a warn on the negative so a typo is not a
silent "retention off forever" (the JCLAW-1067 failure mode). `TaskCleanupJobTest`'s negative
assertion moved with it. The ceiling stays per-caller: the two siblings pass 3650, the event log
passes `RetentionDays.NO_CEILING`, because it never had one and widening it is not this story.

**The Slack prompt cannot reuse `escapeHtml`.** Slack is not HTML. `SlackMarkdownFormatter.escape`
covers the `& < >` Slack documents, and a second pass separates backtick runs with a zero-width
space — model-controlled args inside a ```` ``` ```` fence could otherwise close it and render the
rest of the approval prompt as mrkdwn. `*` and `_` are *not* escaped: Slack publishes no escape for
them, so bold text inside the prompt body remains possible; what is prevented is escaping the block
structure. The Telegram side is complete by contrast, because `& < >` is all HTML needs.

**`AttachmentService`, not `utils.AttachmentView`.** No class in `app/utils` imports `models`, and
the view is a projection of a JPA entity; putting it in `utils` would have been the first breach of
that layering. `AttachmentService` already owns `MessageAttachment` and is `@NullMarked`.

**`deleted` on the streamed frame is additive.** `frontend/types/api.ts` already declares
`deleted?: boolean` and `ChatMessage.vue` reads `!!att.deleted`; a freshly generated attachment is
never deleted, so the value is always `false`. No frontend change, no observable behaviour change —
a consistency fix.

## The AC's grep, honestly

`git grep -n -P "Mirrors|Same as|kept in sync|must match" -- app/` went from **96 to 95**. Exactly
one hit marked duplicated logic this story removed (`LatencyMetricCleanupJob`'s "Mirrors
`TaskCleanupJob`'s parsing"). The other six twins carried no such comment — they were found by
reading, not by the phrase, which makes the count a weak proxy for duplication here.

Residue, all deliberate: 83 × "Mirrors" are cross-references to an analogous implementation that
deliberately is not shared — the per-channel wire shapes (`SlackApprovalService.Outcome` mirrors
Telegram's, `SlackPendingFile` mirrors `PendingAttachment`, the Slack/WhatsApp bindings controllers
mirror the Telegram one), query-technique pointers (JOIN FETCH, bulk-fetch-then-group), and
test-hook classes. 9 × "must match" are user-facing validation strings (`ApiAgentsController`'s
"Agent name must match …", `McpServerService`, `AppInstallTool`, `MemoryEvalPaths`) or a parameter
contract. 3 × "Same as" are a doc pointer, an inline "same as the line before", and a constant's
provenance. 0 × "kept in sync".

## Ticket claim that did not hold

> "`LocalSidecarDaemon.singleFlight` exists for the JCLAW-830 guard but some sidecar managers still
> hand-roll it; the same shape in the deletion cascades and the subagent runners."

Six of the seven managers already call `DAEMON.singleFlight`. Only `DiarizeSidecarManager` differed,
and it was still serialized — on `DAEMON.lock()`, a different monitor. Converting it let `lock()`
and its field be deleted outright, along with the three Javadoc clauses that existed only to explain
the exception. No hand-rolled copy was found in the deletion cascades or the subagent runners.
