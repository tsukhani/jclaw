package agents;

import com.google.gson.Gson;
import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.ToolCall;
import models.Conversation;
import models.Message;
import models.MessageAttachment;
import models.MessageRole;
import services.ConversationService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static utils.GsonHolder.GSON;

/**
 * History → {@link ChatMessage} list hydration: walks the persisted
 * {@code Message} rows for a conversation, normalises tool-call IDs,
 * and emits a system-prompt-prefixed {@code ChatMessage} list ready
 * for the LLM. Extracted from {@link AgentRunner} as part of
 * JCLAW-299.
 *
 * <h2>What this class owns</h2>
 * <ul>
 *   <li>{@link #buildMessages} — the central history walker; returns a
 *   {@link Hydration} carrying the message list plus the audio / image /
 *   video attachment-bearer side-maps (JCLAW-165 / JCLAW-224).</li>
 *   <li>{@link #sanitizeToolCallId} — the JCLAW-119 ID normaliser used
 *   on both sides of an assistant-tool_calls / tool-result pair.</li>
 *   <li>{@link #parseToolCalls} — single-call JSON unpack with
 *   JCLAW-119 ID-normalisation applied.</li>
 *   <li>{@link #contentAsString} — flatten a multi-part vision content
 *   array back to a string (text-parts only).</li>
 * </ul>
 *
 * <h2>What this class does NOT own</h2>
 * The per-user-message content-part shaping (image_url, input_audio,
 * file-reference assembly) lives in {@link VisionAudioAssembler}; this
 * class composes its output via {@link VisionAudioAssembler#userMessageFor}.
 * Splitting them keeps the JCLAW-25 / JCLAW-132 / JCLAW-165 attachment
 * logic separable from the history-walk loop.
 *
 * <p>{@link #buildMessages}, {@link #contentAsString}, and
 * {@link #parseToolCalls} are static helpers exposed for direct invocation
 * by {@code MessageHydratorTest}. The test lives in the default package,
 * so package-private visibility wouldn't reach it; the helpers don't really
 * justify a curated public API surface, but the audit cost of reflection-based
 * test access exceeded the visibility-widening cost.
 */
public final class MessageHydrator {

    private static final Gson gson = GSON;

    private MessageHydrator() {}

    /**
     * Result of {@link #buildMessages}: the hydrated {@link ChatMessage} list
     * plus the three attachment-bearer side-maps (audio / image / video) that
     * the post-Tx capability rewrites re-target. Replaces the JCLAW-165 /
     * JCLAW-224 out-parameter overloads — one call now returns everything the
     * caller previously hand-assembled from mutable out-lists.
     */
    public record Hydration(
            List<ChatMessage> messages,
            List<VisionAudioAssembler.AudioBearer> audioBearers,
            List<VisionAudioAssembler.ImageBearer> imageBearers,
            List<VisionAudioAssembler.VideoBearer> videoBearers) {}

    /**
     * Build the LLM message list and capture the audio-, image-, and
     * video-bearer side-maps in one pass. Appends one bearer entry per user
     * message that has audio / image / video attachments so the post-Tx
     * capability rewrites ({@link VisionAudioAssembler#applyTranscriptsForCapability},
     * {@link VisionAudioAssembler#applyCaptionsForCapability},
     * {@link VisionAudioAssembler#applyVideoForCapability}) can re-target the
     * exact slots. Inside-Tx use only — reads conversation history via
     * {@link ConversationService#loadRecentMessages} which touches lazy collections.
     */
    public static Hydration buildMessages(String systemPrompt, Conversation conversation) {
        var messages = new ArrayList<ChatMessage>();
        var audioBearers = new ArrayList<VisionAudioAssembler.AudioBearer>();
        var imageBearers = new ArrayList<VisionAudioAssembler.ImageBearer>();
        var videoBearers = new ArrayList<VisionAudioAssembler.VideoBearer>();
        messages.add(ChatMessage.system(systemPrompt));

        // JCLAW-193: tool-row history doesn't store the function name, but
        // Ollama Cloud's Gemini bridge requires it on the tool-result message.
        // Recover it from the immediately-preceding ASSISTANT row's tool_calls
        // by registering id->name as we iterate; the loop is in chronological
        // order, so the assistant row containing the matching call is always
        // visited before its TOOL row.
        var toolNamesById = new HashMap<String, String>();

        var history = ConversationService.loadRecentMessages(conversation);
        for (var msg : history) {
            var role = MessageRole.fromValue(msg.role);
            messages.add(switch (role != null ? role : MessageRole.USER) {
                case USER -> hydrateUserMessage(msg, messages.size(), audioBearers, imageBearers, videoBearers);
                case ASSISTANT -> hydrateAssistantMessage(msg, toolNamesById);
                // JCLAW-119: sanitize the tool_call_id on the TOOL-role row so
                // it matches the normalized id on the assistant-row tool_calls.
                // Paired normalization is deterministic — same input string
                // produces the same output on both sides of the pair — so this
                // does not break pairing.
                case TOOL -> {
                    var sanitizedId = sanitizeToolCallId(msg.toolResults);
                    yield ChatMessage.toolResult(sanitizedId, toolNamesById.get(sanitizedId), msg.content);
                }
                case SYSTEM -> ChatMessage.system(msg.content);
            });
        }

        return new Hydration(messages, audioBearers, imageBearers, videoBearers);
    }

    /**
     * Build the USER {@link ChatMessage} and (when audio attachments are
     * present) record an {@link VisionAudioAssembler.AudioBearer} entry
     * into {@code audioBearersOut} so the post-Tx rewrite path can
     * re-target the exact slot in the messages list.
     */
    private static ChatMessage hydrateUserMessage(Message msg, int chatMessageIndex,
                                                  List<VisionAudioAssembler.AudioBearer> audioBearersOut,
                                                  List<VisionAudioAssembler.ImageBearer> imageBearersOut,
                                                  List<VisionAudioAssembler.VideoBearer> videoBearersOut) {
        var atts = msg.attachments;
        if (atts == null) atts = MessageAttachment.findByMessage(msg);
        var audioIds = new ArrayList<Long>();
        var imageIds = new ArrayList<Long>();
        var videoIds = new ArrayList<Long>();
        for (var a : atts) {
            if (a.isAudio() && a.id != null) audioIds.add(a.id);
            if (a.isImage() && a.id != null) imageIds.add(a.id);
            if (a.isVideo() && a.id != null) videoIds.add(a.id);
        }
        if (!audioIds.isEmpty()) {
            audioBearersOut.add(new VisionAudioAssembler.AudioBearer(chatMessageIndex, msg.id, audioIds));
        }
        if (!imageIds.isEmpty()) {
            imageBearersOut.add(new VisionAudioAssembler.ImageBearer(chatMessageIndex, msg.id, imageIds));
        }
        if (!videoIds.isEmpty()) {
            videoBearersOut.add(new VisionAudioAssembler.VideoBearer(chatMessageIndex, msg.id, videoIds));
        }
        return VisionAudioAssembler.userMessageFor(msg);
    }

    /**
     * Build the ASSISTANT {@link ChatMessage}, registering each tool_call
     * id → function name pair into {@code toolNamesById} so the matching
     * TOOL-row hydration can recover the function name (JCLAW-193).
     */
    private static ChatMessage hydrateAssistantMessage(Message msg, HashMap<String, String> toolNamesById) {
        if (msg.toolCalls == null || msg.toolCalls.isBlank()) {
            return ChatMessage.assistant(msg.content != null ? msg.content : "");
        }
        var toolCalls = parseToolCalls(msg.toolCalls);
        for (var tc : toolCalls) {
            if (tc.id() != null && tc.function() != null && tc.function().name() != null) {
                toolNamesById.put(tc.id(), tc.function().name());
            }
        }
        return ChatMessage.assistant(msg.content, toolCalls);
    }

    /**
     * Replace every character outside {@code [a-zA-Z0-9_-]} with
     * {@code '_'} (JCLAW-119). Historical tool_call IDs sometimes
     * carry provider-specific shapes — Gemini and some open-weight
     * model servers emit forms like {@code "functions.web_search:7"} —
     * that stricter providers (Ollama Cloud on kimi-k2.6, for example)
     * reject with HTTP 400 {@code "invalid tool call arguments"}.
     * Normalizing at read time lets a {@code /model} switch across
     * provider families keep working without mutating the DB or losing
     * context. Returns {@code null} unchanged so callers can detect
     * missing IDs.
     */
    public static String sanitizeToolCallId(String id) {
        if (id == null) return null;
        return id.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    /**
     * Safely extract string content from a {@link ChatMessage#content()}
     * which may be a {@code String} or a multi-part content array
     * (vision). Returns empty string if content is null or a non-string
     * type that can't be converted.
     */
    public static String contentAsString(Object content) {
        if (content instanceof String s) return s;
        if (content == null) return "";
        // Multi-part content (e.g. vision blocks): extract text parts
        if (content instanceof List<?> parts) {
            var sb = new StringBuilder();
            for (var part : parts) {
                if (part instanceof Map<?,?> m && m.get("text") instanceof String t) {
                    sb.append(t);
                }
            }
            return sb.toString();
        }
        return content.toString();
    }

    public static List<ToolCall> parseToolCalls(String json) {
        try {
            var tc = gson.fromJson(json, ToolCall.class);
            if (tc == null) return List.of();
            // JCLAW-119: normalize historical IDs so cross-provider /model
            // switches don't re-ship IDs the new provider rejects. Same
            // transformation as the TOOL-role sanitizer in buildMessages so
            // assistant-tool_calls and tool-row tool_call_id still pair.
            var safeId = sanitizeToolCallId(tc.id());
            if (!Objects.equals(safeId, tc.id())) {
                tc = new ToolCall(safeId, tc.type(), tc.function());
            }
            return List.of(tc);
        } catch (Exception _) {
            return List.of();
        }
    }
}
