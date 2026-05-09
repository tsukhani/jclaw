package services.transcription;

import services.ConfigService;

import java.util.Optional;

/**
 * Picks the {@link TranscriptionService} implementation matching the
 * operator's {@code transcription.provider} config selection. Returns
 * {@link Optional#empty()} when no provider is configured (or when the
 * configured value is unrecognized) — callers treat that as "no
 * transcription backend available, skip dispatch."
 *
 * <p>Recognised values match {@code frontend/pages/settings.vue} radio
 * options:
 * <ul>
 *   <li>{@code whisper-local} → {@link WhisperLocalTranscriptionService}</li>
 *   <li>{@code openai} → {@link OpenAiTranscriptionClient}</li>
 *   <li>{@code openrouter} → {@link OpenRouterTranscriptionClient}</li>
 * </ul>
 *
 * <p>Looked up on every dispatch so a Settings change takes effect on
 * the next inbound audio attachment without requiring a restart.
 */
public final class TranscriptionRouter {

    private TranscriptionRouter() {}

    public static Optional<TranscriptionService> configuredService() {
        var provider = ConfigService.get("transcription.provider");
        if (provider == null || provider.isBlank()) return Optional.empty();
        return switch (provider) {
            case "whisper-local" -> Optional.of(new WhisperLocalTranscriptionService());
            case "openai" -> Optional.of(new OpenAiTranscriptionClient());
            case "openrouter" -> Optional.of(new OpenRouterTranscriptionClient());
            default -> Optional.empty();
        };
    }
}
