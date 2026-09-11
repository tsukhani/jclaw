package services.discovery;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Normalizes a provider's model catalog into the one map shape the frontend and the
 * agent editor consume (JCLAW-999, moved out of {@code ModelDiscoveryService} so the
 * discovery strategies no longer reach back into the service that dispatches them).
 * Three parsers — OpenAI-compatible {@code /models}, LM Studio's native
 * {@code /api/v0/models}, Ollama's {@code /api/show} — share the capability detectors,
 * the pricing inference and the {@link ModelInfo} projection, so every path emits the
 * full key set by construction. Pure functions over JSON; no I/O.
 */
public final class ModelCatalogParser {

    private ModelCatalogParser() {}

    // --- Normalized model-map keys (returned to the frontend) ---
    public static final String KEY_ID = "id";
    static final String KEY_NAME = "name";
    private static final String KEY_CONTEXT_WINDOW = "contextWindow";
    private static final String KEY_MAX_TOKENS = "maxTokens";
    private static final String KEY_SUPPORTS_THINKING = "supportsThinking";
    private static final String KEY_THINKING_DETECTED_FROM_PROVIDER = "thinkingDetectedFromProvider";
    private static final String KEY_ALWAYS_THINKS = "alwaysThinks";
    private static final String KEY_ALWAYS_THINKS_DETECTED_FROM_PROVIDER = "alwaysThinksDetectedFromProvider";
    private static final String KEY_SUPPORTS_TOOLS = "supportsTools";
    private static final String KEY_TOOLS_DETECTED_FROM_PROVIDER = "toolsDetectedFromProvider";
    private static final String KEY_SUPPORTS_VISION = "supportsVision";
    private static final String KEY_VISION_DETECTED_FROM_PROVIDER = "visionDetectedFromProvider";
    private static final String KEY_SUPPORTS_AUDIO = "supportsAudio";
    private static final String KEY_AUDIO_DETECTED_FROM_PROVIDER = "audioDetectedFromProvider";
    private static final String KEY_SUPPORTS_VIDEO = "supportsVideo";
    private static final String KEY_VIDEO_DETECTED_FROM_PROVIDER = "videoDetectedFromProvider";
    private static final String KEY_PROMPT_PRICE = "promptPrice";
    private static final String KEY_COMPLETION_PRICE = "completionPrice";
    private static final String KEY_CACHED_READ_PRICE = "cachedReadPrice";
    private static final String KEY_CACHE_WRITE_PRICE = "cacheWritePrice";
    private static final String KEY_IS_FREE = "isFree";
    static final String KEY_LEADERBOARD_RANK = "leaderboardRank";

    // --- Provider JSON field names ---
    public static final String FIELD_MODELS = "models";
    static final String FIELD_DATA = "data";
    private static final String FIELD_ARCHITECTURE = "architecture";
    private static final String FIELD_INSTRUCT_TYPE = "instruct_type";
    private static final String FIELD_CAPABILITIES = "capabilities";
    private static final String FIELD_SUPPORTED_PARAMETERS = "supported_parameters";
    private static final String FIELD_INPUT_MODALITIES = "input_modalities";
    private static final String FIELD_MODALITY = "modality";
    private static final String FIELD_CONTEXT_LENGTH = "context_length";
    private static final String FIELD_CONTEXT_WINDOW = "context_window";
    private static final String FIELD_TOP_PROVIDER = "top_provider";
    private static final String FIELD_MAX_COMPLETION_TOKENS = "max_completion_tokens";
    private static final String FIELD_MAX_TOKENS = "max_tokens";
    private static final String FIELD_MAX_CONTEXT_LENGTH = "max_context_length";
    private static final String FIELD_MODEL_INFO = "model_info";
    private static final String FIELD_PRICING = "pricing";
    private static final String FIELD_PROMPT = "prompt";
    private static final String FIELD_COMPLETION = "completion";
    // Together AI's /v1/models pricing keys — distinct from OpenRouter's
    // prompt/completion, and quoted in dollars-per-million (not per-token).
    private static final String FIELD_TOGETHER_INPUT = "input";
    private static final String FIELD_TOGETHER_OUTPUT = "output";
    private static final String FIELD_TOGETHER_CACHED_INPUT = "cached_input";
    private static final String TYPE_INPUT_CACHE_READ = "input_cache_read";

    // --- Value literals ---
    private static final String INSTRUCT_TYPE_DEEPSEEK_R1 = "deepseek-r1";

    /**
     * The single normalized model shape emitted by all three discovery parsers
     * ({@link #parseModels}, {@link #parseLmStudioNativeResponse},
     * {@link #parseOllamaShow}). Centralizing the field set — and the
     * {@code key -> value} projection in {@link #toMap()} — keeps the three
     * paths from drifting: every parser emits the full key set by construction.
     */
    record ModelInfo(
            String id, String name, int contextWindow, int maxTokens,
            boolean supportsThinking, boolean thinkingFromProvider,
            boolean alwaysThinks, boolean alwaysThinksFromProvider,
            boolean supportsVision, boolean visionFromProvider,
            boolean supportsAudio, boolean audioFromProvider,
            boolean supportsVideo, boolean videoFromProvider,
            boolean supportsTools, boolean toolsFromProvider,
            double promptPrice, double completionPrice,
            double cachedReadPrice, double cacheWritePrice,
            boolean isFree) {

        Map<String, Object> toMap() {
            var m = new LinkedHashMap<String, Object>();
            m.put(KEY_ID, id);
            m.put(KEY_NAME, name);
            m.put(KEY_CONTEXT_WINDOW, contextWindow);
            m.put(KEY_MAX_TOKENS, maxTokens);
            m.put(KEY_SUPPORTS_THINKING, supportsThinking);
            m.put(KEY_THINKING_DETECTED_FROM_PROVIDER, thinkingFromProvider);
            m.put(KEY_ALWAYS_THINKS, alwaysThinks);
            m.put(KEY_ALWAYS_THINKS_DETECTED_FROM_PROVIDER, alwaysThinksFromProvider);
            m.put(KEY_SUPPORTS_VISION, supportsVision);
            m.put(KEY_VISION_DETECTED_FROM_PROVIDER, visionFromProvider);
            m.put(KEY_SUPPORTS_AUDIO, supportsAudio);
            m.put(KEY_AUDIO_DETECTED_FROM_PROVIDER, audioFromProvider);
            m.put(KEY_SUPPORTS_VIDEO, supportsVideo);
            m.put(KEY_VIDEO_DETECTED_FROM_PROVIDER, videoFromProvider);
            m.put(KEY_SUPPORTS_TOOLS, supportsTools);
            m.put(KEY_TOOLS_DETECTED_FROM_PROVIDER, toolsFromProvider);
            m.put(KEY_PROMPT_PRICE, promptPrice);
            m.put(KEY_COMPLETION_PRICE, completionPrice);
            m.put(KEY_CACHED_READ_PRICE, cachedReadPrice);
            m.put(KEY_CACHE_WRITE_PRICE, cacheWritePrice);
            m.put(KEY_IS_FREE, isFree);
            return m;
        }
    }

    // --- Model parsing ---

    public static List<Map<String, Object>> parseModels(JsonElement body) {
        var result = new ArrayList<Map<String, Object>>();
        JsonArray dataArray = null;

        // Three response shapes seen in the wild:
        //   1. Bare array `[{id, ...}, ...]` — Together AI
        //   2. {data: [...]}                — OpenAI, OpenRouter, most OpenAI-compats
        //   3. {models: [...]}              — Ollama-shaped responses
        // Anything else returns an empty list (graceful degradation: caller
        // sees "0 models" rather than a 502).
        if (body == null || body.isJsonNull()) return result;
        if (body.isJsonArray()) {
            dataArray = body.getAsJsonArray();
        } else if (body.isJsonObject()) {
            var obj = body.getAsJsonObject();
            if (obj.has(FIELD_DATA) && obj.get(FIELD_DATA).isJsonArray()) {
                dataArray = obj.getAsJsonArray(FIELD_DATA);
            } else if (obj.has(FIELD_MODELS) && obj.get(FIELD_MODELS).isJsonArray()) {
                dataArray = obj.getAsJsonArray(FIELD_MODELS);
            }
        }

        if (dataArray == null) return result;

        for (var el : dataArray) {
            if (!el.isJsonObject()) continue;
            var obj = el.getAsJsonObject();

            var thinking = detectThinkingSupport(obj);
            var alwaysThinks = detectAlwaysThinks(obj);
            var vision = detectVisionSupport(obj);
            var audio = detectAudioSupport(obj);
            var video = detectVideoSupport(obj);
            var tools = detectToolSupport(obj);
            var info = new ModelInfo(
                    getString(obj, KEY_ID, ""),
                    inferName(obj),
                    inferContextWindow(obj),
                    inferMaxTokens(obj),
                    thinking.confirmed(), thinking.fromProvider(),
                    alwaysThinks.confirmed(), alwaysThinks.fromProvider(),
                    vision.confirmed(), vision.fromProvider(),
                    audio.confirmed(), audio.fromProvider(),
                    video.confirmed(), video.fromProvider(),
                    tools.confirmed(), tools.fromProvider(),
                    inferPrice(obj, FIELD_PROMPT),
                    inferPrice(obj, FIELD_COMPLETION),
                    inferPrice(obj, TYPE_INPUT_CACHE_READ),
                    inferPrice(obj, "input_cache_write"),
                    inferIsFree(obj));

            if (info.id().isBlank()) continue;

            result.add(info.toMap());
        }

        return result;
    }

    private static String inferName(JsonObject obj) {
        if (obj.has(KEY_NAME) && !obj.get(KEY_NAME).isJsonNull()) {
            return obj.get(KEY_NAME).getAsString();
        }
        var id = getString(obj, KEY_ID, "");
        if (id.contains("/")) {
            id = id.substring(id.lastIndexOf('/') + 1);
        }
        return id;
    }

    private static int inferContextWindow(JsonObject obj) {
        if (obj.has(FIELD_CONTEXT_LENGTH) && !obj.get(FIELD_CONTEXT_LENGTH).isJsonNull()) {
            return obj.get(FIELD_CONTEXT_LENGTH).getAsInt();
        }
        if (obj.has(FIELD_CONTEXT_WINDOW) && !obj.get(FIELD_CONTEXT_WINDOW).isJsonNull()) {
            return obj.get(FIELD_CONTEXT_WINDOW).getAsInt();
        }
        return 0;
    }

    private static int inferMaxTokens(JsonObject obj) {
        if (obj.has(FIELD_TOP_PROVIDER) && obj.get(FIELD_TOP_PROVIDER).isJsonObject()) {
            var tp = obj.getAsJsonObject(FIELD_TOP_PROVIDER);
            if (tp.has(FIELD_MAX_COMPLETION_TOKENS) && !tp.get(FIELD_MAX_COMPLETION_TOKENS).isJsonNull()) {
                return tp.get(FIELD_MAX_COMPLETION_TOKENS).getAsInt();
            }
        }
        if (obj.has(FIELD_MAX_COMPLETION_TOKENS) && !obj.get(FIELD_MAX_COMPLETION_TOKENS).isJsonNull()) {
            return obj.get(FIELD_MAX_COMPLETION_TOKENS).getAsInt();
        }
        if (obj.has(FIELD_MAX_TOKENS) && !obj.get(FIELD_MAX_TOKENS).isJsonNull()) {
            return obj.get(FIELD_MAX_TOKENS).getAsInt();
        }
        return 0;
    }

    /**
     * Detect "always thinks" pure reasoning models — those whose architecture
     * has no non-thinking mode (OpenAI o-series, DeepSeek-R1 family, Qwen QwQ).
     * The provider API accepts a "reasoning off" value but the model thinks
     * anyway, so the UI surfaces these with a locked-on toggle.
     *
     * <p>No major provider exposes this as a metadata field — neither
     * {@code supported_parameters} (OpenRouter) nor {@code capabilities}
     * (Ollama) distinguishes reasoning-required from reasoning-optional.
     * The single programmatic signal we have is OpenRouter's
     * {@code architecture.instruct_type: "deepseek-r1"}, which uniquely
     * identifies the R1 family. Everywhere else we fall back to tight
     * id-pattern matching against the well-known reasoning-only families.
     *
     * <p>Patterns are deliberately tighter than {@link #detectThinkingSupport}'s
     * fallback (which uses bare {@code id.contains("o1")} and would
     * false-positive on {@code "claude-opus-4-1"}, etc.). This detector
     * requires the o-series id-component to start at the beginning of the
     * id or after a {@code /} provider prefix, and matches only the suffixes
     * the OpenAI catalog actually ships ({@code -mini}, {@code -pro},
     * {@code -preview}).
     */
    public static CapabilityDetection detectAlwaysThinks(JsonObject obj) {
        // OpenRouter's architecture.instruct_type is the only provider-surfaced signal for
        // "always thinks"; for the R1 family it's exactly "deepseek-r1", so treat as confirmed.
        if (obj.has(FIELD_ARCHITECTURE) && obj.get(FIELD_ARCHITECTURE).isJsonObject()) {
            var arch = obj.getAsJsonObject(FIELD_ARCHITECTURE);
            if (arch.has(FIELD_INSTRUCT_TYPE) && arch.get(FIELD_INSTRUCT_TYPE).isJsonPrimitive()) {
                var instructType = arch.get(FIELD_INSTRUCT_TYPE).getAsString().toLowerCase();
                if (INSTRUCT_TYPE_DEEPSEEK_R1.equals(instructType)) {
                    return new CapabilityDetection(true, true);
                }
            }
        }

        var id = getString(obj, KEY_ID, "").toLowerCase();
        if (matchesReasoningOnlyFamily(id)) {
            return new CapabilityDetection(true, false);
        }

        return new CapabilityDetection(false, false);
    }

    /**
     * Match known reasoning-only model id patterns. Tight on purpose — see
     * {@link #detectAlwaysThinks} doc for why we don't use the broader
     * {@link #detectThinkingSupport} fallback patterns. New families ship
     * roughly twice a year and require an update here.
     */
    private static boolean matchesReasoningOnlyFamily(String id) {
        // OpenAI o-series. Allows optional provider prefix (openai/) and
        // optional Ollama-style :tag suffix. Suffixes are limited to the
        // -mini / -pro / -preview variants the catalog actually ships.
        if (id.matches("^(?:[^/]+/)?o[1-9](?:-(?:mini|pro|preview))?(?::.+)?$")) return true;
        // DeepSeek-R1 family — explicit hyphen so we don't match bare "r1"
        // tokens in unrelated names. Covers distill variants too.
        if (id.contains(INSTRUCT_TYPE_DEEPSEEK_R1)) return true;
        // Qwen QwQ (Question with Question). No non-pure-reasoner variant
        // currently ships under this family name.
        return id.contains("qwq");
    }

    public record ThinkingDetection(boolean confirmed, boolean fromProvider) {}

    public static ThinkingDetection detectThinkingSupport(JsonObject obj) {
        if (obj.has(FIELD_SUPPORTED_PARAMETERS) && obj.get(FIELD_SUPPORTED_PARAMETERS).isJsonArray()) {
            var hit = arrayContainsAny(
                    obj.getAsJsonArray(FIELD_SUPPORTED_PARAMETERS),
                    false,
                    "reasoning", "reasoning_effort");
            return new ThinkingDetection(hit, true);
        }

        // Ollama /api/show exposes capabilities as a bare array (JCLAW-118).
        // Mirrors the same precedence in detectVisionSupport /
        // detectAudioSupport — if the provider reports the array at all, we
        // trust it and mark fromProvider=true.
        if (obj.has(FIELD_CAPABILITIES) && obj.get(FIELD_CAPABILITIES).isJsonArray()) {
            var hit = arrayContainsAny(obj.getAsJsonArray(FIELD_CAPABILITIES), true, "thinking");
            return new ThinkingDetection(hit, true);
        }

        var id = getString(obj, KEY_ID, "").toLowerCase();
        if (id.contains("o1") || id.contains("o3") || id.contains("o4-mini")
                || id.contains(INSTRUCT_TYPE_DEEPSEEK_R1) || id.contains("qwq")) {
            return new ThinkingDetection(true, false);
        }

        return new ThinkingDetection(false, false);
    }

    /** True when any JSON-primitive element of {@code array} equals one of {@code needles}. */
    private static boolean arrayContainsAny(JsonArray array, boolean caseInsensitive, String... needles) {
        for (var el : array) {
            if (!el.isJsonPrimitive()) continue;
            var val = el.getAsString();
            for (var needle : needles) {
                if (caseInsensitive ? needle.equalsIgnoreCase(val) : needle.equals(val)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Shared return shape for capability detectors.
     *
     * @param confirmed    best-effort final answer for the capability
     * @param fromProvider true when the provider explicitly reported enough
     *                     metadata to decide (so the UI can lock the
     *                     corresponding checkbox); false when we fell back
     *                     to an ID-based heuristic or defaulted to absence
     */
    public record CapabilityDetection(boolean confirmed, boolean fromProvider) {}

    /**
     * Detect vision (image input) support. Primary signal is OpenRouter's
     * {@code architecture.input_modalities} array (e.g. {@code ["text","image"]});
     * secondary is Ollama's {@code capabilities} array from {@code /api/show}
     * merged into the /models payload. Falls back to model-ID heuristics for
     * well-known vision-capable families when the provider reports nothing.
     */
    public static CapabilityDetection detectVisionSupport(JsonObject obj) {
        var provider = extractModalities(obj, "image");
        if (provider != null) return provider;

        if (obj.has(FIELD_CAPABILITIES) && obj.get(FIELD_CAPABILITIES).isJsonArray()) {
            var hit = arrayContainsAny(obj.getAsJsonArray(FIELD_CAPABILITIES), true, "vision");
            return new CapabilityDetection(hit, true);
        }

        var id = getString(obj, KEY_ID, "").toLowerCase();
        if (id.contains("gpt-4o") || id.contains("gpt-4.1") || id.contains("gpt-4-vision")
                || id.contains("gpt-5") || id.contains("claude-3") || id.contains("claude-opus")
                || id.contains("claude-sonnet") || id.contains("claude-haiku")
                || id.contains("gemini") || id.contains("llava") || id.contains("-vl")
                || id.contains("qwen2-vl") || id.contains("qwen3-vl") || id.contains("pixtral")
                || id.contains("internvl")) {
            return new CapabilityDetection(true, false);
        }

        return new CapabilityDetection(false, false);
    }

    /**
     * Detect tool-calling support (JCLAW-1074).
     *
     * <p><b>Unknown means supported</b>, inverting the default every other
     * detector uses. The others answer "does this model have an extra
     * capability?", where guessing no costs a feature. This one gates whether
     * the {@code tools} array is sent at all, so guessing no would silently
     * strip tools from every model whose provider publishes no capability
     * list — most of them. Only an authoritative negative flips it off: a
     * provider that reports a capability array and omits {@code tools}.
     *
     * <p>Ollama is that provider today — {@code /api/show} returns
     * {@code ["completion"]} for a chat-only model like {@code dolphin3:8b},
     * which is the 400 this closes.
     */
    public static CapabilityDetection detectToolSupport(JsonObject obj) {
        if (obj.has(FIELD_CAPABILITIES) && obj.get(FIELD_CAPABILITIES).isJsonArray()) {
            var hit = arrayContainsAny(obj.getAsJsonArray(FIELD_CAPABILITIES), true, "tools");
            return new CapabilityDetection(hit, true);
        }
        return new CapabilityDetection(true, false);
    }

    /**
     * Detect audio input support. Same precedence as vision: OpenRouter
     * modality arrays → Ollama capabilities → model-ID heuristic. Audio is
     * still rare across providers so the heuristic list is small.
     */
    public static CapabilityDetection detectAudioSupport(JsonObject obj) {
        var provider = extractModalities(obj, "audio");
        if (provider != null) return provider;

        if (obj.has(FIELD_CAPABILITIES) && obj.get(FIELD_CAPABILITIES).isJsonArray()) {
            var hit = arrayContainsAny(obj.getAsJsonArray(FIELD_CAPABILITIES), true, "audio");
            return new CapabilityDetection(hit, true);
        }

        var id = getString(obj, KEY_ID, "").toLowerCase();
        // JCLAW-160: "-audio-preview" is OpenAI's stable suffix for audio-capable
        // models on /v1/models. Match the suffix anchored on a leading dash so
        // future variants (gpt-4o-mini-audio-preview, gpt-5-audio-preview, …)
        // are flagged without per-version updates, while a hypothetical
        // mid-word match like "non-audio-preview-test" cannot trip the
        // detector. The gpt-4o-audio / gpt-5-audio lines stay in place to
        // catch GA snapshots whose ids drop the -preview suffix.
        if (id.contains("gpt-4o-audio") || id.contains("gpt-5-audio")
                || id.contains("-audio-preview")
                || id.contains("gemini-2.5-flash-audio") || id.contains("whisper")
                || id.contains("qwen2-audio") || id.contains("voxtral")) {
            return new CapabilityDetection(true, false);
        }

        return new CapabilityDetection(false, false);
    }

    /**
     * JCLAW-217: detect native video input support. Same precedence as
     * vision/audio: provider modality arrays (OpenRouter advertises {@code video}
     * in {@code architecture.input_modalities} for Qwen-VL routes) then a
     * model-ID heuristic for the Qwen-VL family. Provider-aware by construction:
     * only the OpenAI-compatible discovery path (OpenRouter, and a vLLM-backed
     * custom provider) reaches this; the Ollama and LM Studio native discovery
     * paths never call it, so a Qwen-VL model served there stays
     * {@code supportsVideo=false} and routes to the multi-image
     * fallback — those backends are image-only (see JCLAW-208). Gemini is not
     * included: we are not using Gemini.
     */
    public static CapabilityDetection detectVideoSupport(JsonObject obj) {
        var provider = extractModalities(obj, "video");
        if (provider != null) return provider;

        var id = getString(obj, KEY_ID, "").toLowerCase();
        if (id.contains("qwen2.5-vl") || id.contains("qwen3-vl") || id.contains("qwen-vl")
                || id.contains("qwen2.5-omni") || id.contains("qwen3-omni")) {
            return new CapabilityDetection(true, false);
        }

        return new CapabilityDetection(false, false);
    }

    /**
     * Scan OpenRouter's {@code architecture.input_modalities} (preferred) and
     * legacy {@code architecture.modality} string for a given modality token.
     * Returns non-null only when the provider explicitly reports modality
     * metadata — caller should fall through to other signals otherwise.
     */
    private static @Nullable CapabilityDetection extractModalities(JsonObject obj, String modality) {
        if (!obj.has(FIELD_ARCHITECTURE) || !obj.get(FIELD_ARCHITECTURE).isJsonObject()) return null;
        var arch = obj.getAsJsonObject(FIELD_ARCHITECTURE);

        if (arch.has(FIELD_INPUT_MODALITIES) && arch.get(FIELD_INPUT_MODALITIES).isJsonArray()) {
            var hit = arrayContainsAny(arch.getAsJsonArray(FIELD_INPUT_MODALITIES), true, modality);
            return new CapabilityDetection(hit, true);
        }

        if (arch.has(FIELD_MODALITY) && arch.get(FIELD_MODALITY).isJsonPrimitive()) {
            var s = arch.get(FIELD_MODALITY).getAsString().toLowerCase();
            return new CapabilityDetection(s.contains(modality), true);
        }

        return null;
    }

    /**
     * Extract a per-million-token price for a JClaw price {@code type}
     * ({@code "prompt"}, {@code "completion"}, {@code "input_cache_read"},
     * {@code "input_cache_write"}) from a model's {@code pricing} object,
     * handling both provider conventions seen on {@code /v1/models}:
     *
     * <ul>
     *   <li><b>OpenRouter:</b> {@code pricing.{prompt,completion,...}} quoted
     *       in dollars <i>per token</i> — multiplied by 1e6 to reach JClaw's
     *       per-million convention.</li>
     *   <li><b>Together AI:</b> {@code pricing.{input,output,cached_input}}
     *       quoted in dollars <i>per million tokens</i> already — used as-is.</li>
     * </ul>
     *
     * <p>The key shape is the discriminator: among JClaw's supported providers
     * only Together uses {@code input}/{@code output}, and it quotes
     * per-million, so reading those keys without the 1e6 scale is correct.
     * Returns {@code -1} ("unknown") when neither shape carries the field.
     */
    public static double inferPrice(JsonObject obj, String type) {
        if (!obj.has(FIELD_PRICING) || !obj.get(FIELD_PRICING).isJsonObject()) return -1;
        var pricing = obj.getAsJsonObject(FIELD_PRICING);

        // OpenRouter shape: dollars per token → scale to per-million.
        var perToken = readPriceField(pricing, type);
        if (perToken >= 0) return perToken * 1_000_000;

        // Together shape: dollars per million already → no scaling.
        var perMillion = readPriceField(pricing, togetherPricingKey(type));
        if (perMillion >= 0) return perMillion;

        return -1;
    }

    /**
     * Read a numeric price field from a {@code pricing} object. Accepts both
     * string-encoded (OpenRouter) and bare-number (Together) JSON values via
     * {@code getAsString}. Returns {@code -1} when the key is null/absent or
     * unparseable; a {@code NaN} value parses without throwing but fails the
     * {@code >= 0} guard in {@link #inferPrice}, so it too resolves to -1.
     */
    private static double readPriceField(JsonObject pricing, @Nullable String key) {
        if (key == null || !pricing.has(key) || pricing.get(key).isJsonNull()) return -1;
        try {
            return Double.parseDouble(pricing.get(key).getAsString());
        } catch (NumberFormatException _) {
            return -1;
        }
    }

    /**
     * Map a JClaw price {@code type} to the equivalent Together AI pricing
     * key. Returns {@code null} for types Together doesn't expose (it has no
     * cache-write price), which {@link #readPriceField} treats as absent.
     */
    private static @Nullable String togetherPricingKey(String type) {
        return switch (type) {
            case FIELD_PROMPT -> FIELD_TOGETHER_INPUT;
            case FIELD_COMPLETION -> FIELD_TOGETHER_OUTPUT;
            case TYPE_INPUT_CACHE_READ -> FIELD_TOGETHER_CACHED_INPUT;
            default -> null;
        };
    }

    /**
     * A model is "free" when both its input and output prices are explicitly
     * zero. Delegates to {@link #inferPrice} so both the OpenRouter and
     * Together pricing shapes are recognized; an unpriced model (either price
     * unknown / {@code -1}) is not free.
     */
    private static boolean inferIsFree(JsonObject obj) {
        return inferPrice(obj, FIELD_PROMPT) == 0 && inferPrice(obj, FIELD_COMPLETION) == 0;
    }

    public static String stripVariant(String id) {
        int idx = id.indexOf(':');
        return idx >= 0 ? id.substring(0, idx) : id;
    }

    /**
     * Strip a date or numeric-version suffix from a model id. Handles three
     * shapes seen in real provider catalogs:
     * <ul>
     *   <li>{@code -YYYY-MM-DD} — OpenAI's dated checkpoint format
     *       ({@code gpt-4o-2024-08-06})</li>
     *   <li>{@code -YYYYMMDD} or longer — Anthropic's contiguous-date format
     *       ({@code claude-3-5-sonnet-20241022})</li>
     *   <li>{@code -NNNN} or {@code -NNN} — short numeric version pins
     *       ({@code gpt-4-0125})</li>
     * </ul>
     *
     * <p>Order matters: the dash-separated date pattern must run first
     * because the trailing day component would otherwise match the short
     * suffix regex and leave the year/month dangling.
     */
    public static String stripVersionSuffix(String id) {
        return id
                .replaceAll("-\\d{4}-\\d{2}-\\d{2}$", "")
                .replaceAll("-\\d{6,}$", "")
                .replaceAll("-\\d{3,4}$", "");
    }

    // ─── LM Studio native discovery (JCLAW-183) ──────────────────────

    /**
     * Parse a {@code /api/v0/models} response body into the same Map shape
     * other discovery paths produce. Filters on the {@code type} field —
     * keeps {@code "llm"} and {@code "vlm"}, drops everything else
     * ({@code "embeddings"}, {@code "tts"}, {@code "stt"}, plus any
     * future type LM Studio adds that we haven't accounted for).
     *
     * <p>Vision support is inferred from {@code type == "vlm"} and
     * marked {@code visionDetectedFromProvider=true} since the type
     * field is authoritative. Thinking and audio are left
     * detector-defaulted because the native API doesn't enumerate them
     * — id-based heuristics in the standard detectors fill in.
     */
    public static List<Map<String, Object>> parseLmStudioNativeResponse(JsonObject body) {
        var results = new ArrayList<Map<String, Object>>();
        if (!body.has(FIELD_DATA) || !body.get(FIELD_DATA).isJsonArray()) return results;

        for (var el : body.getAsJsonArray(FIELD_DATA)) {
            if (!el.isJsonObject()) continue;
            var entry = el.getAsJsonObject();

            var type = getString(entry, "type", "").toLowerCase();
            if (!"llm".equals(type) && !"vlm".equals(type)) continue;

            var id = getString(entry, KEY_ID, "");
            if (id.isBlank()) continue;

            var ctxWin = entry.has(FIELD_MAX_CONTEXT_LENGTH) && !entry.get(FIELD_MAX_CONTEXT_LENGTH).isJsonNull()
                    ? entry.get(FIELD_MAX_CONTEXT_LENGTH).getAsInt()
                    : 0;

            // Vision is authoritative from the type field.
            boolean isVlm = "vlm".equals(type);

            // alwaysThinks runs the id-pattern detector regardless: locally
            // run R1 / QwQ models are still pure reasoners.
            var lmIdOnly = new JsonObject();
            lmIdOnly.addProperty(KEY_ID, id);
            var lmAlwaysThinks = detectAlwaysThinks(lmIdOnly);

            var info = new ModelInfo(
                    id,
                    id.contains("/") ? id.substring(id.lastIndexOf('/') + 1) : id,
                    ctxWin,
                    0,
                    // Thinking and audio aren't enumerated in the native API.
                    // Leave fromProvider=false so the existing id-based heuristic
                    // picks up known thinking models (deepseek-r1, qwq, etc.)
                    // downstream without overriding a confirmed answer here.
                    false, false,
                    lmAlwaysThinks.confirmed(), lmAlwaysThinks.fromProvider(),
                    isVlm, true,
                    false, false,
                    // Video: the LM Studio native path is image-only (JCLAW-208),
                    // so no model discovered here is video-native.
                    false, false,
                    // Tools: the native API doesn't enumerate them, and unknown
                    // means supported — see detectToolSupport.
                    true, false,
                    // Local models — no pricing data.
                    -1.0, -1.0, -1.0, -1.0,
                    false);

            results.add(info.toMap());
        }

        return results;
    }

    // ─── Ollama native discovery (JCLAW-118) ─────────────────────────

    /**
     * Strip a trailing {@code /v1} (with or without trailing slash) from
     * a provider base URL. Ollama providers store
     * {@code https://ollama.com/v1} for the OpenAI-compat inference
     * path; the native discovery endpoints live at the bare host.
     */
    public static String stripV1Suffix(String baseUrl) {
        if (baseUrl == null) return "";
        var s = baseUrl;
        if (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.endsWith("/v1")) s = s.substring(0, s.length() - 3);
        return s;
    }

    /**
     * Extract the list of model ids from a {@code /api/tags} response.
     * The list lives under {@code models[].name} (the full tagged id
     * like {@code "gpt-oss:20b"}); falls back to {@code models[].model}
     * when {@code name} is missing.
     */
    public static List<String> extractTagIds(JsonObject body) {
        var out = new ArrayList<String>();
        if (!body.has(FIELD_MODELS) || !body.get(FIELD_MODELS).isJsonArray()) return out;
        for (var el : body.getAsJsonArray(FIELD_MODELS)) {
            if (!el.isJsonObject()) continue;
            var o = el.getAsJsonObject();
            var id = getString(o, KEY_NAME, getString(o, "model", ""));
            if (!id.isBlank()) out.add(id);
        }
        return out;
    }

    /**
     * Parse a single {@code /api/show} response into the same Map shape
     * {@link #parseModels} produces. The context length is namespaced by
     * model family ({@code "kimi-k2.context_length"},
     * {@code "glm.context_length"}, {@code "qwen3.context_length"}, …),
     * so we scan for any key ending in {@code .context_length} rather
     * than hardcoding the family list. Capability flags are fed through
     * the standard detectors with the {@code capabilities} array
     * surfaced in a minimal synthetic object, so the Ollama path shares
     * the OpenRouter/Anthropic logic.
     */
    @SuppressWarnings("java:S1168") // null means "drop this model from discovery"; empty map would be misread as a successful but empty result
    public static @Nullable Map<String, Object> parseOllamaShow(String id, JsonObject show) {
        // JCLAW-183 Tier 1: drop embedding-only models. Ollama's
        // /api/show capabilities array distinguishes "completion"
        // (chat-capable) from "embedding" (vector-only). When the array
        // is present and non-empty but lacks "completion", the model
        // can't serve chat — return null so {@code OllamaDiscoveryStrategy}
        // drops it from the discovery list. A model with no capabilities
        // array (older Ollama versions) is kept; the existing detectors
        // fall back to id-based heuristics.
        if (!ollamaCapabilitiesAllowChat(show)) return null;

        var forDetect = new JsonObject();
        forDetect.addProperty(KEY_ID, id);
        if (show.has(FIELD_CAPABILITIES) && show.get(FIELD_CAPABILITIES).isJsonArray()) {
            forDetect.add(FIELD_CAPABILITIES, show.getAsJsonArray(FIELD_CAPABILITIES));
        }
        var thinking = detectThinkingSupport(forDetect);
        var alwaysThinks = detectAlwaysThinks(forDetect);
        var vision = detectVisionSupport(forDetect);
        var audio = detectAudioSupport(forDetect);
        var tools = detectToolSupport(forDetect);

        return new ModelInfo(
                id, id,
                extractOllamaContextLength(show),
                0,
                thinking.confirmed(), thinking.fromProvider(),
                alwaysThinks.confirmed(), alwaysThinks.fromProvider(),
                vision.confirmed(), vision.fromProvider(),
                audio.confirmed(), audio.fromProvider(),
                // Video: the Ollama native path is image-only (JCLAW-208); a
                // Qwen-VL model served here stays supportsVideo=false.
                false, false,
                tools.confirmed(), tools.fromProvider(),
                // Ollama doesn't publish pricing via the API. -1 means "unset" —
                // the frontend skips these fields when saving.
                -1.0, -1.0, -1.0, -1.0,
                false).toMap();
    }

    /**
     * Return {@code false} when {@code show.capabilities} is a non-empty array
     * that lacks {@code "completion"} — i.e. the model is embedding-only and
     * not chat-capable. Returns {@code true} when capabilities are absent,
     * empty, or contain {@code "completion"}.
     */
    private static boolean ollamaCapabilitiesAllowChat(JsonObject show) {
        if (!show.has(FIELD_CAPABILITIES) || !show.get(FIELD_CAPABILITIES).isJsonArray()) return true;
        var caps = show.getAsJsonArray(FIELD_CAPABILITIES);
        if (caps.isEmpty()) return true;
        return arrayContainsAny(caps, true, FIELD_COMPLETION);
    }

    /**
     * Pick the first {@code *.context_length} entry under
     * {@code model_info} and return its integer value. Returns {@code 0}
     * when the response lacks a context-length entry — the frontend then
     * surfaces "unknown" and asks the user to fill it in. Malformed
     * numeric values are skipped; extraction continues so one
     * misbehaving family key doesn't mask a correct one.
     */
    public static int extractOllamaContextLength(JsonObject show) {
        if (show == null || !show.has(FIELD_MODEL_INFO) || !show.get(FIELD_MODEL_INFO).isJsonObject()) return 0;
        var mi = show.getAsJsonObject(FIELD_MODEL_INFO);
        var suffix = "." + FIELD_CONTEXT_LENGTH;
        for (var entry : mi.entrySet()) {
            if (entry.getKey().endsWith(suffix) && entry.getValue().isJsonPrimitive()) {
                try {
                    return entry.getValue().getAsInt();
                } catch (NumberFormatException _) {
                    // keep scanning in case a later family key is well-formed
                }
            }
        }
        return 0;
    }

    static String getString(JsonObject obj, String key, String defaultValue) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return defaultValue;
    }
}
