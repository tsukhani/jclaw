import agents.ToolRegistry;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.AgentService;
import services.transcription.FfmpegProbe;
import services.tts.VoiceNoteEncoder;
import tools.GenerateAudioTool;

import java.util.List;
import java.util.Map;

/**
 * {@code generate_audio} (JCLAW-876) — argument handling, the schema the model
 * sees, and the encoder's format contract.
 *
 * <p>Synthesis itself is not exercised: it needs a live TTS engine (and, on the
 * sidecar path, a model download), which is the same reason the other media tools
 * stop at their argument boundary. What is worth pinning here is everything that
 * can be wrong without an engine — most importantly that the reported mime type
 * and file extension describe the bytes actually produced, because a voice note
 * announced as OGG while carrying WAV fails at the far end, on the operator's
 * phone, with nothing in our logs.
 */
class GenerateAudioToolTest extends UnitTest {

    private static final GenerateAudioTool TOOL = new GenerateAudioTool();

    private static String run(String argsJson) {
        return TOOL.executeRich(argsJson, null).text();
    }

    @Test
    void rejectsMissingText() {
        assertTrue(run("{}").startsWith("Error:"));
    }

    @Test
    void rejectsBlankText() {
        assertTrue(run("{\"text\":\"   \"}").startsWith("Error:"));
    }

    @Test
    void rejectsUnparseableArguments() {
        assertTrue(run("not json").startsWith("Error:"));
    }

    @Test
    void rejectsTextBeyondTheReadAloudLimit() {
        // Shares tts.maxChars with read-aloud rather than inventing a second cap:
        // the ceiling belongs to the engines, not to the caller.
        var tooLong = "a".repeat(5001);
        var out = run("{\"text\":\"" + tooLong + "\"}");
        assertTrue(out.startsWith("Error:"));
        assertTrue(out.contains("too long"));
    }

    @Test
    void exposesTextAsTheOnlyRequiredParameter() {
        var params = TOOL.parameters();
        assertEquals(List.of("text"), params.get("required"));
        @SuppressWarnings("unchecked")
        var props = (Map<String, Object>) params.get("properties");
        assertTrue(props.containsKey("text"));
        // save_to joined the schema in JCLAW-1057 and is deliberately optional: a caller
        // that needs the audio as a file asks for one, and a caller that does not still
        // gets the inline attachment. The count stays asserted so a third parameter
        // arriving unnoticed still fails here.
        assertTrue(props.containsKey("save_to"));
        assertEquals(2, props.size(), "unexpected parameter: " + props.keySet());
    }

    @Test
    void isNotParallelSafeBecauseTheSidecarSerializes() {
        assertFalse(TOOL.parallelSafe());
    }

    @Test
    void describesItselfWithoutOfferingAVoiceChoice() {
        // Voice comes from the operator's Settings, not from the model. If the
        // description implied otherwise the model would ask the user to pick one.
        var description = TOOL.description().toLowerCase();
        assertTrue(description.contains("settings"));
        assertFalse(TOOL.parameters().toString().contains("voice"));
    }

    @Test
    void encoderFallsBackToWavWhenFfmpegIsMissing() {
        var previous = FfmpegProbe.lastResult();
        try {
            FfmpegProbe.setForTest(new FfmpegProbe.ProbeResult(false, "not installed"));
            var encoded = VoiceNoteEncoder.forChannel(new byte[] {1, 2, 3, 4}, "telegram");

            // The bytes are unchanged AND described as what they are — the pairing is
            // the point, not either half on its own.
            assertEquals("audio/wav", encoded.mimeType());
            assertEquals("wav", encoded.extension());
            assertEquals(4, encoded.bytes().length);
        } finally {
            FfmpegProbe.setForTest(previous);
        }
    }

    @Test
    void encoderLeavesEmptyAudioAloneRatherThanShellingOut() {
        var encoded = VoiceNoteEncoder.forChannel(new byte[0], "web");
        assertEquals("audio/wav", encoded.mimeType());
        assertEquals(0, encoded.bytes().length);
    }

    @Test
    void encoderFallsBackRatherThanThrowingOnUnusableInput() {
        // Real WAV never reaches ffmpeg as four junk bytes, but a failed transcode
        // must degrade to WAV rather than lose the reply. Runs whether or not
        // ffmpeg is present: absent takes the probe path, present fails the decode.
        var encoded = VoiceNoteEncoder.forChannel(new byte[] {9, 9, 9, 9}, "slack");
        assertEquals("audio/wav", encoded.mimeType());
        assertEquals("wav", encoded.extension());
    }

    /**
     * A real 3-second 16&nbsp;kHz mono WAV, so ffmpeg has something it can actually
     * decode — the junk-byte cases above only ever exercise the failure path.
     *
     * <p>Three seconds rather than a token 200&nbsp;ms on purpose: at very short
     * durations MP3's fixed per-frame and header overhead exceeds the audio itself,
     * so a "transcode" legitimately grows the payload and
     * {@link #transcodingActuallyShrinksTheReply()} would be asserting something
     * untrue. Roughly the length of a short spoken reply, which is what the tool
     * actually produces.
     */
    private static byte[] silentWav() {
        int sampleRate = 16000;
        int dataLen = sampleRate * 3 * 2;
        var buf = java.nio.ByteBuffer.allocate(44 + dataLen).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buf.put("RIFF".getBytes()).putInt(36 + dataLen).put("WAVE".getBytes());
        buf.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(sampleRate).putInt(sampleRate * 2).putShort((short) 2).putShort((short) 16);
        buf.put("data".getBytes()).putInt(dataLen);
        return buf.array();
    }

    @Test
    void encodesOpusForChannelsThatRenderVoiceNotes() {
        // Telegram's delivery path routes .ogg to sendVoice — the voice-note bubble.
        assumeFfmpeg();
        for (var channel : List.of("telegram", "whatsapp")) {
            var encoded = VoiceNoteEncoder.forChannel(silentWav(), channel);
            assertEquals("audio/ogg", encoded.mimeType(), channel);
            assertEquals("ogg", encoded.extension(), channel);
        }
    }

    @Test
    void encodesMp3ForSlackBecauseSlackWillNotInlineOgg() {
        // Regression: Opus everywhere made Slack render a "Download Ogg Vorbis" file
        // card instead of a player, which is worse than useless for a spoken reply.
        assumeFfmpeg();
        var encoded = VoiceNoteEncoder.forChannel(silentWav(), "slack");
        assertEquals("audio/mpeg", encoded.mimeType());
        assertEquals("mp3", encoded.extension());
    }

    @Test
    void defaultsToTheBroadlyPlayableFormatWhenTheChannelIsUnknown() {
        // A scheduled task run has no conversation, so there is no voice-note client
        // to assume — take the format that plays everywhere rather than gambling.
        assumeFfmpeg();
        var encoded = VoiceNoteEncoder.forChannel(silentWav(), null);
        assertEquals("audio/mpeg", encoded.mimeType());
        assertEquals("mp3", encoded.extension());
    }

    @Test
    void transcodingActuallyShrinksTheReply() {
        // The other half of why WAV is the wrong wire format: a minute of 48 kHz mono
        // PCM is ~5.8 MB. If a "transcode" ever grew the payload it silently failed.
        assumeFfmpeg();
        var wav = silentWav();
        var encoded = VoiceNoteEncoder.forChannel(wav, "web");
        assertTrue(encoded.bytes().length < wav.length,
                "expected transcode to shrink %d bytes, got %d".formatted(wav.length, encoded.bytes().length));
        // Also check it is a real saving, not a rounding win — the point of leaving
        // WAV behind is an order of magnitude, not a few bytes.
        assertTrue(encoded.bytes().length < wav.length / 2,
                "expected a substantial saving from %d bytes, got %d".formatted(wav.length, encoded.bytes().length));
    }

    /** Skip the codec assertions where ffmpeg is absent (CI images without it) —
     *  the fallback behavior is covered separately and is what runs there. */
    private static void assumeFfmpeg() {
        org.junit.jupiter.api.Assumptions.assumeTrue(FfmpegProbe.probe().available(),
                "ffmpeg not available; codec selection is untestable here");
    }

    @Test
    void isDefaultOffForEveryAgent() {
        // Opt-in like generate_image / generate_video: synthesis is slow enough, and
        // changes the shape of an answer enough, that an agent should not reach for
        // it unless the operator turned it on.
        var agent = AgentService.create("audio-default-off-" + System.nanoTime(),
                "test-provider", "test-model");
        try {
            ToolRegistry.clearDisabledToolsCache();
            var disabled = ToolRegistry.loadDisabledTools(agent);
            assertTrue(disabled.contains("generate_audio"));
            // Sanity-check the assertion means something: the same call does not
            // disable an ordinary always-on tool.
            assertFalse(disabled.contains("datetime"));
        } finally {
            agent.delete();
            ToolRegistry.clearDisabledToolsCache();
        }
    }
}
