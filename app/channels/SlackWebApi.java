package channels;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.slack.api.Slack;
import com.slack.api.methods.SlackApiException;
import com.slack.api.model.ConversationType;
import com.slack.api.model.block.LayoutBlock;
import services.EventLogger;

import java.io.IOException;
import java.time.Duration;
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

    /** A matched Slack channel: its id, and whether the bot is a member ({@code conversations.list}
     *  only returns private channels the bot is in, so found+member ⇒ reachable, found+not-member
     *  ⇒ a public channel the bot hasn't joined). JCLAW-454/455. */
    public record ChannelInfo(String id, boolean isMember) {}

    /** Test seam (JCLAW-454/455): the {@code conversations.list} name lookup, swappable so unit
     *  tests resolve channels without the network — mirrors {@link SlackFileUploader}'s injectable
     *  {@code Uploader}. {@code name} is already lowercased with any leading {@code #} stripped;
     *  returns the matched channel or {@code null} if none matches. */
    @FunctionalInterface
    public interface ChannelLister {
        ChannelInfo lookup(String botToken, String name);
    }

    static ChannelLister CHANNEL_LISTER = SlackWebApi::lookupChannelByNameLive;

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
        ChannelInfo info = CHANNEL_LISTER.lookup(botToken, name);
        if (info != null) CHANNEL_ID_CACHE.put(key, info.id());
        return info != null ? info.id() : null;
    }

    /** Live {@link ChannelLister}: page {@code conversations.list} and match by name.
     *  {@code conversations.list} only returns private channels the bot is a member of, and
     *  posting to a public channel the bot hasn't joined additionally needs the
     *  {@code chat:write.public} scope (JCLAW-454). Never throws. */
    private static ChannelInfo lookupChannelByNameLive(String botToken, String name) {
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
                        if (name.equalsIgnoreCase(ch.getName())) {
                            return new ChannelInfo(ch.getId(), ch.isMember());
                        }
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

    // ── JCLAW-455: delivery reachability probe (advisory in chat + on the Tasks page) ──

    /** Reachability of a Slack delivery target for a given bot, from the bot's vantage point. */
    public enum SlackReach {
        /** The bot is a member (public or private) — delivery will work. */
        REACHABLE,
        /** A public channel the bot hasn't joined — works only with {@code chat:write.public}. */
        PUBLIC_NOT_MEMBER,
        /** Not returned by {@code conversations.list} — a private channel the bot isn't in, or a bad name. */
        UNRESOLVED,
        /** Can't classify (no token, a literal id, or an API error) — no advisory. */
        UNKNOWN
    }

    /** A reachability verdict plus the human advisory to surface (null when no action is needed). */
    public record SlackReachability(SlackReach status, String channel, String advisory) {
        public boolean needsAttention() {
            return status == SlackReach.PUBLIC_NOT_MEMBER || status == SlackReach.UNRESOLVED;
        }
    }

    /** Short-TTL cache of probe verdicts keyed by (token-hash, name) so expanding tasks on the
     *  Tasks page doesn't re-page rate-limited {@code conversations.list}; 60 s keeps a fresh
     *  invite visible within a minute. */
    private static final Cache<String, SlackReachability> PROBE_CACHE =
            Caffeine.newBuilder().maximumSize(512).expireAfterWrite(Duration.ofSeconds(60)).build();

    /**
     * JCLAW-455: classify whether the bot can deliver to {@code target} and, when not, return an
     * actionable advisory. A literal channel id is {@code UNKNOWN} (membership isn't cheaply
     * knowable without {@code conversations.info}); a {@code #name}/bare name is probed via the
     * shared {@code conversations.list} seam. Never throws.
     *
     * <p>Honest limitation: a private channel the bot isn't in is invisible to a normal bot
     * token, so {@code UNRESOLVED} can't distinguish "private, uninvited" from "no such channel" —
     * the advisory names both causes.
     */
    public static SlackReachability probeChannel(String botToken, String target) {
        if (botToken == null || botToken.isBlank() || target == null) {
            return new SlackReachability(SlackReach.UNKNOWN, target, null);
        }
        String t = target.trim();
        if (t.isEmpty() || CHANNEL_ID.matcher(t).matches()) {
            return new SlackReachability(SlackReach.UNKNOWN, t, null);
        }
        String name = (t.startsWith("#") ? t.substring(1) : t).toLowerCase(Locale.ROOT);
        if (name.isEmpty()) return new SlackReachability(SlackReach.UNKNOWN, target, null);
        String key = Integer.toHexString(botToken.hashCode()) + ":" + name;
        var cached = PROBE_CACHE.getIfPresent(key);
        if (cached != null) return cached;
        var verdict = classifyReachability(botToken, name);
        PROBE_CACHE.put(key, verdict);
        return verdict;
    }

    private static SlackReachability classifyReachability(String botToken, String name) {
        String display = "#" + name;
        ChannelInfo info;
        try {
            info = CHANNEL_LISTER.lookup(botToken, name);
        } catch (RuntimeException e) {
            return new SlackReachability(SlackReach.UNKNOWN, display, null);
        }
        if (info == null) {
            return new SlackReachability(SlackReach.UNRESOLVED, display,
                    "Can't find Slack channel " + display + ". If it's a private channel, invite the bot "
                            + "to it; if it's public, check the name (or grant the bot the chat:write.public scope).");
        }
        if (info.isMember()) {
            return new SlackReachability(SlackReach.REACHABLE, display, null);
        }
        return new SlackReachability(SlackReach.PUBLIC_NOT_MEMBER, display,
                "The bot is not a member of public channel " + display + ". It can post only if it has the "
                        + "chat:write.public scope; otherwise invite the bot to the channel.");
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
