import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.mvc.Http;
import play.test.FunctionalTest;

import java.util.function.Supplier;

/**
 * The routes JCLAW-1266 promoted to a request-layer guard actually
 * refuse the agent principal — driven end to end, not proved structurally.
 *
 * <p>{@code CapabilityRulesTest} shows each of these actions <em>reaches</em> an
 * {@code isAgentOriginated} check. That is a property of the bytecode. This is the other half:
 * a request carrying the internal bearer token, which {@code AuthCheck} stamps as the agent
 * principal, gets a 403 with {@code operator_only} back. Without this, the guards existed with
 * nothing proving they fire.
 *
 * <p><b>Why every id below is bogus.</b> The guard is the first statement in each action, so a
 * 403 can only come from it. Remove the guard and the same request falls through to the id
 * lookup and answers 404 (or 400 for a body-less restore) — which is exactly what makes these
 * assertions fail if the guard is removed, and what keeps the test from ever running a real
 * backup, restore or delete against the shared database. Same idiom as
 * {@code ApiToolsControllerOperatorOnlyTest.MISSING_SKILL}.
 *
 * <p><b>Why the positive control matters.</b> Seven 403s in a row are also what a broken auth
 * layer produces. The last case logs in as the operator and reads the same controller, so the
 * refusals above are shown to be about the principal and not about the route being dead.
 */
class OperatorOnlyPromotionsTest extends FunctionalTest {

    private static final String OPERATOR_ONLY = "operator_only";
    private static final String NO_SUCH_BACKUP = "definitely-not-a-backup-id";
    private static final String NO_SUCH_ROW = "999999999";

    // No Fixtures.deleteDatabase(): nothing here writes a row, and play1 runs test classes
    // concurrently — wiping the shared H2 would only put this class in the way of its siblings.
    @BeforeEach
    void setup() {
        AuthFixture.seedAdminPassword("changeme");
    }

    @AfterEach
    void dropCookieJar() {
        clearCookies();
    }

    // --- database maintenance: the surface whose backup reaches every table -------------------

    @Test
    void agentPrincipalCannotDownloadADatabaseBackup() {
        // The exfiltration path the whole promotion was about: a backup archive carries the
        // Config table, and before JCLAW-1266 this route was hidden from the tool and open to
        // anyone else holding a bearer.
        var resp = asAgent(() -> GET(agentRequest(),
                "/api/system/database/backups/" + NO_SUCH_BACKUP + "/download"));
        assertRefusedAsOperatorOnly(resp);
    }

    @Test
    void agentPrincipalCannotDeleteADatabaseBackup() {
        var resp = asAgent(() -> DELETE(agentRequest(),
                "/api/system/database/backups/" + NO_SUCH_BACKUP));
        assertRefusedAsOperatorOnly(resp);
    }

    @Test
    void agentPrincipalCannotRestoreTheDatabase() {
        // No upload attached: ungated, this is a 400 for the missing file. Gated, it never gets
        // that far — which is the point, since a restore replaces every table at once.
        var resp = asAgent(() -> POST(agentRequest(), "/api/system/database/restore",
                "application/json", "{}"));
        assertRefusedAsOperatorOnly(resp);
    }

    // --- memories: cross-agent personal data ---------------------------------------------------

    @Test
    void agentPrincipalCannotDeleteAMemory() {
        var resp = asAgent(() -> DELETE(agentRequest(), "/api/memories/" + NO_SUCH_ROW));
        assertRefusedAsOperatorOnly(resp);
    }

    @Test
    void agentPrincipalCannotBulkDeleteMemories() {
        var resp = asAgent(() -> DELETE(agentRequest(), "/api/memories"));
        assertRefusedAsOperatorOnly(resp);
    }

    // --- channel bindings: bot tokens and signing secrets --------------------------------------

    @Test
    void agentPrincipalCannotUpdateATelegramBinding() {
        var resp = asAgent(() -> PUT(agentRequest(),
                "/api/channels/telegram/bindings/" + NO_SUCH_ROW, "application/json", "{}"));
        assertRefusedAsOperatorOnly(resp);
    }

    @Test
    void agentPrincipalCannotDeleteASlackBinding() {
        var resp = asAgent(() -> DELETE(agentRequest(),
                "/api/channels/slack/bindings/" + NO_SUCH_ROW));
        assertRefusedAsOperatorOnly(resp);
    }

    // --- the funnel: whether this instance is reachable from the internet ---------------------

    @Test
    void agentPrincipalCannotToggleTheTailscaleFunnel() {
        // The widest-blast-radius switch on the instance: Funnel publishes a whole port, so
        // flipping it on exposes every route this app serves to the public internet. It was
        // hidden from the tool only, which stopped the jclaw_api tool and nobody else.
        var resp = asAgent(() -> POST(agentRequest(), "/api/tailscale",
                "application/json", "{\"enabled\":true}"));
        assertRefusedAsOperatorOnly(resp);
    }

    @Test
    void theFunnelStatusReadIsRefusedToo() {
        // Was pinned open until JCLAW-1270, on the reasoning that a funnel URL is public by
        // definition. What closed it is the mechanism rather than a re-reading of the risk:
        // @AgentAccess has no level meaning "hidden from the tool but not refused", which is the
        // state that let JCLAW-1266's routes look protected while refusing nobody. Collapsing it
        // costs an agent a read nothing needed.
        var resp = asAgent(() -> GET(agentRequest(), "/api/tailscale"));
        assertRefusedAsOperatorOnly(resp);
    }

    // --- channel bindings: which agent receives a channel's messages -------------------------

    @Test
    void agentPrincipalCannotCreateAChannelBinding() {
        // Writing a binding redirects a channel's traffic to an agent of the caller's choosing,
        // which is how an agent grants itself an inbound surface it was never bound to.
        var resp = asAgent(() -> POST(agentRequest(), "/api/bindings",
                "application/json", "{}"));
        assertRefusedAsOperatorOnly(resp);
    }

    @Test
    void agentPrincipalCannotRepointAChannelBinding() {
        var resp = asAgent(() -> PUT(agentRequest(), "/api/bindings/" + NO_SUCH_ROW,
                "application/json", "{}"));
        assertRefusedAsOperatorOnly(resp);
    }

    @Test
    void agentPrincipalCannotDeleteAChannelBinding() {
        // Deleting one takes a channel offline without the operator noticing.
        var resp = asAgent(() -> DELETE(agentRequest(), "/api/bindings/" + NO_SUCH_ROW));
        assertRefusedAsOperatorOnly(resp);
    }

    @Test
    void theBindingListReadIsRefusedToo() {
        // Same collapse as the funnel status above: BindingView carries nothing secret, but the
        // level that used to express "visible, just not advertised" no longer exists (JCLAW-1270).
        var resp = asAgent(() -> GET(agentRequest(), "/api/bindings"));
        assertRefusedAsOperatorOnly(resp);
    }

    // --- positive control ----------------------------------------------------------------------

    @Test
    void theOperatorStillReachesThePromotedController() {
        // Same controller the refusals above target, read-only, via the session cookie. If this
        // fails too, the 403s above are an auth outage rather than the guard doing its job.
        login();
        var resp = GET("/api/system/database");
        assertIsOk(resp);
    }

    // --- seam, mirrored from ApiToolsControllerOperatorOnlyTest --------------------------------

    private static void assertRefusedAsOperatorOnly(Http.Response resp) {
        assertStatus(403, resp);
        assertTrue(getContent(resp).contains(OPERATOR_ONLY),
                "expected the " + OPERATOR_ONLY + " error code; got: " + getContent(resp));
    }

    private void login() {
        // FunctionalTest.savedCookies is JVM-global and login() does not clear what it inherits,
        // so a bearer response left in the jar by a concurrent class would carry the agent
        // principal straight into this operator session and 403 the positive control.
        clearCookies();
        var response = POST("/api/auth/login", "application/json",
                "{\"username\": \"admin\", \"password\": \"changeme\"}");
        assertIsOk(response);
    }

    /** Run an agent-principal request and wipe the shared cookie jar afterwards: AuthCheck's
     *  bearer branch answers with a Set-Cookie carrying the agent principal, and
     *  FunctionalTest.savedCookies is a private static folded into every later request — from
     *  any class, since play1 runs test classes concurrently. Left behind it 403s a sibling. */
    private Http.Response asAgent(Supplier<Http.Response> call) {
        try {
            return call.get();
        } finally {
            clearCookies();
        }
    }

    /** A request carrying the internal bearer token — indistinguishable from a jclaw_api call. */
    private static Http.Request agentRequest() {
        var request = newRequest();
        var token = AuthFixture.seedBearerToken();
        request.headers.put("authorization", new Http.Header("authorization", "Bearer " + token));
        return request;
    }
}
