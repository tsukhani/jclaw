package services.transcription;

import com.google.gson.JsonParser;
import play.Logger;
import services.EventLogger;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Settings-facing status/provisioning for ASR models (JCLAW-650), backed by
 * the sidecar's host-relevant engine instead of the retired ggml downloads:
 * the artifact the Download button fetches is now the one the host actually
 * runs — an mlx-community snapshot on Apple silicon, Systran CT2 weights on
 * CUDA/CPU hosts. Keeps {@code WhisperModelManager}'s status contract
 * (state/bytes/error) so the Settings frontend is unchanged.
 */
public final class AsrModelStore {

    public enum State {
        NOT_DOWNLOADED, DOWNLOADING, DOWNLOADED, ERROR, UNAVAILABLE;

        /**
         * Wire status string the Settings frontend consumes — the vocabulary
         * shared with the imagegen panel (ABSENT / DOWNLOADING / AVAILABLE /
         * ERROR / UNAVAILABLE). JCLAW-650 renamed this internal enum
         * (ABSENT→NOT_DOWNLOADED, AVAILABLE→DOWNLOADED) without updating the
         * frontend contract, so the Download button and Ready badge silently
         * stopped rendering; this projection restores it. Keep in lockstep with
         * the {@code TranscriptionModelStatus} union in SettingsTranscriptionPanel.vue.
         */
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

    public record Status(State state, long bytesDownloaded, long totalBytes,
                         String engine, String error) {}

    /** In-flight prefetches by model id; completed entries are pruned on read. */
    private static final Map<String, CompletableFuture<Void>> PREFETCHES = new ConcurrentHashMap<>();
    private static final Map<String, String> PREFETCH_ERRORS = new ConcurrentHashMap<>();

    private AsrModelStore() {}

    /** Test-only: clear prefetch state so tests don't bleed. */
    public static void resetForTest() {
        PREFETCHES.clear();
        PREFETCH_ERRORS.clear();
    }

    /**
     * Status of every known model in ONE sidecar round trip. Hosts without
     * the sidecar prerequisite (uv) report {@link State#UNAVAILABLE} with an
     * actionable reason instead of failing the whole Settings page.
     */
    public static Map<String, Status> statusAll() {
        var out = new ConcurrentHashMap<String, Status>();
        if (!services.UvProbe.isAvailable()) {
            var reason = "ASR requires the 'uv' launcher on PATH: "
                    + services.UvProbe.lastResult().reason();
            for (var m : AsrModel.values()) {
                out.put(m.id(), new Status(State.UNAVAILABLE, 0, 0, null, reason));
            }
            return out;
        }
        var ids = new StringBuilder();
        for (var m : AsrModel.values()) {
            if (!ids.isEmpty()) ids.append(',');
            ids.append(m.id());
        }
        try {
            var body = new AsrSidecarClient().asrModels(ids.toString());
            var status = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("status");
            for (var m : AsrModel.values()) {
                var s = status.getAsJsonObject(m.id());
                if (s == null) continue;
                out.put(m.id(), rowFor(m, s));
            }
        } catch (RuntimeException e) {
            for (var m : AsrModel.values()) {
                out.putIfAbsent(m.id(), new Status(State.UNAVAILABLE, 0, 0, null,
                        "ASR sidecar unavailable: " + e.getMessage()));
            }
        }
        return out;
    }

    /** One Settings row from the sidecar's per-model status object, folding
     *  in the in-JVM prefetch/error state (S3776: lifted out of statusAll). */
    private static Status rowFor(AsrModel m, com.google.gson.JsonObject s) {
        boolean cached = s.get("cached").getAsBoolean();
        long onDisk = s.get("bytesOnDisk").getAsLong();
        var engine = s.get("engine").getAsString();
        var inflight = PREFETCHES.get(m.id());
        String error = PREFETCH_ERRORS.get(m.id());
        State state;
        if (inflight != null && !inflight.isDone()) {
            state = State.DOWNLOADING;
        } else if (error != null && !cached) {
            state = State.ERROR;
        } else {
            state = cached ? State.DOWNLOADED : State.NOT_DOWNLOADED;
        }
        return new Status(state, onDisk, (long) m.approxSizeMb() * 1024 * 1024, engine, error);
    }

    /** Kick a background prefetch of the HOST engine's weights; single-flight
     *  per model id, mirroring WhisperModelManager.ensureAvailable. */
    public static void prefetch(AsrModel model) {
        PREFETCH_ERRORS.remove(model.id());
        PREFETCHES.compute(model.id(), (id, existing) -> {
            if (existing != null && !existing.isDone()) return existing;
            return CompletableFuture.runAsync(() -> {
                try {
                    new AsrSidecarClient().asrPrefetch(id);
                    EventLogger.info("transcription",
                            "ASR model %s prefetched for the host engine".formatted(id));
                } catch (RuntimeException e) {
                    PREFETCH_ERRORS.put(id, e.getMessage());
                    Logger.warn("AsrModelStore: prefetch of %s failed: %s", id, e.getMessage());
                    throw e;
                }
            });
        });
    }
}
