package tools;

import org.jspecify.annotations.Nullable;
import services.EventLogger;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * What the browser tool's screen did in one session (JCLAW-1280): the requests the network guard refused
 * and the tabs the tool closed. The model hears of them on the tool's next result. The event log records
 * hosts only, because a full URL can carry a token, each host once, and a bounded number of lines per kind
 * per session, so a page that retries cannot flood it. Thread-safe.
 */
public final class BrowserScreenLog {

    private static final int NOTE_ITEMS = 5;
    private static final int NOTE_URL_CHARS = 300;
    // Bounds the distinct items held between two results; past it, "and N more" stops counting.
    private static final int PENDING_CAP = 1_000;
    private static final int REFUSAL_LINES = 20;
    private static final int TAB_LINES = 10;
    private static final String WARN = "WARN";
    private static final String INFO = "INFO";
    private static final String BLANK_TAB = "(blank tab)";
    private static final String REFUSED_TAB = "(refused tab)";
    private static final Pattern SCHEME = Pattern.compile("[A-Za-z][A-Za-z0-9+.-]*");

    private final BiConsumer<String, String> sink;
    private final LogBudget refusalLines = new LogBudget(REFUSAL_LINES, WARN,
            "Browser: later refused requests in this session are not logged");
    private final LogBudget tabLines = new LogBudget(TAB_LINES, INFO,
            "Browser: later closed tabs in this session are not logged");
    private final Pending refusedHosts = new Pending();
    private final Pending tabs = new Pending();
    private boolean blankTab;
    private boolean refusedTab;
    private int opening;

    /** Logs to the event log under {@code agentName}. */
    public BrowserScreenLog(String agentName) {
        this((level, message) -> EventLogger.record(level, "tool", agentName, null, message, null));
    }

    /** Logs each line to {@code sink} as (level, message). */
    public BrowserScreenLog(BiConsumer<String, String> sink) {
        this.sink = sink;
    }

    /** The network guard refused a request to {@code url}; {@code blockedAddress} when the address itself was the reason. */
    public synchronized void refused(String url, boolean blockedAddress) {
        var host = hostOf(url);
        refusedHosts.add(host);
        var level = blockedAddress ? WARN : INFO;
        // Keyed with the level, so a host that first failed to resolve still warns once it resolves to a blocked address.
        refusalLines.write(level + " " + host, level, blockedAddress
                ? "Browser refused a request to blocked host " + host
                : "Browser refused a request to host " + host);
    }

    /** The page is opening a tab, which Playwright reports only once the tab's first response arrives. */
    public synchronized void tabOpening() {
        opening++;
    }

    /** Whether a tab announced by {@link #tabOpening} has not yet been closed. */
    public synchronized boolean tabsOpening() {
        return opening > 0;
    }

    /** Stop waiting for announced tabs, such as one that never loaded; a late one is still recorded. */
    public synchronized void forgetOpeningTabs() {
        opening = 0;
    }

    /** A tab the page opened at {@code url} was closed; a URL that is not http(s) counts as a blank tab. */
    public synchronized void tabClosed(String url) {
        tabSettled();
        var lower = url.toLowerCase(Locale.ROOT);
        // navigate refuses every other scheme, so a blob:, data: or about: address is no use to the model.
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            blankTab = true;
            tabLines.write(BLANK_TAB, INFO, "Browser closed a blank tab the page opened");
            return;
        }
        tabs.add(url.length() > NOTE_URL_CHARS ? url.substring(0, NOTE_URL_CHARS) + "... (truncated)" : url);
        var host = hostOf(url);
        tabLines.write(host, INFO, "Browser closed a tab the page opened at host " + host);
    }

    /** A tab the page opened was closed on the error page Chromium shows for a navigation the guard refused. */
    public synchronized void refusedTabClosed() {
        tabSettled();
        refusedTab = true;
        tabLines.write(REFUSED_TAB, INFO,
                "Browser closed a tab the page opened at an address the network guard refused");
    }

    private void tabSettled() {
        if (opening > 0) opening--;
    }

    /**
     * The note on everything recorded since the last drain, one line per kind, or "" when there is none.
     * {@code openWith} names the action the model should use to open a closed tab's address.
     */
    public synchronized String drainNote(String openWith) {
        var lines = new ArrayList<String>();
        if (!tabs.isEmpty()) {
            lines.add("Note: the page opened %s at %s, which the browser tool closed. It does not follow new tabs; use %s to open one."
                    .formatted(tabs.size() == 1 ? "a new tab" : "new tabs", tabs.describe(), openWith));
        }
        if (blankTab) lines.add("Note: the page opened a blank tab, which the browser tool closed.");
        if (refusedTab) {
            lines.add("Note: the page opened a tab at an address the network guard refused, which the browser tool closed.");
        }
        if (!refusedHosts.isEmpty()) {
            lines.add("Note: the network guard refused requests to: %s.".formatted(refusedHosts.describe()));
        }
        tabs.clear();
        blankTab = false;
        refusedTab = false;
        refusedHosts.clear();
        return String.join("\n", lines);
    }

    /** Distinct items since the last drain: the first {@link #NOTE_ITEMS} are named, the rest counted. */
    private static final class Pending {
        private final Set<String> items = new LinkedHashSet<>();

        void add(String item) {
            if (items.size() < PENDING_CAP) items.add(item);
        }

        boolean isEmpty() {
            return items.isEmpty();
        }

        int size() {
            return items.size();
        }

        String describe() {
            var named = items.stream().limit(NOTE_ITEMS).collect(Collectors.joining(", "));
            int more = items.size() - NOTE_ITEMS;
            return more > 0 ? named + ", and " + more + " more" : named;
        }

        void clear() {
            items.clear();
        }
    }

    /** One kind's event-log lines: one per distinct key, at most {@code cap}, then a single suppression line. */
    private final class LogBudget {
        private final Set<String> logged = new HashSet<>();
        private final int cap;
        private final String suppressionLevel;
        private final String suppression;
        private boolean suppressed;

        LogBudget(int cap, String suppressionLevel, String suppression) {
            this.cap = cap;
            this.suppressionLevel = suppressionLevel;
            this.suppression = suppression;
        }

        void write(String key, String level, String message) {
            if (logged.contains(key)) return;
            if (logged.size() < cap) {
                logged.add(key);
                sink.accept(level, message);
            } else if (!suppressed) {
                suppressed = true;
                sink.accept(suppressionLevel, suppression);
            }
        }
    }

    /** The host alone, lower-cased and without port, path or query; a hostless URL reads as its scheme. */
    private static String hostOf(String url) {
        try {
            var uri = new URI(url);
            @Nullable String host = uri.getHost();
            if (host != null && !host.isEmpty()) return host.toLowerCase(Locale.ROOT);
            // URI leaves the host null for a name it rejects, such as one with an underscore.
            @Nullable String authority = uri.getRawAuthority();
            if (authority != null) return hostOfAuthority(authority);
            @Nullable String scheme = uri.getScheme();
            return scheme != null ? scheme + ":" : "(no host)";
        } catch (URISyntaxException _) {
            // Chromium leaves characters such as | and ^ raw in a query, and URI refuses the whole URL.
            int start = url.indexOf("://");
            if (start < 0 || !SCHEME.matcher(url.substring(0, start)).matches()) return "(unparseable URL)";
            start += 3;
            int end = start;
            while (end < url.length() && "/?#".indexOf(url.charAt(end)) < 0) end++;
            return hostOfAuthority(url.substring(start, end));
        }
    }

    private static String hostOfAuthority(String authority) {
        var hostPort = authority.substring(authority.lastIndexOf('@') + 1);
        String host;
        if (hostPort.startsWith("[")) {
            int close = hostPort.indexOf(']');
            host = close > 0 ? hostPort.substring(0, close + 1) : hostPort;
        } else {
            int colon = hostPort.indexOf(':');
            host = colon >= 0 ? hostPort.substring(0, colon) : hostPort;
        }
        return host.isEmpty() ? "(no host)" : host.toLowerCase(Locale.ROOT);
    }
}
