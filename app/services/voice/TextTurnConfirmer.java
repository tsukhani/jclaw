package services.voice;

import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Text-heuristic turn-completeness confirmer (JCLAW-845) — the cheapest fill for
 * the {@link TurnEndpointer.Confirmer} seam. It reads the latest interim
 * transcript (JCLAW-798) and holds the turn open (toward {@code maxSilenceMs})
 * when the utterance clearly ends mid-clause, so a natural mid-sentence pause
 * isn't cut off; otherwise it lets the turn close at the fast {@code
 * baseSilenceMs}.
 *
 * <p>The rule, deliberately simple and biased toward <em>holding</em>: a turn
 * "looks complete" unless its last word is a function word that rarely ends a
 * finished thought (a conjunction, article/determiner, preposition, or filler),
 * or it ends in sentence punctuation. Biasing toward holding is safe — a false
 * hold only waits up to {@code maxSilenceMs} before endpointing anyway, whereas
 * a false cut fragments the utterance (the failure this story targets).
 *
 * <p>When there is no transcript (audio-native models don't run interim STT, so
 * the supplier returns {@code null}), it returns complete — i.e. it degrades to
 * fixed-silence endpointing, matching {@link TurnEndpointer#ALWAYS_COMPLETE}.
 *
 * <p>{@link #looksComplete(String)} is pure and unit-tested; the instance form
 * pulls the live transcript from a supplier so the endpointer stays decoupled
 * from how the transcript is produced.
 */
public final class TextTurnConfirmer implements TurnEndpointer.Confirmer {

    // Function words that rarely end a finished utterance. wh-words (why/where/…)
    // and auxiliaries (is/will/…) are intentionally excluded — they frequently
    // end complete questions/answers, so holding on them would over-hold.
    private static final Set<String> HOLD_WORDS = Set.of(
            // conjunctions
            "and", "but", "or", "nor", "so", "yet", "for", "because", "if", "when",
            "while", "since", "although", "though", "unless", "until", "as", "that",
            // articles / determiners
            "a", "an", "the", "my", "your", "his", "her", "our", "their", "its",
            // prepositions
            "to", "of", "in", "on", "at", "by", "with", "from", "about", "into",
            "onto", "over", "under", "between", "through",
            // fillers
            "um", "uh", "er", "erm", "hmm");

    private final Supplier<String> latestTranscript;

    public TextTurnConfirmer(Supplier<String> latestTranscript) {
        this.latestTranscript = latestTranscript;
    }

    @Override
    public boolean looksComplete() {
        return looksComplete(latestTranscript.get());
    }

    /** Whether {@code transcript} reads as a finished thought (see class doc). */
    public static boolean looksComplete(String transcript) {
        if (transcript == null) return true;
        var t = transcript.strip();
        if (t.isEmpty()) return true;

        char last = t.charAt(t.length() - 1);
        if (last == '.' || last == '!' || last == '?') return true;

        // Extract the trailing word (letters/apostrophes), skipping trailing
        // punctuation like a comma, then test it against the hold set.
        int end = t.length();
        while (end > 0 && !isWordChar(t.charAt(end - 1))) end--;
        int start = end;
        while (start > 0 && isWordChar(t.charAt(start - 1))) start--;
        if (start >= end) return true; // no trailing word

        var lastWord = t.substring(start, end).toLowerCase(Locale.ROOT);
        return !HOLD_WORDS.contains(lastWord);
    }

    private static boolean isWordChar(char c) {
        return Character.isLetter(c) || c == '\'';
    }
}
