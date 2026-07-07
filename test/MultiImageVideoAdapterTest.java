import org.junit.jupiter.api.Test;
import services.video.FrameSampler;
import services.video.MultiImageVideoAdapter;
import services.video.VideoAdapterException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JCLAW-221: multi-image content-part construction. Pure structural assertions over
 * synthetic frame bytes — no ffmpeg. (The dispatcher functional happy-path against a
 * mocked vision model lives with JCLAW-224's routing tests.)
 */
class MultiImageVideoAdapterTest {

    private static List<FrameSampler.Frame> fakeFrames() {
        return List.of(
                new FrameSampler.Frame(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1}, 5.0),
                new FrameSampler.Frame(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 2}, 65.0),   // 00:01:05
                new FrameSampler.Frame(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 3}, 125.0)); // 00:02:05
    }

    @Test
    void buildsLeadingTextThenImageParts() {
        var parts = MultiImageVideoAdapter.contentParts(fakeFrames(), 130.0);
        assertEquals(4, parts.size(), "1 text header + 3 image parts");

        var head = parts.get(0);
        assertEquals("text", head.get("type"));
        var text = (String) head.get("text");
        assertTrue(text.contains("3 images"), "states frame count: " + text);
        assertTrue(text.contains("00:02:10"), "states duration hh:mm:ss: " + text);
        assertTrue(text.contains("00:00:05") && text.contains("00:01:05") && text.contains("00:02:05"),
                "lists each frame timestamp: " + text);

        for (int i = 1; i < parts.size(); i++) {
            assertEquals("image_url", parts.get(i).get("type"));
            @SuppressWarnings("unchecked")
            var img = (Map<String, Object>) parts.get(i).get("image_url");
            assertTrue(((String) img.get("url")).startsWith("data:image/jpeg;base64,"));
        }
    }

    @Test
    void emptyFramesThrowTyped() {
        var noFrames = List.<FrameSampler.Frame>of();
        assertThrows(VideoAdapterException.class, () -> MultiImageVideoAdapter.contentParts(noFrames, 10.0));
    }
}
