import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.grapheval.Certifier;
import services.grapheval.ResolutionCalibration;
import services.grapheval.ResolutionCalibration.Counts;

import java.util.List;
import java.util.Map;

/** JCLAW-1370: the resolution threshold walk, strictest first, stopping at the first failing bound. */
class ResolutionCalibrationTest extends UnitTest {

    @Test
    void theWalkPicksTheLowestThresholdOfThePassingRunFromTheStrictestDown() {
        var counts = Map.of(0.99, new Counts(60, 0), 0.97, new Counts(93, 1), 0.95, new Counts(124, 2),
                0.93, new Counts(130, 6), 0.91, new Counts(400, 1));
        var walk = ResolutionCalibration.walk(List.of(0.91, 0.95, 0.99, 0.93, 0.97), counts::get);
        assertEquals(0.95, walk.threshold());
        assertEquals(List.of(0.99, 0.97, 0.95, 0.93), walk.steps().stream().map(ResolutionCalibration.Step::t).toList(),
                "stops at the first failure, so 0.91 is never evaluated");
        var failed = walk.steps().getLast();
        assertFalse(failed.passes());
        assertEquals(Certifier.upperBound(6, 130), failed.bound());
        assertEquals(6 / 130.0, failed.rate());
        assertEquals(new Counts(124, 2).attachments(), walk.at(0.95).attachments());
    }

    @Test
    void fewerThanFiftyNineAttachmentsPassNothing() {
        var under = ResolutionCalibration.walk(List.of(0.99, 0.95), _ -> new Counts(58, 0));
        assertNull(under.threshold());
        assertEquals(1, under.steps().size());
        assertFalse(under.steps().getFirst().passes());
        var enough = ResolutionCalibration.walk(List.of(0.99, 0.95), _ -> new Counts(59, 0));
        assertEquals(0.95, enough.threshold());
        var none = ResolutionCalibration.walk(List.of(0.9), _ -> new Counts(0, 0));
        assertNull(none.threshold());
        assertNull(none.steps().getFirst().rate());
    }
}
