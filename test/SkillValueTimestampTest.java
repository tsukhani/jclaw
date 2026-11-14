import models.Agent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.UnitTest;
import services.AgentService;
import services.SkillSecrets;
import services.SkillSettings;
import services.Tx;
import utils.AppClock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/**
 * A skill's secret or setting reads back with the time its save reported. Linux's clock has nanoseconds and H2
 * keeps microseconds, so a save that answered with its in-memory time disagreed with every later read.
 */
class SkillValueTimestampTest extends UnitTest {

    private static final Clock NANOSECONDS = Clock.fixed(Instant.parse("2026-11-13T16:50:34.182938984Z"), ZoneOffset.UTC);
    private static final Instant STORED = Instant.parse("2026-11-13T16:50:34.182938Z");

    private Long agentId;

    @BeforeEach
    void agent() {
        agentId = AgentService.create("stamp-" + System.nanoTime(), "openrouter", "gpt-4.1").id;
    }

    @AfterEach
    void remove() {
        Tx.run(() -> AgentService.delete(Agent.findById(agentId)));
    }

    @Test
    void aSavedSecretReportsTheTimeItIsListedWith() {
        var saved = AppClock.callWith(NANOSECONDS, () -> Tx.run(() -> SkillSecrets.put(Agent.findById(agentId), "demo",
                "NAS_PASSWORD", "fake-value-stamp", List.of("nas.example.com"))));

        var listed = Tx.run(() -> {
            JPA.em().clear();
            return SkillSecrets.list(Agent.findById(agentId), "demo");
        });

        assertEquals(STORED, saved.setAt());
        assertEquals(List.of(saved), listed);
    }

    @Test
    void aSavedSettingReportsTheTimeItIsListedWith() {
        var saved = AppClock.callWith(NANOSECONDS, () -> Tx.run(() -> SkillSettings.put(Agent.findById(agentId), "demo",
                "NAS_URL", "https://nas.example.com")));

        var listed = Tx.run(() -> {
            JPA.em().clear();
            return SkillSettings.list(Agent.findById(agentId), "demo");
        });

        assertEquals(STORED, saved.setAt());
        assertEquals(List.of(saved), listed);
    }
}
