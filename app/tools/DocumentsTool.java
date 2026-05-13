package tools;

import agents.ToolRegistry;
import com.google.gson.JsonParser;
import models.Agent;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.ocr.TesseractOCRConfig;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.xml.sax.SAXException;
import services.AgentService;
import services.DocumentWriter;
import services.OcrHealthProbe;
import utils.TikaHolder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tool for reading and writing rich document formats. Reading uses Apache
 * Tika's {@link AutoDetectParser} to extract text from PDF, DOCX, DOC, XLSX,
 * PPTX, RTF, ODT, HTML, EPUB and more. Writing currently supports HTML, PDF,
 * and DOCX from markdown input via {@link services.DocumentWriter}; XLSX and
 * PPTX are tracked for a later iteration that takes structured input.
 */
public class DocumentsTool implements ToolRegistry.Tool {

    private static final long MAX_DOCUMENT_READ_BYTES = 25L * 1024 * 1024;
    private static final int MAX_DOCUMENT_TEXT_CHARS = 2_000_000;
    private static final int MAX_WRITE_MARKDOWN_CHARS = 500_000;
    private static final List<String> WRITE_FORMATS = List.of("html", "pdf", "docx");

    @Override
    public String name() { return "documents"; }

    @Override
    public String category() { return "Files"; }

    @Override
    public String icon() { return "document"; }

    @Override
    public String shortDescription() {
        return "Read and author rich formats — PDF, DOCX, HTML, XLSX, PPTX, EPUB — from markdown.";
    }

    @Override
    public java.util.List<agents.ToolAction> actions() {
        return java.util.List.of(
                new agents.ToolAction("readDocument",   "Extract text from PDF, DOCX, XLSX, PPTX, HTML, RTF, ODT, EPUB via Apache Tika"),
                new agents.ToolAction("writeDocument",  "Author a new HTML, PDF, or DOCX file from markdown input"),
                new agents.ToolAction("appendDocument", "Append markdown to a draft file for incremental large-document authoring"),
                new agents.ToolAction("renderDocument", "Convert an accumulated markdown draft into the target output format")
        );
    }

    @Override
    public String description() {
        return """
                Read and write rich document formats. This is a single tool with an 'action' parameter. \
                Use action="readDocument" to extract text from PDF, DOCX, DOC, XLSX, PPTX, RTF, ODT, HTML, EPUB and more via Apache Tika. \
                Use action="writeDocument" to author HTML, PDF, or DOCX from markdown input (markdown is the expected 'content' format). \
                Use action="appendDocument" to append markdown to a draft file in the workspace — use this for large documents that would exceed your output token budget in a single call. Call with action="appendDocument" repeatedly with the SAME path (use a .md extension, NOT .docx/.pdf — appending text to a binary format is not supported), then call with action="renderDocument" to convert the accumulated draft to the target format. \
                Use action="renderDocument" to read an existing markdown file from the workspace (via 'sourcePath') and render it to HTML, PDF, or DOCX. \
                Supports headings, paragraphs, bold/italic/strikethrough, inline and fenced code, bullet and ordered lists, block quotes, tables, and horizontal rules. \
                All paths are relative to the agent's workspace. \
                If the target path already exists, the write and render actions pick a non-conflicting name by appending -1, -2, etc. before the extension — the actual written path is reported in the response so you can reference the correct filename in your reply to the user. \
                XLSX and PPTX authoring is not yet supported.""";
    }

    @Override
    public String summary() {
        return "Read and write rich document formats (PDF, DOCX, HTML) via the 'action' parameter: readDocument, writeDocument, appendDocument, renderDocument.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "action", Map.of("type", "string",
                                "enum", List.of("readDocument", "writeDocument", "appendDocument", "renderDocument"),
                                "description", "The document operation to perform"),
                        "path", Map.of("type", "string",
                                "description", "File path relative to the agent workspace (target for writeDocument/renderDocument, source for readDocument)"),
                        "sourcePath", Map.of("type", "string",
                                "description", "For renderDocument only: workspace-relative path to an existing markdown file whose contents should be rendered to 'path'."),
                        "content", Map.of("type", "string",
                                "description", "Markdown content (for writeDocument and appendDocument)"),
                        "format", Map.of("type", "string",
                                "enum", WRITE_FORMATS,
                                "description", "Output format for writeDocument/renderDocument: html, pdf, or docx. If omitted, inferred from the target path extension.")
                ),
                "required", List.of("action", "path")
        );
    }

    @Override
    public String execute(String argsJson, Agent agent) {
        var args = JsonParser.parseString(argsJson).getAsJsonObject();
        var action = args.get("action").getAsString();
        var relativePath = args.get("path").getAsString();

        Path target;
        try {
            target = AgentService.acquireWorkspacePath(agent.name, relativePath);
        } catch (SecurityException e) {
            return "Error: Path '%s' escapes the workspace directory.".formatted(relativePath);
        }

        return switch (action) {
            case "readDocument" -> readDocument(target);
            case "appendDocument", "appendFile" -> {
                var content = args.has("content") && !args.get("content").isJsonNull()
                        ? args.get("content").getAsString() : "";
                yield appendDocument(target, relativePath, content);
            }
            case "writeDocument" -> {
                var content = args.has("content") && !args.get("content").isJsonNull()
                        ? args.get("content").getAsString() : "";
                var format = args.has("format") && !args.get("format").isJsonNull()
                        ? args.get("format").getAsString() : null;
                yield writeDocument(target, relativePath, content, format);
            }
            case "renderDocument" -> {
                if (!args.has("sourcePath") || args.get("sourcePath").isJsonNull()) {
                    yield "Error: renderDocument requires 'sourcePath' (workspace-relative markdown file to render).";
                }
                var sourceRelative = args.get("sourcePath").getAsString();
                Path source;
                try {
                    source = AgentService.acquireWorkspacePath(agent.name, sourceRelative);
                } catch (SecurityException e) {
                    yield "Error: sourcePath '%s' escapes the workspace directory.".formatted(sourceRelative);
                }
                if (!Files.exists(source)) {
                    yield "Error: sourcePath not found: %s".formatted(sourceRelative);
                }
                String content;
                try {
                    content = Files.readString(source);
                } catch (IOException e) {
                    yield "Error reading sourcePath: %s".formatted(e.getMessage());
                }
                var format = args.has("format") && !args.get("format").isJsonNull()
                        ? args.get("format").getAsString() : null;
                yield writeDocument(target, relativePath, content, format);
            }
            default -> "Error: Unknown action '%s'".formatted(action);
        };
    }

    private String writeDocument(Path target, String relativePath, String content, String format) {
        if (content == null || content.isEmpty()) {
            return "Error: writeDocument requires 'content' (markdown).";
        }
        if (content.length() > MAX_WRITE_MARKDOWN_CHARS) {
            return "Error: Markdown content exceeds write limit (%d chars). Content length: %d chars."
                    .formatted(MAX_WRITE_MARKDOWN_CHARS, content.length());
        }

        var resolved = resolveFormat(format, relativePath);
        if (resolved == null) {
            return "Error: Could not determine output format. Provide 'format' (html, pdf, or docx) or use a matching path extension.";
        }
        if (!WRITE_FORMATS.contains(resolved)) {
            return "Error: Unsupported format '%s'. Supported: %s."
                    .formatted(resolved, String.join(", ", WRITE_FORMATS));
        }

        // Avoid clobbering existing files. If the target exists we pick the next
        // free " (N).ext" slot in the same parent directory. Both the filesystem
        // target and the relativePath string we report back must be updated so
        // the markdown download link the LLM echoes into chat actually resolves
        // to the file we just wrote.
        var finalTarget = resolveNonConflicting(target);
        if (!finalTarget.equals(target)) {
            relativePath = replaceFinalSegment(relativePath, finalTarget.getFileName().toString());
            target = finalTarget;
        }

        try {
            switch (resolved) {
                case "html" -> DocumentWriter.writeHtml(target, content);
                case "pdf" -> DocumentWriter.writePdf(target, content);
                case "docx" -> DocumentWriter.writeDocx(target, content);
                default -> throw new IllegalStateException(
                        "unreachable: resolved was validated against WRITE_FORMATS: " + resolved);
            }
            long size = Files.size(target);
            var fileName = target.getFileName().toString();
            return ("Document written: %s (%s, %d bytes). "
                    + "IMPORTANT: in your reply to the user, include this exact markdown link so they can download the file: [%s](%s)")
                    .formatted(relativePath, resolved, size, fileName, relativePath);
        } catch (IOException e) {
            return "Error writing document: %s".formatted(e.getMessage());
        } catch (RuntimeException e) {
            return "Error rendering document: %s".formatted(e.getMessage());
        }
    }

    private static final List<String> BINARY_EXTENSIONS = List.of("docx", "pdf", "xlsx", "pptx");

    private String appendDocument(Path target, String relativePath, String content) {
        if (content == null || content.isEmpty()) {
            return "Error: appendDocument requires 'content' (markdown to append).";
        }
        // Reject binary-format targets. The LLM sometimes tries to append text
        // directly to a .docx/.pdf path — that produces a corrupt binary. Point
        // it at a .md draft file instead.
        int dot = relativePath.lastIndexOf('.');
        if (dot >= 0) {
            var ext = relativePath.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (BINARY_EXTENSIONS.contains(ext)) {
                var stem = relativePath.substring(0, dot);
                return ("Error: Cannot append text to a binary format (.%s). "
                        + "Use a .md draft file instead: appendDocument(path=\"%s.md\", content=...), "
                        + "then renderDocument(sourcePath=\"%s.md\", path=\"%s\") to produce the final .%s.")
                        .formatted(ext, stem, stem, relativePath, ext);
            }
        }
        try {
            Files.createDirectories(target.getParent());
            if (Files.exists(target)) {
                Files.writeString(target, content,
                        java.nio.file.StandardOpenOption.APPEND);
                long size = Files.size(target);
                return "Appended %d chars to %s (total %d bytes). Call renderDocument when all chunks are added."
                        .formatted(content.length(), relativePath, size);
            }
            Files.writeString(target, content);
            return "Draft created: %s (%d chars). Use appendDocument for more chunks, then renderDocument to produce the final document."
                    .formatted(relativePath, content.length());
        } catch (IOException e) {
            return "Error appending to draft: %s".formatted(e.getMessage());
        }
    }

    /**
     * Return {@code desired} if no file sits at that path, otherwise the first
     * " (N)" sibling that's free. Public so {@code DocumentsToolTest} in the
     * default test package can exercise it against a tmp dir without going
     * through Agent/workspace plumbing. The loop cap of 1000 is arbitrarily
     * high — any legitimate workspace will land well before it, and an
     * uncapped loop is a footgun if something else is racing writes into the
     * same directory.
     */
    public static Path resolveNonConflicting(Path desired) {
        if (!Files.exists(desired)) return desired;
        var parent = desired.getParent();
        var name = desired.getFileName().toString();
        int dot = name.lastIndexOf('.');
        var base = (dot <= 0) ? name : name.substring(0, dot);
        var ext = (dot <= 0) ? "" : name.substring(dot);
        for (int i = 1; i < 1000; i++) {
            var candidate = parent.resolve(base + "-" + i + ext);
            if (!Files.exists(candidate)) return candidate;
        }
        return desired;
    }

    /**
     * Replace the final path segment of a relative path string (slash-separated,
     * as produced by the LLM). Used to mirror the renamed target filename back
     * into the relativePath reported to the model and echoed into the download
     * link in chat. Workspaces are unix-style, so {@code /} is the canonical
     * separator; no Windows backslash handling is needed.
     */
    public static String replaceFinalSegment(String relativePath, String newFileName) {
        int slash = relativePath.lastIndexOf('/');
        return slash < 0 ? newFileName : relativePath.substring(0, slash + 1) + newFileName;
    }

    private static String resolveFormat(String explicit, String path) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit.toLowerCase(Locale.ROOT);
        }
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) return null;
        var ext = path.substring(dot + 1).toLowerCase(Locale.ROOT);
        return switch (ext) {
            case "htm", "html" -> "html";
            case "pdf" -> "pdf";
            case "docx" -> "docx";
            default -> null;
        };
    }

    /**
     * Extract text from a rich document. Returns partial text when the per-file
     * character cap is reached, with a trailing truncation notice. Image-only
     * inputs and image-only PDFs go through Tika's TesseractOCRParser; when
     * that parser returns blank because tesseract isn't on PATH, the response
     * names the missing dependency rather than silently saying "no text"
     * (JCLAW-177). Public + static so {@code DocumentsToolTest} can drive the
     * Tika path without going through Agent/workspace plumbing.
     */
    // Nested try/catch (S1141) intentional: the inner try-with-resources lets us
    // distinguish Tika's WriteLimitReached (a SAXException subclass that signals
    // successful truncation, not failure) from other SAX failures, while the outer
    // catch handles the orthogonal TikaException + IOException paths.
    @SuppressWarnings("java:S1141")
    public static String readDocument(Path path) {
        try {
            if (!Files.exists(path)) return "Error: File not found: %s".formatted(path.getFileName());
            var size = Files.size(path);
            if (size > MAX_DOCUMENT_READ_BYTES) {
                return "Error: Document exceeds read limit (%d bytes). File size: %d bytes."
                        .formatted(MAX_DOCUMENT_READ_BYTES, size);
            }

            var parser = TikaHolder.PARSER;
            var handler = new BodyContentHandler(MAX_DOCUMENT_TEXT_CHARS);
            var metadata = new Metadata();
            boolean ocrActive = ocrEnabled();
            boolean truncated = false;
            try (InputStream in = Files.newInputStream(path)) {
                parser.parse(in, handler, metadata, buildParseContext(ocrActive));
            } catch (SAXException e) {
                if (!e.getClass().getSimpleName().contains("WriteLimitReached")) {
                    return "Error parsing document: %s".formatted(e.getMessage());
                }
                truncated = true;
            }
            var text = handler.toString();
            if (text.isBlank()) {
                if (!ocrActive) {
                    return "Error: OCR is disabled in Settings, so '"
                            + path.getFileName() + "' (no extractable text layer) "
                            + "could not be read. Re-enable OCR at Settings → OCR "
                            + "to read scanned PDFs and image-only documents.";
                }
                var hint = ocrUnavailableHint();
                return "(Document parsed but contained no extractable text: "
                        + path.getFileName() + ")"
                        + (hint == null ? "" : " " + hint);
            }
            if (truncated) {
                text += "\n\n[... truncated at " + MAX_DOCUMENT_TEXT_CHARS + " characters ...]";
            }
            return text;
        } catch (TikaException e) {
            return "Error parsing document: %s".formatted(e.getMessage());
        } catch (IOException e) {
            return "Error reading document: %s".formatted(e.getMessage());
        }
    }

    /**
     * Build a {@link ParseContext} configured for the current OCR toggle.
     * When {@code ocrActive} is true, pre-loads TesseractOCRConfig and
     * PDFParserConfig so image inputs (and image-only PDFs) reach Tesseract,
     * with tunables from application.conf. When false, explicitly opts out:
     * Tika's AutoDetectParser would otherwise invoke TesseractOCRParser by
     * default whenever the binary is on PATH, ignoring an empty ParseContext.
     * Each parse rebuilds the context so live config + toggle edits apply on
     * the next call.
     */
    private static ParseContext buildParseContext(boolean ocrActive) {
        var ctx = new ParseContext();

        var ocr = new TesseractOCRConfig();
        if (ocrActive) {
            ocr.setLanguage(stringOrDefault("ocr.tesseract.languages", "eng"));
            ocr.setTimeoutSeconds(positiveIntOrDefault("ocr.tesseract.timeout", 60));
        }
        ocr.setSkipOcr(!ocrActive);
        ctx.set(TesseractOCRConfig.class, ocr);

        var pdf = new PDFParserConfig();
        pdf.setOcrStrategy(ocrActive
                ? parsePdfStrategy(stringOrDefault("ocr.pdf.strategy", "auto"))
                : PDFParserConfig.OCR_STRATEGY.NO_OCR);
        pdf.setExtractInlineImages(ocrActive);
        ctx.set(PDFParserConfig.class, pdf);

        return ctx;
    }

    private static String stringOrDefault(String key, String fallback) {
        var raw = play.Play.configuration != null
                ? play.Play.configuration.getProperty(key) : null;
        return (raw == null || raw.isBlank()) ? fallback : raw.trim();
    }

    private static int positiveIntOrDefault(String key, int fallback) {
        var raw = play.Play.configuration != null
                ? play.Play.configuration.getProperty(key) : null;
        if (raw == null || raw.isBlank()) return fallback;
        try {
            int n = Integer.parseInt(raw.trim());
            return n > 0 ? n : fallback;
        } catch (NumberFormatException _) {
            return fallback;
        }
    }

    private static PDFParserConfig.OCR_STRATEGY parsePdfStrategy(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "no_ocr" -> PDFParserConfig.OCR_STRATEGY.NO_OCR;
            case "ocr_only" -> PDFParserConfig.OCR_STRATEGY.OCR_ONLY;
            case "ocr_and_text_extraction" -> PDFParserConfig.OCR_STRATEGY.OCR_AND_TEXT_EXTRACTION;
            default -> PDFParserConfig.OCR_STRATEGY.AUTO;
        };
    }

    /**
     * The user-facing kill switch for the Tesseract OCR backend, surfaced as
     * the toggle in Settings → OCR. Reads the Config DB on every parse so a
     * UI-driven flip applies on the next call without a restart. Defaults to
     * true so a fresh install with no row seeded is still functional.
     */
    private static boolean ocrEnabled() {
        return "true".equalsIgnoreCase(
                services.ConfigService.get("ocr.tesseract.enabled", "true"));
    }

    /**
     * Build an actionable error fragment when Tika returns empty text and the
     * tesseract binary probe came back unavailable. Returns {@code null} when
     * the probe says tesseract is fine — in that case an empty extraction is
     * a real "no text in this document" result and shouldn't be muddied with
     * a misleading install hint.
     */
    private static String ocrUnavailableHint() {
        var probe = OcrHealthProbe.lastResult();
        if (probe.available()) return null;
        return "Note: tesseract is unavailable (" + probe.reason() + "). "
                + "OCR-dependent inputs (image-only PDFs, plain images, scanned documents) "
                + "require tesseract on PATH. Install: brew install tesseract (macOS), "
                + "apt-get install tesseract-ocr (Debian/Ubuntu).";
    }
}
