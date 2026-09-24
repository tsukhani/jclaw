import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.browser.PlaywrightDriverDir;
import services.browser.PlaywrightNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/** The persistent driver copy playwright.cli.dir names, so a killed JVM leaves no unpacked driver behind. */
class PlaywrightDriverDirTest extends UnitTest {

    private Path root;

    @BeforeEach
    void makeRoot() throws IOException {
        root = Files.createTempDirectory("pw-driver-test");
    }

    @AfterEach
    void removeRoot() throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            for (var p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    private List<Path> entries() throws IOException {
        try (var list = Files.list(root)) {
            return list.toList();
        }
    }

    @Test
    void theDriverIsUnpackedOnceAndThenReused() throws IOException {
        var dir = PlaywrightDriverDir.install(root, "k");
        assertTrue(Files.isRegularFile(dir.resolve("package/cli.js")));
        assertTrue(Files.isExecutable(dir.resolve("node")), "driver-bundle is on the test classpath");
        assertTrue(PlaywrightDriverDir.isManaged(dir));

        var node = Files.readAttributes(dir.resolve("node"), BasicFileAttributes.class).fileKey();
        assertEquals(dir, PlaywrightDriverDir.install(root, "k"));
        assertEquals(node, Files.readAttributes(dir.resolve("node"), BasicFileAttributes.class).fileKey(),
                "a second install must reuse the copy, not unpack again");
        assertEquals(List.of(dir), entries(), "no staging directory is left behind");
    }

    @Test
    void concurrentInstallsLeaveOneCopy() throws Exception {
        var start = new CountDownLatch(1);
        Callable<Path> install = () -> {
            start.await();
            return PlaywrightDriverDir.install(root, "k");
        };
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = pool.submit(install);
            var b = pool.submit(install);
            start.countDown();
            assertEquals(a.get(), b.get());
        }
        assertEquals(1, entries().size(), "the losing copy is discarded: " + entries());
    }

    @Test
    void theKeyNamesPlaywrightsVersionAndThePlatform() {
        var key = PlaywrightDriverDir.key("mac-arm64");
        assertNotNull(key);
        assertTrue(key.matches("\\d+\\.\\d+\\.\\d+(-[\\w.]+)?-mac-arm64"), key);
    }

    @Test
    void onlyADirectoryJClawDidNotInstallIsAnOperatorsDriver() throws IOException {
        assertFalse(PlaywrightNode.operatorDriverDir(null));
        assertFalse(PlaywrightNode.operatorDriverDir(" "));
        assertTrue(PlaywrightNode.operatorDriverDir(root.toString()));
        assertFalse(PlaywrightNode.operatorDriverDir(PlaywrightDriverDir.install(root, "k").toString()));
    }
}
