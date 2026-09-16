import llm.LlmFailureClassifier;
import llm.LlmFailureClassifier.CallSite;
import llm.LlmProvider;
import llm.LlmProvider.LlmException;
import llm.LlmResilience;
import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.ModelInfo;
import llm.LlmTypes.ProviderConfig;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.CircuitBreakers;
import utils.ErrorTemplate;
import utils.HttpFactories;
import utils.LlmErrorTemplates;
import utils.LlmErrorTemplates.Failure;
import utils.LlmErrorTemplates.Remedy;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JCLAW-1134: a failed model call tells the operator which screen to go to.
 *
 * <p>{@code LlmExceptionClassificationTest} pins the other cut of this same space — which
 * subclass a status maps to, which is what the circuit breaker reads. Nothing here may move
 * that, so the overlapping cases assert both: the remedy is new, the fault location is not.
 *
 * <p>The transport is substituted through {@link HttpFactories#callWith}, so the base URL points
 * at {@code .invalid}: a leak in the seam surfaces as a failed call, never as a live request.
 */
class LlmActionableErrorTest extends UnitTest {

    private static final String PROVIDER = "jclaw1134-remedy";

    /** Long enough that a leak into a template would be unmistakable. */
    private static final String API_KEY = "sk-jclaw1134-not-a-real-key";

    /** What the catalog lists; deliberately not what the failing call sends. */
    private static final String CATALOG_MODEL = "catalog-default-model";

    /** What a task pinned for this turn — the model the failure has to name. */
    private static final String PINNED_MODEL = "task-pinned-model";

    private static LlmProvider provider() {
        return LlmProvider.forConfig(new ProviderConfig(PROVIDER, "http://jclaw1134.invalid/v1",
                API_KEY, List.of(new ModelInfo(CATALOG_MODEL, "Catalog default", 8192, 1024, false))));
    }

    /** Three exhausted chats in a row open the provider's breaker, which would then answer a
     *  later case before the canned transport does. */
    @BeforeEach
    void forgetTheBreaker() {
        CircuitBreakers.remove(LlmResilience.breakerName(PROVIDER));
    }

    // ── detection: three of the five had no classification before this story ──────────────

    @Test
    void anInvalidKeyIsDetectedFromTheStatusAndFromTheCode() {
        assertEquals(Remedy.INVALID_KEY, LlmFailureClassifier.classify(401,
                "{\"error\":{\"message\":\"Incorrect API key provided\",\"code\":\"invalid_api_key\"}}"));
        // Some gateways answer 403 and put the meaning in the code position only.
        assertEquals(Remedy.INVALID_KEY, LlmFailureClassifier.classify(403,
                "{\"error\":{\"type\":\"authentication_error\",\"message\":\"api key is invalid\"}}"));
    }

    @Test
    void anExhaustedQuotaIsDetectedFromTheCodePositionAndFromA402() {
        // The live OpenAI body recorded on JCLAW-929.
        assertEquals(Remedy.QUOTA_EXHAUSTED, LlmFailureClassifier.classify(429, """
                {"error":{"message":"You have no credits remaining. Add credits to continue using the API.",
                "type":"insufficient_quota","param":null,"code":"credit_balance_exhausted"}}"""));
        assertEquals(Remedy.QUOTA_EXHAUSTED, LlmFailureClassifier.classify(402,
                "{\"error\":{\"message\":\"Insufficient credits\"}}"));
    }

    @Test
    void aMissingModelIsDetectedFromTheCodeAndFromABareOllamaBody() {
        assertEquals(Remedy.MODEL_NOT_FOUND, LlmFailureClassifier.classify(404,
                "{\"error\":{\"message\":\"The model 'gpt-9' does not exist\",\"code\":\"model_not_found\"}}"));
        // Ollama states the error as a bare string with no code field at all.
        assertEquals(Remedy.MODEL_NOT_FOUND, LlmFailureClassifier.classify(404,
                "{\"error\":\"model 'qwen3.5' not found, try pulling it first\"}"));
    }

    @Test
    void anOverlongPromptIsDetectedFromTheCodeAndFromTheProviderProse() {
        assertEquals(Remedy.CONTEXT_WINDOW_EXCEEDED, LlmFailureClassifier.classify(400,
                "{\"error\":{\"message\":\"too long\",\"code\":\"context_length_exceeded\"}}"));
        assertEquals(Remedy.CONTEXT_WINDOW_EXCEEDED, LlmFailureClassifier.classify(400,
                "{\"error\":{\"message\":\"This model's maximum context length is 8192 tokens, "
                        + "however you requested 10240 tokens.\",\"type\":\"invalid_request_error\"}}"));
    }

    @Test
    void anOrdinaryRateLimitStaysRateLimitedEvenWhenItsProseSaysQuota() {
        // The retry loop keys off exactly this: only the code position makes a 429 permanent,
        // and rate-limit copy has itself used the word "quota" (JCLAW-929).
        assertEquals(Remedy.RATE_LIMITED, LlmFailureClassifier.classify(429, """
                {"error":{"message":"Rate limit reached for gpt-4o in organization org-x on requests
                per min. You exceeded your current quota.","type":"requests","code":"rate_limit_exceeded"}}"""));
    }

    @Test
    void aBodyWithNothingToGoOnStaysUnclassifiedRatherThanGuessing() {
        assertEquals(Remedy.UNCLASSIFIED, LlmFailureClassifier.classify(400, "{\"error\":{}}"));
        assertEquals(Remedy.UNCLASSIFIED, LlmFailureClassifier.classify(400, "<html>Bad Request</html>"));
        assertEquals(Remedy.UNCLASSIFIED, LlmFailureClassifier.classify(400, null));
        assertEquals(Remedy.UNCLASSIFIED, LlmFailureClassifier.classify(500, "upstream is unwell"));
    }

    @Test
    void a5xxBodyMentioningAContextWindowIsNotARemedyForTheOperator() {
        // Phrase matching is gated on a 4xx: a 5xx is the provider's health, whatever it says.
        assertEquals(Remedy.UNCLASSIFIED, LlmFailureClassifier.classify(503,
                "{\"error\":{\"message\":\"context window service unavailable\"}}"));
    }

    // ── the acceptance criterion: different screens, visibly different guidance ────────────

    @Test
    void anInvalidKeyAndAnExhaustedQuotaReadNothingAlike() {
        var key = templateFor(Remedy.INVALID_KEY);
        var quota = templateFor(Remedy.QUOTA_EXHAUSTED);

        assertNotEquals(key.code(), quota.code(), "the two must not collapse onto one message key");
        // One screen is the provider row in Settings, the other the provider's own billing
        // console; sending the operator to the wrong one costs a whole debugging session.
        assertTrue(key.whatToCheck().contains("Settings"), key.whatToCheck());
        assertTrue(quota.whatToCheck().contains("billing console"), quota.whatToCheck());
        assertTrue(quota.whatToCheck().contains("not Settings"),
                "the quota remedy must say the configuration is fine: " + quota.whatToCheck());
        assertNotEquals(key.whatBroke(), quota.whatBroke());
        assertNotEquals(key.howToRetry(), quota.howToRetry());
    }

    @Test
    void noneOfTheFiveFallsBackToTheGenericTemplate() {
        var generic = LlmErrorTemplates.forFailure(null);
        var codes = new ArrayList<String>();
        for (var remedy : Remedy.values()) {
            var t = templateFor(remedy);
            assertEquals(remedy.code(), t.code());
            assertFalse(codes.contains(t.code()), "duplicate error code for " + remedy);
            codes.add(t.code());
            assertTrue(t.hasRetry(), remedy + " has a retry path");
            if (remedy == Remedy.UNCLASSIFIED) continue;
            assertNotEquals(generic.whatBroke(), t.whatBroke(), remedy + " must not read generic");
            assertNotEquals(generic.whatToCheck(), t.whatToCheck(), remedy + " must not read generic");
        }
    }

    @Test
    void aFailureWithNoRemedyStillRendersRatherThanThrowing() {
        var t = LlmErrorTemplates.forFailure(null);
        assertNotNull(t, "the failure path must not have its own failure mode");
        assertFalse(t.whatBroke().isBlank());
        assertFalse(t.whatToCheck().isBlank());
    }

    @Test
    void theRetryAfterTheProviderAskedForReachesTheGuidance() {
        var t = LlmErrorTemplates.forFailure(
                new Failure(Remedy.RATE_LIMITED, PROVIDER, PINNED_MODEL, 42L, null));
        assertTrue(t.whatToCheck().contains("42 second"), t.whatToCheck());

        var noHeader = templateFor(Remedy.RATE_LIMITED);
        assertFalse(noHeader.whatToCheck().contains("second wait"),
                "a provider that sent no Retry-After must not be quoted a made-up one: "
                        + noHeader.whatToCheck());
    }

    @Test
    void theContextWindowComesFromOurOwnCatalogRatherThanTheProviderBody() {
        var t = LlmErrorTemplates.forFailure(
                new Failure(Remedy.CONTEXT_WINDOW_EXCEEDED, PROVIDER, PINNED_MODEL, null, 200_000));
        assertTrue(t.whatBroke().contains("200,000 tokens"), t.whatBroke());

        var unlisted = templateFor(Remedy.CONTEXT_WINDOW_EXCEEDED);
        assertFalse(unlisted.whatBroke().contains("tokens)"),
                "a model absent from the catalog states no limit rather than inventing one: "
                        + unlisted.whatBroke());
    }

    // ── the trap: the model named is the failing call's, not a configured default ──────────

    @Test
    void theRemedyNamesTheModelTheFailingCallSentNotTheOneInTheCatalog() {
        var failure = remedyOf(chat(404,
                "{\"error\":{\"message\":\"no such model\",\"code\":\"model_not_found\"}}"));

        assertEquals(Remedy.MODEL_NOT_FOUND, failure.remedy());
        assertEquals(PROVIDER, failure.provider());
        assertEquals(PINNED_MODEL, failure.model(),
                "read off the call — an unpinned task inherits its agent's current model, so "
                        + "anything reconstructed from configuration is the wrong model");

        var rendered = render(failure);
        assertTrue(rendered.contains(PINNED_MODEL), rendered);
        assertFalse(rendered.contains(CATALOG_MODEL),
                "the catalog's only entry is not the model that was rejected: " + rendered);
    }

    @Test
    void theContextWindowIsReadForTheModelTheCallSentSoAnUnlistedOneStatesNoLimit() {
        var failure = remedyOf(chat(400, "{\"error\":{\"code\":\"context_length_exceeded\"}}"));

        assertEquals(Remedy.CONTEXT_WINDOW_EXCEEDED, failure.remedy());
        assertNull(failure.contextWindow(),
                "the pinned model is not in the catalog, so there is no limit to quote");
    }

    // ── the remedy is a second cut, not a replacement for the first ────────────────────────

    @Test
    void classifyingARemedyLeavesTheFaultLocationAndTheRetryCountExactlyAsTheyWere() {
        for (var status : List.of(400, 401, 403, 404)) {
            var attempted = chat(status, "{\"error\":{\"code\":\"invalid_api_key\"}}");
            assertInstanceOf(LlmException.ClientError.class, attempted.failure(),
                    status + " is still our request, not the provider's health");
            assertEquals(1, attempted.attempts(),
                    "a thrown LlmException still aborts the retry loop for " + status);
        }
    }

    @Test
    void anExhaustedQuota429IsStillTheUnretriedClientErrorItWas() {
        var attempted = chat(429, "{\"error\":{\"code\":\"insufficient_quota\"}}");

        assertInstanceOf(LlmException.ClientError.class, attempted.failure());
        assertEquals(1, attempted.attempts(), "waiting never clears an exhausted balance");
        assertEquals(Remedy.QUOTA_EXHAUSTED, remedyOf(attempted).remedy());
    }

    @Test
    void aRetryable429CarriesItsClampedRetryAfterThroughTheExhaustionWrapper() {
        var attempted = chat(429, "{\"error\":{\"code\":\"rate_limit_exceeded\"}}");
        var failure = remedyOf(attempted);

        assertInstanceOf(LlmException.RateLimited.class, attempted.failure());
        assertEquals(Remedy.RATE_LIMITED, failure.remedy(),
                "the exhaustion wrapper keeps the last attempt's remedy, not a generic one");
        assertNotNull(failure.retryAfterSeconds());
    }

    @Test
    void aTransportFailureStillNamesTheCallEvenWithNoRemedyToOffer() {
        var thrown = failureUnder(_ -> { throw new IOException("connect refused"); });
        var failure = thrown.failure();

        assertInstanceOf(LlmException.Transport.class, thrown);
        assertNotNull(failure);
        assertEquals(Remedy.UNCLASSIFIED, failure.remedy());
        assertEquals(PINNED_MODEL, failure.model());
    }

    // ── sanitization: nothing an attacker writes reaches the operator's remedy ─────────────

    @Test
    void neitherTheApiKeyNorTheProviderBodyReachesTheTemplate() {
        var hostile = "{\"error\":{\"code\":\"invalid_api_key\",\"message\":\"key " + API_KEY
                + " rejected <script>alert(1)</script>\"}}";
        var rendered = render(remedyOf(chat(401, hostile)));

        assertFalse(rendered.contains(API_KEY), "the key must not survive into guidance: " + rendered);
        assertFalse(rendered.contains("<script>"),
                "no provider text at all is interpolated, hostile or otherwise: " + rendered);
    }

    @Test
    void theStreamingPathClassifiesAndSanitizesTheSameWay() throws Exception {
        // The streaming transport runs on its own virtual thread, which inherits no
        // HttpFactories binding, so the driver's classifier is driven directly — the way
        // LlmErrorBodySanitizeTest and OkHttpLlmHttpDriverTest reach the driver.
        var site = new CallSite(PROVIDER, PINNED_MODEL, 8192);
        var body = "{\"error\":{\"code\":\"invalid_api_key\",\"message\":\"key " + API_KEY + " bad\"}}";
        var thrown = driverClassify(401, body, "Bearer " + API_KEY, site);
        var failure = thrown.failure();

        assertInstanceOf(LlmException.ClientError.class, thrown);
        assertNotNull(failure);
        assertEquals(Remedy.INVALID_KEY, failure.remedy());
        assertEquals(PINNED_MODEL, failure.model());
        assertFalse(thrown.getMessage().contains(API_KEY),
                "the streaming message keeps the JCLAW-730 scrub: " + thrown.getMessage());
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────

    private record Attempted(LlmException failure, int attempts) {}

    /** Drive one {@code chat()} against a canned status/body; returns the failure and the count. */
    private static Attempted chat(int status, String body) {
        var attempts = new AtomicInteger();
        Interceptor canned = chain -> {
            attempts.incrementAndGet();
            return new Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(status)
                    .message("canned")
                    .body(ResponseBody.create(body, null))
                    .build();
        };
        return new Attempted(failureUnder(canned), attempts.get());
    }

    private static LlmException failureUnder(Interceptor transport) {
        var client = new OkHttpClient.Builder().addInterceptor(transport).build();
        return HttpFactories.callWith(client, () -> assertThrows(LlmException.class,
                () -> provider().chat(PINNED_MODEL, List.of(ChatMessage.user("hi")),
                        List.of(), 16, null, "test")));
    }

    private static Failure remedyOf(Attempted attempted) {
        var failure = attempted.failure().failure();
        assertNotNull(failure, "a provider rejection must carry the remedy it needs");
        return failure;
    }

    private static ErrorTemplate templateFor(Remedy remedy) {
        return LlmErrorTemplates.forFailure(new Failure(remedy, PROVIDER, PINNED_MODEL, null, null));
    }

    /** Every section of the rendered remedy, as one string to assert over. */
    private static String render(Failure failure) {
        var t = LlmErrorTemplates.forFailure(failure);
        return t.whatBroke() + " " + t.whatToCheck() + " " + t.howToRetry();
    }

    private static LlmException driverClassify(int status, String body, String authHeader,
                                               CallSite site) throws Exception {
        var cls = Class.forName("llm.OkHttpLlmHttpDriver");
        Method m = cls.getDeclaredMethod("classify", int.class, String.class, String.class,
                CallSite.class);
        m.setAccessible(true);
        return (LlmException) m.invoke(null, status, body, authHeader, site);
    }
}
