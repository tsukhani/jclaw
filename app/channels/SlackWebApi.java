package channels;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.slack.api.Slack;
import com.slack.api.methods.SlackApiException;
import com.slack.api.model.Conversation;
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
    /** A Slack <em>user</em> id — not addressable by {@code chat.postMessage}, so it resolves through
     *  {@code conversations.open} to a {@code D}-channel (JCLAW-1018). Shares {@link #CHANNEL_ID}'s
     *  shape, and with it the ambiguity that an all-caps bare name of the same form (e.g. {@code GENERAL},
     *  {@code UPDATES}) reads as an id; prefix with {@code #} to force the name path. */
    private static final Pattern USER_ID = Pattern.compile("^U[A-Z0-9]{6,}$");
    /** EventLogger category/source for this class, spelled as in SlackInbound. */
    private static final String CATEGORY_CHANNEL = "channel";
    private static final String CHANNEL_SLACK = "slack";

    /** Slack's error code when the bot token lacks a scope the call needs. */
    private static final String ERR_MISSING_SCOPE = "missing_scope";

    /** {@code conversations.open} was refused for want of {@code im:write} — distinct from the
     *  {@code missing_scope} the name path reports, which is about {@code channels:read}. */
    private static final String DM_MISSING_SCOPE = "dm_missing_scope";
    /** {@code conversations.open} failed for any other reason (unknown user, a bot user, API error). */
    private static final String DM_OPEN_FAILED = "dm_open_failed";
    private static final int CHANNEL_CACHE_MAX = 2048;
    /** name→id and user→DM cache keyed by (token-hash, lowercased name | {@code U…} id); bounded LRU.
     *  The two key spaces can't collide — names are lowercased, user ids are upper-case {@code U}-prefixed.
     *  Mirrors {@link SlackFileUploader}'s DM cache. */
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

    /** Outcome of a name lookup (JCLAW-458): the matched {@code channel} (null when no match or the
     *  list call failed), and the Slack API {@code error} code (e.g. {@code missing_scope}) when the
     *  {@code conversations.list} call itself failed, else null. Lets the delivery + advisory paths
     *  tell "no such channel / bot not in it" apart from "the bot can't even list channels". */
    public record ChannelLookup(ChannelInfo channel, String error) {
        static final ChannelLookup NOT_FOUND = new ChannelLookup(null, null);
        static ChannelLookup found(ChannelInfo c) { return new ChannelLookup(c, null); }
        static ChannelLookup failed(String error) { return new ChannelLookup(null, error); }
    }

    /** Test seam (JCLAW-454/455/458): the {@code conversations.list} name lookup, swappable so unit
     *  tests resolve channels without the network — mirrors {@link SlackFileUploader}'s injectable
     *  {@code Uploader}. {@code name} is already lowercased with any leading {@code #} stripped. */
    @FunctionalInterface
    public interface ChannelLister {
        ChannelLookup lookup(String botToken, String name);
    }

    static ChannelLister channelLister = SlackWebApi::lookupChannelByNameLive;

    /** Resolution of a Slack delivery target (JCLAW-458): the {@code channelId} (null when it
     *  couldn't be resolved) plus the Slack {@code error} code on failure ({@code missing_scope}
     *  when the bot can't list channels, else {@code channel_not_found}). */
    public record ChannelResolution(String channelId, String error) {}

    /** Test seam (JCLAW-1018): the {@code conversations.open} user→DM lookup, swappable so unit tests
     *  resolve DMs without the network — mirrors {@link ChannelLister}. */
    @FunctionalInterface
    public interface DmOpener {
        ChannelResolution open(String botToken, String userId);
    }

    static DmOpener dmOpener = SlackWebApi::openDmLive;

    /**
     * JCLAW-454/458/1018: resolve a Slack delivery {@code target} to a channel id, surfacing the failure
     * reason. A literal id ({@code C}/{@code G}/{@code D}…) passes through; a {@code U…} user id opens
     * (and caches) a DM channel; a {@code #name}/bare name is looked up via {@code conversations.list}
     * (cached per token+name on success). On failure the {@code error} is the Slack code —
     * {@code missing_scope} when the bot can't list channels (needs {@code channels:read}/{@code
     * groups:read}), {@code dm_missing_scope}/{@code dm_open_failed} when a DM can't be opened, else
     * {@code channel_not_found}. Never throws.
     */
    public static ChannelResolution resolveChannel(String botToken, String target) {
        if (botToken == null || botToken.isBlank() || target == null) return new ChannelResolution(null, null);
        String t = target.trim();
        if (t.isEmpty()) return new ChannelResolution(null, null);
        if (CHANNEL_ID.matcher(t).matches()) return new ChannelResolution(t, null);
        if (USER_ID.matcher(t).matches()) return resolveDm(botToken, t);
        String name = (t.startsWith("#") ? t.substring(1) : t).toLowerCase(Locale.ROOT);
        if (name.isEmpty()) return new ChannelResolution(null, null);
        String key = Integer.toHexString(botToken.hashCode()) + ":" + name;
        var cached = CHANNEL_ID_CACHE.get(key);
        if (cached != null) return new ChannelResolution(cached, null);
        var lk = channelLister.lookup(botToken, name);
        if (lk.channel() != null) {
            CHANNEL_ID_CACHE.put(key, lk.channel().id());
            return new ChannelResolution(lk.channel().id(), null);
        }
        return new ChannelResolution(null, lk.error() != null ? lk.error() : "channel_not_found");
    }

    /** JCLAW-454: id-only convenience over {@link #resolveChannel} — the channel id, or null. */
    public static String resolveChannelId(String botToken, String target) {
        return resolveChannel(botToken, target).channelId();
    }

    /** JCLAW-1018: the {@code D}-channel for a {@code U…} user, cached per (token, user) like the
     *  name path — a DM channel id is stable, so a repeat send never re-opens. */
    private static ChannelResolution resolveDm(String botToken, String userId) {
        String key = Integer.toHexString(botToken.hashCode()) + ":" + userId;
        var cached = CHANNEL_ID_CACHE.get(key);
        if (cached != null) return new ChannelResolution(cached, null);
        var opened = dmOpener.open(botToken, userId);
        if (opened.channelId() != null) CHANNEL_ID_CACHE.put(key, opened.channelId());
        return opened;
    }

    /** Live {@link DmOpener}: {@code conversations.open} on a single user. Needs the {@code im:write}
     *  scope; without it Slack answers {@code missing_scope}, which maps to {@link #DM_MISSING_SCOPE} so
     *  callers don't confuse it with the name path's {@code channels:read} gap. Never throws. */
    private static ChannelResolution openDmLive(String botToken, String userId) {
        try {
            var resp = slack.methods(botToken).conversationsOpen(r -> r.users(List.of(userId)));
            if (resp.isOk() && resp.getChannel() != null) {
                return new ChannelResolution(resp.getChannel().getId(), null);
            }
            EventLogger.warn(CATEGORY_CHANNEL, null, CHANNEL_SLACK,
                    "conversations.open error: %s".formatted(resp.getError()));
            return new ChannelResolution(null,
                    ERR_MISSING_SCOPE.equals(resp.getError()) ? DM_MISSING_SCOPE : DM_OPEN_FAILED);
        } catch (IOException | SlackApiException e) {
            EventLogger.warn(CATEGORY_CHANNEL, null, CHANNEL_SLACK,
                    "conversations.open failed: %s".formatted(e.getMessage()));
            return new ChannelResolution(null, DM_OPEN_FAILED);
        }
    }

    /** Live {@link ChannelLister}: page {@code conversations.list} and match by name. Returns the
     *  Slack error code (e.g. {@code missing_scope}) when the call fails, so callers can tell a scope
     *  gap from a genuine miss. {@code conversations.list} only returns private channels the bot is a
     *  member of, and posting to a public channel the bot hasn't joined additionally needs the
     *  {@code chat:write.public} scope (JCLAW-454). Never throws. */
    private static ChannelLookup lookupChannelByNameLive(String botToken, String name) {
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
                    EventLogger.warn(CATEGORY_CHANNEL, null, CHANNEL_SLACK,
                            "conversations.list error: %s".formatted(resp.getError()));
                    return ChannelLookup.failed(resp.getError());
                }
                var hit = matchByName(resp.getChannels(), name);
                if (hit != null) return ChannelLookup.found(hit);
                cursor = resp.getResponseMetadata() != null
                        ? resp.getResponseMetadata().getNextCursor() : null;
            } while (cursor != null && !cursor.isBlank());
            return ChannelLookup.NOT_FOUND;
        } catch (IOException | SlackApiException e) {
            EventLogger.warn(CATEGORY_CHANNEL, null, CHANNEL_SLACK,
                    "conversations.list failed: %s".formatted(e.getMessage()));
            return ChannelLookup.failed("io_error");
        }
    }

    /** Find a channel by case-insensitive name in one {@code conversations.list} page; null if not
     *  present in this page. Extracted from {@link #lookupChannelByNameLive} to keep it under the
     *  cognitive-complexity bound (Sonar S3776). */
    private static ChannelInfo matchByName(List<Conversation> channels, String name) {
        if (channels == null) return null;
        for (var ch : channels) {
            if (name.equalsIgnoreCase(ch.getName())) {
                return new ChannelInfo(ch.getId(), ch.isMember());
            }
        }
        return null;
    }

    // ── JCLAW-458: bind-time delivery-scope check ──

    /** Test seam (JCLAW-458): probe whether {@code botToken} can list channels for name-based
     *  delivery; returns the Slack error code ({@code missing_scope} when {@code channels:read} /
     *  {@code groups:read} is absent) or null when the list call succeeds. */
    @FunctionalInterface
    public interface ScopeProber {
        String listError(String botToken);
    }

    static ScopeProber scopeProber = SlackWebApi::probeListScopeLive;

    private static String probeListScopeLive(String botToken) {
        try {
            var resp = slack.methods(botToken).conversationsList(r -> r
                    .types(List.of(ConversationType.PUBLIC_CHANNEL, ConversationType.PRIVATE_CHANNEL))
                    .limit(1));
            return resp.isOk() ? null : resp.getError();
        } catch (IOException | SlackApiException _) {
            return null; // can't determine scopes (network/other) — don't warn
        }
    }

    /**
     * JCLAW-458: a non-blocking warning if {@code botToken} can't enumerate channels for name-based
     * delivery (a {@code conversations.list} probe returns {@code missing_scope}), else null. Posting
     * to a channel the bot is already a member of is unaffected, so this is advisory only. Surfaced at
     * binding create/update time because {@code auth.test} validates the token but not its scopes.
     */
    public static String deliveryScopeWarning(String botToken) {
        if (botToken == null || botToken.isBlank()) return null;
        String err;
        try {
            err = scopeProber.listError(botToken);
        } catch (RuntimeException _) {
            return null;
        }
        if (!ERR_MISSING_SCOPE.equals(err)) return null;
        return "This bot token can't look up channels by name (missing the channels:read / groups:read "
                + "scope), so name-based delivery (e.g. slack:#daily-briefings) will fail with "
                + "channel_not_found. Add the scope under Bot Token Scopes and reinstall the app, or "
                + "deliver by channel id (slack:C…). Posting to a channel the bot is already a member of "
                + "still works.";
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
        /** {@code conversations.list} returned {@code missing_scope} — the bot can't resolve names by
         *  listing (needs {@code channels:read}/{@code groups:read}); JCLAW-458. */
        MISSING_SCOPE,
        /** Can't classify (no token, a literal id, or an API error) — no advisory. */
        UNKNOWN
    }

    /** A reachability verdict plus the human advisory to surface (null when no action is needed). */
    public record SlackReachability(SlackReach status, String channel, String advisory) {
        public boolean needsAttention() {
            return status == SlackReach.PUBLIC_NOT_MEMBER || status == SlackReach.UNRESOLVED
                    || status == SlackReach.MISSING_SCOPE;
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
        // JCLAW-1018: a U… target is a DM, not a channel — probing it as a name yields a
        // "can't find #u…, invite the bot" advisory that names the wrong problem entirely.
        if (t.isEmpty() || CHANNEL_ID.matcher(t).matches() || USER_ID.matcher(t).matches()) {
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
        ChannelLookup lk;
        try {
            lk = channelLister.lookup(botToken, name);
        } catch (RuntimeException _) {
            return new SlackReachability(SlackReach.UNKNOWN, display, null);
        }
        // JCLAW-458: a scope gap masquerades as "not found" — name it precisely.
        if (ERR_MISSING_SCOPE.equals(lk.error())) {
            return new SlackReachability(SlackReach.MISSING_SCOPE, display,
                    "JClaw can't look up Slack channel " + display + " by name — the bot token is missing the "
                            + "channels:read / groups:read scope. Add it (Bot Token Scopes) and reinstall the app, "
                            + "or set the delivery to the channel id (slack:C…).");
        }
        var info = lk.channel();
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
