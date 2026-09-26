import agents.ToolContext;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.PlaywrightException;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.TaskRunRegistry;
import services.decision.JevApi;
import tools.jev.JevPage;
import tools.jev.JevRun;
import utils.AppClock;
import utils.HttpFactories;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * The Jev loop without a browser (JCLAW-1274): {@link FakePage} answers JevPage's CDP calls from a
 * small page model and records the input it is sent, and Jev is played by an OkHttp interceptor. So
 * the budgets, Stop, the stale cap, recorded refusals, the DONE re-check, the frozen-call bound and
 * the result's three shapes all run in the default suite rather than only behind
 * {@code JCLAW_PLAYWRIGHT_TEST}.
 */
class JevLoopTest extends UnitTest {

    private static final MediaType JSON = MediaType.get("application/json");
    private static final String GOAL = "Type Lisbon into Destination, then press Find stays.";

    private FakePage site;
    private final List<JsonObject> bodies = new ArrayList<>();
    private final List<JsonObject> typedFor = new ArrayList<>();

    @BeforeEach
    void reset() {
        JevBreakerTestSync.acquire();
        site = new FakePage();
        bodies.clear();
        typedFor.clear();
    }

    @AfterEach
    void releaseBreaker() {
        JevBreakerTestSync.release();
    }

    // --- the stand-in for Jev ----------------------------------------------------------------

    /** What Jev answers for request {@code call} (1-based), given the request's state. */
    @FunctionalInterface
    private interface Policy extends BiFunction<Integer, JsonObject, String[]> {}

    private static String index(JsonObject state, String label) {
        for (var e : state.getAsJsonArray("elements")) {
            if (e.getAsJsonObject().get("label").getAsString().equals(label)) {
                return e.getAsJsonObject().get("index").getAsString();
            }
        }
        throw new AssertionError("Jev was not shown '" + label + "': " + state);
    }

    private static String value(JsonObject state, String label) {
        var elements = state.getAsJsonArray("elements");
        return elements.get(Integer.parseInt(index(state, label)) - 1).getAsJsonObject().get("value").getAsString();
    }

    private static String[] click(JsonObject state, String label) {
        return new String[] {"CLICK", index(state, label)};
    }

    private static JsonObject peaked(JsonObject question, String choice) {
        var probabilities = new JsonObject();
        question.getAsJsonObject("criteria").keySet()
                .forEach(k -> probabilities.addProperty(k, k.equals(choice) ? 1.0 : 0.0));
        var answer = new JsonObject();
        answer.addProperty("choice", choice);
        answer.addProperty("confidence", 0.97);
        answer.add("probabilities", probabilities);
        return answer;
    }

    private Interceptor jev(Policy policy) {
        return chain -> {
            var buffer = new Buffer();
            chain.request().body().writeTo(buffer);
            var body = JsonParser.parseString(buffer.readUtf8()).getAsJsonObject();
            bodies.add(body);
            var decision = policy.apply(bodies.size(), body.getAsJsonObject("state"));
            var questions = body.getAsJsonObject("questions");
            var answers = new JsonObject();
            answers.add("operation", peaked(questions.getAsJsonObject("operation"), decision[0]));
            var head = decision[0].toLowerCase(Locale.ROOT) + "_target";
            if (decision.length > 1) answers.add(head, peaked(questions.getAsJsonObject(head), decision[1]));
            var result = new JsonObject();
            result.add("answers", answers);
            return reply(chain, 200, result.toString());
        };
    }

    private static Response reply(Interceptor.Chain chain, int code, String json) {
        return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("canned").body(ResponseBody.create(json, JSON)).build();
    }

    private JevRun.Outcome run(Policy policy) {
        return run(policy, new JevPage(site, JevPage.CALL_LIMIT, () -> {}));
    }

    private JevRun.Outcome run(Policy policy, JevPage page) {
        return run(jev(policy), page, context -> {
            typedFor.add(context);
            return "Lisbon";
        });
    }

    private JevRun.Outcome run(Interceptor jev, JevPage page, JevRun.TextHelper helper) {
        return HttpFactories.callWith(new OkHttpClient.Builder().addInterceptor(jev).build(),
                () -> JevRun.run(page, "ts-test-key", GOAL, helper, "jev-loop-test"));
    }

    // --- budgets -----------------------------------------------------------------------------

    @Test
    void sixtyActionsEndTheRunBlocked() {
        site.button("Next", site::advance);
        var outcome = run((_, state) -> click(state, "Next"));

        assertEquals("blocked", outcome.status(), outcome.format());
        assertEquals("used all 60 actions", outcome.reason());
        assertEquals(60, outcome.steps().size());
        assertEquals(61, outcome.decisions());
        assertEquals(60, site.clicks("Next"));
    }

    @Test
    void aHundredAndTwentyDecisionsEndTheRunBlocked() {
        // A live clock makes every DONE stale; a click in between keeps the stale cap from ending the run first.
        site.live = true;
        site.button("Next", site::advance);
        var outcome = run((call, state) -> call % 2 == 1 ? new String[] {"DONE"} : click(state, "Next"));

        assertEquals("blocked", outcome.status(), outcome.format());
        assertEquals("used all 120 decisions", outcome.reason());
        assertEquals(120, bodies.size());
        assertEquals(60, outcome.steps().size());
    }

    @Test
    void threeActionsThatChangeNothingEndTheRunBlocked() {
        site.button("Noop", () -> {});
        var outcome = run((_, state) -> click(state, "Noop"));

        assertEquals("blocked", outcome.status(), outcome.format());
        assertEquals("the last 3 actions changed nothing", outcome.reason());
        assertEquals(3, outcome.steps().size());
        assertTrue(outcome.format().contains("3. CLICK Noop (page unchanged)"), outcome.format());
    }

    @Test
    void fiveMinutesEndTheRunBlocked() {
        var clock = new SteppedClock();
        site.button("Next", site::advance);
        Policy slow = (_, state) -> {
            clock.advance(Duration.ofMinutes(2));
            return click(state, "Next");
        };
        var outcome = AppClock.callWith(clock, () -> run(slow));

        assertEquals("blocked", outcome.status(), outcome.format());
        assertEquals("time limit", outcome.reason());
        assertEquals(3, outcome.decisions(), "checked before the fourth decision, six minutes in");
        assertTrue(outcome.format().startsWith("Jev run: blocked (time limit) after 3 decisions."), outcome.format());
    }

    @Test
    void theStaleCapEndsARunOnAPageThatKeepsChanging() {
        site.live = true;
        var outcome = run((_, _) -> new String[] {"DONE"});

        assertEquals("blocked", outcome.status(), outcome.format());
        assertEquals("the page keeps changing", outcome.reason());
        assertEquals(5, outcome.decisions());
        assertEquals(5, bodies.size());
    }

    // --- Stop --------------------------------------------------------------------------------

    @Test
    void stopEndsTheRunAtItsNextStep() {
        var stopped = new AtomicBoolean();
        site.button("Next", site::advance);
        Policy stopAfterTwo = (call, state) -> {
            if (call == 2) stopped.set(true);
            return click(state, "Next");
        };

        var outcome = ToolContext.withScope(null, null, stopped::get, () -> run(stopAfterTwo));

        assertEquals("blocked", outcome.status(), outcome.format());
        assertEquals("stopped", outcome.reason());
        assertEquals(2, bodies.size(), "no decision after the stop");
        assertEquals(2, site.clicks("Next"), "the step in flight still finishes");
        assertTrue(outcome.format().startsWith("Jev run: blocked (stopped) after 2 decisions."), outcome.format());
        assertTrue(outcome.format().contains("Actions:\n1. CLICK Next\n2. CLICK Next\n"), outcome.format());
    }

    @Test
    void aCancelledTaskRunStopsTheRunBeforeAnyDecision() {
        var taskRunId = -ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        TaskRunRegistry.register(taskRunId);
        try {
            TaskRunRegistry.requestCancel(taskRunId);
            site.button("Next", site::advance);
            var outcome = ToolContext.withScope(null, taskRunId, () -> run((_, state) -> click(state, "Next")));

            assertEquals("stopped", outcome.reason(), outcome.format());
            assertEquals(0, bodies.size());
            assertEquals(0, site.clicks("Next"));
        } finally {
            TaskRunRegistry.unregister(taskRunId);
        }
    }

    // --- live pages, stale decisions and refusals -------------------------------------------

    @Test
    void aClockElsewhereOnThePageDoesNotBlockTyping() {
        site.live = true;
        site.textbox("Destination");
        Policy typeOnce = (_, state) -> value(state, "Destination").equals("Lisbon")
                ? new String[] {"DONE"} : new String[] {"TYPE_TEXT", index(state, "Destination")};

        var outcome = run(typeOnce);

        assertEquals(List.of("click Destination", "type Lisbon"), site.inputs, outcome.format());
        assertEquals(1, typedFor.size());
        assertEquals("TYPE_TEXT Destination ← \"Lisbon\"", outcome.format().lines()
                .filter(l -> l.startsWith("1. ")).findFirst().orElseThrow().substring(3));
        assertEquals("the page keeps changing", outcome.reason(), "the clock still keeps a DONE from being accepted");
    }

    @Test
    void aDoneOnAPageThatMovedIsDecidedAgain() {
        Policy moveOnce = (call, _) -> {
            if (call == 1) site.title = "Moved";
            return new String[] {"DONE"};
        };
        var outcome = run(moveOnce);

        assertEquals("done", outcome.status(), outcome.format());
        assertEquals(2, outcome.decisions());
        assertEquals("Moved", outcome.page().get("title").getAsString(), "the accepted DONE saw the moved page");
        assertEquals("Moved", bodies.get(1).getAsJsonObject("state").getAsJsonObject("page").get("title").getAsString());
    }

    @Test
    void aRefusedClickIsRecordedAndCountsTowardTheStall() {
        site.button("Noop", () -> {});
        site.button("Find stays", () -> {}).covered = true;
        Policy policy = (_, state) -> state.getAsJsonArray("recent_actions").isEmpty()
                ? click(state, "Noop") : click(state, "Find stays");

        var outcome = run(policy);

        assertEquals("blocked", outcome.status(), outcome.format());
        assertEquals("the last 3 actions were refused or changed nothing; CLICK Find stays was refused: "
                + "Target changed or is covered", outcome.reason());
        assertEquals(0, site.clicks("Find stays"), "no input reaches a covered target");
        var seen = bodies.get(2).getAsJsonObject("state").getAsJsonArray("recent_actions");
        assertEquals("Find stays", seen.get(1).getAsJsonObject().get("action").getAsString());
        assertEquals("Target changed or is covered", seen.get(1).getAsJsonObject().get("refused").getAsString(),
                "Jev sees the refusal among its recent actions");
        assertTrue(outcome.format().contains("2. CLICK Find stays (refused: Target changed or is covered)"),
                outcome.format());
    }

    @Test
    void aRefusedTextEntryIsRecordedAndItsValueReused() {
        var destination = site.textbox("Destination");
        destination.covered = true;
        var outcome = run((_, state) -> new String[] {"TYPE_TEXT", index(state, "Destination")});

        assertEquals("blocked", outcome.status(), outcome.format());
        assertTrue(outcome.reason().contains("TYPE_TEXT Destination was refused"), outcome.reason());
        assertTrue(site.inputs.isEmpty(), "nothing typed into a covered field");
        assertEquals(1, typedFor.size(), "a refused field that is picked again reuses its value");
    }

    @Test
    void aScrollSendsTheActionsSignedWheelDelta() {
        site.scrollable = true;
        Policy policy = (call, _) -> switch (call) {
            case 1 -> new String[] {"SCROLL_DOWN"};
            case 2 -> new String[] {"SCROLL_UP"};
            default -> new String[] {"DONE"};
        };

        var outcome = run(policy);

        assertEquals("done", outcome.status(), outcome.format());
        assertEquals(List.of("scroll 560", "scroll -560"), site.inputs);
        assertTrue(outcome.format().contains("Actions:\n1. SCROLL_DOWN Scroll down\n2. SCROLL_UP Scroll up\n"),
                outcome.format());
    }

    @Test
    void threeWaitsOnAnUnchangedPageAreNotAStall() {
        var outcome = run((call, _) -> call <= 3 ? new String[] {"WAIT"} : new String[] {"DONE"});

        assertEquals("done", outcome.status(), outcome.format());
        assertEquals(3, outcome.steps().size());
        assertTrue(outcome.format().contains("3. WAIT Wait for the page to update (page unchanged)"), outcome.format());
    }

    @Test
    void onALivePageAScrollAndAWaitStillRun() {
        site.live = true;
        site.scrollable = true;
        Policy policy = (call, _) -> switch (call) {
            case 1 -> new String[] {"SCROLL_DOWN"};
            case 2 -> new String[] {"WAIT"};
            default -> new String[] {"DONE"};
        };

        var outcome = run(policy);

        assertEquals(List.of("scroll 560"), site.inputs);
        assertEquals(2, outcome.steps().size(), outcome.format());
        assertTrue(outcome.format().contains("Actions:\n1. SCROLL_DOWN Scroll down\n2. WAIT Wait for the page to update\n"),
                outcome.format());
        assertEquals("the page keeps changing", outcome.reason(), "only DONE still needs the whole page to hold still");
    }

    // --- errors and the result ---------------------------------------------------------------

    @Test
    void aFrozenPageEndsTheRunWithinTheBoundAndReportsWhatRan() {
        site.button("Freeze", () -> site.freezeNextRead = true);
        var frozenHook = new AtomicBoolean();
        var page = new JevPage(site, Duration.ofMillis(300), () -> {
            frozenHook.set(true);
            site.kill();
        });

        long started = System.nanoTime();
        var outcome = run((_, state) -> click(state, "Freeze"), page);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertTrue(frozenHook.get(), "the hook ran");
        assertTrue(page.frozen());
        assertTrue(elapsedMs < 5_000, "ended within the bound, took " + elapsedMs + " ms");
        assertEquals("Error: the page stopped responding; the browser session was closed\n\n"
                + "Actions executed before the error:\n1. CLICK Freeze\n", outcome.format());
    }

    @Test
    void anUnexpectedExceptionKeepsTheExecutedSteps() {
        site.button("Next", site::advance);
        site.textbox("Destination");
        JevRun.TextHelper broken = _ -> {
            throw new IllegalStateException("boom");
        };
        Policy policy = (call, state) -> call == 1
                ? click(state, "Next") : new String[] {"TYPE_TEXT", index(state, "Destination")};

        var outcome = run(jev(policy), new JevPage(site, JevPage.CALL_LIMIT, () -> {}), broken);

        assertEquals("error", outcome.status());
        assertEquals("Error: the run failed unexpectedly (IllegalStateException)\n\n"
                + "Actions executed before the error:\n1. CLICK Next\n", outcome.format());
    }

    @Test
    void aJevFailureBeforeAnyActionIsABareError() {
        Interceptor unavailable = chain -> reply(chain, 500, "{\"error\":\"down\"}");
        var outcome = run(unavailable, new JevPage(site, JevPage.CALL_LIMIT, () -> {}), _ -> "Lisbon");

        assertEquals("Error: Jev returned HTTP 500; no action executed", outcome.format());
    }

    @Test
    void anIsolatedBreakerEndsTheRunBeforeAnythingIsSent() {
        JevApi.breaker().trip();
        var outcome = run((_, _) -> new String[] {"DONE"});

        assertEquals("Error: JEV was isolated by the operator: not calling TypeSafe until the cooldown ends or it "
                + "is restored; no action executed", outcome.format());
        assertTrue(bodies.isEmpty(), "no request while the breaker is isolated");
    }

    @Test
    void aFinishedRunShowsTheFinalPageTheCaveatAndWhatWasLeftOut() {
        site.omitted = 7;
        site.text.add("x".repeat(5000));
        var outcome = run((_, _) -> new String[] {"DONE"});

        var result = outcome.format();
        assertTrue(result.startsWith("Jev run: done after 1 decision.\nFinal page: Stays — https://stays.example/\n"
                + "7 controls on the final page were not offered to Jev, which is shown at most 250.\n"
                + "No actions executed.\n"), result);
        assertTrue(result.contains("DONE and BLOCKED are Jev's judgement, not a verified result"), result);
        assertTrue(result.contains("\nVisible text:\nFind a stay\n"), result);
        assertTrue(result.endsWith("\n[Truncated]"), result);
    }

    // --- the fake browser --------------------------------------------------------------------

    /** A clock the test moves by hand, bound through {@link AppClock#callWith}. */
    private static final class SteppedClock extends Clock {
        private volatile Instant now = Instant.parse("2026-09-22T10:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final class Control {
        final int node;
        final String role;
        final String label;
        final Runnable onClick;
        String value = "";
        boolean covered;
        int clicks;

        Control(int node, String role, String label, Runnable onClick) {
            this.node = node;
            this.role = role;
            this.label = label;
            this.onClick = onClick;
        }
    }

    /**
     * Stands in for Chromium behind JevPage: each {@code Runtime.evaluate} is answered by what the
     * expression is (snapshot, marker, target guard, hit-test, after-input wait), mirroring the
     * shapes {@code conf/browser/jev-snapshot.js} returns, and each input event is recorded.
     */
    private static final class FakePage implements CDPSession {
        String url = "https://stays.example/";
        String title = "Stays";
        final List<String> text = new ArrayList<>(List.of("Find a stay"));
        final Map<Integer, Control> controls = new LinkedHashMap<>();
        final List<String> inputs = new ArrayList<>();
        boolean live;
        boolean scrollable;
        int omitted;
        private int scrollY;
        volatile boolean freezeNextRead;
        private final CountDownLatch killed = new CountDownLatch(1);
        private int ticks;
        private int page;
        private Control target;

        Control button(String label, Runnable onClick) {
            var control = new Control(controls.size() + 1, "button", label, onClick);
            controls.put(control.node, control);
            return control;
        }

        Control textbox(String label) {
            var control = new Control(controls.size() + 1, "textbox", label, () -> {});
            controls.put(control.node, control);
            return control;
        }

        Control control(String label) {
            return controls.values().stream().filter(c -> c.label.equals(label)).findFirst().orElseThrow();
        }

        int clicks(String label) {
            return control(label).clicks;
        }

        void advance() {
            page++;
        }

        void kill() {
            killed.countDown();
        }

        @Override
        public JsonObject send(String method, JsonObject args) {
            var response = new JsonObject();
            switch (method) {
                case "Runtime.evaluate" -> {
                    if (freezeNextRead) {
                        awaitKill();
                        throw new PlaywrightException("Failed to read message from driver, pipe closed.");
                    }
                    var result = new JsonObject();
                    var value = evaluate(args.get("expression").getAsString());
                    if (!value.isJsonNull()) result.add("value", value);
                    response.add("result", result);
                }
                case "Input.dispatchMouseEvent" -> {
                    var type = args.get("type").getAsString();
                    if (type.equals("mousePressed") && target != null) {
                        target.clicks++;
                        inputs.add("click " + target.label);
                        target.onClick.run();
                    } else if (type.equals("mouseWheel")) {
                        int delta = args.get("deltaY").getAsInt();
                        scrollY = Math.max(0, scrollY + delta);
                        inputs.add("scroll " + delta);
                    }
                }
                case "Input.insertText" -> {
                    var typed = args.get("text").getAsString();
                    target.value = typed;
                    inputs.add("type " + typed);
                }
                default -> { /* focus emulation and key events change nothing here */ }
            }
            return response;
        }

        private void awaitKill() {
            try {
                if (!killed.await(10, TimeUnit.SECONDS)) throw new AssertionError("the frozen call was never freed");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }

        private JsonElement evaluate(String expression) {
            if (expression.startsWith("(() => { const state=")) return marker();
            if (expression.startsWith("(() => window.__jevFast?.pageKey()")) return pageKey();
            if (expression.startsWith("(() => { const c=window.__jevFast;")) {
                int from = expression.indexOf("c.nodes.get(") + "c.nodes.get(".length();
                int node = Integer.parseInt(expression.substring(from, expression.indexOf(')', from)));
                var current = new JsonArray();
                current.add(pageKey());
                current.add(guard(controls.get(node)));
                return current;
            }
            if (expression.startsWith("(action => new Promise")) return JsonNull.INSTANCE;
            if (expression.startsWith("(action =>")) {
                var action = JsonParser.parseString(expression.substring(expression.lastIndexOf("})(") + 3,
                        expression.length() - 1)).getAsJsonObject();
                target = controls.get(action.get("node").getAsInt());
                if (target.covered) return JsonNull.INSTANCE;
                var point = new JsonObject();
                point.addProperty("x", 10);
                point.addProperty("y", 10);
                return point;
            }
            return snapshot();
        }

        private String visibleText() {
            var lines = new ArrayList<>(text);
            if (page > 0) lines.add("Page " + page);
            if (live) lines.add("Updated " + ++ticks);
            return String.join("\n", lines);
        }

        private JsonArray pageKey() {
            var key = new JsonArray();
            key.add(url);
            key.add(scrollY);
            controls.values().stream().filter(c -> c.role.equals("textbox")).forEach(c -> key.add(c.value));
            return key;
        }

        private static JsonElement guard(Control control) {
            var guard = new JsonArray();
            guard.add(control.node);
            guard.add(control.label);
            guard.add(control.value);
            return guard;
        }

        private JsonArray marker(String visible) {
            var marker = new JsonArray();
            marker.add(url);
            marker.add(scrollY);
            marker.add(title);
            marker.add(visible);
            controls.values().forEach(c -> marker.add(c.label + "=" + c.value));
            return marker;
        }

        private JsonArray marker() {
            return marker(visibleText());
        }

        private JsonObject snapshot() {
            var visible = visibleText();
            var actions = new JsonArray();
            var guards = new JsonObject();
            for (var c : controls.values()) {
                var base = new JsonObject();
                base.addProperty("node", c.node);
                base.addProperty("role", c.role);
                base.addProperty("label", c.label);
                base.addProperty("value", c.value);
                if (c.role.equals("textbox")) {
                    var fill = base.deepCopy();
                    fill.addProperty("kind", "fill");
                    actions.add(fill);
                    var open = base.deepCopy();
                    open.addProperty("kind", "click");
                    open.addProperty("label", "Open " + c.label);
                    actions.add(open);
                } else {
                    base.addProperty("kind", "click");
                    actions.add(base);
                }
                guards.add(String.valueOf(c.node), guard(c));
            }
            for (int i = 0; i < actions.size(); i++) actions.get(i).getAsJsonObject().addProperty("id", "e" + (i + 1));
            if (scrollable) actions.add(scroll("scroll_down", "Scroll down", 560));
            if (scrollY > 0) actions.add(scroll("scroll_up", "Scroll up", -560));
            var wait = new JsonObject();
            wait.addProperty("id", "wait");
            wait.addProperty("kind", "wait");
            wait.addProperty("label", "Wait for the page to update");
            actions.add(wait);
            var state = new JsonObject();
            state.addProperty("url", url);
            state.addProperty("title", title);
            state.addProperty("text", visible);
            state.add("actions", actions);
            state.add("marker", marker(visible));
            state.add("page_key", pageKey());
            state.add("guards", guards);
            state.add("omitted_actions", new JsonPrimitive(omitted));
            return state;
        }

        private static JsonObject scroll(String id, String label, int delta) {
            var scroll = new JsonObject();
            scroll.addProperty("id", id);
            scroll.addProperty("kind", "scroll");
            scroll.addProperty("label", label);
            scroll.addProperty("delta", delta);
            return scroll;
        }

        @Override
        public void onClose(Consumer<CDPSession> handler) {}

        @Override
        public void offClose(Consumer<CDPSession> handler) {}

        @Override
        public void detach() {}

        @Override
        public void on(String eventName, Consumer<JsonObject> handler) {}

        @Override
        public void off(String eventName, Consumer<JsonObject> handler) {}
    }
}
