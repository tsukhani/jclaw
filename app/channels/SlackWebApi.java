package channels;

import com.slack.api.Slack;
import com.slack.api.methods.SlackApiException;
import com.slack.api.model.block.LayoutBlock;

import java.io.IOException;
import java.util.List;

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
