import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.ConfigService;
import services.transcription.OpenAiTranscriptionClient;
import services.transcription.OpenRouterTranscriptionClient;
import services.transcription.TranscriptionRouter;
import services.transcription.WhisperLocalTranscriptionService;

/**
 * JCLAW-315: cover the {@code transcription.provider} → service routing logic.
 * Pure unit test — the router only consults {@link ConfigService} and returns
 * a constructed service instance; no network or filesystem access.
 */
class TranscriptionRouterTest extends UnitTest {

    private static final String KEY = "transcription.provider";

    @BeforeEach
    void setUp() {
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
    }

    @AfterEach
    void tearDown() {
        ConfigService.delete(KEY);
        ConfigService.clearCache();
        Fixtures.deleteDatabase();
    }

    @Test
    void emptyWhenProviderUnset() {
        // Unset key → fall-through to Optional.empty so the orchestrator
        // treats it as "no backend, skip dispatch."
        assertTrue(TranscriptionRouter.configuredService().isEmpty(),
                "no provider configured → empty");
    }

    @Test
    void emptyWhenProviderBlank() {
        ConfigService.set(KEY, "   ");
        assertTrue(TranscriptionRouter.configuredService().isEmpty(),
                "blank provider must be treated as unset");
    }

    @Test
    void emptyWhenProviderUnrecognised() {
        // Unknown radio value (typo, deprecated provider) — router falls
        // through the switch default to Optional.empty rather than blowing
        // up the calling pipeline.
        ConfigService.set(KEY, "vosk-magical");
        assertTrue(TranscriptionRouter.configuredService().isEmpty(),
                "unrecognised provider must not throw");
    }

    @Test
    void whisperLocalSelectsLocalService() {
        ConfigService.set(KEY, "whisper-local");
        var svc = TranscriptionRouter.configuredService();
        assertTrue(svc.isPresent(), "whisper-local must resolve");
        assertTrue(svc.get() instanceof WhisperLocalTranscriptionService,
                "whisper-local must return the local service: " + svc.get().getClass());
    }

    @Test
    void openaiSelectsOpenAiClient() {
        // Note: no API-key check happens in configuredService; the router
        // hands back the client and the cloud client itself reports a clean
        // failure later when it discovers no credentials on call. That's
        // the "falls back gracefully" path from the AC — graceful means
        // the router doesn't crash, not that it pre-validates auth.
        ConfigService.set(KEY, "openai");
        var svc = TranscriptionRouter.configuredService();
        assertTrue(svc.isPresent(), "openai must resolve");
        assertTrue(svc.get() instanceof OpenAiTranscriptionClient,
                "openai must return the OpenAI client: " + svc.get().getClass());
    }

    @Test
    void openrouterSelectsOpenRouterClient() {
        ConfigService.set(KEY, "openrouter");
        var svc = TranscriptionRouter.configuredService();
        assertTrue(svc.isPresent(), "openrouter must resolve");
        assertTrue(svc.get() instanceof OpenRouterTranscriptionClient,
                "openrouter must return the OpenRouter client: " + svc.get().getClass());
    }

    @Test
    void routerLooksUpFreshOnEveryCall() {
        // Settings UI changes the provider while the JVM is hot — the next
        // dispatch must observe the new selection without restart.
        ConfigService.set(KEY, "whisper-local");
        var first = TranscriptionRouter.configuredService();
        assertTrue(first.isPresent() && first.get() instanceof WhisperLocalTranscriptionService);

        ConfigService.set(KEY, "openai");
        var second = TranscriptionRouter.configuredService();
        assertTrue(second.isPresent() && second.get() instanceof OpenAiTranscriptionClient,
                "settings change must take effect on the next call");
    }
}
