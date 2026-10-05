import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.ReleaseSignature;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Base64;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.type;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;


/** The verifier {@code jclaw.sh upgrade} runs out of process: what it accepts, refuses and cannot check. */
class ReleaseSignatureTest extends UnitTest {

    private static final byte[] SUMS = "0123abcd  jclaw-bundle.zip\n".getBytes(StandardCharsets.UTF_8);

    private static String pem;
    private static byte[] signature;
    private static Path dir;

    @BeforeAll
    static void signWithAThrowawayKey() throws GeneralSecurityException, IOException {
        var keys = keyPair();
        pem = pem(keys);
        signature = sign(keys, SUMS);
        dir = Files.createTempDirectory("release-signature-test");
        Files.writeString(dir.resolve("key.pem"), pem);
        Files.write(dir.resolve("sums.sig"), signature);
        Files.write(dir.resolve("sums"), SUMS);
    }

    private static KeyPair keyPair() throws GeneralSecurityException {
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static String pem(KeyPair keys) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(keys.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
    }

    private static byte[] sign(KeyPair keys, byte[] data) throws GeneralSecurityException {
        var signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(keys.getPrivate());
        signer.update(data);
        return signer.sign();
    }

    private static int run(String... files) {
        var args = Arrays.stream(files).map(f -> dir.resolve(f).toString()).toArray(String[]::new);
        return ReleaseSignature.run(args, new PrintStream(new ByteArrayOutputStream()));
    }

    @Test
    void aSignatureByThePinnedKeyVerifies() throws GeneralSecurityException {
        assertTrue(ReleaseSignature.verifies(pem, signature, SUMS));
        assertEquals(0, run("key.pem", "sums.sig", "sums"));
    }

    @Test
    void alteredContentDoesNotVerify() throws GeneralSecurityException, IOException {
        var altered = "ffff0000  jclaw-bundle.zip\n".getBytes(StandardCharsets.UTF_8);
        assertFalse(ReleaseSignature.verifies(pem, signature, altered));
        Files.write(dir.resolve("sums.altered"), altered);
        assertEquals(1, run("key.pem", "sums.sig", "sums.altered"));
    }

    @Test
    void anotherKeysSignatureDoesNotVerify() throws GeneralSecurityException {
        assertFalse(ReleaseSignature.verifies(pem, sign(keyPair(), SUMS), SUMS));
    }

    @Test
    void aTruncatedOrEmptySignatureDoesNotVerify() throws GeneralSecurityException, IOException {
        assertFalse(ReleaseSignature.verifies(pem, Arrays.copyOf(signature, 20), SUMS));
        assertFalse(ReleaseSignature.verifies(pem, new byte[0], SUMS));
        Files.write(dir.resolve("empty.sig"), new byte[0]);
        assertEquals(1, run("key.pem", "empty.sig", "sums"));
    }

    @Test
    void whatCannotBeCheckedIsNeverReportedAsVerified() throws IOException {
        Files.writeString(dir.resolve("bad.pem"), "-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----\n");
        assertEquals(2, run("bad.pem", "sums.sig", "sums"), "an unreadable key");
        assertEquals(2, run("key.pem", "missing.sig", "sums"), "a missing signature file");
        assertEquals(2, run("key.pem", "sums.sig"), "a missing argument");
    }

    @Test
    void theVerifierStandsAlone() {
        // jclaw.sh runs this class on precompiled/java alone, with no jar on the classpath: a
        // reference outside the JDK fails at runtime, in the middle of an upgrade.
        ArchRule rule = classes().that().haveNameMatching("utils\\.ReleaseSignature(\\$.*)?")
                .should().onlyDependOnClassesThat(resideInAPackage("java..").or(type(ReleaseSignature.class)))
                .because("jclaw.sh upgrade runs ReleaseSignature out of process on the compiled classes alone");
        rule.check(ArchitectureTest.APP_CLASSES);
    }
}
