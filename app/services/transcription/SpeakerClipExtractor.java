package services.transcription;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Cuts one short reference clip per diarized speaker out of a recording
 * (JCLAW-562) — the raw material for the "who is voice N?" enrollment flow:
 * the operator uploads any multi-speaker recording, listens to the lineup,
 * and names the voices, instead of having to produce a clean per-person
 * sample.
 *
 * <p>Pure sample-array functions — no I/O, no natives — so clip selection
 * and the WAV encoding are unit-testable. All audio is the pipeline-wide
 * shape: PCM float mono at 16 kHz.
 */
public final class SpeakerClipExtractor {

    public static final int SAMPLE_RATE = 16_000;

    /**
     * One extracted reference clip.
     *
     * @param label   stable handle for the enrollment follow-up ({@code voice-1}, …)
     * @param speaker diarized speaker index the clip belongs to
     * @param start   where the clip begins in the source recording, seconds
     * @param samples the clip's PCM float samples
     */
    public record Clip(String label, int speaker, double start, float[] samples) {
        public double duration() {
            return samples.length / (double) SAMPLE_RATE;
        }
    }

    private SpeakerClipExtractor() {}

    /**
     * One clip per distinct speaker: up to {@code targetSeconds} cut from the
     * <b>middle</b> of that speaker's longest diarized segment (mid-speech
     * avoids the turn-boundary bleed both diarization pipelines exhibit).
     * Speakers whose longest segment is shorter than {@code minSeconds} are
     * skipped — sub-second snippets make unreliable voice references.
     * Labels number the clips {@code voice-1..N} in speaker-index order.
     */
    /** As {@link #extract(float[], List, double, double)}, decoding the audio
     *  file first (same ffmpeg path as the rest of the pipeline). */
    public static List<Clip> extract(Path audioFile, List<SherpaDiarizer.SpeakerSegment> segments,
                                     double targetSeconds, double minSeconds) {
        return extract(WhisperJniTranscriber.ffmpegToPcmF32(audioFile), segments, targetSeconds, minSeconds);
    }

    public static List<Clip> extract(float[] samples, List<SherpaDiarizer.SpeakerSegment> segments,
                                     double targetSeconds, double minSeconds) {
        Map<Integer, SherpaDiarizer.SpeakerSegment> longest = new TreeMap<>();
        for (var s : segments) {
            var current = longest.get(s.speaker());
            if (current == null || s.end() - s.start() > current.end() - current.start()) {
                longest.put(s.speaker(), s);
            }
        }

        var clips = new ArrayList<Clip>();
        int n = 1;
        for (var e : longest.entrySet()) {
            var seg = e.getValue();
            double segLength = seg.end() - seg.start();
            if (segLength < minSeconds) continue;
            double duration = Math.min(segLength, targetSeconds);
            double start = seg.start() + (segLength - duration) / 2;
            int durSamples = (int) Math.round(duration * SAMPLE_RATE);
            // Slide the window back rather than dropping the speaker when the
            // diarized times overrun the decoded audio (clock drift, container
            // duration lies) — some clip beats no clip.
            int from = Math.clamp(Math.round(start * SAMPLE_RATE), 0,
                    Math.max(0, samples.length - durSamples));
            int to = Math.min(from + durSamples, samples.length);
            if (to <= from) continue;
            clips.add(new Clip("voice-" + n++, e.getKey(), start,
                    Arrays.copyOfRange(samples, from, to)));
        }
        return clips;
    }

    /** Encode PCM float mono 16 kHz samples as a 16-bit WAV file. */
    public static byte[] toWavPcm16(float[] samples) {
        int dataSize = samples.length * 2;
        var buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
        buf.put("RIFF".getBytes())
                .putInt(36 + dataSize)
                .put("WAVE".getBytes())
                .put("fmt ".getBytes())
                .putInt(16)                       // fmt chunk size
                .putShort((short) 1)              // PCM
                .putShort((short) 1)              // mono
                .putInt(SAMPLE_RATE)
                .putInt(SAMPLE_RATE * 2)          // byte rate
                .putShort((short) 2)              // block align
                .putShort((short) 16)             // bits per sample
                .put("data".getBytes())
                .putInt(dataSize);
        for (float f : samples) {
            buf.putShort((short) Math.round(Math.clamp(f, -1.0f, 1.0f) * Short.MAX_VALUE));
        }
        return buf.array();
    }
}
