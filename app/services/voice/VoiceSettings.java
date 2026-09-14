package services.voice;

import org.jspecify.annotations.Nullable;
import services.ConfigService;

/**
 * The {@code voice.*} runtime keys, all edited in Settings &gt; Voice Mode, and the rules
 * {@code ConfigService.setWithSideEffects} applies when one is written.
 */
public final class VoiceSettings {

    public static final String PREFIX = "voice.";

    public static final String SPEECH_START_MS = "voice.endpoint.speechStartMs";
    public static final String BASE_SILENCE_MS = "voice.endpoint.baseSilenceMs";
    public static final String MAX_SILENCE_MS = "voice.endpoint.maxSilenceMs";
    public static final String MIN_UTTERANCE_MS = "voice.endpoint.minUtteranceMs";
    public static final String SEMANTIC_HOLD = "voice.endpoint.semanticHold";
    public static final String PARTIALS_ENABLED = "voice.partials.enabled";
    public static final String PARTIALS_INTERVAL_MS = "voice.partials.intervalMs";
    public static final String MAX_RUN_ON_CHARS = "voice.tts.maxRunOnChars";

    public static final int DEFAULT_SPEECH_START_MS = 180;
    public static final int DEFAULT_BASE_SILENCE_MS = 500;
    public static final int DEFAULT_MAX_SILENCE_MS = 1500;
    public static final int DEFAULT_MIN_UTTERANCE_MS = 200;
    public static final int DEFAULT_PARTIALS_INTERVAL_MS = 1200;
    public static final int DEFAULT_MAX_RUN_ON_CHARS = 220;

    private VoiceSettings() {}

    /** A message naming what {@code value} must be, or null when {@code key} accepts it. */
    public static @Nullable String rejectionFor(String key, @Nullable String value) {
        var v = value == null ? "" : value.trim();
        return switch (key) {
            // 0 makes the run-on flush cut nothing and loop for ever; a negative value throws.
            case SPEECH_START_MS, MAX_RUN_ON_CHARS -> wholeNumber(key, v, 1);
            case MIN_UTTERANCE_MS, PARTIALS_INTERVAL_MS -> wholeNumber(key, v, 0);
            // TurnEndpointer refuses a base silence above the maximum, failing every voice session at init.
            case BASE_SILENCE_MS -> {
                var rejected = wholeNumber(key, v, 1);
                int max = ConfigService.getInt(MAX_SILENCE_MS, DEFAULT_MAX_SILENCE_MS);
                yield rejected != null || Integer.parseInt(v) <= max ? rejected
                        : "%s must not exceed %s (%d).".formatted(key, MAX_SILENCE_MS, max);
            }
            case MAX_SILENCE_MS -> {
                var rejected = wholeNumber(key, v, 1);
                int base = ConfigService.getInt(BASE_SILENCE_MS, DEFAULT_BASE_SILENCE_MS);
                yield rejected != null || Integer.parseInt(v) >= base ? rejected
                        : "%s must be at least %s (%d).".formatted(key, BASE_SILENCE_MS, base);
            }
            case SEMANTIC_HOLD, PARTIALS_ENABLED ->
                    "true".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v) ? null
                            : key + " must be true or false.";
            default -> null;
        };
    }

    private static @Nullable String wholeNumber(String key, String v, int min) {
        try {
            if (Integer.parseInt(v) >= min) {
                return null;
            }
        } catch (NumberFormatException _) {
            // rejected below
        }
        return "%s must be a whole number of at least %d.".formatted(key, min);
    }
}
