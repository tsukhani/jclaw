import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.tts.TtsEngine;
import services.tts.TtsRouter;
import services.voice.VoiceTurnSpeaker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;

/**
 * Characterization tests for the speaking half of a voice turn (JCLAW-869).
 *
 * <p>These pin behavior that was arrived at by fixing real bugs, so that the
 * decomposition out of {@code VoiceController.runTurn} — and anything later —
 * has something to fail against. Each test names the failure it guards.
 *
 * <p>Written against {@link VoiceTurnSpeaker} rather than {@code runTurn} itself
 * because runTurn also drives STT and the agent, both reached through statics,
 * and the build excludes Mockito. Extracting the speaker is what made these
 * behaviors reachable at all; all four of the behaviors worth pinning live here.
 */
class VoiceTurnSpeakerTest extends UnitTest {

    /** Collects frames instead of writing them to a socket. */
    private static final class RecordingSink implements VoiceTurnSpeaker.Sink {
        final List<Map<String, Object>> frames = new ArrayList<>();
        boolean open = true;

        @Override public boolean isOpen() {
            return open;
        }

        @Override public void send(Map<String, Object> frame) {
            frames.add(frame);
        }

        List<String> types() {
            return frames.stream().map(f -> String.valueOf(f.get("type"))).toList();
        }

        List<String> texts() {
            return frames.stream().filter(f -> "reply".equals(f.get("type")))
                    .map(f -> String.valueOf(f.get("text"))).toList();
        }
    }

    private static TtsRouter.Spoken audio() {
        return new TtsRouter.Spoken(new byte[] {1, 2, 3}, TtsEngine.SIDECAR, false);
    }

    private static LinkedBlockingQueue<String> queueOf(String... sentences) {
        var q = new LinkedBlockingQueue<String>();
        for (var s : sentences) q.offer(s);
        q.offer(VoiceTurnSpeaker.END_OF_TURN);
        return q;
    }

    private static final LongConsumer IGNORE_FIRST_AUDIO = _ -> { };

    @Test
    void emitsReplyTextBeforeTheAudioItDescribes() {
        // JCLAW-860: audio used to be synthesized first and the text emitted only on
        // success, so a TTS failure silently discarded a reply the model had already
        // produced. Text must lead, per sentence.
        var sink = new RecordingSink();
        var speaker = new VoiceTurnSpeaker(sink, 7, new AtomicBoolean(), _ -> audio());

        boolean ended = speaker.speakAll(queueOf("Hello there."), IGNORE_FIRST_AUDIO);

        assertTrue(ended);
        assertEquals(List.of("reply", "audio"), sink.types());
    }

    @Test
    void keepsStreamingTextWhenSynthesisFails() {
        // A dead TTS engine must degrade the turn to transcript-only, not end it and
        // not raise — the caller's handler turns a throw into an `error` frame, which
        // the client treats as fatal and tears the whole session down.
        var sink = new RecordingSink();
        var speaker = new VoiceTurnSpeaker(sink, 7, new AtomicBoolean(), _ -> {
            throw new RuntimeException("engine down");
        });

        boolean ended = speaker.speakAll(queueOf("First.", "Second."), IGNORE_FIRST_AUDIO);

        assertTrue(ended); // the turn still finished, so turn_complete is still owed
        assertEquals(List.of("reply", "reply"), sink.types());
        assertEquals(0, speaker.chunksSent());
        assertTrue(speaker.degraded());
    }

    @Test
    void latchesDegradationSoADeadEngineIsReportedOncePerTurn() {
        // Every utterance after the first fails identically; logging per sentence
        // buries the one line that matters. The latch is observable via degraded().
        var attempts = new AtomicInteger();
        var speaker = new VoiceTurnSpeaker(new RecordingSink(), 7, new AtomicBoolean(), _ -> {
            attempts.incrementAndGet();
            throw new RuntimeException("engine down");
        });

        speaker.speakAll(queueOf("One.", "Two.", "Three."), IGNORE_FIRST_AUDIO);

        assertEquals(3, attempts.get()); // still tried each one — only the log latches
        assertTrue(speaker.degraded());
    }

    @Test
    void reportsNotCompletedWhenCancelledSoNoTurnCompleteFollows() {
        // Barge-in: the operator spoke over the reply. A canceled turn must never
        // emit turn_complete, which would hand the floor back as if it had finished.
        var cancel = new AtomicBoolean(true);
        var sink = new RecordingSink();
        var speaker = new VoiceTurnSpeaker(sink, 7, cancel, _ -> audio());

        boolean ended = speaker.speakAll(queueOf("Hello."), IGNORE_FIRST_AUDIO);

        assertFalse(ended);
        assertTrue(sink.frames.isEmpty());
    }

    @Test
    void stopsMidStreamWhenCancelledPartWay() {
        var cancel = new AtomicBoolean();
        var sink = new RecordingSink();
        // Cancel as soon as the first utterance has been spoken.
        var speaker = new VoiceTurnSpeaker(sink, 7, cancel, _ -> {
            cancel.set(true);
            return audio();
        });

        boolean ended = speaker.speakAll(queueOf("First.", "Second."), IGNORE_FIRST_AUDIO);

        assertFalse(ended);
        // Text for the first sentence went out, but its audio did not: cancellation is
        // re-checked after synthesis precisely so stale audio never reaches the client.
        assertEquals(List.of("reply"), sink.types());
    }

    @Test
    void reportsNotCompletedWhenTheSocketClosed() {
        var sink = new RecordingSink();
        sink.open = false;
        var speaker = new VoiceTurnSpeaker(sink, 7, new AtomicBoolean(), _ -> audio());

        assertFalse(speaker.speakAll(queueOf("Hello."), IGNORE_FIRST_AUDIO));
        assertTrue(sink.frames.isEmpty());
    }

    @Test
    void growsTheReplyTextAcrossSentencesRatherThanReplacingIt() {
        // The client renders the reply frame as the whole reply so far, so each frame
        // has to carry the accumulated text, space-joined.
        var sink = new RecordingSink();
        var speaker = new VoiceTurnSpeaker(sink, 7, new AtomicBoolean(), _ -> audio());

        speaker.speakAll(queueOf("First.", "Second."), IGNORE_FIRST_AUDIO);

        assertEquals(List.of("First.", "First. Second."), sink.texts());
    }

    @Test
    void skipsChunksWithNothingSpeakableWithoutEndingTheTurn() {
        // Emoji-only chunks strip to nothing; they must not produce an empty reply
        // frame, burn a chunk index, or stop the turn.
        var sink = new RecordingSink();
        var speaker = new VoiceTurnSpeaker(sink, 7, new AtomicBoolean(), _ -> audio());

        boolean ended = speaker.speakAll(queueOf("   ", "Real sentence."), IGNORE_FIRST_AUDIO);

        assertTrue(ended);
        assertEquals(List.of("reply", "audio"), sink.types());
        assertEquals(1, speaker.chunksSent());
    }

    @Test
    void indexesAudioChunksFromZeroAndFiresFirstAudioOnce() {
        var sink = new RecordingSink();
        var firstAudioCalls = new AtomicInteger();
        var speaker = new VoiceTurnSpeaker(sink, 7, new AtomicBoolean(), _ -> audio());

        speaker.speakAll(queueOf("One.", "Two.", "Three."), _ -> firstAudioCalls.incrementAndGet());

        var indexes = sink.frames.stream().filter(f -> "audio".equals(f.get("type")))
                .map(f -> f.get("index")).toList();
        assertEquals(List.of(0, 1, 2), indexes);
        assertEquals(1, firstAudioCalls.get());
        assertEquals(3, speaker.chunksSent());
    }

    @Test
    void marksAudioWithTheEngineThatActuallySpoke() {
        // JCLAW-861: a fallback engine's audio must be identifiable on the wire rather
        // than passed off as the operator's chosen engine.
        var sink = new RecordingSink();
        var speaker = new VoiceTurnSpeaker(sink, 7, new AtomicBoolean(),
                _ -> new TtsRouter.Spoken(new byte[] {9}, TtsEngine.JVM, true));

        speaker.speakAll(queueOf("Hello."), IGNORE_FIRST_AUDIO);

        var frame = sink.frames.stream().filter(f -> "audio".equals(f.get("type"))).findFirst().orElseThrow();
        assertEquals(TtsEngine.JVM.id(), frame.get("engine"));
        assertEquals(true, frame.get("degraded"));
    }

    @Test
    void treatsAnEmptyStreamAsACompletedTurn() {
        // The model produced nothing speakable; the turn still ended properly and the
        // floor goes back to the mic.
        var sink = new RecordingSink();
        var speaker = new VoiceTurnSpeaker(sink, 7, new AtomicBoolean(), _ -> audio());

        assertTrue(speaker.speakAll(queueOf(), IGNORE_FIRST_AUDIO));
        assertTrue(sink.frames.isEmpty());
    }
}
