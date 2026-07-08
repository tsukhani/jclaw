# JCLAW-668 — Spike: Evaluate a true ACP (Agent Client Protocol) client

**Status:** research / decision doc — no production code.
**Epic:** JCLAW-657 (external coding-harness integration).
**Date:** 2026-07-07.

## 1. Why this spike exists

Today JClaw runs an external coding harness (Claude Code / Codex / Gemini CLI) as a
**one-shot, batch, text-in-text-out subprocess**. `SubagentSpawnTool.runAcpHarness`
does exactly this:

1. `new ProcessBuilder(command).start()` on the operator-configured command
   (`subagent.acp.command`, e.g. `claude -p` or `codex exec`);
2. write the `task` to stdin, close stdin;
3. `drainAsync` stdout/stderr on virtual threads, bounded to
   `ACP_MAX_OUTPUT_BYTES` (400 000);
4. `waitFor(ceiling)` against `subagent.maxWallClockSeconds` (default 1800);
   over the ceiling ⇒ `destroyForcibly`;
5. exit 0 ⇒ `RunResult(stdout.strip(), null)`; non-zero ⇒ `IllegalStateException`
   carrying stderr ⇒ the spawn records a `FAILED` outcome.

The label "acp" in the code today is a **misnomer** — it is not the Agent Client
Protocol, it is "run an external harness, blind." The harness is a black box:
JClaw sees one blob of stdout at the end and nothing in between. No token
streaming, no per-tool visibility, no permission handshake, no graceful cancel,
and — crucially — the harness runs **entirely outside** JClaw's tool gating and
workspace confinement (documented on `acpRuntimeError`, which is why `runtime:"acp"`
is gated on `Agent.acpAllowed` and restricted to main).

This spike asks: should the "live monitoring" upgrade be built as **(A)** a set of
per-harness stdout adapters, or **(B)** a true ACP client speaking JSON-RPC 2.0 over
stdio? And can `DangerousActionGate` satisfy ACP permission requests?

## 2. What ACP actually is

ACP (Agent Client Protocol, `agentclientprotocol.com`, originated by Zed Industries)
is an open **JSON-RPC 2.0 over stdio** protocol between a *client* (the editor /
orchestrator — here, JClaw) and an *agent* (the coding harness). It is "LSP for
coding agents": one spec'd, versioned wire format instead of N bespoke CLI output
formats. Messages are newline-delimited JSON-RPC frames on the agent process's
stdin/stdout.

### 2.1 Session lifecycle

| Step | Method (direction) | Purpose |
|---|---|---|
| 1 | `initialize` (client→agent) | Negotiate `protocolVersion`; exchange capabilities. Client advertises `fs.readTextFile`, `fs.writeTextFile`, `terminal`; agent advertises `loadSession`, `promptCapabilities`, `authMethods`. |
| 2 | `authenticate` (client→agent) | Optional — only when the agent's `authMethods` require it. |
| 3 | `session/new` (client→agent) | Create a session; params carry `cwd` and `mcpServers`. Returns `sessionId`. |
| 3′ | `session/load` (client→agent) | Optional resume of a prior `sessionId` (gated on `loadSession`). |
| 4 | `session/prompt` (client→agent) | Send the user prompt as content blocks (text/image/audio/resource). Blocks (as an RPC) until the turn ends; returns `{ stopReason }`. |
| 5 | `session/update` (agent→client, **notification**) | Streamed progress for the in-flight turn (see 2.2). |
| 6 | `session/request_permission` (agent→client, **request**) | Agent asks the client to authorize a tool call; blocks until the client answers (see §5). |
| 7 | `session/cancel` (client→agent, **notification**) | Cooperative cancel of the current turn. |

`session/prompt` resolves with a **`stopReason`**, one of: `end_turn`,
`max_tokens`, `max_turn_requests`, `refusal`, `cancelled`.

### 2.2 `session/update` variants (the live stream)

The agent streams these as it works (each a `session/update` notification):

- `user_message_chunk`, `agent_message_chunk`, `agent_thought_chunk` — token
  chunks (the assistant reply and its thinking);
- `tool_call` — a new tool invocation: `toolCallId`, `title`, `kind`
  (`read` / `edit` / `delete` / `move` / `search` / `execute` / `think` /
  `fetch` / `other`), `status` (`pending` / `in_progress` / `completed` /
  `failed`), `content` (text, diffs, terminal output), `locations`,
  `rawInput`/`rawOutput`;
- `tool_call_update` — status/content deltas for an existing `toolCallId`;
- `plan` — the agent's step plan;
- `available_commands_update`, `current_mode_update` — slash-command / mode changes.

### 2.3 Client-provided methods (agent → client requests)

Because ACP mediates file and terminal access *through the client*, the agent calls
back into JClaw:

- `fs/read_text_file`, `fs/write_text_file` — file I/O (gated on the client's
  advertised `fs` capabilities);
- `terminal/create`, `terminal/output`, `terminal/wait_for_exit`,
  `terminal/kill`, `terminal/release` — command execution mediated by the client;
- `session/request_permission` — the permission handshake (§5).

This callback surface is the structural payoff of ACP: file writes and command
execution flow **back through JClaw**, which is exactly the confinement point the
current one-shot path lacks.

## 3. Available bridges (the harnesses that already speak, or nearly speak, ACP)

| Harness | ACP support today | How JClaw would launch it |
|---|---|---|
| **Claude Code** | Via **`claude-code-acp`** (Zed's adapter; also community forks e.g. `harukitosa/claude-code-acp`, a PyPI package). Wraps the Claude Code CLI/SDK as an ACP agent over stdio; runs under a Pro/Max subscription, no API key. | `claude-code-acp` as the long-lived process; `initialize`→`session/new`→`session/prompt`. |
| **Gemini CLI** | **Native** — `gemini --experimental-acp` speaks ACP directly. ⚠️ Google is retiring Gemini CLI for free/personal users on 2026-06-18 in favour of Antigravity CLI (`agy`); treat this bridge as at-risk / migrate target. | `gemini --experimental-acp` directly (no adapter). |
| **OpenAI Codex** | **Near-ACP, not ACP.** Codex exposes its own **app-server** JSON-RPC-over-stdio protocol (`codex app-server` / `codex proto`). Same transport shape as ACP, different method set. Community bridges (e.g. `acp-bridge`, `codex-plugin-cc`) translate app-server ⇄ ACP. | Needs a **shim** (app-server → ACP) regardless of A/B. |
| **OpenCode, Qwen Code, others** | Growing ACP support; multi-agent orchestrators (`allvegetable/acp-bridge`) already drive Codex/Claude/Gemini/OpenCode through one ACP surface. | Same ACP client, once they speak it. |

**Key gap for a Java backend:** ACP's first-party libraries are Rust
(`agent-client-protocol` crate) and TypeScript (`@zed-industries/agent-client-protocol`).
**There is no first-party Java library.** A JClaw ACP client is therefore either
(a) a hand-rolled JSON-RPC-2.0-over-stdio codec in Java (Gson is already in the
stack, so this is bounded), or (b) shelling out to a Node bridge. This is the single
biggest cost line for option B.

## 4. Mapping ACP onto SubagentRun + the two monitoring rails

Whatever the transport, a *live* harness run has to feed JClaw's existing surfaces.
There are two rails today:

- **Chat SSE rail** — `ApiChatController` streams a turn; subagent activity surfaces
  in the parent conversation as `subagent_announce` / `subagent_send` messages
  (announce/resume model; batch spawns block-await, chat spawns announce+resume).
- **NotificationBus `/api/events` rail** — `ApiEventsController.stream()` bridges
  `NotificationBus.publish(type, data)` pushes to the SPA; this is what the
  SubagentRuns admin page and the `/subagent kill` flow watch.

Mapping:

| ACP signal | SubagentRun / rail target |
|---|---|
| `session/prompt` dispatched | `SubagentRun.status = RUNNING`, `startedAt` set (unchanged from today). |
| `agent_message_chunk` / `agent_thought_chunk` | Stream onto the **chat SSE rail** (real token streaming, like a native `AgentRunner` turn) — today's black box emits nothing until the end. Accumulate into `SubagentRun.outcome`. |
| `tool_call` / `tool_call_update` | Publish to **NotificationBus** as live subagent-activity events → the SubagentRuns page shows per-tool progress instead of a spinner. `SubagentRegistry.touch(runId)` on each, feeding the existing idle-detection (`nanosSinceActivity`). |
| `plan` | Optional progress metadata on the announce card / NotificationBus. |
| `stopReason=end_turn` | `status=COMPLETED`, `outcome`=accumulated agent text, `endedAt`. |
| `stopReason=refusal` / `max_tokens` / `max_turn_requests` | `status=FAILED` (or `TIMEOUT` for the wall-clock ceiling) with a reason string. |
| `stopReason=cancelled` | `status=KILLED` — reached via `session/cancel`. |
| operator kill (`/subagent kill`, admin button) | `SubagentRegistry.kill` flips the cooperative-cancel flag → send `session/cancel` (graceful, in-protocol) → keep `destroyForcibly` as the backstop. **Never** interrupt the stdio-reader/DB threads. |

Two notes that fall straight out of this table:

1. `session/cancel` is a *far* better cancel primitive than `destroyForcibly` — it
   lets the harness flush and clean up — but the existing force-kill stays as the
   backstop, and the cooperative-flag / no-interrupt discipline is unchanged.
2. The one-shot batch path (`runtime:"acp"` today) stays working as the
   "no live monitoring, just give me the final text" fallback — the live client is
   an *additional* transport behind the same seam, not a replacement.

## 5. Can `DangerousActionGate` satisfy ACP permission requests?

**Yes — structurally it's a near-perfect match, with one adapter in between.**

`DangerousActionGate.guard(agent, conversationId, toolName, argsJson) → Decision`
already does exactly the shape ACP needs: it **blocks the calling virtual thread**
on an interactive approve/deny surface (`TelegramApprovalService.await` /
Slack equivalent) and returns `PROCEED` / `ABORT`. ACP's `session/request_permission`
is *also* a blocking request (agent → client) that resolves to an option choice.
The wiring:

| ACP `session/request_permission` | `DangerousActionGate` |
|---|---|
| Request carries the `toolCall` + a list of `options`, each `{ optionId, name, kind }` with `kind ∈ { allow_once, allow_always, reject_once, reject_always }`. | `guard(...)` prompts and awaits a tap. |
| outcome `{ outcome: "selected", optionId }` for an `allow_*` option | `Decision.PROCEED`. `allow_always` → the existing `APPROVED_ALWAYS` path (persist a `ToolApprovalGrant`, keyed today by `(agentId, toolName)`). |
| outcome selecting a `reject_*` option, or `{ outcome: "cancelled" }` | `Decision.ABORT`. |

**Caveats to close before it's a drop-in:**

- **Identity mismatch.** The gate is keyed on JClaw's own `toolName` and its
  `ToolRegistry.isDangerous` set (today only `exec`). ACP tool calls carry a
  different identity (`toolCallId`, `kind`, `title`). The adapter must map ACP tool
  `kind` (`execute` / `edit` / `delete` are the dangerous ones) to a "dangerous?"
  decision and synthesize a stable grant key — likely `(agentId, acpToolKind)` — so
  `allow_always` still suppresses re-prompts.
- **Surface routing is unchanged and still limited.** The gate only has an
  interactive surface on Telegram / Slack with a usable binding; web and
  no-conversation contexts fall through to `tool.approval.offChannelPolicy`
  (default `allow`). ACP permission requests inherit exactly this — a web-initiated
  subagent run would auto-`allow` unless the operator has a bound chat. That's an
  acceptable, already-understood limitation, not a regression.
- **Net verdict:** this is *strictly better* than today, where the harness runs
  fully outside the gate. With ACP, dangerous file writes / command execution route
  back through JClaw and can be gated with the machinery that already exists. The
  reusable part is `promptAndAwait`; the new part is a thin `request_permission ⇄
  guard` translator (~80–120 LOC).

## 6. Recommendation: (A) per-harness stdout adapters vs (B) an ACP client

### The seam is the work — ~90% either way

The valuable, hard, shared part is **not** the wire decode. It's the
`HarnessAdapter` seam: replacing the one-shot `runAcpHarness` with a **long-lived
process manager** that

1. keeps the harness process alive across a turn (not fire-and-forget stdin);
2. reads an **event stream** off it on a VT (not one final `readNBytes`);
3. maps that stream onto `SubagentRun` status + the **two rails** (chat SSE +
   NotificationBus) + `SubagentRegistry` touch/idle;
4. bridges permission requests to `DangerousActionGate`;
5. cancels via `session/cancel` + `destroyForcibly` backstop, honoring the
   no-interrupt / cooperative-cancel discipline.

Every one of those five is identical whether events arrive as ACP `session/update`
frames or as `claude --output-format stream-json` NDJSON lines. **That seam is ~90%
of the refactor either way.** A and B differ *only* in the decode layer that turns
a process's bytes into internal events.

### The delta between A and B

| | **(A) Per-harness stdout adapters** | **(B) True ACP client** |
|---|---|---|
| Decode layer | N bespoke NDJSON/stream-json decoders — one per CLI (`claude` stream-json, `codex` app-server, `gemini` …). | One JSON-RPC-2.0-over-stdio client. Bidirectional (handles agent→client `fs/*`, `terminal/*`, `request_permission`). |
| Rough size (delta only) | ~150–250 LOC per harness × N, **plus** each vendor's format is **undocumented and drifts** independently. | ~400–600 LOC for one Gson-based JSON-RPC client (no first-party Java lib) **+** a Codex app-server shim (~150 LOC). One **spec'd, versioned** protocol to track. |
| Permission / fs / terminal | Must be reverse-engineered per CLI; some CLIs don't expose a permission handshake in `-p`/batch mode at all. | First-class in the protocol (`request_permission`, `fs/*`, `terminal/*`) — maps onto `DangerousActionGate` and workspace confinement. |
| Coverage | Each new harness = a new decoder + ongoing schema-drift maintenance. | Any ACP-speaking harness works for free; Codex needs a one-time shim. |

### Decision: **(B) — a true ACP client, built behind the HarnessAdapter seam, staged.**

Rationale:

1. **One spec'd protocol beats N drifting formats.** ACP is versioned and
   documented; per-CLI `stream-json` shapes are per-vendor, undocumented, and change
   under you. The maintenance asymptote favours B.
2. **Permission + fs + terminal mediation are the whole point.** The reason to go
   live at all is confinement + gating + visibility. ACP gives those as protocol
   primitives that map cleanly onto `DangerousActionGate` and the workspace boundary;
   A would have to reconstruct them per harness, and some batch CLIs simply don't
   expose them.
3. **The costs are bounded and testable.** The one real cost — no first-party Java
   ACP library — is a Gson stdio JSON-RPC codec, not open-ended; it's unit-testable
   against captured frames without a live model.
4. **A and B aren't exclusive.** B *is* the first transport plugged into the seam
   that A would have produced. Codex's app-server becomes a second adapter (a
   near-ACP shim) behind the same seam. So the architecture is "**HarnessAdapter seam
   + pluggable transports, ACP as transport #1**," not a fork in the road.

**Staging:**

1. Build the `HarnessAdapter` seam (the 90%): long-lived process, VT event reader,
   `SubagentRun` + two-rail mapping, `session/cancel`+backstop cancel, keep the
   one-shot batch path as the fallback transport.
2. Ship ACP transport #1 targeting `claude-code-acp` and `gemini --experimental-acp`
   (the two that speak ACP today — noting the Gemini CLI retirement risk).
3. Wire `request_permission ⇄ DangerousActionGate` (the ~80–120 LOC translator).
4. Add the Codex app-server shim as transport #2 behind the same seam.

Follow-on implementation tickets (seam, ACP transport, permission bridge, Codex
shim) should be cut under JCLAW-657 from this recommendation.

## 7. References

- Agent Client Protocol — overview, prompt-turn, schema: `agentclientprotocol.com`.
- `claude-code-acp` (Zed adapter; community fork `harukitosa/claude-code-acp`; PyPI `claude-code-acp`).
- Gemini CLI `--experimental-acp` (native ACP; retirement 2026-06-18 for free/personal users → Antigravity CLI `agy`).
- OpenAI Codex app-server (`codex app-server` / `codex proto`) — near-ACP JSON-RPC over stdio; bridges: `allvegetable/acp-bridge`, `codex-plugin-cc`.
- In-repo seam: `app/tools/SubagentSpawnTool.java` (`runAcpHarness`, `executeChildRun`, `acpRuntimeError`, `resolveAcpCommand`, `drainAsync`, `ACP_RUNS`, `ACP_COMMAND_KEY`, `ACP_MAX_OUTPUT_BYTES`, `MAX_WALLCLOCK_KEY`); `app/models/SubagentRun.java`; `app/services/SubagentRegistry.java`; `app/services/NotificationBus.java` + `app/controllers/ApiEventsController.java`; `app/agents/DangerousActionGate.java`.
