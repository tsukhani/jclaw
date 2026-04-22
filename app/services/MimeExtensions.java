package services;

import java.util.Map;

/**
 * Reverse lookup from a MIME type to a canonical file extension, delegating
 * to {@link play.libs.MimeTypes}. The data lives in Play's bundled
 * {@code mime-types.properties} plus any {@code mimetype.*} overrides
 * declared in {@code conf/application.conf} — extending the map for a new
 * format is a one-line config change.
 *
 * <p>Used in two places today:
 * <ul>
 *   <li>{@code ApiChatController.uploadChatFiles} picks a canonical
 *       extension when the uploader's sanitized filename has none</li>
 *   <li>{@code AgentRunner.userMessageFor} derives the OpenAI
 *       {@code input_audio.format} hint from the sniffed MIME stored on the
 *       attachment row</li>
 * </ul>
 */
public final class MimeExtensions {

    private MimeExtensions() {}

    /**
     * Tika sniffs use RFC-registered MIME strings that predate browser and
     * OS conventions (RFC 2361's {@code audio/vnd.wave} for WAV, the x-
     * prefixed legacy variants). Play's mime-types database speaks the
     * de-facto strings the web settled on ({@code audio/wav}, {@code audio/flac})
     * — so the reverse lookup needs a thin alias layer to bridge them. Kept
     * small and targeted: only entries that appear in real upload traffic
     * and would otherwise produce an empty format hint on the wire.
     */
    private static final Map<String, String> MIME_ALIASES = Map.of(
            "audio/vnd.wave", "audio/wav",
            "audio/x-wav", "audio/wav",
            "audio/wave", "audio/wav"
    );

    /**
     * Probe the candidate extensions and return the first whose forward
     * lookup via {@link play.libs.MimeTypes#getMimeType(String)} yields
     * {@code mime} (or its canonical alias). Returns the empty string when
     * no candidate matches.
     */
    public static String forMime(String mime, String[] candidates) {
        if (mime == null || mime.isEmpty() || candidates == null) return "";
        var normalized = MIME_ALIASES.getOrDefault(mime.toLowerCase(), mime);
        for (var ext : candidates) {
            var forward = play.libs.MimeTypes.getMimeType("probe." + ext, "");
            if (normalized.equalsIgnoreCase(forward)) return ext;
        }
        return "";
    }
}
