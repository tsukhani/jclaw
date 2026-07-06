package services.transcription;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import play.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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

    /** Bump when segment-shaping logic changes (exclusive-mode handling,
     *  overlap extraction, ...) so previously cached results read as
     *  misses (JCLAW-621). Model changes are keyed separately below. */
    /** v4: transcript segments carry word timestamps (JCLAW-651 round 2). */
    static final int PIPELINE_VERSION = 4;

    private DiarizationCache() {}

    private static String configuredModel() {
        return services.ConfigService.get(
                PyannoteSidecarManager.CONFIG_PREFIX + ".model",
                PyannoteSidecarManager.DEFAULT_MODEL);
    }

    private static boolean fingerprintMatches(com.google.gson.JsonObject root) {
        return root.has("model") && configuredModel().equals(root.get("model").getAsString())
                && root.has("pipelineVersion")
                && root.get("pipelineVersion").getAsInt() == PIPELINE_VERSION;
    }

    public static Path cacheFile(Path audioFile) {
        return audioFile.resolveSibling(audioFile.getFileName() + ".diarization.json");
    }

    /** Cached result for identical inputs, or null (miss / stale / corrupt). */
    public static DiarizationRouter.Result read(Path audioFile, int numSpeakers) {
        var file = cacheFile(audioFile);
        if (!Files.isRegularFile(file)) return null;
        try {
            var root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            // JCLAW-621: entries persist beside the attachment forever — a
            // model upgrade or pipeline change must read as a miss, never
            // silently serve stale segments. Pre-fingerprint files miss too.
            if (!fingerprintMatches(root) || root.get("numSpeakers").getAsInt() != numSpeakers) {
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
            var rawSegments = new ArrayList<SpeakerSegment>();
            if (root.has("rawSegments")) {
                for (var el : root.getAsJsonArray("rawSegments")) {
                    var o = el.getAsJsonObject();
                    rawSegments.add(new SpeakerSegment(o.get("start").getAsDouble(),
                            o.get("end").getAsDouble(), o.get("speaker").getAsInt()));
                }
            }
            return new DiarizationRouter.Result(segments, rawSegments, overlaps);
        } catch (IOException | RuntimeException e) {
            Logger.warn("DiarizationCache: unreadable cache for %s (%s) — recomputing",
                    audioFile.getFileName(), e.getMessage());
            return null;
        }
    }

    /**
     * Cached MSDD second-opinion segments (JCLAW-624), or null. The
     * identification-first flow promises the post-enrollment diarize is
     * fast; without this, the full NeMo pass re-ran on the second call.
     * Same fingerprint discipline as the diarization section.
     */
    public static List<SpeakerSegment> readMsdd(Path audioFile, int numSpeakers) {
        var file = cacheFile(audioFile);
        if (!Files.isRegularFile(file)) return null;
        try {
            var root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (!fingerprintMatches(root) || !root.has("msdd")) return null;
            var msdd = root.getAsJsonObject("msdd");
            if (msdd.get("numSpeakers").getAsInt() != numSpeakers) return null;
            var segments = new ArrayList<SpeakerSegment>();
            for (var el : msdd.getAsJsonArray("segments")) {
                var o = el.getAsJsonObject();
                segments.add(new SpeakerSegment(o.get("start").getAsDouble(),
                        o.get("end").getAsDouble(), o.get("speaker").getAsInt()));
            }
            Logger.info("DiarizationCache: reusing cached MSDD opinion for %s (%d segments)",
                    audioFile.getFileName(), segments.size());
            return segments;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Merge the MSDD section into the existing cache file, best-effort. */
    public static void writeMsdd(Path audioFile, int numSpeakers, List<SpeakerSegment> segments) {
        var file = cacheFile(audioFile);
        try {
            JsonObject root;
            if (Files.isRegularFile(file)) {
                root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                if (!fingerprintMatches(root)) return; // stale main section: don't graft onto it
            } else {
                return; // MSDD without a diarization section has no anchor
            }
            var arr = new JsonArray();
            for (var s : segments) {
                var o = new JsonObject();
                o.addProperty("start", s.start());
                o.addProperty("end", s.end());
                o.addProperty("speaker", s.speaker());
                arr.add(o);
            }
            var msdd = new JsonObject();
            msdd.addProperty("numSpeakers", numSpeakers);
            msdd.add("segments", arr);
            root.add("msdd", msdd);
            Files.writeString(file, root.toString());
        } catch (IOException | RuntimeException e) {
            Logger.warn("DiarizationCache: could not write MSDD section for %s (%s)",
                    audioFile.getFileName(), e.getMessage());
        }
    }

    /**
     * Cached whisper transcript (JCLAW-629), or null. Deterministic given
     * (audio, model, language) — the post-enrollment second diarize round
     * previously re-ran ASR over the whole recording, its dominant
     * remaining cost. Same fingerprint discipline as the other sections.
     */
    public static List<WhisperJniTranscriber.Segment> readTranscript(
            Path audioFile, String model, String language) {
        var file = cacheFile(audioFile);
        if (!Files.isRegularFile(file)) return null;
        try {
            var root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (!fingerprintMatches(root) || !root.has("transcript")) return null;
            var t = root.getAsJsonObject("transcript");
            if (!t.get("model").getAsString().equals(model)
                    || !t.get("language").getAsString().equals(languageKey(language))) {
                return null;
            }
            var segments = new ArrayList<WhisperJniTranscriber.Segment>();
            for (var el : t.getAsJsonArray("segments")) {
                var o = el.getAsJsonObject();
                var words = new ArrayList<WhisperJniTranscriber.Word>();
                if (o.has("words")) {
                    for (var wl : o.getAsJsonArray("words")) {
                        var w = wl.getAsJsonObject();
                        words.add(new WhisperJniTranscriber.Word(
                                w.get("startMs").getAsLong(), w.get("endMs").getAsLong(),
                                w.get("text").getAsString()));
                    }
                }
                segments.add(new WhisperJniTranscriber.Segment(
                        o.get("startMs").getAsLong(), o.get("endMs").getAsLong(),
                        o.get("text").getAsString(),
                        o.has("noSpeechProb") ? o.get("noSpeechProb").getAsDouble() : 0.0,
                        o.has("avgLogprob") ? o.get("avgLogprob").getAsDouble() : 0.0,
                        o.has("compressionRatio") ? o.get("compressionRatio").getAsDouble() : 1.0,
                        words));
            }
            Logger.info("DiarizationCache: reusing cached transcript for %s (%d segments)",
                    audioFile.getFileName(), segments.size());
            return segments;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Merge the transcript section into the existing cache file. */
    public static void writeTranscript(Path audioFile, String model, String language,
                                       List<WhisperJniTranscriber.Segment> segments) {
        var file = cacheFile(audioFile);
        try {
            if (!Files.isRegularFile(file)) return; // no diarization anchor
            var root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (!fingerprintMatches(root)) return;
            var arr = new JsonArray();
            for (var seg : segments) {
                var o = new JsonObject();
                o.addProperty("startMs", seg.startMs());
                o.addProperty("endMs", seg.endMs());
                o.addProperty("text", seg.text());
                o.addProperty("noSpeechProb", seg.noSpeechProb());
                o.addProperty("avgLogprob", seg.avgLogprob());
                o.addProperty("compressionRatio", seg.compressionRatio());
                var wordsArr = new JsonArray();
                for (var w : seg.words()) {
                    var wo = new JsonObject();
                    wo.addProperty("startMs", w.startMs());
                    wo.addProperty("endMs", w.endMs());
                    wo.addProperty("text", w.text());
                    wordsArr.add(wo);
                }
                o.add("words", wordsArr);
                arr.add(o);
            }
            var t = new JsonObject();
            t.addProperty("model", model);
            t.addProperty("language", languageKey(language));
            t.add("segments", arr);
            root.add("transcript", t);
            Files.writeString(file, root.toString());
        } catch (IOException | RuntimeException e) {
            Logger.warn("DiarizationCache: could not write transcript section for %s (%s)",
                    audioFile.getFileName(), e.getMessage());
        }
    }

    private static String languageKey(String language) {
        return language == null || language.isBlank() ? "auto" : language;
    }

    /** Best-effort write; a failed write only costs a future recompute. */
    public static void write(Path audioFile, int numSpeakers,
                             DiarizationRouter.Result result) {
        var root = new JsonObject();
        root.addProperty("model", configuredModel());
        root.addProperty("pipelineVersion", PIPELINE_VERSION);
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
        var rawSegments = new JsonArray();
        for (var s : result.rawSegments()) {
            var o = new JsonObject();
            o.addProperty("start", s.start());
            o.addProperty("end", s.end());
            o.addProperty("speaker", s.speaker());
            rawSegments.add(o);
        }
        root.add("rawSegments", rawSegments);
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
