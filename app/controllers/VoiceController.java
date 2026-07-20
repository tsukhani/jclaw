package controllers;

import agents.AgentRunner;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.Agent;
import play.Logger;
import play.mvc.Http;
import play.mvc.WebSocketController;
import services.AgentService;
import services.ConfigService;
import services.ConversationService;
import services.Tx;
import services.transcription.AsrSidecarClient;
import services.tts.TtsException;
import services.tts.TtsRouter;
import services.tts.TtsSentenceChunker;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Real-time voice mode WebSocket (JCLAW-791). The browser segments a spoken
 * utterance (client-side VAD) and streams it as a WAV {@code BinaryFrame}; the
 * server transcribes it on the local ASR, runs the agent turn, and streams the
 * reply back as sentence-chunked TTS audio frames (reusing JCLAW-790), which the
 * client plays gaplessly.
 *
 * <p><b>Barge-in (Phase B):</b> each utterance runs its turn on a virtual thread
 * so the inbound loop stays responsive; a new utterance (or an explicit {@code
 * cancel} frame) supersedes any in-flight turn by tripping its cancel flag. The
 * cancelled turn stops emitting between chunks. Every frame carries a monotonic
 * {@code turn} id so the client discards straggler audio from a superseded turn.
 * (Agent <i>generation</i> cancellation — stopping {@code AgentRunner.run}
 * itself — is a Phase C refinement; today a barged turn stops streaming audio
 * but its reply still completes server-side.)
 *
 * <p>The cascade keeps the Java agent (tools, memory, orchestration) in the
 * loop. Voice STT deliberately uses the LOCAL ASR for latency — cloud
 * transcription would blow the voice-to-voice budget — so the operator must
 * provision a local Whisper model in Settings&nbsp;&gt;&nbsp;Transcription.
 *
 * <p>Protocol — client→server:
 * <ul>
 *   <li>{@code TextFrame  {"type":"init","agentId":N}} — bind the agent (once)</li>
 *   <li>{@code BinaryFrame <wav bytes>} — one complete utterance (supersedes any in-flight turn)</li>
 *   <li>{@code TextFrame  {"type":"cancel"}} — barge-in: stop the in-flight turn</li>
 *   <li>{@code TextFrame  {"type":"bye"}} — graceful close</li>
 * </ul>
 * server→client (JSON frames, each with a {@code turn} id after {@code ready}):
 * {@code {"type":"ready"}}, {@code {"type":"transcript","turn":t,"text":..}},
 * {@code {"type":"reply","turn":t,"text":..}}, {@code {"type":"audio","turn":t,"index":i,"audio":<base64 wav>}},
 * {@code {"type":"turn_complete","turn":t}}, {@code {"type":"error","message":..}}.
 */
public class VoiceController extends WebSocketController {

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
            outbound.sendJson(Map.of("type", "error", "message", "authentication required"));
            disconnect();
            return;
        }
        var username = session.get("username");
        var asr = new AsrSidecarClient();
        var out = outbound;
        var writeLock = new Object();               // serialize frame writes across turn threads
        var current = new AtomicReference<AtomicBoolean>();  // in-flight turn's cancel flag
        var turnSeq = new AtomicInteger();
        Agent agent = null;

        for (Http.WebSocketEvent event : inbound) {
            try {
                switch (event) {
                    case Http.TextFrame(var text) -> {
                        var msg = JsonParser.parseString(text).getAsJsonObject();
                        var type = msg.has("type") ? msg.get("type").getAsString() : "";
                        switch (type) {
                            case "init" -> {
                                agent = resolveAgent(msg);
                                send(out, writeLock, agent == null
                                        ? Map.of("type", "error", "message", "unknown or missing agentId")
                                        : Map.of("type", "ready", "agentId", agent.id));
                            }
                            case "cancel" -> cancelCurrent(current);  // barge-in
                            case "bye" -> {
                                cancelCurrent(current);
                                return;
                            }
                            default -> { /* ignore unknown control frames */ }
                        }
                    }
                    case Http.BinaryFrame(var bytes) -> {
                        if (agent == null) {
                            send(out, writeLock, Map.of("type", "error", "message", "send an init frame first"));
                        } else {
                            // A new utterance supersedes any in-flight turn (barge-in),
                            // and its turn runs off the loop so cancels stay responsive.
                            cancelCurrent(current);
                            var cancel = new AtomicBoolean(false);
                            current.set(cancel);
                            int turnId = turnSeq.incrementAndGet();
                            var boundAgent = agent;
                            Thread.ofVirtual().name("voice-turn-" + turnId).start(() ->
                                    runTurn(boundAgent, username, asr, bytes, cancel, turnId, out, writeLock));
                        }
                    }
                    case Http.WebSocketClose ignored -> {
                        cancelCurrent(current);
                        return;
                    }
                }
            } catch (RuntimeException e) {  // a bad frame must not drop the socket
                Logger.warn("voice: frame handling failed: %s", e.getMessage());
            }
        }
    }

    /** Trip the in-flight turn's cancel flag (if any) and clear the slot. */
    private static void cancelCurrent(AtomicReference<AtomicBoolean> current) {
        var prev = current.getAndSet(null);
        if (prev != null) prev.set(true);
    }

    /** Serialize outbound writes so two overlapping turn threads (a cancelled one
     *  winding down + its successor) never interleave a partial frame. */
    private static void send(Http.Outbound out, Object lock, Map<String, Object> frame) {
        synchronized (lock) {
            if (out.isOpen()) out.sendJson(frame);
        }
    }

    /** One voice turn: STT → agent → sentence-chunked streaming TTS. Bails at each
     *  step if superseded (barge-in), and never emits {@code turn_complete} for a
     *  cancelled turn. */
    private static void runTurn(Agent agent, String username, AsrSidecarClient asr, byte[] wav,
                                AtomicBoolean cancel, int turnId, Http.Outbound out, Object lock) {
        try {
            var transcript = transcribe(asr, wav);
            if (cancel.get()) return;
            send(out, lock, Map.of("type", "transcript", "turn", turnId, "text", transcript));
            if (transcript.isBlank()) {
                send(out, lock, Map.of("type", "turn_complete", "turn", turnId));
                return;
            }

            // Agent turn on the shared web conversation, so voice + text share
            // context. Run through the STREAMING runner with the barge-in cancel
            // flag so an interruption stops GENERATION, not just the audio. The
            // runner is async (its own VT); bridge back with a latch — exactly one
            // of onComplete/onError/onCancel fires.
            var conversation = Tx.run(() -> ConversationService.findOrCreate(agent, "web", username));
            var replyRef = new AtomicReference<String>("");
            var done = new CountDownLatch(1);
            var cb = new AgentRunner.StreamingCallbacks(
                    c -> { }, t -> { }, r -> { }, s -> { }, tc -> { },
                    full -> { replyRef.set(full == null ? "" : full); done.countDown(); },
                    err -> done.countDown(),
                    done::countDown);
            AgentRunner.runStreaming(agent, conversation.id, "web", username, transcript,
                    cancel, cb, System.nanoTime(), List.of());
            try {
                done.await(10, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (cancel.get()) return;
            var reply = replyRef.get();
            send(out, lock, Map.of("type", "reply", "turn", turnId, "text", reply));

            if (!reply.isBlank()) {
                var enc = Base64.getEncoder();
                int i = 0;
                for (var chunk : TtsSentenceChunker.chunk(reply)) {
                    if (cancel.get() || !out.isOpen()) return;
                    var audio = TtsRouter.synthesize(chunk);
                    if (cancel.get()) return;
                    send(out, lock, Map.of("type", "audio", "turn", turnId, "index", i++,
                            "audio", enc.encodeToString(audio)));
                }
            }
            if (!cancel.get()) send(out, lock, Map.of("type", "turn_complete", "turn", turnId));
        } catch (RuntimeException e) {
            Logger.warn("voice: turn %d failed: %s", turnId, e.getMessage());
            if (!cancel.get()) {
                send(out, lock, Map.of("type", "error", "turn", turnId, "message", String.valueOf(e.getMessage())));
            }
        }
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
        } catch (RuntimeException e) {  // malformed Origin — treat as hostile
            return false;
        }
    }

    /** Session-cookie auth, mirroring {@link AuthCheck}'s session path (WS
     *  handshakes carry the cookie but no Bearer header): the signed session bit
     *  plus a DB cross-check so a cookie minted before a password reset can't
     *  pass. */
    private static boolean authenticated() {
        if (!"true".equals(session.get("authenticated"))) return false;
        var hash = ConfigService.get(ApiAuthController.PASSWORD_HASH_KEY);
        return hash != null && !hash.isBlank();
    }

    private static Agent resolveAgent(JsonObject msg) {
        if (!msg.has("agentId") || msg.get("agentId").isJsonNull()) return null;
        var agentId = msg.get("agentId").getAsLong();
        return Tx.run(() -> AgentService.findById(agentId));
    }

    /** Buffer the utterance to a temp WAV and transcribe on the local ASR. */
    private static String transcribe(AsrSidecarClient asr, byte[] wav) {
        try {
            var tmp = Files.createTempFile("jclaw-voice-", ".wav");
            try {
                Files.write(tmp, wav);
                var model = ConfigService.get("transcription.localModel");
                var segments = asr.transcribe(tmp, (model == null || model.isBlank()) ? "small" : model, null);
                return segments.stream().map(s -> s.text()).collect(Collectors.joining(" ")).strip();
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new TtsException("failed to buffer utterance for transcription: " + e.getMessage(), e);
        }
    }
}
