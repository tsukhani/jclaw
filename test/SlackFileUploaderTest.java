import channels.SlackFileUploader;
import channels.SlackFileUploader.UploadUrl;
import channels.SlackFileUploader.Uploader;
import org.junit.jupiter.api.*;
import play.test.UnitTest;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for {@link SlackFileUploader} (JCLAW-345). Exercises the 3-step
 * external-upload orchestration (getUploadURLExternal → POST bytes →
 * completeUploadExternal), the {@code U}-user → DM-channel resolution + cache, and
 * the short-circuits — all against an injected {@link Uploader} (swapped via the
 * package-private {@code IMPL} seam by reflection, mirroring
 * {@code SlackFileDownloaderTest}) so nothing hits the network.
 */
class SlackFileUploaderTest extends UnitTest {

    private static final Field IMPL_FIELD;
    static {
        try {
            IMPL_FIELD = SlackFileUploader.class.getDeclaredField("IMPL");
            IMPL_FIELD.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    static final class FakeUploader implements Uploader {
        final List<String> openDmFor = new ArrayList<>();
        final List<String> getUploadFor = new ArrayList<>(); // filenames
        final List<String> postedTo = new ArrayList<>();      // upload urls
        final List<String> completedTo = new ArrayList<>();   // channel ids
        String dmChannel = "D123";
        UploadUrl uploadUrl = new UploadUrl("https://files.slack.com/upload/v1/ABC", "F1");
        boolean postOk = true;
        boolean completeOk = true;

        @Override public UploadUrl getUploadUrl(String botToken, String filename, long length) {
            getUploadFor.add(filename);
            return uploadUrl;
        }
        @Override public boolean postBytes(String url, File file, String contentType) {
            postedTo.add(url);
            return postOk;
        }
        @Override public boolean completeUpload(String botToken, String fileId, String title,
                                                String channelId, String comment, String threadTs) {
            completedTo.add(channelId);
            return completeOk;
        }
        @Override public String openDm(String botToken, String userId) {
            openDmFor.add(userId);
            return dmChannel;
        }
    }

    private Object originalImpl;
    private FakeUploader fake;
    private File tmp;

    @BeforeEach
    void setup() throws Exception {
        originalImpl = IMPL_FIELD.get(null);
        fake = new FakeUploader();
        IMPL_FIELD.set(null, fake);
        tmp = Files.createTempFile("sk-up", ".png").toFile();
        Files.writeString(tmp.toPath(), "fake-bytes");
    }

    @AfterEach
    void teardown() throws Exception {
        IMPL_FIELD.set(null, originalImpl);
        if (tmp != null) {
            Files.deleteIfExists(tmp.toPath());
        }
    }

    @Test
    void channelTargetUploadsWithoutOpeningDm() {
        var ok = SlackFileUploader.upload("xoxb-t", "C123", "1700.0", tmp, "chart.png", "the chart");
        assertTrue(ok, "3-step upload to a channel must succeed");
        assertTrue(fake.openDmFor.isEmpty(), "a C-channel target must not open a DM");
        assertEquals(List.of("chart.png"), fake.getUploadFor);
        assertEquals(List.of("https://files.slack.com/upload/v1/ABC"), fake.postedTo);
        assertEquals(List.of("C123"), fake.completedTo);
    }

    @Test
    void userTargetResolvesToDmChannel() {
        var ok = SlackFileUploader.upload("xoxb-t", "U999", null, tmp, "x.png", null);
        assertTrue(ok);
        assertEquals(List.of("U999"), fake.openDmFor, "a U-user target opens a DM first");
        assertEquals(List.of("D123"), fake.completedTo, "upload shares to the resolved DM channel");
    }

    @Test
    void dmChannelCachedPerToken() {
        SlackFileUploader.upload("xoxb-cache", "U777", null, tmp, "a.png", null);
        SlackFileUploader.upload("xoxb-cache", "U777", null, tmp, "b.png", null);
        assertEquals(1, fake.openDmFor.size(), "conversations.open is called once for a cached (token, user)");
    }

    @Test
    void getUploadUrlFailureShortCircuits() {
        fake.uploadUrl = null;
        var ok = SlackFileUploader.upload("xoxb-t", "C123", null, tmp, "x.png", null);
        assertFalse(ok);
        assertTrue(fake.postedTo.isEmpty(), "no POST when the upload slot couldn't be reserved");
    }

    @Test
    void postFailureShortCircuits() {
        fake.postOk = false;
        var ok = SlackFileUploader.upload("xoxb-t", "C123", null, tmp, "x.png", null);
        assertFalse(ok);
        assertTrue(fake.completedTo.isEmpty(), "no completeUpload when the byte POST failed");
    }

    @Test
    void completeFailureReturnsFalse() {
        fake.completeOk = false;
        assertFalse(SlackFileUploader.upload("xoxb-t", "C123", null, tmp, "x.png", null));
    }

    @Test
    void missingFileReturnsFalse() {
        assertFalse(SlackFileUploader.upload("xoxb-t", "C123", null,
                new File("/no/such/file.png"), "x.png", null));
    }
}
