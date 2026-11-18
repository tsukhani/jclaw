import memory.TemporalExpressions;
import memory.ontology.EdtfInterval;
import org.jetbrains.jetCheck.Generator;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.time.LocalDate;

class TemporalExpressionsPropertyTest extends UnitTest {

    /** Anchors from April to January: neither the anchor nor any probe's offset reaches a leap day. */
    private static final Generator<LocalDate> SHIFTABLE_ANCHORS = Generator.zipWith(
            Generator.integers(1990, 2090), Generator.integers(0, 305), (y, d) -> LocalDate.of(y, 4, 1).plusDays(d));

    // 60 iterations: each runs the finder over every normalizer probe at two anchors, about 0.5 ms.
    @Test
    void aRelativeReadingMovesOneYearWithItsAnchor() {
        PropertyInputs.check(60, SHIFTABLE_ANCHORS, anchor -> {
            var later = anchor.plusYears(1);
            for (var probe : TemporalExpressions.NORMALIZER_PROBES) {
                var now = TemporalExpressions.find(probe, anchor).found();
                var next = TemporalExpressions.find(probe, later).found();
                assertEquals(now.size(), next.size(), () -> probe + " found differently at " + anchor + " and " + later);
                for (int i = 0; i < now.size(); i++) {
                    var a = now.get(i);
                    var b = next.get(i);
                    var shifted = a.relative() ? a.readings().stream().map(TemporalExpressionsPropertyTest::plusOneYear).toList()
                            : a.readings();
                    assertEquals(shifted, b.readings(), () -> probe + " at " + anchor + " read " + a.readings()
                            + ", at " + later + " read " + b.readings());
                    assertEquals(a.duration(), b.duration(), () -> probe + " duration moved between " + anchor
                            + " and " + later);
                }
            }
        });
    }

    private static EdtfInterval plusOneYear(EdtfInterval interval) {
        return switch (interval.start()) {
            case EdtfInterval.Point(var d) when interval.single() -> EdtfInterval.of(d.plusYears(1));
            default -> throw new AssertionError("a relative probe read as a range: " + interval);
        };
    }
}
