package agents;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.google.gson.Gson;

import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.ToolCall;
import models.Conversation;
import models.MessageRole;
import services.ConversationService;

import static utils.GsonHolder.INSTANCE;

/**
 * History → {@link ChatMessage} list hydration: walks the persisted
 * {@code Message} rows for a conversation, normalises tool-call IDs,
 * and emits a system-prompt-prefixed {@code ChatMessage} list ready
 * for the LLM. Extracted from {@link AgentRunner} as part of
 * JCLAW-299.
 *
 * <h3>What this class owns</h3>
 * <ul>
 *   <li>{@link #buildMessages} — the central history walker (with an
 *   {@code audioBearersOut} side-map for JCLAW-165 audio-rewrite, and
 *   a convenience overload that discards it).</li>
 *   <li>{@link #sanitizeToolCallId} — the JCLAW-119 ID normaliser used
 *   on both sides of an assistant-tool_calls / tool-result pair.</li>
 *   <li>{@link #parseToolCalls} — single-call JSON unpack with
 *   JCLAW-119 ID-normalisation applied.</li>
 *   <li>{@link #contentAsString} — flatten a multi-part vision content
 *   array back to a string (text-parts only).</li>
 * </ul>
 *
 * <h3>What this class does NOT own</h3>
 * The per-user-message content-part shaping (image_url, input_audio,
 * file-reference assembly) lives in {@link VisionAudioAssembler}; this
 * class composes its output via {@link VisionAudioAssembler#userMessageFor}.
 * Splitting them keeps the JCLAW-25 / JCLAW-132 / JCLAW-165 attachment
 * logic separable from the history-walk loop.
 */
public final class MessageHydrator {

    private static final Gson gson = INSTANCE;

    private MessageHydrator() {}

    /**
     * JCLAW-165: backward-compat overload for callers that don't need
     * the audio-bearer side-map (compaction path, tests). Drops the
     * captured refs on the floor.
     */
    static List<ChatMessage> buildMessages(String systemPrompt, Conversation conversation) {
        return buildMessages(systemPrompt, conversation, new ArrayList<>());
    }

    /**
     * Build the LLM message list and capture the audio-bearer side-map
     * concurrently. Caller passes in {@code audioBearersOut} (typically
     * a fresh ArrayList); the method appends one entry per user message
     * that has audio attachments. Inside-Tx use only — reads
     * conversation history via {@link ConversationService#loadRecentMessages}
     * which touches lazy collections.
     */
    static List<ChatMessage> buildMessages(String systemPrompt, Conversation conversation,
                                            List<VisionAudioAssembler.AudioBearer> audioBearersOut) {
        var messages = new ArrayList<ChatMessage>();
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
                case USER -> {
                    // Capture audio-bearer ids before assembling the
                    // ChatMessage so the rewrite path can re-target the
                    // exact slot in the messages list.
                    var atts = msg.attachments;
                    if (atts == null) atts = models.MessageAttachment.findByMessage(msg);
                    var audioIds = new ArrayList<Long>();
                    for (var a : atts) {
                        if (a.isAudio() && a.id != null) audioIds.add(a.id);
                    }
                    if (!audioIds.isEmpty()) {
                        audioBearersOut.add(new VisionAudioAssembler.AudioBearer(messages.size(), msg.id, audioIds));
                    }
                    yield VisionAudioAssembler.userMessageFor(msg);
                }
                case ASSISTANT -> {
                    if (msg.toolCalls != null && !msg.toolCalls.isBlank()) {
                        var toolCalls = parseToolCalls(msg.toolCalls);
                        for (var tc : toolCalls) {
                            if (tc.id() != null && tc.function() != null && tc.function().name() != null) {
                                toolNamesById.put(tc.id(), tc.function().name());
                            }
                        }
                        yield ChatMessage.assistant(msg.content, toolCalls);
                    }
                    yield ChatMessage.assistant(msg.content != null ? msg.content : "");
                }
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

        return messages;
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
    static String contentAsString(Object content) {
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

    static List<ToolCall> parseToolCalls(String json) {
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
