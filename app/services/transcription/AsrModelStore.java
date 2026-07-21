package services.transcription;

import com.google.gson.JsonParser;
import services.EventLogger;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Settings-facing status/provisioning for ASR models (JCLAW-650), backed by
 * the sidecar's host-relevant engine instead of the retired ggml downloads:
 * the artifact the Download button fetches is now the one the host actually
 * runs — an mlx-community snapshot on Apple silicon, Systran CT2 weights on
 * CUDA/CPU hosts. Keeps {@code WhisperModelManager}'s status contract
 * (state/bytes/error) so the Settings frontend is unchanged.
 */
public final class AsrModelStore extends ModelPrefetchStore<AsrModelStore.Status> {

    public record Status(State state, long bytesDownloaded, long totalBytes,
                         String engine, String error) {}

    private static final AsrModelStore INSTANCE = new AsrModelStore();

    private AsrModelStore() {}

    /** Test-only: clear prefetch state so tests don't bleed. */
    public static void resetForTest() {
        INSTANCE.resetPrefetchState();
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
        var ids = Arrays.stream(AsrModel.values()).map(AsrModel::id).collect(Collectors.joining(","));
        try {
            var body = new AsrSidecarClient().asrModels(ids);
            var status = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("status");
            for (var m : AsrModel.values()) {
                var s = status.getAsJsonObject(m.id());
                if (s == null) continue;
                out.put(m.id(), INSTANCE.rowFor(m.id(), s));
            }
        } catch (RuntimeException e) {
            for (var m : AsrModel.values()) {
                out.putIfAbsent(m.id(), new Status(State.UNAVAILABLE, 0, 0, null,
                        "ASR sidecar unavailable: " + e.getMessage()));
            }
        }
        return out;
    }

    /** Folds in the ASR size denominator: {@code approxSizeMb} from the model
     *  the id maps to, as the Settings progress total until the live X-Linked-Size
     *  from HF replaces it once a download begins. */
    @Override
    protected Status buildStatus(String key, State state, long bytesDownloaded,
                                 String engine, String error) {
        long totalBytes = (long) AsrModel.byId(key).orElseThrow().approxSizeMb() * 1024 * 1024;
        return new Status(state, bytesDownloaded, totalBytes, engine, error);
    }

    /** Kick a background prefetch of the HOST engine's weights; single-flight
     *  per model id, mirroring WhisperModelManager.ensureAvailable. */
    public static void prefetch(AsrModel model) {
        INSTANCE.startPrefetch(model.id(), id -> {
            new AsrSidecarClient().asrPrefetch(id);
            EventLogger.info("transcription",
                    "ASR model %s prefetched for the host engine".formatted(id));
        });
    }
}
