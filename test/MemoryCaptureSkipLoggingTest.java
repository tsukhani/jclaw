import memory.MemoryAutoCapture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.EventLogger;

import java.util.List;

/**
 * JCLAW-928: a capture that cannot resolve its context must say so. Exercises
 * {@code resolveExtractContext} directly — {@code captureAsync} returns early in test
 * mode, so the branch is unreachable through the async entry point.
 */
class MemoryCaptureSkipLoggingTest extends UnitTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        EventLogger.clear();
    }

    private models.Agent agent() {
        var a = new models.Agent();
        a.name = "skip-log-agent";
        a.modelProvider = "openrouter";
        a.modelId = "gpt-4.1";
        a.save();
        return a;
    }

    private static List<EventLogger.Captured> skipEvents(List<EventLogger.Captured> logged) {
        return logged.stream()
                .filter(e -> e.category().equals("memory") && e.message().startsWith("Auto-capture skipped:"))
                .toList();
    }

    @Test
    void missingConversationIsLoggedNotSilentlySwallowed() {
        var a = agent();

        var ctx = new Object[1];
        var logged = EventLogger.captureForTest(
                () -> ctx[0] = MemoryAutoCapture.resolveExtractContext(a, 999_999_999L, a.name));

        assertNull(ctx[0], "a capture with no conversation must not proceed");
        var skips = skipEvents(logged);
        assertEquals(1, skips.size(), "the reason must reach the event log — silence is the defect");
        var ev = skips.getLast();
        assertTrue(ev.message().contains("999999999"),
                "the message must name the conversation so the race is diagnosable, got: " + ev.message());
    }

    @Test
    void voiceChannelSkipStaysSilent() {
        var a = agent();
        var conv = new models.Conversation();
        conv.agent = a;
        conv.channelType = models.ChannelType.VOICE.value;
        conv.save();

        var ctx = new Object[1];
        var logged = EventLogger.captureForTest(
                () -> ctx[0] = MemoryAutoCapture.resolveExtractContext(a, conv.id, a.name));

        assertNull(ctx[0], "voice turns are deliberately not auto-captured (JCLAW-866)");
        assertEquals(0, skipEvents(logged).size(),
                "an intentional per-turn skip must not log, or voice sessions flood the event log");
    }
}
