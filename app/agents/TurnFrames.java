package agents;

import com.google.gson.JsonParser;
import llm.ProviderRegistry;
import models.Agent;
import models.Conversation;
import org.jspecify.annotations.Nullable;
import services.ModelOverrideResolver;
import utils.AppClock;
import utils.ChannelErrorTemplates;
import utils.ErrorRendering;
import utils.GsonHolder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static utils.GsonHolder.GSON;

/**
 * The frames a turn streams, one builder each: the chat stream sends them, and {@link services.ConversationTurnStream}
 * publishes the same JSON, so a page reads a watched turn with the parser it reads its own with.
 */
public final class TurnFrames {

    private TurnFrames() {}

    private static final String TYPE = "type";
    private static final String CONTENT = "content";

    /** The two frames that carry a delta: one per callback, so the chat stream builds them without a map. */
    public enum Chunk {
        TOKEN("token"),
        REASONING("reasoning");

        private final byte[] open;

        Chunk(String type) {
            this.open = ("{\"type\":\"" + type + "\",\"content\":").getBytes(StandardCharsets.UTF_8);
        }

        /** The bytes a frame of this kind adds to its escaped content: its opening, the content's quotes, its closing brace. */
        public int overhead() {
            return open.length + 3;
        }
    }

    /** {@code init}: the conversation, and the thinking mode the turn's model runs with. */
    public static Map<String, Object> init(Agent agent, Conversation conversation) {
        var initData = new HashMap<String, Object>(Map.of(TYPE, "init", "conversationId", conversation.id));
        // The same resolution AgentRunner applies — conversation override, then agent default,
        // gated by the effective model's capability — so the UI reflects what the LLM receives.
        var providerName = ModelOverrideResolver.provider(conversation, agent);
        var provider = providerName != null ? ProviderRegistry.get(providerName) : null;
        if (provider != null) {
            var mode = ModelResolver.resolveThinkingMode(agent, conversation, provider);
            if (mode != null) initData.put("thinkingMode", mode);
        }
        return initData;
    }

    /**
     * {@code init} as a conversation's watchers get it, carrying when the turn started, for a view that joins it late,
     * and the turn's id, which the page's Stop names ({@link services.ConversationQueue#ownerTurn}, JF-226); a turn
     * that owned no conversation as it began has none.
     */
    public static Map<String, Object> init(Agent agent, Conversation conversation, Instant startedAt,
                                           @Nullable Long turn) {
        var initData = new HashMap<>(init(agent, conversation));
        initData.put("startedAt", startedAt.toString());
        if (turn != null) initData.put("turn", turn);
        return initData;
    }

    /** {@code init} for a slash command's answer: the conversation it ran in, no model. */
    public static Map<String, Object> init(Long conversationId) {
        return Map.of(TYPE, "init", "conversationId", conversationId);
    }

    /** A turn's first {@code token}, stamped for the chat's time-to-first-token display. */
    public static Map<String, Object> firstToken(String token) {
        return Map.of(TYPE, "token", CONTENT, token, "timestamp", AppClock.now().toString());
    }

    /**
     * {@code lead}, the {@code kind} frame's JSON for {@code content}, then {@code tail}, in one array: the chat stream
     * sends 50–200 of these a second, and a shared prefix keeps each to one escaped string and one copy.
     */
    public static byte[] chunk(byte[] lead, Chunk kind, String content, byte[] tail) {
        var json = GSON.toJson(content).getBytes(StandardCharsets.UTF_8);
        var frame = new byte[lead.length + kind.open.length + json.length + 1 + tail.length];
        int at = 0;
        System.arraycopy(lead, 0, frame, at, lead.length);
        at += lead.length;
        System.arraycopy(kind.open, 0, frame, at, kind.open.length);
        at += kind.open.length;
        System.arraycopy(json, 0, frame, at, json.length);
        at += json.length;
        frame[at++] = '}';
        System.arraycopy(tail, 0, frame, at, tail.length);
        return frame;
    }

    /** {@code status}: content may itself be a JSON string carrying {@code usage}, {@code contextTokens} or {@code route}. */
    public static Map<String, Object> status(String content) {
        return Map.of(TYPE, "status", CONTENT, content);
    }

    /**
     * JCLAW-170: tool-call frame. structuredJson rides as a raw
     * JsonElement (not a nested string) so the frontend can parse
     * it as an object tree — search-style tools embed a
     * {provider, results:[...]} payload that the UI turns into
     * clickable result chips.
     */
    public static Map<String, Object> toolCall(AgentRunner.ToolCallEvent ev) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put(TYPE, "tool_call");
        payload.put("id", ev.id());
        payload.put("name", ev.name());
        payload.put("icon", ev.icon());
        payload.put("arguments", ev.arguments());
        payload.put("resultText", ev.resultText() == null ? "" : ev.resultText());
        if (ev.resultStructuredJson() != null) {
            payload.put("resultStructured", JsonParser.parseString(ev.resultStructuredJson()));
        }
        // JCLAW-228/562: the tool produced inline attachments (image / voice clips); ship their
        // metadata so the chat UI renders them live on the streaming bubble rather than only
        // after a reload.
        if (ev.generatedAttachmentsJson() != null) {
            payload.put("generatedAttachments", JsonParser.parseString(ev.generatedAttachmentsJson()));
        }
        return payload;
    }

    /** {@code complete}: the turn's reply. */
    public static Map<String, Object> complete(String content) {
        return Map.of(TYPE, "complete", CONTENT, content);
    }

    /**
     * {@code error}: the 3-part template (JCLAW-1133), rich because the chat renders Markdown. Never the throwable's
     * own message, which goes to event_log alongside.
     */
    public static Map<String, Object> error(Throwable error) {
        return Map.of(TYPE, "error", CONTENT, ErrorRendering.RICH.render(ChannelErrorTemplates.forTurnFailure(error)));
    }

    /** {@code stopped}: the turn was cancelled; whatever it had not committed is gone. */
    public static Map<String, Object> stopped() {
        return Map.of(TYPE, "stopped");
    }

    /**
     * {@code resync}: frames the client cannot have are gone where it stands, dropped from the replay or merged into a
     * later frame, so it reloads the conversation's rows and rebuilds the turn from its {@code init} and the frames
     * after the resync.
     */
    public static Map<String, Object> resync() {
        return Map.of(TYPE, "resync");
    }

    /**
     * A frame's JSON as the chat stream writes it: {@code SseStream.send} serializes with a default {@link
     * com.google.gson.Gson}, which {@link GsonHolder#SCRIPT_SAFE} is.
     */
    public static byte[] json(Map<String, Object> frame) {
        return GsonHolder.SCRIPT_SAFE.toJson(frame).getBytes(StandardCharsets.UTF_8);
    }
}
