import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.ConfigService;
import tools.DiarizeAudioTool;

/**
 * The audio-capability guard (JCLAW-654): a configured diarization model
 * that the provider registry explicitly tags supportsAudio=false must be
 * rejected actionably BEFORE any model call or attachment resolution.
 * Regression test for the S2159 dead-guard bug (Boolean.FALSE compared
 * against the AudioCapability enum — never fired).
 */
class DiarizeAudioToolGuardTest extends UnitTest {

    @Test
    void nonAudioModelIsRejectedBeforeAnyCall() {
        ConfigService.set("transcription.diarization.provider", "guardtest");
        ConfigService.set("transcription.diarization.model", "text-only-model");
        ConfigService.set("provider.guardtest.baseUrl", "http://127.0.0.1:1");
        ConfigService.set("provider.guardtest.models",
                "[{\"id\":\"text-only-model\",\"supportsAudio\":false}]");
        try {
            var result = new DiarizeAudioTool().execute("{}", null);
            assertTrue(result.contains("not audio-capable"),
                    "guard must fire for a supportsAudio=false model: " + result);
        } finally {
            ConfigService.set("transcription.diarization.provider", "");
            ConfigService.set("transcription.diarization.model", "");
        }
    }

    @Test
    void capabilityReadsRegistryTriState() {
        ConfigService.set("provider.guardtest.models",
                "[{\"id\":\"hears\",\"supportsAudio\":true},{\"id\":\"deaf\",\"supportsAudio\":false}]");
        assertEquals(DiarizeAudioTool.AudioCapability.AUDIO,
                DiarizeAudioTool.audioCapability("guardtest", "hears"));
        assertEquals(DiarizeAudioTool.AudioCapability.NOT_AUDIO,
                DiarizeAudioTool.audioCapability("guardtest", "deaf"));
        assertEquals(DiarizeAudioTool.AudioCapability.UNKNOWN,
                DiarizeAudioTool.audioCapability("guardtest", "unlisted-model"));
        assertEquals(DiarizeAudioTool.AudioCapability.UNKNOWN,
                DiarizeAudioTool.audioCapability("no-such-provider", "any"));
    }
}
