package services.tts;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits reply text into speakable chunks for streaming synthesis (JCLAW-790):
 * the {@code /api/tts/stream} endpoint synthesizes and streams each chunk as it
 * is ready, so playback starts on the first sentence instead of after the whole
 * message. Chunking is sentence-level and engine-agnostic — it works the same
 * for the sidecar and the JVM engine (intra-sentence native streaming is a
 * later optimization).
 *
 * <p>Two guards keep chunks synthesis-friendly: fragments shorter than
 * {@link #MIN_CHARS} are merged forward (avoids choppy one-word clips and keeps
 * prosody), and any run longer than {@link #MAX_CHARS} is hard-wrapped at word
 * boundaries (so one run-on sentence can't stall time-to-first-audio). Paragraph
 * breaks are natural flush points.
 */
public final class TtsSentenceChunker {

    private TtsSentenceChunker() {}

    /** Below this a chunk isn't worth its own synth round-trip — merge forward. */
    public static final int MIN_CHARS = 60;
    /** Above this a chunk delays first audio too long — hard-wrap it. */
    public static final int MAX_CHARS = 320;

    /** Split after sentence-ending punctuation followed by whitespace. */
    private static final String SENTENCE_SPLIT = "(?<=[.!?…])\\s+";

    /**
     * Break {@code text} into speakable chunks in reading order. Returns an
     * empty list for null/blank input.
     */
    public static List<String> chunk(String text) {
        var out = new ArrayList<String>();
        if (text == null || text.isBlank()) return out;
        var buf = new StringBuilder();
        for (var paragraph : text.strip().split("\\n+")) {
            var para = paragraph.strip();
            if (para.isEmpty()) continue;
            for (var sentence : para.split(SENTENCE_SPLIT)) {
                var s = sentence.strip();
                if (s.isEmpty()) continue;
                if (s.length() > MAX_CHARS) {
                    flush(out, buf);
                    out.addAll(hardWrap(s));
                    continue;
                }
                if (buf.length() > 0) buf.append(' ');
                buf.append(s);
                if (buf.length() >= MIN_CHARS) flush(out, buf);
            }
            // A paragraph break is a natural pause — flush whatever's buffered.
            flush(out, buf);
        }
        flush(out, buf);
        return out;
    }

    /** Greedily pack words into &le; MAX_CHARS pieces (a single word longer than
     *  MAX still gets its own piece — we never split inside a word). */
    private static List<String> hardWrap(String s) {
        var pieces = new ArrayList<String>();
        var buf = new StringBuilder();
        for (var word : s.split("\\s+")) {
            if (buf.length() > 0 && buf.length() + 1 + word.length() > MAX_CHARS) {
                pieces.add(buf.toString());
                buf.setLength(0);
            }
            if (buf.length() > 0) buf.append(' ');
            buf.append(word);
        }
        if (buf.length() > 0) pieces.add(buf.toString());
        return pieces;
    }

    private static void flush(List<String> out, StringBuilder buf) {
        if (buf.length() > 0) {
            out.add(buf.toString());
            buf.setLength(0);
        }
    }
}
