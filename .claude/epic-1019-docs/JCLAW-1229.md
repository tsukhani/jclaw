# JCLAW-1229 — doc fragment for AGENTS.md

Three separate insertions. Each names the section it belongs in.

---

## 1. Into **Known pitfalls**, appended to the existing `Personal Edition:` bullet

Extend the final sentence of that bullet (the one listing what refuses the agent principal, then
"Give an agent a narrow tool argument rather than reopening one of those.") with a worked example
of the narrow-argument route:

> The printer tool is the worked example of the narrow argument (JCLAW-1229): its `host`/`port`
> stay open, and `services.printing.PrintTargetGuard` classifies the destination instead —
> link-local, multicast and `0.0.0.0` are refused outright, and a well-formed address matching
> neither a discovered printer nor the saved default makes `PrinterTool.dangerous(args)` true so
> the call goes through `DangerousActionGate`. The saved default is exempt because a human chose
> it once, in Settings.

---

## 2. Into **Known pitfalls**, as a new bullet beside the other one-line traps

> A model-chosen network destination is screened with `SsrfGuard.isBlockedForProvider`, not
> `isUnsafe`: the strict guard blocks loopback and RFC-1918, which is where self-hosted inference
> and every LAN printer live. A per-attempt failure string returned to the model must not carry
> the transport exception — "refused" versus "timed out" is a port scan — so `PrintDispatcher`
> emits a fixed phrase per protocol and keeps the detail on `EventLogger.warn`.

---

## 3. Into **Outbound HTTP — OkHttp 5**, appended after the `runWith` paragraph

> **Which tier a call site picks.** The plain `llmStreaming()` / `llmSingleShot()` / `general()`
> clients carry no DNS screen; the `*Guarded()` twins wire `SsrfGuard.PROVIDER_SAFE_DNS`, which
> refuses link-local (the cloud-metadata address), multicast and `0.0.0.0` while permitting
> loopback and RFC-1918. Any client dialling a URL an operator or an agent can change reaches for
> the guarded twin, including the chat path: `OkHttpLlmHttpDriver`'s three sites moved there in
> JCLAW-1229, because a provider `baseUrl` screened once at discovery says nothing about where the
> host resolves on the next turn. The guarded clients are `newBuilder()`-derived from the plain
> ones, so pool, dispatcher, timeouts and the `LlmCallEventListener` are identical, and
> `HttpFactories.runWith` rebinds all six accessors — a test installing a canned transport is
> unaffected by the choice.
>
> `ConfigService.setWithSideEffects` runs `SsrfGuard.assertProviderUrlSafe` on any
> `provider.*.baseUrl` write, so the refusal is a 403 at the save rather than an opaque DNS error
> mid-turn. There is no dedicated provider-write action — `POST /api/config` is the write path,
> which is why the assert lives at the validation seam.
