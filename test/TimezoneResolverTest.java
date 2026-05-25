import models.Agent;
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
    private String savedConfigValue;

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        // Save and clear the Config row so each test starts from a known
        // state; restore on teardown so other tests don't see our mutations.
        savedConfigValue = ConfigService.get(CONFIG_KEY);
        ConfigService.delete(CONFIG_KEY);
        ConfigService.clearCache();
    }

    @AfterEach
    void teardown() {
        ConfigService.delete(CONFIG_KEY);
        if (savedConfigValue != null) ConfigService.set(CONFIG_KEY, savedConfigValue);
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
    void applicationConfDefaultWinsWhenConfigAbsent() {
        // tasks.defaultTimezone=UTC in application.conf is the baked-in
        // baseline (the line this story added). With the Config row
        // absent, that's what the resolver returns.
        var task = newTask(null);
        assertEquals(ZoneId.of("UTC"), TimezoneResolver.resolve(task),
                "application.conf fallback must surface when Config row absent");
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
    void invalidConfigFallsThroughToConf() {
        // Similar defense: a bad Config value falls through to
        // application.conf rather than throwing at resolution time.
        ConfigService.set(CONFIG_KEY, "Mars/Olympus_Mons");
        var task = newTask(null);
        assertEquals(ZoneId.of("UTC"), TimezoneResolver.resolve(task),
                "invalid Config tasks.defaultTimezone must fall through to application.conf");
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
