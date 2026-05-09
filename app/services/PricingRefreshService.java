package services;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.Config;
import play.Logger;
import utils.GsonHolder;
import utils.HttpFactories;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Backfills missing pricing data on operator-configured models from
 * LiteLLM's community-maintained {@code model_prices_and_context_window.json}
 * (JCLAW-28 follow-up).
 *
 * <p>Why LiteLLM and not the providers themselves: most direct provider APIs
 * don't expose pricing in their {@code /v1/models} responses. OpenAI, Anthropic
 * direct, Google direct, Mistral direct all return bare model lists with
 * no cost data. OpenRouter and Together AI are the exceptions; their entries
 * are populated at discovery time and skip this service entirely.
 *
 * <p>LiteLLM hand-maintains a JSON catalog the entire community ecosystem
 * relies on (Vercel AI SDK, Helicone, Langfuse, etc.). It's the de-facto
 * standard answer to "where do I get OpenAI pricing programmatically." The
 * file lives at the canonical raw URL below; updates ship via PR.
 *
 * <p>Two design constraints worth being explicit about:
 *
 * <ol>
 *   <li><b>Operator-set values are sacrosanct.</b> A field that already has
 *       a non-{@code -1} value — including explicit {@code 0} for a known-free
 *       tier — is never overwritten. Operators can audit the saved
 *       {@code provider.{name}.models} JSON and trust it reflects their
 *       intent.</li>
 *   <li><b>Off by default.</b> The Personal Edition is meant for general
 *       public use; phoning home to GitHub on a schedule is the kind of
 *       deployment-posture change that should be opt-in. The {@code refresh()}
 *       entrypoint short-circuits when {@code pricing.refresh.enabled != "true"}.</li>
 * </ol>
 *
 * <p>Free / local providers (Ollama, LM Studio, loadtest-mock) are skipped —
 * LiteLLM doesn't price them and lookups would only produce miss-noise in
 * the logs.
 */
public final class PricingRefreshService {

    private PricingRefreshService() {}

    /** Canonical LiteLLM pricing manifest. Public, no auth, ~500KB. */
    public static final String LITELLM_URL =
            "https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json";

    /** Network timeout for the catalog fetch. Generous; the file is occasionally slow on cold cache hits. */
    private static final int FETCH_TIMEOUT_SECONDS = 30;

    /**
     * Provider names this service does NOT consult LiteLLM for. Free-tier
     * and local providers don't have pricing the operator could refresh.
     * Enumerated rather than pattern-matched so adding a new provider is
     * an explicit decision.
     */
    private static final java.util.Set<String> SKIPPED_PROVIDERS = java.util.Set.of(
            "ollama-cloud", "ollama-local", "lm-studio", "loadtest-mock"
    );

    /**
     * Outcome of a refresh run. {@code skipped} is true when the toggle is
     * off; the controller surfaces this distinct from "ran but updated nothing"
     * so the operator's manual-trigger button can show a useful message.
     */
    public record RefreshResult(
            boolean skipped,
            int providersScanned,
            int modelsUpdated,
            List<String> warnings
    ) {}

    /**
     * Top-level entrypoint: read the toggle, fetch the catalog, apply.
     *
     * <p>Returns a result summary even on the disabled / network-failure
     * paths so callers (the nightly job and the manual-trigger endpoint)
     * can render a coherent log line / response without throwing.
     */
    public static RefreshResult refresh() {
        var enabled = ConfigService.get("pricing.refresh.enabled");
        if (!"true".equalsIgnoreCase(enabled)) {
            return new RefreshResult(true, 0, 0, List.of());
        }
        var catalog = fetchLiteLlmCatalog();
        if (catalog == null) {
            return new RefreshResult(false, 0, 0,
                    List.of("Failed to fetch LiteLLM pricing catalog from " + LITELLM_URL));
        }
        return applyCatalog(catalog);
    }

    /**
     * Fetch and parse LiteLLM's pricing manifest. Returns {@code null} on any
     * network or parse failure — caller treats null as "skip this run, try
     * again next schedule."
     */
    public static JsonObject fetchLiteLlmCatalog() {
        try {
            var req = new okhttp3.Request.Builder()
                    .url(LITELLM_URL)
                    .header("Accept", "application/json")
                    .get()
                    .build();
            var call = HttpFactories.general().newCall(req);
            call.timeout().timeout(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            try (var response = call.execute()) {
                if (response.code() != 200 || response.body() == null) {
                    Logger.warn("LiteLLM pricing fetch failed: HTTP %s", response.code());
                    return null;
                }
                var body = response.body().string();
                return JsonParser.parseString(body).getAsJsonObject();
            }
        } catch (Exception e) {
            Logger.warn(e, "LiteLLM pricing fetch failed");
            return null;
        }
    }

    /**
     * Apply a parsed LiteLLM catalog to every operator-configured provider's
     * model list. Each {@code provider.{name}.models} config row is read,
     * iterated, fields filled where missing, and written back as a single
     * atomic upsert.
     *
     * <p>Visible for testing — pass a hand-built JsonObject mimicking
     * LiteLLM's shape to exercise the apply logic without network.
     */
    public static RefreshResult applyCatalog(JsonObject catalog) {
        var providerKeys = Tx.run(() ->
                Config.find("key LIKE ?1", "provider.%.models").<Config>fetch()
                        .stream()
                        .map(c -> c.key)
                        .toList()
        );
        var warnings = new ArrayList<String>();
        int totalUpdated = 0;
        int scanned = 0;
        for (var key : providerKeys) {
            // key shape: provider.{name}.models — extract name
            var parts = key.split("\\.");
            if (parts.length != 3) continue;
            var providerName = parts[1];
            if (SKIPPED_PROVIDERS.contains(providerName)) continue;
            scanned++;
            try {
                int updated = applyToProvider(providerName, catalog);
                totalUpdated += updated;
                Logger.info("LiteLLM refresh: filled prices on %d model%s for %s",
                        updated, updated == 1 ? "" : "s", providerName);
            } catch (Exception e) {
                Logger.error(e, "LiteLLM refresh failed for provider %s", providerName);
                warnings.add(providerName + ": " + e.getMessage());
            }
        }
        return new RefreshResult(false, scanned, totalUpdated, warnings);
    }

    /**
     * Apply pricing to one provider's models config. Reads the JSON, fills
     * missing prices on each model, writes back if any field was updated.
     * Returns the count of models that received at least one new price.
     */
    private static int applyToProvider(String providerName, JsonObject catalog) {
        var configKey = "provider." + providerName + ".models";
        var raw = ConfigService.get(configKey);
        if (raw == null || raw.isBlank()) return 0;

        JsonArray models;
        try {
            models = JsonParser.parseString(raw).getAsJsonArray();
        } catch (Exception _) {
            return 0;
        }

        int modelsUpdated = 0;
        for (var el : models) {
            if (!el.isJsonObject()) continue;
            var model = el.getAsJsonObject();
            var id = model.has("id") && !model.get("id").isJsonNull()
                    ? model.get("id").getAsString() : null;
            if (id == null || id.isBlank()) continue;

            var litellmEntry = lookupCatalog(catalog, id);
            if (litellmEntry == null) continue;

            boolean changed = false;
            changed |= fillIfMissing(model, "promptPrice",
                    extractPerMillion(litellmEntry, "input_cost_per_token"));
            changed |= fillIfMissing(model, "completionPrice",
                    extractPerMillion(litellmEntry, "output_cost_per_token"));
            changed |= fillIfMissing(model, "cachedReadPrice",
                    extractPerMillion(litellmEntry, "cache_read_input_token_cost"));
            changed |= fillIfMissing(model, "cacheWritePrice",
                    extractPerMillion(litellmEntry, "cache_creation_input_token_cost"));

            if (changed) modelsUpdated++;
        }

        if (modelsUpdated > 0) {
            ConfigService.set(configKey, GsonHolder.INSTANCE.toJson(models));
        }
        return modelsUpdated;
    }

    /**
     * Look up a model id in LiteLLM's catalog, trying progressively looser
     * normalizations. Operators see provider-specific id shapes
     * ({@code openai/gpt-4o} via OpenRouter, {@code gpt-4o} direct,
     * {@code gpt-4o-2024-08-06} version-pinned); LiteLLM keys on the
     * canonical bare form.
     *
     * <p>Returns the matching entry's JsonObject, or {@code null} on miss.
     */
    public static JsonObject lookupCatalog(JsonObject catalog, String id) {
        // Try the id as-is, then progressively looser forms.
        var candidates = new ArrayList<String>();
        candidates.add(id);
        // Strip provider prefix (e.g. "openai/gpt-4o" → "gpt-4o").
        if (id.contains("/")) {
            candidates.add(id.substring(id.lastIndexOf('/') + 1));
        }
        // Strip version-date suffix (e.g. "gpt-4o-2024-08-06" → "gpt-4o").
        candidates.add(stripVersionSuffix(id));
        if (id.contains("/")) {
            candidates.add(stripVersionSuffix(id.substring(id.lastIndexOf('/') + 1)));
        }
        // Strip Ollama-style :tag (e.g. "kimi-k2.5:latest" → "kimi-k2.5").
        if (id.contains(":")) {
            candidates.add(id.substring(0, id.indexOf(':')));
        }
        for (var c : candidates) {
            if (c == null || c.isBlank()) continue;
            if (catalog.has(c) && catalog.get(c).isJsonObject()) {
                return catalog.getAsJsonObject(c);
            }
        }
        return null;
    }

    /** Delegate to the canonical helper to keep one source of truth on id normalization. */
    private static String stripVersionSuffix(String id) {
        return ModelDiscoveryService.stripVersionSuffix(id);
    }

    /** Read a per-token price from LiteLLM and convert to JClaw's per-million convention. */
    private static double extractPerMillion(JsonObject entry, String key) {
        if (!entry.has(key) || entry.get(key).isJsonNull()) return -1;
        try {
            return entry.get(key).getAsDouble() * 1_000_000;
        } catch (NumberFormatException _) {
            return -1;
        }
    }

    /**
     * Fill a price field on the model JSON only if the existing value is
     * absent or {@code -1}. Returns true when a write happened — caller
     * tallies this to know whether the provider's models JSON needs to be
     * persisted.
     *
     * <p>Operator-set values (including explicit {@code 0} for known-free
     * tiers) survive because the missing-or-{@code -1} guard treats both
     * as "no value yet."
     */
    private static boolean fillIfMissing(JsonObject model, String field, double newValue) {
        if (newValue < 0) return false;
        if (model.has(field) && !model.get(field).isJsonNull()) {
            try {
                if (model.get(field).getAsDouble() != -1) return false;
            } catch (NumberFormatException _) {}
        }
        model.addProperty(field, newValue);
        return true;
    }
}
