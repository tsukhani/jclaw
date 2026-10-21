import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import play.Play;
import play.test.UnitTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JF-15: a thread interrupted during a cold read must not close the file database for every other
 * thread. The suite runs H2 in memory, where no file is involved, so this opens a temporary file
 * database with the shipped {@code db.url}'s file system and options.
 */
class H2InterruptSafetyTest extends UnitTest {

    @TempDir Path tmp;

    @Test
    void theShippedUrlOpensTheFileThroughRetry() throws Exception {
        assertTrue(shippedUrl().startsWith("jdbc:h2:retry:./data/jclaw;"), shippedUrl());
    }

    @Test
    void anInterruptedColdReadLeavesTheDatabaseUsableOnTheShippedUrl() throws Exception {
        var failure = interruptThenUse(urlFor(tmp.resolve("db"), shippedUrl()));
        assertNull(failure, () -> "another thread could not use the database after an interrupted read: " + failure);
    }

    @Test
    void withoutRetryTheSameInterruptClosesTheDatabaseForEveryThread() throws Exception {
        // The control: proves the test above reaches the file on the interrupted thread.
        var plain = shippedUrl().replace("jdbc:h2:retry:", "jdbc:h2:file:");
        var failure = interruptThenUse(urlFor(tmp.resolve("db"), plain));
        assertNotNull(failure, "a plain file URL survived the interrupted read, so the test no longer provokes the failure");
        assertTrue(failure.contains("Closed"), () -> "it failed, but not on a closed file channel: " + failure);
    }

    /** Null when another thread can still read and write after an interrupted cold read, else what failed. */
    private static @Nullable String interruptThenUse(String url) throws Exception {
        try (Connection c = open(url); var s = c.createStatement()) {
            s.execute("CREATE TABLE t(id BIGINT AUTO_INCREMENT PRIMARY KEY, payload VARCHAR(8192))");
            for (int i = 0; i < 10; i++) {
                s.execute("INSERT INTO t(payload) SELECT REPEAT('x', 4096) FROM SYSTEM_RANGE(1, 500)");
                c.commit();
            }
        }
        // Reopened, so the 1 MB cache starts cold and the scan must read the file on the interrupted thread.
        try (Connection held = open(url)) {
            // Deliberate: an isolated database, which is the one place interrupting a thread that touches H2 is allowed.
            var reader = Thread.ofPlatform().start(() -> {
                Thread.currentThread().interrupt();
                try (var s = held.createStatement(); var rs = s.executeQuery("SELECT SUM(LENGTH(payload)) FROM t")) {
                    rs.next();
                } catch (SQLException ignored) {
                    // Whether the read itself fails is not the point: what matters is the database afterwards.
                }
            });
            reader.join();
            try (Connection other = open(url); var s = other.createStatement()) {
                s.execute("INSERT INTO t(payload) VALUES ('after')");
                other.commit();
                try (var rs = s.executeQuery("SELECT COUNT(*) FROM t")) {
                    rs.next();
                    return rs.getLong(1) == 5001 ? null : "unexpected row count " + rs.getLong(1);
                }
            } catch (SQLException e) {
                var chain = new StringBuilder();
                for (Throwable t = e; t != null; t = t.getCause()) {
                    chain.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append(" <- ");
                }
                return chain.toString();
            }
        }
    }

    private static Connection open(String url) throws SQLException {
        var c = DriverManager.getConnection(url, "", "");
        c.setAutoCommit(false);
        return c;
    }

    private static String urlFor(Path dir, String shipped) throws Exception {
        Files.createDirectories(dir);
        assertTrue(shipped.contains("./data/jclaw;"), shipped);
        return shipped.replace("./data/jclaw", dir.toAbsolutePath().resolve("jclaw").toString()) + ";CACHE_SIZE=1024";
    }

    private static String shippedUrl() throws Exception {
        var conf = Files.readString(Path.of(Play.applicationPath.getAbsolutePath(), "conf", "application.conf"));
        var m = Pattern.compile("(?m)^db\\.url=(.+)$").matcher(conf);
        assertTrue(m.find(), "conf/application.conf no longer declares db.url");
        return m.group(1).strip();
    }
}
