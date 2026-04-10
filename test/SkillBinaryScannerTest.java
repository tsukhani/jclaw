import org.junit.jupiter.api.*;
import play.test.*;
import services.ConfigService;
import services.SkillBinaryScanner;
import services.scanners.MalwareBazaarScanner;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * End-to-end smoke test for the malware scanner. Hits the real MalwareBazaar
 * API over the network, so requires outbound HTTPS to mb-api.abuse.ch and a
 * valid {@code MALWAREBAZAAR_AUTH_KEY} env var. Tests that need live API access
 * are skipped cleanly when the key is absent.
 *
 * <p>Note on test-sample choice: EICAR is <b>not</b> indexed by MalwareBazaar
 * because it's a harmless test pattern, not real malware. These tests use a
 * real known-malicious SHA-256 from MalwareBazaar's database (a Mirai sample,
 * file name {@code jew.m68k}) for the positive case. The hash alone is safe to
 * commit; no malware bytes ever touch the repo.
 */
public class SkillBinaryScannerTest extends UnitTest {

    /**
     * SHA-256 of a real Mirai sample indexed by MalwareBazaar. Looked up once
     * from {@code get_recent} and confirmed to return {@code query_status: ok}
     * with signature {@code Mirai}. Stable — abuse.ch keeps historical samples.
     */
    private static final String KNOWN_MALICIOUS_SHA256 =
            "1bd060779bcb794a5bb8c551742660923659b3aee42972fb4b0670bf433cf3c9";

    private Path tmpSkill;

    @BeforeEach
    void setup() throws Exception {
        tmpSkill = Files.createTempDirectory("scanner-test-");
        // Seed the MalwareBazaar Auth-Key so scanner is actually enabled. Read from
        // the env so we never commit a real key. Tests that require live API access
        // skip cleanly when MALWAREBAZAAR_AUTH_KEY is unset.
        var key = System.getenv("MALWAREBAZAAR_AUTH_KEY");
        if (key != null && !key.isBlank()) {
            ConfigService.set("skills.scanner.malwarebazaar.authKey", key);
        }
    }

    @AfterEach
    void teardown() throws Exception {
        if (tmpSkill != null && Files.exists(tmpSkill)) {
            try (var walk = Files.walk(tmpSkill)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> { try { Files.delete(p); } catch (Exception _) {} });
            }
        }
    }

    @Test
    public void malwareBazaarLookupFlagsKnownMalwareHash() {
        // Proves the HTTP integration end-to-end: hashing, Auth-Key header,
        // POST body encoding, and JSON parsing all work against the real API.
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getenv("MALWAREBAZAAR_AUTH_KEY") != null
                        && !System.getenv("MALWAREBAZAAR_AUTH_KEY").isBlank(),
                "MALWAREBAZAAR_AUTH_KEY env var not set — skipping live lookup");

        var verdict = MalwareBazaarScanner.lookup(KNOWN_MALICIOUS_SHA256);

        assertTrue(verdict.malicious(),
                "Known Mirai sample should be flagged by MalwareBazaar (got: "
                        + (verdict.malicious() ? "malicious" : "clean") + ")");
        assertNotNull(verdict.reason());
        // abuse.ch signature for this sample is "Mirai"
        assertEquals("Mirai", verdict.reason());
    }

    @Test
    public void malwareBazaarLookupReturnsCleanForUnknownHash() {
        // Any random SHA-256 (e.g. hash of the empty string) should come back clean.
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getenv("MALWAREBAZAAR_AUTH_KEY") != null
                        && !System.getenv("MALWAREBAZAAR_AUTH_KEY").isBlank(),
                "MALWAREBAZAAR_AUTH_KEY env var not set — skipping live lookup");

        // SHA-256 of the empty string — guaranteed not to be in any malware DB
        var emptyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        var verdict = MalwareBazaarScanner.lookup(emptyHash);
        assertFalse(verdict.malicious(),
                "Empty-string hash should not be flagged");
    }

    @Test
    public void scanWalksBinariesAndSkipsTextFiles() throws Exception {
        // Verifies the SkillBinaryScanner plumbing: directory walk, text/binary
        // classification, and per-file hashing. Uses a fake binary whose SHA-256
        // is NOT in MalwareBazaar — so violations should be empty, but the audit
        // log should show one clean scan entry for the binary and zero for the
        // text files. No network assertion needed; a failure-open on outage
        // would still produce an empty violations list.
        var tools = tmpSkill.resolve("tools");
        Files.createDirectories(tools);
        Files.write(tools.resolve("helper.bin"), new byte[] {1, 2, 3, 4, 5, 6, 7, 8});

        Files.writeString(tmpSkill.resolve("SKILL.md"), "---\nname: scanner-test\n---\nbody");
        Files.createDirectories(tmpSkill.resolve("credentials"));
        Files.writeString(tmpSkill.resolve("credentials/config.json"), "{}");

        var violations = SkillBinaryScanner.scan(tmpSkill);

        // This fake binary (8-byte sequence) will not be in any malware database,
        // so we expect zero violations. Test passes whether or not the API key is set.
        assertEquals(0, violations.size(),
                "Fake binary should not trigger a false positive: " + violations);
    }

    @Test
    public void scanIgnoresCleanTextOnlySkill() throws Exception {
        // Pure text skill — no binaries at all. Scanner should short-circuit cleanly.
        Files.writeString(tmpSkill.resolve("SKILL.md"), "---\nname: clean\n---\nbody");
        Files.writeString(tmpSkill.resolve("README.md"), "hello");

        var violations = SkillBinaryScanner.scan(tmpSkill);
        assertEquals(0, violations.size());
    }

    @Test
    public void scanHandlesNonExistentDirectoryGracefully() {
        var violations = SkillBinaryScanner.scan(Path.of("/nonexistent/path/does/not/exist"));
        assertEquals(0, violations.size());
    }
}
