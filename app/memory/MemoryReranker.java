package memory;

import com.google.gson.JsonParser;
import llm.LlmTypes.ChatMessage;
import llm.ProviderRegistry;
import org.jspecify.annotations.Nullable;
import services.ConfigService;
import services.EventLogger;
import services.SessionCompactor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * JCLAW-527: optional cross-encoder rerank over the fused recall shortlist.
 * RRF fusion ranks by <i>where</i> each leg placed a candidate, never reading
 * the query and candidate together; a cross-encoder does exactly that joint
 * read. JClaw has no embedded cross-encoder model, so the implementation is an
 * LLM listwise rank: one call sees the query plus the numbered shortlist and
 * returns the index order (the standard LLM-as-cross-encoder shape).
 *
 * <p>Off by default ({@code memory.rerank.enabled}) — it adds one billable LLM
 * round-trip to every recall. When anything goes wrong (provider missing,
 * malformed output, call failure) the fused order stands: rerank is a quality
 * refinement, never an availability dependency (fail-open, the same stance as
 * every other memory-path degradation).
 *
 * <p>Functional seam ({@link RankCall} + {@link #setRankCallForTest}) mirrors
 * {@code MemoryAutoCapture.Extractor}: tests inject a canned index-array
 * response, no provider needed. The static override is only safe under the
 * {@code LuceneTestSync} lock — the store tests that exercise recall already
 * hold it.
 */
public final class MemoryReranker {

    private static final String EVENT_CATEGORY_MEMORY = "memory";

    private static final String INSTRUCTIONS = """
            You are a relevance ranker. Given a query and a numbered list of \
            memory snippets, order the snippet indices from most to least \
            relevant to the query. Respond with ONLY a JSON array of the \
            0-based indices, e.g. [2,0,1]. Include every index exactly once.""";

    /**
     * Functional seam for the rank LLM call (mirrors
     * {@code MemoryAutoCapture.Extractor}); tests inject a canned JSON string.
     */
    @FunctionalInterface
    public interface RankCall {
        @Nullable String rank(List<ChatMessage> messages) throws Exception;
    }

    private static volatile @Nullable RankCall rankCallOverride;

    /** Test-only: install (or clear with {@code null}) a canned rank call. */
    public static void setRankCallForTest(@Nullable RankCall override) {
        rankCallOverride = override;
    }

    private MemoryReranker() {}

    /** Config keys backing the Settings Memory panel's Reranker subsection. */
    public static final String KEY_PREFIX = "memory.rerank.";
    public static final String KEY_ENABLED = KEY_PREFIX + "enabled";
    public static final String KEY_PROVIDER = KEY_PREFIX + "provider";
    public static final String KEY_MODEL = KEY_PREFIX + "model";

    /**
     * Whether a rerank pass should run: enabled by config, or a test override
     * is installed (the override implies "active" the same way
     * {@code JpaMemoryStore.embedderOverride} implies canned embeddings).
     */
    public static boolean active() {
        return rankCallOverride != null
                || ConfigService.getBoolean(KEY_ENABLED, false);
    }

    /**
     * Rerank {@code shortlist} texts against {@code query}, returning the new
     * order as indices into the shortlist. Fail-open: any failure — no
     * provider, no model configured, malformed output — returns the identity
     * order so callers keep the fused ranking. Indices the model omits are
     * appended in their original (fused) order; out-of-range or duplicate
     * indices are dropped.
     */
    public static List<Integer> rerank(String query, List<String> shortlist) {
        var identity = identityOrder(shortlist.size());
        if (shortlist.size() < 2) return identity;
        try {
            var call = rankCallOverride != null ? rankCallOverride : productionCall();
            if (call == null) return identity;
            var messages = List.of(
                    ChatMessage.system(INSTRUCTIONS),
                    ChatMessage.user(render(query, shortlist)));
            var raw = call.rank(messages);
            if (raw == null) return identity;
            return parseOrder(raw, shortlist.size());
        } catch (Exception e) {
            EventLogger.warn(EVENT_CATEGORY_MEMORY,
                    "Memory rerank failed, keeping fused order: %s".formatted(e.getMessage()));
            return identity;
        }
    }

    /**
     * Production rank call via the configured provider and {@link #KEY_MODEL}.
     * Null (skip rerank) when either is missing — the model has no agent-default
     * fallback because recall runs without an agent's provider context (unlike
     * auto-capture).
     *
     * <p>Requires {@link #KEY_PROVIDER} rather than falling back to the registry
     * primary. {@link #render} puts the full shortlist text into the prompt, so a
     * rerank ships memory content to whatever serves it — the same exposure that
     * restricts embeddings to a local provider (JCLAW-939). Defaulting to the
     * primary would send that text to whichever endpoint happened to sort first,
     * routinely a cloud one. {@code ConfigService} rejects a non-local value for
     * this key, so a configured provider is a local provider.
     */
    private static @Nullable RankCall productionCall() {
        var providerName = ConfigService.get(KEY_PROVIDER, "").trim();
        var model = ConfigService.get(KEY_MODEL, "");
        if (providerName.isBlank() || model.isBlank()) {
            EventLogger.warn(EVENT_CATEGORY_MEMORY,
                    "%s is on but %s is not set — skipping rerank"
                            .formatted(KEY_ENABLED, providerName.isBlank() ? KEY_PROVIDER : KEY_MODEL));
            return null;
        }
        var provider = ProviderRegistry.get(providerName);
        if (provider == null) {
            EventLogger.warn(EVENT_CATEGORY_MEMORY,
                    "%s names '%s', which is not a configured provider — skipping rerank"
                            .formatted(KEY_PROVIDER, providerName));
            return null;
        }
        int maxTokens = ConfigService.getInt(KEY_PREFIX + "maxTokens", 256);
        return msgs -> SessionCompactor.firstChoiceText(
                provider.chat(model, msgs, List.of(), maxTokens, null, null));
    }

    private static String render(String query, List<String> shortlist) {
        var sb = new StringBuilder("Query: ").append(query).append("\n\nSnippets:\n");
        for (int i = 0; i < shortlist.size(); i++) {
            sb.append(i).append(": ").append(shortlist.get(i)).append('\n');
        }
        return sb.toString();
    }

    /**
     * Parse the model's index array into a complete permutation of
     * {@code [0, size)}: valid unique indices in model order first, then any
     * the model omitted, in original order. Non-JSON or non-array output yields
     * the identity order. Public because the test tree compiles into the
     * default package.
     */
    public static List<Integer> parseOrder(String raw, int size) {
        var seen = new LinkedHashSet<Integer>();
        try {
            var root = JsonParser.parseString(MemoryAutoCapture.stripFences(raw.strip()));
            if (root.isJsonArray()) {
                for (var el : root.getAsJsonArray()) {
                    int idx = el.getAsInt();
                    if (idx >= 0 && idx < size) seen.add(idx);
                }
            }
        } catch (Exception _) {
            return identityOrder(size);
        }
        if (seen.isEmpty()) return identityOrder(size);
        var out = new ArrayList<>(seen);
        for (int i = 0; i < size; i++) {
            if (!seen.contains(i)) out.add(i);
        }
        return out;
    }

    private static List<Integer> identityOrder(int size) {
        var out = new ArrayList<Integer>(size);
        for (int i = 0; i < size; i++) out.add(i);
        return out;
    }
}
