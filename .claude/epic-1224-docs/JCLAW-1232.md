# JCLAW-1232 — media bearers across a rebuild of the message list

## The defect, as reproduced

`AgentPromptPreparer.rewriteSyncMedia` (and its streaming twin `applyMediaRewrite`) ran, in one
method: hydrate → compact → trim → apply the media rewrites. The three
`VisionAudioAssembler.apply*ForCapability` methods end in `rewritten.set(b.chatMessageIndex(), …)`,
and `chatMessageIndex` was computed by `MessageHydrator.hydrateUserMessage` against the
*pre*-compaction list.

Two of the intervening steps replace the list:

- `CompactionGate.maybeCompactAndRebuild` returned `MessageHydrator.buildMessages(...).messages()` —
  it rebuilt the list **and** the bearers, then threw the fresh bearers away.
- `ContextWindowManager.trimToContextWindow`'s drop-oldest branch returns
  `system + messages.subList(1 + dropCount, size)`, shifting every survivor down by `dropCount`.

`ToolResultPruner.prune` and `CompressionPipeline.compress` upstream are index-preserving
(`out.set(i, …)` over a same-size copy), as is the tool-result truncation branch of the trim and
`CurrentTimeInjector.inject` (appends). Those four are not part of the defect.

Measured on the unfixed tree by `MediaBearerRebaseTest`:

- compaction case → `IndexOutOfBoundsException: Index 7 out of bounds for length 6`, thrown out of
  the turn;
- trim case → the caption rewrite landed on the assistant row that followed the image turn, and the
  real image turn kept its `image_url` part, which is exactly what a non-vision model cannot read.

## Which AC option was taken, and why

The AC allowed either "locate by `msgId`" or "run before the list can be rebuilt". **The second does
not work**: compaction rebuilds the list from the persisted rows via `MessageHydrator.buildMessages`,
so any rewrite applied before it is discarded wholesale. Taken: locate by id.

`ChatMessage` carries no message id, so "locate by `msgId`" is realised as *keeping the bearers in
step with the list*, which is the same thing done once per step instead of once per lookup:

1. `CompactionGate` gained an overload that takes and returns `MessageHydrator.Hydration`. When it
   rebuilds it now returns the rebuild's own bearers; when no compaction fires it returns the
   caller's hydration unchanged. The two `List`-returning signatures delegate to it, so their
   existing call sites (and `CompactionGateTest`'s ten `assertSame(current, result)` assertions)
   are untouched.
2. `ContextWindowManager` gained the matching overload. The trim body moved into a private
   `trim(...)` returning `Trimmed(messages, droppedOldest)`; the bearer-aware face re-bases through
   `Hydration.afterDroppingOldest`, which shifts each position down by `droppedOldest` and drops a
   bearer whose own message went.
3. `VisionAudioAssembler.applyVideoForCapability` keys its dispatched parts by `msgId` rather than
   by slot index, so dispatch and splice stay paired.
4. Both rewrite methods now return the re-based bearers: `rewriteSyncMedia` in its `PreparedData`
   (which `AgentRunner` already forwards into the tool loop's audio/image retry paths) and
   `applyMediaRewrite` by widening its return from `List<ChatMessage>` to `PreparedPrologue`, which
   is what makes `StreamingAgentRunner`'s round-1 audio-format retry address the list it re-sends.

`ContextWindowManager` returning an explicit `droppedOldest` was preferred to deriving it as
`beforeSize - afterSize`. The derivation happens to hold today — every other branch returns a
same-size list — but it is an invariant nothing states or tests, and the explicit field costs a
private record.

## The bounds-and-role guard

`VisionAudioAssembler.holdsUserTurn` gates all three `apply*` methods: the slot must be in bounds
and must still hold a `USER` message. After the re-base it is a no-op on the two paths above; it
exists for the retry call sites, which reuse bearers against a list that has since grown, and so
that a future rebuild fails as a skipped rewrite rather than as a thrown turn.

## Test

`test/MediaBearerRebaseTest` drives a real `AgentRunner.run` against a canned HTTP LLM and asserts
on the chat call's request body — the smaller seams cannot see this bug, because each rewrite is
correct in isolation and only wrong relative to a list rebuilt under it. The image carries a
pre-set caption so no caption backend is configured or called.

Fixture notes, both learned the hard way:

- The trim's drop loop subtracts **message** tokens from a total that also includes the system
  prompt (~7.8k chars here) and the tool schemas. A `contextWindow` whose trim target does not
  clear those two drops the entire history regardless of what the messages hold — the first
  attempt at `contextWindow=8192` left `[system, user]`. The trim case runs at 24000.
- The compaction case has to keep the trim out of the way, so it runs at `contextWindow=20000`
  with the compaction reserve at 18000: budget 2000 tokens, trim target 15904.

## Not done, deliberately

`LoadTestRunner`'s `long[]`/`long[][]` and `CheckListTool`'s status strings are listed on the
ticket as "do them if the file is open anyway". Neither file is touched by this change.
