import memory.AbsoluteDates;
import memory.TemporalExpressions;
import org.jetbrains.jetCheck.Generator;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;

class AbsoluteDatesPropertyTest extends UnitTest {

    /** A sentence holding one in-set relative phrase at {@code [phraseStart, phraseEnd)}, maybe one out-of-set phrase. */
    record DatedSentence(String text, int phraseStart, int phraseEnd) {}

    record ReadBack(DatedSentence sentence, LocalDate writtenAt, LocalDate readAt) {}

    record Rewrite(DatedSentence sentence, LocalDate anchor) {}

    private static final List<String> TICKET_PREPOSITIONS = List.of("in", "on", "at", "by", "since", "until", "from",
            "before", "after", "during", "early", "late", "mid");

    private static final List<String> OUT_OF_SET = List.of("two years ago", "this spring", "every June", "in March",
            "on 12 December", "last week", "tonight", "recently", "on Monday", "next Jan", "for three years");

    private static final Generator<DatedSentence> DATED_SENTENCES = datedSentences();

    /** 1990-2090, one anchor in five a leap day. */
    private static final Generator<LocalDate> CENTURY_ANCHORS = centuryAnchors();

    private static Generator<DatedSentence> datedSentences() {
        var units = Stream.concat(Stream.of("year", "month", "quarter"), TemporalExpressions.MONTH_NAMES.stream()
                .map(m -> Character.toUpperCase(m.charAt(0)) + m.substring(1))).toList();
        var deictic = Generator.zipWith(Generator.sampledFrom("this", "last", "next"), Generator.sampledFrom(units),
                (w, u) -> w + " " + u);
        var phrase = Generator.anyOf(List.of(Generator.sampledFrom("today", "yesterday", "tomorrow"), deictic));
        // An empty string stands for no preposition (three in ten) and for no out-of-set phrase (one in two).
        var preposition = Generator.frequency(3, Generator.constant(""), 7, Generator.sampledFrom(TICKET_PREPOSITIONS));
        var extra = Generator.frequency(1, Generator.constant(""), 1, Generator.sampledFrom(OUT_OF_SET));
        return Generator.from(env -> {
            boolean atEnd = env.generate(Generator.booleans());
            String prep = env.generate(preposition);
            String ph = env.generate(phrase);
            boolean upper = env.generate(Generator.booleans());
            String other = env.generate(extra);
            var x = prep.isEmpty() ? ph : prep + " " + ph;
            if (upper) x = Character.toUpperCase(x.charAt(0)) + x.substring(1);
            var tail = other.isEmpty() ? "." : " and " + other + ".";
            var lead = atEnd ? "The user moved to Porto " : "";
            var text = atEnd ? lead + x + tail : x + " the user joined Vela" + tail;
            int end = lead.length() + x.length();
            return new DatedSentence(text, end - ph.length(), end);
        });
    }

    private static Generator<LocalDate> centuryAnchors() {
        var first = LocalDate.of(1990, 1, 1);
        var any = Generator.integers(0, (int) (LocalDate.of(2091, 1, 1).toEpochDay() - first.toEpochDay() - 1))
                .map(first::plusDays);
        var leap = Generator.integers(0, 24).map(i -> LocalDate.of(1992 + 4 * i, 2, 29));
        return Generator.frequency(4, any, 1, leap);
    }

    // 150 iterations: four finder runs each, plus class load and JIT warmup.
    @Test
    void anAbsoluteDateReadsBackToTheRelativeValueAtAnyOtherAnchor() {
        var inputs = Generator.from(env -> new ReadBack(env.generate(DATED_SENTENCES), env.generate(CENTURY_ANCHORS),
                env.generate(CENTURY_ANCHORS)));
        PropertyInputs.check(150, inputs, input -> {
            var s = input.sentence();
            var a = input.writtenAt();
            var b = input.readAt();
            var shown = PropertyInputs.quote(s.text()) + " @" + a;
            var original = TemporalExpressions.find(s.text(), a).found().stream()
                    .filter(d -> d.start() == s.phraseStart() && d.end() == s.phraseEnd()).toList();
            assertEquals(1, original.size(), () -> shown + " found " + original);
            var rewritten = AbsoluteDates.rewrite(s.text(), a);
            var suffix = s.text().substring(s.phraseEnd());
            var prefix = s.text().substring(0, s.phraseStart());
            assertTrue(rewritten.endsWith(suffix) && rewritten.length() > suffix.length(),
                    () -> shown + " became " + PropertyInputs.quote(rewritten));
            int formEnd = rewritten.length() - suffix.length();
            var read = TemporalExpressions.find(rewritten, b).found().stream().filter(d -> d.end() == formEnd).toList();
            assertEquals(1, read.size(), () -> shown + " became " + PropertyInputs.quote(rewritten) + ", read @" + b
                    + " as " + TemporalExpressions.find(rewritten, b).found());
            assertFalse(read.getFirst().relative(), () -> PropertyInputs.quote(rewritten) + " still relative @" + b);
            assertEquals(original.getFirst().readings(), read.getFirst().readings(),
                    () -> shown + " became " + PropertyInputs.quote(rewritten) + ", read @" + b);
            assertTrue(rewritten.startsWith(prefix),
                    () -> PropertyInputs.quote(s.text()) + " lost its prefix in " + PropertyInputs.quote(rewritten));
        });
    }

    // 300 iterations: two rewrites each.
    @Test
    void rewritingTwiceIsRewritingOnce() {
        var inputs = Generator.from(env -> new Rewrite(env.generate(DATED_SENTENCES), env.generate(CENTURY_ANCHORS)));
        PropertyInputs.check(300, inputs, input -> {
            var once = AbsoluteDates.rewrite(input.sentence().text(), input.anchor());
            assertEquals(once, AbsoluteDates.rewrite(once, input.anchor()),
                    () -> PropertyInputs.quote(input.sentence().text()) + " @" + input.anchor());
        });
    }
}
