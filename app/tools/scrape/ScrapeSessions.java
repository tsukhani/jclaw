package tools.scrape;

import com.google.errorprone.annotations.MustBeClosed;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;
import services.ConfigService;
import services.EventLogger;
import services.scrape.ScrapeSidecarException;
import utils.SsrfGuard;
import utils.Urls;
import utils.WebExtraction;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * The stealth-browser sessions one crawl holds: at most one per host, so a Cloudflare clearance the
 * browser earns on one page carries to the next (JCLAW-1307).
 *
 * <p>Scoped to one crawl and closed when it ends, fails or is cancelled; never shared between crawls
 * or agents. A host's renders queue here, one at a time, where no call timeout runs but the crawl's
 * stop does: a session's browser renders serially, and the first page's cleared challenge is what
 * the next one reuses.
 *
 * <p>Every SSRF screen holds. Each page is still screened by {@link SsrfGuard}; the sidecar launches
 * a session with the pin validated when it opened, serves only its host, and keeps the route and
 * WebSocket gates on its context. Where the sidecar refuses a session, the page is rendered with a
 * browser of its own, as outside a crawl.
 */
public final class ScrapeSessions implements AutoCloseable {

    /** Whether a coherent clearance is handed to rung 2. Read per crawl; absent means off. */
    public static final String CFG_CLEARANCE_HANDOFF = "scrape.impersonate.clearanceHandoff";

    private static final String EVENT_CATEGORY = "scrape";

    private static final Pattern CHROME_MAJOR = Pattern.compile("\\bChrome/(\\d+)\\.");

    /** How often a page queued behind its host's session asks whether the crawl must stop. */
    private static final Duration STOP_POLL = Duration.ofMillis(500);

    /** The two sidecars as a crawl reaches them. Public because Play's tests live in the default package. */
    public interface Sidecars {
        boolean renderAvailable();

        boolean impersonateAvailable();

        /** A render with a browser of its own. */
        RenderedFetcher.Render render(String url, String language) throws IOException;

        /** @return the new session's id, or null when the sidecar has every session it allows open */
        @Nullable String open(String host, JsonObject pins, String language, @Nullable ScrapeProxy proxy)
                throws IOException;

        /** @throws RenderedFetcher.SessionGoneException when the sidecar no longer holds {@code session} */
        RenderedFetcher.Render render(String session, String url, boolean clearance) throws IOException;

        void close(String session) throws IOException;

        /** Rung 2, with {@code hostHeaders} sent to {@code host} alone. */
        WebExtraction.FetchResult impersonate(String url, Map<String, String> headers, String host,
                                              Map<String, String> hostHeaders) throws IOException;

        OptionalInt impersonationChromeMajor() throws IOException;
    }

    public static final Sidecars LIVE = new Sidecars() {
        @Override public boolean renderAvailable() {
            return RenderedFetcher.available();
        }

        @Override public boolean impersonateAvailable() {
            return ImpersonatedFetcher.available();
        }

        @Override public RenderedFetcher.Render render(String url, String language) throws IOException {
            return RenderedFetcher.render(url, language);
        }

        @Override public @Nullable String open(String host, JsonObject pins, String language,
                                               @Nullable ScrapeProxy proxy) throws IOException {
            return RenderedFetcher.openSession(host, pins, language, proxy);
        }

        @Override public RenderedFetcher.Render render(String session, String url, boolean clearance)
                throws IOException {
            return RenderedFetcher.renderInSession(session, url, clearance);
        }

        @Override public void close(String session) throws IOException {
            RenderedFetcher.closeSession(session);
        }

        @Override public WebExtraction.FetchResult impersonate(String url, Map<String, String> headers,
                                                               String host, Map<String, String> hostHeaders)
                throws IOException {
            return hostHeaders.isEmpty() ? ImpersonatedFetcher.fetch(url, headers)
                    : ImpersonatedFetcher.fetch(url, headers, host, hostHeaders);
        }

        @Override public OptionalInt impersonationChromeMajor() throws IOException {
            return ImpersonatedFetcher.chromeMajor();
        }
    };

    /** The cf_clearance a session's browser holds for its host, and the exact User-Agent it was issued to. */
    public record Clearance(String userAgent, String cookie) {

        /** The Chrome major version {@link #userAgent} names, or empty when it names none. */
        public OptionalInt chromeMajor() {
            var matched = CHROME_MAJOR.matcher(userAgent);
            return matched.find() ? OptionalInt.of(Integer.parseInt(matched.group(1))) : OptionalInt.empty();
        }

        /** Overridden so a log line or exception carrying one never prints the cookie. */
        @Override
        public String toString() {
            return "Clearance[" + userAgent + "]";
        }
    }

    /**
     * How rung 2 approaches one host. {@code skip} keeps the host on rung 3; {@code hostHeaders} are
     * sent to that host alone.
     */
    public record RungTwo(boolean skip, Map<String, String> hostHeaders) {
        public static final RungTwo AS_USUAL = new RungTwo(false, Map.of());
        public static final RungTwo SKIPPED = new RungTwo(true, Map.of());

        /** Names the headers only: one of them is a cookie. */
        @Override
        public String toString() {
            return "RungTwo[skip=%s, hostHeaders=%s]".formatted(skip, hostHeaders.keySet());
        }
    }

    /** One host's session. Its fields change under {@link #lock}; the volatile ones are also read without it. */
    private static final class Host {
        final ReentrantLock lock = new ReentrantLock(true);
        @Nullable String session;
        volatile @Nullable ScrapeProxy egress;
        volatile @Nullable Clearance clearance;
        /** Once a clearance could not be handed down coherently, the host stays on rung 3. */
        volatile boolean browserOnly;
    }

    private final Sidecars sidecars;
    private final boolean clearanceHandoff;
    private final BooleanSupplier stopRequested;
    private final Map<String, Host> hosts = new ConcurrentHashMap<>();
    private final ReentrantLock majorLock = new ReentrantLock();
    private @Nullable OptionalInt impersonationMajor;
    private volatile boolean closed;

    /**
     * Sessions for one crawl, with the handoff as {@link #CFG_CLEARANCE_HANDOFF} says. A page queued
     * behind its host's session gives up once {@code stopRequested} holds.
     */
    @MustBeClosed
    public ScrapeSessions(Sidecars sidecars, BooleanSupplier stopRequested) {
        this(sidecars, ConfigService.getBoolean(CFG_CLEARANCE_HANDOFF, false), stopRequested);
    }

    @MustBeClosed
    public ScrapeSessions(Sidecars sidecars, boolean clearanceHandoff) {
        this(sidecars, clearanceHandoff, () -> false);
    }

    @MustBeClosed
    public ScrapeSessions(Sidecars sidecars, boolean clearanceHandoff, BooleanSupplier stopRequested) {
        this.sidecars = sidecars;
        this.clearanceHandoff = clearanceHandoff;
        this.stopRequested = stopRequested;
    }

    public boolean renderAvailable() {
        return sidecars.renderAvailable();
    }

    public boolean impersonateAvailable() {
        return sidecars.impersonateAvailable();
    }

    /**
     * Render {@code url} in its host's session, opening one if the host has none, or with a browser
     * of its own when the sidecar holds no session for it.
     *
     * @throws SecurityException when {@link SsrfGuard} refuses {@code url}, before the sidecar is asked
     * @throws InterruptedIOException when the crawl must stop while {@code url} waits for its host
     */
    public RenderedFetcher.Render render(String url, String language) throws IOException {
        var pin = SsrfGuard.hostResolverRule(url);
        var name = hostOf(url);
        var host = hosts.computeIfAbsent(name, _ -> new Host());
        await(host);
        try {
            // A session the sidecar lost is reopened once: an idle sidecar exits and a new one
            // holds none of the old sessions.
            for (int attempt = 0; attempt < 2; attempt++) {
                if (host.session == null && !closed) host.session = open(name, pin, language, host);
                var session = host.session;
                if (session == null) break;
                try {
                    var render = sidecars.render(session, url, clearanceHandoff);
                    if (render.clearance() != null) host.clearance = render.clearance();
                    return render;
                } catch (RenderedFetcher.SessionGoneException _) {
                    host.session = null;
                    host.clearance = null;
                }
            }
        } finally {
            host.lock.unlock();
        }
        return sidecars.render(url, language);
    }

    /** Take {@code host}'s lock, polling so a page queued behind a slow render cannot outlast the crawl. */
    private void await(Host host) throws InterruptedIOException {
        try {
            while (!host.lock.tryLock(STOP_POLL.toMillis(), TimeUnit.MILLISECONDS)) {
                if (stopRequested.getAsBoolean()) {
                    throw new InterruptedIOException("the crawl stopped while this page waited for its host's browser");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted waiting for this page's host's browser");
        }
    }

    private @Nullable String open(String host, Optional<String> pin, String language, Host slot) {
        var egress = ScrapeProxy.current().orElse(null);
        try {
            var session = sidecars.open(host, RenderedFetcher.pins(pin), language, egress);
            if (session == null) {
                EventLogger.info(EVENT_CATEGORY, "%s: every browser session is open; rendering per page"
                        .formatted(host), null);
            } else {
                slot.egress = egress;
            }
            return session;
        } catch (IOException | ScrapeSidecarException e) {
            EventLogger.warn(EVENT_CATEGORY, "%s: no browser session; rendering per page".formatted(host),
                    e.getMessage());
            return null;
        }
    }

    /**
     * How rung 2 should approach {@code url}'s host. A clearance is handed down only when the result
     * stays coherent (JCLAW-1087): the same egress it was earned on, the browser's exact User-Agent,
     * and an impersonation profile of the same Chrome major. Otherwise a host that holds one stays on
     * rung 3 for the rest of the crawl, since rung 2 without it meets the challenge again.
     */
    public RungTwo rungTwo(String url) {
        if (!clearanceHandoff) return RungTwo.AS_USUAL;
        var host = hosts.get(hostOf(url));
        if (host == null) return RungTwo.AS_USUAL;
        if (host.browserOnly) return RungTwo.SKIPPED;
        var clearance = host.clearance;
        if (clearance == null) return RungTwo.AS_USUAL;
        if (coherent(host, clearance)) {
            return new RungTwo(false, Map.of("User-Agent", clearance.userAgent(),
                    "Cookie", "cf_clearance=" + clearance.cookie()));
        }
        host.browserOnly = true;
        EventLogger.info(EVENT_CATEGORY, "%s: stays on the browser rung for this crawl".formatted(hostOf(url)),
                "its clearance cannot be handed to rung 2 coherently");
        return RungTwo.SKIPPED;
    }

    private boolean coherent(Host host, Clearance clearance) {
        Optional<ScrapeProxy> egress;
        try {
            egress = ScrapeProxy.current();
        } catch (IllegalStateException _) {
            return false;
        }
        if (!Objects.equals(host.egress, egress.orElse(null))) return false;
        var browser = clearance.chromeMajor();
        var profile = impersonationMajor();
        return browser.isPresent() && profile.isPresent() && browser.getAsInt() == profile.getAsInt();
    }

    private OptionalInt impersonationMajor() {
        majorLock.lock();
        try {
            if (impersonationMajor == null) {
                try {
                    impersonationMajor = sidecars.impersonationChromeMajor();
                } catch (IOException | ScrapeSidecarException e) {
                    EventLogger.warn(EVENT_CATEGORY, "the impersonation profile's Chrome version is unknown",
                            e.getMessage());
                    impersonationMajor = OptionalInt.empty();
                }
            }
            return impersonationMajor;
        } finally {
            majorLock.unlock();
        }
    }

    /** Rung 2 for {@code url}, as {@link #rungTwo} decided. */
    public WebExtraction.FetchResult impersonate(String url, Map<String, String> headers, RungTwo rungTwo)
            throws IOException {
        return sidecars.impersonate(url, headers, hostOf(url), rungTwo.hostHeaders());
    }

    /** Close every session this crawl opened. Never throws: it runs as a crawl ends, however it ends. */
    @Override
    public void close() {
        closed = true;
        for (var entry : hosts.entrySet()) {
            var host = entry.getValue();
            host.lock.lock();
            try {
                var session = host.session;
                if (session == null) continue;
                host.session = null;
                try {
                    sidecars.close(session);
                } catch (IOException | RuntimeException e) {
                    EventLogger.info(EVENT_CATEGORY, "%s: the browser session is left to the sidecar's idle reaper"
                            .formatted(entry.getKey()), e.getMessage());
                }
            } finally {
                host.lock.unlock();
            }
        }
    }

    private static String hostOf(String url) {
        return hostKey(Objects.requireNonNullElse(Urls.parse(url).getHost(), ""));
    }

    /** A host as both this class and the sidecar key a session: lower case, no IPv6 brackets. */
    static String hostKey(String host) {
        var bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        return bare.toLowerCase(Locale.ROOT);
    }
}
