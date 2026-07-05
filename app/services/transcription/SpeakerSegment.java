package services.transcription;

/**
 * One diarized speaker span: {@code speaker} is a zero-based cluster index,
 * times in seconds. The shape every diarization consumer speaks —
 * produced by the pyannote sidecar ({@link PyannoteDiarizationClient}) and
 * by the MSDD second opinion, consumed by naming, merging, clip extraction
 * and re-attribution. (Formerly nested in the retired sherpa diarizer,
 * JCLAW-614.)
 */
public record SpeakerSegment(double start, double end, int speaker) {
    public double duration() {
        return end - start;
    }
}
