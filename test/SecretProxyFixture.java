import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import services.secrets.SecretGrant;
import services.secrets.SecretProxy;
import utils.HttpFactories;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * JF-324: a stub HTTPS service answering as {@link #BOUND} and {@link #OTHER} on one loopback port, with a
 * certificate of its own, and the two clients a test needs: the upstream the proxy uses to reach it, and a client
 * that goes through the proxy with a grant's login, as a command does.
 */
public final class SecretProxyFixture implements AutoCloseable {

    public static final String BOUND = "secret-stub.test";
    public static final String OTHER = "other-stub.test";

    /** What the stub received: one entry per request, in order. */
    public record Received(String host, String method, String target, Map<String, List<String>> headers, byte[] body) {
        public String header(String name) {
            // The JDK server reads header bytes as Latin-1; the proxy writes UTF-8.
            for (var e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) {
                    return new String(e.getValue().getFirst().getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
                }
            }
            return null;
        }

        public String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    public final List<Received> received = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Function<Received, byte[]>> routes = new ConcurrentHashMap<>();
    private final HttpsServer server;
    private final SSLContext stubContext;
    private final SSLContext trustingStub;
    private final X509TrustManager stubTrust;

    public SecretProxyFixture() throws Exception {
        var keys = KeyPairGenerator.getInstance("EC");
        keys.initialize(256);
        var pair = keys.generateKeyPair();
        var now = utils.AppClock.now();
        var builder = new JcaX509v3CertificateBuilder(new X500Name("CN=" + BOUND), BigInteger.valueOf(now.toEpochMilli()),
                Date.from(now.minus(Duration.ofHours(1))), Date.from(now.plus(Duration.ofDays(1))),
                new X500Name("CN=" + BOUND), pair.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(new GeneralName[] {
                new GeneralName(GeneralName.dNSName, BOUND), new GeneralName(GeneralName.dNSName, OTHER)}));
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(
                builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(pair.getPrivate())));
        var store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setKeyEntry("stub", pair.getPrivate(), new char[0], new Certificate[] {cert});
        var kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, new char[0]);
        stubContext = SSLContext.getInstance("TLS");
        stubContext.init(kmf.getKeyManagers(), null, null);

        var trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);
        trustStore.setCertificateEntry("stub", cert);
        var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        stubTrust = (X509TrustManager) tmf.getTrustManagers()[0];
        trustingStub = SSLContext.getInstance("TLS");
        trustingStub.init(null, new X509TrustManager[] {stubTrust}, null);

        server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(stubContext));
        server.createContext("/", this::handle);
        server.start();
        routes.put("/echo", r -> ("method=" + r.method() + "\ntarget=" + r.target() + "\nx-api-key=" + r.header("X-Api-Key")
                + "\nauthorization=" + r.header("Authorization") + "\nbody=" + r.bodyText()).getBytes(StandardCharsets.UTF_8));
    }

    public int port() {
        return server.getAddress().getPort();
    }

    /** Answer {@code path} with what {@code respond} returns for the request. */
    public void route(String path, Function<Received, byte[]> respond) {
        routes.put(path, respond);
    }

    private void handle(HttpExchange exchange) throws IOException {
        var body = exchange.getRequestBody().readAllBytes();
        var hostHeader = exchange.getRequestHeaders().getFirst("Host");
        var host = hostHeader == null ? "" : hostHeader.replaceFirst(":\\d+$", "");
        var target = exchange.getRequestURI().getRawPath()
                + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery());
        var request = new Received(host, exchange.getRequestMethod(), target, Map.copyOf(exchange.getRequestHeaders()), body);
        received.add(request);
        var path = exchange.getRequestURI().getPath();
        if (path.equals("/redirect")) {
            exchange.getResponseHeaders().add("Location", "https://" + OTHER + ":" + port() + "/echo");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
            return;
        }
        var respond = routes.get(path);
        var out = respond == null ? "no route".getBytes(StandardCharsets.UTF_8) : respond.apply(request);
        exchange.sendResponseHeaders(respond == null ? 404 : 200, out.length == 0 ? -1 : out.length);
        try (var os = exchange.getResponseBody()) {
            os.write(out);
        }
    }

    /** How the proxy reaches the stub: either test name on loopback, trusting the stub's own certificate. */
    public OkHttpClient upstream() {
        return HttpFactories.general().newBuilder()
                .sslSocketFactory(trustingStub.getSocketFactory(), stubTrust)
                .dns(hostname -> List.of(InetAddress.getLoopbackAddress()))
                .followRedirects(false).followSslRedirects(false)
                .build();
    }

    /** A client that goes through the proxy with {@code grant}'s login and trusts JClaw's CA, as a command does. */
    public static OkHttpClient through(SecretGrant grant) throws Exception {
        var proxyUrl = URI.create(grant.environment().get("HTTPS_PROXY"));
        var userInfo = proxyUrl.getUserInfo().split(":", 2);
        return through(userInfo[0], userInfo[1]);
    }

    /**
     * The status and body a command holding {@code grant} gets when it sends {@code headerValue} as {@code X-Api-Key}
     * to {@link #BOUND}'s {@code /echo} through the proxy.
     */
    public String send(SecretGrant grant, String headerValue) throws Exception {
        try (var response = through(grant).newCall(new Request.Builder()
                .url("https://" + BOUND + ":" + port() + "/echo").header("X-Api-Key", headerValue).build()).execute()) {
            return response.code() + " " + response.body().string();
        }
    }

    public static OkHttpClient through(String user, String token) throws Exception {
        var trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);
        trustStore.setCertificateEntry("jclaw", SecretProxy.caForTest());
        var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        var trust = (X509TrustManager) tmf.getTrustManagers()[0];
        var context = SSLContext.getInstance("TLS");
        context.init(null, new X509TrustManager[] {trust}, null);
        return HttpFactories.general().newBuilder()
                .proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(InetAddress.getLoopbackAddress(),
                        SecretProxy.portForTest())))
                .proxyAuthenticator((route, response) -> response.request().header("Proxy-Authorization") != null ? null
                        : response.request().newBuilder().header("Proxy-Authorization", Credentials.basic(user, token)).build())
                .sslSocketFactory(context.getSocketFactory(), trust)
                .followRedirects(false).followSslRedirects(false)
                .build();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
