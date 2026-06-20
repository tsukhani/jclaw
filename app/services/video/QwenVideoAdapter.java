package services.video;

import services.video.FrameSampler.Frame;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Native-video adapter (JCLAW-220): wraps sampled frames into a Qwen-native video content
 * part for models that advertise {@code supportsVideo} (the Qwen-VL family served via
 * OpenRouter/DashScope or vLLM). Unlike Gemini, Qwen ingests a frame list with timing
 * metadata and does its own temporal (mRoPE) alignment — there is no Files-API
 * upload-and-poll to manage.
 *
 * <p>The frames come from {@link FrameSampler} — the same extraction the multi-image
 * adapter (JCLAW-221) uses; the two adapters differ only in how they wrap the frames (a single
 * Qwen {@code video} part with an fps hint here vs. independent {@code image_url} parts
 * there). Building only {@code Map<String,Object>} content parts means Gson serializes
 * them to the wire automatically (see {@code LlmProvider.serializeMessages}); no
 * serializer changes are needed.
 *
 * <p>Two wire shapes, chosen by serving endpoint:
 * <ul>
 *   <li>{@link WireShape#OPENAI_VIDEO_ARRAY} (OpenRouter / DashScope OpenAI-compatible):
 *       a single {@code {"type":"video","video":[<frame data URLs>],"sample_fps":F}} part,
 *       where {@code F = frames/duration} tells Qwen how far apart the frames sit in time.</li>
 *   <li>{@link WireShape#VLLM_BASE64} (vLLM OpenAI-compatible): the same frame list but each
 *       frame tagged with the {@code video/jpeg} mime per vLLM's client-side-sampling
 *       convention; vLLM derives the timing itself, so no {@code sample_fps}.</li>
 * </ul>
 *
 * <p>NOTE: the exact Qwen wire keys follow documented DashScope/vLLM conventions and are
 * asserted structurally by the unit test, not against a live endpoint. Runtime
 * format-rejection downgrade is an explicit Phase-2 follow-up (see JCLAW-224 out-of-scope).
 */
public final class QwenVideoAdapter {

    private QwenVideoAdapter() {}

    public enum WireShape { OPENAI_VIDEO_ARRAY, VLLM_BASE64 }

    /**
     * Pick the wire shape from the agent's provider name. vLLM gets the base64
     * {@code video/jpeg} shape; everything else (OpenRouter, DashScope, ollama-cloud)
     * gets the OpenAI video array with an fps hint.
     */
    public static WireShape shapeForProvider(String providerName) {
        var p = providerName == null ? "" : providerName.toLowerCase();
        return p.contains("vllm") ? WireShape.VLLM_BASE64 : WireShape.OPENAI_VIDEO_ARRAY;
    }

    /**
     * True for the Qwen-VL / Omni family — the only models that ingest the native video content part
     * this adapter emits ({@code {"type":"video","video":[...]}}). Other models that advertise
     * {@code supportsVideo} (e.g. Gemini) accept video only in their own provider-native format and
     * <b>silently ignore</b> this one (OpenRouter drops the unrecognized part, no error), so the
     * dispatcher must route only Qwen models through the inline native path — everything else falls
     * back to frames-as-images or a dedicated video model. Mirrors the id heuristic in
     * {@code ModelDiscoveryService.detectVideoSupport}.
     */
    public static boolean isQwenVideoModel(String modelId) {
        if (modelId == null) return false;
        var id = modelId.toLowerCase();
        return id.contains("qwen2.5-vl") || id.contains("qwen3-vl") || id.contains("qwen-vl")
                || id.contains("qwen2.5-omni") || id.contains("qwen3-omni");
    }

    /**
     * Build the Qwen video content part(s) for the given frames. Returned as a singleton
     * list so the dispatcher (JCLAW-224) can treat every strategy's output uniformly as a
     * {@code List<content part>} spliced into the user message.
     *
     * @param frames          sampled frames in ascending timestamp order (from {@link FrameSampler})
     * @param durationSeconds source video duration, for the {@code sample_fps} hint
     * @param shape           wire shape for the serving endpoint
     */
    public static List<Map<String, Object>> contentParts(List<Frame> frames, double durationSeconds, WireShape shape) {
        if (frames == null || frames.isEmpty()) {
            throw new VideoAdapterException("no frames to build a Qwen video part");
        }
        var mime = shape == WireShape.VLLM_BASE64 ? "video/jpeg" : "image/jpeg";
        var urls = new ArrayList<String>(frames.size());
        for (var f : frames) {
            urls.add("data:" + mime + ";base64," + Base64.getEncoder().encodeToString(f.jpeg()));
        }
        var part = new LinkedHashMap<String, Object>();
        part.put("type", "video");
        part.put("video", urls);
        if (shape == WireShape.OPENAI_VIDEO_ARRAY) {
            double fps = durationSeconds > 0 ? frames.size() / durationSeconds : 1.0;
            part.put("sample_fps", Math.round(fps * 100.0) / 100.0);
        }
        return List.of(part);
    }
}
