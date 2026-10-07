import channels.WhatsAppSubscription;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.HttpFactories;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * HTTP-level coverage for {@link WhatsAppSubscription} (JCLAW-1410) against the
 * Graph bodies the ticket measured. Uses the {@code apiBase} overloads, which never
 * consult the static override {@code WhatsAppSubscriptionControllerTest} installs.
 */
class WhatsAppSubscriptionTest extends UnitTest {

    private static final String BASE = "http://graph.invalid/v21.0/";
    private static final String TOKEN = "tok-SECRET-1410";

    private static final String HEALTH = """
            {"health_status":{"can_send_message":"BLOCKED","entities":[
              {"entity_type":"PHONE_NUMBER","id":"111","can_send_message":"AVAILABLE","can_receive_call_sip":"BLOCKED","errors":[{"error_code":138024,"error_description":"...","possible_solution":"..."}]},
              {"entity_type":"WABA","id":"222","can_send_message":"BLOCKED","errors":[{"error_code":141006,"error_description":"...","possible_solution":"..."}]},
              {"entity_type":"BUSINESS","id":"333","can_send_message":"AVAILABLE"},
              {"entity_type":"APP","id":"444","can_send_message":"AVAILABLE"}]},
             "id":"111"}""";

    private static final String HEALTH_APP_FIRST = """
            {"health_status":{"entities":[
              {"entity_type":"APP","id":"444"},
              {"entity_type":"PHONE_NUMBER","id":"111"},
              {"entity_type":"WABA","id":"222"}]},"id":"111"}""";

    private static final String APPS_SUBSCRIBED = """
            {"data":[
              {"whatsapp_business_api_data":{"category":"Education","link":"https://www.facebook.com/games/?app_id=444","name":"My App","id":"444"}},
              {"whatsapp_business_api_data":{"category":"Business","link":"https://www.facebook.com/games/?app_id=555","name":"WA DevX Webhook Events 1P App","id":"555"}}]}""";

    private static final String APPS_SUBSCRIBED_SECOND = """
            {"data":[
              {"whatsapp_business_api_data":{"category":"Business","link":"https://www.facebook.com/games/?app_id=555","name":"WA DevX Webhook Events 1P App","id":"555"}},
              {"whatsapp_business_api_data":{"category":"Education","link":"https://www.facebook.com/games/?app_id=444","name":"My App","id":"444"}}]}""";

    private static final String APPS_NOT_SUBSCRIBED = """
            {"data":[
              {"whatsapp_business_api_data":{"category":"Business","link":"https://www.facebook.com/games/?app_id=555","name":"WA DevX Webhook Events 1P App","id":"555"}}]}""";

    private static final String GRAPH_400 = """
            {"error":{"message":"(#100) Tried accessing nonexisting field (whatsapp_business_account)","type":"OAuthException","code":100,"fbtrace_id":"..."}}""";

    private static final String GRAPH_403 = """
            {"error":{"message":"(#200) Permissions error","type":"OAuthException","code":200,"fbtrace_id":"..."}}""";

    private static final String HEALTH_PATH = "GET /v21.0/111";
    private static final String APPS_GET = "GET /v21.0/222/subscribed_apps";
    private static final String APPS_POST = "POST /v21.0/222/subscribed_apps";

    private record Canned(int code, String body) {}

    private final Map<String, Canned> answers = new LinkedHashMap<>();
    private final List<Request> requests = new ArrayList<>();
    private boolean failTransport;

    // ===== check =====

    @Test
    void subscribedWhenTheAppIsListed() {
        answers.put(HEALTH_PATH, new Canned(200, HEALTH));
        answers.put(APPS_GET, new Canned(200, APPS_SUBSCRIBED));

        var state = run(() -> WhatsAppSubscription.check("111", TOKEN, BASE));

        assertEquals(new WhatsAppSubscription.Subscribed("222", "444"), state);
        assertEquals(2, requests.size());
        var health = requests.get(0);
        assertEquals("GET", health.method());
        assertEquals("/v21.0/111", health.url().encodedPath());
        assertEquals("health_status", health.url().queryParameter("fields"));
        assertEquals("Bearer " + TOKEN, health.header("Authorization"));
        var apps = requests.get(1);
        assertEquals("GET", apps.method());
        assertEquals("/v21.0/222/subscribed_apps", apps.url().encodedPath());
        assertEquals("Bearer " + TOKEN, apps.header("Authorization"));
    }

    @Test
    void subscribedWhenTheAppIsNotTheFirstEntry() {
        answers.put(HEALTH_PATH, new Canned(200, HEALTH));
        answers.put(APPS_GET, new Canned(200, APPS_SUBSCRIBED_SECOND));

        assertEquals(new WhatsAppSubscription.Subscribed("222", "444"),
                run(() -> WhatsAppSubscription.check("111", TOKEN, BASE)));
    }

    @Test
    void entitiesInAnotherOrderStillResolve() {
        answers.put(HEALTH_PATH, new Canned(200, HEALTH_APP_FIRST));
        answers.put(APPS_GET, new Canned(200, APPS_SUBSCRIBED));

        assertEquals(new WhatsAppSubscription.Subscribed("222", "444"),
                run(() -> WhatsAppSubscription.check("111", TOKEN, BASE)));
    }

    @Test
    void notSubscribedWhenOnlyMetasDashboardAppIsListed() {
        answers.put(HEALTH_PATH, new Canned(200, HEALTH));
        answers.put(APPS_GET, new Canned(200, APPS_NOT_SUBSCRIBED));

        assertEquals(new WhatsAppSubscription.NotSubscribed("222", "444"),
                run(() -> WhatsAppSubscription.check("111", TOKEN, BASE)));
    }

    @Test
    void notSubscribedOnAnEmptyList() {
        answers.put(HEALTH_PATH, new Canned(200, HEALTH));
        answers.put(APPS_GET, new Canned(200, "{\"data\":[]}"));

        assertEquals(new WhatsAppSubscription.NotSubscribed("222", "444"),
                run(() -> WhatsAppSubscription.check("111", TOKEN, BASE)));
    }

    @Test
    void healthStatusErrorIsUnknownWithGraphMessageAndNoSecondCall() {
        answers.put(HEALTH_PATH, new Canned(400, GRAPH_400));

        var state = run(() -> WhatsAppSubscription.check("111", TOKEN, BASE));

        assertEquals(new WhatsAppSubscription.Unknown(
                "(#100) Tried accessing nonexisting field (whatsapp_business_account)"), state);
        assertEquals(1, requests.size());
    }

    @Test
    void subscribedAppsErrorIsUnknownWithGraphMessage() {
        answers.put(HEALTH_PATH, new Canned(200, HEALTH));
        answers.put(APPS_GET, new Canned(403, GRAPH_403));

        var state = run(() -> WhatsAppSubscription.check("111", TOKEN, BASE));

        assertUnknownContaining(state, "(#200) Permissions error");
    }

    @Test
    void unparseableHealthStatusIsUnknown() {
        answers.put(HEALTH_PATH, new Canned(200, "not json"));

        assertUnknownContaining(run(() -> WhatsAppSubscription.check("111", TOKEN, BASE)), "");
        assertEquals(1, requests.size());
    }

    @Test
    void unparseableSubscribedAppsIsUnknown() {
        answers.put(HEALTH_PATH, new Canned(200, HEALTH));
        answers.put(APPS_GET, new Canned(200, "not json"));

        assertUnknownContaining(run(() -> WhatsAppSubscription.check("111", TOKEN, BASE)), "");
    }

    @Test
    void subscribedAppsWithoutDataIsUnknown() {
        answers.put(HEALTH_PATH, new Canned(200, HEALTH));
        answers.put(APPS_GET, new Canned(200, "{}"));

        assertUnknownContaining(run(() -> WhatsAppSubscription.check("111", TOKEN, BASE)), "");
    }

    @Test
    void noWabaEntityIsUnknownWithNoSecondCall() {
        answers.put(HEALTH_PATH, new Canned(200,
                "{\"health_status\":{\"entities\":[{\"entity_type\":\"APP\",\"id\":\"444\"}]},\"id\":\"111\"}"));

        assertUnknownContaining(run(() -> WhatsAppSubscription.check("111", TOKEN, BASE)), "Business Account");
        assertEquals(1, requests.size());
    }

    @Test
    void noAppEntityIsUnknownWithNoSecondCall() {
        answers.put(HEALTH_PATH, new Canned(200,
                "{\"health_status\":{\"entities\":[{\"entity_type\":\"WABA\",\"id\":\"222\"}]},\"id\":\"111\"}"));

        assertUnknownContaining(run(() -> WhatsAppSubscription.check("111", TOKEN, BASE)), "app");
        assertEquals(1, requests.size());
    }

    @Test
    void noHealthStatusIsUnknown() {
        answers.put(HEALTH_PATH, new Canned(200, "{\"id\":\"111\"}"));

        assertUnknownContaining(run(() -> WhatsAppSubscription.check("111", TOKEN, BASE)), "");
        assertEquals(1, requests.size());
    }

    @Test
    void transportErrorIsUnknownAndDoesNotThrow() {
        failTransport = true;

        assertUnknownContaining(run(() -> WhatsAppSubscription.check("111", TOKEN, BASE)),
                "Graph request failed");
    }

    @Test
    void blankCredentialsAreUnknownWithoutHttp() {
        run(() -> {
            assertTrue(WhatsAppSubscription.check("", TOKEN, BASE) instanceof WhatsAppSubscription.Unknown);
            assertTrue(WhatsAppSubscription.check("111", " ", BASE) instanceof WhatsAppSubscription.Unknown);
            assertTrue(WhatsAppSubscription.check(null, null, BASE) instanceof WhatsAppSubscription.Unknown);
            return null;
        });
        assertEquals(0, requests.size());
    }

    // ===== subscribe =====

    @Test
    void subscribePostsAnEmptyBodyAndSucceeds() throws IOException {
        answers.put(HEALTH_PATH, new Canned(200, HEALTH));
        answers.put(APPS_POST, new Canned(200, "{\"success\":true}"));

        var result = run(() -> WhatsAppSubscription.subscribe("111", TOKEN, BASE));

        assertEquals(new WhatsAppSubscription.Done("222"), result);
        assertEquals(2, requests.size());
        var post = requests.get(1);
        assertEquals("POST", post.method());
        assertEquals("/v21.0/222/subscribed_apps", post.url().encodedPath());
        assertEquals("Bearer " + TOKEN, post.header("Authorization"));
        var body = post.body();
        assertNotNull(body);
        var buffer = new Buffer();
        body.writeTo(buffer);
        assertEquals(0L, buffer.size(), "the subscribe POST carries no body");
    }

    @Test
    void refusedSubscribeCarriesMetasMessage() {
        answers.put(HEALTH_PATH, new Canned(200, HEALTH));
        answers.put(APPS_POST, new Canned(403, GRAPH_403));

        var result = run(() -> WhatsAppSubscription.subscribe("111", TOKEN, BASE));

        assertEquals(new WhatsAppSubscription.Failed("(#200) Permissions error"), result);
    }

    @Test
    void successFalseIsFailed() {
        answers.put(HEALTH_PATH, new Canned(200, HEALTH));
        answers.put(APPS_POST, new Canned(200, "{\"success\":false}"));

        assertFailed(run(() -> WhatsAppSubscription.subscribe("111", TOKEN, BASE)), "");
    }

    @Test
    void unparseableSubscribeIsFailed() {
        answers.put(HEALTH_PATH, new Canned(200, HEALTH));
        answers.put(APPS_POST, new Canned(200, "not json"));

        assertFailed(run(() -> WhatsAppSubscription.subscribe("111", TOKEN, BASE)), "");
    }

    @Test
    void healthStatusFailureMeansNoPost() {
        answers.put(HEALTH_PATH, new Canned(400, GRAPH_400));

        assertFailed(run(() -> WhatsAppSubscription.subscribe("111", TOKEN, BASE)), "(#100)");
        assertEquals(1, requests.size());
    }

    @Test
    void noWabaEntityMeansNoPost() {
        answers.put(HEALTH_PATH, new Canned(200,
                "{\"health_status\":{\"entities\":[{\"entity_type\":\"APP\",\"id\":\"444\"}]},\"id\":\"111\"}"));

        assertFailed(run(() -> WhatsAppSubscription.subscribe("111", TOKEN, BASE)), "Business Account");
        assertEquals(1, requests.size());
    }

    @Test
    void transportErrorOnSubscribeIsFailed() {
        failTransport = true;

        assertFailed(run(() -> WhatsAppSubscription.subscribe("111", TOKEN, BASE)), "Graph request failed");
    }

    private void assertUnknownContaining(WhatsAppSubscription.State state, String fragment) {
        assertTrue(state instanceof WhatsAppSubscription.Unknown, "expected Unknown, got: " + state);
        var reason = ((WhatsAppSubscription.Unknown) state).reason();
        assertTrue(reason.contains(fragment), () -> "reason '" + reason + "' should contain '" + fragment + "'");
        assertFalse(reason.contains(TOKEN), "the reason must never carry the access token");
    }

    private void assertFailed(WhatsAppSubscription.SubscribeResult result, String fragment) {
        assertTrue(result instanceof WhatsAppSubscription.Failed, "expected Failed, got: " + result);
        var reason = ((WhatsAppSubscription.Failed) result).reason();
        assertTrue(reason.contains(fragment), () -> "reason '" + reason + "' should contain '" + fragment + "'");
        assertFalse(reason.contains(TOKEN), "the reason must never carry the access token");
    }

    /** Answers each request by {@code METHOD path} from {@link #answers}; an unregistered one is a 599. */
    private <T> T run(Supplier<T> body) {
        Interceptor canned = chain -> {
            var request = chain.request();
            requests.add(request);
            if (failTransport) {
                throw new IOException("connection reset by graph.invalid");
            }
            var answer = answers.getOrDefault(request.method() + " " + request.url().encodedPath(),
                    new Canned(599, "unregistered " + request.url().encodedPath()));
            return new Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(answer.code())
                    .message("canned")
                    .body(ResponseBody.create(answer.body(), null))
                    .build();
        };
        return HttpFactories.callWith(new OkHttpClient.Builder().addInterceptor(canned).build(), body);
    }
}
