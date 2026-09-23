package tools.jev;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.PlaywrightException;
import org.jspecify.annotations.Nullable;
import play.Play;
import utils.RetryScheduler;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

/**
 * The browser half of a Jev run over one page's CDP session: a port of jev-ultrafast's
 * {@code browser.py}, whose page scripts are carried verbatim (MIT, Browser Use; notice in
 * {@code conf/browser/jev-ultrafast-LICENSE}). {@code conf/browser/jev-snapshot.js} reads the
 * visible text and controls, and gives each element a code-owned node id; every input targets one
 * of those ids, after a freshness check against the snapshot the decision was made on and a
 * hit-test at the moment of input. Nothing the model writes becomes a selector, coordinate or
 * script.
 *
 * <p>Between {@link #prepare()} and {@link #finish()} each browser call is bounded: one that
 * outlives the call limit marks the page frozen and runs the {@code onFrozen} hook, which must
 * make the blocked call fail. Playwright is not thread-safe, so the hook may not call Playwright.
 */
public final class JevPage {

    /** The decision no longer describes the page; observe and decide again. Nothing was sent. */
    public static final class StalePage extends RuntimeException {

        public StalePage(String message) {
            super(message);
        }
    }

    static final String SNAPSHOT = "conf/browser/jev-snapshot.js";

    /** How long one browser call may take before the page counts as frozen. */
    public static final Duration CALL_LIMIT = Duration.ofSeconds(30);
    static final String FROZEN_MESSAGE = "the page stopped responding; the browser session was closed";

    private static final ScopedValue<Duration> CALL_LIMIT_OVERRIDE = ScopedValue.newInstance();

    /** {@link #CALL_LIMIT}, or the limit a test bound on this thread with {@link #callWithCallLimitForTest}. */
    public static Duration callLimit() {
        return CALL_LIMIT_OVERRIDE.orElse(CALL_LIMIT);
    }

    /**
     * Test seam (JCLAW-1277): {@link #callLimit()} answers {@code limit} while {@code body} runs on this
     * thread. {@code CapabilityRulesTest} fails the build if anything in {@code app/} calls it.
     */
    public static <T> T callWithCallLimitForTest(Duration limit, Supplier<T> body) {
        if (limit.isZero() || limit.isNegative()) throw new IllegalArgumentException("call limit must be positive: " + limit);
        return ScopedValue.where(CALL_LIMIT_OVERRIDE, limit).call(body::get);
    }

    private static final String ACT = """
            (action => {
              const e=window.__jevFast?.nodes.get(action.node);
              if (!e?.isConnected || e.matches(':disabled') || e.closest('[aria-disabled="true"],[inert]') ||
                  !e.checkVisibility({checkOpacity:true,checkVisibilityCSS:true})) return null;
              if (action.kind==='fill' && (e.readOnly || e.getAttribute('aria-readonly')==='true')) return null;
              const r=e.getBoundingClientRect(), x=r.x+r.width/2, y=r.y+r.height/2;
              if (!r.width || !r.height || x<0 || y<0 || x>=innerWidth || y>=innerHeight) return null;
              if (!e.contains(document.elementFromPoint(x,y))) return null;
              if (action.kind==='select') {
                if (e.tagName!=='SELECT' || ![...e.options].some(o=>o.value===action.value &&
                    !o.disabled && !o.closest('optgroup[disabled]'))) return null;
                e.value=action.value;
                e.dispatchEvent(new Event('input',{bubbles:true}));
                e.dispatchEvent(new Event('change',{bubbles:true}));
              }
              return {x,y};
            })""";

    // Read-only: lets a click or keystroke render (and an autocomplete list open) before the next read.
    private static final String AFTER_INPUT = """
            (action => new Promise(resolve => {
              const field=window.__jevFast?.nodes.get(action.node);
              const autocomplete=action.kind==='fill' && field?.getAttribute('role')==='combobox';
              let frames=0, stopped=false;
              const finish=()=>{stopped=true;resolve()};
              setTimeout(finish,autocomplete ? 200 : 50);
              const ready=()=>{
                if (stopped) return;
                const ids=(field?.getAttribute('aria-controls')||field?.getAttribute('aria-owns')||'')
                  .split(/\\s+/).filter(Boolean);
                const roots=ids.length ? ids.map(id=>document.getElementById(id)).filter(Boolean) : [document];
                const options=roots.flatMap(root=>[...root.querySelectorAll('[role="option"]')]);
                if (++frames>=2 && (!autocomplete || options.some(e=>{
                  const r=e.getBoundingClientRect();
                  return r.width && r.height && r.bottom>0 && r.top<innerHeight &&
                    e.checkVisibility({checkOpacity:true,checkVisibilityCSS:true});
                }))) finish();
                else requestAnimationFrame(ready);
              };
              requestAnimationFrame(ready);
            }))""";

    private static final int OBSERVE_ATTEMPTS = 10;
    private static final long OBSERVE_RETRY_MS = 20;
    private static final long WAIT_MS = 100;
    // Upstream scrolls at this point of its 1120x780 window; it is inside Playwright's default 1280x720 too.
    private static final int WHEEL_X = 550;
    private static final int WHEEL_Y = 650;
    private static final int SELECT_ALL_MODIFIER = System.getProperty("os.name", "").startsWith("Mac") ? 4 : 2;
    // Freshness for these compares the page key and the target's own guard, not the whole-page marker.
    private static final List<String> TARGETED = List.of("click", "fill", "select");
    // These have no target, so the page key alone: text changing elsewhere must not hold them back.
    private static final List<String> UNTARGETED = List.of("scroll", "wait");

    private static volatile @Nullable String snapshotScript;

    private final CDPSession cdp;
    private final long callLimitNanos;
    private final Runnable onFrozen;
    private @Nullable JsonObject afterInput;
    private volatile long callStartedNanos;
    private volatile boolean inCall;
    private volatile boolean watching;
    private volatile boolean frozen;

    /**
     * @param callLimit how long one browser call may block, normally {@link #CALL_LIMIT}
     * @param onFrozen  runs on a scheduler thread when a call outlives {@code callLimit}
     */
    public JevPage(CDPSession cdp, Duration callLimit, Runnable onFrozen) {
        this.cdp = cdp;
        this.callLimitNanos = callLimit.toNanos();
        this.onFrozen = onFrozen;
    }

    /** Start bounding browser calls, and keep animation frames and menus rendering while the page is not focused. */
    public void prepare() {
        watching = true;
        watch(callLimitNanos);
        var params = new JsonObject();
        params.addProperty("enabled", true);
        send("Emulation.setFocusEmulationEnabled", params);
    }

    /** Stop bounding browser calls; the run is over. */
    public void finish() {
        watching = false;
    }

    /** Whether a browser call outlived the limit, so the session behind this page had to be closed. */
    public boolean frozen() {
        return frozen;
    }

    /** Read the page, waiting first for the last input to render. Throws {@link StalePage} if it never settles. */
    public JsonObject observe() {
        var pending = afterInput;
        afterInput = null;
        if (pending != null) {
            try {
                evaluate(AFTER_INPUT + "(" + pending + ")", true);
            } catch (StalePage _) {
                // Navigation interrupted the wait; the read below retries until the new document settles.
            }
        }
        for (int attempt = 1; ; attempt++) {
            try {
                var state = evaluate(snapshot(), false);
                if (state.isJsonObject()) return state.getAsJsonObject();
            } catch (StalePage e) {
                if (attempt >= OBSERVE_ATTEMPTS) throw e;
            }
            if (attempt >= OBSERVE_ATTEMPTS) throw new StalePage("Page did not settle");
            JevClient.pause(OBSERVE_RETRY_MS);
        }
    }

    /**
     * Whether {@code page} still describes the document. A click, text entry or select compares
     * only the page key and its own target's guard, and a scroll or wait only the page key, so an
     * unrelated change elsewhere (a clock, a carousel) does not cost a decision; a DONE or BLOCKED
     * ({@code action} null) compares the whole snapshot marker.
     */
    public boolean fresh(JsonObject page, @Nullable JsonObject action) {
        if (action != null && UNTARGETED.contains(action.get("kind").getAsString())) {
            return page.get("page_key").equals(evaluate("(() => window.__jevFast?.pageKey() ?? null)()", false));
        }
        if (action != null && TARGETED.contains(action.get("kind").getAsString())) {
            var node = node(action);
            if (node == null) return false;
            var current = evaluate("(() => { const c=window.__jevFast; "
                    + "return c ? [c.pageKey(),c.guard(c.nodes.get(" + node + "))] : null; })()", false);
            var expected = new JsonArray();
            expected.add(page.get("page_key"));
            var guard = page.getAsJsonObject("guards").get(String.valueOf(node));
            expected.add(guard == null ? JsonNull.INSTANCE : guard);
            return expected.equals(current);
        }
        return page.get("marker").equals(evaluate(marker(), false));
    }

    /**
     * Execute one observed action. Throws {@link StalePage} before any input when the page moved on
     * or the target is covered, and {@link JevException} when a dropdown write cannot be confirmed:
     * a select mutates inside the script, so it is never repeated blind.
     */
    public void act(JsonObject action, JsonObject page, @Nullable String text) {
        var kind = action.get("kind").getAsString();
        if (kind.equals("fill") && text == null) throw new JevException("No field value; nothing typed");
        if (!fresh(page, action)) throw new StalePage("Page changed since this decision");
        switch (kind) {
            case "wait" -> JevClient.pause(WAIT_MS);
            case "scroll" -> send("Input.dispatchMouseEvent", mouse("mouseWheel", WHEEL_X, WHEEL_Y)
                    .with("deltaX", 0).with("deltaY", action.get("delta").getAsInt()).params);
            default -> input(kind, action, text);
        }
        afterInput = kind.equals("wait") ? null : action;
    }

    private void input(String kind, JsonObject action, @Nullable String text) {
        if (node(action) == null) throw new JevException("Invalid observed node");
        JsonElement target;
        try {
            target = evaluate(ACT + "(" + action + ")", false);
        } catch (StalePage e) {
            if (kind.equals("select")) throw new JevException("Dropdown execution was interrupted; inspect before retrying");
            throw e;
        }
        if (!target.isJsonObject()) {
            if (kind.equals("select")) throw new JevException("Dropdown execution was not confirmed; inspect before retrying");
            throw new StalePage("Target changed or is covered");
        }
        if (kind.equals("select")) return;
        double x = target.getAsJsonObject().get("x").getAsDouble();
        double y = target.getAsJsonObject().get("y").getAsDouble();
        for (var type : List.of("mousePressed", "mouseReleased")) {
            send("Input.dispatchMouseEvent", mouse(type, x, y).with("button", "left").with("clickCount", 1).params);
        }
        if (kind.equals("fill") && text != null) {
            var commands = new JsonArray();
            commands.add("selectAll");
            send("Input.dispatchKeyEvent", key("keyDown").with("commands", commands).params);
            send("Input.dispatchKeyEvent", key("keyUp").params);
            send("Input.insertText", new Params().with("text", text).params);
        }
    }

    private JsonElement evaluate(String expression, boolean awaitPromise) {
        var params = new Params().with("expression", expression).with("returnByValue", true)
                .with("awaitPromise", awaitPromise).params;
        JsonObject response;
        try {
            response = send("Runtime.evaluate", params);
        } catch (PlaywrightException _) {
            throw new StalePage("Document changed during evaluation");
        }
        if (response.has("exceptionDetails")) throw new StalePage("Document changed during evaluation");
        var result = response.getAsJsonObject("result");
        return result != null && result.has("value") ? result.get("value") : JsonNull.INSTANCE;
    }

    private JsonObject send(String method, JsonObject params) {
        if (frozen) throw new JevException(FROZEN_MESSAGE);
        callStartedNanos = System.nanoTime();
        inCall = true;
        try {
            return cdp.send(method, params);
        } catch (PlaywrightException e) {
            // The hook kills the driver, which fails the blocked call; report why rather than a closed pipe.
            if (frozen) throw new JevException(FROZEN_MESSAGE);
            throw e;
        } finally {
            inCall = false;
        }
    }

    /** One pending check per run, rescheduled for the moment the current call would reach the limit. */
    private void watch(long delayNanos) {
        RetryScheduler.schedule(() -> {
            check();
            return null;
        }, Math.max(1, delayNanos / 1_000_000));
    }

    private void check() {
        if (!watching || frozen) return;
        if (!inCall) {
            watch(callLimitNanos);
            return;
        }
        long elapsed = System.nanoTime() - callStartedNanos;
        if (elapsed < callLimitNanos) {
            watch(callLimitNanos - elapsed);
            return;
        }
        frozen = true;
        onFrozen.run();
    }

    private static @Nullable Integer node(JsonObject action) {
        var node = action.get("node");
        if (node == null || !node.isJsonPrimitive() || !node.getAsJsonPrimitive().isNumber()) return null;
        double value = node.getAsDouble();
        return value == Math.rint(value) && Math.abs(value) < Integer.MAX_VALUE ? (int) value : null;
    }

    private static Params mouse(String type, double x, double y) {
        return new Params().with("type", type).with("x", x).with("y", y);
    }

    private static Params key(String type) {
        return new Params().with("type", type).with("key", "a").with("code", "KeyA")
                .with("modifiers", SELECT_ALL_MODIFIER);
    }

    private static String snapshot() {
        var script = snapshotScript;
        if (script == null) {
            try {
                script = withoutNotice(Files.readString(Play.getFile(SNAPSHOT).toPath()));
            } catch (IOException | RuntimeException _) {
                throw new JevException("The page reader " + SNAPSHOT + " is missing");
            }
            snapshotScript = script;
        }
        return script;
    }

    private static String marker() {
        return "(() => { const state=" + snapshot() + "; return state?.marker ?? null; })()";
    }

    /** The bundled file opens with its MIT notice; the page is sent only the expression after it. */
    static String withoutNotice(String source) {
        var trimmed = source.strip();
        return trimmed.startsWith("/*") ? trimmed.substring(trimmed.indexOf("*/") + 2).strip() : trimmed;
    }

    private static final class Params {
        final JsonObject params = new JsonObject();

        Params with(String key, String value) {
            params.addProperty(key, value);
            return this;
        }

        Params with(String key, Number value) {
            params.addProperty(key, value);
            return this;
        }

        Params with(String key, boolean value) {
            params.addProperty(key, value);
            return this;
        }

        Params with(String key, JsonElement value) {
            params.add(key, value);
            return this;
        }
    }
}
