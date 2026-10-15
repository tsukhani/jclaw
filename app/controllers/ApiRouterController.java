package controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import llm.ProviderRegistry;
import llm.routing.ModelRouter;
import llm.routing.RouterPolicy;
import llm.routing.SubscriptionUsage;
import llm.routing.TaskClass;
import org.jspecify.annotations.Nullable;
import play.mvc.Controller;
import play.mvc.With;
import utils.ApiResponses;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static controllers.AgentAccess.Level.OPEN;
import static utils.GsonHolder.GSON;

/** The model router's live state for Settings > Model Router (JCLAW-1222). */
@With(AuthCheck.class)
public class ApiRouterController extends Controller {

    /**
     * @param usageSource whether JClaw can read this provider's quota; without it only failed calls tell
     * @param windows     used fraction per quota window, empty when unknown
     */
    public record ProviderUsage(String provider, boolean prepaid, boolean usageSource,
                                Map<String, Double> windows, @Nullable String fetchedAt) {}

    /** @param unavailable providers or models a quota failure has benched, with the time each returns */
    public record RouterStatusResponse(boolean available, double downshiftAt, double exhaustedAt,
                                       List<ProviderUsage> providers, Map<String, String> unavailable) {}

    /** @param partial true for the span still running, the newest */
    public record UsageBucket(String from, String until, long requestCount, boolean partial) {}

    /**
     * @param range     the span the counts cover: {@code 24h}, {@code 7d} or {@code 30d}
     * @param buckets   request counts over it, oldest first; empty when {@code fetchedAt} is null
     * @param fetchedAt when ollama.com answered, at most a minute ago; null when there is no such reading
     */
    public record CloudUsageResponse(String provider, String range, long total, List<UsageBucket> buckets,
                                     @Nullable String fetchedAt) {

        /** {@code usage} as the wire shape; a null {@code usage} is the empty reading for {@code requested}. */
        public static CloudUsageResponse of(String provider, SubscriptionUsage.Range requested,
                                            SubscriptionUsage.@Nullable Snapshot usage) {
            if (usage == null) return new CloudUsageResponse(provider, requested.param, 0, List.of(), null);
            var buckets = usage.buckets().stream()
                    .map(b -> new UsageBucket(b.from().toString(), b.until().toString(), b.requestCount(), b.partial()))
                    .toList();
            var reported = usage.range();
            return new CloudUsageResponse(provider, reported != null ? reported : requested.param, usage.total(),
                    buckets, usage.fetchedAt().toString());
        }
    }

    static final String OLLAMA_CLOUD = "ollama-cloud";

    /**
     * GET /api/router/status — whether the router can serve, its thresholds, and the usage of every
     * provider its lists name. Fetches a stale usage reading before answering, so the page shows
     * what the next routed turn will act on.
     */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = RouterStatusResponse.class)))
    @Operation(summary = "Model router availability, budget thresholds and per-provider quota usage")
    @AgentAccess(OPEN)
    public static void status() {
        var policy = RouterPolicy.load();
        var names = new LinkedHashSet<String>();
        for (var taskClass : TaskClass.values()) {
            policy.classes().getOrDefault(taskClass, List.of()).forEach(c -> names.add(c.provider()));
        }
        var providers = new ArrayList<ProviderUsage>();
        for (var name : names) {
            var provider = ProviderRegistry.get(name);
            if (provider == null) continue;
            var config = provider.config();
            var usage = SubscriptionUsage.fresh(config);
            providers.add(new ProviderUsage(name, ModelRouter.isPrepaid(config), SubscriptionUsage.hasUsageSource(config),
                    usage != null ? usage.windows() : Map.of(),
                    usage != null ? usage.fetchedAt().toString() : null));
        }
        var unavailable = new LinkedHashMap<String, String>();
        SubscriptionUsage.unavailableMarks().forEach((k, until) -> unavailable.put(k, until.toString()));
        renderJSON(GSON.toJson(new RouterStatusResponse(policy.available(), policy.downshiftAt(),
                policy.exhaustedAt(), providers, unavailable)));
    }

    /**
     * GET /api/router/usage/ollama-cloud?range=24h|7d|30d — Ollama Cloud's request counts over the
     * range, from the cache the router reads (JCLAW-1449). A missing key, a refused or failed read and
     * an unknown body all answer 200 with an empty reading whose {@code fetchedAt} is null.
     */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = CloudUsageResponse.class)))
    @Operation(summary = "Ollama Cloud request counts over the last 24 hours, 7 days or 30 days")
    @AgentAccess(OPEN)
    public static void ollamaCloudUsage(@Nullable String range) {
        var span = SubscriptionUsage.Range.parse(range);
        if (span == null) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "range must be 24h, 7d, or 30d");
            return;
        }
        renderJSON(GSON.toJson(CloudUsageResponse.of(OLLAMA_CLOUD, span, SubscriptionUsage.history(OLLAMA_CLOUD, span))));
    }
}
