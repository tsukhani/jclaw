package channels;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonObject;
import models.Agent;
import models.MessageAttachment;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import play.Play;
import services.AttachmentService;
import services.EventLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Telegram inbound-update parsing, extracted from {@link TelegramChannel} in
 * JCLAW-151. Pure static functions: turn a Bot API {@link Update} (or raw
 * {@link JsonObject}) into an {@link InboundMessage} / {@link InboundCallback},
 * apply the inbound modality + size gates ({@link #prepareInboundAttachments}),
 * and decide bot-addressed / wake-word gating. Holds no per-token state; the
 * one network touch (attachment download) is delegated back through
 * {@link TelegramChannel#forToken}.
 */
public final class TelegramInboundParser {

    private static final ObjectMapper JACKSON = new ObjectMapper();
    private static final String LOG_CATEGORY = "channel";
    private static final String CHANNEL_NAME = "telegram";

    private TelegramInboundParser() {}

    /**
     * JCLAW-136: apply the modality + size gates to {@code message.attachments()},
     * then download each pending attachment into the agent's workspace
     * staging directory. Shared by the webhook controller and polling
     * runner — both route user uploads through the same rejection + stage
     * pipeline so behavior stays identical regardless of delivery mode.
     *
     * <p>On any rejection (modality mismatch, size exceeded, network/API
     * failure) sends a user-visible reply, logs at warn, and returns
     * {@code null} so the caller can bail out. On success returns a
     * (possibly empty) list of {@link services.AttachmentService.Input}
     * whose shape is identical to what the web upload path produces — the
     * runner handles both origins uniformly.
     */
    // S1168: null is the deliberate "rejected, helper already replied + logged"
    // sentinel — callers explicitly `if (inputs == null) return` to abort the
    // turn. An empty list, by contrast, means "no attachments to process,
    // continue with text only" — they are semantically distinct outcomes.
    @SuppressWarnings("java:S1168")
    public static List<AttachmentService.Input> prepareInboundAttachments(
            String sendToken, String sendChatId, Agent sendAgent, InboundMessage message) {
        if (message.attachments().isEmpty()) return List.of();

        // JCLAW-165 / JCLAW-215: audio and images are universally accepted —
        // text-only models get a transcript / caption text part (via the
        // transcription + captioning pipelines), audio-/vision-capable models
        // get native input_audio / image_url. No model-side gate; the rest of
        // the pipeline (AgentRunner.userMessageFor) handles the downgrade.

        var inputs = new ArrayList<AttachmentService.Input>(
                message.attachments().size());
        for (var pending : message.attachments()) {
            var result = TelegramFileDownloader.download(sendToken, pending, sendAgent.name);
            if (result instanceof TelegramFileDownloader.Ok(var input)) {
                inputs.add(input);
            } else if (result instanceof TelegramFileDownloader.SizeExceeded(var actualBytes, var limit)) {
                TelegramChannel.forToken(sendToken).sendText(sendChatId,
                        "That file is too large — Telegram bots can only accept up to %d MB.".formatted(
                                TelegramFileDownloader.MAX_FILE_BYTES / (1024 * 1024)));
                EventLogger.warn(LOG_CATEGORY, sendAgent.name, CHANNEL_NAME,
                        "Rejected upload: %d bytes exceeds %d limit".formatted(actualBytes, limit));
                return null;
            } else if (result instanceof TelegramFileDownloader.DownloadFailed(var reason)) {
                TelegramChannel.forToken(sendToken).sendText(sendChatId,
                        "Sorry, I couldn't download your file from Telegram.");
                EventLogger.warn(LOG_CATEGORY, sendAgent.name, CHANNEL_NAME,
                        "Download failed: %s".formatted(reason));
                return null;
            }
        }
        return inputs;
    }

    /** Parse a Gson {@link JsonObject} update (webhook payload) into {@link InboundMessage}. */
    public static InboundMessage parseUpdate(JsonObject update) {
        try {
            Update sdk = JACKSON.readValue(update.toString(), Update.class);
            return parseUpdate(sdk);
        } catch (Exception e) {
            EventLogger.warn(LOG_CATEGORY, null, CHANNEL_NAME,
                    "Update parse error: %s".formatted(e.getMessage()));
            return null;
        }
    }

    /**
     * Parse an SDK {@link Update} (polling runner source) into {@link InboundMessage}.
     *
     * <p>JCLAW-136: accepts messages with attachments (photo, voice, audio,
     * document, video, video_note) in addition to plain text. When the user
     * uploads a file, {@code msg.hasText()} is false — the typed prose lives
     * in {@code msg.getCaption()} instead. The returned InboundMessage
     * populates {@code text} from caption-then-text (empty when neither) and
     * populates {@code attachments} with one {@link PendingAttachment} per
     * media field present. Returns {@code null} only when the update has
     * neither text nor caption nor any recognizable attachment.
     *
     * <p>JCLAW-367: this single-arg form has no bot identity to resolve
     * {@code @mention} / {@code text_mention} / {@code /cmd@botname} against,
     * so {@link InboundMessage#botMentioned()} only fires on the
     * identity-independent signal (a reply to a bot message that the SDK can
     * see). Prefer {@link #parseUpdate(Update, String, Long)} where the
     * caller knows the bot's username/id; the kept single-arg overload exists
     * so existing call sites compile unchanged.
     */
    public static InboundMessage parseUpdate(Update update) {
        return parseUpdate(update, null, null);
    }

    /**
     * JCLAW-367: identity-aware parse. {@code botUsername} (the bot's
     * {@code @}-handle, without the leading {@code @}; null if unknown) and
     * {@code botUserId} (the bot's numeric Telegram user id; null if unknown)
     * let the entity scan decide whether a {@code mention} / {@code text_mention}
     * / {@code bot_command@suffix} addresses <i>this</i> bot. Entity OFFSETS are
     * used, never substring search, so an {@code @handle} or {@code /cmd}
     * appearing inside a URL or code span never false-positives.
     *
     * <p>The bot user id is derivable from the bot token (Telegram tokens are
     * {@code <bot_id>:<hash>}) by the caller; the username is only known after
     * a {@code getMe} call. When neither is supplied this degrades exactly to
     * {@link #parseUpdate(Update)}'s best-effort behavior.
     */
    public static InboundMessage parseUpdate(Update update, String botUsername, Long botUserId) {
        if (update == null || update.getMessage() == null) return null;
        Message msg = update.getMessage();

        String chatId = String.valueOf(msg.getChatId());
        String chatType = msg.getChat() != null ? msg.getChat().getType() : null;
        String fromId = null;
        String fromUsername = null;
        String fromDisplayName = null;
        if (msg.getFrom() != null) {
            fromId = String.valueOf(msg.getFrom().getId());
            fromUsername = msg.getFrom().getUserName();
            fromDisplayName = displayNameOf(msg.getFrom());
        }

        var attachments = new ArrayList<PendingAttachment>();
        collectPhotoAttachment(msg, attachments);
        collectAudioAttachments(msg, attachments);
        collectFileAttachments(msg, attachments);
        // JCLAW-366: a static WEBP sticker stages as an image attachment;
        // animated/video stickers stage nothing (placeholder note only).
        collectStickerAttachment(msg, attachments);

        // Caption wins over plain-text when both exist is impossible per
        // Telegram's shape — a given Message carries either text or caption,
        // never both. Pick whichever is populated; empty string is fine
        // (means "user attached a file with no prose context").
        String text = pickInboundText(msg);

        // JCLAW-366: stickers, location, and venue messages carry no text and
        // (for animated/video stickers, location, venue) no downloadable
        // attachment, so they previously hit the empty-drop below. Surface
        // each as a text context note so the turn is no longer discarded.
        String note = inboundContextNote(msg);
        if (!note.isEmpty()) {
            text = text.isEmpty() ? note : text + "\n" + note;
        }

        boolean botMentioned = detectBotAddressed(msg, botUsername, botUserId);

        String mediaGroupId = msg.getMediaGroupId();

        // JCLAW-368: capture the inbound message id verbatim, and the
        // forum-topic thread id only when this message is actually scoped to
        // a topic. Telegram populates message_thread_id on non-topic replies
        // too; gating on isTopicMessage keeps the field null for plain
        // (non-topic) messages, which is the contract this record promises.
        Integer messageId = msg.getMessageId();
        Integer messageThreadId = msg.isTopicMessage() ? msg.getMessageThreadId() : null;

        // JCLAW-366: fold the replied-to / natively-quoted context into a
        // supplemental block carried alongside (not inside) text.
        String replyContext = buildReplyContext(msg);

        // Fully empty updates (no text, no caption, no attachment, no
        // sticker/location/venue note) are nothing we can act on — drop as
        // before. A reply with no body of its own (replyContext only) is not
        // actionable on its own, so it does not keep the turn alive.
        if (text.isEmpty() && attachments.isEmpty()) return null;

        return new InboundMessage(chatId, chatType, text, fromId, fromUsername,
                fromDisplayName, botMentioned, attachments, mediaGroupId,
                messageId, messageThreadId, replyContext);
    }

    /**
     * Build a human-readable display name from a Telegram {@link User} for
     * transcript attribution (JCLAW-367): "First Last" when names are present,
     * falling back to the {@code @}-handle, else null. Telegram guarantees a
     * non-blank first name on real users, but a defensive trim keeps the
     * result clean for edge shapes.
     */
    private static String displayNameOf(User user) {
        var sb = new StringBuilder();
        if (user.getFirstName() != null && !user.getFirstName().isBlank()) {
            sb.append(user.getFirstName().strip());
        }
        if (user.getLastName() != null && !user.getLastName().isBlank()) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(user.getLastName().strip());
        }
        if (!sb.isEmpty()) return sb.toString();
        return (user.getUserName() != null && !user.getUserName().isBlank())
                ? user.getUserName().strip() : null;
    }

    /**
     * JCLAW-367: decide whether the bot is directly addressed by {@code msg}.
     * Uses entity offsets (never substring search) so {@code @handle}/{@code /cmd}
     * inside a URL, {@code code} span, or {@code text_link} can't false-positive.
     * Fires on any of:
     * <ul>
     *   <li>a {@code mention} entity whose {@code @handle} (case-insensitively)
     *       equals {@code botUsername};</li>
     *   <li>a {@code text_mention} entity whose embedded {@link User} id equals
     *       {@code botUserId};</li>
     *   <li>a {@code bot_command} entity carrying a {@code @botusername} suffix
     *       that matches {@code botUsername};</li>
     *   <li>a reply to a message authored by the bot itself (matched by
     *       {@code botUserId}, or — when the id is unknown — by the reply
     *       target being authored by any bot, the best-effort fallback).</li>
     * </ul>
     */
    private static boolean detectBotAddressed(Message msg, String botUsername, Long botUserId) {
        if (entitiesAddressBot(msg.getText(), msg.getEntities(), botUsername, botUserId)
                || entitiesAddressBot(msg.getCaption(), msg.getCaptionEntities(), botUsername, botUserId)) {
            return true;
        }
        if (isReplyToBot(msg, botUserId)) return true;
        // JCLAW-387 (B3): operator-configured regex wake-words. If the message
        // body (text or caption) matches any configured pattern, treat the
        // message as addressed to the bot — same access effect as a @mention.
        return matchesWakeWord(msg.getText()) || matchesWakeWord(msg.getCaption());
    }

    // ── JCLAW-387 (B3): configurable regex wake-word patterns ────────────

    /**
     * JCLAW-387 (B3): config key for operator-configured wake-word patterns.
     * Value is a newline-or-comma-separated list of Java regexes; a group
     * message whose text matches any of them is treated as addressed to the bot
     * (see {@link #matchesWakeWord(String)}). Empty / unset disables the feature.
     */
    static final String CFG_MENTION_PATTERNS = "telegram.mentionPatterns";

    /**
     * Cached compiled wake-word patterns, keyed by the raw config string they
     * were compiled from, so a config change recompiles but a steady config
     * compiles exactly once. {@code volatile} single-slot cache: the hot path
     * reads {@link CompiledPatterns#source} and reuses {@link CompiledPatterns#patterns}
     * when the source is unchanged. An invalid regex is skipped (logged once at
     * compile time), never thrown on the matching hot path.
     */
    // AtomicReference (not a volatile field) per java:S3077: volatile only
    // publishes the reference, not the referent's internals — a thread-safe
    // holder makes the intent explicit. CompiledPatterns is a record holding an
    // immutable pattern list (compileWakeWords returns List.copyOf), so the
    // cached value is genuinely immutable and safely published.
    private static final AtomicReference<CompiledPatterns> wakeWordCache =
            new AtomicReference<>();

    /** Immutable (raw-config, compiled-patterns) pair for the wake-word cache. */
    private record CompiledPatterns(String source, List<Pattern> patterns) {}

    /**
     * JCLAW-387 (B3): true when {@code body} matches any configured wake-word
     * pattern. Returns false on null/blank body or when the feature is off
     * (empty config). Patterns are compiled once and cached (see
     * {@link #wakeWordCache}); an unmatched body or an empty pattern set is a
     * cheap false. Never throws — an invalid regex is dropped at compile time.
     *
     * <p>Public so default-package tests can assert the match / off / invalid-skip
     * contract directly, matching the {@link TelegramChannel#replyToMode()} convention.
     */
    public static boolean matchesWakeWord(String body) {
        if (body == null || body.isBlank()) return false;
        var compiled = compiledWakeWords();
        for (var p : compiled) {
            if (p.matcher(body).find()) return true;
        }
        return false;
    }

    /**
     * Resolve the compiled wake-word patterns, recompiling only when the raw
     * config value changes. Reads {@link #CFG_MENTION_PATTERNS} via
     * {@link play.Play#configuration}. Splits on newlines and commas, trims, and
     * compiles each non-blank token; an invalid regex is skipped with a warn log
     * (so one bad pattern can't disable the rest, and the hot path never sees a
     * {@link java.util.regex.PatternSyntaxException}).
     */
    private static List<Pattern> compiledWakeWords() {
        var raw = Play.configuration.getProperty(CFG_MENTION_PATTERNS, "");
        if (raw == null) raw = "";
        var cached = wakeWordCache.get();
        if (cached != null && cached.source().equals(raw)) return cached.patterns();
        var compiled = compileWakeWords(raw);
        wakeWordCache.set(new CompiledPatterns(raw, compiled));
        return compiled;
    }

    /** Split, trim, and compile the raw wake-word config into patterns, skipping invalid regexes. */
    private static List<Pattern> compileWakeWords(String raw) {
        var out = new ArrayList<Pattern>();
        if (raw.isBlank()) return List.of();
        for (var token : raw.split("[\\n,]")) {
            var trimmed = token.trim();
            if (trimmed.isEmpty()) continue;
            try {
                out.add(Pattern.compile(trimmed));
            } catch (PatternSyntaxException e) {
                EventLogger.warn(LOG_CATEGORY, null, CHANNEL_NAME,
                        "Ignoring invalid telegram.mentionPatterns regex %s: %s"
                                .formatted(trimmed, e.getMessage()));
            }
        }
        // Immutable so the cached CompiledPatterns can't be mutated post-publish.
        return List.copyOf(out);
    }

    /** Scan one text/entity pair for a mention, text_mention, or bot_command suffix addressing the bot. */
    private static boolean entitiesAddressBot(String body, List<MessageEntity> entities,
                                              String botUsername, Long botUserId) {
        if (body == null || entities == null) return false;
        for (var entity : entities) {
            if (entityAddressesBot(body, entity, botUsername, botUserId)) return true;
        }
        return false;
    }

    private static boolean entityAddressesBot(String body, MessageEntity entity,
                                              String botUsername, Long botUserId) {
        var type = entity.getType();
        if (type == null) return false;
        return switch (type) {
            case "mention" -> botUsername != null
                    && botUsername.equalsIgnoreCase(stripLeadingAt(entitySlice(body, entity)));
            case "text_mention" -> botUserId != null
                    && entity.getUser() != null
                    && botUserId.equals(entity.getUser().getId());
            case "bot_command" -> commandSuffixMatchesBot(entitySlice(body, entity), botUsername);
            default -> false;
        };
    }

    /**
     * Safe offset-based slice of the entity text. {@link MessageEntity#computeText}
     * is only populated by the SDK's getters in some shapes, so we slice from
     * the offset/length ourselves and clamp to the body bounds to stay robust
     * against malformed offsets.
     */
    private static String entitySlice(String body, MessageEntity entity) {
        if (entity.getOffset() == null || entity.getLength() == null) return "";
        int start = Math.max(0, entity.getOffset());
        int end = Math.min(body.length(), start + Math.max(0, entity.getLength()));
        return start <= end ? body.substring(start, end) : "";
    }

    private static String stripLeadingAt(String s) {
        return s.startsWith("@") ? s.substring(1) : s;
    }

    /**
     * A bot_command entity slice is shaped {@code /cmd} or {@code /cmd@botname}.
     * Returns true only when a {@code @suffix} is present AND matches the bot's
     * own username — a bare {@code /cmd} (no suffix) is not a direct address in
     * a group, so it does not fire the signal here.
     */
    private static boolean commandSuffixMatchesBot(String slice, String botUsername) {
        if (botUsername == null) return false;
        int at = slice.indexOf('@');
        if (at < 0) return false;
        return botUsername.equalsIgnoreCase(slice.substring(at + 1));
    }

    /**
     * True when {@code msg} replies to a message the bot itself authored. When
     * {@code botUserId} is known, match the reply target's author by id; when it
     * is unknown (single-arg parse), fall back to "the reply target was authored
     * by a bot" — best-effort, since in a 1:1 binding the only bot in the chat
     * is ours.
     */
    private static boolean isReplyToBot(Message msg, Long botUserId) {
        var replyTo = msg.getReplyToMessage();
        if (replyTo == null || replyTo.getFrom() == null) return false;
        var author = replyTo.getFrom();
        if (botUserId != null) return botUserId.equals(author.getId());
        return Boolean.TRUE.equals(author.getIsBot());
    }

    /**
     * Highest-resolution PhotoSize wins — the array is sorted ascending by
     * Telegram, so the last element is the full-quality original.
     */
    private static void collectPhotoAttachment(Message msg, List<PendingAttachment> attachments) {
        if (!msg.hasPhoto() || msg.getPhoto() == null || msg.getPhoto().isEmpty()) return;
        var sizes = msg.getPhoto();
        var best = sizes.get(sizes.size() - 1);
        long bytes = best.getFileSize() != null ? best.getFileSize() : 0L;
        attachments.add(new PendingAttachment(
                best.getFileId(), null, "image/jpeg", bytes,
                MessageAttachment.KIND_IMAGE));
    }

    /**
     * Voice notes (OGG Opus) and audio files (mp3/m4a/etc.) both map to
     * KIND_AUDIO. getMimeType is nullable — finalizeAttachment re-sniffs
     * with Tika so we're not relying on Telegram's self-report anyway.
     */
    private static void collectAudioAttachments(Message msg, List<PendingAttachment> attachments) {
        if (msg.getVoice() != null) {
            var v = msg.getVoice();
            long bytes = v.getFileSize() != null ? v.getFileSize() : 0L;
            attachments.add(new PendingAttachment(
                    v.getFileId(), null, v.getMimeType(), bytes,
                    MessageAttachment.KIND_AUDIO));
        }
        if (msg.hasAudio() && msg.getAudio() != null) {
            var a = msg.getAudio();
            long bytes = a.getFileSize() != null ? a.getFileSize() : 0L;
            attachments.add(new PendingAttachment(
                    a.getFileId(), a.getFileName(), a.getMimeType(), bytes,
                    MessageAttachment.KIND_AUDIO));
        }
    }

    /** Documents, videos, and video notes — all map to KIND_FILE, except image/* docs which stay KIND_IMAGE. */
    private static void collectFileAttachments(Message msg, List<PendingAttachment> attachments) {
        if (msg.hasDocument() && msg.getDocument() != null) {
            var d = msg.getDocument();
            long bytes = d.getFileSize() != null ? d.getFileSize() : 0L;
            // A document whose MIME starts with image/ is still an inline
            // image (user uploaded via "File" rather than "Photo" to avoid
            // Telegram's compression). Classify as IMAGE so the multimodal
            // gate applies correctly and the model receives an image part.
            String kind = d.getMimeType() != null && d.getMimeType().startsWith("image/")
                    ? MessageAttachment.KIND_IMAGE
                    : MessageAttachment.KIND_FILE;
            attachments.add(new PendingAttachment(
                    d.getFileId(), d.getFileName(), d.getMimeType(), bytes, kind));
        }
        if (msg.hasVideo() && msg.getVideo() != null) {
            var v = msg.getVideo();
            long bytes = v.getFileSize() != null ? v.getFileSize() : 0L;
            attachments.add(new PendingAttachment(
                    v.getFileId(), v.getFileName(), v.getMimeType(), bytes,
                    MessageAttachment.KIND_FILE));
        }
        if (msg.hasVideoNote() && msg.getVideoNote() != null) {
            var vn = msg.getVideoNote();
            long bytes = vn.getFileSize() != null ? vn.getFileSize() : 0L;
            attachments.add(new PendingAttachment(
                    vn.getFileId(), null, null, bytes,
                    MessageAttachment.KIND_FILE));
        }
    }

    private static String pickInboundText(Message msg) {
        if (msg.hasText()) return msg.getText();
        if (msg.getCaption() != null) return msg.getCaption();
        return "";
    }

    /**
     * JCLAW-366: stage a STATIC (non-animated, non-video) sticker's WEBP as an
     * image attachment so the model can see the picture, reusing the same
     * {@code getFile} download path as photos. Animated (TGS) and video (WEBM)
     * stickers are NOT staged — they aren't a still image the vision path can
     * consume, and we deliberately don't convert them — the
     * {@link #stickerNote(org.telegram.telegrambots.meta.api.objects.stickers.Sticker)}
     * placeholder is the only surfacing for those. Telegram's WEBP is sniffed
     * to {@code image/webp} on disk by {@code finalizeAttachment}; the reported
     * MIME here is a best-effort hint.
     */
    private static void collectStickerAttachment(Message msg, List<PendingAttachment> attachments) {
        if (!msg.hasSticker() || msg.getSticker() == null) return;
        var s = msg.getSticker();
        boolean animated = Boolean.TRUE.equals(s.getIsAnimated());
        boolean video = Boolean.TRUE.equals(s.getIsVideo());
        if (animated || video) return; // placeholder note only
        long bytes = s.getFileSize() != null ? s.getFileSize() : 0L;
        attachments.add(new PendingAttachment(
                s.getFileId(), null, "image/webp", bytes,
                MessageAttachment.KIND_IMAGE));
    }

    /**
     * JCLAW-366: build a text context note for the non-attachment "rich" inbound
     * types this story surfaces — sticker, location, venue. Returns "" when none
     * are present. A sticker yields {@code [sticker: 😀 (set X)]}; a venue yields
     * its title/address; a bare location yields its lat/long. Only one of these
     * is ever present on a given Message, but they're checked independently so a
     * future combined shape still degrades gracefully.
     */
    private static String inboundContextNote(Message msg) {
        var parts = new ArrayList<String>(1);
        if (msg.hasSticker() && msg.getSticker() != null) {
            parts.add(stickerNote(msg.getSticker()));
        }
        // Venue wraps a location, so prefer the richer venue note and skip the
        // bare-location branch when a venue is present.
        if (msg.getVenue() != null) {
            parts.add(venueNote(msg.getVenue()));
        } else if (msg.hasLocation() && msg.getLocation() != null) {
            parts.add(locationNote(msg.getLocation()));
        }
        return String.join("\n", parts);
    }

    /**
     * {@code [sticker: <emoji> (set <name>)]} — emoji and set name are both
     * optional on the Bot API object, so each is omitted when absent. A sticker
     * with neither degrades to a bare {@code [sticker]}.
     */
    private static String stickerNote(org.telegram.telegrambots.meta.api.objects.stickers.Sticker s) {
        var sb = new StringBuilder("[sticker");
        boolean hasEmoji = s.getEmoji() != null && !s.getEmoji().isBlank();
        if (hasEmoji) sb.append(": ").append(s.getEmoji().strip());
        if (s.getSetName() != null && !s.getSetName().isBlank()) {
            sb.append(hasEmoji ? " " : ": ").append("(set ").append(s.getSetName().strip()).append(')');
        }
        return sb.append(']').toString();
    }

    /** {@code [location: <lat>, <long>]} from the location's coordinates. */
    private static String locationNote(org.telegram.telegrambots.meta.api.objects.location.Location loc) {
        return "[location: %s, %s]".formatted(loc.getLatitude(), loc.getLongitude());
    }

    /**
     * {@code [venue: <title> — <address> (<lat>, <long>)]}. Title/address are
     * appended when present; the embedded location's coordinates ride in
     * parentheses when the venue carries a location (it always should).
     */
    private static String venueNote(org.telegram.telegrambots.meta.api.objects.Venue v) {
        var sb = new StringBuilder("[venue");
        if (v.getTitle() != null && !v.getTitle().isBlank()) sb.append(": ").append(v.getTitle().strip());
        if (v.getAddress() != null && !v.getAddress().isBlank()) sb.append(" — ").append(v.getAddress().strip());
        var loc = v.getLocation();
        if (loc != null) sb.append(" (%s, %s)".formatted(loc.getLatitude(), loc.getLongitude()));
        return sb.append(']').toString();
    }

    /**
     * JCLAW-366: build the "in reply to: …" supplemental context block, or null
     * when this message neither replies to another nor carries a native quote.
     * Prefers the native {@code quote} substring (the user explicitly selected
     * that span) over the full replied-to body. When neither text is available
     * but the replied-to message is media, notes the media type instead so the
     * agent still knows what was referenced.
     */
    private static String buildReplyContext(Message msg) {
        var quote = msg.getQuote();
        if (quote != null && quote.getText() != null && !quote.getText().isBlank()) {
            return "in reply to (quoted): " + quote.getText().strip();
        }
        var replyTo = msg.getReplyToMessage();
        if (replyTo == null) return null;
        String body = replyToText(replyTo);
        if (!body.isEmpty()) return "in reply to: " + body;
        String media = replyToMediaType(replyTo);
        if (media != null) return "in reply to: [" + media + "]";
        return null;
    }

    /** Text/caption of the replied-to message, trimmed; "" when it carries neither. */
    private static String replyToText(Message replyTo) {
        if (replyTo.hasText() && replyTo.getText() != null) return replyTo.getText().strip();
        if (replyTo.getCaption() != null && !replyTo.getCaption().isBlank()) return replyTo.getCaption().strip();
        return "";
    }

    /**
     * A short media-type label for a media-only replied-to message, or null when
     * it isn't one of the recognized media shapes. Used only when the replied-to
     * message has no text/caption of its own.
     */
    private static String replyToMediaType(Message replyTo) {
        if (replyTo.hasPhoto()) return "photo";
        if (replyTo.hasSticker()) return "sticker";
        if (replyTo.hasVoice()) return "voice";
        if (replyTo.hasAudio()) return "audio";
        if (replyTo.hasVideo()) return "video";
        if (replyTo.hasVideoNote()) return "video note";
        if (replyTo.hasDocument()) return "document";
        if (replyTo.getVenue() != null) return "venue";
        if (replyTo.hasLocation()) return "location";
        return null;
    }

    /**
     * JCLAW-109: parse an SDK {@link Update} for an inline-keyboard
     * callback query. Returns null when the update isn't a callback,
     * when the callback has no data field (Telegram theoretically allows
     * games without data — we don't use that), or when identity fields
     * required for authorization are missing. Separate entry point from
     * {@link #parseUpdate(Update)} so callers can cleanly distinguish
     * "text message arrived" from "keyboard tap arrived."
     */
    public static InboundCallback parseCallback(Update update) {
        if (update == null || update.getCallbackQuery() == null) return null;
        CallbackQuery cq = update.getCallbackQuery();
        if (cq.getData() == null || cq.getData().isBlank()) return null;
        if (cq.getFrom() == null) return null;

        String callbackId = cq.getId();
        String fromId = String.valueOf(cq.getFrom().getId());
        String chatId = null;
        String chatType = null;
        Integer messageId = null;
        var origin = cq.getMessage();
        if (origin != null) {
            messageId = origin.getMessageId();
            if (origin instanceof Message mm) {
                chatId = mm.getChatId() != null ? String.valueOf(mm.getChatId()) : null;
                chatType = mm.getChat() != null ? mm.getChat().getType() : null;
            }
        }
        return new InboundCallback(callbackId, chatId, chatType, fromId, messageId, cq.getData());
    }

    /** Parse a Gson {@link JsonObject} update (webhook payload) into an {@link InboundCallback}. */
    public static InboundCallback parseCallback(JsonObject update) {
        try {
            Update sdk = JACKSON.readValue(update.toString(), Update.class);
            return parseCallback(sdk);
        } catch (Exception e) {
            EventLogger.warn(LOG_CATEGORY, null, CHANNEL_NAME,
                    "Callback parse error: %s".formatted(e.getMessage()));
            return null;
        }
    }
}
