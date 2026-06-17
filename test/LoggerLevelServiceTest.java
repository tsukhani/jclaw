import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.*;
import play.test.*;
import services.ConfigService;
import services.LoggerLevelService;

/**
 * Unit coverage for {@link LoggerLevelService}.
 *
 * <p>Every test that actually flips a log level targets a UNIQUE dummy logger
 * that no other code references, so it cannot affect (or be affected by) the
 * concurrently-running functional-test lane. We never apply an override to the
 * ROOT logger here — root level is genuinely process-global and would race the
 * other lane's logging.
 */
class LoggerLevelServiceTest extends UnitTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
    }

    // --- validate ---

    @Test
    void validateRejectsBlankLogger() {
        assertNotNull(LoggerLevelService.validate("", "DEBUG"));
        assertNotNull(LoggerLevelService.validate("   ", "DEBUG"));
        assertNotNull(LoggerLevelService.validate(null, "DEBUG"));
    }

    @Test
    void validateRejectsUnknownLevel() {
        assertNotNull(LoggerLevelService.validate("com.example.Foo", "VERBOSE"));
        assertNotNull(LoggerLevelService.validate("com.example.Foo", ""));
        assertNotNull(LoggerLevelService.validate("com.example.Foo", null));
    }

    @Test
    void validateAcceptsStandardLevelsCaseInsensitively() {
        assertNull(LoggerLevelService.validate("com.example.Foo", "debug"));
        assertNull(LoggerLevelService.validate("com.example.Foo", "WARN"));
        assertNull(LoggerLevelService.validate("root", "info"));
    }

    @Test
    void validateRejectsMalformedLoggerNames() {
        assertNotNull(LoggerLevelService.validate("com example.Foo", "DEBUG"),
                "internal whitespace must be rejected");
        assertNotNull(LoggerLevelService.validate(".com.example.Foo", "DEBUG"),
                "leading dot must be rejected");
        assertNotNull(LoggerLevelService.validate("com.example.Foo.", "DEBUG"),
                "trailing dot must be rejected");
        assertNotNull(LoggerLevelService.validate("com..example.Foo", "DEBUG"),
                "empty segment must be rejected");
    }

    @Test
    void validateAcceptsWellFormedNamesIncludingInnerClasses() {
        // Shape validation must NOT false-reject legitimate names: a bare tree,
        // a package tree, a class FQN, and an inner-class ($) logger name.
        assertNull(LoggerLevelService.validate("play", "DEBUG"));
        assertNull(LoggerLevelService.validate("controllers", "DEBUG"));
        assertNull(LoggerLevelService.validate("controllers.ApiChatController", "DEBUG"));
        assertNull(LoggerLevelService.validate("com.example.Outer$Inner", "DEBUG"));
    }

    @Test
    void knownLoggersIncludesRoot() {
        // The root LoggerConfig always exists; knownLoggers() surfaces it as
        // the "root" alias. Other entries vary by what has logged, so we only
        // pin the always-present one.
        assertTrue(LoggerLevelService.knownLoggers().contains("root"),
                "the root logger must always be a known name");
    }

    // --- apply / revert (unique dummy logger) ---

    @Test
    void applyThenRevertRestoresInheritedLevel() {
        String dummy = "test.jclaw.loggerlevel.applyrevert";
        Level inherited = LogManager.getLogger(dummy).getLevel(); // from parent/root

        LoggerLevelService.apply(dummy, "TRACE");
        try {
            assertEquals(Level.TRACE, LogManager.getLogger(dummy).getLevel(),
                    "override must take effect immediately");
        } finally {
            LoggerLevelService.revert(dummy);
        }
        assertEquals(inherited, LogManager.getLogger(dummy).getLevel(),
                "revert must restore the inherited level");
    }

    @Test
    void applyIgnoresInvalidLevelInsteadOfThrowing() {
        String dummy = "test.jclaw.loggerlevel.invalid";
        Level inherited = LogManager.getLogger(dummy).getLevel();
        // Tolerant apply: a bad level is logged + skipped, never an exception.
        LoggerLevelService.apply(dummy, "NONSENSE");
        assertEquals(inherited, LogManager.getLogger(dummy).getLevel(),
                "an invalid level must leave the logger untouched");
    }

    // --- list / applyAllFromConfig ---

    @Test
    void listReturnsOnlyPrefixedRowsStrippedOfPrefix() {
        ConfigService.set(LoggerLevelService.PREFIX + "com.example.Foo", "WARN");
        ConfigService.set("provider.openrouter.apiKey", "irrelevant");

        var entry = LoggerLevelService.list().stream()
                .filter(l -> l.logger().equals("com.example.Foo"))
                .findFirst();
        assertTrue(entry.isPresent(), "the persisted override must be listed");
        assertEquals("WARN", entry.get().level());
        assertTrue(LoggerLevelService.list().stream().noneMatch(l -> l.logger().contains("provider")),
                "non-logging config keys must not leak into the list");
    }

    @Test
    void applyAllFromConfigReplaysPersistedOverridesLive() {
        String dummy = "test.jclaw.loggerlevel.replay";
        ConfigService.set(LoggerLevelService.PREFIX + dummy, "ERROR");
        try {
            LoggerLevelService.applyAllFromConfig();
            assertEquals(Level.ERROR, LogManager.getLogger(dummy).getLevel(),
                    "boot-time replay must apply persisted overrides");
        } finally {
            LoggerLevelService.revert(dummy);
        }
    }
}
