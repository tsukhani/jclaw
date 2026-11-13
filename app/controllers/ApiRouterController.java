package controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import llm.ProviderRegistry;
import llm.routing.ClassifierHealth;
import llm.routing.ModelRouter;
import llm.routing.RouterPolicy;
import llm.routing.SubscriptionUsage;
import llm.routing.TaskClass;
import org.jspecify.annotations.Nullable;
import play.mvc.Controller;
import play.mvc.With;
import services.ConfigService;
import utils.ApiResponses;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static controllers.AgentAccess.Level.OPEN;
import static utils.GsonHolder.GSON;

/**
 * The model router's live state for Settings > Model Router (JCLAW-1222), and the main agent's way to change its
 * model lists. Every other router setting stays on the operator-only {@code POST /api/config}.
 */
@With(AuthCheck.class)
public class ApiRouterController extends Controller {

    private static final String READ_ROUTER = "read the model router's status and usage";

    /**
     * @param usageSource whether JClaw can read this provider's quota; without it only failed calls tell
     * @param windows     used fraction per quota window, empty when unknown
     */
    public record ProviderUsage(String provider, boolean prepaid, boolean usageSource,
                                Map<String, Double> windows, @Nullable String fetchedAt) {}

    /**
     * @param unavailable     providers or models a quota failure has benched, with the time each returns
     * @param classifierAlert the classifier's alert while it has failed {@link ClassifierHealth#ALERT_AFTER} turns in a
     *                        row, else null
     */
    public record RouterStatusResponse(boolean available, double downshiftAt, double exhaustedAt,
                                       List<ProviderUsage> providers, Map<String, String> unavailable,
                                       ClassifierHealth.@Nullable Alert classifierAlert) {}

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
    @AgentAccess(value = OPEN, reason = "the operator and main only (JF-292): the router's setup and each provider's "
            + "quota, read from the provider")
    public static void status() {
        RequestPrincipal.requireOperatorOrMain(READ_ROUTER);
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
        var classifier = policy.classifier();
        renderJSON(GSON.toJson(new RouterStatusResponse(policy.available(), policy.downshiftAt(),
                policy.exhaustedAt(), providers, unavailable,
                ClassifierHealth.alertFor(classifier != null ? classifier.describe() : null))));
    }

    /**
     * GET /api/router/usage/ollama-cloud?range=24h|7d|30d — Ollama Cloud's request counts over the
     * range, from the cache the router reads (JCLAW-1449). A missing key, a refused or failed read and
     * an unknown body all answer 200 with an empty reading whose {@code fetchedAt} is null.
     */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = CloudUsageResponse.class)))
    @Operation(summary = "Ollama Cloud request counts over the last 24 hours, 7 days or 30 days")
    @AgentAccess(value = OPEN, reason = "the operator and main only (JF-292): the instance's usage of a subscription")
    public static void ollamaCloudUsage(@Nullable String range) {
        RequestPrincipal.requireOperatorOrMain(READ_ROUTER);
        var span = SubscriptionUsage.Range.parse(range);
        if (span == null) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "range must be 24h, 7d, or 30d");
            return;
        }
        renderJSON(GSON.toJson(CloudUsageResponse.of(OLLAMA_CLOUD, span, SubscriptionUsage.history(OLLAMA_CLOUD, span))));
    }

    /** @param models the list in the order the router tries it; empty clears it, and the class then uses the chat list */
    public record RouterModelsRequest(List<RouterPolicy.Candidate> models) {}

    /** @param models the list as stored, each model once */
    public record RouterModelsResponse(String taskClass, List<RouterPolicy.Candidate> models) {}

    /**
     * PUT /api/router/models/{taskClass} — replace one class's model list. Writes the key Settings > Model Router
     * writes, through the same validation, and clears it on an empty list as the panel does.
     */
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = RouterModelsRequest.class)))
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = RouterModelsResponse.class)))
    @Operation(summary = "Replace a task class's model router list, tried in the order given: models is "
            + "[{provider, model}], [] clears it; taskClass is chat, summarize, agentic, reasoning or coding. "
            + "The operator and the main agent only")
    @AgentAccess(value = OPEN, reason = "writes only the five router.<class>.models keys, through RouterPolicy's "
            + "validation, and refuses every agent but main (RequestPrincipal.requireOperatorOrMain)")
    public static void updateModels(String taskClass) {
        // Before anything else, so an agent that may not write learns only that.
        RequestPrincipal.requireOperatorOrMain("change the model router's lists");
        var target = TaskClass.fromId(taskClass);
        if (target == null) {
            var classes = Arrays.stream(TaskClass.values()).map(TaskClass::id).collect(Collectors.joining(", "));
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST,
                    "taskClass must be one of %s; got '%s'.".formatted(classes, taskClass));
            throw ApiResponses.unreachable();
        }
        var body = JsonBodyReader.readJsonBody();
        var listed = body != null && body.has("models") && body.get("models").isJsonArray()
                ? RouterPolicy.entriesOf(body.getAsJsonArray("models")) : null;
        if (listed == null) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST,
                    "models must be a JSON array of {\"provider\":\"…\",\"model\":\"…\"} objects; send [] to clear the list.");
            throw ApiResponses.unreachable();
        }
        var key = RouterPolicy.modelsKey(target);
        if (listed.isEmpty()) {
            ConfigService.deleteWithSideEffects(key);
        } else {
            var rejection = ConfigService.setWithSideEffects(key, GSON.toJson(listed));
            // A 400, not POST /api/config's 403, so an agent tells a list to fix from a write it may not make.
            if (rejection != null) ApiResponses.error(400, ApiResponses.INVALID_REQUEST, rejection);
        }
        renderJSON(GSON.toJson(new RouterModelsResponse(target.id(), listed)));
    }
}
