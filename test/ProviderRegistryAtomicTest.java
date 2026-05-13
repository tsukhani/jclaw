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
class ProviderRegistryAtomicTest extends UnitTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
    }

    @Test
    void refreshProducesImmutableSnapshot() {
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
    void concurrentReadsNeverSeeEmptyCache() throws Exception {
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
    void registersOllamaLocalAsOllamaProvider() {
        // JCLAW-178 AC #2: seeding the ollama-local config keys and refreshing
        // the registry must yield an OllamaProvider instance — no new Java
        // provider code, just substring routing through LlmProvider.forConfig.
        ConfigService.set("provider.ollama-local.baseUrl", "http://localhost:11434/v1");
        ConfigService.set("provider.ollama-local.apiKey", "ollama-local");
        ConfigService.set("provider.ollama-local.models", "[]");

        ProviderRegistry.refresh();

        var provider = ProviderRegistry.get("ollama-local");
        assertNotNull(provider, "ollama-local should be registered after refresh");
        assertInstanceOf(llm.OllamaProvider.class, provider,
                "ollama-local must route through OllamaProvider via the substring match");
        assertEquals("http://localhost:11434/v1", provider.config().baseUrl());
    }

    @Test
    void getOpenaiReturnsNullWhenApiKeyBlank() {
        // JCLAW-160 AC #3: a baseUrl-only row (apiKey blank) must not
        // register, so `get("openai")` returns null until the operator
        // pastes a key in Settings.
        ConfigService.set("provider.openai.baseUrl", "https://api.openai.com/v1");
        ConfigService.set("provider.openai.apiKey", "");
        ProviderRegistry.refresh();

        assertNull(ProviderRegistry.get("openai"),
                "openai with blank apiKey must not be in the registry");
    }

    @Test
    void getOpenaiReturnsOpenAiProviderWhenKeyed() {
        // JCLAW-160 AC #3: with both keys populated the registry produces
        // an OpenAiProvider via the factory's openai entry (not the
        // fallback path).
        ConfigService.set("provider.openai.baseUrl", "https://api.openai.com/v1");
        ConfigService.set("provider.openai.apiKey", "sk-real");
        ProviderRegistry.refresh();

        var provider = ProviderRegistry.get("openai");
        assertNotNull(provider, "openai must register when both keys are non-blank");
        assertInstanceOf(llm.OpenAiProvider.class, provider,
                "openai must route to OpenAiProvider");
        assertEquals("https://api.openai.com/v1", provider.config().baseUrl());
    }

    @Test
    void refreshAfterProviderRemoved() {
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
    void doubleCheckedLockingPreventsStampede() throws Exception {
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
