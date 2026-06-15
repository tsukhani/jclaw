import channels.SlackWebApi;
import channels.SlackWebApi.ChannelInfo;
import channels.SlackWebApi.ChannelLister;
import channels.SlackWebApi.ChannelLookup;
import channels.SlackWebApi.ScopeProber;
import channels.SlackWebApi.SlackReach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JCLAW-454/455/458 unit tests for the shared Slack channel lookup: {@link SlackWebApi#resolveChannel}
 * / {@code resolveChannelId} (literal-id passthrough, {@code #}-strip + lowercasing, bare-name lookup,
 * per-(token,name) caching, not-found, and the JCLAW-458 {@code missing_scope} surfacing),
 * {@link SlackWebApi#probeChannel} reachability classification, and
 * {@link SlackWebApi#deliveryScopeWarning}. The {@code conversations.list} lookup + scope probe are
 * swapped via the package-private {@code CHANNEL_LISTER} / {@code SCOPE_PROBER} seams (reflection),
 * mirroring {@code SlackFileUploaderTest}, so nothing hits the network. Probe/resolve tests use a
 * distinct bot token each, since {@code probeChannel}/resolution keep process-wide caches.
 */
class SlackWebApiResolveTest extends UnitTest {

    private static final Field LISTER_FIELD;
    private static final Field PROBER_FIELD;
    static {
        try {
            LISTER_FIELD = SlackWebApi.class.getDeclaredField("channelLister");
            LISTER_FIELD.setAccessible(true);
            PROBER_FIELD = SlackWebApi.class.getDeclaredField("scopeProber");
            PROBER_FIELD.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    static final class CountingLister implements ChannelLister {
        final AtomicInteger calls = new AtomicInteger();
        String id = "C0RESOLVED"; // matched channel id; null = not found
        boolean member = true;    // membership reported for a matched channel
        String error;             // non-null = conversations.list API error (e.g. missing_scope)
        String lastName;

        @Override
        public ChannelLookup lookup(String botToken, String name) {
            calls.incrementAndGet();
            lastName = name;
            if (error != null) return new ChannelLookup(null, error);
            return new ChannelLookup(id == null ? null : new ChannelInfo(id, member), null);
        }
    }

    private Object originalLister;
    private Object originalProber;
    private CountingLister fake;
    private String scopeProbeError; // what the swapped SCOPE_PROBER returns

    @BeforeEach
    void setup() throws Exception {
        originalLister = LISTER_FIELD.get(null);
        originalProber = PROBER_FIELD.get(null);
        fake = new CountingLister();
        LISTER_FIELD.set(null, fake);
        scopeProbeError = null;
        ScopeProber prober = botToken -> scopeProbeError;
        PROBER_FIELD.set(null, prober);
    }

    @AfterEach
    void teardown() throws Exception {
        LISTER_FIELD.set(null, originalLister);
        PROBER_FIELD.set(null, originalProber);
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

    // ──────── JCLAW-458: missing_scope surfacing + bind-time scope warning ────────

    @Test
    void resolveChannelSurfacesMissingScope() {
        fake.error = "missing_scope";
        var r = SlackWebApi.resolveChannel("xoxb-ms-res", "#daily-briefings");
        assertNull(r.channelId());
        assertEquals("missing_scope", r.error(),
                "a missing_scope list error must surface, not be flattened to channel_not_found");
    }

    @Test
    void resolveChannelNotFoundReportsChannelNotFound() {
        fake.id = null; // found nothing, no API error
        var r = SlackWebApi.resolveChannel("xoxb-nf-res", "#ghost");
        assertNull(r.channelId());
        assertEquals("channel_not_found", r.error());
    }

    @Test
    void probeMissingScopeNamesTheScope() {
        fake.error = "missing_scope";
        var r = SlackWebApi.probeChannel("xoxb-ms-probe", "#daily-briefings");
        assertEquals(SlackReach.MISSING_SCOPE, r.status());
        assertNotNull(r.advisory());
        assertTrue(r.advisory().contains("groups:read"), r.advisory());
        assertTrue(r.needsAttention());
    }

    @Test
    void deliveryScopeWarningWhenListReturnsMissingScope() {
        scopeProbeError = "missing_scope";
        var w = SlackWebApi.deliveryScopeWarning("xoxb-noscope");
        assertNotNull(w, "a missing_scope probe must produce a warning");
        assertTrue(w.contains("groups:read"), w);
    }

    @Test
    void deliveryScopeWarningNullWhenListOk() {
        scopeProbeError = null;
        assertNull(SlackWebApi.deliveryScopeWarning("xoxb-ok"));
    }

    @Test
    void deliveryScopeWarningNullForBlankToken() {
        scopeProbeError = "missing_scope";
        assertNull(SlackWebApi.deliveryScopeWarning(""));
        assertNull(SlackWebApi.deliveryScopeWarning(null));
    }
}
