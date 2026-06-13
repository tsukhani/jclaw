import channels.SlackWebApi;
import channels.SlackWebApi.ChannelLister;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JCLAW-454 unit tests for {@link SlackWebApi#resolveChannelId}: literal-id passthrough,
 * {@code #}-strip + lowercasing, bare-name lookup, per-(token,name) caching, and
 * not-found. The {@code conversations.list} lookup is swapped for an in-memory
 * {@link ChannelLister} via the package-private {@code CHANNEL_LISTER} seam (reflection),
 * mirroring {@code SlackFileUploaderTest}, so nothing hits the network.
 */
class SlackWebApiResolveTest extends UnitTest {

    private static final Field LISTER_FIELD;
    static {
        try {
            LISTER_FIELD = SlackWebApi.class.getDeclaredField("CHANNEL_LISTER");
            LISTER_FIELD.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    static final class CountingLister implements ChannelLister {
        final AtomicInteger calls = new AtomicInteger();
        String id = "C0RESOLVED"; // returned for any name unless set null
        String lastName;

        @Override
        public String idForName(String botToken, String name) {
            calls.incrementAndGet();
            lastName = name;
            return id;
        }
    }

    private Object originalLister;
    private CountingLister fake;

    @BeforeEach
    void setup() throws Exception {
        originalLister = LISTER_FIELD.get(null);
        fake = new CountingLister();
        LISTER_FIELD.set(null, fake);
    }

    @AfterEach
    void teardown() throws Exception {
        LISTER_FIELD.set(null, originalLister);
    }

    @Test
    void literalChannelIdPassesThroughWithoutLookup() {
        assertEquals("C0ABC123", SlackWebApi.resolveChannelId("xoxb-id", "C0ABC123"));
        assertEquals(0, fake.calls.get(), "a literal channel id must not call conversations.list");
    }

    @Test
    void hashPrefixedNameIsStrippedAndLowercasedThenResolved() {
        assertEquals("C0RESOLVED", SlackWebApi.resolveChannelId("xoxb-hash", "#Daily-Briefings"));
        assertEquals("daily-briefings", fake.lastName,
                "leading # stripped and name lowercased before lookup");
    }

    @Test
    void bareNameIsResolved() {
        assertEquals("C0RESOLVED", SlackWebApi.resolveChannelId("xoxb-bare", "announcements"));
        assertEquals("announcements", fake.lastName);
    }

    @Test
    void resolutionIsCachedPerTokenAndName() {
        SlackWebApi.resolveChannelId("xoxb-cache", "ops");
        SlackWebApi.resolveChannelId("xoxb-cache", "ops");
        assertEquals(1, fake.calls.get(),
                "conversations.list is called once for a cached (token, name)");
    }

    @Test
    void unknownChannelResolvesToNull() {
        fake.id = null; // lister finds nothing
        assertNull(SlackWebApi.resolveChannelId("xoxb-missing", "ghost-channel"));
    }

    @Test
    void blankAndNullInputsResolveToNullWithoutLookup() {
        assertNull(SlackWebApi.resolveChannelId("xoxb", null));
        assertNull(SlackWebApi.resolveChannelId("xoxb", "   "));
        assertNull(SlackWebApi.resolveChannelId(null, "general"));
        assertNull(SlackWebApi.resolveChannelId("", "general"));
        assertEquals(0, fake.calls.get(), "blank/null inputs short-circuit before any lookup");
    }
}
