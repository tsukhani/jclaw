import memory.ontology.EdtfDate;
import memory.ontology.EdtfInterval;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.util.List;

/** JCLAW-1361: EDTF intervals over the date subset. */
class EdtfIntervalTest extends UnitTest {

    private static void assertRoundTrip(String edtf) {
        assertEquals(edtf, EdtfInterval.parse(edtf).toString(), edtf);
    }

    @Test
    void aSlashlessIntervalIsOneDate() {
        var single = EdtfInterval.parse("2026-06");
        assertTrue(single.single());
        assertEquals(new EdtfInterval.Point(EdtfDate.parse("2026-06")), single.start());
        assertEquals(single, EdtfInterval.of(EdtfDate.parse("2026-06")));
        assertRoundTrip("2026-06");
    }

    @Test
    void twoDatesRoundTrip() {
        assertRoundTrip("2019/2021");
        assertRoundTrip("2020-03/2021-06");
        assertRoundTrip("2026-21/2026-35~");
        assertRoundTrip("2019/2019");
    }

    @Test
    void eitherSideMayBeUnknown() {
        var since = EdtfInterval.parse("2019/");
        assertEquals(EdtfInterval.UNKNOWN, since.end());
        assertRoundTrip("2019/");
        assertRoundTrip("/2024-05");
        assertRoundTrip("2019/..");
    }

    @Test
    void theNeverScopeIsAnOpenStartToAnExactDay() {
        var never = EdtfInterval.parse("../2026-02-15");
        assertEquals(EdtfInterval.OPEN, never.start());
        assertEquals(new EdtfInterval.Point(EdtfDate.parse("2026-02-15")), never.end());
        assertRoundTrip("../2026-02-15");
    }

    @Test
    void anOpenStartToAnythingButAnExactDayIsRefused() {
        for (var refused : List.of("../2019", "../2026-02", "../2026-02-15~", "../2026-24", "../..", "../")) {
            assertThrows(IllegalArgumentException.class, () -> EdtfInterval.parse(refused), refused);
        }
    }

    @Test
    void malformedIntervalsAreRefused() {
        for (var refused : List.of("/", "2021/2019", "2019/2020/2021", "2019-XX/2020", "2019?/2020")) {
            assertThrows(IllegalArgumentException.class, () -> EdtfInterval.parse(refused), refused);
        }
    }
}
