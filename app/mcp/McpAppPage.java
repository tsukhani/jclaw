package mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import models.McpServer;
import org.jspecify.annotations.Nullable;
import services.EventLogger;
import services.McpServerService;
import services.Tx;
import utils.ApiResponses;
import utils.SsrfGuard;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * An MCP Apps view page (SEP-1865) as the web chat frames it: the HTML a {@code ui://} resource reads
 * to, and the Content-Security-Policy its response carries.
 *
 * <p>The page is served from JClaw's own origin, so the policy's {@code sandbox allow-scripts} is what
 * keeps it apart: the document gets an opaque origin, with no access to JClaw's DOM, storage or
 * {@code SameSite=Strict} session. That stands in for the spec's second-origin sandbox proxy, and the
 * page is served only to a frame, never as a tab under JClaw's address.
 *
 * <p>No load directive allows {@code 'self'}: here it is JClaw's own origin, the API included.
 */
public final class McpAppPage {

    private static final String CATEGORY = "MCP_APPS";
    /** An origin as {@code McpUiResourceCsp} declares one, with an optional {@code *.} subdomain and path. */
    private static final Pattern DECLARED_ORIGIN = Pattern.compile("(https?|wss?)://(?<wildcard>\\*\\.)?"
            + "(?<host>[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*)(:(\\d{1,5}|\\*))?(/[A-Za-z0-9._~%/-]*)?",
            Pattern.CASE_INSENSITIVE);
    /** Names that point inside a home or office network by convention, matched as written. */
    private static final List<String> LOCAL_SUFFIXES = List.of(
            "local", "localhost", "lan", "home", "home.arpa", "internal", "intranet", "corp", "localdomain");
    /** A last label that makes a browser read the whole host as an IPv4 address (WHATWG URL, "ends in a number"). */
    private static final Pattern NUMERIC_LABEL = Pattern.compile("\\d+|0x[0-9a-f]*");
    /** The spec's restrictive default, without {@code 'self'}, for a page that declares no {@code csp}. */
    private static final String DEFAULT_POLICY = "default-src 'none'; script-src 'unsafe-inline'; "
            + "style-src 'unsafe-inline'; img-src data:; media-src data:; connect-src 'none'; base-uri 'none'";
    private static final String HOST_DIRECTIVES =
            "; object-src 'none'; form-action 'none'; frame-ancestors 'self'; sandbox allow-scripts";

    private McpAppPage() {}

    /** The page's HTML and the policy its response carries. */
    public record Page(String html, String contentSecurityPolicy) {}

    /** JClaw's own host, as the request for the page reached it. */
    public record Host(String domain) {}

    /**
     * What a page's declared origins may reach. JClaw's host never, at any port; a host that names the
     * operator's machine or network only when the page's server is itself local. Hosts are judged as
     * written, without DNS, so a public name that resolves to a private address is not caught.
     */
    public record Reach(Host jclaw, boolean localServer) {}

    /** A policy and the declared entries it left out, by {@code csp} key. */
    public record Policy(String header, JsonObject dropped) {}

    /** Why a page could not be served, as an HTTP status and one line for the frame's error body. */
    public static final class Unavailable extends Exception {
        private final int status;

        Unavailable(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() { return status; }
    }

    /**
     * Read {@code uri} from {@code serverName} with {@code resources/read}. Its {@code _meta.ui} comes
     * from the read content, else from the server's listing entry for it.
     */
    public static Page load(String serverName, String uri, Host jclaw) throws Unavailable {
        if (!McpResources.isUiUri(uri)) throw new Unavailable(400, "Not an MCP Apps page URI.");
        if (McpConnectionManager.status(serverName) != McpServer.Status.CONNECTED) {
            throw new Unavailable(503, "MCP server '" + serverName + "' is not connected.");
        }
        List<McpResources.Content> contents;
        try {
            contents = McpConnectionManager.readResource(serverName, uri);
        } catch (McpException.ResourceNotFound _) {
            throw new Unavailable(404, "MCP server '" + serverName + "' has no page " + uri + ".");
        } catch (IOException | RuntimeException e) {
            EventLogger.warn(CATEGORY, "MCP server '%s' could not read view page %s: %s"
                    .formatted(serverName, new JsonPrimitive(uri), ApiResponses.messageOf(e)));
            throw new Unavailable(502, "MCP server '" + serverName + "' could not read the page.");
        }
        // mimeType is optional on a read content; one for the requested ui:// URI is taken to be the page.
        var page = contents.stream().filter(c -> c.text() != null && (McpResources.isAppPage(c.mimeType())
                || (c.mimeType() == null && c.uri().equals(uri)))).findFirst().orElse(null);
        var html = page == null ? null : page.text();
        if (page == null || html == null || html.isBlank()) {
            throw new Unavailable(502, "MCP server '" + serverName + "' did not return an MCP Apps page.");
        }
        var ui = page.ui();
        if (ui == null || !(ui.get("csp") instanceof JsonObject)) {
            var listed = listedUi(serverName, uri);
            if (listed != null && listed.get("csp") instanceof JsonObject) ui = listed;
        }
        var policy = policy(ui, new Reach(jclaw, localServer(serverName)));
        if (!policy.dropped().isEmpty()) {
            EventLogger.warn(CATEGORY, "MCP server '%s' view page %s declared origins that were left out of its CSP: %s"
                    .formatted(serverName, new JsonPrimitive(uri), policy.dropped()));
        }
        return new Page(html, policy.header());
    }

    /** A listing that fails leaves the page on the restrictive default. */
    private static @Nullable JsonObject listedUi(String serverName, String uri) {
        try {
            for (var r : McpConnectionManager.listResources(serverName).resources()) {
                if (r.uri().equals(uri)) return r.ui();
            }
        } catch (IOException | RuntimeException e) {
            EventLogger.warn(CATEGORY, "MCP server '%s' could not list resources for view page %s: %s"
                    .formatted(serverName, new JsonPrimitive(uri), ApiResponses.messageOf(e)));
        }
        return null;
    }

    private static boolean localServer(String serverName) {
        var row = Tx.run(() -> McpServer.findByName(serverName));
        return row != null && isLocal(row);
    }

    /** A STDIO server, or an HTTP server whose URL host is loopback or private as written. */
    public static boolean isLocal(McpServer row) {
        if (row.transport == McpServer.Transport.STDIO) return true;
        var url = McpServerService.explodeConfigJson(row.transport, row.configJson).url();
        try {
            var host = url == null ? null : URI.create(url).getHost();
            return host != null && privateHost(host);
        } catch (IllegalArgumentException _) {
            return false;
        }
    }

    /**
     * The policy for a page whose {@code _meta.ui} is {@code ui}, built as SEP-1865's "CSP Construction
     * from Metadata" without {@code 'self'}. A declared entry that is not an origin, such as a keyword or
     * one carrying {@code ;}, or that {@code reach} does not allow, is left out.
     */
    public static Policy policy(@Nullable JsonObject ui, Reach reach) {
        var dropped = new JsonObject();
        if (!(ui != null && ui.get("csp") instanceof JsonObject csp)) return new Policy(DEFAULT_POLICY + HOST_DIRECTIVES, dropped);
        var resources = origins(csp, "resourceDomains", reach, dropped);
        var header = "default-src 'none'"
                + "; script-src 'unsafe-inline'" + resources
                + "; style-src 'unsafe-inline'" + resources
                + "; img-src data:" + resources
                + "; font-src " + orNone(resources)
                + "; media-src data:" + resources
                + "; connect-src " + orNone(origins(csp, "connectDomains", reach, dropped))
                + "; frame-src " + orNone(origins(csp, "frameDomains", reach, dropped))
                + "; base-uri " + orNone(origins(csp, "baseUriDomains", reach, dropped))
                + HOST_DIRECTIVES;
        return new Policy(header, dropped);
    }

    private static String orNone(String sources) {
        return sources.isEmpty() ? "'none'" : sources.substring(1);
    }

    /** Each accepted origin with a leading space, so an empty list adds nothing; the rest go to {@code dropped}. */
    private static String origins(JsonObject csp, String key, Reach reach, JsonObject dropped) {
        if (!(csp.get(key) instanceof JsonElement list) || !list.isJsonArray()) return "";
        var kept = new StringBuilder();
        var left = new JsonArray();
        for (var el : list.getAsJsonArray()) {
            if (el.isJsonPrimitive() && allowed(el.getAsString(), reach)) {
                kept.append(' ').append(el.getAsString());
            } else {
                left.add(el.deepCopy());
            }
        }
        if (!left.isEmpty()) dropped.add(key, left);
        return kept.toString();
    }

    private static boolean allowed(String declared, Reach reach) {
        var m = DECLARED_ORIGIN.matcher(declared);
        if (!m.matches()) return false;
        var host = m.group("host").toLowerCase(Locale.ROOT);
        var jclaw = reach.jclaw().domain().toLowerCase(Locale.ROOT);
        // JClaw listens on more than one port, and a wildcard reaches every subdomain of its base.
        if (host.equals(jclaw) || (m.group("wildcard") != null && jclaw.endsWith("." + host))) return false;
        try {
            return reach.localServer() || !privateHost(host);
        } catch (IllegalArgumentException _) {
            return false;
        }
    }

    /**
     * Whether a host names the operator's machine or network as written, without DNS: an address literal as
     * a browser reads one (IPv4 in any of its numeric forms whenever the last label is a number) that is
     * loopback, private, link-local or unspecified; a single-label name; or a conventional local suffix.
     *
     * @throws IllegalArgumentException for a host a browser would refuse as a malformed IPv4 address
     */
    static boolean privateHost(String host) {
        var lower = host.toLowerCase(Locale.ROOT);
        if (lower.startsWith("[") && lower.endsWith("]")) {
            return SsrfGuard.isUnsafe(InetAddress.ofLiteral(lower.substring(1, lower.length() - 1)));
        }
        var last = lower.substring(lower.lastIndexOf('.') + 1);
        if (NUMERIC_LABEL.matcher(last).matches()) return SsrfGuard.isUnsafe(Inet4Address.ofPosixLiteral(lower));
        if (!lower.contains(".")) return true;
        return LOCAL_SUFFIXES.stream().anyMatch(suffix -> lower.equals(suffix) || lower.endsWith("." + suffix));
    }
}
