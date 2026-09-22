package tools.jev;

import agents.ModelResolver;
import agents.ToolContext;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.microsoft.playwright.PlaywrightException;
import llm.LlmTypes.ChatMessage;
import llm.ProviderRegistry;
import llm.routing.ModelRouter;
import llm.routing.RouteDecision;
import models.Agent;
import models.Conversation;
import org.jspecify.annotations.Nullable;
import services.EventLogger;
import services.SessionCompactor;
import services.Tx;
import utils.AppClock;

import java.io.IOException;
import java.io.StringReader;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * One goal-driven Jev run: a port of jev-ultrafast's {@code agent.py} (MIT, Browser Use; notice in
 * {@code conf/browser/jev-ultrafast-LICENSE}). Jev chooses every step; the calling agent's own
 * model writes any text to be typed. The run ends when Jev answers DONE or BLOCKED on a page that
 * has not moved since, when a budget runs out or the turn is stopped, or on the first error — and
 * every action is recorded before the page is read again, so the result reports what was executed
 * even when a later read fails.
 */
public final class JevRun {

    static final int MAX_ACTIONS = 60;
    static final int MAX_DECISIONS = MAX_ACTIONS * 2;
    static final int STALLED_STEPS = 3;
    // Not upstream, which spends its whole decision budget on a page whose text never stops changing.
    static final int STALE_DECISIONS = 5;
    static final Duration TIME_LIMIT = Duration.ofMinutes(5);
    private static final int TEXT_MAX_TOKENS = 1024;
    // The default 180 s would let one text entry overrun the run's five minutes on its own.
    private static final int TEXT_TIMEOUT_SECONDS = 60;
    private static final int TEXT_MAX_CHARS = 2000;
    private static final int VISIBLE_TEXT_CHARS = 4000;
    private static final String NOTHING_TYPED = "Text helper returned no valid field value; nothing typed";
    private static final String NO_VALUE =
            "The goal gives no value for this field; nothing typed. Put every value to enter in the goal";
    private static final String FENCE = "```";
    private static final int EXCERPT_CHARS = 200;
    private static final String ERROR = "error";
    private static final String BLOCKED = "blocked";

    /** Writes the value for one field from {@link JevActionSpace#fieldContext}, or throws {@link JevException}. */
    @FunctionalInterface
    public interface TextHelper {
        String text(JsonObject fieldContext);
    }

    /**
     * How a run ended.
     *
     * @param status    {@code done}, {@code blocked} or {@code error}
     * @param reason    why a run is blocked or failed, or null when Jev itself answered
     * @param steps     each action taken or refused: operation, label, kind, typed text, whether the page
     *                  changed, and for a refused one why
     * @param decisions how many decisions Jev was asked for
     * @param page      the last page read, or null when the first read failed
     */
    public record Outcome(String status, @Nullable String reason, JsonArray steps, int decisions,
                          @Nullable JsonObject page) {

        /** The tool result: status, final page, numbered history, and the visible text to check the goal against. */
        public String format() {
            var out = new StringBuilder();
            if (status.equals(ERROR)) {
                out.append("Error: ").append(reason);
                if (steps.isEmpty()) return out.toString();
                out.append("\n\nActions executed before the error:\n");
                appendSteps(out);
                return out.toString();
            }
            out.append("Jev run: ").append(status);
            if (reason != null) out.append(" (").append(reason).append(')');
            out.append(" after ").append(decisions).append(decisions == 1 ? " decision.\n" : " decisions.\n");
            if (page != null) {
                out.append("Final page: ").append(page.get("title").getAsString())
                        .append(" — ").append(page.get("url").getAsString()).append('\n');
                var omitted = page.get("omitted_actions");
                if (omitted != null && omitted.isJsonPrimitive() && omitted.getAsInt() > 0) {
                    out.append(omitted.getAsInt()).append(" controls on the final page were not offered to Jev, "
                            + "which is shown at most 250.\n");
                }
            }
            out.append(steps.isEmpty() ? "No actions executed.\n" : "Actions:\n");
            appendSteps(out);
            out.append("\nDONE and BLOCKED are Jev's judgement, not a verified result: check the final page "
                    + "below against the goal before relying on it.\n");
            if (page != null) {
                var text = page.get("text").getAsString();
                out.append("\nVisible text:\n").append(text.length() > VISIBLE_TEXT_CHARS
                        ? text.substring(0, VISIBLE_TEXT_CHARS) + "\n[Truncated]" : text);
            }
            return out.toString();
        }

        private void appendSteps(StringBuilder out) {
            for (int i = 0; i < steps.size(); i++) {
                var step = steps.get(i).getAsJsonObject();
                out.append(i + 1).append(". ").append(step.get("operation").getAsString())
                        .append(' ').append(step.get("action").getAsString());
                var typed = step.get("text");
                if (typed != null && typed.isJsonPrimitive()) out.append(" ← \"").append(typed.getAsString()).append('"');
                var refused = step.get(JevActionSpace.REFUSED);
                var changed = step.get("page_changed");
                if (refused != null) {
                    out.append(" (refused: ").append(refused.getAsString()).append(')');
                } else if (changed != null && changed.isJsonPrimitive() && !changed.getAsBoolean()) {
                    out.append(" (page unchanged)");
                }
                out.append('\n');
            }
        }

        private long executed() {
            long n = 0;
            for (var step : steps) {
                if (!step.getAsJsonObject().has(JevActionSpace.REFUSED)) n++;
            }
            return n;
        }
    }

    private JevRun() {}

    /**
     * Run {@code goal} on the page {@code browser} is attached to, which the caller has already loaded,
     * and log the outcome under {@code agentName}. Stops at the next step once {@link ToolContext#cancelled()}.
     */
    public static Outcome run(JevPage browser, String apiKey, String goal, TextHelper helper, String agentName) {
        var outcome = drive(browser, apiKey, goal, helper);
        var reason = outcome.reason() == null ? "" : " (" + outcome.reason() + ")";
        var summary = "Jev run %s%s after %d decisions and %d actions"
                .formatted(outcome.status(), reason, outcome.decisions(), outcome.executed());
        if (outcome.status().equals(ERROR)) EventLogger.warn("tool", agentName, null, summary);
        else EventLogger.info("tool", agentName, null, summary);
        return outcome;
    }

    private static Outcome drive(JevPage browser, String apiKey, String goal, TextHelper helper) {
        var deadline = AppClock.now().plus(TIME_LIMIT);
        var history = new JsonArray();
        int decisions = 0;
        int actions = 0;
        int stale = 0;
        JsonObject page = null;
        var pending = new PendingText();
        try {
            browser.prepare();
            page = browser.observe();
            while (true) {
                var stop = stopReason(deadline, stale, decisions);
                if (stop != null) return new Outcome(BLOCKED, stop, history, decisions, page);
                try {
                    if (!browser.fresh(page, null)) page = browser.observe();
                    var decision = JevClient.decide(apiKey, page, goal, history);
                    decisions++;
                    var action = decision.action();
                    if (action == null) {
                        if (!browser.fresh(page, null)) {
                            throw new JevPage.StalePage("Page changed since the decision");
                        }
                        var status = decision.operation().equals(JevActionSpace.DONE) ? "done" : BLOCKED;
                        return new Outcome(status, null, history, decisions, page);
                    }
                    if (actions >= MAX_ACTIONS) {
                        return new Outcome(BLOCKED, "used all " + MAX_ACTIONS + " actions", history, decisions, page);
                    }
                    var kind = action.get("kind").getAsString();
                    String text = null;
                    try {
                        if (kind.equals("fill")) {
                            if (!browser.fresh(page, action)) {
                                throw new JevPage.StalePage("Page changed before text generation");
                            }
                            text = pending.forField(helper, JevActionSpace.fieldContext(goal, action, page, history));
                        }
                        browser.act(action, page, text);
                    } catch (JevPage.StalePage refused) {
                        // Recorded so Jev sees it among its recent actions rather than re-picking the target blind.
                        if (kind.equals("click") || kind.equals("fill")) {
                            history.add(step(decision.operation(), action, null, refused.getMessage()));
                            if (stalled(history)) {
                                return new Outcome(BLOCKED, stallReason(history), history, decisions, page);
                            }
                        }
                        throw refused;
                    }
                    stale = 0;
                    actions++;
                    pending.clear();
                    var step = step(decision.operation(), action, text, null);
                    history.add(step);
                    var before = page.get("marker");
                    page = browser.observe();
                    step.addProperty("page_changed", !before.equals(page.get("marker")));
                    if (stalled(history)) {
                        return new Outcome(BLOCKED, stallReason(history), history, decisions, page);
                    }
                } catch (JevPage.StalePage _) {
                    stale++;
                    page = browser.observe();
                }
            }
        } catch (JevException | JevPage.StalePage | PlaywrightException e) {
            return new Outcome(ERROR, firstLine(e), history, decisions, page);
        } catch (RuntimeException e) {
            return new Outcome(ERROR, "the run failed unexpectedly (" + e.getClass().getSimpleName() + ")",
                    history, decisions, page);
        } finally {
            browser.finish();
        }
    }

    /** The helper's value for a field, reused while the same field is re-picked after a refusal. */
    private static final class PendingText {
        private @Nullable JsonObject context;
        private @Nullable String text;

        String forField(TextHelper helper, JsonObject fieldContext) {
            var cached = text;
            if (cached != null && fieldContext.equals(context)) return cached;
            var fresh = helper.text(fieldContext);
            context = fieldContext;
            text = fresh;
            return fresh;
        }

        void clear() {
            context = null;
            text = null;
        }
    }

    private static @Nullable String stopReason(Instant deadline, int stale, int decisions) {
        if (ToolContext.cancelled()) return "stopped";
        if (!AppClock.now().isBefore(deadline)) return "time limit";
        if (stale >= STALE_DECISIONS) return "the page keeps changing";
        if (decisions >= MAX_DECISIONS) return "used all " + MAX_DECISIONS + " decisions";
        return null;
    }

    private static JsonObject step(String operation, JsonObject action, @Nullable String text,
                                   @Nullable String refused) {
        var step = new JsonObject();
        step.addProperty("operation", operation);
        step.add("action", action.get("label"));
        step.add("kind", action.get("kind"));
        step.addProperty("text", text);
        if (refused != null) {
            step.addProperty("page_changed", false);
            step.addProperty(JevActionSpace.REFUSED, refused);
        }
        return step;
    }

    /** The last {@link #STALLED_STEPS} steps were each refused, or executed without changing the page. */
    private static boolean stalled(JsonArray history) {
        int n = history.size();
        if (n < STALLED_STEPS) return false;
        for (int i = n - STALLED_STEPS; i < n; i++) {
            var step = history.get(i).getAsJsonObject();
            if (step.has(JevActionSpace.REFUSED)) continue;
            var changed = step.get("page_changed");
            boolean waited = step.get("kind").getAsString().equals("wait");
            if (changed == null || changed.getAsBoolean() || waited) return false;
        }
        return true;
    }

    private static String stallReason(JsonArray history) {
        for (int i = history.size() - 1; i >= history.size() - STALLED_STEPS; i--) {
            var step = history.get(i).getAsJsonObject();
            if (step.has(JevActionSpace.REFUSED)) {
                return "the last %d actions were refused or changed nothing; %s %s was refused: %s".formatted(
                        STALLED_STEPS, step.get("operation").getAsString(), step.get("action").getAsString(),
                        step.get(JevActionSpace.REFUSED).getAsString());
            }
        }
        return "the last " + STALLED_STEPS + " actions changed nothing";
    }

    private static String firstLine(RuntimeException e) {
        var message = e.getMessage();
        if (message == null || message.isBlank()) return e.getClass().getSimpleName();
        return message.lines().findFirst().orElse(message);
    }

    /**
     * The production helper: the calling agent's effective model — the conversation's override
     * when there is one, else the agent's own on a task fire — asked for strict {@code {"text": string}}.
     */
    public static TextHelper agentModel(Agent agent) {
        return context -> {
            var target = effectiveModel(agent);
            // An unregistered provider falls back to the primary, as the turn itself does (AgentRunner).
            var registered = target != null ? ProviderRegistry.get(target.provider()) : null;
            var provider = registered != null ? registered : ProviderRegistry.getPrimary();
            if (target == null || provider == null) {
                throw new JevException("The agent's model is not configured; nothing typed");
            }
            String reply;
            try {
                reply = SessionCompactor.firstChoiceText(provider.chat(target.modelId(),
                        List.of(ChatMessage.system(JevActionSpace.TEXT_VALUE), ChatMessage.user(context.toString())),
                        List.of(), TEXT_MAX_TOKENS, null, TEXT_TIMEOUT_SECONDS, null));
            } catch (RuntimeException _) {
                throw new JevException("The agent's model did not answer; nothing typed");
            }
            try {
                return parseText(reply);
            } catch (JevException e) {
                // The next unexpected reply shape is only diagnosable from what the model actually said.
                EventLogger.warn("tool", agent.name, null, "Jev text helper refused the reply: " + excerpt(reply));
                throw e;
            }
        };
    }

    private static String excerpt(@Nullable String reply) {
        if (reply == null) return "(no reply)";
        var flat = reply.replace('\n', ' ');
        return flat.length() <= 2 * EXCERPT_CHARS ? flat
                : flat.substring(0, EXCERPT_CHARS) + " … " + flat.substring(flat.length() - EXCERPT_CHARS);
    }

    private static RouteDecision.@Nullable Target effectiveModel(Agent agent) {
        var conversationId = ToolContext.conversationId();
        Conversation conversation = conversationId == null ? null
                : Tx.run(() -> Conversation.<Conversation>findById(conversationId));
        if (conversation == null) return ModelRouter.concrete(agent.modelProvider, agent.modelId);
        return ModelRouter.concrete(ModelResolver.effectiveModelProvider(agent, conversation),
                ModelResolver.effectiveModelId(agent, conversation));
    }

    /**
     * The field value from the reply's final JSON object, which must be exactly
     * {@code {"text": "<non-blank, at most 2000 chars>"}}. Anything before that object and one
     * closing code fence after it are ignored; any other text after it types nothing.
     */
    public static String parseText(@Nullable String reply) {
        if (reply == null) throw new JevException(NOTHING_TYPED);
        var body = reply.strip();
        if (body.endsWith(FENCE)) body = body.substring(0, body.length() - FENCE.length()).strip();
        if (!body.endsWith("}")) throw new JevException(NOTHING_TYPED);
        // JCLAW-1275: models prefix reasoning (glm leaks "…</think>") or fence the object, so take the final one.
        for (int start = body.lastIndexOf('{'); start >= 0; start = body.lastIndexOf('{', start - 1)) {
            var object = strictObject(body.substring(start));
            if (object != null) return textOf(object);
        }
        throw new JevException(NOTHING_TYPED);
    }

    /** {@code candidate} parsed as one strict JSON object with nothing after it, or null. */
    private static @Nullable JsonObject strictObject(String candidate) {
        try {
            var reader = new JsonReader(new StringReader(candidate));
            reader.setStrictness(Strictness.STRICT);
            JsonElement parsed = JsonParser.parseReader(reader);
            return reader.peek() == JsonToken.END_DOCUMENT && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (JsonParseException | IOException _) {
            return null;
        }
    }

    private static String textOf(JsonObject object) {
        var text = object.get("text");
        // The prompt asks for null when the goal holds no value; the calling agent can fix that, so say so.
        if (object.size() == 1 && text != null && text.isJsonNull()) throw new JevException(NO_VALUE);
        if (object.size() != 1 || text == null || !text.isJsonPrimitive() || !text.getAsJsonPrimitive().isString()) {
            throw new JevException(NOTHING_TYPED);
        }
        var value = text.getAsString();
        if (value.isBlank() || value.length() > TEXT_MAX_CHARS) throw new JevException(NOTHING_TYPED);
        return value;
    }
}
