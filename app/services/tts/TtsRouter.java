package services.tts;

import org.jspecify.annotations.Nullable;
import play.Logger;
import services.ConfigService;
import services.UvProbe;

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
        if (configured != null && !configured.isBlank()
                && TtsModel.byId(configured).map(m -> m.engine() == engine).orElse(false)) {
            return configured;
        }
        return TtsModel.defaultFor(engine).id();
    }

    /** Optional per-engine voice/speaker ({@code tts.<engine>.voice}). */
    public static @Nullable String voiceFor(TtsEngine engine) {
        return ConfigService.get("tts." + engine.id() + ".voice");
    }

    /**
     * Reference clip the engine clones its speaker from, or null (JCLAW-865).
     *
     * <p>Only meaningful for models with no named presets — Chatterbox and
     * Qwen3-TTS — where cloning IS the voice picker. Gated on
     * {@link TtsModel#supportsCloning} rather than sent blindly, so a clip left
     * configured from a previous model cannot leak into Kokoro, which selects its
     * speaker by name and would be confused by both.
     */
    public static @Nullable String refAudioFor(TtsEngine engine) {
        if (!TtsModel.cloningById(modelFor(engine))) return null;
        return TtsReferenceVoice.activePath(engine);
    }

    /**
     * Synthesize {@code text} to WAV bytes using the selected engine + its
     * configured model. Throws {@link TtsException} if the chosen engine can't
     * satisfy the request (sidecar unreachable, model download failed, …).
     *
     * <p>Deliberately has no fallback. Read-aloud in the chat view is a discrete
     * action the operator can retry, and an explicit error there is more useful
     * than silently substituting a different engine's voice. Voice mode, where
     * losing a turn mid-conversation is disproportionate, uses
     * {@link #synthesizeForVoice} instead.
     */
    public static byte[] synthesize(String text) {
        return synthesizeWith(currentEngine(), text);
    }

    /**
     * Audio for one voice-mode utterance, and which engine actually produced it.
     *
     * @param fellBack true when the operator's selected engine failed and the
     *                 other one covered for it — the caller is expected to make
     *                 that visible rather than pass off downgraded audio as the
     *                 chosen engine's output.
     */
    public record Spoken(byte[] audio, TtsEngine engine, boolean fellBack) {}

    /**
     * Voice-mode synthesis, falling back to the other engine when the selected
     * one fails (JCLAW-861).
     *
     * <p>The two engines fail in different ways, which is what makes falling
     * back worthwhile here rather than merely papering over a problem. The JVM
     * engine is in-process with its weights already on disk and has no external
     * dependencies; the sidecar needs uv, a Python environment, a subprocess
     * that can die for reasons unrelated to the request, and possibly a network
     * fetch. The higher-quality engine is the less reliable one, so a live
     * conversation should not end because it stumbled.
     */
    public static Spoken synthesizeForVoice(String text) {
        var engine = currentEngine();
        try {
            return new Spoken(synthesizeWith(engine, text), engine, false);
        } catch (RuntimeException primary) {
            var alt = fallbackFor(engine);
            if (alt == null) throw primary;
            try {
                var audio = synthesizeWith(alt, text);
                Logger.warn("TtsRouter: %s synthesis failed (%s) — fell back to %s for this utterance",
                        engine.id(), primary.getMessage(), alt.id());
                return new Spoken(audio, alt, true);
            } catch (RuntimeException secondary) {
                // Surface the ORIGINAL failure. The operator chose that engine and
                // it is the one they need to fix; the fallback's own error is a
                // detail, attached rather than substituted.
                primary.addSuppressed(secondary);
                throw primary;
            }
        }
    }

    /**
     * The engine to try when {@code primary} fails, or null when there is no
     * sane alternative.
     *
     * <p>One-directional on purpose. Sidecar → JVM trades quality for a local,
     * dependency-free engine. The reverse would fall back to the <em>less</em>
     * reliable option, which is backwards.
     *
     * <p>The JVM engine is only offered when its weights are already extracted.
     * {@link TtsJvmEngine#synthesize} would otherwise download hundreds of
     * megabytes on demand, and stalling a live turn on a cold download is worse
     * than failing it promptly.
     */
    private static @Nullable TtsEngine fallbackFor(TtsEngine primary) {
        if (primary != TtsEngine.SIDECAR) return null;
        return TtsJvmEngine.isModelPresent(modelFor(TtsEngine.JVM)) ? TtsEngine.JVM : null;
    }

    private static byte[] synthesizeWith(TtsEngine engine, String text) {
        var model = modelFor(engine);
        var voice = voiceFor(engine);
        return switch (engine) {
            case SIDECAR -> {
                // Without uv the sidecar cannot start, but the client discovers that only
                // after minutes of building the environment and pulling weights — holding
                // the JVM-wide sidecar lock throughout. The probe is cached, so checking it
                // first costs nothing and turns that stall into the same actionable reason
                // Settings already displays. It also lets synthesizeForVoice reach its JVM
                // fallback while the turn is still worth saving, which is the entire point
                // of having a fallback (JCLAW-885).
                if (!UvProbe.isAvailable()) {
                    throw new TtsException("TTS sidecar needs 'uv' on PATH: " + UvProbe.lastResult().reason());
                }
                yield SIDECAR.synthesize(text, model, voice, "wav", refAudioFor(engine));
            }
            case JVM -> TtsJvmEngine.synthesize(text, model, voice, null);
        };
    }
}
