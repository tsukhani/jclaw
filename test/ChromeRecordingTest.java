import com.google.gson.JsonObject;
import models.Agent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.AgentService;
import services.ChromeBridgePolicy;
import services.ConfigService;
import tools.ChromeControlTool;
import utils.AppClock;
import utils.SsrfGuard;

import javax.imageio.ImageIO;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static com.google.gson.JsonParser.parseString;

class ChromeRecordingTest extends UnitTest {

    private static final Map<String, String> HOSTS = Map.of("example.com", "93.184.216.34", "evil.com", "1.1.1.1");
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC);
    private static final String ALLOWED = "https://example.com/";
    private static final String SAVED_NOTE = " Don't quote the file paths in your reply — the user already sees the images.]";

    private final ChromeControlTool tool = new ChromeControlTool();
    private Agent agent;

    @BeforeEach
    void saveAgent() {
        agent = AgentService.create("chrome-rec-" + UUID.randomUUID().toString().substring(0, 8), "openrouter", "gpt-4.1");
        ConfigService.set(ChromeBridgePolicy.key(agent.name, ChromeBridgePolicy.ALLOWED_SITES), "example.com");
    }

    @AfterEach
    void cleanup() {
        AgentService.delete(agent);
    }

    private static byte[] png(int width, int height, int color) throws Exception {
        var img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (var x = 0; x < width; x++) for (var y = 0; y < height; y++) img.setRGB(x, y, color);
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static JsonObject shot(byte[] bytes, String url) {
        return ChromeBridgeSync.ok(parseString("{\"url\":\"" + url + "\",\"data\":\"" + Base64.getEncoder().encodeToString(bytes) + "\"}"));
    }

    /** An extension that answers everything, and screenshots through {@code onShot}. */
    private static ChromeBridgeSync.Fake extension(Function<JsonObject, JsonObject> onShot) {
        return new ChromeBridgeSync.Fake(msg -> switch (msg.get("action").getAsString()) {
            case "tab_info" -> ChromeBridgeSync.ok(parseString("{\"url\":\"" + ALLOWED + "\"}"));
            case "screenshot" -> onShot.apply(msg);
            default -> ChromeBridgeSync.ok(parseString("{\"done\":\"ok\"}"));
        });
    }

    private static ChromeBridgeSync.Fake plainExtension() {
        try {
            var gif = png(24, 16, 0x336699);
            return extension(msg -> msg.get("format").getAsString().equals("webp")
                    ? shot(AnimatedImagesTest.A, ALLOWED) : shot(gif, ALLOWED));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String run(String args) {
        return AppClock.callWith(CLOCK, () -> SsrfGuard.callWithHostsForTest(HOSTS, () -> tool.execute(args, agent)));
    }

    private static String call(String action, String extra) {
        return "{\"action\":\"" + action + "\",\"tabId\":7" + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    private static String saved(Agent agent, String filename) {
        return "/api/agents/" + agent.id + "/files/" + filename;
    }

    private static int count(byte[] bytes, String id) {
        var text = new String(bytes, StandardCharsets.ISO_8859_1);
        var n = 0;
        for (var at = text.indexOf(id); at >= 0; at = text.indexOf(id, at + 1)) n++;
        return n;
    }

    private long screenshots(ChromeBridgeSync.Fake fake) {
        return fake.actions().stream().filter(a -> a.equals("screenshot")).count();
    }

    @Test
    void aWebpRecordingOfTwoClicksHoldsAFrameForTheStartEachClickAndTheEnd() throws Exception {
        var fake = plainExtension();
        ChromeBridgeSync.with(fake, () -> {
            assertEquals("Recording tab 7 as webp: up to 30 frames, 700 ms each, 800 px wide at most. A frame is added after "
                    + "each click, key, typing, scroll, form change, drag and navigation. Stop it with record_stop.",
                    run(call("record_start", "")));
            run(call("click", "\"ref\":\"e1\""));
            run(call("click", "\"ref\":\"e2\""));

            assertEquals("![Recording](" + saved(agent, "recording-1700000000000.webp") + ")\n"
                    + "[Recording saved: 4 frames, 700 ms each, up to 800 px wide." + SAVED_NOTE, run(call("record_stop", "")));

            var file = Files.readAllBytes(AgentService.workspacePath(agent.name).resolve("recording-1700000000000.webp"));
            assertEquals("WEBP", new String(file, 8, 4, StandardCharsets.ISO_8859_1));
            assertEquals(4, count(file, "ANMF"));
            var frame = fake.sent.stream().filter(m -> m.get("action").getAsString().equals("screenshot")).findFirst().orElseThrow();
            assertEquals("webp", frame.get("format").getAsString());
            assertEquals(800, frame.get("maxWidth").getAsInt());
            assertEquals(60, frame.get("quality").getAsInt());
        });
    }

    @Test
    void aGifRecordingAsksForPngFramesAndSavesADecodableGif() throws Exception {
        var fake = plainExtension();
        ChromeBridgeSync.with(fake, () -> {
            run(call("record_start", "\"format\":\"gif\",\"frameMs\":500"));
            run(call("click", "\"ref\":\"e1\""));

            assertEquals("![Recording](" + saved(agent, "recording-1700000000000.gif") + ")\n"
                    + "[Recording saved: 3 frames, 500 ms each, up to 800 px wide." + SAVED_NOTE, run(call("record_stop", "")));

            assertTrue(fake.sent.stream().filter(m -> m.get("action").getAsString().equals("screenshot"))
                    .allMatch(m -> m.get("format").getAsString().equals("png")));
            var gif = Files.readAllBytes(AgentService.workspacePath(agent.name).resolve("recording-1700000000000.gif"));
            var reader = ImageIO.getImageReadersByFormatName("gif").next();
            reader.setInput(ImageIO.createImageInputStream(new ByteArrayInputStream(gif)));
            assertEquals(3, reader.getNumImages(true));
        });
    }

    @Test
    void bothFormatsSaveBothFilesAndCaptureEachFrameTwice() throws Exception {
        var fake = plainExtension();
        ChromeBridgeSync.with(fake, () -> {
            run(call("record_start", "\"format\":\"both\""));
            run(call("click", "\"ref\":\"e1\""));

            assertEquals("![Recording](" + saved(agent, "recording-1700000000000.webp") + ")\n"
                    + "![Recording (GIF)](" + saved(agent, "recording-1700000000000.gif") + ")\n"
                    + "[Recording saved: 3 frames, 700 ms each, up to 800 px wide." + SAVED_NOTE, run(call("record_stop", "")));
            assertEquals(6, screenshots(fake));
        });
    }

    @Test
    void theFrameLimitStopsCaptureAndSaysSo() throws Exception {
        var fake = plainExtension();
        ChromeBridgeSync.with(fake, () -> {
            run(call("record_start", "\"maxFrames\":3"));
            for (var i = 0; i < 5; i++) run(call("click", "\"ref\":\"e1\""));

            assertEquals("![Recording](" + saved(agent, "recording-1700000000000.webp") + ")\n"
                    + "[Recording saved: 3 frames, 700 ms each, up to 800 px wide. The limit was reached, so later steps are not shown."
                    + SAVED_NOTE, run(call("record_stop", "")));
            assertEquals(3, screenshots(fake), "nothing is captured once the recording is full");
        });
    }

    @Test
    void theRequestedLimitsAreClamped() throws Exception {
        ChromeBridgeSync.with(plainExtension(), () -> {
            assertTrue(run(call("record_start", "\"maxFrames\":500,\"frameMs\":10")).startsWith("Recording tab 7 as webp: up to 100 frames, 100 ms each,"));
            run(call("close", ""));
            assertTrue(run(call("record_start", "\"maxFrames\":1,\"frameMs\":99999")).startsWith("Recording tab 7 as webp: up to 2 frames, 5000 ms each,"));
            run(call("close", ""));
        });
    }

    @Test
    void aFrameFromAPageOffTheAllowlistIsLeftOut() throws Exception {
        var calls = new AtomicInteger();
        var fake = extension(msg -> shot(AnimatedImagesTest.A, calls.incrementAndGet() == 2 ? "https://evil.com/" : ALLOWED));
        ChromeBridgeSync.with(fake, () -> {
            run(call("record_start", ""));
            run(call("click", "\"ref\":\"e1\""));

            assertEquals("![Recording](" + saved(agent, "recording-1700000000000.webp") + ")\n"
                    + "[Recording saved: 2 frames, 700 ms each, up to 800 px wide. 1 frame was left out: the page was off the allowed "
                    + "list, a capture failed, or the window changed size." + SAVED_NOTE, run(call("record_stop", "")));
        });
    }

    @Test
    void aFrameOfAnotherSizeIsLeftOut() throws Exception {
        var calls = new AtomicInteger();
        var fake = extension(msg -> shot(calls.incrementAndGet() == 2 ? AnimatedImagesTest.E : AnimatedImagesTest.A, ALLOWED));
        ChromeBridgeSync.with(fake, () -> {
            run(call("record_start", ""));
            run(call("click", "\"ref\":\"e1\""));

            var result = run(call("record_stop", ""));
            assertTrue(result.contains("[Recording saved: 2 frames,"), result);
            assertTrue(result.contains("1 frame was left out"), result);
        });
    }

    @Test
    void aRecordingOfOneFrameHasNothingToAnimate() throws Exception {
        var calls = new AtomicInteger();
        var fake = extension(msg -> calls.incrementAndGet() == 1 ? shot(AnimatedImagesTest.A, ALLOWED) : ChromeBridgeSync.error("tab gone"));
        ChromeBridgeSync.with(fake, () -> {
            run(call("record_start", ""));

            assertEquals("Error: recorded only 1 frame; there is nothing to animate.", run(call("record_stop", "")));
        });
    }

    @Test
    void aFailedFirstFrameStartsNothing() throws Exception {
        var failing = extension(_ -> ChromeBridgeSync.error("boom"));
        ChromeBridgeSync.with(failing, () -> {
            assertEquals("Error: boom", run(call("record_start", "")));
            assertEquals("Error: no recording is running on that tab.", run(call("record_stop", "")));
        });
        var offList = extension(_ -> shot(AnimatedImagesTest.A, "https://evil.com/"));
        ChromeBridgeSync.with(offList, () ->
                assertEquals("Error: the first frame could not be captured.", run(call("record_start", ""))));
    }

    @Test
    void misuseIsNamed() throws Exception {
        ChromeBridgeSync.with(plainExtension(), () -> {
            assertEquals("Error: no recording is running on that tab.", run(call("record_stop", "")));
            assertEquals("Error: format must be webp, gif or both.", run(call("record_start", "\"format\":\"mp4\"")));
            assertEquals("Error: record_start needs a tabId.", run("{\"action\":\"record_start\"}"));
            assertEquals("Error: record_stop needs a tabId.", run("{\"action\":\"record_stop\"}"));
            run(call("record_start", ""));
            assertEquals("Error: that tab is already being recorded.", run(call("record_start", "")));
            run(call("close", ""));
        });
    }

    @Test
    void closingATabDiscardsItsRecording() throws Exception {
        ChromeBridgeSync.with(plainExtension(), () -> {
            run(call("record_start", ""));
            run(call("close", ""));

            assertEquals("Error: no recording is running on that tab.", run(call("record_stop", "")));
        });
    }

    @Test
    void aTabOffTheAllowlistCannotBeRecorded() throws Exception {
        var fake = new ChromeBridgeSync.Fake(_ -> ChromeBridgeSync.ok(parseString("{\"url\":\"https://evil.com/\"}")));
        ChromeBridgeSync.with(fake, () -> {
            assertEquals("Error: site not on the allowlist: evil.com", run(call("record_start", "")));
            assertEquals(List.of("tab_info"), fake.actions());
        });
    }

    @Test
    void onlyAFewRecordingsRunAtOnce() throws Exception {
        ChromeBridgeSync.with(plainExtension(), () -> {
            for (var tab = 1; tab <= 4; tab++) run("{\"action\":\"record_start\",\"tabId\":" + tab + "}");
            try {
                assertEquals("Error: too many recordings are running; stop one first.", run("{\"action\":\"record_start\",\"tabId\":5}"));
            } finally {
                for (var tab = 1; tab <= 4; tab++) run("{\"action\":\"close\",\"tabId\":" + tab + "}");
            }
        });
    }
}
