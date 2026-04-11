package tools;

import agents.ToolRegistry;
import com.google.gson.JsonParser;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitForSelectorState;
import models.Agent;
import services.AgentService;
import services.EventLogger;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Headless Chromium browser automation for JS-heavy pages.
 * Each agent gets an isolated browser session with lazy initialization and idle cleanup.
 */
public class PlaywrightBrowserTool implements ToolRegistry.Tool {

    private static final int MAX_TEXT_LENGTH = 50_000;
    private static final long IDLE_TIMEOUT_MS = 5 * 60 * 1000; // 5 minutes
    private static final ConcurrentHashMap<String, BrowserSession> sessions = new ConcurrentHashMap<>();

    private record BrowserSession(Playwright playwright, Browser browser, Page page, long lastUsed) {
        BrowserSession withLastUsed() {
            return new BrowserSession(playwright, browser, page, System.currentTimeMillis());
        }
    }

    @Override
    public String name() { return "browser"; }

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

        try {
            return switch (action) {
                case "navigate" -> {
                    var url = args.get("url").getAsString();
                    yield navigate(agent.name, url);
                }
                case "click" -> {
                    var selector = args.get("selector").getAsString();
                    yield click(agent.name, selector);
                }
                case "fill" -> {
                    var selector = args.get("selector").getAsString();
                    var value = args.get("value").getAsString();
                    yield fill(agent.name, selector, value);
                }
                case "getText" -> {
                    var selector = args.has("selector") ? args.get("selector").getAsString() : "body";
                    yield getText(agent.name, selector);
                }
                case "screenshot" -> screenshot(agent.name, agent.id);
                case "evaluate" -> {
                    var expression = args.get("expression").getAsString();
                    yield evaluate(agent.name, expression);
                }
                case "close" -> {
                    closeSession(agent.name);
                    yield "Browser session closed.";
                }
                default -> "Error: Unknown action '%s'".formatted(action);
            };
        } catch (PlaywrightException e) {
            return "Browser error: %s".formatted(e.getMessage());
        } catch (Exception e) {
            return "Error: %s".formatted(e.getMessage());
        }
    }

    private String navigate(String agentName, String url) {
        var page = getOrCreatePage(agentName);
        page.navigate(url);
        page.waitForLoadState(LoadState.NETWORKIDLE);
        var title = page.title();
        var text = page.textContent("body");
        if (text != null && text.length() > MAX_TEXT_LENGTH) {
            text = text.substring(0, MAX_TEXT_LENGTH) + "\n[Truncated]";
        }
        return "Page: %s\n\n%s".formatted(title, text != null ? text : "(empty page)");
    }

    private String click(String agentName, String selector) {
        var page = getOrCreatePage(agentName);
        page.locator(selector).first().click();
        page.waitForLoadState(LoadState.NETWORKIDLE);
        var title = page.title();
        return "Clicked '%s'. Page: %s".formatted(selector, title);
    }

    private String fill(String agentName, String selector, String value) {
        var page = getOrCreatePage(agentName);
        page.locator(selector).first().fill(value);
        return "Filled '%s' with value.".formatted(selector);
    }

    private String getText(String agentName, String selector) {
        var page = getOrCreatePage(agentName);
        var text = page.locator(selector).first().textContent();
        if (text != null && text.length() > MAX_TEXT_LENGTH) {
            text = text.substring(0, MAX_TEXT_LENGTH) + "\n[Truncated]";
        }
        return text != null ? text : "(no text content)";
    }

    private String screenshot(String agentName, Long agentId) {
        var page = getOrCreatePage(agentName);
        var timestamp = System.currentTimeMillis();
        var filename = "screenshot-%d.png".formatted(timestamp);
        var path = AgentService.workspacePath(agentName).resolve(filename);
        page.screenshot(new Page.ScreenshotOptions().setPath(path).setFullPage(true));

        var url = "/api/agents/%d/files/%s".formatted(agentId, filename);
        return formatScreenshotResult(url);
    }

    /**
     * Build the tool-result string for a captured screenshot. The markdown image
     * tag is included so {@code AgentRunner.extractImageUrls} picks it up and
     * prepends the rendered screenshot to the assistant message. The instruction
     * mirrors {@code ShellExecTool}'s QR-code handling and prevents the LLM from
     * re-embedding the same image in its own reply (which would produce a
     * duplicate or broken placeholder).
     *
     * <p>Exposed for unit tests; not part of the public tool API.
     */
    public static String formatScreenshotResult(String url) {
        return ("![Screenshot](%s)\n"
                + "[Screenshot captured and displayed above to the user. Do NOT re-embed, "
                + "re-link, or re-fetch this image in your reply — it is already visible.]")
                .formatted(url);
    }

    private String evaluate(String agentName, String expression) {
        var page = getOrCreatePage(agentName);
        var result = page.evaluate(expression);
        return result != null ? result.toString() : "null";
    }

    // --- Session management ---

    private synchronized Page getOrCreatePage(String agentName) {
        var session = sessions.get(agentName);
        if (session != null && session.page().isClosed()) {
            closeSession(agentName);
            session = null;
        }
        if (session != null) {
            sessions.put(agentName, session.withLastUsed());
            return session.page();
        }

        EventLogger.info("tool", agentName, null, "Launching headless browser");
        var playwright = Playwright.create();
        var browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions().setHeadless(
                        !"false".equals(services.ConfigService.get("jclaw.tools.playwright.headless", "true"))));
        var page = browser.newPage();
        sessions.put(agentName, new BrowserSession(playwright, browser, page, System.currentTimeMillis()));
        return page;
    }

    public static void closeSession(String agentName) {
        var session = sessions.remove(agentName);
        if (session != null) {
            try { session.page().close(); } catch (Exception _) {}
            try { session.browser().close(); } catch (Exception _) {}
            try { session.playwright().close(); } catch (Exception _) {}
            EventLogger.info("tool", agentName, null, "Browser session closed");
        }
    }

    /** Called periodically to clean up idle sessions. */
    public static void cleanupIdleSessions() {
        var now = System.currentTimeMillis();
        sessions.forEach((name, session) -> {
            if (now - session.lastUsed() > IDLE_TIMEOUT_MS) {
                closeSession(name);
            }
        });
    }

    /** Close all sessions — called on application shutdown. */
    public static void closeAllSessions() {
        sessions.keySet().forEach(PlaywrightBrowserTool::closeSession);
    }
}
