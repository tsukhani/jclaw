import channels.SlackStreamingSink;
import channels.SlackStreamingSink.Slacker;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.util.ArrayList;
import java.util.List;

/**
 * JCLAW-341: the streaming sink's post → update → seal dispatch, exercised with
 * an injected {@link Slacker} (no Slack API) and throttle 0 so every update
 * flushes deterministically.
 */
class SlackStreamingSinkTest extends UnitTest {

    static final class FakeSlacker implements Slacker {
        final List<String> posts = new ArrayList<>();
        final List<String> updates = new ArrayList<>();
        String tsToReturn = "1700000000.0001";

        @Override public String post(String channelId, String text, String threadTs) {
            posts.add(text);
            return tsToReturn;
        }

        @Override public boolean update(String channelId, String ts, String text) {
            updates.add(text);
            return true;
        }
    }

    @Test
    void beginPostsPlaceholder() {
        var f = new FakeSlacker();
        var sink = new SlackStreamingSink("C1", null, f, 0L);
        sink.begin();
        assertEquals(1, f.posts.size());
        assertEquals("_…_", f.posts.get(0));
    }

    @Test
    void updateEditsRawAccumulatedText() {
        var f = new FakeSlacker();
        var sink = new SlackStreamingSink("C1", null, f, 0L);
        sink.begin();
        sink.update("Hel");
        sink.update("lo");
        // Throttle 0 → each update flushes; the last carries the raw accumulation.
        assertEquals("Hello", f.updates.get(f.updates.size() - 1));
    }

    @Test
    void sealEditsWithMrkdwnFormattedText() {
        var f = new FakeSlacker();
        var sink = new SlackStreamingSink("C1", null, f, 0L);
        sink.begin();
        sink.seal("**bold** and [x](https://y.io)");
        assertEquals("*bold* and <https://y.io|x>", f.updates.get(f.updates.size() - 1));
    }

    @Test
    void sealPostsFreshWhenPlaceholderFailed() {
        var f = new FakeSlacker();
        f.tsToReturn = null; // placeholder post failed → no ts to edit
        var sink = new SlackStreamingSink("C1", null, f, 0L);
        sink.begin();
        sink.seal("**hi**");
        assertEquals("*hi*", f.posts.get(f.posts.size() - 1));
        assertTrue(f.updates.isEmpty(), "no ts → must not attempt an edit");
    }

    @Test
    void errorFallbackEditsPlaceholderWithNotice() {
        var f = new FakeSlacker();
        var sink = new SlackStreamingSink("C1", null, f, 0L);
        sink.begin();
        sink.errorFallback(new RuntimeException("boom"));
        assertTrue(f.updates.get(f.updates.size() - 1).contains("something went wrong"));
    }
}
