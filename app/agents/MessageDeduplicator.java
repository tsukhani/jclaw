package agents;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Image / link deduplication helpers extracted from {@link AgentRunner} (JCLAW-284).
 *
 * <p>Pure string transforms: given a list of collected image-markdown entries
 * scraped from tool results and the assistant's reply content, decide which
 * collected images still need to be prepended (inline render) and which need a
 * trailing download link. All methods are deterministic and side-effect free
 * (except {@link #extractImageUrls}, which mutates its caller-supplied list).
 */
public final class MessageDeduplicator {

    private MessageDeduplicator() {}

    private static final Pattern IMAGE_URL_PATTERN =
            Pattern.compile("!\\[([^\\]]*)\\]\\((/api/[^)]+)\\)");
    private static final Pattern PAREN_URL_PATTERN =
            Pattern.compile("\\(([^)]+)\\)");
    // Matches any image-markdown embed, not just /api/ URLs. Used by buildImagePrefix
    // to distinguish "LLM re-embedded the image" (suppress prepend) from "LLM
    // mentioned the filename/URL as text" (still prepend — the user wants both
    // an inline image AND a textual link reference).
    private static final Pattern ANY_IMAGE_EMBED =
            Pattern.compile("!\\[[^\\]]*\\]\\(([^)]+)\\)");
    // Matches HTML <img src="..."> embeds (single, double, or unquoted src).
    // LLMs occasionally emit HTML instead of markdown; catching both ensures a
    // re-embed in either form suppresses the prepend and prevents duplicate
    // rendering.
    private static final Pattern HTML_IMG_EMBED =
            Pattern.compile("<img\\s[^>]*?src\\s*=\\s*[\"']?([^\"'\\s>]+)",
                    Pattern.CASE_INSENSITIVE);
    // Matches any markdown link or image ({@code [text](url)} or
    // {@code ![alt](url)}) — the optional {@code !} prefix isn't captured in
    // the group but the pattern still matches both. Used by
    // {@link #buildDownloadSuffix} to detect when the LLM has already linked
    // to a file so we don't append a duplicate download link.
    private static final Pattern MARKDOWN_LINK_OR_IMAGE =
            Pattern.compile("!?\\[[^\\]]*\\]\\(([^)]+)\\)");
    // Matches HTML anchor {@code <a href="...">} (single, double, or unquoted).
    // Same intent as HTML_IMG_EMBED — catch LLM-emitted HTML instead of
    // markdown so the link dedup stays symmetric across formats.
    private static final Pattern HTML_ANCHOR =
            Pattern.compile("<a\\s[^>]*?href\\s*=\\s*[\"']?([^\"'\\s>]+)",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern ALT_TEXT_PATTERN =
            Pattern.compile("!\\[([^\\]]*)\\]");

    /**
     * Extract markdown image URLs from tool results and stream them directly to the frontend.
     * This ensures rendered images (screenshots, QR codes, etc.) appear in the chat
     * regardless of whether the LLM includes them in its response.
     *
     * <p>Exposed for unit tests; not part of the public runner API.
     */
    public static void extractImageUrls(String toolResult, List<String> collectedImages) {
        if (collectedImages == null || toolResult == null) return;
        var matcher = IMAGE_URL_PATTERN.matcher(toolResult);
        while (matcher.find()) {
            collectedImages.add(matcher.group(0));
        }
    }

    /**
     * Build a leading image block containing only collected images that the LLM
     * reply has not already embedded as an image. Prevents double-rendering when
     * the LLM echoes the image from a tool result — in either markdown
     * ({@code ![alt](url)}) or HTML ({@code <img src="url">}) form — but does
     * <em>not</em> suppress the prepend when the LLM merely references the URL
     * or filename as a plain text link. In the text-mention case the user
     * expects to see both the inline image AND the link reference, so the
     * prepend still fires.
     *
     * <p>A collected image is suppressed when its filename appears inside any
     * {@code ![...](...)} or {@code <img src=...>} in the reply. Filenames in
     * JClaw are timestamp-suffixed (e.g. {@code screenshot-1713100000000.png})
     * so collisions are effectively impossible, and an LLM reply that re-embeds
     * the image with a rewritten path (e.g.
     * {@code ![alt](./workspace/screenshot-1713100000000.png)}) is still caught
     * by filename match.
     *
     * <p>When the LLM drops the image entirely, every collected image survives
     * the filter and the prefix acts as a safety net so the user still sees it.
     *
     * <p>Exposed for unit tests; not part of the public runner API.
     */
    public static String buildImagePrefix(List<String> collectedImages, String content) {
        if (collectedImages == null || collectedImages.isEmpty()) return "";
        var safeContent = content != null ? content : "";

        // Collect filenames of images already embedded (as markdown OR HTML) in
        // the reply. Plain-text URL / filename / link mentions are intentionally
        // NOT collected here — that's the behavioral distinction from the prior
        // filename-substring dedup.
        var embeddedFilenames = new HashSet<String>();
        for (var pattern : List.of(ANY_IMAGE_EMBED, HTML_IMG_EMBED)) {
            var m = pattern.matcher(safeContent);
            while (m.find()) {
                var fn = extractFilename(m.group(1));
                if (!fn.isEmpty()) embeddedFilenames.add(fn);
            }
        }

        var missing = new ArrayList<String>();
        for (var img : collectedImages) {
            var m = PAREN_URL_PATTERN.matcher(img);
            if (m.find()) {
                var filename = extractFilename(m.group(1));
                if (!filename.isEmpty() && embeddedFilenames.contains(filename)) continue;
            }
            missing.add(img);
        }
        return missing.isEmpty() ? "" : String.join("\n\n", missing) + "\n\n";
    }

    /**
     * Extract the trailing filename from a URL or path captured from a
     * markdown link (JCLAW-125). Strips leading {@code <} / trailing
     * {@code >} first to handle CommonMark's angle-bracket form
     * ({@code [text](<url>)}) — without this normalization the dedup
     * logic in {@link #buildImagePrefix} and {@link #buildDownloadSuffix}
     * compared {@code "<screenshot-1776813474015.png>"} against the
     * collected {@code "screenshot-1776813474015.png"}, always missing,
     * so buildDownloadSuffix appended a duplicate download pill.
     */
    public static String extractFilename(String capturedUrl) {
        if (capturedUrl == null) return "";
        var url = capturedUrl;
        if (url.startsWith("<") && url.endsWith(">")) {
            url = url.substring(1, url.length() - 1);
        }
        var slash = url.lastIndexOf('/');
        return slash >= 0 ? url.substring(slash + 1) : url;
    }

    /**
     * Build a trailing download-link block for collected images that the LLM
     * reply has not already linked to (JCLAW-104). Mirrors
     * {@link #buildImagePrefix} but in the opposite direction: prefix renders
     * the image inline at the top, suffix gives the user a clickable
     * "download" at the bottom.
     *
     * <p>Channel-aware — the suffix only fires for channels whose renderers
     * resolve relative URLs to clickable links. {@code web} does (its
     * frontend is same-origin with the workspace-file API); Telegram does
     * not (its HTML parser requires absolute URLs, silently dropping an
     * {@code href} that's relative, which would render "download Screenshot"
     * as plain text — strictly worse than no link because the real download
     * affordance on Telegram is the native save option on the uploaded
     * photo). We fall back to no-suffix for any non-web channel and let the
     * channel's native file-delivery path handle the download surface.
     *
     * <p>A collected image is suppressed when its filename appears in any
     * markdown link ({@code [text](url)} or {@code ![alt](url)}) or HTML
     * anchor/image embed inside the LLM reply, so the suffix never doubles
     * up on a link the LLM already wrote out itself. Filenames in JClaw are
     * timestamp-suffixed so collisions are effectively impossible.
     *
     * <p>Link text is derived from the alt in the collected image markdown
     * — {@code ![Screenshot](url)} becomes {@code [download Screenshot](url)}.
     * Falls back to {@code [download](url)} when the collected entry has no
     * recoverable alt text.
     *
     * <p>Exposed for unit tests; not part of the public runner API.
     */
    public static String buildDownloadSuffix(List<String> collectedImages,
                                             String content,
                                             String channelType) {
        if (collectedImages == null || collectedImages.isEmpty()) return "";
        // Only the web frontend renders relative markdown links as clickable.
        // Telegram / Slack / WhatsApp / etc. need absolute URLs for <a href>,
        // and our workspace-file URLs are relative by design — rendering a
        // non-clickable "download" caption would be confusing noise.
        if (!"web".equalsIgnoreCase(channelType)) return "";
        var safeContent = content != null ? content : "";

        // Filenames already linked in the reply (covers both markdown forms
        // — image and plain link — plus HTML <img> and <a href>). If the
        // LLM already wrote a link to a file, we leave that as the user's
        // download affordance and skip our suffix entry for that file.
        var linkedFilenames = new HashSet<String>();
        for (var pattern : List.of(MARKDOWN_LINK_OR_IMAGE, HTML_IMG_EMBED, HTML_ANCHOR)) {
            var m = pattern.matcher(safeContent);
            while (m.find()) {
                var fn = extractFilename(m.group(1));
                if (!fn.isEmpty()) linkedFilenames.add(fn);
            }
        }

        var downloads = new ArrayList<String>();
        for (var img : collectedImages) {
            // img is "![alt](url)" shape. Extract url + alt for the link.
            var urlMatcher = PAREN_URL_PATTERN.matcher(img);
            if (!urlMatcher.find()) continue;
            var url = urlMatcher.group(1);
            var filename = extractFilename(url);
            if (!filename.isEmpty() && linkedFilenames.contains(filename)) continue;

            // Pull alt text out of the leading "![alt]" portion; fall back
            // to an empty alt (= just "download" as the link label) if the
            // pattern doesn't match (shouldn't happen for well-formed
            // collected entries but guarded for safety).
            var altMatcher = ALT_TEXT_PATTERN.matcher(img);
            var alt = altMatcher.find() ? altMatcher.group(1).trim() : "";
            var label = alt.isEmpty() ? "download" : "download " + alt;
            downloads.add("[" + label + "](" + url + ")");
        }
        return downloads.isEmpty() ? "" : "\n\n" + String.join("\n\n", downloads);
    }
}
