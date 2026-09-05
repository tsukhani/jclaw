package controllers;

import agents.AgentRunner;
import agents.ModelResolver;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import llm.LlmTypes.ModelInfo;
import llm.ProviderRegistry;
import models.Agent;
import models.ChannelType;
import models.Conversation;
import models.MessageAttachment;
import play.Logger;
import play.db.jpa.NoTransaction;
import play.mvc.Http;
import play.mvc.WebSocketController;
import services.AgentService;
import services.AttachmentService;
import services.ConfigService;
import services.ConversationDeletionCascade;
import services.ConversationService;
import services.Tx;
import services.transcription.AsrSidecarClient;
import services.transcription.WhisperTranscriber;
import services.tts.TtsEngine;
import services.tts.TtsException;
import services.tts.TtsRouter;
import services.tts.TtsSidecarManager;
import services.voice.TextTurnConfirmer;
import services.voice.TurnEndpointer;
import services.voice.VoiceSession;
import services.voice.VoiceTurnMetrics;
import services.voice.VoiceTurnSpeaker;
import services.voice.VoiceVad;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Real-time voice mode WebSocket (JCLAW-791; server-side endpointing JCLAW-799).
 * The browser streams the mic as continuous PCM16 {@code BinaryFrame}s; the
 * server runs the Silero VAD + adaptive-silence endpointer ({@link VoiceSession},
 * JCLAW-797) to detect where each utterance begins and ends. How the finalized
 * utterance becomes a user turn depends on the agent's active model: an
 * audio-capable model receives the raw speech as a native {@code input_audio}
 * attachment (the JCLAW-165 chat pipeline, so tone and non-lexical cues survive),
 * while a text-only model gets a local-ASR transcript. Either way the reply
 * streams back as sentence-chunked TTS audio frames (reusing JCLAW-790), which
 * the client plays gaplessly.
 *
 * <p><b>Barge-in:</b> each turn runs on a virtual thread so the inbound loop
 * keeps running the VAD; when the endpointer detects the user speaking over an
 * in-flight turn, the server trips that turn's cancel flag and sends a {@code
 * flush} so the client drops its queued playback immediately. Generation is
 * canceled too — {@code AgentRunner.runStreaming} takes the same flag — so a
 * barged turn stops producing tokens, not just audio. Every frame carries a
 * monotonic {@code turn} id so the client discards straggler audio from a
 * superseded turn.
 *
 * <p>The cascade keeps the Java agent (tools, memory, orchestration) in the
 * loop. When the model can't hear audio, voice STT deliberately uses the LOCAL
 * ASR for latency — cloud transcription would blow the voice-to-voice budget —
 * so a text-only model needs a local Whisper model provisioned in
 * Settings&nbsp;&gt;&nbsp;Transcription.
 *
 * <p>Protocol — client→server:
 * <ul>
 *   <li>{@code TextFrame  {"type":"init","agentId":N}} — bind the agent + start the pipeline (once)</li>
 *   <li>{@code BinaryFrame <pcm16 bytes>} — a chunk of the continuous 16&nbsp;kHz mono mic stream</li>
 *   <li>{@code TextFrame  {"type":"cancel"}} — client-initiated stop of the in-flight turn</li>
 *   <li>{@code TextFrame  {"type":"bye"}} — graceful close</li>
 * </ul>
 * server→client (JSON frames):
 * {@code {"type":"ready"}}, {@code {"type":"state","value":"capturing|thinking"}},
 * {@code {"type":"flush"}} (drop queued playback on barge-in),
 * {@code {"type":"transcript","turn":t,"text":..}}, {@code {"type":"reply","turn":t,"text":..}},
 * {@code {"type":"audio","turn":t,"index":i,"audio":<base64 wav>}},
 * {@code {"type":"turn_complete","turn":t}}, {@code {"type":"error","message":..}}.
 */
public class VoiceController extends WebSocketController {

    /** Overlay "You:" placeholder for the native-audio path — the raw speech went
     *  straight to the model, so there is no Whisper transcript to echo. */
    private static final String VOICE_ATTACHMENT_LABEL = "(voice message)";

    /** Grace given to an in-flight turn to observe its cancel flag before the
     *  session conversation is deleted (JCLAW-864). A canceled turn stops at its
     *  next cooperative checkpoint, well inside this. */
    private static final long CANCEL_SETTLE_MS = 250;

    /** Pre-roll windows kept before speech-start so the onset isn't clipped
     *  (~320&nbsp;ms at the 32&nbsp;ms Silero window). */
    private static final int PREROLL_WINDOWS = 10;

    /** Channel identity for a voice session — both the per-turn tag handed to the
     *  streaming runner AND, since JCLAW-862, the conversation's own channel.
     *
     *  <p>Tagging the turn {@code "voice"} makes {@link agents.SystemPromptAssembler}
     *  inject spoken-conversation guidance instead of the web-UI markdown guidance;
     *  otherwise the model reads the transcript as a text chat and denies it can
     *  hear the user. The conversation used to stay {@code "web"}, shared with the
     *  typed chat — it is now created fresh per session on this channel, so voice
     *  history resets each time the operator opens voice mode. */
    private static final String VOICE_CHANNEL = ChannelType.VOICE.value;

    /** Server→client frame tokens, de-duplicated per S1192 — the {@code type}
     *  discriminators and the field keys reused across frames. */
    private static final String TYPE_ERROR = "error";
    private static final String TYPE_TRANSCRIPT = "transcript";
    private static final String KEY_MESSAGE = "message";
    private static final String KEY_AGENT_ID = "agentId";

    /**
     * JCLAW-862 follow-up: {@code @NoTransaction} is load-bearing here, for two
     * reasons discovered when the session conversation went invisible.
     *
     * <p><b>Correctness.</b> Play otherwise wraps this method in a per-request JPA
     * transaction that lives for the whole WebSocket session. The conversation
     * created at init then joined that transaction and stayed uncommitted until
     * the socket closed, so the per-turn threads — which get transactions of their
     * own — could not see it, aborted with "session conversation is gone", and
     * left the client waiting in Thinking forever. Opting out means the
     * {@code Tx.run} at init owns and commits its own transaction, which is what
     * every other DB touch on this path already assumed.
     *
     * <p><b>Resource.</b> The same wrapper pinned a HikariCP connection for the
     * entire conversation — minutes of idle mic time holding a pooled connection.
     * That is exactly what JCLAW-199 opted the SSE endpoints out of; the voice
     * socket is longer-lived than any of them and was never opted out.
     *
     * <p>Safe because every DB access reachable from here already goes through
     * {@code Tx.run}: {@link #resolveAgent}, the per-turn plan,
     * {@link #modelHearsAudioAtInit}, and {@link #discardSessionConversation}.
     */
    @NoTransaction
    public static void socket() {
        // CSWSH defense — a WebSocket handshake is NOT bound by the Same-Origin
        // Policy, so the browser attaches the session cookie even on a cross-site
        // WS. Reject any handshake whose (browser-set, JS-unforgeable) Origin
        // isn't our own host BEFORE touching the session, else a malicious page
        // could drive the agent as the logged-in victim.
        if (!sameOrigin()) {
            disconnect();
            return;
        }
        if (!authenticated()) {
            outbound.sendJson(Map.of("type", TYPE_ERROR, KEY_MESSAGE, "authentication required"));
            disconnect();
            return;
        }
        var username = session.get("username");
        var asr = new AsrSidecarClient();
        var out = outbound;
        var writeLock = new Object();               // serialize frame writes across turn threads
        var current = new AtomicReference<AtomicBoolean>();  // in-flight turn's cancel flag
        var turnSeq = new AtomicInteger();
        var sessionRef = new AtomicReference<VoiceSession>(); // per-connection streaming pipeline
        // JCLAW-864: the session's conversation, so the finally below can discard
        // it however the socket ends — bye, close, or an exception unwinding the
        // inbound loop.
        var bindingRef = new AtomicReference<VoiceBinding>();

        try {
            for (Http.WebSocketEvent event : inbound) {
                try {
                    switch (event) {
                        case Http.TextFrame(var text) -> {
                            var msg = JsonParser.parseString(text).getAsJsonObject();
                            var type = msg.has("type") ? msg.get("type").getAsString() : "";
                            switch (type) {
                                case "init" -> initSession(msg, username, asr, out, writeLock,
                                        current, turnSeq, sessionRef, bindingRef);
                                case "cancel" -> cancelCurrent(current);  // client-initiated stop
                                case "bye" -> {
                                    cancelCurrent(current);
                                    return;
                                }
                                default -> { /* ignore unknown control frames */ }
                            }
                        }
                        case Http.BinaryFrame(var bytes) -> {
                            // Continuous PCM16 mic frames; the session runs VAD +
                            // endpointing inline and calls back on turn boundaries.
                            var s = sessionRef.get();
                            if (s == null) {
                                send(out, writeLock, Map.of("type", TYPE_ERROR, KEY_MESSAGE, "send an init frame first"));
                            } else {
                                s.onPcm(bytes, bytes.length);
                            }
                        }
                        case Http.WebSocketClose _ -> {
                            cancelCurrent(current);
                            return;
                        }
                    }
                } catch (RuntimeException e) {  // a bad frame must not drop the socket
                    Logger.warn("voice: frame handling failed: %s", e.getMessage());
                }
            }
        } finally {
            cancelCurrent(current);
            var s = sessionRef.getAndSet(null);
            if (s != null) s.close();  // release the native VAD
            discardSessionConversation(bindingRef.getAndSet(null));
        }
    }

    /**
     * Delete the conversation backing a finished voice session (JCLAW-864).
     *
     * <p>Voice interactions are one-off, so the row is discarded with the dialog
     * rather than left to accumulate — there is no conversation retention job to
     * prune it later.
     *
     * <p>Cancellation is cooperative and checked at specific points, so a turn may
     * still be persisting when the socket closes. The caller trips the cancel flag
     * first; this waits briefly for the turn thread to notice before deleting, which
     * keeps the log clean of errors about a conversation that is being thrown away
     * anyway. The wait is short and bounded — a canceled turn stops at its next
     * checkpoint — and expiring it is not a failure, just a noisier delete.
     *
     * <p>Best-effort by design: a failure here must not propagate out of the socket's
     * finally and mask whatever actually ended the session. The startup sweep in
     * {@link jobs.BootConsistencyCheck} is the backstop for anything left behind,
     * including a hard kill that skips this path entirely.
     */
    private static void discardSessionConversation(VoiceBinding binding) {
        if (binding == null || binding.conversationId() == null) return;
        try {
            Thread.sleep(CANCEL_SETTLE_MS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();  // fall through and still delete
        }
        try {
            int n = Tx.run(() -> ConversationDeletionCascade.deleteByIds(List.of(binding.conversationId())));
            if (n > 0) {
                Logger.info("voice: discarded session conversation %d", binding.conversationId());
            }
        } catch (RuntimeException e) {
            Logger.warn("voice: could not discard session conversation %d: %s",
                    binding.conversationId(), e.getMessage());
        }
    }

    /** Bind the agent and stand up the streaming pipeline (Silero VAD + adaptive
     *  endpointing, JCLAW-797) for this connection. Utterances detected server-side
     *  flow one at a time to {@link #runTurn}; the mic stays open so a new utterance
     *  during an in-flight turn is a server-driven barge-in. */
    @SuppressWarnings("java:S107") // per-connection wiring — all captured by the session listener
    private static void initSession(JsonObject msg, String username, AsrSidecarClient asr,
                                    Http.Outbound out, Object writeLock,
                                    AtomicReference<AtomicBoolean> current, AtomicInteger turnSeq,
                                    AtomicReference<VoiceSession> sessionRef,
                                    AtomicReference<VoiceBinding> bindingRef) {
        var agent = resolveAgent(msg);
        if (agent == null) {
            send(out, writeLock, Map.of("type", TYPE_ERROR, KEY_MESSAGE, "unknown or missing agentId"));
            return;
        }
        VoiceVad vad = null;
        boolean handedOff = false; // set once the VoiceSession takes ownership of the VAD
        try {
            vad = new VoiceVad(); // provisions the Silero model on first use
            // Adaptive semantic endpointing (JCLAW-845): a text-heuristic confirmer
            // reads the latest interim transcript (JCLAW-798) and holds the turn open
            // toward maxSilenceMs when the utterance ends mid-clause, so a natural
            // mid-sentence pause isn't cut off. Falls back to fixed baseSilenceMs when
            // there's no transcript (audio-native models) or when disabled via config.
            var latestPartial = new AtomicReference<String>();
            boolean semanticHold = !"false".equalsIgnoreCase(
                    String.valueOf(ConfigService.get("voice.endpoint.semanticHold")));
            TurnEndpointer.Confirmer confirmer = semanticHold
                    ? new TextTurnConfirmer(latestPartial::get)
                    : TurnEndpointer.ALWAYS_COMPLETE;
            var endpointer = new TurnEndpointer(
                    ConfigService.getInt("voice.endpoint.speechStartMs", 180),
                    ConfigService.getInt("voice.endpoint.baseSilenceMs", 500),
                    ConfigService.getInt("voice.endpoint.maxSilenceMs", 1500),
                    ConfigService.getInt("voice.endpoint.minUtteranceMs", 200),
                    confirmer);
            var boundAgent = agent;
            // Interim transcripts (JCLAW-798): show partial text as the user
            // speaks. Skipped for audio-capable models (they receive the raw audio,
            // not a transcript) and disableable via config. Single-flight + the
            // session's throttle keep interims from starving the final transcribe.
            // The active model's audio capability decides the input leg (native
            // audio vs local ASR) and whether interim transcripts run — resolve it
            // once. When the model is text-only, warm the local ASR now so the
            // first utterance doesn't eat the worker+model cold start (JCLAW-800).
            // JCLAW-862: one conversation per voice session, created here rather
            // than resolved per turn. create() not findOrCreate() — the latter
            // would hand back the previous session's row and defeat the reset.
            // JCLAW-863: warm the TTS model now, while the operator is still
            // speaking their first sentence. This is the only moment in the voice
            // flow where nothing is waiting on audio — by the time the first turn
            // needs synthesis, a cold load (up to ~52s for Chatterbox) is either
            // done or well underway instead of starting from scratch.
            if (TtsRouter.currentEngine() == TtsEngine.SIDECAR) {
                TtsSidecarManager.prewarmModelAsync();
            }
            var binding = newSessionBinding(boundAgent, username);
            // A re-init on the same socket replaces the binding; discard the
            // superseded conversation rather than orphaning it.
            discardSessionConversation(bindingRef.getAndSet(binding));
            boolean modelHearsAudio = modelHearsAudioAtInit(binding);
            if (!modelHearsAudio) prewarmAsr(asr);
            boolean partialsOn = !"false".equalsIgnoreCase(String.valueOf(ConfigService.get("voice.partials.enabled")))
                    && !modelHearsAudio;
            var interimBusy = new AtomicBoolean(false);
            VoiceSession.Partial partialSink = !partialsOn ? null : wav -> {
                if (!interimBusy.compareAndSet(false, true)) return; // one interim at a time
                Thread.ofVirtual().name("voice-partial").start(() -> {
                    try {
                        var text = transcribe(asr, wav);
                        if (!text.isBlank()) {
                            latestPartial.set(text); // feed the semantic-hold confirmer (JCLAW-845)
                            send(out, writeLock, Map.of("type", TYPE_TRANSCRIPT, "text", text, "partial", true));
                        }
                    } catch (RuntimeException e) {
                        Logger.debug("voice: interim transcript failed: %s", e.getMessage());
                    } finally {
                        interimBusy.set(false);
                    }
                });
            };
            var voice = new VoiceSession(vad, endpointer, PREROLL_WINDOWS, new VoiceSession.Listener() {
                @Override
                public void onSpeechStart() {
                    // Fresh utterance: drop any stale partial so the confirmer decides
                    // this turn's endpoint on this turn's transcript, not the last one's.
                    latestPartial.set(null);
                    // Server-side barge-in: abandon any in-flight turn and flush the
                    // client's playback the instant the user starts talking.
                    if (current.get() != null) {
                        cancelCurrent(current);
                        send(out, writeLock, Map.of("type", "flush"));
                    }
                    send(out, writeLock, Map.of("type", "state", "value", "capturing"));
                }

                @Override
                public void onUtterance(byte[] wav) {
                    cancelCurrent(current);
                    var cancel = new AtomicBoolean(false);
                    current.set(cancel);
                    int turnId = turnSeq.incrementAndGet();
                    send(out, writeLock, Map.of("type", "state", "value", "thinking"));
                    Thread.ofVirtual().name("voice-turn-" + turnId).start(() ->
                            runTurn(binding, asr, wav, cancel, turnId, out, writeLock));
                }
            }, partialSink, ConfigService.getInt("voice.partials.intervalMs", 1200));
            handedOff = true; // the session now owns the VAD; socket()'s finally closes it
            sessionRef.set(voice);
            send(out, writeLock, Map.of("type", "ready", KEY_AGENT_ID, agent.id));
        } catch (RuntimeException e) {
            Logger.warn("voice: failed to start session: %s", e.getMessage());
            send(out, writeLock, Map.of("type", TYPE_ERROR,
                    KEY_MESSAGE, "voice engine unavailable: " + e.getMessage()));
        } finally {
            // The VAD was provisioned but wiring threw before a live session took
            // ownership → close the native Silero model so it can't leak. socket()'s
            // finally only closes a session it can reach via sessionRef.
            if (vad != null && !handedOff) vad.close();
        }
    }

    /** Trip the in-flight turn's cancel flag (if any) and clear the slot. */
    private static void cancelCurrent(AtomicReference<AtomicBoolean> current) {
        var prev = current.getAndSet(null);
        if (prev != null) prev.set(true);
    }

    /** Serialize outbound writes so two overlapping turn threads (a canceled one
     *  winding down + its successor) never interleave a partial frame. */
    private static void send(Http.Outbound out, Object lock, Map<String, Object> frame) {
        synchronized (lock) {
            if (out.isOpen()) out.sendJson(frame);
        }
    }

    /** One voice turn: STT → agent → sentence-chunked streaming TTS. Bails at each
     *  step if superseded (barge-in), and never emits {@code turn_complete} for a
     *  canceled turn. */
    private static void runTurn(VoiceBinding binding, AsrSidecarClient asr, byte[] wav,
                                AtomicBoolean cancel, int turnId, Http.Outbound out, Object lock) {
        var agent = binding.agent();
        var username = binding.username();
        var sink = sinkFor(out, lock);
        try {
            long t0 = System.nanoTime(); // ≈ endpoint: the utterance is now in hand
            // Per-stage voice latency (JCLAW-800): STT, first-chunk TTS synth, and the
            // two voice-to-voice numbers, recorded under channel "voice" alongside the
            // LLM segments the streaming trace already emits on that channel.
            var metrics = new VoiceTurnMetrics(agent.id == null ? null : agent.id.toString(), t0);
            int maxRunOn = ConfigService.getInt("voice.tts.maxRunOnChars", 220);
            // One transaction resolves the session conversation (JCLAW-862, created at init)
            // AND whether the active model hears audio natively — both walk agent/conversation.
            // Audio capability is resolved per turn so a mid-session model switch takes effect
            // on the next utterance.
            var plan = Tx.run(() -> {
                var conv = ConversationService.findById(binding.conversationId());
                if (conv == null) return null;  // deleted mid-session
                return new TurnPlan(conv.id, modelHearsAudio(agent, conv));
            });
            if (plan == null) {
                Logger.warn("voice: turn %d aborted — session conversation %d is gone",
                        turnId, binding.conversationId());
                return;
            }
            if (cancel.get()) return;

            var input = resolveInput(agent, asr, wav, plan.nativeAudio(), cancel, turnId, sink);
            if (input.isEmpty()) return; // canceled, or nothing was said

            long inputMs = (System.nanoTime() - t0) / 1_000_000L; // STT / attachment staging
            metrics.sttDone();

            // Agent turn through the STREAMING runner with the barge-in cancel flag (an
            // interruption stops GENERATION, not just audio); the reply is sentence-chunked
            // AS IT STREAMS — the callback buffers tokens and queues each complete sentence
            // while the consumer below synthesizes them — so audio starts on the first
            // sentence instead of after the whole reply.
            var sentences = new LinkedBlockingQueue<String>();
            AgentRunner.runStreaming(agent, plan.conversationId(), VOICE_CHANNEL, username,
                    input.get().userMessage(), cancel, sentenceChunking(sentences, maxRunOn),
                    System.nanoTime(), input.get().attachments());

            // Consumer: speak each sentence as it arrives. The loop and its
            // degradation rules live in VoiceTurnSpeaker (JCLAW-869), where they are
            // reachable by a test — they encode four separate bug fixes.
            var speaker = new VoiceTurnSpeaker(sink, turnId, cancel,
                    TtsRouter::synthesizeForVoice);
            boolean ended = speaker.speakAll(sentences, synthMs -> {
                // First audio out — the voice-to-voice metric that matters most.
                metrics.ttsSynth(synthMs);
                metrics.firstAudioSent();
                Logger.info("voice: turn %d — stt %dms, first audio %dms after endpoint",
                        turnId, inputMs, (System.nanoTime() - t0) / 1_000_000L);
            });
            if (ended && !cancel.get()) {
                sink.send(Map.of("type", "turn_complete", "turn", turnId));
                metrics.turnComplete();
                Logger.info("voice: turn %d complete — %dms end-to-end, %d chunk(s)",
                        turnId, (System.nanoTime() - t0) / 1_000_000L, speaker.chunksSent());
            }
        } catch (RuntimeException e) {
            Logger.warn("voice: turn %d failed: %s", turnId, e.getMessage());
            if (!cancel.get()) {
                sink.send(Map.of("type", TYPE_ERROR, "turn", turnId, KEY_MESSAGE,
                        String.valueOf(e.getMessage())));
            }
        }
    }

    /** What the agent is asked with, once the input leg has run. */
    private record TurnInput(String userMessage, List<AttachmentService.Input> attachments) {}

    /**
     * Run the input leg and emit the transcript frame.
     *
     * <p>An audio-capable model receives the raw speech as a native audio attachment
     * (the chat pipeline transcodes it to {@code input_audio}, falling back to a
     * Whisper transcript only if the provider rejects the format), so tone and
     * non-lexical cues reach the model. A text-only model gets a local-ASR
     * transcript. Either way the reply is text and is spoken via TTS.
     *
     * <p>Empty when the turn is already over: canceled mid-transcription, or nothing
     * intelligible was said — in which case {@code turn_complete} has been sent, since
     * the floor still has to go back to the mic.
     */
    private static Optional<TurnInput> resolveInput(Agent agent, AsrSidecarClient asr, byte[] wav,
                                                    boolean nativeAudio, AtomicBoolean cancel,
                                                    int turnId, VoiceTurnSpeaker.Sink sink) {
        if (nativeAudio) {
            // Staged before the frame goes out: a staging failure should abort the
            // turn rather than announce an utterance that never reached the model.
            var staged = List.of(stageVoiceAudio(agent, wav));
            sink.send(Map.of("type", TYPE_TRANSCRIPT, "turn", turnId, "text", VOICE_ATTACHMENT_LABEL));
            return Optional.of(new TurnInput("", staged));
        }
        var transcript = transcribe(asr, wav);
        if (cancel.get()) return Optional.empty();
        sink.send(Map.of("type", TYPE_TRANSCRIPT, "turn", turnId, "text", transcript));
        if (transcript.isBlank()) {
            sink.send(Map.of("type", "turn_complete", "turn", turnId));
            return Optional.empty();
        }
        return Optional.of(new TurnInput(transcript, List.of()));
    }

    /**
     * Streaming callbacks that sentence-chunk the reply as it arrives, so audio can
     * start on the first sentence instead of after the whole reply.
     *
     * <p>Every terminal callback — completion, error, cancellation — queues
     * {@code END_OF_TURN}. A consumer blocked on the queue would otherwise wait out
     * its whole timeout on any path that is not a clean finish.
     */
    private static AgentRunner.StreamingCallbacks sentenceChunking(Queue<String> sentences, int maxRunOn) {
        var pending = new StringBuilder();
        return new AgentRunner.StreamingCallbacks(
                c -> { },
                token -> {
                    synchronized (pending) {
                        pending.append(token);
                        drainSentences(pending, sentences, maxRunOn);
                    }
                },
                r -> { }, s -> { }, tc -> { },
                full -> {
                    synchronized (pending) {
                        var tail = pending.toString().strip();
                        if (!tail.isEmpty()) sentences.offer(tail);
                        pending.setLength(0);
                    }
                    sentences.offer(VoiceTurnSpeaker.END_OF_TURN);
                },
                err -> sentences.offer(VoiceTurnSpeaker.END_OF_TURN),
                () -> sentences.offer(VoiceTurnSpeaker.END_OF_TURN));
    }

    /** Adapt the voice socket to the speaker's sink: same write lock, same
     *  closed-socket check every other frame on this connection goes through. */
    private static VoiceTurnSpeaker.Sink sinkFor(Http.Outbound out, Object lock) {
        return new VoiceTurnSpeaker.Sink() {
            @Override public boolean isOpen() {
                return out.isOpen();
            }

            @Override public void send(Map<String, Object> frame) {
                VoiceController.send(out, lock, frame);
            }
        };
    }

    /** Move complete sentences from the streaming token buffer into the queue,
     *  leaving the incomplete tail. Hard-flushes an over-long run-on (e.g. a
     *  markdown table with no sentence marks) so audio still starts promptly. */
    private static void drainSentences(StringBuilder buf, Queue<String> out, int maxRunOn) {
        while (true) {
            var s = buf.toString();
            int idx = sentenceEnd(s);
            if (idx < 0) {
                if (flushRunOn(buf, out, s, maxRunOn)) continue;   // run-on guard
                return;
            }
            var sentence = s.substring(0, idx).strip();
            if (!sentence.isEmpty()) out.offer(sentence);
            buf.delete(0, idx);
        }
    }

    /** Flush an over-long run-on (no sentence mark) as one piece so audio starts
     *  promptly; returns true if it cut (keep draining), false if nothing to do. */
    private static boolean flushRunOn(StringBuilder buf, Queue<String> out, String s, int maxRunOn) {
        if (s.length() <= maxRunOn) return false;
        int cut = s.lastIndexOf(' ', maxRunOn);
        if (cut <= 0) cut = maxRunOn;
        var piece = s.substring(0, cut).strip();
        if (!piece.isEmpty()) out.offer(piece);
        buf.delete(0, cut);
        return true;
    }

    /** Index just past a sentence-ending mark ({@code . ! ? …}) followed by
     *  whitespace, or -1 if the buffer holds no complete sentence yet. */
    private static int sentenceEnd(String s) {
        for (int i = 0; i < s.length() - 1; i++) {
            char c = s.charAt(i);
            if ((c == '.' || c == '!' || c == '?' || c == '…') && Character.isWhitespace(s.charAt(i + 1))) {
                return i + 1;
            }
        }
        return -1;
    }

    /** CSWSH guard: the handshake's browser-set {@code Origin} must match our own
     *  host. A cross-site page can open a cookie-bearing WS, but the browser sets
     *  {@code Origin} to that page's origin and JS cannot override it — so an
     *  Origin that isn't our host (or is missing, i.e. a non-browser client) is
     *  rejected. Compared against {@code request.host} so it follows the deployed
     *  host without extra config. */
    private static boolean sameOrigin() {
        var header = request.headers.get("origin");
        return originMatchesHost(header == null ? null : header.value(), request.host);
    }

    /** Pure Origin-vs-host comparison, extracted for unit testing. The browser
     *  sets the handshake Origin and JS cannot override it, so an Origin whose
     *  authority doesn't equal our host — or is missing/malformed — is rejected. */
    public static boolean originMatchesHost(String origin, String host) {
        if (origin == null || origin.isBlank() || host == null || host.isBlank()) return false;
        try {
            var authority = URI.create(origin.trim()).getAuthority();
            return authority != null && authority.equalsIgnoreCase(host);
        } catch (RuntimeException _) {  // malformed Origin — treat as hostile
            return false;
        }
    }

    /** Session-cookie auth, mirroring {@link AuthCheck}'s session path (WS
     *  handshakes carry the cookie but no Bearer header): the signed session bit
     *  plus a DB cross-check so a cookie minted before a password reset can't
     *  pass. */
    private static boolean authenticated() {
        // JCLAW-1121: the shared classifier, not the bare flag — a cookie from a superseded
        // credential generation must not open a socket either. JCLAW-1031 replaces this whole
        // hand-rolled helper with an @With(AuthCheck.class) mount.
        if (ApiAuthController.sessionRejection() != null) return false;
        var hash = ConfigService.get(ApiAuthController.PASSWORD_HASH_KEY);
        return hash != null && !hash.isBlank();
    }

    private static Agent resolveAgent(JsonObject msg) {
        if (!msg.has(KEY_AGENT_ID) || msg.get(KEY_AGENT_ID).isJsonNull()) return null;
        var agentId = msg.get(KEY_AGENT_ID).getAsLong();
        return Tx.run(() -> AgentService.findById(agentId));
    }

    /**
     * Per-session binding: the agent, the operator, and the conversation created
     * for <em>this</em> voice session (JCLAW-862).
     *
     * <p>Voice used to resolve {@code findOrCreate(agent, "web", username)} on
     * every turn, so it had no conversation of its own — it joined the text
     * chat's. History therefore survived closing and reopening the voice UI, and
     * the two surfaces shared one context. Creating a conversation once per
     * session resets the history simply by existing: nothing is deleted, and the
     * operator's typed chat is untouched.
     *
     * <p>Also collapses what were three separate {@code runTurn} parameters, so
     * threading the conversation through doesn't push that signature to nine.
     */
    private record VoiceBinding(Agent agent, String username, Long conversationId) {}

    /** Per-turn plan: the session conversation id + whether the active model
     *  accepts audio natively (drives native-audio vs Whisper input). */
    private record TurnPlan(Long conversationId, boolean nativeAudio) {}

    /** Whether the agent's active model accepts audio input natively — the same
     *  {@code supportsAudio} gate the chat path uses (JCLAW-165). Caller must hold
     *  a JPA tx: resolves the provider + model info off the agent/conversation. */
    private static boolean modelHearsAudio(Agent agent, Conversation conv) {
        var provider = ProviderRegistry.get(ModelResolver.effectiveModelProvider(agent, conv));
        if (provider == null) provider = ProviderRegistry.getPrimary();
        if (provider == null) return false;
        return ModelResolver.resolveModelInfo(agent, conv, provider)
                .map(ModelInfo::supportsAudio).orElse(false);
    }

    /**
     * Create the conversation backing one voice session (JCLAW-862).
     *
     * <p>The peer id carries a per-session suffix so each row is individually
     * addressable and {@code findByAgentChannelPeer} is never left choosing
     * between sessions — a plain username would accumulate indistinguishable
     * rows on the same key.
     */
    private static VoiceBinding newSessionBinding(Agent agent, String username) {
        // Random suffix rather than a timestamp: two sessions opened in the same
        // millisecond would otherwise share a peer id and leave
        // findByAgentChannelPeer choosing between them.
        var peer = username + "#" + UUID.randomUUID().toString().substring(0, 8);
        var id = Tx.run(() -> ConversationService.create(agent, VOICE_CHANNEL, peer).id);
        Logger.info("voice: session conversation %d created for agent '%s' (peer %s)",
                id, agent.name, peer);
        return new VoiceBinding(agent, username, id);
    }

    /** Resolve audio-capability once at session start — gates interim transcripts.
     *
     *  <p>Reads the session's own conversation, not the web one. Beyond tidiness:
     *  {@code ModelResolver} honors a per-conversation model override, so probing
     *  the web conversation would apply the text chat's override to voice. A fresh
     *  conversation carries none and falls back to the agent default. */
    private static boolean modelHearsAudioAtInit(VoiceBinding binding) {
        return Tx.run(() -> {
            var conv = ConversationService.findById(binding.conversationId());
            return conv != null && modelHearsAudio(binding.agent(), conv);
        });
    }

    /** Stage the utterance WAV as an audio attachment the streaming runner can
     *  finalize and ship as a native {@code input_audio} part. The staging layout
     *  ({@code attachments/staging/<uuid>.wav}) mirrors {@code ApiChatController}
     *  uploads; MIME and kind are re-sniffed on finalize, so these are only hints. */
    private static AttachmentService.Input stageVoiceAudio(Agent agent, byte[] wav) {
        try {
            var uuid = UUID.randomUUID().toString();
            var stagingDir = AgentService.acquireWorkspacePath(agent.name, "attachments/staging");
            Files.createDirectories(stagingDir);
            Files.write(stagingDir.resolve(uuid + ".wav"), wav);
            return new AttachmentService.Input(uuid, "voice.wav", "audio/wav", wav.length,
                    MessageAttachment.KIND_AUDIO);
        } catch (IOException e) {
            throw new TtsException("failed to stage voice audio: " + e.getMessage(), e);
        }
    }

    /** Best-effort warm of the local ASR worker at session start: the first
     *  transcribe spawns a Python worker and loads the Whisper model (~5s), and
     *  we'd rather pay that while the user is speaking their first sentence than
     *  on turn 1's critical path. Runs off-thread on a short silent clip;
     *  failures are swallowed — a cold first turn is the prior behavior, not a
     *  regression. Only called for the text-only (ASR) path; audio-native models
     *  never touch the sidecar. */
    private static void prewarmAsr(AsrSidecarClient asr) {
        Thread.ofVirtual().name("voice-asr-prewarm").start(() -> {
            try {
                transcribe(asr, VoiceSession.wrapWav(new byte[16000])); // 0.5s of silence @ 16 kHz
            } catch (RuntimeException e) {
                Logger.debug("voice: ASR prewarm failed (first turn will cold-start): %s", e.getMessage());
            }
        });
    }

    /** Buffer the utterance to a temp WAV and transcribe on the local ASR. */
    private static String transcribe(AsrSidecarClient asr, byte[] wav) {
        try {
            var tmp = Files.createTempFile("jclaw-voice-", ".wav");
            try {
                Files.write(tmp, wav);
                var model = ConfigService.get("transcription.localModel");
                var segments = asr.transcribe(tmp, (model == null || model.isBlank()) ? "small" : model, null);
                return segments.stream().map(WhisperTranscriber.Segment::text).collect(Collectors.joining(" ")).strip();
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new TtsException("failed to buffer utterance for transcription: " + e.getMessage(), e);
        }
    }
}
