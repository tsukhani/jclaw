package controllers;

import com.google.gson.Gson;
import play.mvc.Controller;
import play.mvc.With;
import services.ConfigService;
import services.EventLogger;
import services.TailscaleFunnel;

import static utils.GsonHolder.INSTANCE;

/**
 * Tailscale Funnel admin API (JCLAW-84): a single app-level toggle that exposes
 * this JClaw instance's HTTP port to the public internet over HTTPS, so
 * webhook-mode channels (e.g. the Slack Events API) get a reachable Request URL
 * without a manual tunnel. Funnel publishes a whole port — and JClaw serves all
 * channel webhooks on one port — so this is one switch per instance, not per
 * channel.
 */
@With(AuthCheck.class)
public class ApiTailscaleController extends Controller {

    private static final Gson gson = INSTANCE;
    private static final String CATEGORY = "tailscale";
    private static final String FIELD_ENABLED = "enabled";

    /**
     * @param enabled   whether the operator has switched Funnel on
     * @param available whether Tailscale is usable here right now (installed,
     *                  daemon up, logged in)
     * @param publicUrl the instance's public HTTPS base URL, or null
     * @param error     why it's unavailable, or null
     */
    public record StatusResponse(boolean enabled, boolean available, String publicUrl, String error) {}

    /** GET /api/tailscale — funnel toggle state + live availability / public URL. */
    public static void status() {
        var st = TailscaleFunnel.status();
        renderJSON(gson.toJson(new StatusResponse(
                TailscaleFunnel.isFunnelEnabled(), st.available(), st.publicUrl(), st.error())));
    }

    /** POST /api/tailscale — body {@code {"enabled":bool,"port":int?}}. Persists
     *  the toggle and (re)establishes or tears down the funnel accordingly. */
    @SuppressWarnings("java:S2259")
    public static void toggle() {
        var body = JsonBodyReader.readJsonBody();
        if (body == null || !body.has(FIELD_ENABLED)) badRequest();
        boolean enabled = body.get(FIELD_ENABLED).getAsBoolean();
        if (body.has("port") && !body.get("port").isJsonNull()) {
            ConfigService.set(TailscaleFunnel.CFG_PORT, String.valueOf(body.get("port").getAsInt()));
        }
        ConfigService.set(TailscaleFunnel.CFG_ENABLED, String.valueOf(enabled));

        if (enabled) {
            TailscaleFunnel.reconcile();
        } else {
            TailscaleFunnel.disable();
        }
        EventLogger.info(CATEGORY, "Funnel " + (enabled ? "enabled" : "disabled") + " via API");

        var st = TailscaleFunnel.status();
        renderJSON(gson.toJson(new StatusResponse(enabled, st.available(), st.publicUrl(), st.error())));
    }
}
