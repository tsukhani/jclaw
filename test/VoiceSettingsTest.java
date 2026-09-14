import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.voice.VoiceSettings;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Write-time rules for the voice.* keys. Never writes a key, so the cross-key checks see the defaults. */
class VoiceSettingsTest extends UnitTest {

    @Test
    void aRunOnLimitBelowOneIsRefused() {
        // At 0 the run-on flush cuts nothing and loops for ever; a negative value throws.
        assertNotNull(VoiceSettings.rejectionFor(VoiceSettings.MAX_RUN_ON_CHARS, "0"));
        assertNull(VoiceSettings.rejectionFor(VoiceSettings.MAX_RUN_ON_CHARS, "1"));
    }

    @Test
    void baseSilenceCannotExceedTheMaximum() {
        var rejected = VoiceSettings.rejectionFor(VoiceSettings.BASE_SILENCE_MS, "1600");
        assertNotNull(rejected);
        assertTrue(rejected.contains("1500"), rejected);
        assertNull(VoiceSettings.rejectionFor(VoiceSettings.BASE_SILENCE_MS, "1500"));
    }

    @Test
    void maxSilenceCannotFallBelowTheBase() {
        assertNotNull(VoiceSettings.rejectionFor(VoiceSettings.MAX_SILENCE_MS, "499"));
        assertNull(VoiceSettings.rejectionFor(VoiceSettings.MAX_SILENCE_MS, "500"));
    }

    @Test
    void zeroIsLegitimateForTheMinimumUtteranceAndTranscriptInterval() {
        for (var key : List.of(VoiceSettings.MIN_UTTERANCE_MS, VoiceSettings.PARTIALS_INTERVAL_MS)) {
            assertNull(VoiceSettings.rejectionFor(key, "0"), key);
            assertNotNull(VoiceSettings.rejectionFor(key, "-1"), key);
        }
    }

    @Test
    void togglesTakeOnlyTrueOrFalse() {
        for (var key : List.of(VoiceSettings.SEMANTIC_HOLD, VoiceSettings.PARTIALS_ENABLED)) {
            assertNull(VoiceSettings.rejectionFor(key, "FALSE"), key);
            assertNotNull(VoiceSettings.rejectionFor(key, "off"), key);
        }
    }
}
