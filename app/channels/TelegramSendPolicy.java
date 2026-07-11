package channels;

import models.TelegramBinding;
import org.telegram.telegrambots.meta.api.objects.LinkPreviewOptions;
import org.telegram.telegrambots.meta.api.objects.ReplyParameters;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import play.Play;

/**
 * Config- and binding-driven send-policy resolver for the Telegram outbound
 * path (JCLAW-702, extracted from {@code TelegramSender}). Answers the "how
 * should this send behave?" questions — reply-targeting mode, link-preview
 * suppression, forum-topic stripping, and the HTML-parse-error retry gate —
 * so {@link TelegramSender} can stay a focused send engine. Pure policy: no
 * per-instance state, no I/O; every method reads {@link play.Play#configuration}
 * (and, for the effective mode, the per-binding override) and returns a
 * decision.
 */
final class TelegramSendPolicy {

    private TelegramSendPolicy() { }

    // ── JCLAW-369: outbound reply targeting + topic-aware sends ──────────

    /**
     * JCLAW-369: reply-targeting policy. Controls whether — and how often —
     * an inbound {@code replyToMessageId} is applied to the turn's outbound
     * messages, read from {@code telegram.replyTo.mode} via
     * {@link play.Play#configuration} (default {@link #REPLY_MODE_FIRST} when
     * unset/blank/unrecognized):
     *
     * <ul>
     *   <li>{@link #REPLY_MODE_OFF} — never set {@code reply_parameters};</li>
     *   <li>{@link #REPLY_MODE_FIRST} — set it on only the first chunk/message
     *       of a turn (the natural "this is my answer to that" affordance
     *       without spamming a reply badge on every chunk);</li>
     *   <li>{@link #REPLY_MODE_ALL} — set it on every chunk/message.</li>
     * </ul>
     */
    private static final String CFG_REPLY_TO_MODE = "telegram.replyTo.mode";
    static final String REPLY_MODE_OFF = "off";
    static final String REPLY_MODE_FIRST = "first";
    static final String REPLY_MODE_ALL = "all";

    /**
     * The Telegram "General" forum topic always has {@code message_thread_id == 1},
     * and the Bot API rejects a send that names it explicitly — so JCLAW-369
     * OMITS {@code message_thread_id} on sends to General (a bare send already
     * lands there). Typing actions, by contrast, accept it.
     */
    static final int GENERAL_TOPIC_THREAD_ID = 1;

    // ── JCLAW-359: link-preview suppression ──────────────────────────────

    /**
     * JCLAW-359: link-preview policy, read from {@code telegram.linkPreview} via
     * {@link play.Play#configuration}. Telegram auto-renders a preview card for
     * the first URL in a message; some operators want that off so a chat full of
     * agent-cited links stays compact. The flag is a coarse on/off:
     *
     * <ul>
     *   <li>{@code on} (default; also any unset/blank/unrecognized value) — leave
     *       {@code link_preview_options} unset, preserving Telegram's default
     *       preview-on behavior;</li>
     *   <li>{@code off} — attach {@code LinkPreviewOptions(is_disabled=true)} to
     *       every text send so no preview card is generated.</li>
     * </ul>
     */
    private static final String CFG_LINK_PREVIEW = "telegram.linkPreview";
    static final String LINK_PREVIEW_ON = "on";
    static final String LINK_PREVIEW_OFF = "off";

    /**
     * JCLAW-359: marker substring identifying a Telegram "can't parse entities"
     * rejection. Telegram returns it inside a 400 {@code Bad Request} description
     * (e.g. {@code Bad Request: can't parse entities: Unsupported start tag ...})
     * when the HTML payload is malformed — typically a revoked / mangled entity
     * the markdown formatter emitted. Matched case-insensitively so a wording
     * tweak in the apostrophe / casing doesn't slip the detection.
     */
    private static final String PARSE_ENTITIES_MARKER = "can't parse entities";

    /**
     * JCLAW-359: true when link previews should be suppressed on outbound text
     * sends ({@code telegram.linkPreview = off}). Public so default-package tests
     * can assert the config-read contract, mirroring {@link #replyToMode()}.
     */
    public static boolean suppressLinkPreview() {
        var raw = Play.configuration.getProperty(CFG_LINK_PREVIEW, LINK_PREVIEW_ON);
        return raw != null && raw.trim().equalsIgnoreCase(LINK_PREVIEW_OFF);
    }

    /**
     * JCLAW-359: the {@link LinkPreviewOptions}
     * to attach to a text send, or null to leave Telegram's default preview-on
     * behavior. Returns a disabled-preview options object only when
     * {@link #suppressLinkPreview()} is true.
     */
    static LinkPreviewOptions linkPreviewOptions() {
        if (!suppressLinkPreview()) return null;
        return LinkPreviewOptions.builder()
                .isDisabled(true)
                .build();
    }

    /**
     * JCLAW-359: true when {@code e} is a 400 "can't parse entities" rejection —
     * the HTML payload was malformed and the SAME send should be retried once as
     * plain text. Distinct from the 429 rate-limit path (handled separately) and
     * from other 400s (genuine bad requests that a plain-text retry can't fix).
     */
    static boolean isParseEntitiesError(TelegramApiRequestException e) {
        Integer code = e.getErrorCode();
        if (code == null || code != 400) return false;
        var desc = e.getApiResponse();
        return desc != null && desc.toLowerCase().contains(PARSE_ENTITIES_MARKER);
    }

    /**
     * Resolve the configured reply mode, normalizing unknown/blank values to
     * {@link #REPLY_MODE_FIRST}. Public so default-package tests can assert the
     * config-read contract (matches the {@code *ForTest} convention used
     * elsewhere in this class for test-reachable surface).
     */
    public static String replyToMode() {
        var raw = Play.configuration.getProperty(CFG_REPLY_TO_MODE, REPLY_MODE_FIRST);
        return normalizeReplyMode(raw, REPLY_MODE_FIRST);
    }

    /** Normalize a raw reply-mode value (config or per-binding override) to a
     *  known constant, falling back to {@code fallback} on null/blank/unknown. */
    private static String normalizeReplyMode(String raw, String fallback) {
        if (raw == null) return fallback;
        var v = raw.trim().toLowerCase();
        return switch (v) {
            case REPLY_MODE_OFF, REPLY_MODE_FIRST, REPLY_MODE_ALL -> v;
            default -> fallback;
        };
    }

    /**
     * JCLAW-378: the effective reply mode for a bot token: the per-binding
     * {@link models.TelegramBinding#replyToMode} override when set (and valid),
     * otherwise the JVM-wide {@link #replyToMode()} config default. A blank /
     * unrecognized override is ignored and the config default applies. Public so
     * default-package tests can assert the override-wins / null-falls-back
     * contract, matching the {@code *ForTest} convention.
     */
    public static String effectiveReplyToMode(String botToken) {
        var override = TelegramBinding.overridesForToken(botToken).replyToMode();
        if (override == null || override.isBlank()) return replyToMode();
        return normalizeReplyMode(override, replyToMode());
    }

    /**
     * Build the {@link ReplyParameters} to apply on a given outbound chunk, or
     * null when none should be set. Honors the {@link #replyToMode()} policy:
     * {@code off} → never; {@code first} → only when {@code firstChunk}; {@code all}
     * → always (given a non-null target). {@code allow_sending_without_reply=true}
     * so a since-deleted target degrades to a plain send instead of a 400.
     */
    static ReplyParameters replyParamsFor(Integer replyToMessageId, boolean firstChunk, String mode) {
        if (replyToMessageId == null) return null;
        boolean apply = switch (mode) {
            case REPLY_MODE_ALL -> true;
            case REPLY_MODE_FIRST -> firstChunk;
            default -> false; // off
        };
        if (!apply) return null;
        return ReplyParameters.builder()
                .messageId(replyToMessageId)
                .allowSendingWithoutReply(true)
                .build();
    }

    /**
     * The {@code message_thread_id} to set on an outbound send, or null to omit
     * it. Returns null when {@code messageThreadId} is null or names the General
     * topic ({@link #GENERAL_TOPIC_THREAD_ID}) — a bare send already lands in
     * General, and naming it explicitly is rejected by the Bot API.
     */
    static Integer sendThreadId(Integer messageThreadId) {
        if (messageThreadId == null || messageThreadId == GENERAL_TOPIC_THREAD_ID) return null;
        return messageThreadId;
    }

    /**
     * JCLAW-369: package-private bridge for {@link TelegramStreamingSink}. The
     * streaming placeholder is the turn's first (and only live) message, so the
     * sink always evaluates the reply policy as the first chunk. Returns null
     * when no badge should be applied ({@code off}, or a null target).
     */
    static ReplyParameters replyParamsForSink(String botToken, Integer replyToMessageId) {
        return replyParamsFor(replyToMessageId, true, effectiveReplyToMode(botToken));
    }

    /** JCLAW-369: package-private bridge so the sink shares the General-topic strip rule. */
    static Integer sendThreadIdForSink(Integer messageThreadId) {
        return sendThreadId(messageThreadId);
    }
}
