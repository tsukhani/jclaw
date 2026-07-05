package services.transcription;

import services.ConfigService;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The one place that owns the diarization stage sequence (JCLAW-628):
 * cached diarization → enrolled-voice naming → (optionally halt for
 * identification) → transcription → word-split → merge → overlap
 * re-attribution/MSDD/under-speech → emotion. The diarize tool and the
 * REST endpoint are thin callers; before this class they hand-sequenced
 * the same seven stages and had already diverged (the endpoint ignored
 * the cache, transcribed BEFORE diarizing, and had no identification-first
 * mode).
 *
 * <p>Identification-first is a caller option: only the tool enables it
 * (it can converse — stage a lineup, ask the user, enroll); the stateless
 * API always produces a transcript.
 */
public final class DiarizationPipeline {

    /** Caller knobs. {@code identificationFirst} halts before transcription
     *  when voices are unknown (tool-only); {@code allowAnonymous} waives
     *  that halt. */
    public record Options(Integer numSpeakers, String language, boolean identificationFirst,
                          boolean allowAnonymous) {}

    /** Either a finished transcript or a request to identify voices. */
    public sealed interface Outcome permits Transcript, IdentificationNeeded {}

    public record Transcript(List<DiarizedTranscript.Entry> entries,
                             DiarizationRouter.Result diarization) implements Outcome {}

    /** Voices unknown and the caller wants identification-first: no
     *  transcription was paid; the caller stages the lineup. */
    public record IdentificationNeeded(DiarizationRouter.Result diarization,
                                       Map<Integer, String> names,
                                       List<Integer> unmatched) implements Outcome {}

    private DiarizationPipeline() {}

    public static Outcome run(Path audio, Options opts) {
        var diarization = cachedDiarization(audio, opts.numSpeakers());
        var speakers = diarization.segments();
        var names = SpeakerNamer.enrollmentPresent()
                ? SpeakerNamer.nameSpeakers(audio, speakers, (float) ConfigService.getDouble(
                        "transcription.diarization.speakerMatchThreshold", 0.6))
                : Map.<Integer, String>of();

        if (opts.identificationFirst() && !opts.allowAnonymous()) {
            var unmatched = speakers.stream().map(SpeakerSegment::speaker)
                    .distinct().filter(id -> !names.containsKey(id)).toList();
            if (!unmatched.isEmpty()) {
                return new IdentificationNeeded(diarization, names, unmatched);
            }
        }

        var model = WhisperModel.byId(ConfigService.get("transcription.localModel"))
                .orElse(WhisperModel.DEFAULT);
        // JCLAW-629: raw ASR is deterministic given (audio, model, language)
        // — cache it beside the diarization so the post-enrollment second
        // round performs no full-recording inference at all.
        var transcript = DiarizationCache.readTranscript(audio, model.id(), opts.language());
        if (transcript == null) {
            transcript = WhisperJniTranscriber.transcribeSegments(audio, model, opts.language());
            DiarizationCache.writeTranscript(audio, model.id(), opts.language(), transcript);
        }
        // JCLAW-603: word-level split of boundary-straddling segments.
        transcript = SegmentWordSplitter.split(transcript, speakers, audio);
        var entries = DiarizedTranscript.merge(transcript, speakers, names);
        // JCLAW-605/612/613: overlap re-attribution, MSDD second opinion,
        // under-speech recovery. Best-effort inside reattribute().
        if (ConfigService.getBoolean(OverlapReattributor.ENABLED_KEY, true)) {
            entries = OverlapReattributor.reattribute(entries, diarization, audio);
        }
        // JCLAW-563: per-turn acoustic emotion labels. Best-effort.
        if (ConfigService.getBoolean("transcription.emotion.enabled", true)) {
            entries = EmotionRecognizer.annotate(audio, entries);
        }
        return new Transcript(entries, diarization);
    }

    /** Cache-through diarization (JCLAW-611/621): repeated visits to the
     *  same attachment see IDENTICAL segments. */
    public static DiarizationRouter.Result cachedDiarization(Path path, Integer numSpeakers) {
        int n = numSpeakers == null ? -1 : numSpeakers;
        var cached = DiarizationCache.read(path, n);
        if (cached != null) return cached;
        var result = DiarizationRouter.diarizeRich(path, n);
        DiarizationCache.write(path, n, result);
        return result;
    }
}
