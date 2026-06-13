import channels.SlackChannel;
import channels.SlackChannel.DeliveryOutcome;
import channels.SlackChannel.PostFn;
import channels.SlackChannel.SlackDeliveryCreds;
import channels.SlackWebApi;
import channels.SlackWebApi.ChannelInfo;
import channels.SlackWebApi.ChannelLister;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JCLAW-456 unit tests for {@link SlackChannel#sendForDelivery}'s bot→user fallback:
 * delivery prefers the bot identity and falls back to posting <b>as the user</b> only
 * when the bot can't reach the channel ({@code not_in_channel} / {@code channel_not_found})
 * AND user-token writes are enabled; resolution prefers the user token. The
 * {@code conversations.list} lookup and the post primitive are both swapped
 * ({@code CHANNEL_LISTER} + {@code POST_FN} seams, reflection) so nothing hits the network.
 * Each case uses distinct tokens/names — {@code resolveChannelId} keeps a process-wide id cache.
 */
class SlackDeliveryFallbackTest extends UnitTest {

    private static final Field LISTER_FIELD;
    private static final Field POST_FIELD;
    static {
        try {
            LISTER_FIELD = SlackWebApi.class.getDeclaredField("CHANNEL_LISTER");
            LISTER_FIELD.setAccessible(true);
            POST_FIELD = SlackChannel.class.getDeclaredField("POST_FN");
            POST_FIELD.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    static final class RecordingPost implements PostFn {
        final List<String> tokens = new ArrayList<>();
        final Map<String, DeliveryOutcome> byToken = new HashMap<>();

        @Override
        public DeliveryOutcome post(String token, String channelId, String text) {
            tokens.add(token);
            return byToken.getOrDefault(token, DeliveryOutcome.delivered());
        }
    }

    private Object originalLister;
    private Object originalPost;
    private RecordingPost post;
    private final List<String> resolveTokens = new ArrayList<>();

    @BeforeEach
    void setup() throws Exception {
        originalLister = LISTER_FIELD.get(null);
        originalPost = POST_FIELD.get(null);
        resolveTokens.clear();
        // Resolve any name to C0CHAN, recording the token resolution used.
        ChannelLister lister = (token, name) -> {
            resolveTokens.add(token);
            return new ChannelInfo("C0CHAN", false);
        };
        LISTER_FIELD.set(null, lister);
        post = new RecordingPost();
        POST_FIELD.set(null, post);
    }

    @AfterEach
    void teardown() throws Exception {
        LISTER_FIELD.set(null, originalLister);
        POST_FIELD.set(null, originalPost);
    }

    @Test
    void botDeliversWhenItCanReach() {
        post.byToken.put("xoxb-1", DeliveryOutcome.delivered());
        var out = SlackChannel.sendForDelivery(new SlackDeliveryCreds("xoxb-1", "xoxp-1", true), "ops-bot-ok", "hi");
        assertTrue(out.ok());
        assertEquals(List.of("xoxb-1"), post.tokens, "bot delivered; the user token is never used");
    }

    @Test
    void fallsBackToUserWhenBotNotInChannelAndWritesAllowed() {
        post.byToken.put("xoxb-2", DeliveryOutcome.failed("not_in_channel"));
        post.byToken.put("xoxp-2", DeliveryOutcome.delivered());
        var out = SlackChannel.sendForDelivery(new SlackDeliveryCreds("xoxb-2", "xoxp-2", true), "private-room-fb", "hi");
        assertTrue(out.ok(), "should deliver as the user after the bot bounces");
        assertEquals(List.of("xoxb-2", "xoxp-2"), post.tokens, "tried the bot, then fell back to the user");
    }

    @Test
    void noFallbackWhenUserWritesDisabled() {
        post.byToken.put("xoxb-3", DeliveryOutcome.failed("not_in_channel"));
        var out = SlackChannel.sendForDelivery(new SlackDeliveryCreds("xoxb-3", "xoxp-3", false), "private-room-ro", "hi");
        assertFalse(out.ok());
        assertEquals("not_in_channel", out.error());
        assertEquals(List.of("xoxb-3"), post.tokens, "user-token writes disabled → no fallback");
    }

    @Test
    void noFallbackOnNonReachabilityError() {
        post.byToken.put("xoxb-4", DeliveryOutcome.failed("msg_too_long"));
        var out = SlackChannel.sendForDelivery(new SlackDeliveryCreds("xoxb-4", "xoxp-4", true), "ops-toolong", "hi");
        assertFalse(out.ok());
        assertEquals("msg_too_long", out.error(), "a non-reachability error is not retried as the user");
        assertEquals(List.of("xoxb-4"), post.tokens);
    }

    @Test
    void resolutionPrefersUserToken() {
        post.byToken.put("xoxb-5", DeliveryOutcome.delivered());
        SlackChannel.sendForDelivery(new SlackDeliveryCreds("xoxb-5", "xoxp-5", true), "resolve-pref", "hi");
        assertEquals("xoxp-5", resolveTokens.getFirst(),
                "resolution prefers the user token — it sees private channels the bot isn't in");
    }

    @Test
    void botOnlyCredsResolveAndPostWithBot() {
        post.byToken.put("xoxb-6", DeliveryOutcome.delivered());
        var out = SlackChannel.sendForDelivery(SlackDeliveryCreds.botOnly("xoxb-6"), "ops-botonly", "hi");
        assertTrue(out.ok());
        assertEquals(List.of("xoxb-6"), post.tokens);
        assertEquals("xoxb-6", resolveTokens.getFirst(), "no user token → resolution uses the bot token");
    }
}
