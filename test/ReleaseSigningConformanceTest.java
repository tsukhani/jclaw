import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;
import utils.ReleaseSignature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.regex.Pattern;


/**
 * Jenkins signs each release's {@code SHA256SUMS}, and {@code jclaw.sh upgrade} and {@code install.sh}
 * each pin the public key and refuse a release they cannot verify. A pin that drifts from the other,
 * or from the signing credential, is therefore a release nobody can install; this fails the build first.
 */
class ReleaseSigningConformanceTest extends UnitTest {

    private static final Pattern UPGRADER_KEY = Pattern.compile("(?m)^UPGRADE_RELEASE_PUBKEY='([^']+)'");
    private static final Pattern INSTALLER_KEY = Pattern.compile("(?m)^RELEASE_PUBKEY='([^']+)'");
    private static final Pattern UPGRADER_FLOOR = Pattern.compile("(?m)^UPGRADE_LAST_UNSIGNED=\"([0-9.]+)\"");
    private static final Pattern INSTALLER_FLOOR = Pattern.compile("(?m)^LAST_UNSIGNED_RELEASE=\"([0-9.]+)\"");

    // Signed on the Jenkins host by the private key now held in the jclaw-release-signing-key
    // credential. Rotating the key means re-making this pair with the new one.
    private static final String SELF_TEST = "jclaw release signing self-test\n";
    private static final String SELF_TEST_SIGNATURE =
            "MEUCIQDAGdSF7Wdg/ZUlVuyyr4p5q+0YdAG4aTw7QiM04siBcQIgf0wo8eaTvCkHA4PDzZD+wd1MyTVPXL5MAPXOM9+ynjs=";

    private static String read(String file) throws IOException {
        return Files.readString(Path.of(Play.applicationPath.getAbsolutePath()).resolve(file));
    }

    private static String pinned(String file, Pattern pattern) throws IOException {
        var m = pattern.matcher(read(file));
        assertTrue(m.find(), () -> file + " no longer declares " + pattern.pattern() + " — update this test with it");
        return m.group(1);
    }

    private static boolean verifies(String pem, String message) throws GeneralSecurityException {
        return ReleaseSignature.verifies(pem, Base64.getDecoder().decode(SELF_TEST_SIGNATURE),
                message.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void theInstallerAndTheUpgraderPinTheSameKey() throws IOException {
        assertEquals(pinned("jclaw.sh", UPGRADER_KEY), pinned("install.sh", INSTALLER_KEY),
                "jclaw.sh and install.sh pin different release-signing keys");
    }

    @Test
    void theKeyDockerUsersVerifyWithIsThePinnedKey() throws IOException {
        var pinned = pinned("jclaw.sh", UPGRADER_KEY);
        assertEquals(pinned, read("release-signing.pub").strip(),
                "release-signing.pub is not the key jclaw.sh pins, so a signed IMAGE_DIGEST would not verify under it");
        assertEquals(pinned, pinned("docker-pull-verified.sh", INSTALLER_KEY),
                "docker-pull-verified.sh pins a different key, so it would refuse every release's IMAGE_DIGEST");
    }

    @Test
    void theInstallerAndTheUpgraderAgreeOnTheLastUnsignedRelease() throws IOException {
        assertEquals(pinned("jclaw.sh", UPGRADER_FLOOR), pinned("install.sh", INSTALLER_FLOOR),
                "jclaw.sh and install.sh disagree on which releases must carry a signature");
    }

    @Test
    void thePinnedKeyIsThePublicHalfOfTheSigningCredential() throws IOException, GeneralSecurityException {
        var key = pinned("jclaw.sh", UPGRADER_KEY);
        assertTrue(verifies(key, SELF_TEST),
                "the pinned key does not verify a signature made by the signing credential's key");
        assertFalse(verifies(key, SELF_TEST + "x"), "the pinned key verified a message that was never signed");
    }

    @Test
    void theReleaseStageSignsAndPublishesTheSignature() throws IOException {
        var jenkinsfile = read("Jenkinsfile");
        assertTrue(jenkinsfile.contains("bin/sign-release.sh dist/SHA256SUMS"),
                "the Release stage no longer signs SHA256SUMS");
        assertTrue(Pattern.compile("gh release create [^\\n]*dist/SHA256SUMS\\.sig").matcher(jenkinsfile).find(),
                "the Release stage no longer attaches SHA256SUMS.sig, so every installed client would refuse the release");
    }

    @Test
    void theReleaseStageSignsTheDigestOfTheImageItPushed() throws IOException {
        var jenkinsfile = read("Jenkinsfile");
        assertTrue(jenkinsfile.contains("--metadata-file dist/image-metadata.json"),
                "the image push no longer records its digest, so IMAGE_DIGEST would be read from a tag anyone with push access can move");
        assertTrue(jenkinsfile.contains("bin/sign-release.sh dist/IMAGE_DIGEST"),
                "the Release stage no longer signs IMAGE_DIGEST");
        assertTrue(Pattern.compile("gh release upload [^\\n]*dist/IMAGE_DIGEST\\.sig").matcher(jenkinsfile).find(),
                "the Release stage no longer attaches IMAGE_DIGEST.sig");
    }
}
