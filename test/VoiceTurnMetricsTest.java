import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.LatencyMetricRecorder;
import services.voice.VoiceTurnMetrics;
import utils.LatencyStats;

import java.util.function.LongSupplier;

/**
 * VoiceTurnMetrics (JCLAW-800): the voice-specific latency stages are recorded
 * under channel "voice" from the endpoint t0, using an injected clock so the
 * stage math is deterministic without a live WebSocket turn.
 */
class VoiceTurnMetricsTest extends UnitTest {

    @BeforeEach
    void setup() {
        LatencyStats.reset();
        LatencyMetricRecorder.clear(); // don't leak samples into the persisted queue
    }

    /** Advanceable fake clock: holds a nanoTime the test steps forward. */
    private static final class FakeClock implements LongSupplier {
        long ns;
        @Override public long getAsLong() { return ns; }
    }

    private static long sum(String segment) {
        return LatencyStats.snapshot().getAsJsonObject("voice")
                .getAsJsonObject(segment).get("sum_ms").getAsLong();
    }

    private static long count(String segment) {
        return LatencyStats.snapshot().getAsJsonObject("voice")
                .getAsJsonObject(segment).get("count").getAsLong();
    }

    @Test
    void recordsEachStageFromEndpointOnTheVoiceChannel() {
        var clock = new FakeClock();
        var m = new VoiceTurnMetrics("7", 0L, clock);

        clock.ns = 120_000_000L;            // 120 ms after endpoint
        m.sttDone();                        // voice_stt = 120
        m.ttsSynth(45);                     // voice_tts_synth = 45 (explicit value, not clock-derived)
        clock.ns = 300_000_000L;            // 300 ms
        m.firstAudioSent();                 // voice_reply = 300
        clock.ns = 900_000_000L;            // 900 ms
        m.turnComplete();                   // voice_turn = 900

        assertEquals(120L, sum(VoiceTurnMetrics.STT));
        assertEquals(45L, sum(VoiceTurnMetrics.TTS_SYNTH));
        assertEquals(300L, sum(VoiceTurnMetrics.REPLY));
        assertEquals(900L, sum(VoiceTurnMetrics.TURN));
    }

    @Test
    void firstAudioSentIsIdempotent() {
        var clock = new FakeClock();
        var m = new VoiceTurnMetrics("7", 0L, clock);

        clock.ns = 200_000_000L;
        m.firstAudioSent();                 // records 200
        clock.ns = 500_000_000L;
        m.firstAudioSent();                 // no-op — must not add a second sample

        assertEquals(1L, count(VoiceTurnMetrics.REPLY));
        assertEquals(200L, sum(VoiceTurnMetrics.REPLY));
    }

    @Test
    void nullAgentIdStillRecords() {
        var clock = new FakeClock();
        var m = new VoiceTurnMetrics(null, 0L, clock);

        clock.ns = 50_000_000L;
        m.sttDone();

        assertEquals(1L, count(VoiceTurnMetrics.STT));
        assertEquals(50L, sum(VoiceTurnMetrics.STT));
    }
}
