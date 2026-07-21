import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.SkillBinaryScanner;
import services.scanners.Scanner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JCLAW-825: the SHA-256 fed to scanners is now streamed through an 8 KB buffer
 * instead of {@code Files.readAllBytes}. This proves the streaming digest is
 * byte-for-byte identical to the whole-file digest across a file larger than the
 * buffer (so the multi-read loop is exercised, not just a single read).
 */
class SkillBinaryScannerDigestTest extends UnitTest {

    @Test
    void streamingDigestMatchesWholeFileDigestForMultiChunkBinary() throws Exception {
        Path skillDir = Files.createTempDirectory("scanner-digest-");
        try {
            // 20 KB of deterministic bytes → forces several 8 KB read iterations.
            var bytes = new byte[20 * 1024];
            for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i % 251);
            Files.write(skillDir.resolve("payload.bin"), bytes);

            var expected = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));

            var captured = new AtomicReference<String>();
            Scanner capturing = new Scanner() {
                @Override public String name() { return "capture"; }
                @Override public boolean isEnabled() { return true; }
                @Override public Verdict lookup(String sha256) {
                    captured.set(sha256);
                    return Verdict.clean();
                }
            };

            var violations = SkillBinaryScanner.scan(skillDir, List.of(capturing));

            assertTrue(violations.isEmpty(), "clean stub scanner should flag nothing: " + violations);
            assertEquals(expected, captured.get(),
                    "streaming digest must equal the whole-file SHA-256 for a multi-chunk binary");
        } finally {
            try (var walk = Files.walk(skillDir)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> { try { Files.delete(p); } catch (Exception _) {} });
            }
        }
    }
}
