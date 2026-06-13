package channels;

import com.slack.api.Slack;
import com.slack.api.methods.SlackApiException;
import com.slack.api.model.ConversationType;
import com.slack.api.model.block.LayoutBlock;
import services.EventLogger;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Thin Slack Web API helper shared across the Slack epic (JCLAW-441 foundation).
 * Uses the same shared {@link Slack} instance as {@link SlackChannel}; each call
 * is per-token via {@code methods(token)}. The action-tool and resolution stories
 * (JCLAW-347/355) extend this with reactions/pins/users.info etc.
 */
public final class SlackWebApi {

    /** Shared SDK entry point; {@code methods(token)} yields a per-token client. */
    private static final Slack slack = Slack.getInstance();

    private SlackWebApi() {}

    // ── JCLAW-454: channel name → id resolution for task-output delivery ──

    /** A literal Slack channel/group/DM id ({@code C}/{@code G}/{@code D}…) — passes through resolution unchanged. */
    private static final Pattern CHANNEL_ID = Pattern.compile("^[CGD][A-Z0-9]{6,}$");
    private static final int CHANNEL_CACHE_MAX = 2048;
    /** name→id cache keyed by (token-hash, lowercased name); bounded LRU. Mirrors {@link SlackFileUploader}'s DM cache. */
    private static final Map<String, String> CHANNEL_ID_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, String> e) {
                    return size() > CHANNEL_CACHE_MAX;
                }
            });

    /** Test seam (JCLAW-454): the {@code conversations.list} name→id lookup, swappable so
     *  unit tests resolve channels without the network — mirrors {@link SlackFileUploader}'s
     *  injectable {@code Uploader}. {@code name} is already lowercased with any leading
     *  {@code #} stripped; returns the channel id or {@code null} if none matches. */
    @FunctionalInterface
    public interface ChannelLister {
        String idForName(String botToken, String name);
    }

    static ChannelLister CHANNEL_LISTER = SlackWebApi::lookupChannelIdByNameLive;

    /**
     * JCLAW-454: resolve a Slack delivery {@code target} to a channel id. A literal id
     * ({@code C}/{@code G}/{@code D}…) passes through unchanged; a {@code #name} or bare
     * {@code name} is looked up via {@code conversations.list} (public + private channels
     * the bot can see) and cached per (token, name). Returns {@code null} when no channel
     * matches — the caller surfaces an invite/typo remedy. Never throws.
     */
    public static String resolveChannelId(String botToken, String target) {
        if (botToken == null || botToken.isBlank() || target == null) return null;
        String t = target.trim();
        if (t.isEmpty()) return null;
        if (CHANNEL_ID.matcher(t).matches()) return t;
        String name = (t.startsWith("#") ? t.substring(1) : t).toLowerCase(Locale.ROOT);
        if (name.isEmpty()) return null;
        String key = Integer.toHexString(botToken.hashCode()) + ":" + name;
        var cached = CHANNEL_ID_CACHE.get(key);
        if (cached != null) return cached;
        String id = CHANNEL_LISTER.idForName(botToken, name);
        if (id != null) CHANNEL_ID_CACHE.put(key, id);
        return id;
    }

    /** Live {@link ChannelLister}: page {@code conversations.list} and match by name.
     *  {@code conversations.list} only returns private channels the bot is a member of, and
     *  posting to a public channel the bot hasn't joined additionally needs the
     *  {@code chat:write.public} scope (JCLAW-454). Never throws. */
    private static String lookupChannelIdByNameLive(String botToken, String name) {
        try {
            String cursor = null;
            do {
                final String c = cursor;
                var resp = slack.methods(botToken).conversationsList(r -> r
                        .types(List.of(ConversationType.PUBLIC_CHANNEL, ConversationType.PRIVATE_CHANNEL))
                        .excludeArchived(true)
                        .limit(1000)
                        .cursor(c));
                if (!resp.isOk()) {
                    EventLogger.warn("channel", null, "slack",
                            "conversations.list error: %s".formatted(resp.getError()));
                    return null;
                }
                if (resp.getChannels() != null) {
                    for (var ch : resp.getChannels()) {
                        if (name.equalsIgnoreCase(ch.getName())) return ch.getId();
                    }
                }
                cursor = resp.getResponseMetadata() != null
                        ? resp.getResponseMetadata().getNextCursor() : null;
            } while (cursor != null && !cursor.isBlank());
            return null;
        } catch (IOException | SlackApiException e) {
            EventLogger.warn("channel", null, "slack",
                    "conversations.list failed: %s".formatted(e.getMessage()));
            return null;
        }
    }

    /**
     * Post a Block Kit message and return its {@code ts}, or {@code null} on failure.
     * The {@code fallbackText} is the notification/screen-reader text Slack shows
     * where blocks can't render (mobile push, a11y); the {@code blocks} carry the
     * interactive surface. Used by {@link SlackApprovalService} (JCLAW-350) to post
     * the exec-approval prompt with approve/deny buttons.
     */
    public static String postMessageWithBlocks(String botToken, String channelId, String threadTs,
                                               String fallbackText, List<LayoutBlock> blocks) {
        if (botToken == null || botToken.isBlank()) return null;
        try {
            var resp = slack.methods(botToken).chatPostMessage(r -> r
                    .channel(channelId).threadTs(threadTs).text(fallbackText).blocks(blocks));
            return resp.isOk() ? resp.getTs() : null;
        } catch (IOException | SlackApiException _) {
            return null;
        }
    }

    /**
     * Replace a message's blocks via {@code chat.update} — used to swap the live
     * approve/deny prompt for a static "Approved/Denied/Expired" line once the
     * approval resolves, so the stale buttons can't be tapped again.
     */
    public static boolean updateMessageWithBlocks(String botToken, String channelId, String ts,
                                                  String fallbackText, List<LayoutBlock> blocks) {
        if (botToken == null || botToken.isBlank()) return false;
        try {
            return slack.methods(botToken).chatUpdate(r -> r
                    .channel(channelId).ts(ts).text(fallbackText).blocks(blocks)).isOk();
        } catch (IOException | SlackApiException _) {
            return false;
        }
    }

    /**
     * Result of an {@code auth.test} probe: {@code ok} plus the bot's own user
     * and team identity (cached on the {@link models.SlackBinding} for the
     * bot-loop guard, JCLAW-357, and surfaced in the Channels UI), or the Slack
     * error string when the token is bad/revoked.
     */
    public record AuthTestResult(boolean ok, String botUserId, String teamId,
                                 String teamName, String error) {}

    /**
     * Validate a bot token against Slack's {@code auth.test}. A bad or revoked
     * token surfaces here as {@code ok=false} with the Slack error, rather than
     * failing later at the first send. On success returns the bot's user id +
     * team id so callers can cache them on the binding.
     */
    public static AuthTestResult authTest(String botToken) {
        if (botToken == null || botToken.isBlank()) {
            return new AuthTestResult(false, null, null, null, "missing bot token");
        }
        try {
            var resp = slack.methods(botToken).authTest(r -> r);
            if (resp.isOk()) {
                return new AuthTestResult(true, resp.getUserId(), resp.getTeamId(),
                        resp.getTeam(), null);
            }
            return new AuthTestResult(false, null, null, null, resp.getError());
        } catch (IOException | SlackApiException e) {
            return new AuthTestResult(false, null, null, null, e.getMessage());
        }
    }
}
