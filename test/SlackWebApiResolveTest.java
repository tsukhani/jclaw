import channels.SlackWebApi;
import channels.SlackWebApi.ChannelInfo;
import channels.SlackWebApi.ChannelLister;
import channels.SlackWebApi.SlackReach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JCLAW-454/455 unit tests for the shared Slack channel lookup: {@link SlackWebApi#resolveChannelId}
 * (literal-id passthrough, {@code #}-strip + lowercasing, bare-name lookup, per-(token,name) caching,
 * not-found) and {@link SlackWebApi#probeChannel} reachability classification. The
 * {@code conversations.list} lookup is swapped for an in-memory {@link ChannelLister} via the
 * package-private {@code CHANNEL_LISTER} seam (reflection), mirroring {@code SlackFileUploaderTest},
 * so nothing hits the network. Probe tests use a distinct bot token each, since {@code probeChannel}
 * keeps a process-wide 60 s verdict cache.
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
        String id = "C0RESOLVED"; // returned for any name unless set null (= not found)
        boolean member = true;    // membership reported for a matched channel
        String lastName;

        @Override
        public ChannelInfo lookup(String botToken, String name) {
            calls.incrementAndGet();
            lastName = name;
            return id == null ? null : new ChannelInfo(id, member);
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

    // ──────── JCLAW-455: reachability probe ────────

    @Test
    void probeReachableWhenBotIsMember() {
        fake.id = "C0OPS";
        fake.member = true;
        var r = SlackWebApi.probeChannel("xoxb-probe-reach", "#ops");
        assertEquals(SlackReach.REACHABLE, r.status());
        assertNull(r.advisory(), "a reachable channel needs no advisory");
        assertFalse(r.needsAttention());
    }

    @Test
    void probePublicNotMemberWhenFoundButNotMember() {
        fake.id = "C0ANN";
        fake.member = false;
        var r = SlackWebApi.probeChannel("xoxb-probe-pubnm", "announcements");
        assertEquals(SlackReach.PUBLIC_NOT_MEMBER, r.status());
        assertNotNull(r.advisory());
        assertTrue(r.advisory().contains("chat:write.public"), r.advisory());
        assertTrue(r.needsAttention());
    }

    @Test
    void probeUnresolvedWhenNotFoundAdvisesBothCauses() {
        fake.id = null; // not found in conversations.list
        var r = SlackWebApi.probeChannel("xoxb-probe-unres", "#daily-briefings");
        assertEquals(SlackReach.UNRESOLVED, r.status());
        assertNotNull(r.advisory());
        assertTrue(r.advisory().contains("private") && r.advisory().contains("public"),
                "an unresolved channel can't be classified, so the advisory names both causes: " + r.advisory());
        assertTrue(r.advisory().contains("#daily-briefings"), r.advisory());
    }

    @Test
    void probeLiteralIdIsUnknownWithoutLookup() {
        var r = SlackWebApi.probeChannel("xoxb-probe-id", "C0LITERAL1");
        assertEquals(SlackReach.UNKNOWN, r.status());
        assertNull(r.advisory());
        assertEquals(0, fake.calls.get(), "a literal channel id is not probed via conversations.list");
    }

    @Test
    void probeIsCachedPerTokenAndName() {
        fake.id = "C0CACHE";
        fake.member = false;
        SlackWebApi.probeChannel("xoxb-probe-cache", "ops");
        SlackWebApi.probeChannel("xoxb-probe-cache", "ops");
        assertEquals(1, fake.calls.get(), "the probe verdict is cached per (token, name) within the TTL");
    }
}
