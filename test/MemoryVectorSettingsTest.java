import memory.MemoryStoreFactory;
import memory.MemoryVectorSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.ConfigService;


/**
 * JCLAW-930: the vector settings come from the config store, and changing one rebuilds
 * the memory-store singleton.
 *
 * <p>Serialized on {@code LuceneTestSync} because it mutates two process-global things —
 * the config rows behind {@link MemoryVectorSettings} and the {@link MemoryStoreFactory}
 * singleton — which the sibling memory tests also read. The play1 engine runs unit and
 * functional lanes concurrently, so an unserialized flip here would leak into them.
 */
class MemoryVectorSettingsTest extends UnitTest {

    @BeforeEach
    void setup() {
        LuceneTestSync.closedForTest();
        clearKeys();
        MemoryStoreFactory.reset();
    }

    @AfterEach
    void teardown() {
        clearKeys();
        MemoryStoreFactory.reset();
        LuceneTestSync.release();
    }

    private static void clearKeys() {
        for (var k : new String[] {MemoryVectorSettings.KEY_ENABLED, MemoryVectorSettings.KEY_PROVIDER,
                MemoryVectorSettings.KEY_MODEL, MemoryVectorSettings.KEY_DIMENSIONS}) {
            ConfigService.delete(k);
        }
    }

    @Test
    void defaultsLeaveVectorMemoryOff() {
        assertFalse(MemoryVectorSettings.enabled(),
                "a fresh install has no embedding provider — vector memory must default off");
        assertEquals("", MemoryVectorSettings.provider(),
                "blank provider means fall back to the registry primary");
    }

    @Test
    void settingsAreReadFromTheConfigStore() {
        ConfigService.set(MemoryVectorSettings.KEY_ENABLED, "true");
        ConfigService.set(MemoryVectorSettings.KEY_PROVIDER, "lm-studio");
        ConfigService.set(MemoryVectorSettings.KEY_MODEL, "some-embed-model");
        ConfigService.set(MemoryVectorSettings.KEY_DIMENSIONS, "768");

        assertEquals(true, MemoryVectorSettings.enabled());
        assertEquals("lm-studio", MemoryVectorSettings.provider());
        assertEquals("some-embed-model", MemoryVectorSettings.model());
        assertEquals(768, MemoryVectorSettings.dimensions());
    }

    @Test
    void changingAVectorSettingRebuildsTheStoreSingleton() {
        var before = MemoryStoreFactory.get();
        assertSame(before, MemoryStoreFactory.get(), "the factory caches, or this test proves nothing");

        ConfigService.setWithSideEffects(MemoryVectorSettings.KEY_MODEL, "a-different-model");

        assertNotSame(before, MemoryStoreFactory.get(),
                "the store caches settings in final fields, so a stale singleton would serve the old model forever");
    }

    @Test
    void clearingAVectorSettingAlsoRebuildsTheStoreSingleton() {
        ConfigService.set(MemoryVectorSettings.KEY_MODEL, "a-model");
        var before = MemoryStoreFactory.get();

        ConfigService.deleteWithSideEffects(MemoryVectorSettings.KEY_MODEL);

        assertNotSame(before, MemoryStoreFactory.get(),
                "clearing a key changes the effective setting exactly as writing one does");
    }

    // --- JCLAW-935: the vector backend's dimension ceiling ---

    @Test
    void luceneRejectsDimensionsAboveItsCodecLimit() {
        int max = services.search.LuceneIndexer.maxVectorDimensions();

        assertTrue(MemoryVectorSettings.dimensionsSupported(max, false),
                "a model exactly at the limit must remain usable");
        assertFalse(MemoryVectorSettings.dimensionsSupported(max + 1, false));
        // text-embedding-3-small is this class's own default model and is 1536-dim,
        // so on lucene-core 10.5.2 (limit 1024) the default is unusable on the default
        // backend — the case that makes this guard worth having.
        assertFalse(MemoryVectorSettings.dimensionsSupported(1536, false),
                "1536 exceeds the Lucene limit; accepting it would silently drop documents");
        assertTrue(MemoryVectorSettings.dimensionsSupported(768, false),
                "the nomic-embed-text dimension in use today must not be caught by the guard");
    }

    @Test
    void postgresIsNotConstrainedByTheLuceneLimit() {
        // pgvector's ceiling is far higher and has not been verified against a live
        // Postgres, so no limit is asserted for that backend rather than guessing one.
        assertEquals(0, MemoryVectorSettings.maxDimensions(true));
        assertTrue(MemoryVectorSettings.dimensionsSupported(1536, true));
        assertTrue(MemoryVectorSettings.dimensionsSupported(3072, true));
    }

    @Test
    void unrelatedConfigKeysDoNotRebuildTheStore() {
        var before = MemoryStoreFactory.get();

        ConfigService.setWithSideEffects("memory.recall.limit", "7");

        assertSame(before, MemoryStoreFactory.get(),
                "only the vector settings are cached in the store — rebuilding on every config write "
                        + "would re-run pgvector provisioning on unrelated saves");
    }
}
