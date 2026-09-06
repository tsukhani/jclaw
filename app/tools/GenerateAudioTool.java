package tools;

import agents.GeneratedAttachment;
import agents.ToolAction;
import agents.ToolContext;
import agents.ToolRegistry;
import com.google.gson.JsonParser;
import models.Agent;
import org.jspecify.annotations.Nullable;
import play.Logger;
import services.ConfigService;
import services.ConversationService;
import services.Tx;
import services.tts.TtsRouter;
import services.tts.VoiceNoteEncoder;
import utils.JsonArgs;

import java.util.List;
import java.util.Map;

/**
 * {@code generate_audio} (JCLAW-876): speak a reply aloud and deliver it as an
 * audio attachment.
 *
 * <p>The asynchronous counterpart to voice mode. Voice mode needs a live socket,
 * an endpointer and barge-in handling; this is the same voice reaching the
 * operator through whatever channel they are already on — a Telegram voice note
 * answering the one they sent, an audio file in web chat. No socket, no realtime
 * budget.
 *
 * <p>Voice selection is deliberately not a parameter. {@link TtsRouter#synthesize}
 * resolves the operator's configured engine, model, voice and reference clip, so
 * a cloned voice set up in Settings&nbsp;&gt;&nbsp;Speech is automatically the
 * agent's voice here, and it stays consistent with read-aloud and voice mode. A
 * model picking its own voice per call would undo that.
 *
 * <p>Default-OFF per agent ({@code ToolRegistry.computeDisabledTools}), like
 * {@code generate_image} and {@code generate_video} — synthesis costs seconds to
 * tens of seconds and can trigger a sidecar model load, so an operator opts each
 * agent in.
 */
public class GenerateAudioTool implements ToolRegistry.Tool {

    static final String NAME = "generate_audio";
    private static final String ARG_TEXT = "text";
    private static final String ARG_SAVE_TO = "save_to";

    /** Shared with read-aloud rather than a second limit of its own: the ceiling is
     *  a property of the TTS engines, not of who is calling them. */
    private static final String MAX_CHARS_KEY = "tts.maxChars";
    private static final int MAX_CHARS_DEFAULT = 5000;

    @Override public String name() { return NAME; }

    @Override public String category() { return "Utilities"; }

    @Override public String icon() { return "speaker"; }

    @Override
    public List<ToolAction> actions() {
        return List.of(new ToolAction("speak",
                "Synthesize speech from text and attach it to the reply as an audio file"));
    }

    @Override
    public String description() {
        return """
                Speak text aloud in your own voice and attach it to your reply as an audio file. \
                Use this when the user asks to be answered in audio, sends you a voice message and \
                would naturally get one back, or is in a situation where listening beats reading. \
                Pass the words to be spoken as 'text' — write them the way they should sound, since \
                they are read verbatim: no markdown, no bullet lists, no code blocks, no URLs. Keep \
                it to what is worth hearing; a long document is a bad audio message. The voice is \
                the one the operator configured in Settings, so do not ask the user to choose one. \
                The audio is attached automatically and the user can play it — do not try to link \
                or embed it in your reply text.""";
    }

    @Override
    public String summary() {
        return "Speak text aloud in the configured voice and attach it as an audio file.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                SchemaKeys.TYPE, SchemaKeys.OBJECT,
                SchemaKeys.PROPERTIES, Map.of(
                        ARG_SAVE_TO, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.DESCRIPTION, "Optional filename, relative to your workspace, to also write "
                                        + "the audio to (e.g. \"message.mp3\"). Use this whenever you need the file "
                                        + "afterwards — to attach it, send it, or pass it to a command. Without it "
                                        + "the audio is only attached to the reply and NO file exists on disk; do "
                                        + "not go looking for one."),
                        ARG_TEXT, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.DESCRIPTION, "The words to speak, written as they should "
                                        + "sound. Read verbatim — plain prose only, no markdown or URLs.")
                ),
                SchemaKeys.REQUIRED, List.of(ARG_TEXT)
        );
    }

    /** One synthesis per call, and the sidecar serializes on a JVM-wide lock
     *  anyway — running these in parallel would queue, not overlap. */
    @Override public boolean parallelSafe() { return false; }

    @Override
    public String execute(String argsJson, Agent agent) {
        return executeRich(argsJson, agent).text();
    }

    /**
     * Which channel will deliver this reply, so the audio can be encoded in the
     * container that client actually plays inline — Telegram and WhatsApp render
     * OGG/Opus as a voice note, Slack only inlines MP3.
     *
     * <p>Null when there is no conversation in scope (a scheduled task run), which
     * the encoder treats as "assume nothing" and takes the broadly-playable path.
     * Own transaction: tool execution runs off the request path.
     */
    private static @Nullable String deliveringChannel() {
        var conversationId = ToolContext.conversationId();
        if (conversationId == null) return null;
        return Tx.run(() -> {
            var conversation = ConversationService.findById(conversationId);
            return conversation == null ? null : conversation.channelType;
        });
    }

    @Override
    public ToolRegistry.ToolResult executeRich(String argsJson, Agent agent) {
        String text;
        String saveTo;
        try {
            var args = JsonParser.parseString(argsJson).getAsJsonObject();
            text = JsonArgs.optString(args, ARG_TEXT, "");
            saveTo = JsonArgs.optString(args, ARG_SAVE_TO);
        } catch (RuntimeException e) {
            return ToolRegistry.ToolResult.text("Error: could not parse arguments: " + e.getMessage());
        }

        if (text == null || text.isBlank()) {
            return ToolRegistry.ToolResult.text("Error: 'text' is required — pass the words to speak.");
        }
        // Config read in its own transaction: tool execution runs off the request
        // path, where a cache miss would otherwise have no EntityManager.
        int maxChars = Tx.run(() -> ConfigService.getInt(MAX_CHARS_KEY, MAX_CHARS_DEFAULT));
        if (text.length() > maxChars) {
            return ToolRegistry.ToolResult.text(
                    ("Error: text too long to speak (%d > %d characters). Say the essential part "
                            + "instead of the whole thing, or split it across turns.")
                            .formatted(text.length(), maxChars));
        }

        try {
            var wav = TtsRouter.synthesize(text);
            var encoded = VoiceNoteEncoder.forChannel(wav, deliveringChannel());
            var filename = "reply." + encoded.extension();
            Logger.info("generate_audio: spoke %d chars -> %d bytes (%s)",
                    text.length(), encoded.bytes().length, encoded.mimeType());
            // Same framing as generate_image: the attachment is delivered
            // out-of-band, so tell the model it is already there. A model that
            // tries to "helpfully" link the audio has to invent a URL, which
            // resolves to nothing and renders as a dead link.
            var summary = "Audio attached to your reply and playable by the user. It is already "
                    + "attached — do not link or embed it; just say what you sent, briefly.";
            if (saveTo != null && !saveTo.isBlank()) {
                // Optional workspace copy — a scheduled task has no reply to attach to.
                try {
                    var savedPath = GeneratedMediaFile.write(agent, saveTo.trim(), encoded.bytes());
                    summary += " Also saved to " + savedPath + " — use exactly that path; no other "
                            + "copy of this audio exists on disk.";
                } catch (IllegalArgumentException e) {
                    return ToolRegistry.ToolResult.text(
                            "Audio generated, but saving it failed: " + e.getMessage());
                }
            }
            return new ToolRegistry.ToolResult(summary, null,
                    List.of(new GeneratedAttachment(encoded.bytes(), encoded.mimeType(),
                            NAME, filename)),
                    null);
        } catch (RuntimeException e) {
            // The engine is the operator's to fix, and the model can still answer in
            // text — surface the reason rather than failing the whole turn.
            return ToolRegistry.ToolResult.text(
                    "Speech synthesis failed: " + e.getMessage() + ". Answer in text instead.");
        }
    }
}
