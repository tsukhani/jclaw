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
    public void dedupsWhenFilenameIsPlainTextMentioned() {
        // Even a plain-text mention of the filename counts as "already referenced"
        // — the assistant has acknowledged the file, so we don't need to also
        // prepend a rendered copy.
        List<String> collected = List.of(
                "![Screenshot](/api/agents/1/files/screenshot-1000.png)");
        var content = "I saved the screenshot as screenshot-1000.png in your workspace.";
        assertEquals("", AgentRunner.buildImagePrefix(collected, content));
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
