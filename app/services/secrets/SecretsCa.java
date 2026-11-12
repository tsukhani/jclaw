package services.secrets;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.jspecify.annotations.Nullable;
import utils.AppClock;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The certificate authority the secrets proxy signs each host's certificate with (JF-324). It lives in memory for
 * one start of JClaw: its private key is never written anywhere, and a command trusts it only through the bundle
 * files JClaw hands that command. The bundle also holds the JDK's own trust anchors, so a tool given it as its
 * whole trust store still verifies every other site.
 */
final class SecretsCa {

    private static final Duration CA_LIFETIME = Duration.ofDays(400);
    private static final Duration LEAF_LIFETIME = Duration.ofDays(30);
    private static final Duration BACKDATE = Duration.ofHours(1);
    private static final int MAX_CACHED_HOSTS = 256;
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(?:\\.\\d{1,3}){3}");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] NO_PASSWORD = new char[0];

    private static volatile @Nullable SecretsCa instance;

    private final KeyPair caKeys;
    private final X509Certificate caCert;
    private final Path caFile;
    private final Path bundleFile;
    private final ConcurrentHashMap<String, SSLContext> hostContexts = new ConcurrentHashMap<>();

    private SecretsCa() throws GeneralSecurityException, IOException {
        caKeys = newKeys();
        var name = new X500Name("CN=JClaw secrets proxy (this machine only)");
        var now = AppClock.now();
        var builder = new JcaX509v3CertificateBuilder(name, serial(), Date.from(now.minus(BACKDATE)),
                Date.from(now.plus(CA_LIFETIME)), name, caKeys.getPublic());
        var ext = new JcaX509ExtensionUtils();
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(0));
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
        builder.addExtension(Extension.subjectKeyIdentifier, false, ext.createSubjectKeyIdentifier(caKeys.getPublic()));
        caCert = sign(builder, caKeys);

        var dir = Files.createTempDirectory("jclaw-secrets-proxy-");
        caFile = dir.resolve("ca.pem");
        bundleFile = dir.resolve("bundle.pem");
        Files.writeString(caFile, pem(caCert));
        var bundle = new StringBuilder(pem(caCert));
        for (var anchor : jdkTrustAnchors()) bundle.append(pem(anchor));
        Files.writeString(bundleFile, bundle);
        dir.toFile().deleteOnExit();
        caFile.toFile().deleteOnExit();
        bundleFile.toFile().deleteOnExit();
    }

    static SecretsCa get() {
        var ca = instance;
        if (ca != null) return ca;
        synchronized (SecretsCa.class) {
            ca = instance;
            if (ca == null) {
                try {
                    ca = new SecretsCa();
                } catch (GeneralSecurityException | IOException e) {
                    throw new IllegalStateException("The secrets proxy's certificate authority could not be made", e);
                }
                instance = ca;
            }
            return ca;
        }
    }

    /** The folder holding both trust files, or null while no command has needed them yet. */
    static @Nullable Path folderIfCreated() {
        var ca = instance;
        return ca == null ? null : ca.caFile.getParent();
    }

    /** The CA alone, for a tool that adds it to its own roots ({@code NODE_EXTRA_CA_CERTS}). */
    Path caFile() {
        return caFile;
    }

    /** The CA plus the JDK's trust anchors, for a tool that takes one file as its whole trust store. */
    Path bundleFile() {
        return bundleFile;
    }

    X509Certificate caCertificate() {
        return caCert;
    }

    /** A server-side TLS context presenting a certificate for {@code host}, issued once per host per start. */
    SSLContext serverContext(String host) {
        var cached = hostContexts.get(host);
        if (cached != null) return cached;
        if (hostContexts.size() >= MAX_CACHED_HOSTS) hostContexts.clear();
        return hostContexts.computeIfAbsent(host, h -> {
            try {
                return newServerContext(h);
            } catch (GeneralSecurityException | IOException e) {
                throw new IllegalStateException("No certificate could be issued for " + h, e);
            }
        });
    }

    private SSLContext newServerContext(String host) throws GeneralSecurityException, IOException {
        var leafKeys = newKeys();
        var now = AppClock.now();
        var builder = new JcaX509v3CertificateBuilder(caCert, serial(), Date.from(now.minus(BACKDATE)),
                Date.from(now.plus(LEAF_LIFETIME)), new X500Name("CN=" + host), leafKeys.getPublic());
        var ext = new JcaX509ExtensionUtils();
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
        builder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
        var san = IPV4.matcher(host).matches()
                ? new GeneralName(GeneralName.iPAddress, host) : new GeneralName(GeneralName.dNSName, host);
        builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(san));
        // Python 3.13's default context verifies strictly and refuses a leaf without these two.
        builder.addExtension(Extension.subjectKeyIdentifier, false, ext.createSubjectKeyIdentifier(leafKeys.getPublic()));
        builder.addExtension(Extension.authorityKeyIdentifier, false, ext.createAuthorityKeyIdentifier(caCert));
        var leaf = sign(builder, caKeys);

        var store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setKeyEntry("leaf", leafKeys.getPrivate(), NO_PASSWORD, new Certificate[] {leaf, caCert});
        var kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, NO_PASSWORD);
        var context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), null, RANDOM);
        return context;
    }

    private static KeyPair newKeys() throws GeneralSecurityException {
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
        return generator.generateKeyPair();
    }

    private static BigInteger serial() {
        return new BigInteger(127, RANDOM);
    }

    private static X509Certificate sign(X509v3CertificateBuilder builder, KeyPair issuer)
            throws GeneralSecurityException {
        try {
            var signer = new JcaContentSignerBuilder("SHA256withECDSA").build(issuer.getPrivate());
            return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
        } catch (OperatorCreationException e) {
            throw new GeneralSecurityException(e);
        }
    }

    private static X509Certificate[] jdkTrustAnchors() throws GeneralSecurityException {
        var factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        for (var manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager x509) return x509.getAcceptedIssuers();
        }
        return new X509Certificate[0];
    }

    static String pem(X509Certificate cert) throws CertificateEncodingException {
        var body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(cert.getEncoded());
        return "-----BEGIN CERTIFICATE-----\n" + body + "\n-----END CERTIFICATE-----\n";
    }
}
