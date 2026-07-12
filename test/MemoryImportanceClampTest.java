import models.Memory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;

/**
 * JCLAW-733: {@link Memory#importance} is a raw public field (Play
 * active-record), so a writer other than the controller — the auto-capture
 * extractor, a store implementation, a test — can assign it directly. The
 * @PrePersist/@PreUpdate clamp guarantees it can never land in the DB outside
 * [0.0, 1.0]. Persists through a real flush + clear + re-read so the assertion
 * is on the value that round-tripped, not the in-memory field.
 *
 * <p>Lucene-guarded: persisting a Memory fires its @PostPersist index upsert;
 * holding the index closed for the test keeps it a deterministic no-op.
 */
class MemoryImportanceClampTest extends UnitTest {

    @BeforeEach
    void setup() {
        LuceneTestSync.closedForTest();
        Fixtures.deleteDatabase();
    }

    @AfterEach
    void release() {
        LuceneTestSync.release();
    }

    private Memory persistWithImportance(double importance) {
        var agent = AgentService.create("clamp-" + System.nanoTime(), "openrouter", "gpt-4.1");
        var m = new Memory();
        m.agent = agent;
        m.text = "clamp me";
        m.category = "core";
        m.importance = importance;
        m.save();
        JPA.em().flush();
        JPA.em().clear();
        return Memory.findById(m.id);
    }

    @Test
    void aboveRangeClampsToOne() {
        assertEquals(1.0, persistWithImportance(42.0).importance, 0.0);
    }

    @Test
    void belowRangeClampsToZero() {
        assertEquals(0.0, persistWithImportance(-5.0).importance, 0.0);
    }

    @Test
    void inRangeIsUnchanged() {
        assertEquals(0.7, persistWithImportance(0.7).importance, 1e-9);
    }

    @Test
    void updateAlsoClamps() {
        var agent = AgentService.create("clamp-upd-" + System.nanoTime(), "openrouter", "gpt-4.1");
        var m = new Memory();
        m.agent = agent;
        m.text = "starts in range";
        m.importance = 0.5;
        m.save();
        JPA.em().flush();
        Long id = m.id;

        // Direct out-of-range write on the managed entity, then update: @PreUpdate clamps.
        m.importance = 42.0;
        m.save();
        JPA.em().flush();
        JPA.em().clear();

        Memory reloaded = Memory.findById(id);
        assertEquals(1.0, reloaded.importance, 0.0);
    }
}
