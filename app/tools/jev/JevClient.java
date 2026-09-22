package tools.jev;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.jspecify.annotations.Nullable;
import services.EventLogger;
import utils.HttpFactories;
import utils.HttpKeys;
import utils.RetryScheduler;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * One Jev decision per {@code POST /v1/systemone}: a port of jev-ultrafast's
 * {@code model.post_json}, {@code model.validate_choice} and {@code model.choose}
 * (MIT, Browser Use; notice in {@code conf/browser/jev-ultrafast-LICENSE}). Every head is asked
 * in the one request; only the head the chosen operation names is read, so a malformed
 * speculative head can never cause an action.
 */
public final class JevClient {

    static final String ENDPOINT = "https://api.typesafe.ai/v1/systemone";

    private static final MediaType JSON = MediaType.get(HttpKeys.APPLICATION_JSON);
    private static final int ATTEMPTS = 3;
    // Live runs: median 1.75 s, slowest success 7 s, and 11% of requests hung to the old 25 s limit.
    private static final long ATTEMPT_TIMEOUT_MS = 10_000;
    private static final long BACKOFF_MS = 500;
    private static final Set<Integer> RETRIED_STATUS = Set.of(429, 503, 529);
    static final String INVALID = "Invalid Jev response; no action executed";

    private JevClient() {}

    /**
     * What Jev chose. {@code action} is the observed snapshot action to execute; it is null
     * exactly when the operation is {@code DONE} or {@code BLOCKED}.
     */
    public record Decision(String operation, @Nullable JsonObject action) {}

    /** Ask Jev for the next operation on {@code page}. Throws {@link JevException} with nothing executed. */
    public static Decision decide(String apiKey, JsonObject page, String goal, JsonArray history) {
        var space = JevActionSpace.of(page.getAsJsonArray("actions"));
        var result = post(apiKey, space.request(page, goal, history));
        try {
            var answers = result.getAsJsonObject("answers");
            var operationAnswer = validateChoice(answers.get("operation"), space.operations().keySet());
            var operation = operationAnswer.get("choice").getAsString();
            var head = space.targets().get(operation);
            if (head != null) {
                var targetAnswer = validateChoice(answers.get(JevActionSpace.targetHead(operation)), head.keySet());
                return new Decision(operation, head.get(targetAnswer.get("choice").getAsString()));
            }
            return new Decision(operation, space.controls().get(operation));
        } catch (JevException e) {
            throw e;
        } catch (RuntimeException _) {
            throw new JevException(INVALID);
        }
    }

    /**
     * POST {@code body}, three attempts of ten seconds each. A decision has no side effects, so
     * a timeout or a dropped connection is retried as well as 429, 503 and 529.
     */
    static JsonObject post(String apiKey, JsonObject body) {
        var request = new Request.Builder().url(ENDPOINT)
                .header(HttpKeys.AUTHORIZATION, HttpKeys.BEARER_PREFIX + apiKey)
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        for (int attempt = 1; ; attempt++) {
            var call = HttpFactories.general().newCall(request);
            call.timeout().timeout(ATTEMPT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            try (var response = call.execute()) {
                if (!response.isSuccessful()) {
                    EventLogger.warn("tool", "Jev request %d of %d failed: HTTP %d"
                            .formatted(attempt, ATTEMPTS, response.code()));
                }
                if (RETRIED_STATUS.contains(response.code()) && attempt < ATTEMPTS) {
                    pause(BACKOFF_MS << (attempt - 1));
                    continue;
                }
                if (response.code() == 401 || response.code() == 403) {
                    throw new JevException("TypeSafe refused the Jev API key (HTTP %d); the operator must update it "
                            .formatted(response.code()) + "in Settings → Browser. No action executed");
                }
                if (!response.isSuccessful()) {
                    throw new JevException("Jev returned HTTP " + response.code() + "; no action executed");
                }
                var parsed = JsonParser.parseString(response.body().string());
                if (!parsed.isJsonObject()) throw new JevException(INVALID);
                return parsed.getAsJsonObject();
            } catch (IOException e) {
                EventLogger.warn("tool", "Jev request %d of %d failed: %s"
                        .formatted(attempt, ATTEMPTS, e.getClass().getSimpleName()));
                if (attempt >= ATTEMPTS) throw new JevException("Jev unreachable; no action executed");
                pause(BACKOFF_MS << (attempt - 1));
            } catch (JsonParseException _) {
                throw new JevException(INVALID);
            }
        }
    }

    /**
     * The answer, if it chooses one of {@code ids} and its probabilities cover exactly those ids,
     * are finite numbers in [0, 1] summing to 1 within 0.02, and peak at the choice.
     */
    public static JsonObject validateChoice(@Nullable JsonElement raw, Set<String> ids) {
        try {
            if (raw == null || !raw.isJsonObject()) throw new JevException(INVALID);
            var answer = raw.getAsJsonObject();
            var choice = answer.getAsJsonPrimitive("choice");
            var probabilities = answer.getAsJsonObject("probabilities");
            if (choice == null || !choice.isString() || !ids.contains(choice.getAsString())
                    || probabilities == null || !probabilities.keySet().equals(ids)) {
                throw new JevException(INVALID);
            }
            double sum = 0;
            double max = Double.NEGATIVE_INFINITY;
            for (Map.Entry<String, JsonElement> p : probabilities.entrySet()) {
                double value = unitNumber(p.getValue());
                sum += value;
                max = Math.max(max, value);
            }
            unitNumber(answer.get("confidence"));
            if (Math.abs(sum - 1) >= 0.02 || unitNumber(probabilities.get(choice.getAsString())) < max - 1e-6) {
                throw new JevException(INVALID);
            }
            return answer;
        } catch (JevException e) {
            throw e;
        } catch (RuntimeException _) {
            throw new JevException(INVALID);
        }
    }

    private static double unitNumber(@Nullable JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new JevException(INVALID);
        }
        double value = element.getAsDouble();
        if (!Double.isFinite(value) || value < 0 || value > 1) throw new JevException(INVALID);
        return value;
    }

    /** A wait that unmounts a virtual-thread caller (JDK-8373224). */
    static void pause(long millis) {
        RetryScheduler.schedule(() -> null, millis).join();
    }
}
