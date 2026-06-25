package channels;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * JCLAW-345: extracts workspace-file links from an agent's reply and uploads each
 * to Slack. The prose itself was already streamed (native or draft preview), so
 * this only handles the file segments — the agent signals an attachment the same
 * way it does on Telegram: a workspace-relative markdown link
 * ({@code [chart](chart.png)} or {@code ![alt](/api/agents/N/files/...)}).
 *
 * <p>Reuses {@link TelegramOutboundPlanner#plan} (the link detection +
 * path-resolution + caption-folding logic is channel-agnostic); Slack just uploads
 * the resolved {@link java.io.File}s via {@link SlackFileUploader}. External URLs
 * are deliberately not fetched/re-uploaded — only resolved workspace files (the
 * planner already drops {@code ://} paths), matching OpenClaw.
 */
public final class SlackOutboundPlanner {

    private SlackOutboundPlanner() {}

    /** Slack's {@code initial_comment} caption limit (vs Telegram's 1024). */
    static final int CAPTION_MAX = 3000;

    /**
     * Upload every file the agent linked in {@code replyText} to {@code peerId}
     * (channel or DM) / {@code threadTs}, on a spawned virtual thread so a large
     * upload never blocks the reply thread. No-op when there's no agent/token
     * context or no file links.
     */
    public static void dispatchFiles(String peerId, String threadTs, String agentName,
                                     String replyText, String botToken) {
        if (agentName == null || botToken == null || replyText == null || replyText.isBlank()) {
            return;
        }
        var files = fileSegments(replyText, agentName);
        if (files.isEmpty()) {
            return;
        }
        Thread.ofVirtual().name("slack-upload").start(() -> {
            for (var fs : files) {
                SlackFileUploader.upload(botToken, peerId, threadTs, fs.file(), fs.displayName(), caption(fs));
            }
        });
    }

    /** Flatten the planner output to the uploadable file segments, deduped by file
     *  path. A media group has no Slack album primitive (upload items individually),
     *  and an image yields two Telegram segments (inline photo + downloadable doc)
     *  pointing at the same file — Slack uploads each file once. */
    public static List<TelegramOutboundPlanner.FileSegment> fileSegments(String replyText, String agentName) {
        var out = new ArrayList<TelegramOutboundPlanner.FileSegment>();
        var seen = new HashSet<String>();
        for (var seg : TelegramOutboundPlanner.plan(replyText, agentName)) {
            if (seg instanceof TelegramOutboundPlanner.FileSegment fs) {
                addUnique(out, seen, fs);
            } else if (seg instanceof TelegramOutboundPlanner.MediaGroupSegment mg) {
                for (var item : mg.items()) {
                    addUnique(out, seen, item);
                }
            }
        }
        return out;
    }

    private static void addUnique(List<TelegramOutboundPlanner.FileSegment> out,
                                  Set<String> seen, TelegramOutboundPlanner.FileSegment fs) {
        var path = fs.file() != null ? fs.file().getAbsolutePath() : null;
        if (path == null || seen.add(path)) {
            out.add(fs);
        }
    }

    private static String caption(TelegramOutboundPlanner.FileSegment fs) {
        var c = fs.caption();
        if (c == null) return null;
        return c.length() > CAPTION_MAX ? c.substring(0, CAPTION_MAX) : c;
    }
}
