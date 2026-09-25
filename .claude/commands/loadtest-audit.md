---
description: Load-test the running JClaw backend through jclaw.sh's harness — two identical mock passes plus one real-provider pass (OpenRouter nemotron-3-nano by default) — while sampling the JVM throughout, then report JClaw-attributable memory leaks, memory bottlenecks and performance bottlenecks, or say plainly that there are none. Provider latency is out of scope.
argument-hint: "[--provider P --model M] [--concurrency N --turns N]   (sizes the real pass; default openrouter nvidia/nemotron-3-nano-30b-a3b, 10 x 5)"
---

# JClaw load-test audit

Answer one question: **under load, does JClaw leak memory, run short of memory, or add latency of its own?** The evidence comes from `loadtest/audit.sh`, which drives `./jclaw.sh loadtest` against the backend on :9000 and watches that JVM throughout; you judge the digest it writes. Use `/usr/bin/git` for any git invocation.

**Real-pass arguments:** `$ARGUMENTS`. Empty means `openrouter` / `nvidia/nemotron-3-nano-30b-a3b` at concurrency 10 × 5 turns: the smallest and cheapest nemotron chat model there ($0.05/M in, $0.20/M out), well under a cent per run. The `:free` variants' per-minute and daily caps refuse concurrent calls. The mock passes are always 100 × 50.

## The rule that governs this command

**Provider behavior is never a finding.** On the real pass, `ttft`, `stream_body`, tokens/s, reasoning time, the provider's 429s and 5xx, and a breaker opened by provider failures all belong to the provider. They get one line of context in the report and nothing more. A finding is something JClaw's own code, threads, locks, heap or sockets did.

**A clean result is a result.** If no check crosses its threshold, the deliverable is a short paragraph saying so, plus the checks table with its numbers. No "could be faster" rows, no speculative tuning, no findings table with nothing in it.

**Every finding carries its evidence and its attribution:** the digest number, JFR view row or histogram delta that shows it, and why it is JClaw's rather than the provider's or the host's. If you cannot establish a cause, say so and propose nothing for it.

**Report; don't fix.** Stop after the report and ask which findings to pursue.

## Guardrails

- **Never start, stop or restart the backend.** Invoking this command approves loading the instance, not restarting it. If nothing listens on :9000, say so and stop.
- The instance may be serving the operator's own chat. Each mock pass holds 100 concurrent streams for about 45 s; say so in one line before starting.
- Run one audit at a time, and never alongside another `./jclaw.sh loadtest`.
- Never clear the operator's metrics (`DELETE /api/metrics/latency`, the Chat Performance rows). The harness already sweeps its own conversations, events and latency samples when each pass ends.
- If the collector is interrupted mid-pass, its `EXIT` trap stops the JFR recording, but the server-side run is left behind: `./jclaw.sh --clean loadtest` removes it.
- The real pass shares its provider's circuit breaker with the operator's own agents. Three refused calls in ten trip it for about ten minutes, which is why the real pass stays small. If it tripped, say so in the report.

---

## Phase 1 — Collect

```bash
OUT=<session scratchpad>/loadtest-audit-$(date -u +%Y%m%dT%H%M%SZ)
loadtest/audit.sh "$OUT" $ARGUMENTS > "$OUT.log" 2>&1
```

Run it with `run_in_background: true` and wait for the completion notification; don't poll it. Allow about 4 minutes plus the real pass, a minute or two at the default size. The collector:

1. Finds the backend by its :9000 listener and attaches with that JVM's own `jcmd` (a jenv shim costs ~10 s per call).
2. Makes one real call (c=1 × 1) to prove the provider and model work before spending anything else.
3. Starts a 2 s sampler (heap used, RSS, OS threads, fds, TCP ESTABLISHED / CLOSE_WAIT) tagged by phase.
4. Takes a **settled snapshot** (`GC.run` twice, 5 s apart; one call does not reliably finish a ZGC cycle), then runs **mock1**, **mock2** and **real**. Each pass gets its own JFR recording (`settings=profile`), a slice of `logs/application.log`, and a settled snapshot afterwards: heap, class histogram, metaspace, platform and virtual threads, `lsof`.
5. Renders JFR views and writes `digest.md`.

**Exit codes:** 2 means usage error or no backend: report it and stop. 3 means the smoke call failed: quote the error from `smoke.out` and stop, because the provider or model is unusable, which is not a JClaw finding unless the error came from JClaw itself (a 500 from `/api/metrics/loadtest`). 4 means the backend died mid-run, which is the most serious possible finding: look for `logs/heap-oom.hprof` (the JVM runs `-XX:+HeapDumpOnOutOfMemoryError`), an `hs_err_pid*.log`, and the tail of `logs/application.log`.

Keep a real pass under 30 minutes (`jclaw.sh` gives a real run 1800 s before its client gives up, while the server keeps going) and within 100 × 50, the endpoint's limits.

## Phase 2 — Trust the measurement before reading it

Read `$OUT/digest.md`. Before any verdict, confirm the harness measured what it claims. Each of these has a silent failure mode:

1. **Every pass has a JSON report and `requests = c × t`.** A missing report means the pass failed at the HTTP level; read `<pass>.out`. Errors on the real pass don't void it. Classify them from `real.app.log`: provider-side errors (429, 5xx, timeouts, breaker short-circuits after those) are one line of context. Judge the JClaw rows on the turns that succeeded, and mark them *Not measured* only if none did.
2. **The sampler recorded heap values in every phase** (the `with heap` column is non-zero in each row). If it is zero, the sampler broke, and any claim about peak heap is unfounded.
3. **Every JFR has execution samples and GC cycles.** A recording with none measured nothing.
4. **The mock honored its stub.** `ttft above stub` on the mock passes should be single-digit ms. Hundreds of ms is either a JClaw finding or a broken harness; `queue_wait` and `dispatcher_wait` tell you which.
5. **The host was not saturated.** JFR's machine CPU sitting far above the JVM's is normal on a workstation: the validation run averaged 52% machine against 6% JVM and still met its stub to within 0.4 ms. Only machine CPU near 100% (a mean above ~85%) makes timing unreliable, and even then the mock proves the point directly: if it still met its stub (check 4) and wall clock stayed within 1.10× ideal, the host kept up. If it did not, say so and keep only the memory verdicts, which do not depend on timing.

If a check fails, name it and withhold the verdicts that depended on it.

## Phase 3 — Judge

The design that makes this work: **mock1 and mock2 are identical.** mock1 pays one-time warm-up (the token-count memo, H2's page cache, JIT, class loading), so growth from baseline to after-mock1 alone is not a leak. Whatever grows *again* on mock2, at a per-request rate, is retained per request. And on the mock, the provider is an in-process stub with a fixed budget (first frame at `ttftMs`, the rest evenly spaced; 880 ms at the defaults), so **every millisecond above the stub is JClaw's.**

Thresholds come from past runs on this codebase (see *Reference* below). Treat them as "investigate above", not as automatic verdicts.

### Memory leaks

| Check | Digest source | Healthy | Finding |
| --- | --- | --- | --- |
| Live heap on the second identical pass | Settled state, `live heap MB (histogram)`, `Δ mock2` | within ~25 MB. That is the measurement's resolution, about 5 KB per request over 5000; say so in the report | larger, *and* a histogram grower accounts for it |
| Per-request retention, JClaw types | "Grew on both identical mock passes", owner `JClaw` | none | any, even with a flat heap: a small shallow size can pin a large retained graph |
| Per-request retention, library types | same table, owner `H2` or `JDK/lib` | present, with the live-heap row healthy | only when the live-heap row is a finding and these types account for it |
| Classes and loaders | `Δ mock2` | classes within ~50, loaders within ~10 (hidden classes for method handles and reflection keep trickling in: +7 and +6 on a healthy run) | hundreds of classes or dozens of loaders on the second identical pass: a class-loader leak candidate |
| Platform threads | thread families | back to baseline | a family that grows every pass |
| Virtual threads | parking sites | back to baseline | a site holding threads in proportion to requests, or growing every pass |
| Sockets and fds | TCP table, `open fds` | ESTABLISHED bounded by pool size and no higher after mock2 than after mock1; no CLOSE_WAIT apart from the mock-port sockets below | CLOSE_WAIT that persists or grows (JClaw never closed its end), or fds rising every pass |

Never take the live set from `GC.heap_info`'s "used". Under ZGC it includes whatever was allocated after the collection finished: one audit read +224 MB there against +3 MB in the histogram, while a dashboard polled the metrics endpoints.

**Known-benign, so not a finding:**

- **`ScheduledThreadPoolExecutor$ScheduledFutureTask`** (and its queue's backing array) grows by about one per chat request. `utils.InactivityTimer` schedules each stream's inactivity timeout on a scheduler without `setRemoveOnCancelPolicy(true)`, so a canceled timer stays queued as a ~96-byte shell until its budget elapses (`ApiChatController.chatStreamTimeout()`, about 12 minutes). It will appear in the "grew on both" table. To confirm it is still benign, re-histogram once the budget has passed (`jcmd <pid> GC.class_histogram | grep ScheduledFutureTask`): the count falls back. If it does not fall back, it is a finding.
- **`org.h2.*` rows, values and tokens** growing at 0.5–1 instance per request on both passes. By type, these are H2's MVStore page cache and its per-connection parsed-statement caches, both bounded. On a healthy run they add a few hundred KB per pass while the live heap stays flat.
- **`services.ConversationQueue$QueueState`** growing by one per conversation: +101 on each mock pass (100 workers plus the warmup), which is under the "grew on both" filter's per-request rate. `jobs.ConversationQueueEvictionJob` runs hourly and removes entries idle for more than an hour (`conversation.queue.idleEvictionMs`); after the first audit it logged "Evicted 208 idle conversation queue state(s)". It is a finding only if that log line stops appearing, or the count outlives two hours.
- **Growth on mock1 only**, when mock2 is flat.
- **RSS.** It sits far above the live heap because ZGC keeps committed and cached memory mapped, and it climbs while committed heap stays flat, because ZGC touches committed pages for the first time as a pass's peak heap exceeds the last one's (+73 MB on a healthy mock2). So "RSS outside heap" is only a coarse upper bound on native memory. It becomes an unconfirmed native-leak candidate only if it rises by more than ~150 MB on *both* mock passes. Report it as needing `-XX:NativeMemoryTracking=summary`, which requires a restart; do not restart.
- **OkHttp `TaskRunner` threads** that are up after a pass and back down by a later snapshot. They idle out after 60 s.
- **Sockets to the mock provider's port (127.0.0.1:19999) after a mock pass.** Teardown stops the mock server while OkHttp's LLM pool (`HttpFactories`: 64 idle connections, 5-minute keep-alive) still holds its idle connections to it. They show as `CLOSE_WAIT`, and on macOS later as `CLOSED` with the fd still open, until the pool evicts them. The count is capped at 64. It is a finding only when it exceeds 64, involves any other peer, or has not cleared 5 minutes after the last pass (`lsof -nP -a -p <pid> -iTCP | grep -c ':19999'`).
- **ESTABLISHED connections after a pass**: the harness's own keep-alive connections to :9000, and to the provider host after the real pass, bounded by the same pools.

### Memory bottlenecks

| Check | Digest source | Healthy | Finding |
| --- | --- | --- | --- |
| Allocation stalls | `jdk.ZAllocationStall` | 0 | any: threads blocked waiting for the collector |
| Heap headroom | sampler, peak heap `of max` | below 90% of MaxHeapSize (running above SoftMaxHeapSize under load is normal, since ZGC exceeds it on demand) | ≥ 90% |
| GC pauses | JFR, longest pause | sub-millisecond | over 1 ms, or `Allocation Stall` among the GC causes |

For a memory bottleneck, `<pass>.view-allocation-by-class.txt` names the allocating types. `jfr view allocation-by-site <pass>.jfr` names the sites.

### Performance bottlenecks

| Check | Digest source | Healthy | Finding |
| --- | --- | --- | --- |
| Errors | harness `errors`, application log | 0 on the mocks. On the real pass, only provider-classified errors (429, 5xx, provider timeouts) | any mock error; any real error whose log line is JClaw's (an exception thrown from a JClaw frame, Hikari `Connection is not available`, an H2 lock timeout, `RejectedExecutionException`, `OutOfMemoryError`) |
| Mock per-request overhead | "Mean request above stub" | ≤ ~25 ms | > 50 ms; decompose it with the segment table |
| Mock throughput | wall clock vs ideal | ≤ 1.10× | > 1.15×: the JVM cannot keep c workers at stub pace |
| History scaling | last-5-turns / turn-2 duration (mock) | ≤ 1.10× | > 1.10×: per-turn cost grows with conversation length |
| Pass to pass | mock2 wall clock vs mock1 | the same or faster (JIT is warm) | mock2 > 10% slower: accumulated state degrades serving |
| JClaw-owned segments | per-request means, mock and real | `queue_wait` and `dispatcher_wait` ≈ 0; `prologue` and `persist` a few ms | `queue_wait` or `dispatcher_wait` > 5 ms (the endpoint raises the dispatcher cap above c, so any wait there is JClaw's own queueing); `prologue` or `persist` > 25 ms, or real ≥ 3× mock *and* at least 5 ms above it (contention while streams are held open). On a sub-millisecond mock baseline the ratio alone means nothing: real replies make longer histories, and a short real pass has a bigger share of conversation-creating first turns (2.2 ms vs 0.3 ms on a healthy 10 × 5 run) |
| Virtual-thread pinning | `jdk.VirtualThreadPinned` | 0 | any: see `<pass>.view-pinned-threads.txt` for the site |
| Monitor contention | `jdk.JavaMonitorEnter` by class | a few short waits | a JClaw monitor class accumulating ≥ ~100 ms per pass on the request path: see `view-contention-by-site` |
| CPU hotspots | CPU samples by leaf-most JClaw frame (mock passes), with JVM CPU | JVM CPU low (single-digit % of cores on a healthy run) | JVM CPU sustained above ~50% of cores, *or* a failing overhead or throughput row above. Then the top JClaw frames are where to look |

The CPU table says where the CPU went, not that it was short. On a healthy mock the JVM idles at ~6% of cores and the table still has a leader (persisting messages and loading conversations, ~10% each of a few hundred samples). A leader is a bottleneck only when CPU is actually constrained, so with the JVM mostly idle it gets one line of observation at most. The real pass mostly waits on the network, so its samples are too few to judge. Never judge `prologue_prompt` pass-to-pass: its sum swung 3× between two identical mock runs on the same build. Judge its per-request mean against ≤ 1 ms.

On the real pass, the JClaw-owned surface is what the stub never exercises: streams held open for seconds, TLS and OkHttp's connection pool, reasoning deltas, and a real provider's SSE framing. So look at JClaw segments against the mock's, sockets and fds, pinning and errors. `ttft` and `stream_body` there are the provider's.

## Phase 4 — Establish the cause of each finding

Only for rows judged a finding:

- **Leak candidate:** find what retains it. `jfr view memory-leaks-by-site <pass>.jfr` gives the allocation site. For the retention path, take one heap dump (`jcmd <pid> GC.heap_dump "$OUT/leak.hprof"`; tell the user first, since it pauses the JVM for about a second per 100 MB of live heap and the file holds in-memory secrets, so it stays local) and open it with the JProfiler MCP (`load_snapshot`, then `get_heap_data`). Name the retaining JClaw field or collection as `file:line`.
- **CPU hotspot:** read full stacks with `jfr print --events jdk.ExecutionSample --stack-depth 64 <pass>.jfr`. The default depth of 5 frames attributes everything to library code.
- **Pinning or contention:** the view files name the site. Open the JClaw frame.
- **Errors:** quote the log lines from `<pass>.app.log`.

## Phase 5 — Report, and stop

1. **Verdict, first line.** Either "No JClaw-attributable leaks, memory bottlenecks or performance bottlenecks" plus the load shape (mock 100 × 50 twice; real `<provider>/<model>` c × t), or the number of findings.
2. **Checks table:** every check from Phase 3, passed or not: `Area | Check | Measured | Verdict` (OK / Finding / Not measured).
3. **Findings, only if any:** `# | Area | Evidence | Why it is JClaw's | Site (file:line) | Severity`.
4. **Provider context, one line:** the real pass's ttft mean, tokens/s, and any provider-side errors with their class.
5. **Caveats:** host load, if Phase 2 flagged it, and what this harness does not exercise: it drives `/api/chat/stream` through the `__loadtest__` agent only, with no tools, no channels (Telegram, Slack, WhatsApp), no sidecars, and vector recall switched off for the run by the endpoint (the keyword leg still runs). The prompts are short, so compaction and context-window trimming are barely exercised.
6. **Artifacts:** `$OUT`, including `digest.md` and the three `.jfr` files, which open in JDK Mission Control.

Then **stop and ask which findings to pursue.** Make no edits until the user chooses.

---

## Reference: past runs on this codebase (mock c = 100 × 50, 2026-06 to 2026-09)

- Mock: 5000/0 in 44.7–44.9 s; per-turn duration flat at 873–892 ms against the 880 ms stub; `prologue` 0.3–0.8 ms per request.
- This command's first run (v0.19.3, 2026-09-25): mean request +2 to +3 ms above the stub; wall clock 1.016–1.018× ideal; last-5-turns / turn-2 duration 0.99–1.01×; `queue_wait` and `dispatcher_wait` ≤ 0.3 ms.
- Settled live set by class histogram: 269 → 276 → 279 MB and 227 → 255 → 258 MB on two runs on 2026-09-25 (v0.19.5), so +2 to +3 MB on the second identical pass. The first pass adds 7–28 MB of one-time warm-up.
- JFR: `ZAllocationStall` 0, `VirtualThreadPinned` 0, longest GC pause 17–236 µs.
- CPU, with the JVM at 6–8% of cores on the mocks: leaf hot methods are Play's reflective action dispatch (~2%), H2 MVStore and Gson SSE parsing. By leaf-most JClaw frame, `ConversationService.appendMessage`, `Conversation.findById` and `Message.findRecent` lead at ~7–12% each, with ~20% of samples having no JClaw frame.
- JDK 25 has no `jdk.ZPhasePause` event. The collector reads pauses from `jdk.GarbageCollection`, so a script that reads the old event reports a false zero.
