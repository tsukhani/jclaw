package tools;

import agents.ToolAction;
import agents.ToolRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.ServiceWorkerPolicy;
import models.Agent;
import org.jspecify.annotations.Nullable;
import services.AgentService;
import services.EventLogger;
import services.browser.BrowserSetup;
import services.browser.PlaywrightDriverDir;
import services.browser.PlaywrightNode;
import tools.jev.JevPage;
import tools.jev.JevRun;
import tools.jev.JevSettings;
import utils.AppClock;
import utils.SsrfGuard;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Headless Chromium browser automation for JS-heavy pages.
 * Each agent gets an isolated browser session with lazy initialization and idle cleanup.
 *
 * <p><b>Outside the {@code shell.sandbox} boundary (JCLAW-1153).</b> Two independent
 * reasons, either sufficient: Playwright's Java client spawns the driver itself, so
 * there is no argv for {@link HarnessSandbox#wrap} to prefix; and under a macOS
 * Seatbelt profile Chromium's own child-process sandbox cannot initialize
 * ("sandbox initialization failed: Operation not permitted") and the browser aborts
 * with "GPU process isn't usable. Goodbye." — it launches only with
 * {@code --no-sandbox}, trading per-renderer confinement against a hostile page for
 * a coarse filesystem jail. Under Linux bwrap it does launch. Confining this tool
 * means confining the whole JVM, which is the operator-side mitigation
 * {@link ShellExecTool}'s posture Javadoc already names.
 */
public class PlaywrightBrowserTool implements ToolRegistry.Tool {

    private static final int MAX_TEXT_LENGTH = 50_000;
    private static final long IDLE_TIMEOUT_MS = 5L * 60 * 1000; // 5 minutes
    private static final ConcurrentHashMap<String, SessionHolder> sessions = new ConcurrentHashMap<>();
    private static final double TAB_WAIT_MS = 5_000;

    // Action names dispatched in execute()
    private static final String ACTION_NAVIGATE = "navigate";
    private static final String ACTION_CLICK = "click";
    private static final String ACTION_FILL = "fill";
    private static final String ACTION_GET_TEXT = "getText";
    private static final String ACTION_SCREENSHOT = "screenshot";
    private static final String ACTION_EVALUATE = "evaluate";
    private static final String ACTION_RUN = "run";
    private static final String ACTION_CLOSE = "close";

    /**
     * Every valid action, single-sourced so the schema enum and the unknown-action
     * error cannot drift. The enum is advisory — JCLAW-905 recorded a model sending
     * action names outside it and burning nine calls guessing — so the error has to
     * name the alternatives rather than only reject the input.
     */
    private static final List<String> ACTIONS = List.of(
            ACTION_NAVIGATE, ACTION_CLICK, ACTION_FILL, ACTION_GET_TEXT,
            ACTION_SCREENSHOT, ACTION_EVALUATE, ACTION_CLOSE);

    /** While Jev is the engine (JCLAW-1274) it chooses every step, so the selector actions are withheld. */
    private static final List<String> JEV_ACTIONS = List.of(ACTION_RUN, ACTION_CLOSE);

    private static final List<ToolAction> ACTION_CATALOG = List.of(
            new ToolAction(ACTION_NAVIGATE,   "Load a URL, wait for network idle, and return page text"),
            new ToolAction(ACTION_CLICK,      "Click a DOM element by CSS selector"),
            new ToolAction(ACTION_FILL,       "Fill a form field with a value by CSS selector"),
            new ToolAction(ACTION_GET_TEXT,    "Extract the text content of a CSS selector"),
            new ToolAction(ACTION_SCREENSHOT, "Capture a full-page screenshot and save it to the workspace"),
            new ToolAction(ACTION_EVALUATE,   "Execute a JavaScript expression and return the result"),
            new ToolAction(ACTION_RUN,        "Open a URL and let Jev carry out a goal on it, step by step"),
            new ToolAction(ACTION_CLOSE,      "Close the browser session and free all resources"));

    // JSON argument keys
    private static final String ARG_ACTION = "action";
    private static final String ARG_SELECTOR = "selector";
    private static final String ARG_URL = "url";
    private static final String ARG_GOAL = "goal";

    /**
     * The live browser resources for one agent session. Immutable; a relaunch builds a fresh
     * instance rather than mutating this one.
     *
     * <p>{@code proxy} is the SOCKS5 screen this Chromium was launched behind (JCLAW-1283); it
     * belongs to the session and {@link #destroySession} closes it.
     *
     * <p>{@code driver} is the Node process behind {@code playwright}, or null when it could not be
     * identified; killing it is the only way to free a thread blocked on a frozen page (JCLAW-1274).
     */
    private record BrowserSession(Playwright playwright, @Nullable ProcessHandle driver, Browser browser, Page page,
                                  CDPSession cdp, BrowserScreenProxy proxy) {
    }

    /** A Playwright client and its Node driver process, or a null process when it could not be identified. */
    public record Driver(Playwright playwright, @Nullable ProcessHandle process) {
    }

    // Held across Playwright.create so the one child process that appears during it is that driver.
    private static final ReentrantLock DRIVER_LAUNCH_LOCK = new ReentrantLock();

    /**
     * Per-agent session slot held in the {@link #sessions} map. The map only
     * ever stores holders, and installing one is an O(1), non-blocking
     * {@code computeIfAbsent} — the blocking work (browser launch in seconds,
     * Chromium install in minutes) runs under {@link #lock}, never inside a
     * ConcurrentHashMap bin-node monitor where a synchronized-around-blocking-IO
     * stall would pin the virtual-thread carrier (JCLAW-821).
     *
     * <p>{@link #lock} is stable for the agent's lifetime and is held for the
     * full duration of each {@code execute()} call, so parallel browser tool
     * calls in one streaming round (dispatched on separate virtual threads by
     * {@link agents.AgentRunner#executeToolsParallel}) serialize against the
     * same {@link Page} — Playwright's Page is not thread-safe — and a launch or
     * teardown can never overlap a live Page op. Concurrent navigate / screenshot
     * calls on the same Page otherwise corrupt its internal request map and
     * surface as {@code "Object doesn't exist: request@<hash>"}.
     *
     * <p>{@link #session} is the live browser, lazily launched and {@code null}
     * before first use and after teardown; {@link #lastUsed} drives idle cleanup;
     * {@link #removed} fences a holder retired from the map so a thread that
     * captured it just before removal retries instead of launching into an orphan.
     * All three are guarded by {@link #lock} ({@code lastUsed} is also read racily
     * by {@link #cleanupIdleSessions}'s idle fast-path, hence volatile).
     *
     * <p>{@link #log} lives as long as the holder, so a relaunch or a failed launch keeps its event-log
     * caps and pending notes (JCLAW-1280).
     */
    private static final class SessionHolder {
        final ReentrantLock lock = new ReentrantLock();
        final BrowserScreenLog log;
        @Nullable BrowserSession session;
        volatile long lastUsed = System.currentTimeMillis();
        boolean removed;

        SessionHolder(String agentName) {
            log = new BrowserScreenLog(agentName);
        }
    }

    @Override
    public String name() { return "browser"; }

    @Override
    public String category() { return "Web"; }

    @Override
    public String icon() { return "browser"; }

    @Override
    public String shortDescription() {
        if (JevSettings.active()) return "Headless browser for JavaScript-heavy pages, where Jev carries out a goal.";
        return "Headless browser automation for SPAs, login flows, and JavaScript-heavy pages.";
    }

    /** The actions on offer this turn; the schema, the catalog and the refusal all read it. */
    private static List<String> validActions() {
        return JevSettings.active() ? JEV_ACTIONS : ACTIONS;
    }

    @Override
    public List<ToolAction> actions() {
        var valid = validActions();
        return ACTION_CATALOG.stream().filter(a -> valid.contains(a.name())).toList();
    }

    @Override
    public String description() {
        if (JevSettings.active()) {
            return """
                    Headless browser driven by the Jev engine. Call run with a url and a goal: Jev opens the \
                    page and chooses every click, text entry, dropdown choice and scroll itself until it judges \
                    the goal done or blocked, then returns the final page. Your own model writes the text Jev \
                    types, from the goal, so put every value to enter in it. Write the goal as explicit, ordered \
                    steps naming the controls to use: fill the fields, submit the search, set the filters, then \
                    open the result. "Use the destination search: type Lisbon, submit it, set the category to \
                    Design, tick Free cancellation, then open Casa Flora" works where "Find Design stays in \
                    Lisbon" can leave the search unsubmitted. Jev never sees or fills password fields, so it \
                    cannot log in: when a page needs a login, tell the operator rather than retrying. DONE is \
                    Jev's judgement, not a check: compare the returned page with the goal. Actions: run, close.""";
        }
        return """
                Headless browser for JavaScript-heavy web pages. \
                Use this when web_fetch returns incomplete content (SPAs, dynamic pages, login flows). \
                Actions: navigate, click, fill, getText, screenshot, evaluate, close.""";
    }

    @Override
    public Map<String, Object> parameters() {
        if (JevSettings.active()) {
            return Map.of(
                    SchemaKeys.TYPE, SchemaKeys.OBJECT,
                    SchemaKeys.PROPERTIES, Map.of(
                            ARG_ACTION, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                    SchemaKeys.ENUM, JEV_ACTIONS,
                                    SchemaKeys.DESCRIPTION, "The browser action to perform"),
                            ARG_URL, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                    SchemaKeys.DESCRIPTION, "Page to open before Jev starts (required for run)"),
                            ARG_GOAL, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                    SchemaKeys.DESCRIPTION, "What Jev should do, as explicit ordered steps with "
                                            + "every value to enter (required for run)")
                    ),
                    SchemaKeys.REQUIRED, List.of(ARG_ACTION)
            );
        }
        return Map.of(
                SchemaKeys.TYPE, SchemaKeys.OBJECT,
                SchemaKeys.PROPERTIES, Map.of(
                        ARG_ACTION, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.ENUM, ACTIONS,
                                SchemaKeys.DESCRIPTION, "The browser action to perform"),
                        "url", Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.DESCRIPTION, "URL to navigate to (for navigate action)"),
                        ARG_SELECTOR, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.DESCRIPTION, "CSS selector (for click, fill, getText actions)"),
                        "value", Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.DESCRIPTION, "Value to fill (for fill action)"),
                        "expression", Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.DESCRIPTION, "JavaScript expression (for evaluate action)")
                ),
                SchemaKeys.REQUIRED, List.of(ARG_ACTION)
        );
    }

    @Override
    public String execute(String argsJson, Agent agent) {
        var args = JsonParser.parseString(argsJson).getAsJsonObject();
        var action = args.get(ARG_ACTION).getAsString();

        // "close" tears down the session; don't re-create one just to close it.
        if (ACTION_CLOSE.equals(action)) {
            closeSession(agent.name);
            return "Browser session closed.";
        }

        // One read decides both what is refused and what runs, so a Settings change mid-call cannot split them.
        var jevKey = JevSettings.activeKey();
        var valid = jevKey != null ? JEV_ACTIONS : ACTIONS;
        if (!valid.contains(action)) {
            return refusal(action, valid);
        }
        if (ACTION_RUN.equals(action) && (isBlank(args, ARG_URL) || isBlank(args, ARG_GOAL))) {
            return "Error: run needs both a url and a goal.";
        }

        // An unsafe entry URL is refused here, before a browser is spun up. Every connection the
        // session then opens — this one included — is screened again by the proxy (JCLAW-1283),
        // which is also where the DNS pin lives now.
        if (ACTION_NAVIGATE.equals(action) || ACTION_RUN.equals(action)) {
            try {
                SsrfGuard.assertUrlSafe(args.get(ARG_URL).getAsString());
            } catch (SecurityException e) {
                return "Error: " + e.getMessage();
            }
        }

        // Acquire the per-agent lock BEFORE touching the browser so parallel
        // tool calls in the same round (e.g. navigate + screenshot dispatched by
        // executeToolsParallel) run serially against the same Page (Playwright's
        // Page is not thread-safe), AND so the blocking browser launch/relaunch
        // runs under this lock rather than inside a ConcurrentHashMap bin monitor
        // (JCLAW-821). Installing the holder is O(1); ensureSession does the launch.
        var holder = acquireHolder(agent.name);
        BrowserSession session = null;
        // A closed tab's address is opened with whichever action this engine offers.
        var openWith = jevKey != null ? ACTION_RUN : ACTION_NAVIGATE;
        try {
            session = ensureSession(holder, agent.name);
            if (ACTION_RUN.equals(action) && jevKey != null) {
                return withNote(holder, session, run(holder, session, args.get(ARG_URL).getAsString(),
                        args.get(ARG_GOAL).getAsString(), jevKey, agent), openWith);
            }
            var page = session.page();
            return withNote(holder, session, switch (action) {
                case ACTION_NAVIGATE -> navigate(page, args.get(ARG_URL).getAsString());
                case ACTION_CLICK -> click(page, args.get(ARG_SELECTOR).getAsString());
                case ACTION_FILL -> fill(page, args.get(ARG_SELECTOR).getAsString(),
                                    args.get("value").getAsString());
                case ACTION_GET_TEXT -> getText(page,
                        args.has(ARG_SELECTOR) ? args.get(ARG_SELECTOR).getAsString() : "body");
                case ACTION_SCREENSHOT -> screenshot(page, agent.name, agent.id);
                case ACTION_EVALUATE -> evaluate(page, args.get("expression").getAsString());
                default -> refusal(action, valid);
            }, openWith);
        } catch (PlaywrightException e) {
            return withNote(holder, session, "Browser error: %s".formatted(e.getMessage()), openWith);
        } catch (Exception e) {
            return withNote(holder, session, "Error: %s".formatted(e.getMessage()), openWith);
        } finally {
            holder.lock.unlock();
        }
    }

    /**
     * {@code result} followed by what the screen did since the previous result (JCLAW-1280), if anything.
     * A tab Chromium announced is awaited first, so the action that opened it is the one that reports it.
     */
    private static String withNote(SessionHolder holder, @Nullable BrowserSession session, String result,
                                   String openWith) {
        var log = holder.log;
        // A retired session's driver may be dead, and a call into it would fail or hang.
        if (session != null && holder.session == session && log.tabsOpening()) {
            try {
                session.page().context().waitForCondition(() -> !log.tabsOpening(),
                        new BrowserContext.WaitForConditionOptions().setTimeout(TAB_WAIT_MS));
            } catch (PlaywrightException _) {
                log.forgetOpeningTabs();
            }
        }
        var note = log.drainNote(openWith);
        return note.isEmpty() ? result : result + "\n\n" + note;
    }

    private static String refusal(String action, List<String> valid) {
        var reason = valid.equals(JEV_ACTIONS) && ACTIONS.contains(action)
                ? "Action '%s' is not available while Jev drives the browser".formatted(action)
                : "Unknown action '%s'".formatted(action);
        return "Error: %s. Valid actions: %s".formatted(reason, String.join(", ", valid));
    }

    private static boolean isBlank(JsonObject args, String key) {
        var value = args.get(key);
        return value == null || !value.isJsonPrimitive() || value.getAsString().isBlank();
    }

    /**
     * Navigate, returning the refusal when the URL is unsafe and null once the page has loaded. With
     * {@code idleOptional}, a page that never goes network-idle (a news site's polling) counts as loaded.
     */
    private static @Nullable String load(Page page, String url, boolean idleOptional) {
        // JCLAW-116: validate the entry URL before handing it to Chromium.
        // The route interceptor installed by screen() catches subresources
        // and redirects, but the top-level URL is still checked here so we
        // can surface a clean error to the agent without spinning up the nav.
        try {
            SsrfGuard.assertUrlSafe(url);
        } catch (SecurityException e) {
            return "Error: " + e.getMessage();
        }
        page.navigate(url);
        try {
            page.waitForLoadState(LoadState.NETWORKIDLE);
        } catch (TimeoutError e) {
            if (!idleOptional) throw e;
        }
        return null;
    }

    private String navigate(Page page, String url) {
        var refused = load(page, url, false);
        if (refused != null) return refused;
        var title = page.title();
        var text = page.textContent("body");
        if (text != null && text.length() > MAX_TEXT_LENGTH) {
            text = text.substring(0, MAX_TEXT_LENGTH) + "\n[Truncated]";
        }
        return "Page: %s\n\n%s".formatted(title, text != null ? text : "(empty page)");
    }

    /**
     * Jev mode (JCLAW-1274): open {@code url} through the same guarded path as navigate, then let Jev
     * drive. A browser call that outlives {@link JevPage#callLimit()} kills the driver to free this
     * thread, and the session is closed so the next call and shutdown start clean. Runs under
     * {@code holder.lock}.
     */
    private static String run(SessionHolder holder, BrowserSession session, String url, String goal, String apiKey,
                              Agent agent) {
        var driver = session.driver();
        if (driver == null) {
            return "Error: the browser driver could not be identified, so a frozen page could not be stopped. "
                    + "Ask the operator to switch Settings → Browser to Playwright.";
        }
        var refused = load(session.page(), url, true);
        if (refused != null) return refused;
        var jev = new JevPage(session.cdp(), JevPage.callLimit(), () -> killDriver(driver));
        var result = JevRun.run(jev, apiKey, goal, JevRun.agentModel(agent), agent.name).format();
        // A run can last minutes; without this the idle sweep could retire the session just after it.
        holder.lastUsed = AppClock.now().toEpochMilli();
        if (jev.frozen()) retire(holder, agent.name);
        return result;
    }

    private String click(Page page, String selector) {
        page.locator(selector).first().click();
        page.waitForLoadState(LoadState.NETWORKIDLE);
        var title = page.title();
        return "Clicked '%s'. Page: %s".formatted(selector, title);
    }

    private String fill(Page page, String selector, String value) {
        page.locator(selector).first().fill(value);
        return "Filled '%s' with value.".formatted(selector);
    }

    private String getText(Page page, String selector) {
        var text = page.locator(selector).first().textContent();
        if (text != null && text.length() > MAX_TEXT_LENGTH) {
            text = text.substring(0, MAX_TEXT_LENGTH) + "\n[Truncated]";
        }
        return text != null ? text : "(no text content)";
    }

    private String screenshot(Page page, String agentName, Long agentId) {
        var timestamp = AppClock.now().toEpochMilli();
        var filename = "screenshot-%d.png".formatted(timestamp);
        var path = AgentService.workspacePath(agentName).resolve(filename);
        page.screenshot(new Page.ScreenshotOptions().setPath(path).setFullPage(true));

        var url = "/api/agents/%d/files/%s".formatted(agentId, filename);
        return formatScreenshotResult(url);
    }

    /**
     * Build the tool-result string for a captured screenshot. The markdown image
     * tag is included so {@code AgentRunner.extractImageUrls} picks it up — the
     * runtime then guarantees both the inline image (via
     * {@code buildImagePrefix}) and a download link below (via
     * {@code buildDownloadSuffix}) in the assistant message. The instruction
     * describes what's already handled so the LLM can focus on the
     * page-description portion of its reply rather than juggling embed / link
     * directives.
     *
     * <p>Pre-JCLAW-104 this string carried two explicit directives ("Do NOT
     * re-embed" and "SHOULD include the link") that papered over two gaps in
     * the runtime — the first fixed by filename-aware dedup in buildImagePrefix
     * + the Telegram planner canonical-path dedup, the second fixed by the
     * deterministic download-link suffix. Both directives became redundant
     * once the runtime guarantees those outcomes, and the "SHOULD include the
     * link" directive became actively harmful (some models produced duplicate
     * links). The replacement is descriptive rather than prescriptive.
     *
     * <p>Exposed for unit tests; not part of the public tool API.
     */
    public static String formatScreenshotResult(String url) {
        // The one remaining directive — "don't quote the file path" — targets
        // a specific chatty-model failure mode (observed with Kimi-K2.5 on
        // Telegram): models sometimes echo the URL from the tool return as
        // a parenthetical like "(Screenshot file saved to: /api/agents/1/...)"
        // which renders in Telegram as a plain-text monospace blob with no
        // clickable affordance. The runtime handles the image display and
        // download surface on both channels, so the path is internal
        // bookkeeping the LLM shouldn't surface.
        return ("![Screenshot](%s)\n"
                + "[Screenshot captured. Don't quote the file path in your reply — "
                + "the user already sees the image.]").formatted(url);
    }

    private String evaluate(Page page, String expression) {
        var result = page.evaluate(expression);
        return result != null ? result.toString() : "null";
    }

    // --- Session management ---

    /**
     * Return this agent's holder with its lock held (the caller must unlock).
     * Installing the holder is an O(1), non-blocking {@code computeIfAbsent}; the
     * actual browser launch is deferred to {@link #ensureSession} under the
     * returned lock (JCLAW-821). Retries if the holder is retired from the map
     * between the {@code computeIfAbsent} and the {@code lock()} — otherwise a
     * launch into that orphaned holder would leak (its browser would be tracked
     * by no map entry).
     */
    private static SessionHolder acquireHolder(String agentName) {
        while (true) {
            var holder = sessions.computeIfAbsent(agentName, SessionHolder::new);
            holder.lock.lock();
            if (!holder.removed) {
                return holder;
            }
            // Retired by a concurrent close / idle-cleanup between the map lookup
            // and the lock; drop it and re-resolve the current holder.
            holder.lock.unlock();
        }
    }

    /**
     * Launch or reuse this agent's browser as needed, returning the live session.
     * Runs under {@code holder.lock} (held by the caller), so the blocking launch
     * never executes inside a ConcurrentHashMap callback (JCLAW-821). Refreshes
     * {@code lastUsed} so idle cleanup sees the activity.
     */
    private static BrowserSession ensureSession(SessionHolder holder, String agentName) {
        holder.lastUsed = System.currentTimeMillis();
        var existing = holder.session;
        if (existing != null && existing.page().isClosed()) {
            destroySession(existing, agentName);
            holder.session = null;
            existing = null;
        }
        if (existing != null) {
            return existing;
        }
        holder.session = launchSession(agentName, holder.log);
        return holder.session;
    }

    /**
     * Launch a fresh headless Chromium session behind its own screening proxy
     * (JCLAW-1283). {@code <-loopback>} is subtracted from the bypass list because
     * Chromium otherwise dials loopback directly, unscreened.
     */
    // MustBeClosed: the proxy outlives this method by design — the session owns it and
    // destroySession closes it, as the guarded teardown below does on a partial launch.
    @SuppressWarnings("MustBeClosed")
    private static BrowserSession launchSession(String key, BrowserScreenLog log) {
        EventLogger.info("tool", key, null, "Launching headless browser");
        // A tab the previous browser announced will never be closed by this one.
        log.forgetOpeningTabs();
        // Build the driver -> browser -> screened context -> page chain under a guard: a
        // failure partway through must best-effort close whatever OS processes
        // were already spawned before rethrowing. launchSession runs under the
        // holder lock before holder.session is assigned, so a throw leaves NO
        // reference to the partial browser — nothing downstream would ever tear
        // these down, so an unguarded partial construction leaks the Playwright
        // driver + Chromium processes for the JVM lifetime. Mirrors
        // destroySession's independent-close teardown.
        Playwright playwright = null;
        Browser browser = null;
        Page page = null;
        BrowserScreenProxy proxy = null;
        try {
            // Before the install and the driver: a browser nothing screens must not be launched, and
            // one that will be refused must not be downloaded first either.
            try {
                if (screenFailsForTest()) throw new IOException("bound port in use");
                proxy = new BrowserScreenProxy(log);
            } catch (IOException e) {
                throw new IllegalStateException("the browser's network screen could not start: " + e.getMessage(), e);
            }
            ensureBrowserReady();
            var driver = startDriver();
            playwright = driver.playwright();
            if (driver.process() == null && JevSettings.active()) {
                EventLogger.warn("tool", key, null,
                        "Browser driver process not identified; a Jev run on a frozen page cannot be stopped");
            }
            // JCLAW-172: headless is hardcoded — there is no UX where running a
            // visible browser on the host serves an LLM-driven agent. The
            // previous {@code playwright.headless} config key is gone.
            var launchOptions = new BrowserType.LaunchOptions().setHeadless(true).setArgs(launchArgs(proxy.port()));
            browser = playwright.chromium().launch(launchOptions);
            page = openScreenedPage(browser, log);
            var cdp = page.context().newCDPSession(page);
            // Chromium announces a tab while the click that opens it is still running; Playwright's page event
            // waits for the tab's first response, which can land after the tool has returned.
            cdp.send("Page.enable");
            cdp.on("Page.windowOpen", _ -> log.tabOpening());
            return new BrowserSession(playwright, driver.process(), browser, page, cdp, proxy);
        } catch (RuntimeException e) {
            // Best-effort teardown of whatever was constructed, newest first,
            // each guarded independently (mirrors destroySession).
            if (page != null) { try { page.close(); } catch (Exception _) { /* best-effort */ } }
            if (browser != null) { try { browser.close(); } catch (Exception _) { /* best-effort */ } }
            if (playwright != null) { try { playwright.close(); } catch (Exception _) { /* best-effort */ } }
            if (proxy != null) { try { proxy.close(); } catch (Exception _) { /* best-effort */ } }
            throw e;
        }
    }

    private static final ScopedValue<Boolean> SCREEN_FAILS_FOR_TEST = ScopedValue.newInstance();

    /** Whether a test asked this thread's next launch to find its screen unable to start. */
    private static boolean screenFailsForTest() {
        return SCREEN_FAILS_FOR_TEST.isBound();
    }

    /**
     * Test seam (JCLAW-1288): run {@code body} with the network screen failing to start, so the one
     * path that must never launch a browser can be taken on demand — an ephemeral bind does not fail
     * when asked. Bound on this thread only, which is the thread {@code launchSession} runs on.
     * {@code CapabilityRulesTest} fails the build if anything in {@code app/} calls it.
     *
     * <p>It stands in for the constructor, not for the throw: a future constructor that failed with
     * an unchecked exception would still fail the launch closed, through the outer guard, but would
     * no longer carry the message this seam's test asserts.
     */
    public static <T> T callWithFailingScreenForTest(Supplier<T> body) {
        return ScopedValue.where(SCREEN_FAILS_FOR_TEST, Boolean.TRUE).call(body::get);
    }

    /**
     * What the screen costs at launch: the proxy every connection goes through, and the two flags that
     * decide what reaches it. Both were measured on 2026-09-23 and neither announces a mistake.
     *
     * <p>{@code <-loopback>} subtracts Chromium's implicit bypass, which covers more than its name says:
     * 169.254.169.254 reaches the proxy with the flag and is dialled directly without it, so dropping it
     * would unscreen the cloud-metadata address. WebRTC is UDP, which a SOCKS5 CONNECT proxy cannot carry:
     * without the third flag, STUN, TURN and a data channel's connectivity checks all reached loopback
     * listeners the proxy never saw (JCLAW-1286). Chromium honours only the {@code force} spelling and
     * ignores {@code --webrtc-ip-handling-policy} silently, so a wrong value fails no faster than a
     * missing one — which is why a live test holds the behaviour and an ungated one holds this list.
     *
     * <p>Exposed for tests.
     */
    public static List<String> launchArgs(int proxyPort) {
        return List.of("--proxy-server=socks5://127.0.0.1:" + proxyPort,
                "--proxy-bypass-list=<-loopback>",
                "--force-webrtc-ip-handling-policy=disable_non_proxied_udp");
    }

    /**
     * The session's page (JCLAW-1276): a context whose every page is screened, with service workers
     * blocked and popups closed, each refusal and closed tab recorded in {@code log}. Exposed for tests,
     * so they exercise the wiring production uses.
     */
    public static Page openScreenedPage(Browser browser, BrowserScreenLog log) {
        var context = browser.newContext(screenedContextOptions());
        screen(context, log);
        var page = context.newPage();
        closePopups(context, page, log);
        return page;
    }

    /** A service worker's fetches bypass Playwright routing, so workers are blocked. Exposed for tests. */
    public static Browser.NewContextOptions screenedContextOptions() {
        return new Browser.NewContextOptions().setServiceWorkers(ServiceWorkerPolicy.BLOCK);
    }

    /**
     * Abort any request whose URL fails the SSRF guard (JCLAW-116): subresources aimed at private
     * networks, redirects to unsafe hosts, and navigations that bypass {@code navigate()}. Installed
     * on the context rather than the page (JCLAW-1276), so a popup or new tab is screened too. Each
     * refusal is recorded in {@code log} (JCLAW-1280). The proxy underneath screens every connection
     * the page cannot reach around; this screens the URL a request carries, which SOCKS5 does not
     * see (JCLAW-1283). Exposed for tests.
     */
    public static void screen(BrowserContext context, BrowserScreenLog log) {
        context.route("**/*", route -> {
            var url = route.request().url();
            try {
                SsrfGuard.assertUrlSafe(url);
            } catch (SecurityException e) {
                route.abort();
                log.refused(url, e instanceof SsrfGuard.BlockedAddressException);
                return;
            }
            route.resume();
        });
    }

    /**
     * Close every page but {@code primary}, recording it in {@code log} first: the tool never reads a
     * popup, so it only widens reach. Exposed for tests.
     */
    public static void closePopups(BrowserContext context, Page primary, BrowserScreenLog log) {
        context.onPage(opened -> {
            if (opened == primary) return;
            var url = opened.url();
            // A tab whose navigation the route refused sits on Chromium's error page instead of its address.
            if (url.startsWith("chrome-error:")) {
                log.refusedTabClosed();
            } else {
                log.tabClosed(url);
            }
            opened.close();
        });
    }

    /** Start a Playwright client, recording its Node driver process. Exposed for tests. */
    public static Driver startDriver() {
        DRIVER_LAUNCH_LOCK.lock();
        try {
            PlaywrightDriverDir.pin();
            var before = ProcessHandle.current().children().map(ProcessHandle::pid).collect(Collectors.toSet());
            var env = new HashMap<String, String>();
            env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
            var node = BrowserSetup.driver().node();
            if (node != null) env.put("PLAYWRIGHT_NODEJS_PATH", node.toString());
            var playwright = Playwright.create(new Playwright.CreateOptions().setEnv(env));
            var drivers = ProcessHandle.current().children()
                    .filter(p -> !before.contains(p.pid()))
                    .filter(p -> p.info().arguments().map(a -> List.of(a).contains("run-driver")).orElse(false))
                    .toList();
            return new Driver(playwright, drivers.size() == 1 ? drivers.getFirst() : null);
        } finally {
            DRIVER_LAUNCH_LOCK.unlock();
        }
    }

    /**
     * Kill a driver and everything it started, which fails any call blocked on it. Calls no
     * Playwright method, so it is safe from any thread. Exposed for tests.
     */
    public static void killDriver(@Nullable ProcessHandle driver) {
        if (driver == null) return;
        // Listed before the driver dies: its children are re-parented once it is gone.
        driver.descendants().toList().forEach(ProcessHandle::destroyForcibly);
        driver.destroyForcibly();
    }

    public static void closeSession(String agentName) {
        // Acquire the holder lock before tearing down so an in-flight Page op
        // from another thread finishes cleanly (Playwright close() during a
        // live request would surface as "Object doesn't exist").
        var holder = sessions.get(agentName);
        if (holder == null) return;
        holder.lock.lock();
        try {
            retire(holder, agentName);
        } finally {
            holder.lock.unlock();
        }
    }

    /**
     * Tear down a holder's live browser and retire the holder from the map.
     * Caller must hold {@code holder.lock}. The {@code removed} flag makes any
     * thread that captured this holder just before removal retry in
     * {@link #acquireHolder} rather than launch into an orphan; the value-checked
     * {@code remove} leaves a freshly re-installed holder for the same key alone.
     */
    private static void retire(SessionHolder holder, String agentName) {
        if (holder.session != null) {
            destroySession(holder.session, agentName);
            holder.session = null;
        }
        holder.removed = true;
        sessions.remove(agentName, holder);
    }

    /** Tear down a session's resources. Safe to call from any thread. */
    private static void destroySession(BrowserSession session, String agentName) {
        // Best-effort teardown: close each resource independently so a failure on
        // one (already closed, crashed) cannot leak the others.
        try { session.page().close(); } catch (Exception _) { /* best-effort */ }
        try { session.browser().close(); } catch (Exception _) { /* best-effort */ }
        try { session.playwright().close(); } catch (Exception _) { /* best-effort */ }
        // After the browser, so nothing is still dialling through the screen when it drops the
        // tunnels it is holding open.
        try { session.proxy().close(); } catch (Exception _) { /* best-effort */ }
        EventLogger.info("tool", agentName, null, "Browser session closed");
    }

    /** Called periodically to clean up idle sessions. */
    public static void cleanupIdleSessions() {
        var now = System.currentTimeMillis();
        // forEach is weakly consistent and holds no bin lock, so the blocking
        // teardown below runs outside any ConcurrentHashMap monitor.
        sessions.forEach((name, holder) -> {
            if (now - holder.lastUsed <= IDLE_TIMEOUT_MS) return;
            // tryLock: if an op is in flight the session isn't really idle, so
            // skip this round and revisit on the next tick.
            if (!holder.lock.tryLock()) return;
            try {
                if (holder.removed) return; // already retired by a concurrent close
                if (System.currentTimeMillis() - holder.lastUsed <= IDLE_TIMEOUT_MS) {
                    return; // refreshed between the idle check and the lock — keep
                }
                retire(holder, name);
            } finally {
                holder.lock.unlock();
            }
        });
    }

    /** Close all sessions — called on application shutdown. */
    public static void closeAllSessions() {
        sessions.keySet().forEach(PlaywrightBrowserTool::closeSession);
    }

    /**
     * First-use setup: the Playwright driver's Node.js, then Chromium. Both steps are no-ops once
     * done, and one lock serialises them, so concurrent sessions and the Settings button share a
     * single download. Throws {@link IllegalStateException} when the driver cannot be obtained.
     */
    public static void ensureBrowserReady() {
        INSTALL_LOCK.lock();
        try {
            // Only real work marks a setup in flight, so a routine launch never flashes a progress bar.
            if (setupNeeded()) BrowserSetup.begin();
            try {
                ensureDriver();
                ensureBrowserInstalled();
            } finally {
                if (BrowserSetup.active()) BrowserSetup.end();
            }
        } finally {
            INSTALL_LOCK.unlock();
        }
    }

    private static boolean setupNeeded() {
        return BrowserSetup.driver().source() == PlaywrightNode.Source.MISSING
                || (!browserInstalled && !BrowserSetup.chromiumInstalled());
    }

    // The release bundle ships without driver-bundle, so a bundle install downloads the one official
    // Node.js its host needs. Dev, tests and the Docker image already have one and skip straight past.
    private static void ensureDriver() {
        var status = BrowserSetup.driver();
        switch (status.source()) {
            case PREINSTALLED, BUNDLED, DOWNLOADED -> { return; }
            case UNSUPPORTED -> throw new IllegalStateException(
                    "Playwright has no browser driver for this platform (%s)".formatted(System.getProperty("os.name")));
            case MISSING -> { }
        }
        var platform = status.platform();
        var build = platform == null ? null : PlaywrightNode.build(platform);
        if (build == null) throw new IllegalStateException("no Node.js build for " + platform);
        BrowserSetup.step("Downloading the browser driver (Node.js %s)".formatted(PlaywrightNode.VERSION));
        try {
            var node = PlaywrightNode.download(build, BrowserSetup.cacheRoot(), BrowserSetup::percent);
            EventLogger.info("tool", "Downloaded the Playwright driver's Node.js to " + node);
        } catch (IOException e) {
            BrowserSetup.failed("The browser driver could not be downloaded: " + e.getMessage());
            EventLogger.warn("tool", "Playwright driver download failed: %s".formatted(e.getMessage()));
            throw new IllegalStateException("the browser driver could not be downloaded (" + e.getMessage()
                    + "); retry, or download it from Settings > Browser", e);
        }
    }

    /**
     * Run {@link #ensureBrowserReady} off the request thread for the Settings button, returning at
     * once. Clears the no-retry latch first, so an operator's explicit retry re-runs a failed install.
     */
    public static void startBrowserSetup() {
        if (BrowserSetup.active()) return;
        browserInstalled = false;
        if (!setupNeeded()) return;
        // Marked before the thread starts, so the POST's own response already reads as in flight.
        BrowserSetup.begin();
        Thread.ofVirtual().name("browser-setup").start(() -> {
            try {
                ensureBrowserReady();
            } catch (RuntimeException e) {
                EventLogger.warn("tool", "Browser setup from Settings failed: %s".formatted(e.getMessage()));
            }
        });
    }

    /**
     * Ensure only Chromium is installed. Playwright's driver auto-install
     * downloads ALL browsers (Chromium, Firefox, WebKit) by default. This
     * invokes the CLI with {@code install chromium} once per JVM lifetime
     * so only Chromium (+ headless shell + ffmpeg) are fetched. Subsequent
     * calls are no-ops — the CLI detects the browser is already present.
     *
     * <p>Uses ProcessBuilder instead of {@code CLI.main()} directly because
     * the CLI calls {@code System.exit()} on some error paths, which would
     * kill the application server.
     */
    private static volatile boolean browserInstalled = false;
    // ReentrantLock, not synchronized: the install holds the lock across a
    // (potentially minutes-long) Chromium-download waitFor(); on a virtual
    // thread an intrinsic monitor would pin the carrier, whereas a ReentrantLock
    // releases it while blocked.
    private static final ReentrantLock INSTALL_LOCK = new ReentrantLock();

    private static void ensureBrowserInstalled() {
        if (browserInstalled) return;
        INSTALL_LOCK.lock();
        try {
            if (browserInstalled) return;
            // Revision-exact, not "any chromium* directory": a Playwright upgrade leaves the old
            // revision on a persistent PLAYWRIGHT_BROWSERS_PATH, and it would not launch.
            if (BrowserSetup.chromiumInstalled()) {
                browserInstalled = true;
                return;
            }
            try {
                EventLogger.info("tool", "Installing Chromium browser (skipping Firefox/WebKit)");
                // Build classpath from the same JARs the server uses
                var cp = System.getProperty("java.class.path");
                var pb = new ProcessBuilder(
                        ProcessHandle.current().info().command().orElse("java"),
                        "-cp", cp,
                        "com.microsoft.playwright.CLI",
                        "install", "chromium");
                var node = BrowserSetup.driver().node();
                if (node != null) pb.environment().put("PLAYWRIGHT_NODEJS_PATH", node.toString());
                pb.redirectErrorStream(true);
                var proc = pb.start();
                // Read, not inherited: the lines drive the chat and Settings progress bars.
                try (var out = proc.inputReader()) {
                    out.lines().forEach(BrowserSetup::onInstallLine);
                }
                var exitCode = proc.waitFor();
                if (exitCode != 0) {
                    BrowserSetup.failed("The Chromium install exited with code %d".formatted(exitCode));
                    EventLogger.warn("tool", "Playwright chromium install exited with code %d".formatted(exitCode));
                }
                browserInstalled = true;
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                browserInstalled = true; // don't retry on every session
                BrowserSetup.failed("The Chromium install failed: " + e.getMessage());
                EventLogger.warn("tool", "Playwright chromium install failed: %s".formatted(e.getMessage()));
            }
        } finally {
            INSTALL_LOCK.unlock();
        }
    }
}
