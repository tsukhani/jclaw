import channels.SlackStreamingSink;
import channels.SlackStreamingSink.Slacker;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.util.ArrayList;
import java.util.List;

/**
 * JCLAW-341: the native-streaming sink's start → append → stop dispatch and its
 * off-thread fallback, exercised with an injected {@link Slacker} (no Slack API)
 * and append throttle 0 so every update flushes deterministically.
 */
class SlackStreamingSinkTest extends UnitTest {

    static final class FakeSlacker implements Slacker {
        final List<String> appended = new ArrayList<>();
        final List<String> fallbackPosts = new ArrayList<>();
        final List<String> statuses = new ArrayList<>();
        boolean stopped = false;
        String startResult = "1700000000.0001";
        String startedThread;
        String startedUser;

        @Override public String startStream(String channelId, String threadTs, String recipientUserId) {
            startedThread = threadTs;
            startedUser = recipientUserId;
            return startResult;
        }

        @Override public boolean appendStream(String channelId, String ts, String markdownDelta) {
            appended.add(markdownDelta);
            return true;
        }

        @Override public boolean stopStream(String channelId, String ts) {
            stopped = true;
            return true;
        }

        @Override public void setStatus(String channelId, String threadTs, String status) {
            statuses.add(status);
        }

        @Override public void postFallback(String channelId, String text, String threadTs) {
            fallbackPosts.add(text);
        }
    }

    @Test
    void nativeStreamsWhenThreadAndUserPresent() {
        var f = new FakeSlacker();
        var sink = new SlackStreamingSink("C1", "1700.0", "U1", f, 0L);
        sink.begin();
        sink.update("Hel");
        sink.update("lo");
        sink.seal("Hello");
        assertEquals("U1", f.startedUser);
        assertEquals("1700.0", f.startedThread);
        // Throttle 0 → each update flushes its delta; markdown is sent raw (Slack renders it).
        assertEquals(List.of("Hel", "lo"), f.appended);
        assertTrue(f.stopped, "stream must be finalized");
        assertTrue(f.fallbackPosts.isEmpty(), "native path must not post a fallback");
        // "is typing…" set at begin, cleared at seal.
        assertEquals(List.of("is typing...", ""), f.statuses);
    }

    @Test
    void noStatusWhenOffThread() {
        var f = new FakeSlacker();
        var sink = new SlackStreamingSink("C1", null, "U1", f, 0L);
        sink.begin();
        sink.seal("hi");
        assertTrue(f.statuses.isEmpty(), "no thread → no assistant status");
    }

    @Test
    void fallbackToSinglePostWhenNoThread() {
        var f = new FakeSlacker();
        var sink = new SlackStreamingSink("C1", null, "U1", f, 0L); // no thread → no native stream
        sink.begin();
        sink.update("Hi");
        sink.seal("**Hi**");
        assertTrue(f.appended.isEmpty(), "no stream → no appends");
        assertFalse(f.stopped);
        // Raw text handed to postFallback (sendMessage mrkdwn-formats it live).
        assertEquals(List.of("**Hi**"), f.fallbackPosts);
    }

    @Test
    void fallbackWhenStartStreamFails() {
        var f = new FakeSlacker();
        f.startResult = null; // startStream failed (e.g. app not an AI Assistant)
        var sink = new SlackStreamingSink("C1", "1700.0", "U1", f, 0L);
        sink.begin();
        sink.update("x");
        sink.seal("done");
        assertTrue(f.appended.isEmpty());
        assertEquals(List.of("done"), f.fallbackPosts);
    }

    @Test
    void errorPostsNoticeWhenNotStreaming() {
        var f = new FakeSlacker();
        var sink = new SlackStreamingSink("C1", null, "U1", f, 0L);
        sink.begin();
        sink.errorFallback(new RuntimeException("boom"));
        assertEquals(1, f.fallbackPosts.size());
        assertTrue(f.fallbackPosts.get(0).contains("something went wrong"));
    }
}
