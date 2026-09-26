package tools.jev;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;
import services.decision.JevApi;
import services.decision.JevException;
import utils.RetryScheduler;

/**
 * One Jev decision per {@code POST /v1/systemone}: a port of jev-ultrafast's {@code model.choose}
 * (MIT, Browser Use; notice in {@code conf/browser/jev-ultrafast-LICENSE}). Every head is asked
 * in the one request; only the head the chosen operation names is read, so a malformed
 * speculative head can never cause an action.
 */
public final class JevClient {

    private static final int ATTEMPTS = 3;
    // Live runs: median 1.75 s, slowest success 7 s, and 11% of requests hung to the old 25 s limit.
    private static final long ATTEMPT_TIMEOUT_MS = 10_000;
    private static final String NOTHING_EXECUTED = "; no action executed";
    static final String INVALID = JevApi.INVALID + NOTHING_EXECUTED;

    private JevClient() {}

    /**
     * What Jev chose. {@code action} is the observed snapshot action to execute; it is null
     * exactly when the operation is {@code DONE} or {@code BLOCKED}.
     */
    public record Decision(String operation, @Nullable JsonObject action) {}

    /**
     * Ask Jev for the next operation on {@code page}, three attempts of ten seconds each.
     * Throws {@link JevException} with nothing executed.
     */
    public static Decision decide(String apiKey, JsonObject page, String goal, JsonArray history) {
        var space = JevActionSpace.of(page.getAsJsonArray("actions"));
        JsonObject result;
        try {
            result = JevApi.post(apiKey, space.request(page, goal, history), ATTEMPTS, ATTEMPT_TIMEOUT_MS);
        } catch (JevException e) {
            throw new JevException(e.getMessage() + NOTHING_EXECUTED);
        }
        try {
            var answers = result.getAsJsonObject("answers");
            var operationAnswer = JevApi.validateChoice(answers.get("operation"), space.operations().keySet());
            var operation = operationAnswer.get("choice").getAsString();
            var head = space.targets().get(operation);
            if (head != null) {
                var targetAnswer = JevApi.validateChoice(answers.get(JevActionSpace.targetHead(operation)), head.keySet());
                return new Decision(operation, head.get(targetAnswer.get("choice").getAsString()));
            }
            return new Decision(operation, space.controls().get(operation));
        } catch (RuntimeException _) {
            throw new JevException(INVALID);
        }
    }

    /** A wait that unmounts a virtual-thread caller (JDK-8373224). */
    static void pause(long millis) {
        RetryScheduler.schedule(() -> null, millis).join();
    }
}
