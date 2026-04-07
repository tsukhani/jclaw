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
                """[{"id":"model-1","name":"Model 1","contextWindow":100000,"maxTokens":4096}]""");

        ProviderRegistry.refresh();

        var provider = ProviderRegistry.get("test-provider");
        assertNotNull(provider);
        assertEquals("https://test.ai/v1", provider.baseUrl());
        assertEquals(1, provider.models().size());
    }

    @Test
    public void concurrentReadsNeverSeeEmptyCache() throws Exception {
        // Set up a valid provider
        ConfigService.set("provider.concurrent-test.baseUrl", "https://test.ai/v1");
        ConfigService.set("provider.concurrent-test.apiKey", "sk-test");
        ProviderRegistry.refresh();

        assertNotNull(ProviderRegistry.get("concurrent-test"),
                "Provider should exist before concurrent test");

        int threadCount = 20;
        var barrier = new CyclicBarrier(threadCount);
        var latch = new CountDownLatch(threadCount);
        var nullReads = new AtomicInteger(0);
        var emptyListReads = new AtomicInteger(0);
        var successReads = new AtomicInteger(0);

        // Launch threads that simultaneously read while triggering refreshes
        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            Thread.ofVirtual().start(() -> {
                try {
                    barrier.await(); // Ensure all threads start simultaneously

                    for (int j = 0; j < 100; j++) {
                        // Half the threads refresh, half read
                        if (idx % 2 == 0) {
                            ProviderRegistry.refresh();
                        } else {
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
                    }
                } catch (Exception _) {
                    // Barrier/interrupt exceptions
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();

        assertEquals(0, nullReads.get(),
                "No reads should return null for a valid provider during refresh");
        assertEquals(0, emptyListReads.get(),
                "listAll() should never return empty during refresh");
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

        // Force the cache to be "stale" by waiting — in practice we can't easily
        // manipulate lastRefresh, but we can verify that multiple concurrent calls
        // to refresh() don't corrupt the cache
        int threadCount = 10;
        var barrier = new CyclicBarrier(threadCount);
        var latch = new CountDownLatch(threadCount);
        var errors = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    barrier.await();
                    ProviderRegistry.refresh();
                    // Immediately read — should see valid data
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
                "All reads after concurrent refresh should find the provider");
    }
}
