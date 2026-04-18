import org.junit.jupiter.api.*;
import play.test.*;
import agents.AgentRunner;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for {@link AgentRunner#buildImagePrefix} and
 * {@link AgentRunner#extractImageUrls}, the tool-result image dedup pair.
 *
 * <p>Guards the contract that lets {@code PlaywrightBrowserTool.screenshot()}
 * and {@code ShellExecTool}'s QR-code renderer show images exactly once in the
 * chat — the runner prepends rendered images to the assistant message, but
 * only if the LLM reply doesn't already reference them by filename.
 */
public class AgentRunnerDedupTest extends UnitTest {

    // ==================== extractImageUrls ====================

    @Test
    public void extractImageUrlsPicksUpScreenshotMarkdown() {
        var collected = new ArrayList<String>();
        AgentRunner.extractImageUrls(
                "![Screenshot](/api/agents/1/files/screenshot-1713100000000.png)\n"
                        + "[Screenshot captured and displayed above...]",
                collected);
        assertEquals(1, collected.size());
        assertEquals("![Screenshot](/api/agents/1/files/screenshot-1713100000000.png)",
                collected.getFirst());
    }

    @Test
    public void extractImageUrlsPicksUpMultipleMarkdownImages() {
        var collected = new ArrayList<String>();
        AgentRunner.extractImageUrls(
                "Here: ![QR Code](/api/agents/1/files/terminal-image-1.png) "
                        + "and also ![Another](/api/agents/1/files/terminal-image-2.png)",
                collected);
        assertEquals(2, collected.size());
    }

    @Test
    public void extractImageUrlsIgnoresNonApiMarkdownImages() {
        // The regex is intentionally limited to /api/ URLs — external images
        // the LLM references should not be collected as "rendered by a tool."
        var collected = new ArrayList<String>();
        AgentRunner.extractImageUrls(
                "![External](https://example.com/image.png)",
                collected);
        assertEquals(0, collected.size());
    }

    @Test
    public void extractImageUrlsHandlesNullAndEmpty() {
        var collected = new ArrayList<String>();
        AgentRunner.extractImageUrls(null, collected);
        AgentRunner.extractImageUrls("", collected);
        AgentRunner.extractImageUrls("no markdown here", collected);
        assertEquals(0, collected.size());
    }

    // ==================== buildImagePrefix: dedup paths ====================

    @Test
    public void dedupsWhenUrlIsExactMatch() {
        // The LLM echoed the exact same markdown image tag — no prefix needed.
        List<String> collected = List.of(
                "![Screenshot](/api/agents/1/files/screenshot-1000.png)");
        var content = "Here's the screenshot: ![Screenshot](/api/agents/1/files/screenshot-1000.png)";
        assertEquals("", AgentRunner.buildImagePrefix(collected, content));
    }

    @Test
    public void dedupsWhenOnlyFilenameMatches() {
        // The LLM rewrote the URL (e.g., stripped the /api/agents/1/files/ prefix)
        // but kept the filename. Filename-based dedup must still catch this.
        List<String> collected = List.of(
                "![Screenshot](/api/agents/1/files/screenshot-1000.png)");
        var content = "Here's the screenshot: ![Screenshot](./workspace/screenshot-1000.png)";
        assertEquals("", AgentRunner.buildImagePrefix(collected, content),
                "Dedup should catch filename match even when path differs");
    }

    @Test
    public void prependsWhenFilenameIsMentionedAsPlainTextOnly() {
        // Intentional behavior change: a plain-text mention of the filename is
        // NOT an image embed — the user expects both the inline image AND the
        // textual link reference, so the prepend still fires. Previously this
        // case suppressed the prepend under a filename-substring dedup, which
        // conflicted with the PlaywrightBrowserTool guidance that now asks the
        // LLM to include the screenshot URL as a plain link in its reply.
        List<String> collected = List.of(
                "![Screenshot](/api/agents/1/files/screenshot-1000.png)");
        var content = "I saved the screenshot as screenshot-1000.png in your workspace.";
        var prefix = AgentRunner.buildImagePrefix(collected, content);
        assertTrue(prefix.contains("![Screenshot](/api/agents/1/files/screenshot-1000.png)"),
                "Plain-text filename mention must NOT suppress the inline-image prepend");
    }

    @Test
    public void prependsWhenUrlIsMentionedAsMarkdownLinkOnly() {
        // A markdown link (not an image embed) — same rule as plain text.
        // User sees both: the prepended inline image AND the LLM's clickable link.
        List<String> collected = List.of(
                "![Screenshot](/api/agents/1/files/screenshot-1000.png)");
        var content = "Captured: [screenshot](/api/agents/1/files/screenshot-1000.png)";
        var prefix = AgentRunner.buildImagePrefix(collected, content);
        // Stricter than a bare filename substring: the prefix MUST carry a
        // well-formed image embed so the user actually sees an inline image,
        // not just a text fragment that happens to contain the filename.
        assertTrue(prefix.contains("![Screenshot](/api/agents/1/files/screenshot-1000.png)"),
                "Regular markdown link must NOT suppress the inline-image prepend");
    }

    @Test
    public void dedupsWhenLlmReembedsAsHtmlImgTag() {
        // LLMs occasionally emit HTML <img src="..."> instead of markdown.
        // The dedup must catch both forms — otherwise the prepend fires AND the
        // LLM's HTML <img> renders, producing a duplicate.
        List<String> collected = List.of(
                "![Screenshot](/api/agents/1/files/screenshot-1000.png)");
        var content = "Here: <img src=\"/api/agents/1/files/screenshot-1000.png\" alt=\"\">";
        assertEquals("", AgentRunner.buildImagePrefix(collected, content),
                "HTML <img> re-embed must suppress the prepend");
    }

    @Test
    public void dedupsWhenLlmReembedsAsHtmlImgTagWithSingleQuotes() {
        // Single-quoted src attribute — same contract.
        List<String> collected = List.of(
                "![Screenshot](/api/agents/1/files/screenshot-1000.png)");
        var content = "<img alt='shot' src='./workspace/screenshot-1000.png'>";
        assertEquals("", AgentRunner.buildImagePrefix(collected, content),
                "HTML <img> with single-quoted src must also suppress the prepend");
    }

    // ==================== buildImagePrefix: prepend paths ====================

    @Test
    public void prependsWhenFilenameDiffers() {
        // LLM hallucinated a different filename — prepend the correct one so
        // the user still sees the image.
        List<String> collected = List.of(
                "![Screenshot](/api/agents/1/files/screenshot-1000.png)");
        var content = "Here's the screenshot: ![Screenshot](/api/agents/1/files/screenshot-9999.png)";
        var prefix = AgentRunner.buildImagePrefix(collected, content);
        assertTrue(prefix.contains("![Screenshot](/api/agents/1/files/screenshot-1000.png)"),
                "Prefix must include the collected image when the LLM referenced a different filename");
        assertTrue(prefix.endsWith("\n\n"),
                "Prefix must be separated from the LLM content by a blank line");
    }

    @Test
    public void prependsWhenContentIsEmpty() {
        // LLM returned no text at all — the image is the entire assistant message.
        List<String> collected = List.of(
                "![Screenshot](/api/agents/1/files/screenshot-1000.png)");
        var prefix = AgentRunner.buildImagePrefix(collected, "");
        assertTrue(prefix.contains("screenshot-1000.png"));
    }

    @Test
    public void prependsWhenContentIsNull() {
        // Defensive: null content must not throw and must produce the prefix.
        List<String> collected = List.of(
                "![Screenshot](/api/agents/1/files/screenshot-1000.png)");
        var prefix = AgentRunner.buildImagePrefix(collected, null);
        assertTrue(prefix.contains("screenshot-1000.png"));
    }

    @Test
    public void returnsEmptyWhenCollectedListIsEmpty() {
        assertEquals("", AgentRunner.buildImagePrefix(List.of(), "some content"));
        assertEquals("", AgentRunner.buildImagePrefix(null, "some content"));
    }

    @Test
    public void prependsOnlyTheMissingImagesFromAMixedList() {
        // One image is referenced by the LLM (should be skipped) and another is not
        // (should be prepended).
        List<String> collected = List.of(
                "![Screenshot](/api/agents/1/files/screenshot-A.png)",
                "![QR](/api/agents/1/files/terminal-image-B.png)");
        var content = "Here's the screenshot: ![Screenshot](/api/agents/1/files/screenshot-A.png). "
                + "Then I printed a code.";
        var prefix = AgentRunner.buildImagePrefix(collected, content);
        assertFalse(prefix.contains("screenshot-A.png"),
                "screenshot-A was referenced in the content — should not be prepended");
        assertTrue(prefix.contains("terminal-image-B.png"),
                "terminal-image-B was not referenced — should be prepended");
    }
}
