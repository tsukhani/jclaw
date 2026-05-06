package tools;

import agents.ToolRegistry;
import com.google.gson.JsonParser;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;
import models.Agent;
import services.AgentService;
import services.EventLogger;
import utils.SsrfGuard;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Headless Chromium browser automation for JS-heavy pages.
 * Each agent gets an isolated browser session with lazy initialization and idle cleanup.
 */
public class PlaywrightBrowserTool implements ToolRegistry.Tool {

    private static final int MAX_TEXT_LENGTH = 50_000;
    private static final long IDLE_TIMEOUT_MS = 5L * 60 * 1000; // 5 minutes
    private static final ConcurrentHashMap<String, BrowserSession> sessions = new ConcurrentHashMap<>();

    /**
     * Playwright's {@link Page} API is not thread-safe — concurrent navigate /
     * screenshot calls on the same Page corrupt its internal request map and
     * surface as {@code "Object doesn't exist: request@<hash>"}. When the LLM
     * emits multiple browser tool calls in a single streaming round,
     * {@link agents.AgentRunner#executeToolsParallel} dispatches them on
     * separate virtual threads, so the tool must serialize Page access itself.
     * The {@code lock} is held for the full duration of each {@code execute()}
     * call on this agent's session.
     */
    private record BrowserSession(Playwright playwright, Browser browser, Page page,
                                  ReentrantLock lock, long lastUsed) {
        BrowserSession withLastUsed() {
            return new BrowserSession(playwright, browser, page, lock, System.currentTimeMillis());
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
        return "Headless browser automation for SPAs, login flows, and JavaScript-heavy pages.";
    }

    @Override
    public java.util.List<agents.ToolAction> actions() {
        return java.util.List.of(
                new agents.ToolAction("navigate",   "Load a URL, wait for network idle, and return page text"),
                new agents.ToolAction("click",      "Click a DOM element by CSS selector"),
                new agents.ToolAction("fill",       "Fill a form field with a value by CSS selector"),
                new agents.ToolAction("getText",    "Extract the text content of a CSS selector"),
                new agents.ToolAction("screenshot", "Capture a full-page screenshot and save it to the workspace"),
                new agents.ToolAction("evaluate",   "Execute a JavaScript expression and return the result"),
                new agents.ToolAction("close",      "Close the browser session and free all resources")
        );
    }

    @Override
    public String description() {
        return """
                Headless browser for JavaScript-heavy web pages. \
                Use this when web_fetch returns incomplete content (SPAs, dynamic pages, login flows). \
                Actions: navigate, click, fill, getText, screenshot, evaluate, close.""";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "action", Map.of("type", "string",
                                "enum", List.of("navigate", "click", "fill", "getText", "screenshot", "evaluate", "close"),
                                "description", "The browser action to perform"),
                        "url", Map.of("type", "string",
                                "description", "URL to navigate to (for navigate action)"),
                        "selector", Map.of("type", "string",
                                "description", "CSS selector (for click, fill, getText actions)"),
                        "value", Map.of("type", "string",
                                "description", "Value to fill (for fill action)"),
                        "expression", Map.of("type", "string",
                                "description", "JavaScript expression (for evaluate action)")
                ),
                "required", List.of("action")
        );
    }

    @Override
    public String execute(String argsJson, Agent agent) {
        var args = JsonParser.parseString(argsJson).getAsJsonObject();
        var action = args.get("action").getAsString();

        // "close" tears down the session; don't re-create one just to close it.
        if ("close".equals(action)) {
            closeSession(agent.name);
            return "Browser session closed.";
        }

        // Acquire the per-agent lock so parallel tool calls in the same round
        // (e.g. navigate + screenshot dispatched by executeToolsParallel) run
        // serially against the same Page. Playwright's Page is not thread-safe.
        var session = getOrCreateSession(agent.name);
        session.lock().lock();
        try {
            var page = session.page();
            return switch (action) {
                case "navigate" -> navigate(page, args.get("url").getAsString());
                case "click" -> click(page, args.get("selector").getAsString());
                case "fill" -> fill(page, args.get("selector").getAsString(),
                                    args.get("value").getAsString());
                case "getText" -> getText(page,
                        args.has("selector") ? args.get("selector").getAsString() : "body");
                case "screenshot" -> screenshot(page, agent.name, agent.id);
                case "evaluate" -> evaluate(page, args.get("expression").getAsString());
                default -> "Error: Unknown action '%s'".formatted(action);
            };
        } catch (PlaywrightException e) {
            return "Browser error: %s".formatted(e.getMessage());
        } catch (Exception e) {
            return "Error: %s".formatted(e.getMessage());
        } finally {
            session.lock().unlock();
        }
    }

    private String navigate(Page page, String url) {
        // JCLAW-116: validate the entry URL before handing it to Chromium.
        // The route interceptor installed in createSession catches subresources
        // and redirects, but the top-level URL is still checked here so we
        // can surface a clean error to the agent without spinning up the nav.
        try {
            SsrfGuard.assertUrlSafe(url);
        } catch (SecurityException e) {
            return "Error: " + e.getMessage();
        }
        page.navigate(url);
        page.waitForLoadState(LoadState.NETWORKIDLE);
        var title = page.title();
        var text = page.textContent("body");
        if (text != null && text.length() > MAX_TEXT_LENGTH) {
            text = text.substring(0, MAX_TEXT_LENGTH) + "\n[Truncated]";
        }
        return "Page: %s\n\n%s".formatted(title, text != null ? text : "(empty page)");
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
        var timestamp = System.currentTimeMillis();
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
        // Keep the markdown image so AgentRunner.extractImageUrls picks up
        // the URL for the buildImagePrefix pipeline. Everything else is the
        // LLM's call — if the user asked only to take a screenshot, a short
        // acknowledgment is fine; if they asked for a summary, it'll describe.
        // Pre-JCLAW-104 this instruction was a three-directive block telling
        // the LLM what to embed, what not to embed, and what link to include;
        // every directive has been replaced by a runtime guarantee (see the
        // class javadoc).
        //
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

    private BrowserSession getOrCreateSession(String agentName) {
        // Use compute() for atomic get-or-create — prevents race between
        // concurrent getOrCreateSession and closeSession on the same key.
        return sessions.compute(agentName, (key, existing) -> {
            if (existing != null && existing.page().isClosed()) {
                destroySession(existing, key);
                existing = null;
            }
            if (existing != null) {
                return existing.withLastUsed();
            }
            EventLogger.info("tool", key, null, "Launching headless browser");
            ensureBrowserInstalled();
            var playwright = Playwright.create(new Playwright.CreateOptions()
                    .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
            // JCLAW-172: headless is hardcoded — there is no UX where running a
            // visible browser on the host serves an LLM-driven agent. The
            // previous {@code playwright.headless} config key is gone.
            var browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true));
            var page = browser.newPage();
            // JCLAW-116: abort any request (main frame, subresource, or
            // redirect target) whose URL fails the SSRF guard. Catches
            // three cases the entry-URL check in navigate() can't:
            //   (a) subresources embedded in the loaded page that target
            //       private networks (tracking pixels pointing at
            //       169.254.169.254, script-loaders reaching loopback, etc.),
            //   (b) HTTP redirects to unsafe hosts (Chromium follows them
            //       automatically without re-invoking our one-shot check),
            //   (c) any future navigation that bypasses navigate() (e.g.
            //       a click handler that triggers page.navigate internally).
            page.route("**/*", route -> {
                if (SsrfGuard.isUrlSafe(route.request().url())) {
                    route.resume();
                } else {
                    route.abort();
                }
            });
            return new BrowserSession(playwright, browser, page,
                    new ReentrantLock(), System.currentTimeMillis());
        });
    }

    public static void closeSession(String agentName) {
        // Atomic remove — ConcurrentHashMap guarantees no race with compute().
        // Acquire the session lock before tearing down so an in-flight Page op
        // from another thread finishes cleanly (Playwright close() during a
        // live request would surface as "Object doesn't exist").
        var session = sessions.remove(agentName);
        if (session != null) {
            session.lock().lock();
            try {
                destroySession(session, agentName);
            } finally {
                session.lock().unlock();
            }
        }
    }

    /** Tear down a session's resources. Safe to call from any thread. */
    private static void destroySession(BrowserSession session, String agentName) {
        try { session.page().close(); } catch (Exception _) {}
        try { session.browser().close(); } catch (Exception _) {}
        try { session.playwright().close(); } catch (Exception _) {}
        EventLogger.info("tool", agentName, null, "Browser session closed");
    }

    /** Called periodically to clean up idle sessions. */
    public static void cleanupIdleSessions() {
        var now = System.currentTimeMillis();
        // Use removeIf-style iteration — avoids ConcurrentModificationException
        // and makes remove+destroy atomic per key.
        sessions.forEach((name, session) -> {
            if (now - session.lastUsed() > IDLE_TIMEOUT_MS) {
                sessions.computeIfPresent(name, (k, s) -> {
                    if (System.currentTimeMillis() - s.lastUsed() <= IDLE_TIMEOUT_MS) {
                        return s; // refreshed between check and compute — keep
                    }
                    // tryLock: if an op is in flight the session isn't really
                    // idle, so skip this round and revisit on the next tick.
                    if (!s.lock().tryLock()) return s;
                    try {
                        destroySession(s, k);
                        return null; // removes the entry
                    } finally {
                        s.lock().unlock();
                    }
                });
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

    private static void ensureBrowserInstalled() {
        if (browserInstalled) return;
        synchronized (PlaywrightBrowserTool.class) {
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
        }
    }

    /**
     * Returns true if Chromium (or its headless-shell variant) is already
     * installed under {@code $PLAYWRIGHT_BROWSERS_PATH}. The directory layout
     * Playwright produces is {@code <root>/chromium-<rev>/} and
     * {@code <root>/chromium_headless_shell-<rev>/}; either is sufficient
     * for our launchers, so any {@code chromium*} subdirectory counts.
     */
    private static boolean chromiumPreinstalled() {
        var path = System.getenv("PLAYWRIGHT_BROWSERS_PATH");
        if (path == null || path.isBlank()) return false;
        var dir = Path.of(path);
        if (!Files.isDirectory(dir)) return false;
        try (var entries = Files.list(dir)) {
            return entries.anyMatch(p ->
                    p.getFileName().toString().startsWith("chromium") && Files.isDirectory(p));
        } catch (Exception e) {
            return false;
        }
    }
}
