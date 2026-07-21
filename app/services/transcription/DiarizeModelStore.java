package services.transcription;

import com.google.gson.JsonParser;
import services.ConfigService;
import services.EventLogger;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Settings-facing status/provisioning for the on-device diarization weights:
 * the gated pyannote diarizer (fixed) and the operator-selectable SER emotion
 * model. Mirrors {@link AsrModelStore} but keyed by HF repo (the diarize
 * sidecar downloads by repo), backed by the sidecar's detached-download
 * mechanism so a multi-GB pull never stalls status polling.
 */
public final class DiarizeModelStore extends ModelPrefetchStore<DiarizeModelStore.Status> {

    /** The fixed pyannote diarizer repo — matches diarize.py's MODEL constant. */
    public static final String PYANNOTE_REPO = "pyannote/speaker-diarization-community-1";

    /** A selectable SER emotion model: its HF repo + a Settings label. */
    public record SerOption(String repo, String displayName) {}

    /** The SER models the operator may pick — a fixed trio, not free-text (the
     *  download endpoint downloads whatever is chosen, so the set is bounded).
     *  The first is the default, used when the config is unset or stale. */
    public static final List<SerOption> SER_MODELS = List.of(
            new SerOption("MERaLiON/MERaLiON-SER-v1", "MERaLiON-SER v1 — multilingual SEA (default)"),
            new SerOption("superb/wav2vec2-base-superb-er", "wav2vec2 SUPERB-ER — English"),
            new SerOption("Dpngtm/wav2vec2-emotion-recognition", "wav2vec2 emotion — English"));

    /** SER model used when {@code transcription.diarization.emotionModel} is
     *  blank or no longer one of {@link #SER_MODELS}. */
    public static final String DEFAULT_SER_REPO = SER_MODELS.get(0).repo();

    /** Whether {@code repo} is one of the selectable SER models. */
    public static boolean isAllowedSer(String repo) {
        return SER_MODELS.stream().anyMatch(m -> m.repo().equals(repo));
    }

    /** No {@code totalBytes}: diarization repos are free-text/unknown-size, so
     *  the UI shows a live downloaded-MB counter rather than a percentage. */
    public record Status(State state, long bytesDownloaded, String engine, String error) {}

    private static final DiarizeModelStore INSTANCE = new DiarizeModelStore();

    private DiarizeModelStore() {}

    /** Test-only: clear prefetch state so tests don't bleed. */
    public static void resetForTest() {
        INSTANCE.resetPrefetchState();
    }

    /** The SER repo the operator has configured, coerced to the default when
     *  blank or not one of {@link #SER_MODELS} (e.g. a stale free-text value
     *  from before the picker was restricted to a fixed set). */
    public static String serRepo() {
        var v = ConfigService.get("transcription.diarization.emotionModel", "");
        return v != null && isAllowedSer(v.trim()) ? v.trim() : DEFAULT_SER_REPO;
    }

    /** Status of the pyannote diarizer + the given SER repo in ONE sidecar round
     *  trip. Hosts without uv report {@link State#UNAVAILABLE} with a reason
     *  instead of failing the Settings page. */
    public static Map<String, Status> statusAll(String serRepo) {
        var repos = new LinkedHashSet<String>();
        repos.add(PYANNOTE_REPO);
        repos.add(serRepo);
        var out = new LinkedHashMap<String, Status>();
        if (!services.UvProbe.isAvailable()) {
            var reason = "on-device diarization requires the 'uv' launcher on PATH: "
                    + services.UvProbe.lastResult().reason();
            for (var r : repos) out.put(r, new Status(State.UNAVAILABLE, 0, null, reason));
            return out;
        }
        try {
            var body = new DiarizeSidecarClient().models(String.join(",", repos));
            var status = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("status");
            for (var r : repos) {
                var s = status.getAsJsonObject(r);
                if (s != null) out.put(r, INSTANCE.rowFor(r, s));
            }
        } catch (RuntimeException e) {
            for (var r : repos) out.putIfAbsent(r, new Status(State.UNAVAILABLE, 0, null,
                    "diarize sidecar unavailable: " + e.getMessage()));
        }
        return out;
    }

    /** Diarization repos are unknown-size, so there is no size denominator to
     *  fold in — the row is built straight from the shared fields. */
    @Override
    protected Status buildStatus(String key, State state, long bytesDownloaded,
                                 String engine, String error) {
        return new Status(state, bytesDownloaded, engine, error);
    }

    /** Kick a background prefetch of an HF repo; single-flight per repo. */
    public static void prefetch(String repo) {
        INSTANCE.startPrefetch(repo, r -> {
            new DiarizeSidecarClient().prefetch(r);
            EventLogger.info("transcription",
                    "diarization weight %s prefetch requested".formatted(r));
        });
    }
}
