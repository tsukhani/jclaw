import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.ConfigService;
import services.video.VideoUrlAdapter;

/**
 * The native-video adapter inlines the whole clip as a {@code video_url} part and gates on a size cap
 * ({@code video.maxInlineMb}). Covers the cap arithmetic + boundary; the content-part shape itself
 * reads a file off disk and is exercised end-to-end by VideoUnderstandingDispatcherTest.
 */
class VideoUrlAdapterTest extends UnitTest {

    @BeforeEach
    void setUp() {
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
    }

    @Test
    void defaultInlineCapIs20Mb() {
        assertEquals(20L * 1024 * 1024, VideoUrlAdapter.maxInlineBytes());
    }

    @Test
    void inlineCapIsConfigurableAndClamped() {
        ConfigService.set("video.maxInlineMb", "8");
        assertEquals(8L * 1024 * 1024, VideoUrlAdapter.maxInlineBytes());
        ConfigService.set("video.maxInlineMb", "999"); // clamp to 100
        assertEquals(100L * 1024 * 1024, VideoUrlAdapter.maxInlineBytes());
        ConfigService.set("video.maxInlineMb", "0");   // clamp to 1
        assertEquals(1L * 1024 * 1024, VideoUrlAdapter.maxInlineBytes());
    }

    @Test
    void isWithinInlineCapHonorsSizeAndBoundary() {
        ConfigService.set("video.maxInlineMb", "1"); // 1 MiB cap
        assertTrue(VideoUrlAdapter.isWithinInlineCap(video(1024)));
        assertTrue(VideoUrlAdapter.isWithinInlineCap(video(1024 * 1024)));        // exactly at cap
        assertFalse(VideoUrlAdapter.isWithinInlineCap(video(1024 * 1024 + 1)));   // 1 byte over
        assertFalse(VideoUrlAdapter.isWithinInlineCap(video(0)));                 // unknown size
        assertFalse(VideoUrlAdapter.isWithinInlineCap(null));
    }

    private static models.MessageAttachment video(long sizeBytes) {
        var a = new models.MessageAttachment();
        a.kind = models.MessageAttachment.KIND_VIDEO;
        a.mimeType = "video/mp4";
        a.sizeBytes = sizeBytes;
        return a;
    }
}
