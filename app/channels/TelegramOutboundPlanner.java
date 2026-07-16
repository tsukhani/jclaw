package channels;

import services.AgentService;
import services.EventLogger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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

    private static final String LOG_CATEGORY = "channel";
    private static final String LOG_SOURCE = "telegram";

    /** Set of file extensions that Telegram renders as an inline photo. */
    private static final List<String> IMAGE_EXTS = List.of(
            ".png", ".jpg", ".jpeg", ".webp", ".gif");

    /** Extensions routed to {@code sendVoice} (Telegram voice note — OGG/Opus). */
    private static final List<String> VOICE_EXTS = List.of(".ogg", ".oga", ".opus");

    /** Extensions routed to {@code sendAudio} (music / spoken-word tracks). */
    private static final List<String> AUDIO_EXTS = List.of(
            ".mp3", ".m4a", ".aac", ".flac", ".wav");

    /** Extensions routed to {@code sendVideo}. */
    private static final List<String> VIDEO_EXTS = List.of(
            ".mp4", ".mov", ".mkv", ".webm", ".avi", ".m4v");

    /**
     * Telegram caption hard limit (1024 UTF-16 code units). Prose longer than
     * this stays a standalone text message rather than being truncated into a
     * caption — losing content silently would be worse than an extra bubble.
     */
    private static final int CAPTION_MAX = 1024;

    /**
     * How a {@link FileSegment} should be dispatched. The channel's dispatch
     * switches on this to pick the right {@code trySend*} call; unknown file
     * types fall back to {@link #DOCUMENT}.
     */
    public enum MediaKind {
        PHOTO, VOICE, AUDIO, VIDEO, DOCUMENT
    }

    /**
     * Markdown link pattern. Accepts both the canonical angle-bracket form
     * {@code [text](<path>)} from the file delivery convention and the plain
     * {@code [text](path)} form — agents produce both. The optional leading
     * {@code !?} lets the match envelope include a markdown-image prefix so
     * the {@code !} doesn't leak into a standalone text segment when the
     * planner cuts around the match (a visible bug pre-JCLAW-104 once
     * {@code buildImagePrefix} started prepending {@code ![Screenshot](url)}
     * on every turn). Group 1 is the display text, group 2 is the path
     * (angle brackets stripped if present).
     */
    private static final Pattern LINK_PATTERN = Pattern.compile(
            "!?\\[([^\\]]+)\\]\\(<([^>]+)>\\)"
                    + "|!?\\[([^\\]]+)\\]\\(([^)\\s]+)\\)");

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

    /**
     * Telegram caps a single {@code sendMediaGroup} album at 10 items
     * (JCLAW-365). Runs longer than this chunk into successive groups of
     * {@link #MEDIA_GROUP_MAX}.
     */
    private static final int MEDIA_GROUP_MAX = 10;

    /** Ordered piece of an outbound response — text to format and send, or a file to upload. */
    public sealed interface Segment permits TextSegment, FileSegment, MediaGroupSegment {}

    public record TextSegment(String markdown) implements Segment {}

    /**
     * JCLAW-365: a run of 2–10 photo/video {@link FileSegment}s the channel
     * dispatches as a single {@code sendMediaGroup} album instead of N
     * individual sends. The caption (if any) rides the first item only;
     * {@link #caption()} folds the lead-in prose of the first grouped file.
     * Built only for genuinely groupable runs — a lone media item keeps its
     * standalone {@link FileSegment} so the single-send path is unchanged.
     *
     * @param items   2–10 foreground PHOTO/VIDEO segments, in order
     * @param caption prose to attach to the album (first item); null when none
     */
    public record MediaGroupSegment(List<FileSegment> items, String caption) implements Segment {}

    /**
     * File segment dispatched via the matching {@code trySend*} method per
     * {@link #kind}.
     *
     * @param displayName  filename hint shown to the user
     * @param file         on-disk file to upload
     * @param isImage      true when the file should ride as a photo (rendered
     *                     inline) vs a document (file chip). Retained alongside
     *                     {@link #kind} ({@code isImage == (kind == PHOTO)}) so
     *                     the JCLAW-123 photo+document duplicate logic and its
     *                     tests read clearly.
     * @param isBackground marks the JCLAW-123 quality-duplicate document emit
     *                     (second segment in the photo+document pair for an
     *                     image). The channel fires background segments on a
     *                     virtual thread and does not block subsequent
     *                     dispatch on them — critical because these uploads
     *                     of the already-rendered photo can take multiple
     *                     minutes server-side, and we don't want text
     *                     messages to wait behind them.
     * @param kind         JCLAW-364: how the channel should dispatch the file
     *                     (photo / voice / audio / video / document).
     * @param caption      JCLAW-364: prose folded in from the immediately
     *                     preceding text run, attached to the media instead of
     *                     sent as a separate message; {@code null} when none.
     */
    public record FileSegment(String displayName, File file, boolean isImage, boolean isBackground,
                              MediaKind kind, String caption) implements Segment {

        /** Convenience constructor for the no-caption case. */
        public FileSegment(String displayName, File file, boolean isImage, boolean isBackground, MediaKind kind) {
            this(displayName, file, isImage, isBackground, kind, null);
        }

        /** Return a copy of this segment with {@code newCaption} attached. */
        public FileSegment withCaption(String newCaption) {
            return new FileSegment(displayName, file, isImage, isBackground, kind, newCaption);
        }
    }

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
        // JCLAW-104 + JCLAW-123: tools like the Playwright screenshot produce
        // both a markdown image ({@code ![alt](url)}) and a plain text link
        // ({@code [text](url)}) pointing at the same file URL. Our regex
        // matches both. Dedupe by canonical resolved path.
        var seenFiles = new HashSet<String>();

        while (matcher.find()) {
            var processed = processMatch(matcher, markdown, agentName, segments, cursor, seenFiles);
            if (processed != null) cursor = processed;
        }

        if (cursor < markdown.length()) {
            appendOrMergeText(segments, markdown.substring(cursor));
        }

        // No file references found — hand back a single-segment list so the
        // caller has a single uniform code path.
        if (segments.isEmpty()) return List.of(new TextSegment(markdown));
        return coalesceMediaGroups(foldCaptions(segments));
    }

    /**
     * JCLAW-365: collapse a run of 2+ consecutive foreground photo/video
     * {@link FileSegment}s into a {@link MediaGroupSegment} so the channel sends
     * one Telegram album ({@code sendMediaGroup}) instead of N individual
     * uploads. Telegram only groups photos + videos — documents, voice, and
     * audio are never group-eligible and stay individual.
     *
     * <p>Interplay with the JCLAW-123 photo+document pair: an image emits a
     * foreground PHOTO immediately followed by a <em>background</em> DOCUMENT
     * (the original-quality re-upload). A run of N images therefore reads
     * {@code PHOTO, bgDOC, PHOTO, bgDOC, ...}. The grouped run absorbs the
     * foreground photos into one album; the interleaved background documents are
     * preserved and re-emitted <em>after</em> the album so the
     * background-original-document behavior is unchanged.
     *
     * <p>A lone media item (run of 1) keeps its standalone {@link FileSegment} —
     * the single-send path is untouched. Runs longer than {@link #MEDIA_GROUP_MAX}
     * chunk into successive albums of 10.
     *
     * <p>A blank (whitespace-only) {@link TextSegment} between two media files —
     * the no-op separator the regex leaves between back-to-back links — is
     * transparent to a run: it would never put bytes on the wire
     * ({@code sendTextSegment} short-circuits on blank), so it neither breaks the
     * run nor survives into the output. A non-blank text segment DOES break the
     * run (it is real prose the user must see between the media).
     */
    private static List<Segment> coalesceMediaGroups(List<Segment> segments) {
        var out = new ArrayList<Segment>(segments.size());
        int i = 0;
        while (i < segments.size()) {
            var run = new ArrayList<FileSegment>();        // foreground photos/videos
            var trailingBg = new ArrayList<FileSegment>(); // interleaved background docs
            int j = i;
            while (j < segments.size()) {
                var seg = segments.get(j);
                if (seg instanceof FileSegment fs
                        && (isGroupEligible(fs) || (fs.isBackground() && !run.isEmpty()))) {
                    if (fs.isBackground()) trailingBg.add(fs); else run.add(fs);
                    j++;
                } else if (seg instanceof TextSegment(String prose)
                        && prose.isBlank() && !run.isEmpty()) {
                    // No-op separator between two media files — skip it (drop it,
                    // since it produces no wire traffic) without breaking the run.
                    j++;
                } else {
                    break;
                }
            }
            if (run.size() >= 2) {
                emitGroups(out, run);
                out.addAll(trailingBg);
                i = j;
            } else {
                // Not a groupable run — emit this single segment as-is and advance one.
                out.add(segments.get(i));
                i++;
            }
        }
        return out;
    }

    /** A foreground photo or video FileSegment — the only kinds Telegram bundles into an album. */
    private static boolean isGroupEligible(FileSegment fs) {
        return !fs.isBackground()
                && (fs.kind() == MediaKind.PHOTO || fs.kind() == MediaKind.VIDEO);
    }

    /**
     * Emit {@code run} as one or more {@link MediaGroupSegment}s, chunked at
     * {@link #MEDIA_GROUP_MAX}. The first item's folded caption becomes the
     * album caption (Telegram surfaces the first item's caption as the album's).
     */
    private static void emitGroups(List<Segment> out, List<FileSegment> run) {
        for (int start = 0; start < run.size(); start += MEDIA_GROUP_MAX) {
            int end = Math.min(start + MEDIA_GROUP_MAX, run.size());
            var chunk = List.copyOf(run.subList(start, end));
            // A lone tail (e.g. the 11th of 11 photos) isn't a valid media group —
            // Telegram requires 2-10. Emit it as a single send rather than a 1-item
            // MediaGroupSegment that sendMediaGroup would reject and fall back from.
            if (chunk.size() == 1) {
                out.add(chunk.get(0));
            } else {
                out.add(new MediaGroupSegment(chunk, chunk.get(0).caption()));
            }
        }
    }

    /**
     * JCLAW-364: fold the prose immediately preceding a media file into that
     * file's caption, dropping the standalone {@link TextSegment} so the user
     * sees the prose attached to the media rather than as a separate bubble.
     *
     * <p>Scoped to be safe against the JCLAW-123 photo+document pair:
     * <ul>
     *   <li>only a <em>foreground</em> segment ({@code !isBackground}) absorbs a
     *       caption — the quality-duplicate background document keeps none, so a
     *       photo's lead-in prose rides the visible photo, not the silent file
     *       re-upload;</li>
     *   <li>the preceding text must be the segment directly before the file and
     *       must not already belong to another file (i.e. it is a real
     *       {@link TextSegment});</li>
     *   <li>prose longer than {@link #CAPTION_MAX} stays a standalone message —
     *       captioning it would silently truncate.</li>
     * </ul>
     */
    private static List<Segment> foldCaptions(List<Segment> segments) {
        var out = new ArrayList<Segment>(segments.size());
        for (var segment : segments) {
            if (segment instanceof FileSegment fs && !fs.isBackground() && fs.caption() == null
                    && !out.isEmpty() && out.getLast() instanceof TextSegment(String prose)) {
                var trimmed = prose.strip();
                if (!trimmed.isEmpty() && trimmed.length() <= CAPTION_MAX) {
                    out.removeLast();
                    out.add(fs.withCaption(trimmed));
                    continue;
                }
            }
            out.add(segment);
        }
        return out;
    }

    /**
     * Resolve a single regex match into a file reference (or skip if not a
     * workspace file). Returns the new cursor position when the match was
     * consumed, or {@code null} when the match should be left in the
     * surrounding text. Mutates {@code segments} and {@code seenFiles}.
     */
    private static Integer processMatch(Matcher matcher, String markdown, String agentName,
                                         List<Segment> segments, int cursor,
                                         Set<String> seenFiles) {
        // Either the angle-bracket branch or the plain-form branch fired;
        // read from whichever captured.
        String path = matcher.group(2) != null ? matcher.group(2) : matcher.group(4);
        String display = matcher.group(1) != null ? matcher.group(1) : matcher.group(3);

        String relative = extractWorkspaceRelative(path);
        if (relative == null) return null;

        File resolved = resolveWorkspaceFile(agentName, relative);
        if (resolved == null) return null;

        // JCLAW-123: advance cursor past every file-ref match (whether
        // we're emitting a new segment or deduping a repeat). Pre-JCLAW-123
        // dedup left the duplicate markdown in the text segment, which the
        // HTML formatter converted to an anchor pointing at the localhost
        // /api/agents/... URL — a visible-but-broken link in Telegram.
        // Cutting the match out uniformly means the surrounding text stays
        // clean regardless of how many times the LLM mentions the file.
        String before = markdown.substring(cursor, matcher.start());
        if (!before.isEmpty()) {
            appendOrMergeText(segments, before);
        }
        int newCursor = matcher.end();

        if (!seenFiles.add(canonicalPath(resolved))) return newCursor;

        MediaKind kind = classify(resolved.getName());
        boolean isImage = kind == MediaKind.PHOTO;
        segments.add(new FileSegment(display, resolved, isImage, false, kind));
        // JCLAW-123: Telegram compresses photos aggressively (JPEG re-encode,
        // downscaled). For image files we also emit a sendDocument pass so
        // the user gets the original-quality downloadable file alongside
        // the inline preview. Non-image files already deliver as documents
        // on the first pass — no duplicate needed. Marked isBackground=true
        // (JCLAW-126) so the channel fires it async — Telegram document
        // uploads of a just-sent photo can stall for 2+ minutes, and we
        // don't want text messages to wait behind that.
        if (isImage) {
            segments.add(new FileSegment(display, resolved, false, true, MediaKind.DOCUMENT));
        }
        return newCursor;
    }

    /**
     * Reduce a raw link target to its workspace-relative path, or {@code null}
     * if the target can't be delivered (external URL, anchor, foreign absolute
     * path). Returns the same string for already-relative paths.
     */
    private static String extractWorkspaceRelative(String path) {
        // External URLs and in-page anchors aren't workspace files — leave
        // them inside the surrounding text so the formatter renders them
        // as normal links.
        if (path.contains("://")) return null;
        if (path.startsWith("#")) return null;

        // Absolute paths are only resolvable when they match the JClaw
        // workspace-file serve URL that the Playwright tool (and similar)
        // hand to the agent. Extract the relative portion; skip any other
        // absolute path — those are neither workspace files nor something
        // we can deliver on Telegram.
        if (path.startsWith("/")) {
            var apiMatch = API_FILES_URL.matcher(path);
            if (!apiMatch.matches()) return null;
            return apiMatch.group(1);
        }
        return path;
    }

    private static String canonicalPath(File resolved) {
        try {
            return resolved.getCanonicalPath();
        } catch (IOException _) {
            return resolved.getAbsolutePath();
        }
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
                EventLogger.warn(LOG_CATEGORY, agentName, LOG_SOURCE,
                        "File delivery: workspace path does not exist: %s".formatted(relativePath));
                return null;
            }
            return file;
        } catch (SecurityException _) {
            EventLogger.warn(LOG_CATEGORY, agentName, LOG_SOURCE,
                    "File delivery rejected (path traversal): %s".formatted(relativePath));
            return null;
        } catch (Exception e) {
            EventLogger.warn(LOG_CATEGORY, agentName, LOG_SOURCE,
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
        return hasExtensionIn(filename, IMAGE_EXTS);
    }

    /**
     * JCLAW-364: route {@code filename} to the native Telegram send method best
     * matching its extension. Images → {@link MediaKind#PHOTO}, OGG/Opus →
     * {@link MediaKind#VOICE}, other audio → {@link MediaKind#AUDIO}, video →
     * {@link MediaKind#VIDEO}; everything else (including a null/extension-less
     * name) falls back to {@link MediaKind#DOCUMENT}.
     */
    public static MediaKind classify(String filename) {
        if (hasExtensionIn(filename, IMAGE_EXTS)) return MediaKind.PHOTO;
        if (hasExtensionIn(filename, VOICE_EXTS)) return MediaKind.VOICE;
        if (hasExtensionIn(filename, AUDIO_EXTS)) return MediaKind.AUDIO;
        if (hasExtensionIn(filename, VIDEO_EXTS)) return MediaKind.VIDEO;
        return MediaKind.DOCUMENT;
    }

    private static boolean hasExtensionIn(String filename, List<String> exts) {
        if (filename == null) return false;
        var lower = filename.toLowerCase(Locale.ROOT);
        for (String ext : exts) {
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
        if (!segments.isEmpty() && segments.getLast() instanceof TextSegment(String markdown)) {
            segments.set(segments.size() - 1, new TextSegment(markdown + text));
        } else {
            segments.add(new TextSegment(text));
        }
    }
}
