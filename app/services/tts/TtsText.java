package services.tts;

/**
 * Reduces agent-reply markdown to plain, speakable text before TTS — drops code
 * blocks, table syntax (separator rows + cell pipes), link URLs, list bullets,
 * and stray emphasis/heading markers so the engine speaks words, not "pipe pipe
 * dash". JCLAW-791: voice mode synthesized the reply RAW, so a markdown table
 * (e.g. a weather forecast) produced no usable audio and stalled the turn.
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
        s = s.replaceAll("\\s+", " ").strip();
        return s;
    }
}
