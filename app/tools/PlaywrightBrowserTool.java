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
import tools.jev.JevPage;
import tools.jev.JevRun;
import tools.jev.JevSettings;
import utils.AppClock;
import utils.SsrfGuard;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
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
     * The live browser resources for one agent session. Immutable; a relaunch
     * (JCLAW-731 DNS re-pin) builds a fresh instance rather than mutating this one.
     *
     * <p>{@code pinnedRules} records the {@code --host-resolver-rules} MAP
     * clauses this browser was launched with (JCLAW-731). Because that flag is
     * a launch-time argument, the set grows only by relaunch: a navigation to a
     * host not already pinned tears the browser down and relaunches it with the
     * union, so every host visited in the session is connect-time pinned — not
     * just the entry host.
     *
     * <p>{@code driver} is the Node process behind {@code playwright}, or null when it could not be
     * identified; killing it is the only way to free a thread blocked on a frozen page (JCLAW-1274).
     */
    private record BrowserSession(Playwright playwright, @Nullable ProcessHandle driver, Browser browser, Page page,
                                  CDPSession cdp, Set<String> pinnedRules) {
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

        // JCLAW-731: for a navigation, pin the browser's DNS to the guard-
        // validated IP via --host-resolver-rules, so Chromium connects only
        // where we checked (SNI-safe — the hostname stays in the URL, so the
        // Host header and TLS SNI are preserved). --host-resolver-rules is a
        // launch arg, so ensureSession relaunches the browser (carrying the
        // union of all prior pins) whenever a navigation targets a host not
        // already pinned — every navigated host, not just the entry host, ends
        // up connect-time pinned. The route interceptor keeps re-validating
        // every other request as before. An unsafe URL is rejected here, before
        // a browser is even spun up.
        Optional<String> pinRule = Optional.empty();
        if (ACTION_NAVIGATE.equals(action) || ACTION_RUN.equals(action)) {
            try {
                pinRule = SsrfGuard.hostResolverRule(args.get(ARG_URL).getAsString());
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
            session = ensureSession(holder, agent.name, pinRule);
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
     * Launch, reuse, or relaunch this agent's browser as needed, returning the
     * live session. Runs under {@code holder.lock} (held by the caller), so the
     * blocking launch never executes inside a ConcurrentHashMap callback
     * (JCLAW-821). Refreshes {@code lastUsed} so idle cleanup sees the activity.
     */
    private static BrowserSession ensureSession(SessionHolder holder, String agentName,
                                                Optional<String> hostResolverRule) {
        holder.lastUsed = System.currentTimeMillis();
        var existing = holder.session;
        if (existing != null && existing.page().isClosed()) {
            destroySession(existing, agentName);
            holder.session = null;
            existing = null;
        }
        if (existing != null) {
            // The DNS pin is a launch-time arg, so a navigation to a host not
            // already pinned can't be added to the running Chromium.
            var relaunchPins = pinsForNavigation(existing.pinnedRules(), hostResolverRule);
            if (relaunchPins.isEmpty()) {
                // Rule empty or already pinned — reuse the live session as-is.
                return existing;
            }
            // Relaunch with the union of prior pins + the new host so every
            // previously-visited host stays connect-time pinned. This discards
            // the current page state, but a cross-host navigation loads a fresh
            // page anyway, so nothing useful is lost. Null the reference before
            // launching so a launch failure leaves no dangling torn-down session.
            destroySession(existing, agentName);
            holder.session = null;
            holder.session = launchSession(agentName, relaunchPins.get(), holder.log);
            return holder.session;
        }
        var initialPins = new LinkedHashSet<String>();
        hostResolverRule.ifPresent(initialPins::add);
        holder.session = launchSession(agentName, initialPins, holder.log);
        return holder.session;
    }

    /**
     * Decide the DNS pin set a live session should run with for the next
     * navigation (JCLAW-731). Returns empty when the session already covers the
     * navigation — the rule is absent (literal-IP / hostless URL) or the host is
     * already pinned — so the caller reuses the browser untouched. Otherwise
     * returns the union of the existing pins and the new rule, signaling a
     * relaunch so the new host is pinned without dropping any prior host.
     *
     * <p>Exposed for unit tests; not part of the public tool API.
     */
    public static Optional<Set<String>> pinsForNavigation(Set<String> existingPins,
                                                          Optional<String> newRule) {
        if (newRule.isEmpty() || existingPins.contains(newRule.get())) {
            return Optional.empty();
        }
        var merged = new LinkedHashSet<>(existingPins);
        merged.add(newRule.get());
        return Optional.of(merged);
    }

    /**
     * Launch a fresh headless Chromium session pinned to {@code pinnedRules}.
     * The MAP clauses are joined into a single {@code --host-resolver-rules}
     * flag ("MAP h1 ip1,MAP h2 ip2") because that flag is a single launch arg;
     * this keeps every host validated so far in the session connect-time pinned,
     * not just the entry host (JCLAW-731).
     */
    private static BrowserSession launchSession(String key, Set<String> pinnedRules, BrowserScreenLog log) {
        EventLogger.info("tool", key, null, "Launching headless browser");
        // A tab the previous browser announced will never be closed by this one.
        log.forgetOpeningTabs();
        ensureBrowserInstalled();
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
        try {
            var driver = startDriver();
            playwright = driver.playwright();
            if (driver.process() == null && JevSettings.active()) {
                EventLogger.warn("tool", key, null,
                        "Browser driver process not identified; a Jev run on a frozen page cannot be stopped");
            }
            // JCLAW-172: headless is hardcoded — there is no UX where running a
            // visible browser on the host serves an LLM-driven agent. The
            // previous {@code playwright.headless} config key is gone.
            var launchOptions = new BrowserType.LaunchOptions().setHeadless(true);
            // JCLAW-731: pin DNS for every validated host in this session so Chromium
            // resolves each to exactly the IP the SSRF guard approved.
            if (!pinnedRules.isEmpty()) {
                launchOptions.setArgs(List.of("--host-resolver-rules=" + String.join(",", pinnedRules)));
            }
            browser = playwright.chromium().launch(launchOptions);
            page = openScreenedPage(browser, log);
            var cdp = page.context().newCDPSession(page);
            // Chromium announces a tab while the click that opens it is still running; Playwright's page event
            // waits for the tab's first response, which can land after the tool has returned.
            cdp.send("Page.enable");
            cdp.on("Page.windowOpen", _ -> log.tabOpening());
            return new BrowserSession(playwright, driver.process(), browser, page, cdp, Set.copyOf(pinnedRules));
        } catch (RuntimeException e) {
            // Best-effort teardown of whatever was constructed, newest first,
            // each guarded independently (mirrors destroySession).
            if (page != null) { try { page.close(); } catch (Exception _) { /* best-effort */ } }
            if (browser != null) { try { browser.close(); } catch (Exception _) { /* best-effort */ } }
            if (playwright != null) { try { playwright.close(); } catch (Exception _) { /* best-effort */ } }
            throw e;
        }
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
     * refusal is recorded in {@code log} (JCLAW-1280). Exposed for tests.
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
            var before = ProcessHandle.current().children().map(ProcessHandle::pid).collect(Collectors.toSet());
            var playwright = Playwright.create(new Playwright.CreateOptions()
                    .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
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
            // Skip the CLI install when an external installer (e.g. the Docker
            // image's chromium-stage) has already placed Chromium under
            // PLAYWRIGHT_BROWSERS_PATH. Playwright's CLI runs an OS-allowlist
            // check *before* its "already installed?" detection, so on hosts
            // the CLI doesn't recognize (e.g. ubuntu26.04 resolute) the install
            // call aborts noisily even when the browser is sitting right there.
            if (chromiumPreinstalled()) {
                EventLogger.info("tool", "Using pre-installed Chromium under PLAYWRIGHT_BROWSERS_PATH");
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
                pb.inheritIO();
                var proc = pb.start();
                var exitCode = proc.waitFor();
                if (exitCode != 0) {
                    EventLogger.warn("tool", "Playwright chromium install exited with code %d".formatted(exitCode));
                }
                browserInstalled = true;
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                browserInstalled = true; // don't retry on every session
                EventLogger.warn("tool", "Playwright chromium install failed: %s".formatted(e.getMessage()));
            }
        } finally {
            INSTALL_LOCK.unlock();
        }
    }

    /**
     * Returns true if Chromium (or its headless-shell variant) is already
     * installed under {@code $PLAYWRIGHT_BROWSERS_PATH}. Reads the env var,
     * then delegates the directory inspection to {@link #chromiumPreinstalledAt(Path)}
     * so that branch can be unit-tested without per-OS env mocking.
     */
    private static boolean chromiumPreinstalled() {
        var path = System.getenv("PLAYWRIGHT_BROWSERS_PATH");
        if (path == null || path.isBlank()) return false;
        return chromiumPreinstalledAt(Path.of(path));
    }

    /**
     * Returns true if {@code dir} contains a {@code chromium*} subdirectory.
     * Playwright's layout is {@code <root>/chromium-<rev>/} and
     * {@code <root>/chromium_headless_shell-<rev>/}; either is sufficient
     * for our launchers, so any {@code chromium*} subdirectory counts.
     *
     * <p>Exposed for unit tests; not part of the public tool API. The env-var
     * lookup lives in {@link #chromiumPreinstalled()} which delegates here.
     */
    public static boolean chromiumPreinstalledAt(Path dir) {
        if (!Files.isDirectory(dir)) return false;
        try (var entries = Files.list(dir)) {
            return entries.anyMatch(p ->
                    p.getFileName().toString().startsWith("chromium") && Files.isDirectory(p));
        } catch (Exception _) {
            return false;
        }
    }
}
