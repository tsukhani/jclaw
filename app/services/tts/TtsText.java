package services.tts;

/**
 * Reduces agent-reply markdown to plain, speakable text before TTS — drops code
 * blocks, table syntax (separator rows + cell pipes), link URLs, list bullets,
 * stray emphasis/heading markers, and emoji so the engine speaks words, not
 * "pipe pipe dash" or "lotus". JCLAW-791: voice mode synthesized the reply RAW,
 * so a markdown table (e.g. a weather forecast) produced no usable audio and
 * stalled the turn.
 *
 * <p>Deliberately lossy — good-enough prosody, not a full markdown renderer.
 * Applied server-side so every TTS entry point (read-aloud + voice mode) gets
 * clean text regardless of source.
 */
public final class TtsText {

    private TtsText() {}

    public static String toSpeakable(String md) {
        if (md == null) return "";
        var s = md;
        s = s.replaceAll("```[\\s\\S]*?```", " ");                          // fenced code blocks
        s = s.replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", " ");                  // images
        s = s.replaceAll("\\[([^\\]]+)\\]\\([^)]*\\)", "$1");                // links -> link text
        s = s.replaceAll("(?m)^[ \\t]*\\|?[ \\t:|+-]+\\|?[ \\t]*$", " ");    // table separator / empty rows / hr
        s = s.replaceAll("(?m)^[ \\t]{0,3}[-+*]\\s+", "");                   // list bullets
        s = s.replace("`", "");                                              // inline code ticks
        s = s.replace("|", ", ");                                           // table cell separators -> pauses
        s = s.replaceAll("[*_~>#]", "");                                     // emphasis / heading / quote markers
        // Emoji & pictographs must not be spoken. Drop the joiners/variation
        // selectors/keycap combiners first, then every "Symbol, other" code
        // point (the Unicode category nearly all emoji live in, including the
        // astral 1F300+ planes). Leaves letters, digits, currency, and math
        // symbols intact.
        s = s.replaceAll("[\\x{FE00}-\\x{FE0F}\\x{200D}\\x{20E3}]", "");
        s = s.replaceAll("\\p{So}", "");
        s = s.replaceAll("\\s+", " ").strip();
        return s;
    }
}
