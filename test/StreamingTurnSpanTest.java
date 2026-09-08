import com.google.gson.JsonParser;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.semconv.HttpAttributes;
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.Play;
import play.mvc.Http;
import play.test.FunctionalTest;
import services.telemetry.OtelRuntime;
import utils.LatencyTrace;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A real streaming turn — the loadtest harness driving {@code POST /api/chat/stream}
 * against the in-process mock provider — produces the full chain
 * {@code POST /api/chat/stream} ← {@code turn} ← {@code chat mock-model} ← HTTP client
 * (JCLAW-34). The two hops that matter cross thread boundaries: the turn runs on
 * {@code StreamingAgentRunner}'s virtual thread and the model call on the LLM stream
 * thread, and neither inherits an OpenTelemetry context unless it is carried across.
 */
public class StreamingTurnSpanTest extends FunctionalTest {

    @BeforeEach
    public void lock() {
        LoadTestHarnessSync.acquire();
        TelemetryTestSync.acquire();
        OtelRuntime.init();
        // The workers authenticate with a minted session cookie, which AuthCheck refuses
        // (401 password_unset) until an admin password exists.
        AuthFixture.seedAdminPassword("loadtest-pw");
    }

    @AfterEach
    public void unlock() {
        AuthFixture.clearAdminPassword();
        TelemetryTestSync.release();
        LoadTestHarnessSync.release();
    }

    private Http.Request authedLoadtestRequest() {
        var req = newRequest();
        req.remoteAddress = "127.0.0.1";
        if (req.headers == null) {
            req.headers = new HashMap<>();
        }
        var secret = Play.configuration.getProperty("application.secret");
        req.headers.put("x-loadtest-auth", new Http.Header("x-loadtest-auth", secret));
        return req;
    }

    @Test
    public void aStreamingTurnNestsUnderItsRequestAndItsModelCallUnderTheTurn() {
        var report = new String[1];
        var spans = OtelRuntime.captureForTest(() -> {
            var response = POST(authedLoadtestRequest(), "/api/metrics/loadtest", "application/json",
                    """
                    {"concurrency":1,"turns":1,"ttftMs":1,"tokensPerSecond":200,"responseTokens":5}
                    """);
            report[0] = getContent(response);
            assertEquals(200, response.status.intValue(), () -> "loadtest run: " + report[0]);
        });
        var json = JsonParser.parseString(report[0]).getAsJsonObject();
        assertEquals(1, json.get("successCount").getAsInt(), () -> "the turn itself must succeed: " + report[0]);

        Map<String, SpanData> byId = new HashMap<>();
        for (var s : spans) byId.put(s.getSpanContext().getSpanId(), s);
        Function<SpanData, SpanData> parentOf = s -> byId.get(s.getParentSpanContext().getSpanId());

        // The harness sends a warmup turn before the measured one, so two chains.
        var chats = spans.stream().filter(s -> s.getName().startsWith("chat ")).toList();
        assertEquals(2, chats.size(), () -> "chat spans in " + describe(spans) + "\nloadtest report: " + report[0]);
        for (var chat : chats) {
            assertEquals(SpanKind.CLIENT, chat.getKind());
            assertNotNull(chat.getAttributes().get(GenAiIncubatingAttributes.GEN_AI_CONVERSATION_ID),
                    "the streaming runner tags the turn with its conversation, and the model call inherits it");

            var turn = parentOf.apply(chat);
            assertNotNull(turn, "the model call has a parent");
            assertEquals("turn", turn.getName());
            assertTrue(turn.getEvents().stream().anyMatch(e -> e.getName().equals(LatencyTrace.PROLOGUE_DONE)));

            var server = parentOf.apply(turn);
            assertNotNull(server, "the turn was started on a thread that inherited the request's span");
            assertEquals(SpanKind.SERVER, server.getKind());
            assertEquals("POST /api/chat/stream", server.getName());
            assertEquals("/api/chat/stream", server.getAttributes().get(HttpAttributes.HTTP_ROUTE));

            var http = spans.stream()
                    .filter(s -> s.getKind() == SpanKind.CLIENT
                            && "POST".equals(s.getAttributes().get(HttpAttributes.HTTP_REQUEST_METHOD))
                            && chat.getSpanContext().getSpanId().equals(s.getParentSpanContext().getSpanId()))
                    .toList();
            // One span per attempt: a pooled connection to the mock gone stale since the harness
            // last restarted costs an IOException span and a resend, so count the successful one.
            var succeeded = http.stream()
                    .filter(s -> Long.valueOf(200).equals(s.getAttributes().get(HttpAttributes.HTTP_RESPONSE_STATUS_CODE)))
                    .count();
            assertEquals(1L, succeeded, () -> "successful HTTP client spans under the model call in " + describe(spans));
        }
    }

    private static String describe(List<SpanData> spans) {
        var sb = new StringBuilder();
        for (var s : spans) {
            sb.append("\n  ").append(s.getName()).append(" kind=").append(s.getKind())
              .append(" status=").append(s.getStatus().getStatusCode())
              .append(" id=").append(s.getSpanContext().getSpanId())
              .append(" parent=").append(s.getParentSpanContext().getSpanId())
              .append(" attrs=").append(s.getAttributes().asMap());
        }
        return sb.toString();
    }
}
