package services.secrets;

import com.google.errorprone.annotations.MustBeClosed;
import okhttp3.OkHttpClient;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The secrets one command run was given (JF-324): a placeholder for each, the proxy login that redeems them, and
 * nothing else. The command's environment holds only the placeholders; the values stay here, in JClaw, and the
 * proxy writes one into a request only when that request goes to one of the secret's hosts. Closing the grant,
 * when the command exits, is stopped or times out, makes every placeholder worthless; revoking a binding, when a
 * change means a new command would no longer be given that secret (JF-390), makes its placeholder worthless. A
 * renewed secret's binding takes the value the secrets proxy stores from a sign-in under the same placeholder
 * (JF-374); a value the operator types stops it, as any change does.
 */
public final class SecretGrant implements AutoCloseable {

    /** One secret as this run holds it. {@code hosts} are lowercase, each a name with an optional {@code :port}. */
    public record Binding(String skill, String name, String value, List<String> hosts, String placeholder) {

        /** Whether a request to {@code host} on {@code port} may carry this secret; a host without a port means 443. */
        public boolean allows(String host, int port) {
            return SecretGrant.allows(hosts, host, port);
        }
    }

    /** Whether {@code hosts}, as a secret stores them, name {@code host} on {@code port}; no port means 443. */
    static boolean allows(List<String> hosts, String host, int port) {
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

    /**
     * The bindings by placeholder, and every value to mask longest first, so a value that contains a shorter one is
     * masked whole. A renewal replaces the whole state; the values it replaced stay among those masked.
     */
    private record State(Map<String, Binding> byPlaceholder, List<Binding> masked) {
        static State of(Map<String, Binding> byPlaceholder, List<Binding> retired) {
            var all = new ArrayList<>(byPlaceholder.values());
            all.addAll(retired);
            all.sort(Comparator.comparingInt((Binding b) -> b.value().length()).reversed());
            return new State(Map.copyOf(byPlaceholder), List.copyOf(all));
        }
    }

    private final String id;
    private final byte[] token;
    private final String ownerName;
    private final boolean forAgent;
    private volatile State state;
    private final List<Binding> retired = new ArrayList<>();
    private final Set<String> revoked = ConcurrentHashMap.newKeySet();
    private final @Nullable OkHttpClient upstream;
    private final SecretGrants.@Nullable CaptureFaults captureFaults;
    private volatile boolean closed;

    @MustBeClosed
    SecretGrant(String id, String token, String ownerName, boolean forAgent, List<Binding> bindings,
                @Nullable OkHttpClient upstream, SecretGrants.@Nullable CaptureFaults captureFaults) {
        this.id = id;
        this.token = token.getBytes(StandardCharsets.US_ASCII);
        this.ownerName = ownerName;
        this.forAgent = forAgent;
        var map = new LinkedHashMap<String, Binding>();
        for (var b : bindings) map.put(b.placeholder(), b);
        this.state = State.of(map, List.of());
        this.upstream = upstream;
        this.captureFaults = captureFaults;
    }

    String id() {
        return id;
    }

    String ownerName() {
        return ownerName;
    }

    /** False for a grant a test opened with no agent behind it, which no agent's change may revoke. */
    boolean forAgent() {
        return forAgent;
    }

    /** The upstream client a test bound with {@link SecretGrants#callWithUpstreamForTest}, or null for the default. */
    @Nullable OkHttpClient upstream() {
        return upstream;
    }

    /** What a test bound with {@link SecretGrants#callWithCaptureFaultsForTest}, or null. */
    SecretGrants.@Nullable CaptureFaults captureFaults() {
        return captureFaults;
    }

    boolean open() {
        return !closed;
    }

    boolean tokenMatches(String candidate) {
        return MessageDigest.isEqual(token, candidate.getBytes(StandardCharsets.US_ASCII));
    }

    @Nullable Binding binding(String placeholder) {
        return closed || revoked.contains(placeholder) ? null : state.byPlaceholder().get(placeholder);
    }

    /** The binding {@code placeholder} stood for until {@link #reconcile} stopped it, or null. */
    @Nullable Binding revoked(String placeholder) {
        return closed || !revoked.contains(placeholder) ? null : state.byPlaceholder().get(placeholder);
    }

    /**
     * The placeholder this run holds for secret {@code name} of {@code skill}, or null when it was not given it or a
     * change stopped it: a stopped placeholder no longer sends anything.
     */
    @Nullable String placeholderOf(String skill, String name) {
        var held = held(skill, name);
        return held == null ? null : held.placeholder();
    }

    /** The binding this run holds for secret {@code name} of {@code skill}, with the value it sends now; null as above. */
    @Nullable Binding held(String skill, String name) {
        if (closed) return null;
        for (var b : state.byPlaceholder().values()) {
            if (b.skill().equals(skill) && b.name().equals(name) && !revoked.contains(b.placeholder())) return b;
        }
        return null;
    }

    /**
     * Mask each of {@code taken}, values the secrets proxy stored from a sign-in this run made (JF-374), in every later
     * answer to it as each binding's placeholder or marker: a run not given the secret, or whose binding a change
     * stopped, holds no binding the reconcile would renew.
     */
    synchronized void maskFromNowOn(List<Binding> taken) {
        boolean added = false;
        for (var b : taken) {
            if (b.value().isEmpty() || state.masked().stream().anyMatch(m -> m.value().equals(b.value()))) continue;
            retired.add(b);
            added = true;
        }
        if (added) state = State.of(state.byPlaceholder(), retired);
    }

    /**
     * Hold each binding still live to what {@code now} returns for it, the value a new command would be given (JF-390,
     * JF-374): its own value keeps it, another value renews it under its placeholder, so the command sends that value
     * at once, and null stops it for the rest of the run. Returns the names this call stopped, sorted. A stopped
     * binding, and a renewed one's old value, stay in {@link #bindings}, so a value a service sends back is still masked.
     */
    synchronized List<String> reconcile(Function<Binding, @Nullable String> now) {
        var current = state;
        var next = new LinkedHashMap<>(current.byPlaceholder());
        boolean renewed = false;
        var stopped = new ArrayList<String>();
        for (var b : current.byPlaceholder().values()) {
            if (revoked.contains(b.placeholder())) continue;
            var value = now.apply(b);
            if (value == null) {
                revoked.add(b.placeholder());
                stopped.add(b.name());
            } else if (!value.equals(b.value())) {
                next.put(b.placeholder(), new Binding(b.skill(), b.name(), value, b.hosts(), b.placeholder()));
                if (!b.value().isEmpty()) retired.add(b);
                renewed = true;
            }
        }
        if (renewed) state = State.of(next, retired);
        stopped.sort(null);
        return stopped;
    }

    /** Every value to turn back into its placeholder, longest first: each binding's, and each a renewal replaced. */
    List<Binding> bindings() {
        return state.masked();
    }

    /** The names of the secrets this run still holds, sorted, for the event that records what a command was given. */
    public List<String> names() {
        return state.byPlaceholder().values().stream().filter(b -> !revoked.contains(b.placeholder())).map(Binding::name)
                .sorted().toList();
    }

    /**
     * What the command's environment adds: each secret's placeholder under its name, the proxy with this run's
     * login, and the trust bundle, under the names curl, Python, Node and Git read.
     */
    public Map<String, String> environment() {
        var env = new LinkedHashMap<String, String>();
        for (var b : state.byPlaceholder().values()) env.put(b.name(), b.placeholder());
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
        for (var b : state.masked()) {
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
