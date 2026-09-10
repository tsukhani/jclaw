package controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.jspecify.annotations.Nullable;
import play.mvc.Controller;
import play.mvc.With;
import utils.ApiResponses;
import utils.CircuitBreaker;
import utils.CircuitBreakers;

import java.util.ArrayList;

import static utils.GsonHolder.GSON;

/**
 * Circuit-breaker state and the operator's handle on it (JCLAW-1170).
 *
 * <p>An open breaker is a silent partial outage — every turned-away call returns fast and
 * clean, so latency improves and the error rate stays flat while real work fails. This is
 * where that state becomes visible, and the only place a human can move a breaker by hand.
 *
 * <p>Isolating is bounded, not latched: {@code trip} restarts the ordinary cooldown, so a
 * provider that is actually healthy closes itself again after {@code llm.breaker.wait-seconds}
 * rather than staying dark until somebody remembers it. That is also what makes the pair a
 * usable failover drill — isolate the primary, send a turn, watch it land on the secondary —
 * without a scheduled job spending tokens to rehearse the same thing forever.
 */
@With(AuthCheck.class)
public class ApiBreakersController extends Controller {

    /**
     * One breaker as the dashboard reads it.
     *
     * @param subsystem the registry-name prefix: {@code llm} or {@code mcp}
     * @param target    what the breaker guards — a provider name, an MCP server name
     * @param reason    what moved it into {@code state}; null before it has ever moved
     * @param manual    whether {@code reason} was an operator's decision rather than the
     *                  breaker acting on its own
     */
    public record BreakerView(String name, String subsystem, String target, String state,
                              int samples, int failures, int slowCalls,
                              double failureRate, double slowCallRate,
                              @Nullable String reason, boolean manual) {}

    public record BreakerRequest(String name) {}

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = BreakerView.class)))
    @Operation(summary = "Every registered circuit breaker with its state, window counters and last reason")
    public static void list() {
        var out = new ArrayList<BreakerView>();
        CircuitBreakers.snapshot().forEach((name, stats) -> out.add(view(name, stats)));
        renderJSON(GSON.toJson(out));
    }

    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = BreakerRequest.class)))
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = BreakerView.class)))
    @Operation(summary = "Force a breaker open, turning its subsystem's calls away for one cooldown")
    @ChatHidden("isolating a provider is operator maintenance; an agent tripping the breaker it is "
            + "talking through would cut its own turn off mid-sentence")
    public static void trip() {
        move(true);
    }

    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = BreakerRequest.class)))
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = BreakerView.class)))
    @Operation(summary = "Force a breaker closed and clear its outcome window")
    @ChatHidden("the other half of the operator's handle -- restoring a subsystem the operator "
            + "isolated is the operator's call, not the agent's")
    public static void reset() {
        move(false);
    }

    private static void move(boolean open) {
        var body = JsonBodyReader.readJsonBody();
        var name = body == null ? null : JsonBodyReader.requiredString(body, "name");
        if (name == null) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "A breaker name is required.");
            throw ApiResponses.unreachable();
        }
        var breaker = CircuitBreakers.find(name).orElse(null);
        if (breaker == null) {
            // Breakers are minted on first use, so an unknown name is as likely a subsystem
            // nobody has called yet as a typo. Say so rather than reporting a bare 404.
            ApiResponses.error(404, ApiResponses.NOT_FOUND, "No circuit breaker named '" + name
                    + "'. Known breakers: " + String.join(", ", CircuitBreakers.snapshot().keySet()));
            throw ApiResponses.unreachable();
        }
        if (open) breaker.trip();
        else breaker.reset();
        renderJSON(GSON.toJson(view(name, breaker.stats())));
    }

    private static BreakerView view(String name, CircuitBreaker.Stats stats) {
        var split = name.indexOf(':');
        var reason = stats.reason();
        return new BreakerView(
                name,
                split < 0 ? "" : name.substring(0, split),
                name.substring(split + 1),
                stats.state().name(),
                stats.samples(), stats.failures(), stats.slowCalls(),
                stats.failureRate(), stats.slowCallRate(),
                reason == null ? null : reason.name(),
                reason != null && reason.manual());
    }
}
