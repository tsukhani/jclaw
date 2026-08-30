package utils;

import org.jspecify.annotations.NonNull;

/**
 * How an {@link ErrorTemplate} is turned into text (JCLAW-1130).
 *
 * <p>Two modes, because the surfaces divide two ways and not more: destinations that render
 * markup ({@link #RICH} — the web chat, Slack, the admin UI) and destinations that do not
 * ({@link #PLAIN} — Telegram, WhatsApp, the console at boot, a tool result read by a model).
 *
 * <p>An enum rather than an interface with two implementations: the set is closed, both cases
 * are pure functions of the template, and a caller picking a mode should not be able to supply
 * a third. Per-channel quirks belong in the channel senders, not here — this only decides
 * whether the section labels carry markup.
 */
public enum ErrorRendering {

    /** Markdown emphasis on the section labels. Safe for the web chat, Slack and the admin UI. */
    RICH("**What broke** — ", "**What to check** — ", "**How to retry** — "),

    /**
     * No markup at all. Telegram and WhatsApp reject or mangle stray markup, and an error
     * message that fails to send because of its own formatting is the worst outcome on this
     * path, so this mode emits none — not even characters that are inert in most parsers.
     */
    PLAIN("What broke: ", "What to check: ", "How to retry: ");

    private final String brokeLabel;
    private final String checkLabel;
    private final String retryLabel;

    ErrorRendering(String brokeLabel, String checkLabel, String retryLabel) {
        this.brokeLabel = brokeLabel;
        this.checkLabel = checkLabel;
        this.retryLabel = retryLabel;
    }

    /**
     * Render the template's sections, separated by blank lines. A template with no retry path
     * emits two sections rather than an empty third — in both modes, so no caller has to know
     * which mode it is in to know how many sections it gets.
     */
    public String render(@NonNull ErrorTemplate template) {
        var out = new StringBuilder()
                .append(brokeLabel).append(template.whatBroke())
                .append("\n\n")
                .append(checkLabel).append(template.whatToCheck());
        if (template.hasRetry()) {
            out.append("\n\n").append(retryLabel).append(template.howToRetry());
        }
        return out.toString();
    }
}
