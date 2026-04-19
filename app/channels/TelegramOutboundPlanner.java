package channels;

import services.AgentService;
import services.EventLogger;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Segments an outbound agent response into ordered text + file chunks so the
 * Telegram channel can dispatch each piece with the right Bot API method
 * (sendMessage for text, sendPhoto for images, sendDocument for other files).
 *
 * <p>Detection runs before the markdown-to-HTML formatter: we scan the raw
 * markdown for the JClaw Workspace File Delivery convention —
 * {@code [filename](<path>)} or the bare-path variant — and, for each match that
 * resolves to a real file inside the agent's workspace, emit a
 * {@link FileSegment} in its place. Non-workspace links (HTTP URLs, broken
 * paths, traversal attempts) stay inside the surrounding {@link TextSegment}
 * so the agent's intent at least survives as visible text.
 *
 * <p>Pure functional entry point so the unit tests don't need a running Play
 * server — {@link #plan} takes primitives and returns primitives.
 */
public final class TelegramOutboundPlanner {

    /** Set of file extensions that Telegram renders as an inline photo. */
    private static final List<String> IMAGE_EXTS = List.of(
            ".png", ".jpg", ".jpeg", ".webp", ".gif");

    /**
     * Markdown link pattern. Accepts both the canonical angle-bracket form
     * {@code [text](<path>)} from the file delivery convention and the plain
     * {@code [text](path)} form — agents produce both. Group 1 is the display
     * text, group 2 is the path (angle brackets stripped if present).
     */
    private static final Pattern LINK_PATTERN = Pattern.compile(
            "\\[([^\\]]+)\\]\\(<([^>]+)>\\)"
                    + "|\\[([^\\]]+)\\]\\(([^)\\s]+)\\)");

    /**
     * Matches JClaw's workspace-file serve URL as produced by the Playwright tool
     * (and any other tool that hands an API URL back to the agent). The web UI
     * consumes these URLs directly; on Telegram we need to pull the
     * workspace-relative path out (capture group 1) and resolve it locally so we
     * can upload the file natively instead of letting it render as an unclickable
     * absolute-path link that Telegram can't follow.
     */
    private static final Pattern API_FILES_URL = Pattern.compile(
            "^/api/agents/\\d+/files/(.+)$");

    private TelegramOutboundPlanner() {}

    /** Ordered piece of an outbound response — text to format and send, or a file to upload. */
    public sealed interface Segment permits TextSegment, FileSegment {}

    public record TextSegment(String markdown) implements Segment {}

    public record FileSegment(String displayName, File file, boolean isImage) implements Segment {}

    /**
     * Split {@code markdown} into an ordered list of text and file segments.
     * When {@code agentName} is {@code null} (e.g. the webhook error-fallback
     * path with no agent context), no file detection is attempted and the whole
     * input is returned as a single {@link TextSegment}.
     */
    public static List<Segment> plan(String markdown, String agentName) {
        if (markdown == null || markdown.isEmpty()) return List.of();
        if (agentName == null) return List.of(new TextSegment(markdown));

        var segments = new ArrayList<Segment>();
        var matcher = LINK_PATTERN.matcher(markdown);
        int cursor = 0;

        while (matcher.find()) {
            // Either the angle-bracket branch or the plain-form branch fired;
            // read from whichever captured.
            String path = matcher.group(2) != null ? matcher.group(2) : matcher.group(4);
            String display = matcher.group(1) != null ? matcher.group(1) : matcher.group(3);

            // External URLs and in-page anchors aren't workspace files — leave
            // them inside the surrounding text so the formatter renders them
            // as normal links.
            if (path.contains("://")) continue;
            if (path.startsWith("#")) continue;

            // Absolute paths are only resolvable when they match the JClaw
            // workspace-file serve URL that the Playwright tool (and similar)
            // hand to the agent. Extract the relative portion; skip any other
            // absolute path — those are neither workspace files nor something
            // we can deliver on Telegram.
            String relative = path;
            if (path.startsWith("/")) {
                var apiMatch = API_FILES_URL.matcher(path);
                if (!apiMatch.matches()) continue;
                relative = apiMatch.group(1);
            }

            File resolved = resolveWorkspaceFile(agentName, relative);
            if (resolved == null) continue;

            // Everything between the previous cut and this match stays as text.
            String before = markdown.substring(cursor, matcher.start());
            if (!before.isEmpty()) {
                appendOrMergeText(segments, before);
            }
            segments.add(new FileSegment(display, resolved, isImageFilename(resolved.getName())));
            cursor = matcher.end();
        }

        if (cursor < markdown.length()) {
            appendOrMergeText(segments, markdown.substring(cursor));
        }

        // No file references found — hand back a single-segment list so the
        // caller has a single uniform code path.
        if (segments.isEmpty()) return List.of(new TextSegment(markdown));
        return segments;
    }

    /**
     * Resolve {@code relativePath} against {@code agentName}'s workspace via
     * {@link AgentService#acquireWorkspacePath} (which does lexical and canonical
     * path-traversal validation). Returns {@code null} and logs at warn level on
     * any reason the path can't be delivered — not found, rejected, or resolution
     * threw. The caller falls back to emitting the original markdown text.
     */
    private static File resolveWorkspaceFile(String agentName, String relativePath) {
        try {
            var path = AgentService.acquireWorkspacePath(agentName, relativePath);
            var file = path.toFile();
            if (!file.exists() || !file.isFile()) {
                EventLogger.warn("channel", agentName, "telegram",
                        "File delivery: workspace path does not exist: %s".formatted(relativePath));
                return null;
            }
            return file;
        } catch (SecurityException e) {
            EventLogger.warn("channel", agentName, "telegram",
                    "File delivery rejected (path traversal): %s".formatted(relativePath));
            return null;
        } catch (Exception e) {
            EventLogger.warn("channel", agentName, "telegram",
                    "File delivery resolution error for %s: %s".formatted(relativePath, e.getMessage()));
            return null;
        }
    }

    /**
     * Case-insensitive image-extension check. Telegram handles photo messages
     * differently from document messages — the former renders inline with a
     * preview, the latter shows up as a download attachment.
     */
    public static boolean isImageFilename(String filename) {
        if (filename == null) return false;
        var lower = filename.toLowerCase(Locale.ROOT);
        for (String ext : IMAGE_EXTS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    /**
     * Append {@code text} to the tail of {@code segments} if the last segment is
     * already text, otherwise add a new {@link TextSegment}. Keeps the output
     * list alternating text/file cleanly even when multiple file references sit
     * in one markdown blob.
     */
    private static void appendOrMergeText(List<Segment> segments, String text) {
        if (!segments.isEmpty() && segments.get(segments.size() - 1) instanceof TextSegment prev) {
            segments.set(segments.size() - 1, new TextSegment(prev.markdown() + text));
        } else {
            segments.add(new TextSegment(text));
        }
    }
}
