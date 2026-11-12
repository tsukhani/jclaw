package services.secrets;

import com.google.errorprone.annotations.MustBeClosed;
import okhttp3.OkHttpClient;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The secrets one command run was given (JF-324): a placeholder for each, the proxy login that redeems them, and
 * nothing else. The command's environment holds only the placeholders; the values stay here, in JClaw, and the
 * proxy writes one into a request only when that request goes to one of the secret's hosts. Closing the grant,
 * when the command exits, is stopped or times out, makes every placeholder worthless.
 */
public final class SecretGrant implements AutoCloseable {

    /** One secret as this run holds it. {@code hosts} are lowercase, each a name with an optional {@code :port}. */
    public record Binding(String skill, String name, String value, List<String> hosts, String placeholder) {

        /** Whether a request to {@code host} on {@code port} may carry this secret; a host without a port means 443. */
        public boolean allows(String host, int port) {
            var bare = host.toLowerCase(Locale.ROOT);
            for (var entry : hosts) {
                var colon = entry.lastIndexOf(':');
                if (colon < 0 ? port == 443 && entry.equals(bare)
                        : entry.substring(0, colon).equals(bare) && entry.substring(colon + 1).equals(String.valueOf(port))) {
                    return true;
                }
            }
            return false;
        }
    }

    private final String id;
    private final byte[] token;
    private final String ownerName;
    private final Map<String, Binding> byPlaceholder;
    private final List<Binding> byValueLength;
    private final @Nullable OkHttpClient upstream;
    private volatile boolean closed;

    @MustBeClosed
    SecretGrant(String id, String token, String ownerName, List<Binding> bindings, @Nullable OkHttpClient upstream) {
        this.id = id;
        this.token = token.getBytes(StandardCharsets.US_ASCII);
        this.ownerName = ownerName;
        var map = new LinkedHashMap<String, Binding>();
        for (var b : bindings) map.put(b.placeholder(), b);
        this.byPlaceholder = Map.copyOf(map);
        // Longest first, so a value that contains a shorter one is masked whole.
        this.byValueLength = bindings.stream().sorted(Comparator.comparingInt((Binding b) -> b.value().length()).reversed())
                .toList();
        this.upstream = upstream;
    }

    String id() {
        return id;
    }

    String ownerName() {
        return ownerName;
    }

    /** The upstream client a test bound with {@link SecretGrants#callWithUpstreamForTest}, or null for the default. */
    @Nullable OkHttpClient upstream() {
        return upstream;
    }

    boolean open() {
        return !closed;
    }

    boolean tokenMatches(String candidate) {
        return MessageDigest.isEqual(token, candidate.getBytes(StandardCharsets.US_ASCII));
    }

    @Nullable Binding binding(String placeholder) {
        return closed ? null : byPlaceholder.get(placeholder);
    }

    List<Binding> bindings() {
        return byValueLength;
    }

    /** The secrets' names, sorted, for the event that records what a command was given. */
    public List<String> names() {
        return byPlaceholder.values().stream().map(Binding::name).sorted().toList();
    }

    /**
     * What the command's environment adds: each secret's placeholder under its name, the proxy with this run's
     * login, and the trust bundle, under the names curl, Python, Node and Git read.
     */
    public Map<String, String> environment() {
        var env = new LinkedHashMap<String, String>();
        for (var b : byPlaceholder.values()) env.put(b.name(), b.placeholder());
        var proxy = "http://" + id + ":" + new String(token, StandardCharsets.US_ASCII) + "@127.0.0.1:"
                + SecretProxy.ensure().port();
        env.put("HTTPS_PROXY", proxy);
        env.put("https_proxy", proxy);
        env.put("NO_PROXY", "localhost,127.0.0.1,::1");
        env.put("no_proxy", "localhost,127.0.0.1,::1");
        env.put("NODE_USE_ENV_PROXY", "1");
        var ca = SecretsCa.get();
        var bundle = ca.bundleFile().toString();
        env.put("SSL_CERT_FILE", bundle);
        env.put("CURL_CA_BUNDLE", bundle);
        env.put("REQUESTS_CA_BUNDLE", bundle);
        env.put("GIT_SSL_CAINFO", bundle);
        env.put("NODE_EXTRA_CA_CERTS", ca.caFile().toString());
        return env;
    }

    /** {@code text} with each value this run holds replaced by {@code [secret NAME]}: the backstop for tool output. */
    public String mask(String text) {
        var out = text;
        for (var b : byValueLength) {
            if (!b.value().isEmpty()) out = out.replace(b.value(), "[secret " + b.name() + "]");
        }
        return out;
    }

    @Override
    public void close() {
        closed = true;
        SecretGrants.remove(this);
    }
}
