# Real-Time Voice & TTS - Deep Dive Documentation

**Generated:** 2026-07-24
**Scope:** `app/services/voice/`, `app/services/tts/`, `app/controllers/VoiceController.java`, `app/controllers/ApiTtsController.java`, `app/services/LocalSidecarDaemon.java`, `app/services/sidecar/SidecarHttpClient.java`, `sidecar/tts/`, and the browser voice UI under `frontend/`
**Files Analyzed:** 28
**Lines of Code:** ~4,129
**Workflow Mode:** Exhaustive Deep-Dive
**Application Version:** 0.16.47

## Overview

This deep dive covers JClaw's two newest and least-documented audio subsystems, which meet at exactly one server-side call (`TtsRouter.synthesize`):

- **(A) Read-aloud TTS** — text-to-speech for the chat "speaker" icon and Settings, spanning a Java engine layer (`services.tts`), a REST controller (`ApiTtsController`), and a Python sidecar (`sidecar/tts/`).
- **(B) Real-time voice mode** — a full spoken-conversation cascade over a WebSocket (`WS /api/voice`): browser mic → server VAD + adaptive-silence endpointing → agent turn (native audio or local Whisper transcript) → streamed sentence-chunked TTS back to the browser, with server-driven barge-in.

**Purpose:** Give a future contributor everything needed to change voice/TTS behavior safely — the frame→VAD→endpoint→utterance pipeline, the two-tier TTS engine split (out-of-process Python sidecar vs in-JVM sherpa-onnx), the shared local-sidecar lifecycle machinery, and the browser audio protocol — without re-reading 4,000 lines to reconstruct the invariants.

**Key Responsibilities:**
- Turn a continuous PCM16 mic stream into finalized utterances with onset-preserving, semantically-aware endpointing (JCLAW-795/797/798/799/845).
- Route each utterance to an agent turn and stream the reply back as gapless, barge-in-able TTS audio (JCLAW-791/800).
- Synthesize speech through either a GPU-capable Python sidecar (Qwen3-TTS / Kokoro / Chatterbox) or an always-available in-JVM sherpa-onnx engine (Piper / Kokoro), selectable per request with no restart (JCLAW-789/793/814/846).
- Provision and health-manage local Python inference sidecars uniformly (JCLAW-828/830).

**Integration Points:** `WS /api/voice` and `GET/POST /api/tts/*` (routes); `AgentRunner.runStreaming` (agent orchestration); `AsrSidecarClient` (local STT); `LatencyStats` (Chat Performance dashboard); the shared `LocalSidecarDaemon` (ASR / diarize / TTS / image / video); `POST /api/config` (engine/model/voice selection, read fresh per call).

## Complete File Inventory

The 28 files group into six layers. Within each entry, line references use `file:line`.

---

### app/services/voice/VoiceSession.java

**Purpose:** Per-connection streaming orchestrator (JCLAW-799). Chains `PcmWindower → VoiceVad → TurnEndpointer`, owns the pre-roll onset ring and the growing-utterance buffer, and mints the final 16 kHz mono WAV emitted through a `Listener`.
**Lines of Code:** 154
**File Type:** Java service (stateful, single-threaded)

**What Future Contributors Must Know:** Not thread-safe and stateful — it must stay on the single WebSocket inbound thread. `frameIndex` never resets within a session, so the synthetic timestamps grow monotonically (which `TurnEndpointer` requires). The `ENDPOINT` branch deliberately drops the trailing silence window — do not "fix" it by appending the closing window. Onset preservation lives entirely in the `SPEECH_STARTED` pre-roll replay; changing pre-roll size/eviction changes clipped-onset behavior.

**Exports:**
- `VoiceSession(VoiceVad, TurnEndpointer, int prerollWindows, Listener)` / `(…, Partial, long partialIntervalMs)` - the pipeline; two ctors (with/without interim partials).
- `interface Listener { void onSpeechStart(); void onUtterance(byte[] wav); }` - turn-boundary callbacks on the inbound thread.
- `@FunctionalInterface interface Partial { void onInterim(byte[] wav); }` - throttled interim (pre-endpoint) WAV hook (JCLAW-798); `null` disables it.
- `void onPcm(byte[] pcm, int len)` - feed one raw LE-PCM16 browser frame.
- `static byte[] wrapWav(byte[] pcm)` - wrap PCM16 mono samples in a 16 kHz WAV container (public for ASR prewarm).
- `void close()` - releases the native VAD.

**Dependencies:**
- `services.voice.VoiceVad` - constants (`WINDOW`, `WINDOW_MS`, `SAMPLE_RATE`) + `isSpeech`/`close`.
- `services.voice.TurnEndpointer` - `accept`, `Event`.
- `services.voice.PcmWindower` - constructed internally with `new PcmWindower(VoiceVad.WINDOW)`.
- JDK only otherwise (`ByteArrayOutputStream`, `ByteBuffer`, `ArrayDeque`).

**Used By:**
- `app/controllers/VoiceController.java` (sole consumer; held in an `AtomicReference<VoiceSession>`).

**Key Implementation Details:**

```java
// onWindow — the core switch on the endpointer result
boolean speech = vad.isSpeech(w);
long tsMs = frameIndex++ * VoiceVad.WINDOW_MS;   // synthetic monotonic clock
switch (endpointer.accept(speech, tsMs)) {
    case SPEECH_STARTED -> { /* replay pre-roll ring, fire listener.onSpeechStart() */ }
    case ENDPOINT       -> { /* snapshot buffer, wrapWav, fire listener.onUtterance() */ }
    case NONE           -> { /* buffering ? append + maybeEmitPartial : push to pre-roll */ }
}
```

`maybeEmitPartial` throttles interim WAVs by `partialIntervalMs`. `appendWindow` quantizes float→PCM16 with `Math.round(Math.clamp(f,-1,1) * 32767)`. `wrapWav` hand-builds a 44-byte RIFF/WAVE header (mono, 16-bit, `VoiceVad.SAMPLE_RATE`). `PREROLL_WINDOWS=10` in the controller ≈ 320 ms of preserved onset.

**Patterns Used:**
- Pipeline / Chain: windower → VAD → endpointer → assembler.
- Ring buffer (bounded `ArrayDeque`) for onset pre-roll.
- Callback/Listener for turn boundaries (keeps the orchestrator transport-agnostic).

**State Management:** In-memory only — `ByteArrayOutputStream utterance`, `ArrayDeque<float[]> preroll`, `frameIndex`, `buffering`. No threads.

**Side Effects:** None beyond in-memory buffering; `close()` releases the native Silero VAD.

**Error Handling:** Minimal by design — floors `prerollWindows` to 0, relies on `Math.clamp`; exceptions propagate to the WS loop.

**Testing:**
- Test File: none direct.
- Coverage: transitive (via `PcmWindowerTest`/`TurnEndpointerTest`) + manual WS runs.
- Test Approach: N/A — orchestrator is a known coverage gap.

**Comments/TODOs:** None load-bearing.

---

### app/services/voice/PcmWindower.java

**Purpose:** Reframes an arbitrarily-chunked little-endian PCM16 byte stream into fixed-size float windows (JCLAW-799), buffering across frame boundaries including a single odd byte that splits a sample.
**Lines of Code:** 67
**File Type:** Java utility (pure, stateful, deterministic)

**What Future Contributors Must Know:** The window buffer is reused; only the emitted `clone()` is safe to retain. Always honor the `len` argument (may be < `pcm.length`). Call `reset()` at session boundaries or a stale `pendingByte` corrupts the first sample of the next stream. Input normalizes with `/32768f`; `VoiceSession` re-quantizes with `*32767f` — an intentional float-in/PCM-out asymmetry.

**Exports:**
- `PcmWindower(int windowSize)` - throws if `windowSize <= 0`.
- `void accept(byte[] pcm, int len, Consumer<float[]> onWindow)` - fires `onWindow` once per full window with a fresh copy.
- `void reset()` - drop partial-window / split-sample state.

**Dependencies:** `java.util.function.Consumer` only.

**Used By:** `services.voice.VoiceSession`; `test/PcmWindowerTest.java`.

**Key Implementation Details:** Handles the split-sample case first (`(short)((pcm[i++] << 8) | pendingByte)`), then consumes pairs `while (i + 1 < len)`, stashing a trailing lone byte in `pendingByte`. `push` normalizes `sample16 / 32768f` and emits `window.clone()` when full.

**Patterns Used:** Streaming reframe with carry state; callback emit.

**State Management:** Reusable `float[] window`, `filled`, `pendingByte` (-1 = none).

**Side Effects:** None.

**Error Handling:** Constructor guard on non-positive window size; no null checks (contract).

**Testing:**
- Test File: `test/PcmWindowerTest.java` (65 LOC, 3 tests).
- Coverage: strong on the tricky boundaries (remainder buffering, sample-split reassembly, reset).
- Test Approach: direct unit with normalization value asserts.

**Comments/TODOs:** None.

---

### app/services/voice/VoiceVad.java

**Purpose:** JVM-native voice-activity detection (JCLAW-797) via the sherpa-onnx Silero VAD — reusing the ONNX stack already on the classpath for the TTS engine (no new dependency, no sidecar hop). Reports per-window speech/non-speech.
**Lines of Code:** 140
**File Type:** Java service (native model wrapper, stateful)

**What Future Contributors Must Know:** The `WINDOW` (512), `WINDOW_MS` (32), and `SAMPLE_RATE` (16000) constants are a shared contract consumed across the pipeline — changing `WINDOW` breaks Silero's fixed-window requirement; changing `SAMPLE_RATE` breaks `wrapWav` and the resample assumption. Not thread-safe (holds streaming state); one instance per session. Always pair construction with `close()` — the controller's `handedOff` flag exists to avoid leaking a partially-wired native VAD.

**Exports:**
- `VoiceVad()` - provisions the Silero model (download-on-first-use) and constructs the native `Vad`.
- `static final int WINDOW = 512`, `SAMPLE_RATE = 16_000`, `long WINDOW_MS = 32`.
- `boolean isSpeech(float[] window)` - per-window speech state.
- `void reset()` / `void close()` - clear streaming state / release native memory.
- `static Path ensureModel()` - absolute path to the weights, downloading on first use.

**Dependencies:** sherpa-onnx (`SileroVadModelConfig`, `Vad`, `VadModelConfig`), OkHttp (`Request`), `play.Play`/`Logger`, `services.ConfigService`, `utils.HttpFactories`.

**Used By:** `app/controllers/VoiceController.java` (constructs, closes in `finally`, hands to `VoiceSession`); constants referenced across the pipeline.

**Key Implementation Details:** Deliberately minimal sherpa hysteresis (`minSilenceDuration=0.05`, `minSpeechDuration=0.05`) because `TurnEndpointer` owns the adaptive silence — the VAD should report near-raw per-window state. Threshold from `voice.vad.threshold` (0.5). `ensureModel` resolves `data/voice-models/silero_vad.onnx`, downloading from `voice.vad.modelUrl` (default k2-fsa release, ~2 MB) atomically via a `.part` temp + `Files.move`.

**Patterns Used:** Lazy download-on-demand provisioning (mirrors `TtsJvmEngine`); atomic file publish.

**State Management:** Native sherpa `Vad` streaming state.

**Side Effects:** Native ONNX model load in ctor; first-use network download + disk write; native free on `close()`.

**Error Handling:** `IOException` → `UncheckedIOException` (model resolve); non-2xx → `IOException`; `NumberFormatException` in config → default. Controller `handedOff` guard prevents leaks.

**Testing:**
- Test File: none (requires native lib + model download).
- Coverage: manual / behind an integration profile.
- Test Approach: N/A — coverage gap.

**Comments/TODOs:** None.

---

### app/services/voice/TurnEndpointer.java

**Purpose:** Adaptive-silence turn endpointing state machine (JCLAW-797). Consumes a per-frame speech/non-speech signal with monotonic timestamps and emits `NONE` / `SPEECH_STARTED` / `ENDPOINT`; closes an utterance when trailing silence exceeds an adaptive threshold (short if the turn "looks complete", longer otherwise). Pure and deterministic.
**Lines of Code:** 122
**File Type:** Java state machine (pure)

**What Future Contributors Must Know:** `utteranceStartMs` is backdated to `speechRunStart` (not the frame that crossed the threshold), so `minUtteranceMs` measures onset→last-speech. The monotonic-timestamp contract is load-bearing: `silence = tsMs - lastSpeechMs` never closes if the clock goes backward. The `Confirmer` runs on the hot path **every silent frame** — implementations must be cheap and side-effect-free.

**Exports:**
- `enum Event { NONE, SPEECH_STARTED, ENDPOINT }`.
- `@FunctionalInterface interface Confirmer { boolean looksComplete(); }` + `static final Confirmer ALWAYS_COMPLETE = () -> true`.
- `TurnEndpointer(long speechStartMs, long baseSilenceMs, long maxSilenceMs, long minUtteranceMs, Confirmer)` - throws if `baseSilenceMs > maxSilenceMs`.
- `Event accept(boolean speech, long tsMs)` - feed one VAD frame.
- `boolean isSpeaking()` / `void reset()` - open-utterance query / barge-in reset.

**Dependencies:** none (pure Java).

**Used By:** `app/controllers/VoiceController.java`, `services.voice.VoiceSession`, `services.voice.TextTurnConfirmer` (implements `Confirmer`); `test/TurnEndpointerTest.java`.

**Key Implementation Details:** While speaking + silence: `threshold = confirmer.looksComplete() ? baseSilenceMs : maxSilenceMs`. Closing checks `longEnough = (lastSpeechMs - utteranceStartMs) >= minUtteranceMs`, `reset()`, returns `ENDPOINT` if long enough else `NONE` (drops sub-minimum blips). Querying the confirmer per silent frame lets a mid-turn transcript change flip the threshold dynamically.

**Patterns Used:** Finite state machine; strategy hook (`Confirmer`).

**State Management:** Four fields — `speaking`, `speechRunStart`, `lastSpeechMs`, `utteranceStartMs`. No allocation on the hot path.

**Side Effects:** None.

**Error Handling:** Constructor guard (`IllegalArgumentException`); contract-based otherwise.

**Testing:**
- Test File: `test/TurnEndpointerTest.java` (101 LOC, 8 tests).
- Coverage: strong — open/gap-reset/hold-through-pause/incomplete-extends-to-max/drop-sub-minimum/reset/reopen/ctor-guard.
- Test Approach: direct unit with a `() -> false` confirmer for the max-silence path.

**Comments/TODOs:** None.

---

### app/services/voice/TextTurnConfirmer.java

**Purpose:** Cheapest fill for the `TurnEndpointer.Confirmer` seam (JCLAW-845). Reads the latest interim transcript and holds the turn open (toward `maxSilenceMs`) when the utterance ends mid-clause (a function/filler word), else lets it close at the fast `baseSilenceMs`.
**Lines of Code:** 82
**File Type:** Java strategy (pure heuristic)

**What Future Contributors Must Know:** The hold-bias is a deliberate invariant — a false hold only waits; a false cut fragments the utterance. The wh-word/auxiliary exclusion from `HOLD_WORDS` is intentional (they often end complete questions/answers); re-adding them causes over-holding. `looksComplete` runs per silent frame — keep it O(word length).

**Exports:**
- `TextTurnConfirmer(Supplier<String> latestTranscript)` - pulls the live transcript from a supplier.
- `boolean looksComplete()` (instance) - delegates to the static form.
- `static boolean looksComplete(String transcript)` - pure heuristic, unit-tested directly.

**Dependencies:** `java.util.{Locale, Set, function.Supplier}`; implements `TurnEndpointer.Confirmer`.

**Used By:** `app/controllers/VoiceController.java` (`new TextTurnConfirmer(latestPartial::get)` when `voice.endpoint.semanticHold != false`); `test/TextTurnConfirmerTest.java`.

**Key Implementation Details:** `null`/blank → `true` (degrade to fixed-silence, matching `ALWAYS_COMPLETE`). Sentence-final punctuation `.!?` → `true`. Otherwise extract the trailing word (letters + apostrophes), lowercase, return `!HOLD_WORDS.contains(word)`. `HOLD_WORDS` = conjunctions/articles/determiners/prepositions/fillers.

**Patterns Used:** Strategy; supplier indirection (decouples endpointer from transcript production).

**State Management:** Stateless (the supplier reads a controller `AtomicReference<String>`).

**Side Effects:** None.

**Error Handling:** Null/blank-safe; no exceptions.

**Testing:**
- Test File: `test/TextTurnConfirmerTest.java` (64 LOC, 6 tests).
- Coverage: thorough — blank/null, punctuation, function-word hold, content-word complete, trailing-punctuation/case, live-supplier.
- Test Approach: direct unit on the static form + a supplier-backed instance test.

**Comments/TODOs:** Non-Latin scripts resolve to "content word" (complete) — acceptable given the hold-bias; note if extending to other languages.

---

### app/services/voice/VoiceTurnMetrics.java

**Purpose:** Per-turn voice-pipeline latency recording (JCLAW-800). Records STT, first-chunk TTS synth, and the two voice-to-voice headline numbers into the same `LatencyStats` histograms/persisted rows as the LLM segments, all measured from the endpoint `t0`, so the Chat Performance dashboard shows one full breakdown for channel `voice`.
**Lines of Code:** 91
**File Type:** Java instrumentation helper

**What Future Contributors Must Know:** The segment-name constants drive dashboard filters and persisted rows — renaming them is a breaking analytics change. All offset-based stages assume `endpointNs` is the true `t0`. `firstAudioSent` idempotency (the `replyRecorded` guard) is intentional — callers may invoke it per chunk. `ttsSynth(ms)` is a raw duration, not an offset — don't normalize it to the clock.

**Exports:**
- `static final String CHANNEL = "voice"`, `STT`, `TTS_SYNTH`, `REPLY`, `TURN` - segment names.
- `VoiceTurnMetrics(@Nullable String agentId, long endpointNs)` / `(…, LongSupplier clockNs)` - prod + clock-injection ctors.
- `void sttDone()` / `void ttsSynth(long ms)` / `void firstAudioSent()` / `void turnComplete()` - record each stage.

**Dependencies:** `utils.LatencyStats` (sink), `org.jspecify.annotations.Nullable`, `java.util.function.LongSupplier`.

**Used By:** `app/controllers/VoiceController.java`; `test/VoiceTurnMetricsTest.java`.

**Key Implementation Details:** `sinceEndpointMs = (clockNs.getAsLong() - endpointNs) / 1_000_000`. `t0` is shared with the turn's own nanoTime stamp so every stage shares one origin. `firstAudioSent` records `voice_reply` only once.

**Patterns Used:** Clock injection for deterministic tests; idempotent recording.

**State Management:** `endpointNs`, injected `LongSupplier`, `replyRecorded` guard.

**Side Effects:** Writes latency samples to `LatencyStats` (in-memory histograms + persisted-row queue).

**Error Handling:** None explicit; `@Nullable agentId` passes through (a null agent still records).

**Testing:**
- Test File: `test/VoiceTurnMetricsTest.java` (83 LOC, 3 tests).
- Coverage: each-stage-from-endpoint, firstAudioSent-idempotent, null-agentId-records.
- Test Approach: `FakeClock implements LongSupplier`; resets `LatencyStats`/`LatencyMetricRecorder` in `@BeforeEach`.

**Comments/TODOs:** None.

---

### app/controllers/VoiceController.java

**Purpose:** Real-time voice-mode WebSocket (`WS /api/voice`, JCLAW-791). Streams the mic in, runs VAD + endpointing, turns each utterance into an agent turn (native audio for audio-capable models, local Whisper transcript otherwise), and streams the reply back as sentence-chunked TTS audio frames with server-driven barge-in.
**Lines of Code:** 574
**File Type:** Play WebSocket controller

**What Future Contributors Must Know:** Barge-in means two turn threads can be briefly live (a cancelled turn winding down + its successor); **all** frames must go through the `send(out, writeLock, …)` helper, which synchronizes and checks `out.isOpen()`. The semantic-hold path only works when interim transcripts run (text-only models); audio-native models get `ALWAYS_COMPLETE`. Respect the `handedOff` flag — the native VAD must be closed exactly once. The turn is tagged `VOICE_CHANNEL="voice"` (not the conversation, which stays `"web"`) so `SystemPromptAssembler` injects spoken guidance — removing it makes the model deny it can hear the user.

**Exports:**
- `static void socket()` - the sole route target; the WS event loop.
- `static boolean originMatchesHost(String origin, String host)` - pure CSWSH Origin-vs-host comparison (extracted for unit testing).
- Everything else is `private static`.

**Dependencies:**
- Voice/STT/TTS: `services.voice.{VoiceSession, VoiceVad, TurnEndpointer, TextTurnConfirmer, VoiceTurnMetrics}`, `services.transcription.AsrSidecarClient`, `services.tts.{TtsRouter, TtsText, TtsException}`.
- Agent/model: `agents.{AgentRunner, ModelResolver}`, `llm.ProviderRegistry`, `services.{AgentService, AttachmentService, ConversationService, ConfigService, Tx}`.
- Framework: `play.mvc.WebSocketController`, `play.mvc.Http.*`, Gson, virtual threads.

**Used By:** `conf/routes:41` (`WS /api/voice → VoiceController.socket`); `test/VoiceControllerTest.java` (`originMatchesHost`).

**Key Implementation Details:**

```
WS protocol
  client→server:  {"type":"init","agentId":N} | <binary PCM16> | {"type":"cancel"} | {"type":"bye"}
  server→client:  ready | state(capturing|thinking) | flush | transcript | reply
                  | audio(base64 wav, indexed) | turn_complete | error
```

`socket()` runs `sameOrigin()` + `authenticated()` guards, then a `for (event : inbound)` pattern switch; a per-frame try/catch keeps a bad frame from dropping the socket; `finally` cancels the turn and closes the session (releases the VAD). `initSession` builds the endpointer from config knobs (`voice.endpoint.speechStartMs`=180, `baseSilenceMs`=500, `maxSilenceMs`=1500, `minUtteranceMs`=200), resolves audio capability once, prewarms ASR for text-only models, and wires the interim-transcript partial sink into `latestPartial`. `onUtterance` spawns a `voice-turn-<id>` virtual thread running `runTurn`, which resolves the shared "web" conversation + `nativeAudio` in one tx, then either stages the WAV as a native `input_audio` attachment or transcribes via `AsrSidecarClient`, calls `AgentRunner.runStreaming(...)` with the barge-in cancel flag, buffers tokens into complete sentences (`maxRunOn`=`voice.tts.maxRunOnChars`=220), and the consumer loop runs `TtsText.toSpeakable → TtsRouter.synthesize → base64 → audio frame`. `VoiceTurnMetrics` records STT / first-audio / voice-to-voice under channel `voice`.

**Patterns Used:** Event-loop over WS inbound; virtual-thread-per-turn; single-flight interim (`interimBusy`); server-driven barge-in via a shared `AtomicBoolean` cancel flag + `flush` frame; monotonic turn-id gating for straggler suppression.

**State Management:** Per-connection: `writeLock`, `AtomicReference<AtomicBoolean> current`, `AtomicInteger turnSeq`, `AtomicReference<VoiceSession> sessionRef`, `AtomicReference<String> latestPartial`.

**Side Effects:** WS I/O; virtual threads; native VAD lifecycle; ASR sidecar spawn; temp + staged WAV files; JPA transactions.

**Error Handling:** CSWSH + session-cookie-with-DB-hash-cross-check auth guards; per-frame try/catch; `runTurn` emits an `error` frame (only if not cancelled); `InterruptedException` re-interrupts.

**Testing:**
- Test File: `test/VoiceControllerTest.java` (covers `originMatchesHost` only; WS upgrade can't run under FunctionalTest).
- Coverage: origin logic strong; WS wiring manual.
- Test Approach: pure-function extraction for the testable security predicate.

**Comments/TODOs:** WS wiring exercised manually (documented in the test).

---

### app/services/tts/TtsRouter.java

**Purpose:** The single dispatch point for read-aloud synthesis. Reads the operator's engine selection fresh per call and routes text to either the sidecar HTTP client or the in-JVM sherpa engine.
**Lines of Code:** 53
**File Type:** Java dispatcher (static)

**What Future Contributors Must Know:** Adding a third engine = a new `TtsEngine` value + a new `case` in the exhaustive switch (the compiler flags the missing arm — the intended safety net). `voiceFor` can return `null` and is forwarded to both engines; both tolerate it (sidecar omits blank fields, JVM maps null→speaker 0). `modelFor` guards against a stale cross-engine model id.

**Exports:**
- `static TtsEngine currentEngine()` - resolves `tts.engine`, default-safe.
- `static String modelFor(TtsEngine)` - `tts.<engine>.model` if set + valid + engine-matched, else the engine default.
- `static String voiceFor(TtsEngine)` - raw `tts.<engine>.voice` (may be null).
- `static byte[] synthesize(String text)` - the load-bearing dispatch.

**Dependencies:** `TtsEngine`, `TtsModel`, `TtsSidecarClient`, `TtsJvmEngine`, `services.ConfigService`.

**Used By:** `app/controllers/ApiTtsController.java`; `app/controllers/VoiceController.java`.

**Key Implementation Details:**

```java
return switch (engine) {
    case SIDECAR -> SIDECAR.synthesize(text, model, voice, "wav");    // format "wav"
    case JVM     -> TtsJvmEngine.synthesize(text, model, voice, null); // speed null → 1.0
};
```

Holds one shared `TtsSidecarClient SIDECAR` (owns the JVM-wide fair lock + HTTP pool).

**Patterns Used:** Exhaustive-switch dispatch; config-read-per-call (no restart to switch engine/model/voice).

**State Management:** One static client instance; all selection is config.

**Side Effects:** None itself; delegates spawn/download to the engines.

**Error Handling:** Propagates `TtsException` from either engine.

**Testing:**
- Test File: none direct.
- Coverage: enum logic covered indirectly by `TtsModelTest`; dispatch + cross-engine `modelFor` guard untested.
- Test Approach: gap — a stubbed `ConfigService` `TtsRouterTest` is the suggested fill.

**Comments/TODOs:** None.

---

### app/services/tts/TtsEngine.java

**Purpose:** Enum of the two selectable backends (`SIDECAR`, `JVM`) plus safe config-resolution helpers.
**Lines of Code:** 52
**File Type:** Java enum

**What Future Contributors Must Know:** `id()` strings are persisted config values and the routing tokens — never rename without a migration. `DEFAULT = SIDECAR` (quality-first).

**Exports:** `id()`, `displayName()`, `static Optional<TtsEngine> byId(String)`, `static TtsEngine fromConfigOrDefault(String)` (null/blank/unknown → `DEFAULT`).

**Dependencies:** `java.util.Optional`.

**Used By:** `TtsRouter`, `TtsModel`, `ApiTtsController`, `jobs.DefaultConfigJob`; `TtsModelTest`.

**Key Implementation Details:** Total functions with a default fallback so a stale key never breaks read-aloud.

**Patterns Used:** Null-object-style default fallback.

**State Management:** None (enum constants).

**Side Effects:** None.

**Error Handling:** None needed (total).

**Testing:**
- Test File: `test/TtsModelTest.java` (`engineResolvesByIdAndFallsBackToDefault`).
- Coverage: by-id + null/blank/bogus → DEFAULT.
- Test Approach: direct unit.

**Comments/TODOs:** None.

---

### app/services/tts/TtsModel.java

**Purpose:** Catalog of self-hosted TTS models, each tagged with its serving `TtsEngine`; declaration order == Settings dropdown order == default selection.
**Lines of Code:** 70
**File Type:** Java enum

**What Future Contributors Must Know:** Adding a JVM model needs **two** coordinated edits — a `TtsModel` row AND a `TtsJvmEngine.SPECS` entry (plus optionally a `TtsVoiceCatalog` entry). Adding a sidecar model needs a matching `MLX_REPOS` entry in `sidecar/tts/synth.py`. Do NOT reorder the SIDECAR block — `CHATTERBOX` is listed last so the SIDECAR default stays Qwen3.

**Exports:**
- Six values: `QWEN3_06B`, `QWEN3_06B_4BIT`, `KOKORO_MLX`, `CHATTERBOX` (SIDECAR); `PIPER_AMY`, `KOKORO_SHERPA` (JVM).
- `engine()`, `id()`, `displayName()`, `approxSizeMb()`.
- `static Optional<TtsModel> byId(String)`, `static List<TtsModel> forEngine(TtsEngine)`, `static TtsModel defaultFor(TtsEngine)`.

**Dependencies:** `java.util.{ArrayList,List,Optional}`; `TtsEngine`.

**Used By:** `TtsRouter`, `ApiTtsController`, `jobs.DefaultConfigJob`; `TtsModelTest`.

**Key Implementation Details:** The `id` is the contract token — for SIDECAR it's the `/synthesize` `model` field (→ `synth.py` `MLX_REPOS`); for JVM it's the `TtsJvmEngine.SPECS` key. `defaultFor` = `forEngine(engine).get(0)` (implicit invariant: every engine has ≥1 model).

**Patterns Used:** Registry enum; declaration-order-as-priority.

**State Management:** None.

**Side Effects:** None.

**Error Handling:** `byId` returns `Optional`; `defaultFor` throws if an engine has zero models.

**Testing:**
- Test File: `test/TtsModelTest.java` (4 tests incl. `chatterboxIsASidecarModelButNotTheDefault`).
- Coverage: engine-tagging, first-listed default, by-id round-trip, the "Chatterbox listed last" invariant.
- Test Approach: direct unit.

**Comments/TODOs:** `approxSizeMb` is a Settings progress denominator until a live size replaces it.

---

### app/services/tts/TtsSidecarClient.java

**Purpose:** OkHttp client for the local Python TTS sidecar; sends TEXT, receives AUDIO BYTES (WAV). Serializes all sidecar calls JVM-wide so the sidecar's one-at-a-time 409 never surfaces to users.
**Lines of Code:** 79
**File Type:** Java HTTP client (extends `SidecarHttpClient`)

**What Future Contributors Must Know:** Do NOT lower the base client's `readTimeout=0` without keeping the per-call `timeoutSeconds` bound — that call-timeout is the only thing bounding a hung socket (JCLAW-626). The `(baseUrlOverride, client)` ctor is an unused-in-repo test seam — inject a MockWebServer through it.

**Exports:**
- `TtsSidecarClient()` (prod) / `TtsSidecarClient(String baseUrlOverride, OkHttpClient)` (test seam).
- `@Override protected ReentrantLock sidecarLock()` - the class-static FAIR lock.
- `byte[] synthesize(String text, String model, String voice, String format)` - wraps `synthesizeLocked` in `withSidecarLock`.

**Dependencies:** Gson, OkHttp; base `services.sidecar.SidecarHttpClient`, `TtsSidecarManager`, `TtsException`, `services.ConfigService`.

**Used By:** `TtsRouter` (the shared `SIDECAR` instance).

**Key Implementation Details:** Base URL from `TtsSidecarManager.ensureRunning()` (may spawn + download); builds a Gson body adding `model`/`voice`/`format` only when non-blank (lets the sidecar apply defaults); per-call timeout = `tts.local.timeoutSeconds` (1800s default); non-2xx → `TtsException` with `truncate`d body. Only sends `text/model/voice/format` — the `ref_audio/ref_text/speed` cloning fields the sidecar accepts are not wired from Java yet (JCLAW-847).

**Patterns Used:** Template-method base (`SidecarHttpClient`); fair-lock serialization.

**State Management:** Class-static fair `ReentrantLock`.

**Side Effects:** Triggers sidecar spawn + weight download via `ensureRunning`; network I/O.

**Error Handling:** `IOException` → `TtsException("unreachable")`; HTTP failure → `TtsException` with truncated body.

**Testing:**
- Test File: none dedicated.
- Coverage: 409/500 error-body formatting + lock serialization uncovered.
- Test Approach: gap — the second ctor is the injection point.

**Comments/TODOs:** None.

---

### app/services/tts/TtsSidecarManager.java

**Purpose:** Lifecycle owner (spawn/health/stop) for the one-per-JVM Python TTS sidecar, delegating to the shared `LocalSidecarDaemon`.
**Lines of Code:** 66
**File Type:** Java manager (static facade)

**What Future Contributors Must Know:** `IDENTITY="tts"` is a `/health` fingerprint, not a model id. The `9531` port default is duplicated in `DefaultConfigJob` (`seedIfAbsent("tts.local.port","9531")`) — keep them in sync. Don't cache running state in callers; the 300s idle self-eviction makes it stale — always route through `ensureRunning`.

**Exports:**
- `static final String IDENTITY = "tts"`, `static final String CONFIG_PREFIX = "tts.local"`.
- `static String ensureRunning()` - idempotent single-flight; returns base URL.
- `static boolean isRunning()` - cheap liveness for `/api/tts/state`.
- `static void stop()` - wired into `jobs.ShutdownJob`.

**Dependencies:** `services.LocalSidecarDaemon`, `services.UvProbe`, `TtsException`.

**Used By:** `TtsSidecarClient`, `ApiTtsController`, `jobs.ShutdownJob`.

**Key Implementation Details:** `DAEMON` `Config`: script dir `sidecar/tts`, model dir `data/tts-models`, port 9531, 300s idle timeout, exception factory `TtsException::new`. `ensureRunning` fast-paths on health, else `singleFlight`: re-check health → verify `UvProbe.isAvailable()` → `spawn(IDENTITY, null)` → `awaitHealthy`.

**Patterns Used:** Single-flight; delegation to shared daemon.

**State Management:** One static `LocalSidecarDaemon`.

**Side Effects:** Spawns the Python process; first launch installs uv deps + downloads weights; HTTP health polling.

**Error Handling:** `TtsException` on missing uv (with `UvProbe.lastResult().reason()`), missing script, or health timeout.

**Testing:**
- Test File: none dedicated (spawn logic lives in `LocalSidecarDaemon`).
- Coverage: via daemon tests.
- Test Approach: N/A.

**Comments/TODOs:** None.

---

### app/services/tts/TtsJvmEngine.java

**Purpose:** The in-process JVM-native engine: sherpa-onnx `OfflineTts` running ONNX models (Piper VITS / Kokoro) with no Python. Lazily downloads/extracts sherpa release archives, caches one `OfflineTts` per model, serializes generation under a fair lock.
**Lines of Code:** 285 (largest file in scope)
**File Type:** Java native-engine wrapper (static)

**What Future Contributors Must Know:** Adding a model needs a `SPECS` entry AND a `TtsModel` row; the `onnx` field is both the config model filename and the "extraction complete" marker, so it must match the archive's real filename. `extractTarBz2` shells out to host `tar -xjf` (a POSIX + bzip2 portability assumption). The download client's `readTimeout=0` is bounded only by `downloadTimeoutSeconds` (same JCLAW-626 caveat). `release()` and `synthesize()` share `LOCK`, so shutdown waits for an in-flight generate.

**Exports:**
- `static boolean isKnownModel/isModelPresent/isDownloading(String)` - drive `/api/tts/state`.
- `static void prefetch(String modelId)` - off-request-path provisioning (single-flight; does NOT build the `OfflineTts`).
- `static byte[] synthesize(String text, String modelId, String voice, Float speed)` - lock → `ensureLoaded` → `tts.generate(text, speakerId(voice), spd)` → `toWav`.
- `static void release()` - native free of all cached engines (wired into `ShutdownJob`).

**Dependencies:** sherpa-onnx (`OfflineTts*` configs), OkHttp, `play.{Logger, Play}`, `TtsException`, `services.ConfigService`, `utils.HttpFactories`.

**Used By:** `TtsRouter`, `ApiTtsController`, `jobs.ShutdownJob`.

**Key Implementation Details:** `SPECS` = the JVM analogue of `MLX_REPOS` (Piper `en_US-amy-low.onnx` VITS; Kokoro `model.onnx`) from the k2-fsa `tts-models` release. `speakerId(voice)` parses the router's `voice` string as a numeric index (blank/invalid → 0). `ensureModel` uses a marker-file idempotency (`createDirectories → downloadTo → extractTarBz2 → delete archive → verify marker`). `buildConfig` wires espeak-ng dataDir (VITS) or voices.bin + optional lexicon/dict (Kokoro). `toWav` round-trips through a temp file.

**Patterns Used:** Lazy provisioning + on-disk marker idempotency; per-model cache (`ConcurrentHashMap<String,OfflineTts>`); single-flight download set; fair-lock generation.

**State Management:** `CACHE`, `DOWNLOADING` set, fair `LOCK`.

**Side Effects:** Network archive download; disk writes under `data/tts-models/sherpa`; **subprocess spawn (`tar`)**; native `OfflineTts` construction + generate + release; temp files.

**Error Handling:** `TtsException` for unknown model (lists known ids), provisioning failure, missing marker, WAV read/write failure; `NumberFormatException` → speaker 0; lexicon `IOException` → best-effort empty.

**Testing:**
- Test File: none dedicated (highest-risk file — native + subprocess + network).
- Coverage: only enum tagging via `TtsModelTest`.
- Test Approach: gap — `speakerId` parsing table + `specFor` message are cheap unit wins; native path needs an integration profile.

**Comments/TODOs:** None load-bearing.

---

### app/services/tts/TtsException.java

**Purpose:** The single unchecked failure type thrown by every TTS backend so the router's dispatch/catch stays uniform.
**Lines of Code:** 17
**File Type:** Java exception

**What Future Contributors Must Know:** Top-level by design — don't nest it into a backend. Note `VoiceController` reuses it for non-synthesis I/O failures (staging/buffer), so "TtsException" doesn't strictly mean "synthesis failed."

**Exports:** `TtsException(String)`, `TtsException(String, Throwable)`.

**Dependencies:** none.

**Used By:** every file in the layer; `VoiceController`; `ApiTtsController` (→ HTTP 503 `tts_unavailable`).

**Key Implementation Details:** Extends `RuntimeException`.

**Patterns Used:** Single domain exception type.

**State Management:** None.

**Side Effects:** N/A.

**Error Handling:** It is the error type.

**Testing:**
- Test File: none (trivial).
- Coverage: exercised via callers.
- Test Approach: N/A.

**Comments/TODOs:** None.

---

### app/services/tts/TtsText.java

**Purpose:** Deliberately-lossy markdown→speakable reducer (JCLAW-791) so the engine speaks words, not "pipe pipe dash" or emoji names. Applied server-side at every TTS entry point.
**Lines of Code:** 40
**File Type:** Java text utility (pure)

**What Future Contributors Must Know:** Regex order matters (links before emphasis; joiners before `\p{So}`). `\p{So}` also removes some non-emoji symbols (™, ©, arrows) — an accepted tradeoff; the test locks in that currency/math survive. This is not a full markdown renderer by design.

**Exports:** `static String toSpeakable(String md)` - null → `""`.

**Dependencies:** none.

**Used By:** `ApiTtsController` (synthesize + stream), `VoiceController`; `TtsTextTest`.

**Key Implementation Details:** Ordered regex pipeline — fenced code → space; images → space; links → text; table/hr/bullet strip; backticks removed; `|` → `, ` (pause); emphasis/heading/quote markers removed; two-step emoji strip (joiners/VS/keycaps then `\p{So}`); collapse whitespace.

**Patterns Used:** Ordered transformation pipeline.

**State Management:** None (pure).

**Side Effects:** None.

**Error Handling:** Null-guarded; no exceptions.

**Testing:**
- Test File: `test/TtsTextTest.java` (9 tests).
- Coverage: strong — prose/emphasis/links/code/table/emoji(ZWJ)/currency-math-preserved/bullets.
- Test Approach: direct unit.

**Comments/TODOs:** None.

---

### app/services/tts/TtsSentenceChunker.java

**Purpose:** Sentence-level, engine-agnostic chunker for streaming synthesis (JCLAW-790) so playback starts on the first sentence, not after the whole message.
**Lines of Code:** 85
**File Type:** Java text utility (pure)

**What Future Contributors Must Know:** The split lookbehind requires whitespace after terminal punctuation, so `"a.b"` won't split — intentional (URLs/decimals). Tuning `MIN_CHARS`/`MAX_CHARS` trades time-to-first-audio against prosody. `ApiTtsController` streaming and `VoiceController` use different chunking paths — keep behavior aligned if consolidating.

**Exports:** `static final int MIN_CHARS=60`, `MAX_CHARS=320`; `static List<String> chunk(String text)`.

**Dependencies:** `java.util.{ArrayList,List}`.

**Used By:** `ApiTtsController.stream`; `TtsSentenceChunkerTest`.

**Key Implementation Details:** Splits on paragraphs then sentences (`(?<=[.!?…])\s+`); merges forward under `MIN`, hard-wraps over `MAX` (`hardWrap` never splits inside a word); paragraph breaks force a flush.

**Patterns Used:** Greedy packing with min/max bounds.

**State Management:** None (pure).

**Side Effects:** None.

**Error Handling:** Null/blank → empty list.

**Testing:**
- Test File: `test/TtsSentenceChunkerTest.java` (6 tests).
- Coverage: merge/hard-wrap/paragraph-boundary/word-order.
- Test Approach: direct unit.

**Comments/TODOs:** None.

---

### app/services/tts/TtsVoiceCatalog.java

**Purpose:** Curated per-model speaker-voice presets (JCLAW-846) for the Settings voice picker; the `id` is written to `tts.<engine>.voice` and flows through `TtsRouter.voiceFor` to the engine's `voice` field.
**Lines of Code:** 58
**File Type:** Java catalog (pure)

**What Future Contributors Must Know:** A bogus Kokoro voice name errors the synth with **no fallback**, so only validated voices belong here. Piper is single-voice, Chatterbox clones from a reference clip, and the JVM sherpa Kokoro's speaker indices are unmapped — all intentionally absent so the UI hides the picker. The sidecar `kokoro` has a catalog; the JVM `kokoro-multi-lang-v1_0` does not (different ids, different engines).

**Exports:** `record Voice(String id, String label)`; `static List<Voice> voicesFor(String modelId)` (null → empty).

**Dependencies:** `java.util.{List,Map}`.

**Used By:** `ApiTtsController` (both `sidecarEntry`/`jvmEntry`); `TtsVoiceCatalogTest`.

**Key Implementation Details:** `KOKORO_VOICES` = 8 validated MLX Kokoro named voices; `QWEN3_VOICES` = 4 numeric RNG-seed speakers `"1".."4"` (Qwen3-TTS-Base has no named voices — the number seeds synth.py's `_voice_seed`). `BY_MODEL` maps `kokoro`, `qwen3-0.6b`, `qwen3-0.6b-4bit`.

**Patterns Used:** Static curated registry; null-guard for `Map.of` lookup.

**State Management:** None.

**Side Effects:** None.

**Error Handling:** Null modelId → empty list.

**Testing:**
- Test File: `test/TtsVoiceCatalogTest.java`.
- Coverage: Kokoro ids/labels, Qwen3 all-digit shared ids, empty for Piper/Chatterbox/sherpa-Kokoro/unknown/null.
- Test Approach: direct unit.

**Comments/TODOs:** None.

---

### app/controllers/ApiTtsController.java

**Purpose:** Text-to-speech / read-aloud Settings backend + playback (JCLAW-789/790/793): a state snapshot of both engines, one-shot WAV synthesis, streaming SSE read-aloud, and JVM-native model download.
**Lines of Code:** 224
**File Type:** Play REST controller (`@With(AuthCheck.class)`)

**What Future Contributors Must Know:** Sidecar `present` tracks the process being up, not on-disk weights (weights live in the sidecar's HF cache); JVM `present` is real disk state. There is **no write path here** — engine/model/voice selection is `POST /api/config`; don't add a mutation. Keep tests hermetic — never let a test hit a path that actually synthesizes.

**Exports:**
- Records: `TtsModelEntry(id, displayName, approxSizeMb, present, downloading, List<TtsVoiceCatalog.Voice> voices)`, `TtsEngineEntry`, `TtsStateResponse`, `DownloadStartedResponse`.
- `static void state()` / `synthesize()` / `stream()` / `download(String id)`.

**Dependencies:** `services.tts.*`, `services.{ConfigService, EventLogger, UvProbe}`, `utils.{ApiResponses, GsonHolder}`, `play.mvc.SseStream`.

**Used By:** `conf/routes:35-38`.

**Key Implementation Details:**

```
GET  /api/tts/state                 -> both-engine snapshot (availability/status/models/voices)
POST /api/tts/synthesize            -> audio/wav bytes (tts.maxChars=5000; TtsException→503)
POST /api/tts/stream                -> SSE {audio,index}* then {complete,count}
POST /api/tts/models/{id}/download  -> JVM prefetch (virtual thread); sidecar → {"status":"managed"}
```

Streaming uses a `tts-stream` virtual thread + `AtomicBoolean cancelled` wired to `sse.onClose`, 15s heartbeat + 10-min timeout, returning the Play worker via `await(sse.completion())`.

**Patterns Used:** Read snapshot vs config-write separation; virtual-thread streaming; single-flight prefetch.

**State Management:** Stateless controller; reads engine state from the TTS layer.

**Side Effects:** WAV/SSE render; virtual threads; triggers synthesis + JVM weight downloads; `EventLogger` entries.

**Error Handling:** `AuthCheck` (401); missing/blank/too-long text → 400; `TtsException` → 503 (synthesize) / SSE `error` (stream); unknown id → 400. `@SuppressWarnings({"java:S2259","java:S3655"})` because `ApiResponses.error(400)` halts across the framework boundary.

**Testing:**
- Test File: `test/ApiTtsControllerTest.java` (FunctionalTest).
- Coverage: auth gates, state snapshot, missing/blank text 400, unknown-model 400, sidecar-download "managed" — all hermetic (return before spawning).
- Test Approach: assert every path returns before synthesis.

**Comments/TODOs:** None.

---

### app/services/sidecar/SidecarHttpClient.java

**Purpose:** Shared abstract skeleton for the local inference-sidecar HTTP clients (ASR / diarize / TTS), extracted from triplicated boilerplate (JCLAW-828). Serializes each sidecar's one-inference-at-a-time work through a subclass-owned fair lock and supplies the timeout-lifted OkHttp client.
**Lines of Code:** 74
**File Type:** Java abstract base

**What Future Contributors Must Know:** Don't remove `readTimeout=0` without adding a per-call `callTimeout` (JCLAW-626) — otherwise cold model loads that take minutes fail spuriously, or a hung socket hangs forever. Each subclass must declare its OWN static fair `ReentrantLock`; sharing a lock across sidecars over-serializes unrelated work.

**Exports:**
- `protected static final MediaType JSON`; `protected static OkHttpClient defaultClient()` (`readTimeout=0`).
- `protected abstract ReentrantLock sidecarLock()`; `protected final <T> T withSidecarLock(Supplier<T>)`.
- `protected static String truncate(String)` - collapse an error body to ≤300 chars.

**Dependencies:** OkHttp, `utils.HttpFactories`.

**Used By:** `services.transcription.AsrSidecarClient`, `services.transcription.DiarizeSidecarClient`, `services.tts.TtsSidecarClient`.

**Key Implementation Details:** Each concrete sidecar is one-request-at-a-time (HTTP 409 when busy); the base serializes JVM-wide through a **fair** `ReentrantLock` so concurrent conversations queue rather than surface a retryable busy failure.

**Patterns Used:** Template method; fair-lock serialization.

**State Management:** Subclass-owned lock.

**Side Effects:** None directly (constructs clients, holds locks).

**Error Handling:** `truncate` normalizes error bodies (null-safe, whitespace-collapsed, 300-char cap).

**Testing:**
- Test File: none direct.
- Coverage: via `AsrSidecarClientTest`, `DiarizeSidecarClientTest`.
- Test Approach: exercised through subclasses.

**Comments/TODOs:** None.

---

### app/services/LocalSidecarDaemon.java

**Purpose:** Shared lifecycle mechanism for jclaw's local Python sidecars — spawns `uv run serve.py`, drains its streams on virtual threads, polls `/health`, and stops with a `destroy()`→`destroyForcibly()` discipline. One instance per managed daemon, parameterized by a `Config` record (JCLAW-830).
**Lines of Code:** 420
**File Type:** Java lifecycle manager

> **Path note:** this file lives at `app/services/LocalSidecarDaemon.java` (package `services`), NOT under `app/services/sidecar/`. Only `SidecarHttpClient` is in `services/sidecar/`.

**What Future Contributors Must Know:** Don't make `stop()` take `startLock` — it would reintroduce a stall behind a multi-minute cold-start health poll. The `stopGeneration` hand-off keeps a stop/spawn race orphan-free — preserve both the snapshot-at-launch (`genAtLaunch`) and snapshot-at-await (`genAtStart`) checks. Never let a cancellation flow through the `RuntimeException` branch of `spawn` — it must stay `StartCancelledException` so `spawnFailedUntil` isn't set for a healthy sidecar. `isHealthy(expectedModel)` must keep evicting a model-mismatched adopted orphan (JCLAW-637).

**Exports:**
- `record Config(sidecarSubdir, cacheSubdir, configPrefix, defaultPort, defaultStartupTimeoutS, logChannel, threadPrefix, displayName, startupHint, BiFunction<String,Throwable,RuntimeException> fail)`.
- `<T> T singleFlight(Supplier<T>)`, `Object lock()` (diarize facade only).
- `int port()`, `String baseUrl()`, `boolean hasProcess()`.
- `void spawn(String model)` / `spawn(String model, String hfToken)`, `void awaitHealthy()`.
- `boolean isHealthy()` / `isHealthy(String expectedModel)`, `static String healthModel(String)`.
- `void stop()`.

**Dependencies:** Gson, OkHttp, `play.{Logger, Play}`, `utils.HttpFactories`, `services.{ConfigService, EventLogger}`.

**Used By:** static `DAEMON` in `imagegen.LocalImageSidecarManager`, `videogen.LocalVideoSidecarManager`, `transcription.{AsrSidecarManager, DiarizeSidecarManager}`, `tts.TtsSidecarManager`; also `DefaultConfigJob`, capability probes.

**Key Implementation Details:** `spawnNow` builds `uv run serve.py --host 127.0.0.1 --port <p> --model <m> --cache-dir <abs> --idle-timeout-min <n>`, sets `SIDECAR_REQUEST_TIMEOUT_SEC = max(60, jvmTimeout-60)` (JCLAW-641) + optional child-scoped `HF_TOKEN`. `awaitHealthy` polls `GET /health` (5s per-call) every 1s until healthy or the startup deadline, aborting if the process died or `stopGeneration` changed. `stop()` takes only the short `procLock`, then `destroy()` outside the lock (→ `destroyForcibly()` after 2s). JCLAW-830 concurrency: `singleFlight` (`startLock`) ensures one spawner on the fixed port; `procLock` is a short publication guard; `stopGeneration` closes the stop-before-publish race.

**Patterns Used:** Config-parameterized shared mechanism; single-flight spawn; generation-counter race handling; stream draining on virtual threads.

**State Management:** `volatile process`, drain handles, `startLock`/`procLock`, `stopGeneration`, `spawnFailedUntil` cooldown.

**Side Effects:** Spawns/kills OS subprocesses; virtual threads; `/health` + `/shutdown` HTTP; `EventLogger`; the sidecar downloads/loads models.

**Error Handling:** Missing script / launch `IOException` / health timeout / process-exit → the domain exception via `cfg.fail()`. `StartCancelledException` surfaced without poisoning the cooldown. `isHealthy` swallows `IOException` → false. `healthModel` never throws.

**Testing:**
- Test File: `test/LocalSidecarDaemonHealthTest.java` (UnitTest).
- Coverage: `healthModel` parsing + JCLAW-830 concurrency (exactly-one-spawn, non-overlap, stop-doesn't-stall, idle-stop-noop).
- Test Approach: side-effect-free `Config` (`sidecar/none`, port 9999) — no real process/Play.

**Comments/TODOs:** Diarization still serializes on `lock()` rather than `singleFlight()` — the odd one out; keep both facades working.

---

### sidecar/tts/serve.py

**Purpose:** A stdlib-only localhost HTTP daemon (the "supervisor") launched on demand by the JVM. Takes TEXT and returns AUDIO BYTES, shelling each request to a persistent PEP-723 worker (`synth.py --worker`). A mirror of the ASR sidecar with the data direction inverted.
**Lines of Code:** 252
**File Type:** Python (stdlib-only) HTTP daemon

**What Future Contributors Must Know:** The worker's fd-1 hijack (in `synth.py`) keeps the JSON protocol clean — never `print()` to stdout in the worker. `_prewarm` must never raise (broad except by design). The idle watcher uses `os._exit` (no cleanup) — deliberate. The localhost-only bind is a security boundary; don't widen `--host`.

**Exports / public surface:**
- `GET /health` → `{status, model, loaded}`; `POST /synthesize {text, model?, voice?, ref_audio?, ref_text?, speed?, format?}` → audio bytes | `400/409/500` JSON; `POST /shutdown` → `os._exit(0)`.
- `class SidecarState` (`run_lock`, `io_lock`, cached worker handle; `_spawn`, `tts_worker`, `ask`, `touch`).
- `main()` - argparse `--host/--port/--model/--cache-dir/--idle-timeout-min`; sets `HF_HOME`; starts idle-watcher + prewarm daemon threads.

**Dependencies:** pure stdlib (`argparse, base64, json, os, sys, threading, time, http.server`); `subprocess` lazily.

**Used By:** `app/services/tts/TtsSidecarManager.java` → `app/services/LocalSidecarDaemon.java` (spawn command); `TtsSidecarClient` (POST); `ShutdownJob`.

**Key Implementation Details:** Supervisor→persistent-worker over stdin/stdout JSON lines (serialized by `io_lock`), one worker cached to amortize python-start + engine-import + model-load. `_prewarm` runs `uv sync --script synth.py` off the request path. `_idle_watcher` self-evicts after the idle timeout but skips while `run_lock` is held. `409` when `run_lock.acquire(blocking=False)` fails.

**Patterns Used:** Supervisor + persistent worker; single-flight synth (`run_lock`); idle self-eviction.

**State Management:** `SidecarState` (worker handle + two locks + `last_activity`).

**Side Effects:** Spawns subprocesses, binds a TCP port, writes weights under `HF_HOME`, `os._exit` on shutdown/idle.

**Error Handling:** `400`/`409`/`500`(`str(e)`)/`404`; worker-death detection in `ask`; `BrokenPipeError` swallowed; timeouts bounded by `REQUEST_TIMEOUT_SEC` (env, default 1740).

**Testing:**
- Test File: none Python-side.
- Coverage: via Java `TtsSidecarClient`/controller.
- Test Approach: end-to-end from Java.

**Comments/TODOs:** None.

---

### sidecar/tts/synth.py

**Purpose:** The TTS synthesis worker: text in, audio bytes out, running in its OWN uv/PEP-723 script env. The request `model` id is the engine router.
**Lines of Code:** 254
**File Type:** Python (PEP-723) worker

**What Future Contributors Must Know:** The perth-watermarker monkeypatch is fragile against `chatterbox-tts` upstream changes — if construction starts crashing again after a bump, re-check the watermarker import. `chatterbox-tts` (unconditional dep) drags torch into the same env as mlx-audio — watch for resolver conflicts. Never `print()` before `_init_protocol_stdout` (the fd hijack guards library prints, not your own early writes).

**Exports / public surface:**
- Registry `MLX_REPOS` (`qwen3-0.6b`, `qwen3-0.6b-4bit`, `qwen3-1.7b`, `kokoro`) + `DEFAULT_MODEL="qwen3-0.6b"`; Chatterbox is a separate torch branch keyed by `"chatterbox"`.
- `worker()` line-protocol: `{"op":"synthesize", …}` → `{"audio_b64","sample_rate","format"}` | `{"error"}`; first emits `{"ready": True}`.
- `is_apple_silicon()`, `_load_mlx`, `_voice_seed`, `_pick_device()` (`cuda>mps>cpu`), `_load_chatterbox()`, `_synthesize_chatterbox`, `_synthesize_mlx`, `_synthesize` (the router), `main()`.

**Dependencies:** PEP-723 header — `requires-python=">=3.10,<3.13"`; `numpy`, `soundfile`, `mlx-audio`+`misaki[en]` (Apple-silicon-gated), **`chatterbox-tts`** (unconditional → torch). Lazy imports: `huggingface_hub`, `mlx_audio`, `mlx.core`, `torch`, `perth`, `chatterbox.tts`, `soundfile`.

**Used By:** spawned exclusively by `serve.py._spawn` (`uv run synth.py --worker`); env pre-synced by `serve.py._prewarm`.

**Key Implementation Details:** `_init_protocol_stdout` dups real stdout to `_REAL_STDOUT_FD`, then `os.dup2(2,1)` so model-load chatter can't corrupt JSON frames. Kokoro gets named `voice`; Qwen3-TTS-Base seeds `mx.random.seed(_voice_seed(voice))` to pin the speaker across chunks. Chatterbox: if `perth.PerthImplicitWatermarker` is `None`, monkeypatch a `_NoopWatermarker` before importing `ChatterboxTTS` (JCLAW-814). The mlx path is Apple-silicon-gated; `chatterbox` is EXEMPT (cross-platform torch, MPS/CUDA). Output → `soundfile.write` to `BytesIO` (WAV/FLAC) → base64.

**Patterns Used:** Registry-routed engine dispatch; protocol-stdout hijack; lazy heavy imports; monkeypatch shim.

**State Management:** `_MODEL_CACHE` (per-model), `_REAL_STDOUT_FD`.

**Side Effects:** Model download + in-process load, torch device acquisition, fd-1 redirect.

**Error Handling:** Per-request `try/except` → `{"error": str(e)}`; empty audio → raise; platform gate raises an actionable message.

**Testing:**
- Test File: none.
- Coverage: end-to-end from Java; one-shot `main` for manual debug.
- Test Approach: `uv run synth.py "hello" chatterbox > out.wav`.

**Comments/TODOs:** NVIDIA/vLLM Qwen backend deferred to JCLAW-788.

---

### sidecar/tts/pyproject.toml

**Purpose:** Project metadata for the daemon env — `name=jclaw-tts-sidecar`, `requires-python=">=3.11"`, **`dependencies=[]`** (stdlib-only by design; all ML deps live in `synth.py`'s inline PEP-723 header).
**Lines of Code:** 11
**File Type:** TOML config

**What Future Contributors Must Know:** Version-floor mismatch worth tracking — pyproject says `>=3.11`; synth.py's PEP-723 header says `>=3.10,<3.13` (the worker env has the tighter, upper-bounded constraint).

**Exports:** N/A (metadata).

**Dependencies:** none (`uv run serve.py` reads it).

**Used By:** the daemon env.

**Key Implementation Details:** Comment documents the ASR-mirror / inverted-direction rationale.

**Patterns Used:** Stdlib-only supervisor vs PEP-723 worker split.

**State Management:** N/A.

**Side Effects:** None.

**Error Handling:** N/A.

**Testing:** N/A.

**Comments/TODOs:** Reconcile the `requires-python` floors on the next touch.

---

### frontend/composables/useVoiceMode.ts

**Purpose:** The browser-side real-time voice client (SPA singleton — one session app-wide). Opens a WebSocket to `/api/voice`, streams the mic continuously as 16 kHz PCM16, plays streamed TTS through an AudioWorklet ring buffer, with server-driven state + barge-in.
**Lines of Code:** 330
**File Type:** Vue/Nuxt composable (TypeScript)

**What Future Contributors Must Know:** Same-origin WS works in prod (one JVM); in `--dev` the Nitro proxy may not forward the WS upgrade to :9000 and the Origin check may reject a mismatched proxy Origin — **prod is the reference path**. Turn gating by `currentTurn` is the correctness backbone for barge-in — preserve it when adding message types. The singleton state means a second `start()` while active is a guarded no-op.

**Exports:**
- `type VoiceState = 'idle'|'connecting'|'listening'|'capturing'|'thinking'|'speaking'|'error'`.
- `useVoiceMode()` → `{ state, transcript, reply, errorMsg (all readonly), start(agentId), stop() }`.

**Dependencies:** Nuxt auto-imports; `WebSocket` (same-origin `/api/voice`), `getUserMedia`, `AudioContext`, `AudioWorkletNode`; loads `/worklets/voice-capture.js` + `/worklets/voice-playback.js`. Constants: `TARGET_RATE=16000`, `STREAM_FRAME=1024`, `THINKING_STALL_MS=60000`, `SPEAKING_STALL_MS=15000`.

**Used By:** `frontend/components/chat/ChatVoiceOverlay.vue` (only consumer). Server counterpart: `VoiceController.socket`.

**Key Implementation Details:** `binaryType='arraybuffer'`; `onopen` sends `{type:'init',agentId}`. `startMic` uses `AudioContext({sampleRate:16000})` + the capture worklet (`frame:1024`); `sendFrame` converts `Float32Array`→LE `Int16Array` and streams continuously (server VAD decides speech). Playback: `decodeAudioData` → transfer mono channel to the playback worklet ring. `onMessage` routes string frames; `audio` accepted only if `msg.turn === currentTurn` (straggler drop). Stall watchdog re-arms on progress; on stall sends `{type:'cancel'}` and recovers to `listening`. `teardown` closes both AudioContexts, stops tracks, sends `{type:'bye'}`.

**Patterns Used:** SPA singleton; continuous-stream + server-side VAD; turn-id gating; transferable buffers; stall watchdog.

**State Management:** Module-level singleton refs + mutable audio-graph handles.

**Side Effects:** Microphone acquisition (permission prompt), two `AudioContext`s, a live WebSocket, timers.

**Error Handling:** `fail(msg)` sets error + tears down; `start` reports "could not start voice mode (mic permission?)"; `playChunk` catches decode failures (non-fatal); non-string / bad-JSON frames ignored.

**Testing:**
- Test File: none (`useVoiceMode`/worklets/`/api/voice` have no `frontend/test` coverage).
- Coverage: gap.
- Test Approach: N/A.

**Comments/TODOs:** 16 kHz capture is assumed by server VAD/STT; the rare "platform ignored the rate" branch only warns — resampling is a known TODO.

---

### frontend/components/chat/ChatVoiceOverlay.vue

**Purpose:** The modal overlay over the chat page that drives the real-time voice cascade via `useVoiceMode`. Distinct from the composer's "Record voice" clip button; shares the current conversation so turns land in chat history.
**Lines of Code:** 136
**File Type:** Vue SFC

**What Future Contributors Must Know:** Keep it dumb — the overlay owns nothing beyond forwarding lifecycle to the singleton composable. Mount is guarded by `selectedAgentId != null`.

**Exports:** Props `{ agentId: number }`; emits `(e:'close')`.

**Dependencies:** `@heroicons/vue` `XMarkIcon`; `useVoiceMode()`; auto-imported `IconVoiceWaveform`.

**Used By:** `frontend/pages/chat.vue` (`v-if="voiceModeActive && selectedAgentId != null"`).

**Key Implementation Details:** `role="dialog" aria-modal` panel with a circular status indicator + `aria-live="polite"` label; computed `statusLabel`/`indicatorClass`/`animating`. `onMounted` → `start(agentId)` + Escape listener; `onBeforeUnmount` → `stop()`.

**Patterns Used:** Thin view over a composable; a11y live-region status.

**State Management:** None of its own (delegates to the composable).

**Side Effects:** Through `useVoiceMode`: mic + WS on mount, teardown on unmount.

**Error Handling:** Renders `errorMsg` when `state==='error'`.

**Testing:**
- Test File: none dedicated.
- Coverage: gap.
- Test Approach: N/A.

**Comments/TODOs:** a11y lint disabled intentionally for the static backdrop.

---

### frontend/components/settings/SettingsSpeechPanel.vue

**Purpose:** The Settings › Speech panel: pick the TTS engine (Sidecar / JVM-native), the per-engine model, and the per-model speaker voice (JCLAW-846). Changes take effect on the next read-aloud.
**Lines of Code:** 282
**File Type:** Vue SFC

**What Future Contributors Must Know:** The `tts.<engine>.voice` reset on model change is load-bearing (a Kokoro voice name is meaningless for Qwen3). Polling must be idempotent (`ttsPollTimer != null` guard) and always torn down. Sidecar weights auto-pull (needs `uv`); JVM voices are disk-provisioned via the Download button.

**Exports:** SFC (no props/emits); local types `TtsVoiceEntry`/`TtsModelEntry`/`TtsEngineEntry`/`TtsState`.

**Dependencies:** `useSettingsConfig()` (`configValue/saveField/saving`), `useLazyFetch<TtsState>('/api/tts/state')`, `$fetch('/api/tts/models/{id}/download')`. Config keys: `tts.engine`, `tts.sidecar.model`, `tts.jvm.model`, `tts.<engine>.voice`.

**Used By:** `frontend/components/settings/sections.ts` (registers the `speech` section, `SpeakerWaveIcon`) → `pages/settings.vue`.

**Key Implementation Details:** `setEngine`/`setModel`/`setVoice` persist then `refreshTtsState()`; `setModel` resets the voice to `''`. Download polling (1500 ms) runs while `anyDownloadInFlight()`; a `watch(ttsState)` resumes polling if a download was already in flight on open; `onUnmounted` clears the timer. `useLazyFetch` → panel paints immediately.

**Patterns Used:** Read snapshot + config-write; lazy fetch (avoids sidecar-cold-boot stall); idempotent polling.

**State Management:** Local reactive selectors over the config store + lazy state.

**Side Effects:** `/api/tts/state`, `/api/config`, `/api/tts/models/{id}/download`; an interval timer.

**Error Handling:** `downloadModel` `try/finally` clears `saving`; absent state renders "status unavailable."

**Testing:**
- Test File: `frontend/test/settings.speech.test.ts`.
- Coverage: engine radios + sidecar default, POST `tts.engine=jvm`, Download vs Ready control, Voice dropdown POST + hide-for-single-voice (JCLAW-846).
- Test Approach: `mountSuspended` + `registerEndpoint('/api/tts/state')`.

**Comments/TODOs:** None.

---

### frontend/public/worklets/voice-capture.js

**Purpose:** Mic-capture `AudioWorkletProcessor` (`voice-capture-processor`) on the audio render thread; accumulates fixed-size mono PCM frames and posts transferable copies to the main thread, replacing the deprecated `ScriptProcessorNode`.
**Lines of Code:** 35
**File Type:** AudioWorklet (JavaScript)

**What Future Contributors Must Know:** Buffering is a simple fixed-size accumulator with no partial-frame flush — trailing `< frame` samples aren't emitted (acceptable for continuous streaming). Transferables neuter `out` after post — never reuse it.

**Exports / contract:**
- Constructor reads `options.processorOptions.frame` (default 4096; composable passes 1024).
- `process(inputs)` accumulates `inputs[0][0]` (128-sample quanta); on full frame posts `buf.slice(0)` transferable; returns `true`.
- Messages out: raw `Float32Array` frames (no envelope). Messages in: none.

**Dependencies:** worklet globals only.

**Used By:** `useVoiceMode.startMic` (`addModule('/worklets/voice-capture.js')`).

**Key Implementation Details:** Node connected to `destination` only to keep the graph pulling; mic audio is never played back.

**Patterns Used:** Fixed-size frame accumulator; transferable postMessage.

**State Management:** `buf`, `n`.

**Side Effects:** None beyond `postMessage`.

**Error Handling:** Defensive optional chaining (`inputs[0]?.[0]`).

**Testing:**
- Test File: none.
- Coverage: gap.
- Test Approach: N/A.

**Comments/TODOs:** Default frame documented to match the pre-worklet buffer timing.

---

### frontend/public/worklets/voice-playback.js

**Purpose:** TTS-playback `AudioWorkletProcessor` (`voice-playback-processor`): a single-reader/single-writer ring buffer fed by the main thread, enabling gapless playback and instant barge-in (one `flush` stops within a render quantum ~2.7 ms) instead of scheduling/discarding N `AudioBufferSourceNode`s.
**Lines of Code:** 76
**File Type:** AudioWorklet (JavaScript)

**What Future Contributors Must Know:** The `drained` edge is single-fire (guarded by `hadData`); pairing it with the server's `turn_complete` in `maybeEndTurn` is what returns the floor. `flush` is the barge-in fast path — keep it O(1) (index reset only). Capacity scales with the playback context's `sampleRate`, not 16 kHz.

**Exports / contract:**
- Messages in: `{type:'push', data:Float32Array}` → `enqueue`; `{type:'flush'}` → hard-stop reset.
- Messages out: `{type:'drained'}` once on the non-empty→empty edge.
- Constructor: `capacity = max(1, floor(sampleRate*10))` (~10 s headroom).

**Dependencies:** worklet globals (`sampleRate`).

**Used By:** `useVoiceMode.startPlayback` (`numberOfInputs:0, outputChannelCount:[1]`).

**Key Implementation Details:** `enqueue` writes into the ring (drops oldest on overflow); `process` emits one sample per output frame (0 when empty), copying mono to all channels; `drained` announced exactly once per empty edge.

**Patterns Used:** SPSC ring buffer; single-fire drained edge; O(1) flush.

**State Management:** `ring`, `read/write/filled`, `hadData`.

**Side Effects:** Audio output + `postMessage`.

**Error Handling:** Ignores unknown messages; underflow plays silence; overflow drops oldest.

**Testing:**
- Test File: none.
- Coverage: gap.
- Test Approach: N/A.

**Comments/TODOs:** None.

---

## Contributor Checklist

- **Risks & Gotchas:**
  - **Single-threaded voice pipeline.** `VoiceSession`/`PcmWindower`/`VoiceVad`/`TurnEndpointer` are not thread-safe and must stay on the WS inbound thread. The only cross-thread datum is `latestPartial` (`AtomicReference`), safe because `TextTurnConfirmer` is stateless.
  - **Native VAD leak.** Honor the `handedOff` flag in `VoiceController`; the Silero `Vad` must be closed exactly once.
  - **Barge-in double-turn.** Two turn threads can be briefly live; all frames go through `send(out, writeLock, …)`. Turn-id gating (server) + `currentTurn` gating (client) suppress stragglers.
  - **`readTimeout=0` everywhere sidecar-adjacent.** Bounded only by per-call timeouts (JCLAW-626) — never remove one without the other.
  - **`stop()` must not take `startLock`** in `LocalSidecarDaemon`, or it stalls behind a cold-start poll; the `stopGeneration` generation counter is the race guard.
  - **Two-edit model additions.** JVM model = `TtsModel` row + `TtsJvmEngine.SPECS`; sidecar model = `TtsModel` row + `synth.py` `MLX_REPOS`; both optionally a `TtsVoiceCatalog` entry.
  - **Only validated voices in `TtsVoiceCatalog`** — a bogus name errors synth with no fallback.
  - **Segment-name constants** in `VoiceTurnMetrics` are analytics contracts — renaming breaks the dashboard.
  - **Dev-mode WS caveat** — voice mode's reference path is prod (single JVM); `--dev` proxy may not forward the upgrade.
  - **Chatterbox perth monkeypatch** is fragile to `chatterbox-tts` bumps; torch is dragged into the shared MLX env.
- **Pre-change Verification Steps:**
  1. `play autotest` (backend) — targets `TurnEndpointerTest`, `PcmWindowerTest`, `TextTurnConfirmerTest`, `VoiceTurnMetricsTest`, `VoiceControllerTest`, `Tts*Test`, `ApiTtsControllerTest`, `LocalSidecarDaemonHealthTest`.
  2. `cd frontend && pnpm test` — `settings.speech.test.ts` after any Speech-panel change.
  3. Manual voice UAT in prod mode (`./jclaw.sh restart`) with a background watcher on `logs/application.log` for `voice: turn` lines — the WS path has no automated coverage.
  4. For sidecar changes: `uv run synth.py "hello" chatterbox > out.wav` and confirm a valid WAV.
- **Suggested Tests Before PR:**
  - A `TtsRouterTest` stubbing `ConfigService` to assert the cross-engine `modelFor` guard + the exhaustive switch.
  - A `TtsSidecarClientTest` via MockWebServer through the `(baseUrlOverride, client)` seam — success bytes, HTTP-500 body truncation, concurrent-call serialization.
  - A `speakerId` parsing table + `specFor` unknown-id message test for `TtsJvmEngine`.
  - A minimal `useVoiceMode` unit (turn-id gating + `flush` handling) with a mocked WebSocket.

## Architecture & Design Patterns

### Code Organization

Six layers, each with a single responsibility, wired so the controllers stay thin: (1) the **voice pipeline** (`services.voice`) is transport-agnostic and callback-driven; (2) the **TTS engine layer** (`services.tts`) is a config-routed dispatcher over two interchangeable engines; (3) the **controllers** (`VoiceController`, `ApiTtsController`) adapt Play's WS/REST/SSE to those services; (4) the **shared sidecar infra** (`LocalSidecarDaemon`, `SidecarHttpClient`) is a parameterized mechanism reused by five domains; (5) the **Python sidecar** (`serve.py` supervisor + `synth.py` worker) isolates heavy ML deps in a per-script uv env; (6) the **frontend** (composable + component + two worklets) owns the browser audio graph.

### Design Patterns

- **Pipeline / Chain of Responsibility:** `PcmWindower → VoiceVad → TurnEndpointer → VoiceSession` assembler.
- **Strategy hook:** `TurnEndpointer.Confirmer` (`ALWAYS_COMPLETE` vs `TextTurnConfirmer`).
- **Exhaustive-switch dispatch:** `TtsRouter.synthesize` — the compiler enforces engine coverage.
- **Template method:** `SidecarHttpClient` (shared client + per-subclass fair lock).
- **Config-parameterized shared mechanism:** one `LocalSidecarDaemon` class, five `Config` instances.
- **Supervisor + persistent worker:** `serve.py` (stdlib) shells to a cached `synth.py` worker over JSON lines.
- **SPSC ring buffer + single-flight:** the playback worklet; the `run_lock`/`SIDECAR_LOCK` one-at-a-time serialization.
- **SPA singleton:** `useVoiceMode` module-level state (one voice session app-wide).

### State Management Strategy

Backend voice state is per-connection and single-threaded (no shared mutable state except the atomic `latestPartial` and the turn-id counters). TTS selection is entirely externalized to config (`tts.engine` / `tts.<engine>.model` / `tts.<engine>.voice`), read fresh per call — no in-memory selection to invalidate. Sidecar lifecycle state is centralized in `LocalSidecarDaemon` behind `startLock`/`procLock`/`stopGeneration`. Frontend state is a module singleton (`useVoiceMode`) plus the two worklets' internal buffers.

### Error Handling Philosophy

Fail soft on the user-facing path, fail fast on provisioning. The WS loop swallows per-frame `RuntimeException`s so one bad frame never drops the socket; `runTurn` emits an `error` frame only if the turn wasn't cancelled. TTS collapses every backend failure into one `TtsException` (→ HTTP 503 / SSE `error`). Sidecar provisioning throws the domain exception immediately (missing `uv`, missing script, health timeout) with an actionable message, and a 60s cooldown prevents every caller from paying the full startup timeout after a failure.

### Testing Strategy

The pure/deterministic units are directly and thoroughly tested (`TurnEndpointerTest`, `PcmWindowerTest`, `TextTurnConfirmerTest`, `VoiceTurnMetricsTest` via clock injection, `TtsModelTest`, `TtsTextTest`, `TtsSentenceChunkerTest`, `TtsVoiceCatalogTest`, `LocalSidecarDaemonHealthTest` via a side-effect-free `Config`). The stateful/native/subprocess/WS surfaces (`VoiceSession`, `VoiceVad`, `TtsJvmEngine`, `TtsSidecarClient`, the WS wiring, `useVoiceMode`, the worklets) are covered manually — the deliberate seam is to extract the testable pure predicate (e.g. `VoiceController.originMatchesHost`) and leave the I/O boundary to manual UAT.

## Data Flow

```
REAL-TIME VOICE (WS /api/voice)
  Browser mic
    └─ voice-capture-processor (worklet, frame=1024 @16kHz) ── Float32 frame ──► main thread
         └─ useVoiceMode.sendFrame  ── LE PCM16 binary ──► WS
              └─ VoiceController.socket → VoiceSession.onPcm
                   └─ PcmWindower (512-sample float windows)
                        └─ VoiceVad.isSpeech (Silero ONNX)  +  tsMs = frameIndex*32
                             └─ TurnEndpointer.accept  ──(Confirmer: TextTurnConfirmer)──►
                                  ├─ SPEECH_STARTED → onSpeechStart (barge-in: cancel + flush)
                                  ├─ NONE (buffering) → interim WAV → AsrSidecarClient (partial transcript → latestPartial)
                                  └─ ENDPOINT → onUtterance(WAV)
                                       └─ runTurn (voice-turn-N virtual thread)
                                            ├─ native-audio model: stage WAV as input_audio attachment
                                            └─ text-only model:    AsrSidecarClient.transcribe → transcript
                                            └─ AgentRunner.runStreaming (cancel flag = barge-in)
                                                 └─ sentence buffer → TtsText.toSpeakable → TtsRouter.synthesize
                                                      └─ base64 WAV ── {type:audio,turn,index} ──► WS
   ◄───────────────────────────────────────────────────────────────────────────────────────────┘
  Browser: useVoiceMode.onMessage (turn-gated) → decodeAudioData → voice-playback-processor ring → speaker
                                                                     └─ drained + turn_complete → maybeEndTurn → listening
  Latency: VoiceTurnMetrics records voice_stt / voice_tts_synth / voice_reply / voice_turn → LatencyStats (channel "voice")

READ-ALOUD TTS (REST/SSE)
  chat speaker icon / Settings → POST /api/tts/synthesize|stream
    └─ TtsText.toSpeakable → (stream: TtsSentenceChunker.chunk) → TtsRouter.synthesize
         ├─ SIDECAR → TtsSidecarClient → serve.py /synthesize → synth.py worker (MLX / Chatterbox) → WAV bytes
         └─ JVM     → TtsJvmEngine (sherpa-onnx OfflineTts) → WAV bytes
```

### Data Entry Points

- **`WS /api/voice` binary frames** — continuous LE PCM16 mic stream.
- **`WS /api/voice` control frames** — `init` / `cancel` / `bye`.
- **`POST /api/tts/synthesize` / `/stream`** — read-aloud text.
- **`POST /api/config`** — engine/model/voice selection (consumed here, written elsewhere).

### Data Transformations

- **Reframe:** arbitrary byte chunks → 512-sample float windows (`PcmWindower`).
- **Detect + endpoint:** windows → speech booleans → `NONE/SPEECH_STARTED/ENDPOINT` (`VoiceVad` + `TurnEndpointer`).
- **Assemble:** windows → 16 kHz mono WAV utterance (`VoiceSession.wrapWav`).
- **Transcribe / stage:** WAV → transcript (`AsrSidecarClient`) or native `input_audio` attachment.
- **Speakable reduction:** markdown → spoken text (`TtsText`), sentence chunks (`TtsSentenceChunker`).
- **Synthesize:** text → WAV bytes (sidecar or JVM engine).

### Data Exit Points

- **`WS /api/voice` server frames** — `ready`/`state`/`transcript`/`reply`/`audio`/`turn_complete`/`flush`/`error`.
- **`POST /api/tts/synthesize`** — `audio/wav` bytes.
- **`POST /api/tts/stream`** — SSE `{audio,index}*` + `{complete}`.
- **`GET /api/tts/state`** — engine/model/voice snapshot JSON.
- **`LatencyStats`** — persisted latency rows under channel `voice`.

## Integration Points

### APIs Consumed

- **`AgentRunner.runStreaming(...)`**: the agent turn — streams reply tokens, honors the barge-in cancel flag.
  - Method: in-process call. Authentication: session (already authed at WS open). Response: streaming callbacks.
- **`AsrSidecarClient.transcribe(wav)`**: local Whisper STT for text-only models.
  - Method: HTTP to the ASR sidecar (via `LocalSidecarDaemon`). Response: `WhisperTranscriber.Segment`s.
- **TTS Python sidecar `POST /synthesize`**: `{text, model?, voice?, format?}` → `audio/wav` bytes.
  - Method: HTTP (localhost 9531). Authentication: localhost-only bind. Response: WAV/FLAC bytes | `400/409/500`.
- **sherpa-onnx / Silero release archives**: model weight downloads (k2-fsa GitHub releases).

### APIs Exposed

- **`WS /api/voice`** — real-time voice mode. Request: `init`/binary PCM16/`cancel`/`bye`. Response: the JSON+audio frame protocol above.
- **`GET /api/tts/state`** — both-engine snapshot. Response: `TtsStateResponse`.
- **`POST /api/tts/synthesize`** — `{text}` → `audio/wav`.
- **`POST /api/tts/stream`** — `{text}` → SSE audio chunks.
- **`POST /api/tts/models/{id}/download`** — JVM model prefetch; sidecar → `{"status":"managed"}`.

### Shared State

- **`latestPartial`** (`AtomicReference<String>`): the interim transcript. Type: per-connection atomic. Accessed By: the interim-transcript virtual thread (writer) + `TextTurnConfirmer` on the inbound thread (reader).
- **`current` cancel flag** (`AtomicReference<AtomicBoolean>`): barge-in signal. Accessed By: the inbound thread + each `voice-turn-N` thread + `AgentRunner`.
- **`LocalSidecarDaemon.DAEMON`** (static per domain): sidecar process handle + generation counter. Accessed By: manager facades, capability probes, `ShutdownJob`.
- **TTS config keys** (`tts.engine`/`tts.<engine>.model`/`tts.<engine>.voice`): the selection. Accessed By: `TtsRouter` (read-per-call), `SettingsSpeechPanel` (write via `/api/config`).

### Events

- **Barge-in** (`flush` frame): Type: server→client publish. Payload: `{type:"flush"}` — client drops queued playback.
- **`drained`**: Type: worklet→main publish. Payload: `{type:"drained"}` — one per empty edge; paired with `turn_complete` to return the floor.
- **Idle self-eviction**: Type: sidecar-internal. The Python daemon `os._exit`s after its idle timeout; the manager transparently respawns on the next `ensureRunning`.

### Database Access

- No direct DB access in this scope. `VoiceController.runTurn` resolves the shared "web" `Conversation` and stages attachments through `services.Tx.run(...)` + `ConversationService`/`AttachmentService`, but the voice/TTS layers themselves are stateless w.r.t. JPA. `VoiceTurnMetrics` writes go to `LatencyStats` (in-memory histograms + the `LatencyMetricRecorder` persisted-row queue), not directly to a table.

## Dependency Graph

```
                         conf/routes
                        /            \
             WS /api/voice        /api/tts/*
                  |                    |
          VoiceController         ApiTtsController
           /    |     \    \           |    \
  VoiceSession  |  VoiceTurnMetrics    |   TtsSentenceChunker
    /  |  \     |        |             |
Pcm  Vad  TurnEndpointer |          TtsRouter ──────────────┐
Windower    |            |          /   |    \               |
            TextTurnConfirmer   TtsEngine TtsModel  TtsVoiceCatalog
                                     \    /
                          ┌───────────┴───────────┐
                     TtsSidecarClient         TtsJvmEngine
                          |                        |
                   TtsSidecarManager          (sherpa-onnx native)
                          |
                   LocalSidecarDaemon ◄── AsrSidecarClient/Manager, Diarize, Image, Video
                          |
                   SidecarHttpClient (base of Tts/Asr/Diarize clients)
                          |
                   sidecar/tts/serve.py ── spawns ──► sidecar/tts/synth.py

  Frontend:  chat.vue → ChatVoiceOverlay.vue → useVoiceMode.ts → {voice-capture.js, voice-playback.js} → WS /api/voice
             settings.vue → sections.ts → SettingsSpeechPanel.vue → /api/tts/*, /api/config
```

### Entry Points (Not Imported by Others in Scope)

- `app/controllers/VoiceController.java` (route target)
- `app/controllers/ApiTtsController.java` (route target)
- `frontend/components/chat/ChatVoiceOverlay.vue` (mounted by `chat.vue`)
- `frontend/components/settings/SettingsSpeechPanel.vue` (registered by `sections.ts`)
- `sidecar/tts/serve.py` (spawned by `LocalSidecarDaemon`)

### Leaf Nodes (Don't Import Others in Scope)

- `TtsException.java`, `TtsEngine.java`, `TtsText.java`, `TtsSentenceChunker.java`, `TtsVoiceCatalog.java`
- `PcmWindower.java`, `TurnEndpointer.java` (pure); `VoiceVad.java` (external libs only)
- `sidecar/tts/pyproject.toml`, `frontend/public/worklets/voice-capture.js`, `frontend/public/worklets/voice-playback.js`

### Circular Dependencies

✓ No circular dependencies detected. The graph is strictly layered: controllers → services → shared infra → sidecar process; the Python worker never calls back into Java.

## Testing Analysis

### Test Coverage Summary

Coverage is bimodal — high on pure logic, absent on I/O/native boundaries (no aggregate percentage is meaningful across Java + Python + browser worklets in one scope). By file class:

- **Pure/deterministic units:** well covered (8 dedicated test files).
- **Native/subprocess/WS/browser:** manual only.

### Test Files

- **`test/TurnEndpointerTest.java`** — 8 tests; direct state-machine unit; no mocking (pure).
- **`test/PcmWindowerTest.java`** — 3 tests; boundary-focused; pure.
- **`test/TextTurnConfirmerTest.java`** — 6 tests; heuristic + supplier; pure.
- **`test/VoiceTurnMetricsTest.java`** — 3 tests; `FakeClock` injection; resets `LatencyStats`.
- **`test/VoiceControllerTest.java`** — `originMatchesHost` only (WS can't upgrade under FunctionalTest).
- **`test/TtsModelTest.java`** — engine/model enum tagging + defaults + Chatterbox invariant.
- **`test/TtsTextTest.java`** — 9 tests; markdown/emoji reduction.
- **`test/TtsSentenceChunkerTest.java`** — 6 tests; chunk merge/wrap/paragraph.
- **`test/TtsVoiceCatalogTest.java`** — voice presets per model.
- **`test/ApiTtsControllerTest.java`** — FunctionalTest; hermetic auth/state/validation paths.
- **`test/LocalSidecarDaemonHealthTest.java`** — health parse + JCLAW-830 concurrency via a no-op `Config`.
- **`frontend/test/settings.speech.test.ts`** — engine/model/voice UI via `mountSuspended` + `registerEndpoint`.

### Test Utilities Available

- `FakeClock implements LongSupplier` (in `VoiceTurnMetricsTest`) — deterministic timing.
- Side-effect-free `LocalSidecarDaemon.Config` (`sidecar/none`, port 9999) — spawn logic without a real process.
- `TtsSidecarClient(baseUrlOverride, client)` — an unused test seam for MockWebServer injection.
- `@nuxt/test-utils` `mountSuspended` + `registerEndpoint` — the frontend panel harness.

### Testing Gaps

- `TtsRouter` dispatch + cross-engine `modelFor` guard (untested).
- `TtsSidecarClient` HTTP success/409/500 + lock serialization (seam exists, unused).
- `TtsJvmEngine` — the largest, highest-risk file (native + subprocess + download); only enum tagging is touched.
- `VoiceSession` / `VoiceVad` — orchestrator + native VAD (manual only).
- `useVoiceMode`, `ChatVoiceOverlay`, both worklets — zero frontend coverage.
- Python `serve.py` / `synth.py` — no Python-side tests (end-to-end only).

## Related Code & Reuse Opportunities

### Similar Features Elsewhere

- **ASR / transcription** (`app/services/transcription/`) — the mirror of this subsystem (audio→text). `AsrSidecarClient` shares `SidecarHttpClient`; `AsrSidecarManager` shares `LocalSidecarDaemon`; `sidecar/asr/serve.py` is the direction-inverted twin of `sidecar/tts/serve.py`.
  - Similarity: identical sidecar lifecycle + HTTP-client base. Reference for: adding a new sidecar-backed capability.
- **Image / video generation** (`app/services/imagegen/`, `videogen/`) — also use `LocalSidecarDaemon` (with `hasProcess()`-driven restart on model switch).
  - Reference for: model-switch restart semantics.
- **Diarization** (`sidecar/diarize/`) — the one facade that serializes on `DAEMON.lock()` rather than `singleFlight()`.
  - Reference for: the `_pick_device()` (`cuda>mps>cpu`) pattern that `synth.py` mirrors.

### Reusable Utilities Available

- **`LocalSidecarDaemon`** (`app/services/`) — spawn/health/stop any `uv run serve.py` sidecar. Use: define a `Config` and delegate `ensureRunning`/`stop`.
- **`SidecarHttpClient`** (`app/services/sidecar/`) — request-serialized OkHttp base. Use: extend, supply a static fair lock.
- **`HttpFactories`** (`app/utils/`) — the shared OkHttp 5 stack (`general()`, `llmStreaming()`, `llmSingleShot()`).
- **`LatencyStats` / `LatencyTrace`** (`app/utils/`) — record any channel/segment for the Chat Performance dashboard.
- **`TtsText.toSpeakable`** — reuse anywhere text must be spoken (already used by both TTS entry points).

### Patterns to Follow

- **Adding a sidecar-backed capability:** Reference `TtsSidecarManager` + `serve.py`/`synth.py` (supervisor+worker, PEP-723, fd-1 hijack).
- **Adding a config-routed engine choice:** Reference `TtsRouter` + `TtsEngine` (exhaustive switch, config-read-per-call, safe defaults).
- **Extracting a testable predicate from an I/O boundary:** Reference `VoiceController.originMatchesHost`.
- **A browser real-time audio graph:** Reference `useVoiceMode` + the two worklets (transferable frames, ring-buffer playback, turn-id gating).

## Implementation Notes

### Code Quality Observations

- The pure/impure split is disciplined and deliberate — the hard logic (endpointing, chunking, markup reduction, metrics) is extracted into pure, unit-tested classes, and the I/O boundaries are thin.
- Concurrency invariants are documented in-code (single-threaded pipeline, fair locks, the `handedOff`/`stopGeneration` guards) rather than left implicit.
- The two TTS engines are kept symmetrical (same exception, same fair-lock discipline, same lazy provisioning) so the controllers treat them uniformly.

### TODOs and Future Work

- **`frontend/composables/useVoiceMode.ts`**: resample when the platform ignores the requested 16 kHz capture rate (currently warns only).
- **`app/services/tts/TtsSidecarClient.java`**: wire the `ref_audio`/`ref_text`/`speed` cloning fields the sidecar already accepts (JCLAW-847).
- **`sidecar/tts/synth.py`**: NVIDIA/vLLM Qwen backend deferred to JCLAW-788.
- **`sidecar/tts/pyproject.toml`**: reconcile `requires-python >=3.11` with synth.py's `>=3.10,<3.13`.

### Known Issues

- `chatterbox-tts`'s hardcoded `perth.PerthImplicitWatermarker` is `None` in this env; a monkeypatched no-op watermarker works around it but is fragile to upstream bumps.
- `chatterbox-tts` (unconditional) pulls torch into the same uv script env as mlx-audio — a heavy dependency co-tenancy to watch.
- Voice mode's `--dev` WS path is unreliable (Nitro proxy upgrade + Origin) — prod is the reference path.

### Optimization Opportunities

- `prewarmAsr` already hides STT cold-start behind the user's first sentence; a parallel TTS prewarm could shave first-audio latency on cold sidecars.
- The sidecar and voice-mode chunking paths differ (`TtsSentenceChunker` vs the inline `drainSentences`) — consolidating would reduce prosody drift between read-aloud and voice mode.

### Technical Debt

- `TtsJvmEngine` (285 LOC, native + subprocess + network) is the single largest untested surface — highest change-risk in the scope.
- Duplicated `9531` port default (`TtsSidecarManager` + `DefaultConfigJob`) — a manual-sync hazard.
- No automated coverage for the entire browser audio path.

## Modification Guidance

### To Add New Functionality

- **A new TTS voice/model:** add a `TtsModel` enum row (correct engine block, mind default ordering) + the engine-side entry (`TtsJvmEngine.SPECS` for JVM, `synth.py` `MLX_REPOS` for sidecar) + optionally a `TtsVoiceCatalog` entry (only validated voices). Add a `TtsModelTest` assertion.
- **A new endpointing heuristic:** implement `TurnEndpointer.Confirmer`, wire it in `VoiceController.initSession` behind a `voice.endpoint.*` config flag, keep `looksComplete` O(1), add a direct unit test.
- **A new voice-mode control frame:** extend the `onMessage`/`socket()` switch on both sides; preserve turn-id gating.

### To Modify Existing Functionality

- **Endpointing timing:** tune `voice.endpoint.{speechStartMs,baseSilenceMs,maxSilenceMs,minUtteranceMs}` (config, no code); for behavior, edit `TurnEndpointer` and update `TurnEndpointerTest`.
- **Speakable text rules:** edit `TtsText` (mind regex order) + `TtsTextTest`.
- **Sidecar startup/health:** edit `LocalSidecarDaemon`; never make `stop()` take `startLock`; keep both the diarize `lock()` and the `singleFlight()` facades working; run `LocalSidecarDaemonHealthTest`.

### To Remove/Deprecate

- **Retiring an engine:** remove the `TtsEngine` value — the compiler flags the now-missing/covered `switch` arm in `TtsRouter`; drop its `TtsModel` rows, manager, and (for sidecar) the `serve.py`/`synth.py` branch; update `DefaultConfigJob` seeds and `SettingsSpeechPanel` radios.
- **Retiring voice mode:** remove the `WS /api/voice` route + `VoiceController`, the `services.voice` package, and the frontend composable/overlay/worklets; `TtsRouter`/`ApiTtsController` remain (read-aloud is independent).

### Testing Checklist for Changes

- [ ] `play autotest` green (voice + tts + sidecar suites).
- [ ] `cd frontend && pnpm test` green (`settings.speech.test.ts` if the panel changed).
- [ ] Manual voice UAT in prod mode with a `logs/application.log` `voice: turn` watcher.
- [ ] `uv run synth.py "…" <model>` produces a valid WAV for any sidecar model touched.
- [ ] New pure logic has a direct unit test; new I/O boundaries extract a testable predicate.
- [ ] No new native VAD / sidecar leak (respect `handedOff` / `stopGeneration`).
- [ ] Config-key defaults kept in sync across `DefaultConfigJob` and the managers.

---

_Generated by `document-project` workflow (deep-dive mode)_
_Base Documentation: docs/architecture/index.md_
_Scan Date: 2026-07-24_
_Analysis Mode: Exhaustive_
