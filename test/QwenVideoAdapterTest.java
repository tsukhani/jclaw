import org.junit.jupiter.api.Test;
import services.video.FrameSampler;
import services.video.QwenVideoAdapter;
import services.video.QwenVideoAdapter.WireShape;
import services.video.VideoAdapterException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JCLAW-220: native-video (Qwen) content-part construction for both serving-endpoint
 * wire shapes. Pure structural assertions over synthetic frame bytes — no ffmpeg.
 */
class QwenVideoAdapterTest {

    private static List<FrameSampler.Frame> fakeFrames() {
        return List.of(
                new FrameSampler.Frame(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1}, 5.0),
                new FrameSampler.Frame(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 2}, 15.0),
                new FrameSampler.Frame(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 3}, 25.0),
                new FrameSampler.Frame(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 4}, 35.0));
    }

    @Test
    void openRouterShapeIsVideoArrayWithFps() {
        var parts = QwenVideoAdapter.contentParts(fakeFrames(), 40.0, WireShape.OPENAI_VIDEO_ARRAY);
        assertEquals(1, parts.size(), "native-video emits a single video part");
        var part = parts.get(0);
        assertEquals("video", part.get("type"));

        @SuppressWarnings("unchecked")
        var urls = (List<String>) part.get("video");
        assertEquals(4, urls.size());
        assertTrue(urls.get(0).startsWith("data:image/jpeg;base64,"), "OpenAI shape uses image/jpeg frames");
        // 4 frames over 40 s → 0.1 fps.
        assertEquals(0.1, (double) part.get("sample_fps"), 1e-9);
    }

    @Test
    void vllmShapeIsBase64VideoJpegWithoutFps() {
        var parts = QwenVideoAdapter.contentParts(fakeFrames(), 40.0, WireShape.VLLM_BASE64);
        var part = parts.get(0);
        assertEquals("video", part.get("type"));

        @SuppressWarnings("unchecked")
        var urls = (List<String>) part.get("video");
        assertEquals(4, urls.size());
        assertTrue(urls.get(0).startsWith("data:video/jpeg;base64,"), "vLLM shape tags frames video/jpeg");
        assertFalse(part.containsKey("sample_fps"), "vLLM derives timing itself");
    }

    @Test
    void shapeForProviderRoutesVllmVsOthers() {
        assertEquals(WireShape.VLLM_BASE64, QwenVideoAdapter.shapeForProvider("vllm"));
        assertEquals(WireShape.VLLM_BASE64, QwenVideoAdapter.shapeForProvider("my-vLLM-box"));
        assertEquals(WireShape.OPENAI_VIDEO_ARRAY, QwenVideoAdapter.shapeForProvider("openrouter"));
        assertEquals(WireShape.OPENAI_VIDEO_ARRAY, QwenVideoAdapter.shapeForProvider("dashscope"));
        assertEquals(WireShape.OPENAI_VIDEO_ARRAY, QwenVideoAdapter.shapeForProvider(null));
    }

    @Test
    void emptyFramesThrowTyped() {
        var noFrames = List.<FrameSampler.Frame>of();
        assertThrows(VideoAdapterException.class,
                () -> QwenVideoAdapter.contentParts(noFrames, 40.0, WireShape.OPENAI_VIDEO_ARRAY));
    }

    @Test
    void isQwenVideoModelMatchesOnlyTheQwenVlOmniFamily() {
        // Drives the native-inline gate (chat model) AND the dedicated-model dropdown filter — only
        // these accept the Qwen video part; everything else must be excluded.
        assertTrue(QwenVideoAdapter.isQwenVideoModel("qwen/qwen3-vl-30b-instruct"));
        assertTrue(QwenVideoAdapter.isQwenVideoModel("Qwen2.5-VL-72B"));
        assertTrue(QwenVideoAdapter.isQwenVideoModel("qwen2.5-omni-7b"));
        assertTrue(QwenVideoAdapter.isQwenVideoModel("some/qwen3-omni"));

        assertFalse(QwenVideoAdapter.isQwenVideoModel("google/gemini-pro-latest"));
        assertFalse(QwenVideoAdapter.isQwenVideoModel("qwen/qwen3-32b")); // a Qwen text model, not -VL/-omni
        assertFalse(QwenVideoAdapter.isQwenVideoModel("gpt-4o"));
        assertFalse(QwenVideoAdapter.isQwenVideoModel(null));
    }
}
