package services.tts;

import services.ConfigService;

/**
 * Dispatches read-aloud synthesis to whichever engine the operator has selected
 * in Settings &gt; Speech (JCLAW-789/793). The selection is the {@code
 * tts.engine} config key, read fresh on every call, so switching engines takes
 * effect on the next request with no restart. The per-engine model/voice come
 * from {@code tts.<engine>.model} / {@code tts.<engine>.voice}, falling back to
 * the engine's default {@link TtsModel} when unset or stale.
 */
public final class TtsRouter {

    private TtsRouter() {}

    /** One shared sidecar client — it owns the JVM-wide fair lock + HTTP pool. */
    private static final TtsSidecarClient SIDECAR = new TtsSidecarClient();

    /** The operator's currently-selected engine ({@code tts.engine}). */
    public static TtsEngine currentEngine() {
        return TtsEngine.fromConfigOrDefault(ConfigService.get("tts.engine"));
    }

    /** The configured model id for {@code engine}, or the engine's default when
     *  unset, blank, or pointing at a model that belongs to a different engine. */
    public static String modelFor(TtsEngine engine) {
        var configured = ConfigService.get("tts." + engine.id() + ".model");
        boolean valid = configured != null && !configured.isBlank()
                && TtsModel.byId(configured).map(m -> m.engine() == engine).orElse(false);
        return valid ? configured : TtsModel.defaultFor(engine).id();
    }

    /** Optional per-engine voice/speaker ({@code tts.<engine>.voice}). */
    public static String voiceFor(TtsEngine engine) {
        return ConfigService.get("tts." + engine.id() + ".voice");
    }

    /**
     * Synthesize {@code text} to WAV bytes using the selected engine + its
     * configured model. Throws {@link TtsException} if the chosen engine can't
     * satisfy the request (sidecar unreachable, model download failed, …).
     */
    public static byte[] synthesize(String text) {
        var engine = currentEngine();
        var model = modelFor(engine);
        var voice = voiceFor(engine);
        return switch (engine) {
            case SIDECAR -> SIDECAR.synthesize(text, model, voice, "wav");
            case JVM -> TtsJvmEngine.synthesize(text, model, voice, null);
        };
    }
}
