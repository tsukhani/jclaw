package services.transcription;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import play.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

/**
 * Per-attachment diarization cache (JCLAW-611). The identification-first
 * diarize flow legitimately visits the same attachment twice — once to
 * identify speakers (and stop to ask the user about unknown voices), once
 * after enrollment to produce the transcript. Re-running segmentation would
 * waste sidecar time AND risk boundary jitter between what the user heard
 * in the lineup clips and what the transcript attributes; serving the
 * second call from this cache keeps both runs on identical segments.
 *
 * <p>The cache lives beside the attachment file
 * ({@code <attachment>.diarization.json}) and is keyed by the input that
 * changes segmentation: the requested speaker count. A mismatch (or any
 * parse problem) reads as a miss.
 */
public final class DiarizationCache {

    private DiarizationCache() {}

    public static Path cacheFile(Path audioFile) {
        return audioFile.resolveSibling(audioFile.getFileName() + ".diarization.json");
    }

    /** Cached result for identical inputs, or null (miss / stale / corrupt). */
    public static DiarizationRouter.Result read(Path audioFile, int numSpeakers) {
        var file = cacheFile(audioFile);
        if (!Files.isRegularFile(file)) return null;
        try {
            var root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (root.get("numSpeakers").getAsInt() != numSpeakers) {
                return null;
            }
            var segments = new ArrayList<SpeakerSegment>();
            for (var el : root.getAsJsonArray("segments")) {
                var o = el.getAsJsonObject();
                segments.add(new SpeakerSegment(
                        o.get("start").getAsDouble(), o.get("end").getAsDouble(),
                        o.get("speaker").getAsInt()));
            }
            var overlaps = new ArrayList<double[]>();
            for (var el : root.getAsJsonArray("overlaps")) {
                var a = el.getAsJsonArray();
                overlaps.add(new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble()});
            }
            Logger.info("DiarizationCache: reusing cached diarization for %s (%d segments)",
                    audioFile.getFileName(), segments.size());
            return new DiarizationRouter.Result(segments, overlaps);
        } catch (IOException | RuntimeException e) {
            Logger.warn("DiarizationCache: unreadable cache for %s (%s) — recomputing",
                    audioFile.getFileName(), e.getMessage());
            return null;
        }
    }

    /** Best-effort write; a failed write only costs a future recompute. */
    public static void write(Path audioFile, int numSpeakers,
                             DiarizationRouter.Result result) {
        var root = new JsonObject();
        root.addProperty("numSpeakers", numSpeakers);
        var segments = new JsonArray();
        for (var s : result.segments()) {
            var o = new JsonObject();
            o.addProperty("start", s.start());
            o.addProperty("end", s.end());
            o.addProperty("speaker", s.speaker());
            segments.add(o);
        }
        root.add("segments", segments);
        var overlaps = new JsonArray();
        for (var region : result.overlaps()) {
            var a = new JsonArray();
            a.add(region[0]);
            a.add(region[1]);
            overlaps.add(a);
        }
        root.add("overlaps", overlaps);
        try {
            Files.writeString(cacheFile(audioFile), root.toString());
        } catch (IOException e) {
            Logger.warn("DiarizationCache: could not write cache for %s (%s)",
                    audioFile.getFileName(), e.getMessage());
        }
    }
}
