package services.transcription;

import com.google.gson.JsonParser;
import play.Logger;
import services.ConfigService;
import services.EventLogger;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Settings-facing status/provisioning for the on-device diarization weights:
 * the gated pyannote diarizer (fixed) and the operator-selectable SER emotion
 * model. Mirrors {@link AsrModelStore} but keyed by HF repo (the diarize
 * sidecar downloads by repo), backed by the sidecar's detached-download
 * mechanism so a multi-GB pull never stalls status polling.
 */
public final class DiarizeModelStore {

    /** The fixed pyannote diarizer repo — matches diarize.py's MODEL constant. */
    public static final String PYANNOTE_REPO = "pyannote/speaker-diarization-community-1";
    /** SER model used when {@code transcription.diarization.emotionModel} is blank. */
    public static final String DEFAULT_SER_REPO = "MERaLiON/MERaLiON-SER-v1";

    public enum State {
        NOT_DOWNLOADED, DOWNLOADING, DOWNLOADED, ERROR, UNAVAILABLE;

        /** Wire status string shared with the imagegen/ASR panels. */
        public String wireName() {
            return switch (this) {
                case NOT_DOWNLOADED -> "ABSENT";
                case DOWNLOADED -> "AVAILABLE";
                case DOWNLOADING -> "DOWNLOADING";
                case ERROR -> "ERROR";
                case UNAVAILABLE -> "UNAVAILABLE";
            };
        }
    }

    /** No {@code totalBytes}: diarization repos are free-text/unknown-size, so
     *  the UI shows a live downloaded-MB counter rather than a percentage. */
    public record Status(State state, long bytesDownloaded, String engine, String error) {}

    private static final Map<String, CompletableFuture<Void>> PREFETCHES = new ConcurrentHashMap<>();
    private static final Map<String, String> PREFETCH_ERRORS = new ConcurrentHashMap<>();

    private DiarizeModelStore() {}

    /** Test-only: clear prefetch state so tests don't bleed. */
    public static void resetForTest() {
        PREFETCHES.clear();
        PREFETCH_ERRORS.clear();
    }

    /** The SER repo the operator has configured (blank → the default). */
    public static String serRepo() {
        var v = ConfigService.get("transcription.diarization.emotionModel", "");
        return v == null || v.isBlank() ? DEFAULT_SER_REPO : v.trim();
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
                if (s != null) out.put(r, rowFor(r, s));
            }
        } catch (RuntimeException e) {
            for (var r : repos) out.putIfAbsent(r, new Status(State.UNAVAILABLE, 0, null,
                    "diarize sidecar unavailable: " + e.getMessage()));
        }
        return out;
    }

    /** One Settings row from the sidecar's per-repo status object. The sidecar
     *  owns download state (detached subprocess + cache-dir progress); the JVM
     *  PREFETCH_ERRORS map only carries a failure to even reach the sidecar. */
    private static Status rowFor(String repo, com.google.gson.JsonObject s) {
        boolean cached = s.get("cached").getAsBoolean();
        boolean downloading = s.has("downloading") && s.get("downloading").getAsBoolean();
        long onDisk = s.get("bytesOnDisk").getAsLong();
        var engine = s.has("engine") && !s.get("engine").isJsonNull() ? s.get("engine").getAsString() : null;
        String error = s.has("error") && !s.get("error").isJsonNull()
                ? s.get("error").getAsString() : PREFETCH_ERRORS.get(repo);
        State state;
        if (downloading) {
            state = State.DOWNLOADING;
        } else if (error != null && !cached) {
            state = State.ERROR;
        } else {
            state = cached ? State.DOWNLOADED : State.NOT_DOWNLOADED;
        }
        return new Status(state, onDisk, engine, error);
    }

    /** Kick a background prefetch of an HF repo; single-flight per repo. */
    public static void prefetch(String repo) {
        PREFETCH_ERRORS.remove(repo);
        PREFETCHES.compute(repo, (r, existing) -> {
            if (existing != null && !existing.isDone()) return existing;
            return CompletableFuture.runAsync(() -> {
                try {
                    new DiarizeSidecarClient().prefetch(r);
                    EventLogger.info("transcription",
                            "diarization weight %s prefetch requested".formatted(r));
                } catch (RuntimeException e) {
                    PREFETCH_ERRORS.put(r, e.getMessage());
                    Logger.warn("DiarizeModelStore: prefetch of %s failed: %s", r, e.getMessage());
                    throw e;
                }
            });
        });
    }
}
