import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.UvProbe;
import services.transcription.AsrModel;
import services.transcription.AsrModelStore;
import services.transcription.ModelPrefetchStore.State;
import utils.HttpFactories;

/**
 * Guards the internal-state → wire-status projection the Settings frontend
 * consumes. JCLAW-650 renamed the internal enum (ABSENT→NOT_DOWNLOADED,
 * AVAILABLE→DOWNLOADED) but left {@code ApiTranscriptionController} emitting the
 * raw {@code name()}, so the Download button and Ready badge — which key off the
 * old ABSENT/AVAILABLE vocabulary — stopped rendering. {@link State#wireName()}
 * restores the contract; this pins it. Keep in lockstep with the
 * {@code TranscriptionModelStatus} union in SettingsTranscriptionPanel.vue.
 */
class AsrModelStoreWireNameTest extends UnitTest {

    @Test
    void projectsInternalStatesToFrontendVocabulary() {
        assertEquals("ABSENT", State.NOT_DOWNLOADED.wireName());
        assertEquals("AVAILABLE", State.DOWNLOADED.wireName());
        assertEquals("DOWNLOADING", State.DOWNLOADING.wireName());
        assertEquals("ERROR", State.ERROR.wireName());
        assertEquals("UNAVAILABLE", State.UNAVAILABLE.wireName());
    }

    @Test
    void everyStateHasANonBlankWireName() {
        for (var s : State.values()) {
            assertNotNull(s.wireName(), "wireName for " + s);
            assertFalse(s.wireName().isBlank(), "wireName blank for " + s);
        }
    }

    /**
     * A sidecar reply that omits a model id used to leave {@code statusAll()} without an
     * entry for it, and the Settings page reads one row per {@link AsrModel} — so the whole
     * Transcription panel 500'd instead of showing that one model as unavailable. The
     * realistic trigger is version skew: a model added here before the sidecar knows it.
     *
     * <p>Drives the real sidecar call through the {@code HttpFactories} transport override
     * (JCLAW-1151) rather than a mock server, so the parse path under test is the production one.
     */
    @Test
    void aModelTheSidecarOmitsIsReportedUnavailableRatherThanDropped() {
        var known = AsrModel.values()[0];
        var omitted = AsrModel.values()[1];
        // The sidecar's real row shape (see ModelPrefetchStore.rowFor): a wrong fixture
        // throws into statusAll's catch and every id comes back unavailable, which would
        // pass a weaker assertion for the wrong reason.
        var reply = """
                {"status":{"%s":{"cached":true,"bytesOnDisk":1024}}}
                """.formatted(known.id());

        var previousProbe = UvProbe.lastResult();
        UvProbe.setForTest(new UvProbe.ProbeResult(true, "forced available in test"));
        try {
            var statuses = HttpFactories.callWith(cannedClient(reply), AsrModelStore::statusAll);

            for (var m : AsrModel.values()) {
                assertNotNull(statuses.get(m.id()),
                        "statusAll must cover every AsrModel; " + m.id() + " was dropped");
            }
            assertEquals(State.DOWNLOADED, statuses.get(known.id()).state(),
                    "the model the sidecar did report must parse normally, not fall into the catch");

            var missing = statuses.get(omitted.id());
            assertEquals(State.UNAVAILABLE, missing.state());
            assertTrue(missing.error() != null && missing.error().contains(omitted.id()),
                    "the row must name the id the sidecar said nothing about, got: " + missing.error());
        } finally {
            UvProbe.setForTest(previousProbe);
        }
    }

    private static OkHttpClient cannedClient(String body) {
        Interceptor canned = chain -> new Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("canned")
                .body(ResponseBody.create(body, null))
                .build();
        return new OkHttpClient.Builder().addInterceptor(canned).build();
    }

}
