package llm;

import com.google.gson.JsonParser;
import play.Logger;
import services.ConfigService;
import services.Tx;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers models that answered "I don't support tools" (JCLAW-1076).
 *
 * <p>JCLAW-1074 gates the {@code tools} array on what a provider <em>declares</em>,
 * which only Ollama does. Everywhere else a chat-only model is indistinguishable
 * from a capable one until it rejects a request, so the capability is learned
 * from that rejection instead: the failed call is retried without tools and the
 * answer recorded, turning a permanent failure into one wasted round trip.
 *
 * <p>The in-memory set is what makes the retry a one-off within a run; the
 * write-through to provider config is what stops it recurring after a restart.
 * The write is best-effort — a failure costs one wasted call next boot, so it is
 * logged rather than propagated into the user's turn.
 */
public final class ToolCapabilityMemo {

    private ToolCapabilityMemo() {}

    /** "provider::model" for every model observed to reject tools this run. */
    private static final Set<String> LEARNED = ConcurrentHashMap.newKeySet();

    /**
     * Provider error fragments meaning "this model cannot use tools". Matched
     * case-insensitively against the error body.
     *
     * <p>Deliberately narrow. A broad match on "tools" would swallow unrelated
     * 400s — a malformed tool schema, a tool-choice argument the provider
     * rejects — and retrying those without tools would silently drop the
     * caller's tools instead of surfacing a real bug.
     */
    private static final String[] SIGNATURES = {
        "does not support tools",
        "does not support tool",
        "doesn't support tools",
        "tools are not supported",
        "tool use is not supported",
        "tool calling is not supported",
        "unsupported parameter: 'tools'",
    };

    /** True when {@code message} is a provider's way of saying the model can't call tools. */
    public static boolean isToolsUnsupported(String message) {
        if (message == null) return false;
        var lower = message.toLowerCase();
        for (var sig : SIGNATURES) {
            if (lower.contains(sig)) return true;
        }
        return false;
    }

    /** True when the throwable, or anything it wraps, carries a tools-unsupported error. */
    public static boolean isToolsUnsupported(Throwable t) {
        for (var cur = t; cur != null; cur = cur.getCause()) {
            if (isToolsUnsupported(cur.getMessage())) return true;
        }
        return false;
    }

    /** True once {@link #record} has seen this model reject tools. */
    public static boolean isKnownIncapable(String provider, String modelId) {
        return LEARNED.contains(key(provider, modelId));
    }

    /**
     * Record that {@code modelId} rejects tools, and persist it to the provider's
     * model list so the next boot skips the doomed first call.
     */
    public static void record(String provider, String modelId) {
        if (provider == null || modelId == null) return;
        if (!LEARNED.add(key(provider, modelId))) return;
        Logger.info("[llm] %s/%s rejected tools; retrying without them and remembering",
                provider, modelId);
        try {
            persist(provider, modelId);
        } catch (Exception e) {
            // Costs one wasted call after the next restart, nothing more.
            Logger.warn("[llm] could not persist tool-incapability for %s/%s: %s",
                    provider, modelId, e.getMessage());
        }
    }

    /** Set {@code supportsTools:false} on this model inside the provider's stored list. */
    private static void persist(String provider, String modelId) {
        var key = "provider." + provider + ".models";
        Tx.run(() -> {
            var raw = ConfigService.get(key);
            if (raw == null || raw.isBlank()) return;
            var arr = JsonParser.parseString(raw).getAsJsonArray();
            var touched = false;
            for (var el : arr) {
                if (!el.isJsonObject()) continue;
                var obj = el.getAsJsonObject();
                var id = obj.has("id") && !obj.get("id").isJsonNull() ? obj.get("id").getAsString() : null;
                if (!modelId.equals(id)) continue;
                obj.addProperty("supportsTools", false);
                touched = true;
            }
            // Only the one field changes; every other model and every other
            // field is written back exactly as stored.
            if (touched) ConfigService.set(key, arr.toString());
        });
    }

    private static String key(String provider, String modelId) {
        return provider + "::" + modelId;
    }

    /** Test seam: drop everything learned this run. */
    public static void clearForTest() {
        LEARNED.clear();
    }
}
