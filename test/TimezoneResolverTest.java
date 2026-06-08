import models.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.ConfigService;
import services.TimezoneResolver;

import java.time.Instant;
import java.time.ZoneId;

/**
 * JCLAW-261 coverage for the four-step zone fallback chain:
 * per-task → Config DB → application.conf → JVM default.
 *
 * <p>Each scenario isolates one layer by setting/clearing the others
 * so the assertion pins the exact branch under test.
 */
class TimezoneResolverTest extends UnitTest {

    private static final String CONFIG_KEY = "tasks.defaultTimezone";
    private static final String APP_KEY = TimezoneResolver.APP_CONFIG_KEY;
    private String savedConfigValue;
    private String savedAppValue;

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        // Save and clear the Config rows so each test starts from a known
        // state; restore on teardown so other tests don't see our mutations.
        savedConfigValue = ConfigService.get(CONFIG_KEY);
        savedAppValue = ConfigService.get(APP_KEY);
        ConfigService.delete(CONFIG_KEY);
        ConfigService.delete(APP_KEY);
        ConfigService.clearCache();
    }

    @AfterEach
    void teardown() {
        ConfigService.delete(CONFIG_KEY);
        ConfigService.delete(APP_KEY);
        if (savedConfigValue != null) ConfigService.set(CONFIG_KEY, savedConfigValue);
        if (savedAppValue != null) ConfigService.set(APP_KEY, savedAppValue);
        ConfigService.clearCache();
    }

    @Test
    void perTaskTimezoneWinsOverEverything() {
        // task.timezone is the top of the fallback chain — even when the
        // Config row says something else, the per-task value is used.
        ConfigService.set(CONFIG_KEY, "America/Los_Angeles");
        var task = newTask("Asia/Tokyo");
        assertEquals(ZoneId.of("Asia/Tokyo"), TimezoneResolver.resolve(task),
                "per-task timezone must outrank Config DB default");
    }

    @Test
    void configDefaultWinsWhenTaskTimezoneIsNull() {
        // No per-task override → Config row applies.
        ConfigService.set(CONFIG_KEY, "Europe/Berlin");
        var task = newTask(null);
        assertEquals(ZoneId.of("Europe/Berlin"), TimezoneResolver.resolve(task));
    }

    @Test
    void tasksFollowOperatorZoneWhenNoExplicitTaskZone() {
        // No per-task zone and no tasks.defaultTimezone override → CRON /
        // SCHEDULED tasks follow the operator's wall-clock zone (app.timezone),
        // so a task fires at the operator's local time rather than UTC.
        ConfigService.set(APP_KEY, "Asia/Tokyo");
        var task = newTask(null);
        assertEquals(ZoneId.of("Asia/Tokyo"), TimezoneResolver.resolve(task),
                "tasks must default to the operator zone, not UTC");
    }

    @Test
    void tasksFallBackToServerZoneWhenNothingSet() {
        // Nothing configured anywhere → the operator zone defaults to the
        // server's JVM zone, and tasks inherit it. tasks.defaultTimezone ships
        // commented-out, so UTC is no longer forced.
        var task = newTask(null);
        assertEquals(ZoneId.systemDefault(), TimezoneResolver.resolve(task),
                "absent everything must fall through to the JVM default");
    }

    @Test
    void explicitTasksDefaultOverridesOperatorZone() {
        // The operator can still pin tasks to a zone different from their own
        // clock by setting tasks.defaultTimezone explicitly (Settings → Tasks).
        ConfigService.set(APP_KEY, "Asia/Tokyo");
        ConfigService.set(CONFIG_KEY, "UTC");
        var task = newTask(null);
        assertEquals(ZoneId.of("UTC"), TimezoneResolver.resolve(task),
                "explicit tasks.defaultTimezone must outrank the operator zone");
    }

    @Test
    void invalidPerTaskFallsThroughToConfig() {
        // Garbage per-task zone (shouldn't happen post-controller-validation,
        // but the resolver is read-time-defensive) falls through cleanly
        // to the next layer without throwing.
        ConfigService.set(CONFIG_KEY, "America/New_York");
        var task = newTask("Not/A/Real/Zone");
        assertEquals(ZoneId.of("America/New_York"), TimezoneResolver.resolve(task),
                "invalid task.timezone must fall through to Config default");
    }

    @Test
    void invalidConfigFallsThroughToOperatorZone() {
        // Defense: a bad tasks.defaultTimezone Config value falls through
        // (without throwing) to the operator zone rather than UTC.
        ConfigService.set(CONFIG_KEY, "Mars/Olympus_Mons");
        ConfigService.set(APP_KEY, "Europe/Paris");
        var task = newTask(null);
        assertEquals(ZoneId.of("Europe/Paris"), TimezoneResolver.resolve(task),
                "invalid task default must fall through to the operator zone");
    }

    @Test
    void currentDefaultReflectsConfigOverride() {
        // currentDefault() is what the Settings UI surfaces and what the
        // Next Run column uses for tasks without per-task zones.
        ConfigService.set(CONFIG_KEY, "Pacific/Auckland");
        assertEquals(ZoneId.of("Pacific/Auckland"), TimezoneResolver.currentDefault());
    }

    @Test
    void resolveWithNullTaskUsesGlobalChain() {
        // Defensive: null task shouldn't NPE — the resolver treats it as
        // "no per-task value" and walks the global chain.
        ConfigService.set(CONFIG_KEY, "Africa/Cairo");
        assertEquals(ZoneId.of("Africa/Cairo"), TimezoneResolver.resolve((Task) null));
    }

    // ─────────── appZone(): operator wall-clock zone ───────────

    @Test
    void appZoneUsesConfigOverride() {
        ConfigService.set(APP_KEY, "Asia/Kuala_Lumpur");
        assertEquals(ZoneId.of("Asia/Kuala_Lumpur"), TimezoneResolver.appZone(),
                "app.timezone Config row must drive the operator zone");
    }

    @Test
    void appZoneFallsBackToSystemDefaultWhenAbsent() {
        // No app.timezone Config row and (by default) no application.conf line
        // → the operator zone is the server's JVM default, NOT UTC.
        assertEquals(ZoneId.systemDefault(), TimezoneResolver.appZone(),
                "absent app.timezone must fall back to the JVM default");
    }

    @Test
    void appZoneInvalidConfigFallsThroughToSystemDefault() {
        ConfigService.set(APP_KEY, "Mars/Olympus_Mons");
        assertEquals(ZoneId.systemDefault(), TimezoneResolver.appZone(),
                "invalid app.timezone must fall through, never throw");
    }

    @Test
    void appZoneIsIndependentOfTasksDefault() {
        // The task scheduler default (UTC) must not leak into the operator's
        // wall-clock zone — they're separate settings with separate defaults.
        ConfigService.set(CONFIG_KEY, "UTC");
        assertEquals(ZoneId.systemDefault(), TimezoneResolver.appZone(),
                "tasks.defaultTimezone must not influence appZone()");
    }

    // ─────────── helpers ───────────

    private static Task newTask(String timezone) {
        var t = new Task();
        var agent = AgentService.create("tz-test-" + System.nanoTime(), "openrouter", "gpt-4.1");
        t.agent = agent;
        t.name = "tz-task-" + System.nanoTime();
        t.type = Task.Type.CRON;
        t.status = Task.Status.ACTIVE;
        t.cronExpression = "0 0 9 * * *";
        t.scheduledAt = Instant.now();
        t.timezone = timezone;
        t.save();
        return t;
    }
}
