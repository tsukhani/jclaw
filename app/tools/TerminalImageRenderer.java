package tools;

import models.Agent;
import services.AgentService;
import services.EventLogger;

import javax.imageio.ImageIO;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders terminal block-art — QR codes and other Unicode half-block images a
 * command prints to the terminal — into PNG files and rewrites the command
 * output to reference them as markdown image links.
 *
 * <p>Extracted from {@link ShellExecTool} (JCLAW-736): rendering half-block art
 * with Java2D is an image-processing concern independent of running a shell
 * command. {@code ShellExecTool} keeps only the streaming detector that decides
 * when a run has emitted enough block-art to short-circuit, delegating the
 * "is this a block-art line?" predicate ({@link #isBlockArtLine}) and the
 * render ({@link #replaceTerminalImagesInOutput}) here.
 */
public final class TerminalImageRenderer {

    private TerminalImageRenderer() {}

    /** Consecutive block-art lines that qualify a region as a terminal image. */
    public static final int MIN_BLOCK_ART_LINES = 5;

    /**
     * Detect QR codes or other terminal-rendered images in command output,
     * render them as PNGs, and replace the block art in the output with
     * markdown image URLs that the LLM will include in its response.
     */
    public static String replaceTerminalImagesInOutput(String output, Agent agent) {
        var lines = output.split("\n");
        var result = new StringBuilder();
        var qrLines = new ArrayList<String>();

        for (var line : lines) {
            if (isBlockArtLine(line)) {
                qrLines.add(line);
            } else {
                flushQrBlock(qrLines, result, agent);
                result.append(line).append("\n");
            }
        }
        // Handle block art at end of output
        flushQrBlock(qrLines, result, agent);

        return result.toString().stripTrailing();
    }

    /**
     * Drain the accumulated block-art lines into {@code result}: render as a
     * PNG image link when the block is substantial enough ({@code >=
     * MIN_BLOCK_ART_LINES} lines), or emit verbatim when it's too small to be a
     * real terminal image. Either way the buffer ends empty so the caller can
     * start a new block on the next non-block line.
     */
    private static void flushQrBlock(ArrayList<String> qrLines,
                                     StringBuilder result, Agent agent) {
        if (qrLines.isEmpty()) return;
        if (qrLines.size() >= MIN_BLOCK_ART_LINES) {
            var imageUrl = renderBlockArtToPng(qrLines, agent);
            if (imageUrl != null) {
                result.append(imageUrl).append("\n");
            }
        } else {
            for (var ql : qrLines) result.append(ql).append("\n");
        }
        qrLines.clear();
    }

    /** True when {@code line} is dominated (&gt; 70%) by Unicode block glyphs. */
    public static boolean isBlockArtLine(String line) {
        if (line.length() <= 10) return false;
        long blockChars = line.chars().filter(c ->
                c == '█' || c == '▀' || c == '▄' || c == '▌' || c == '▐' ||
                c == '░' || c == '▒' || c == '▓' || c == '▊' || c == '▋' ||
                c == '▍' || c == '▎' || c == '▏' || c == ' '
        ).count();
        return blockChars > line.length() * 0.7;
    }

    /**
     * Render Unicode block art (QR code, etc.) to a PNG image using Java2D.
     *
     * Unicode half-block characters encode two vertical pixels per character:
     *   █ (U+2588) = top black, bottom black
     *   ▀ (U+2580) = top black, bottom white
     *   ▄ (U+2584) = top white, bottom black
     *   ' ' (space) = top white, bottom white
     *
     * Each character cell maps to cellSize x (cellSize*2) pixels to preserve the
     * 1:2 aspect ratio of half-block encoding.
     */
    private static String renderBlockArtToPng(List<String> lines, Agent agent) {
        try {
            int cellW = 8;  // pixels per character width
            int cellH = 8;  // pixels per HALF character height (each char = 2 vertical halves)
            int maxWidth = lines.stream().mapToInt(String::length).max().orElse(0);
            int imgWidth = maxWidth * cellW;
            int imgHeight = lines.size() * cellH * 2; // *2 because each char line = 2 pixel rows

            if (imgWidth <= 0 || imgHeight <= 0 || imgWidth > 8000 || imgHeight > 8000) return null;

            var img = new BufferedImage(imgWidth, imgHeight, BufferedImage.TYPE_INT_RGB);
            var g = img.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, imgWidth, imgHeight);

            paintBlockArt(g, lines, cellW, cellH);
            g.dispose();

            var timestamp = System.currentTimeMillis();
            var filename = "terminal-image-%d.png".formatted(timestamp);
            var path = AgentService.workspacePath(agent.name).resolve(filename);
            ImageIO.write(img, "PNG", path.toFile());

            var url = "/api/agents/%d/files/%s".formatted(agent.id, filename);
            // Use full markdown image syntax — this is a web URL for the chat UI, not a file path
            return "![QR Code](%s)".formatted(url);

        } catch (Exception e) {
            EventLogger.warn("tool", "Failed to render terminal image: %s".formatted(e.getMessage()));
            return null;
        }
    }

    /**
     * Paint the block-art {@code lines} into the {@link java.awt.Graphics2D}
     * context using {@code cellW × cellH} cells (each character is two
     * vertically-stacked half-cells per the Unicode half-block encoding).
     */
    private static void paintBlockArt(Graphics2D g, List<String> lines,
                                      int cellW, int cellH) {
        for (int row = 0; row < lines.size(); row++) {
            var line = lines.get(row);
            int py = row * cellH * 2; // pixel y for this character row
            for (int col = 0; col < line.length(); col++) {
                char c = line.charAt(col);
                int px = col * cellW;
                var halves = halfBlocksFor(c);
                if (halves.topBlack()) {
                    g.setColor(Color.BLACK);
                    g.fillRect(px, py, cellW, cellH);
                }
                if (halves.bottomBlack()) {
                    g.setColor(Color.BLACK);
                    g.fillRect(px, py + cellH, cellW, cellH);
                }
            }
        }
    }

    /** Encoded top/bottom-half occupancy for one half-block character. */
    private record HalfBlock(boolean topBlack, boolean bottomBlack) {
        private static final HalfBlock EMPTY = new HalfBlock(false, false);
        private static final HalfBlock FULL = new HalfBlock(true, true);
        private static final HalfBlock TOP = new HalfBlock(true, false);
        private static final HalfBlock BOTTOM = new HalfBlock(false, true);
    }

    private static HalfBlock halfBlocksFor(char c) {
        return switch (c) {
            // █ full block, ▊, ▋ — treat as full for QR
            case '█', '▊', '▋' -> HalfBlock.FULL;
            // ▀ upper half
            case '▀' -> HalfBlock.TOP;
            // ▄ lower half
            case '▄' -> HalfBlock.BOTTOM;
            // ▌ left half, ▐ right half — treat as full for QR
            case '▌', '▐' -> HalfBlock.FULL;
            // ▓ dark shade
            case '▓' -> HalfBlock.FULL;
            // ▒ medium shade, ░ light shade, literal space — treat as white
            case '▒', '░', ' ' -> HalfBlock.EMPTY;
            // Unknown char — treat as black if it's a block-range char
            default -> (c >= '▀' && c <= '▟') ? HalfBlock.FULL : HalfBlock.EMPTY;
        };
    }
}
