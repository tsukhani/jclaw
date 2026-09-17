import jobs.DbSchedulerSchemaInitJob;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.ConfigService;
import utils.ErrorRendering;
import utils.ErrorTemplate;
import utils.ErrorTemplates;
import utils.StartupErrorTemplates;

import java.io.IOException;
import java.sql.SQLException;

/**
 * JCLAW-1136: the failures an operator hits on the console, where there is no UI to consult.
 *
 * <p>The config half was a defect rather than a wording problem — the three numeric getters
 * discarded the {@code NumberFormatException} and substituted the default, so a typo'd value
 * produced no message at all and the instance ran on a value nobody chose. What is pinned here
 * is that it now says which key and which value, once per bad value rather than per read.
 *
 * <p>Every config key below is unique to its own test: the "already reported" map is a
 * process-global, and play1 runs test classes concurrently.
 */
class StartupErrorTemplatesTest extends UnitTest {

    // ── config parsing ────────────────────────────────────────────────────────

    @Test
    void aRejectedValueNamesTheKeyAndTheValueItRejected() {
        var report = ConfigService.parseFailureReport(
                "jclaw1136.names.key", "12 seconds", "a whole number", "30");

        assertNotNull(report, "the first read of a bad value must produce a message");
        assertTrue(report.contains("jclaw1136.names.key"), report);
        assertTrue(report.contains("12 seconds"), report);
        assertTrue(report.contains("30"), "the default now in effect belongs in the message: " + report);
        assertEquals(3, report.split("\n\n").length, "three parts: " + report);
    }

    @Test
    void theSameBadValueIsQuietAfterTheFirstReadButAChangedOneShoutsAgain() {
        // getInt for one key runs on the request path, not just at boot, so every read shouting
        // would bury the first occurrence under its own repeats.
        var key = "jclaw1136.repeat.key";
        assertNotNull(ConfigService.parseFailureReport(key, "abc", "a whole number", "1"));
        assertNull(ConfigService.parseFailureReport(key, "abc", "a whole number", "1"),
                "the same value on the same key must not report twice");
        assertNotNull(ConfigService.parseFailureReport(key, "def", "a whole number", "1"),
                "a value that changed — corrected or newly broken — is a new fact");
    }

    @Test
    void aClearedValueIsNotReportedBecauseTakingTheDefaultIsWhatItAsksFor() {
        assertNull(ConfigService.parseFailureReport("jclaw1136.blank.key", "   ", "a whole number", "1"),
                "an emptied field means 'use the default', which is exactly what happens");
    }

    @Test
    void getIntReportsThroughThatGateRatherThanSwallowingTheFailure() {
        var key = "jclaw1136.getint.key";
        ConfigService.set(key, "eight");

        assertEquals(4, ConfigService.getInt(key, 4), "the default still stands in for the value");
        // The gate is already spent, which is only true if getInt went through it.
        assertNull(ConfigService.parseFailureReport(key, "eight", "a whole number", "4"),
                "getInt must report the failure, not discard it");
    }

    @Test
    void getLongReportsThroughThatGateToo() {
        var key = "jclaw1136.getlong.key";
        ConfigService.set(key, "3,600,000");

        assertEquals(60L, ConfigService.getLong(key, 60L));
        assertNull(ConfigService.parseFailureReport(key, "3,600,000", "a whole number", "60"));
    }

    @Test
    void getDoubleReportsANonFiniteLiteralAsWellAsOneThatWillNotParse() {
        // "NaN" parses without throwing (JCLAW-1016), so it reaches the report by the other
        // path — an operator who typed it is owed the same message.
        var nonFinite = "jclaw1136.getdouble.nonfinite";
        ConfigService.set(nonFinite, "NaN");
        assertEquals(0.5, ConfigService.getDouble(nonFinite, 0.5));
        assertNull(ConfigService.parseFailureReport(nonFinite, "NaN", "a finite number", "0.5"));

        var unparseable = "jclaw1136.getdouble.unparseable";
        ConfigService.set(unparseable, "0,62");
        assertEquals(0.5, ConfigService.getDouble(unparseable, 0.5));
        assertNull(ConfigService.parseFailureReport(unparseable, "0,62", "a finite number", "0.5"));
    }

    // ── database and DDL at boot ──────────────────────────────────────────────

    @Test
    void aBootFailureDistinguishesAnUnreachableDatabaseFromUnreadableDdl() {
        // Both used to render as "db-scheduler schema init failed: <driver text>", which points
        // at neither remedy.
        var database = DbSchedulerSchemaInitJob.bootFailureMessage(
                new SQLException("Connection refused: connect"));
        assertTrue(database.contains("Connection refused: connect"), database);
        assertTrue(database.contains("db.url"), "the database remedy names the connection settings: " + database);

        var ddl = DbSchedulerSchemaInitJob.bootFailureMessage(new IOException("Permission denied"));
        assertTrue(ddl.contains("Permission denied"), ddl);
        assertTrue(ddl.contains("db_scheduler_h2.sql"), "the DDL remedy names the file: " + ddl);

        for (var message : new String[] {database, ddl}) {
            assertEquals(3, message.split("\n\n").length, "three parts: " + message);
        }
    }

    @Test
    void aStatementRefusedOnAWorkingConnectionIsNotReportedAsAConnectionFailure() {
        // Refused DDL used to render as "the database refused the connection", which sends the
        // operator to db.url when the fix is a permission.
        var message = DbSchedulerSchemaInitJob.bootFailureMessage(new DbSchedulerSchemaInitJob.DdlStatementFailed(
                new SQLException("permission denied for schema public")));

        assertTrue(message.contains("permission denied for schema public"), message);
        assertFalse(message.contains("refused the connection"), message);
        assertTrue(message.contains("permission to create tables"), message);
        assertEquals(3, message.split("\n\n").length, "three parts: " + message);
    }

    @Test
    void aDriverThatCarriesNoMessageStillRendersACompleteSentence() {
        var message = DbSchedulerSchemaInitJob.bootFailureMessage(new SQLException());

        assertFalse(message.contains("startup:"),
                "a trailing colon with nothing after it reads as a truncated message: " + message);
        assertFalse(message.contains("null"), message);
    }

    // ── the registry ──────────────────────────────────────────────────────────

    @Test
    void theStartupCodesAreRegisteredSoAGenericLookupAlsoHasARemedy() {
        for (var code : new String[] {StartupErrorTemplates.CONFIG_PARSE_FAILED,
                StartupErrorTemplates.DATABASE_UNAVAILABLE,
                StartupErrorTemplates.SCHEMA_DDL_UNREADABLE,
                StartupErrorTemplates.SCHEMA_DDL_FAILED}) {
            assertTrue(ErrorTemplates.isRegistered(code),
                    code + " must be in the merged registry, not falling back");
        }
    }

    @Test
    void theFactoriesAndTheRegisteredRowsGiveTheSameRemedy() {
        // A specific message that advised something different from the code's own row would be a
        // second source of truth.
        for (var specific : new ErrorTemplate[] {
                StartupErrorTemplates.configParseFailure("a.key", "x", "a whole number", "1"),
                StartupErrorTemplates.databaseUnavailable("Connection refused"),
                StartupErrorTemplates.schemaDdlUnreadable("Permission denied"),
                StartupErrorTemplates.schemaDdlFailed("permission denied for schema public")}) {
            var registered = ErrorTemplates.forCode(specific.code());
            assertEquals(registered.whatToCheck(), specific.whatToCheck(), specific.code());
            assertEquals(registered.howToRetry(), specific.howToRetry(), specific.code());
            assertNotEquals(registered.whatBroke(), specific.whatBroke(),
                    "only whatBroke carries the specifics: " + specific.code());
        }
    }

    @Test
    void theConsoleRenderingCarriesNoMarkup() {
        // The destination is a terminal and a log file: no markup parser, no colour support.
        for (var template : new ErrorTemplate[] {
                StartupErrorTemplates.configParseFailure("a.key", "x", "a whole number", "1"),
                StartupErrorTemplates.databaseUnavailable("Connection refused"),
                StartupErrorTemplates.schemaDdlUnreadable("Permission denied"),
                StartupErrorTemplates.schemaDdlFailed("permission denied for schema public")}) {
            var rendered = ErrorRendering.PLAIN.render(template);
            for (var markup : new String[] {"*", "`", "#", "[", "]", "\u001b"}) {
                assertFalse(rendered.contains(markup), "PLAIN must not emit " + markup + ": " + rendered);
            }
        }
    }
}
