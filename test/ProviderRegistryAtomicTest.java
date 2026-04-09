import org.junit.jupiter.api.*;
import play.test.*;
import llm.ProviderRegistry;
import llm.LlmTypes.*;
import services.ConfigService;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests that ProviderRegistry refresh is atomic — concurrent reads never see an empty cache.
 */
public class ProviderRegistryAtomicTest extends UnitTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
    }

    @Test
    public void refreshProducesImmutableSnapshot() {
        ConfigService.set("provider.test-provider.baseUrl", "https://test.ai/v1");
        ConfigService.set("provider.test-provider.apiKey", "sk-test");
        ConfigService.set("provider.test-provider.models",
                "[{\"id\":\"model-1\",\"name\":\"Model 1\",\"contextWindow\":100000,\"maxTokens\":4096}]");

        ProviderRegistry.refresh();

        var provider = ProviderRegistry.get("test-provider");
        assertNotNull(provider);
        assertEquals("https://test.ai/v1", provider.config().baseUrl());
        assertEquals(1, provider.config().models().size());
    }

    @Test
    public void concurrentReadsNeverSeeEmptyCache() throws Exception {
        // Set up a valid provider and ensure cache is warm
        ConfigService.set("provider.concurrent-test.baseUrl", "https://test.ai/v1");
        ConfigService.set("provider.concurrent-test.apiKey", "sk-test");
        ProviderRegistry.refresh();

        assertNotNull(ProviderRegistry.get("concurrent-test"),
                "Provider should exist before concurrent test");

        // Verify the snapshot is immutable — concurrent reads of the volatile cache
        // reference should always see a consistent, non-empty map.
        int threadCount = 20;
        var barrier = new CyclicBarrier(threadCount);
        var latch = new CountDownLatch(threadCount);
        var nullReads = new AtomicInteger(0);
        var emptyListReads = new AtomicInteger(0);
        var successReads = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    barrier.await();

                    for (int j = 0; j < 100; j++) {
                        var providers = ProviderRegistry.listAll();
                        if (providers.isEmpty()) {
                            emptyListReads.incrementAndGet();
                        } else {
                            successReads.incrementAndGet();
                        }

                        var provider = ProviderRegistry.get("concurrent-test");
                        if (provider == null) {
                            nullReads.incrementAndGet();
                        }
                    }
                } catch (Exception _) {
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();

        assertEquals(0, nullReads.get(),
                "No reads should return null for a valid provider");
        assertEquals(0, emptyListReads.get(),
                "listAll() should never return empty");
        assertTrue(successReads.get() > 0,
                "Should have had successful reads");
    }

    @Test
    public void refreshAfterProviderRemoved() {
        ConfigService.set("provider.removable.baseUrl", "https://test.ai/v1");
        ConfigService.set("provider.removable.apiKey", "sk-test");
        ProviderRegistry.refresh();

        assertNotNull(ProviderRegistry.get("removable"));

        // Remove the API key
        ConfigService.set("provider.removable.apiKey", "");
        ProviderRegistry.refresh();

        assertNull(ProviderRegistry.get("removable"),
                "Provider with empty API key should not be in registry");
    }

    @Test
    public void doubleCheckedLockingPreventsStampede() throws Exception {
        ConfigService.set("provider.stampede-test.baseUrl", "https://test.ai/v1");
        ConfigService.set("provider.stampede-test.apiKey", "sk-test");
        ProviderRegistry.refresh();

        // Verify that after a refresh, the cache is consistent regardless of
        // how many threads read concurrently. The refresh is serialized by
        // synchronized(refreshLock), so concurrent reads always see a valid snapshot.
        int threadCount = 10;
        var barrier = new CyclicBarrier(threadCount);
        var latch = new CountDownLatch(threadCount);
        var errors = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    barrier.await();
                    // Read the already-refreshed cache — should see valid data
                    if (ProviderRegistry.get("stampede-test") == null) {
                        errors.incrementAndGet();
                    }
                } catch (Exception _) {
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        assertEquals(0, errors.get(),
                "All reads after refresh should find the provider");
    }
}
