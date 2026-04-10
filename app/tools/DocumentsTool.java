package tools;

import agents.ToolRegistry;
import com.google.gson.JsonParser;
import models.Agent;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.xml.sax.SAXException;
import services.AgentService;
import services.DocumentWriter;

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
    public String description() {
        return """
                Read and write rich document formats. \
                readDocument extracts text from PDF, DOCX, DOC, XLSX, PPTX, RTF, ODT, HTML, EPUB and more via Apache Tika. \
                writeDocument authors HTML, PDF, or DOCX from markdown input (markdown is the expected 'content' format). \
                Supports headings, paragraphs, bold/italic/strikethrough, inline and fenced code, bullet and ordered lists, block quotes, tables, and horizontal rules. \
                All paths are relative to the agent's workspace. \
                XLSX and PPTX authoring is not yet supported.""";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "action", Map.of("type", "string",
                                "enum", List.of("readDocument", "writeDocument"),
                                "description", "The document operation to perform"),
                        "path", Map.of("type", "string",
                                "description", "File path relative to the agent workspace"),
                        "content", Map.of("type", "string",
                                "description", "Markdown content to render (writeDocument only)"),
                        "format", Map.of("type", "string",
                                "enum", WRITE_FORMATS,
                                "description", "Output format for writeDocument: html, pdf, or docx. If omitted, inferred from the path extension.")
                ),
                "required", List.of("action", "path")
        );
    }

    @Override
    public String execute(String argsJson, Agent agent) {
        var args = JsonParser.parseString(argsJson).getAsJsonObject();
        var action = args.get("action").getAsString();
        var relativePath = args.get("path").getAsString();

        var target = AgentService.resolveWorkspacePath(agent.name, relativePath);
        if (target == null) {
            return "Error: Path '%s' escapes the workspace directory.".formatted(relativePath);
        }

        return switch (action) {
            case "readDocument" -> readDocument(target);
            case "writeDocument" -> {
                var content = args.has("content") && !args.get("content").isJsonNull()
                        ? args.get("content").getAsString() : "";
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

        try {
            switch (resolved) {
                case "html" -> DocumentWriter.writeHtml(target, content);
                case "pdf" -> DocumentWriter.writePdf(target, content);
                case "docx" -> DocumentWriter.writeDocx(target, content);
            }
            long size = Files.size(target);
            var fileName = target.getFileName().toString();
            return ("Document written: %s (%s, %d bytes). "
                    + "IMPORTANT: in your reply to the user, include this markdown link so they can download the file directly from chat: [%s](%s)")
                    .formatted(relativePath, resolved, size, fileName, relativePath);
        } catch (IOException e) {
            return "Error writing document: %s".formatted(e.getMessage());
        } catch (RuntimeException e) {
            return "Error rendering document: %s".formatted(e.getMessage());
        }
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
     * character cap is reached, with a trailing truncation notice.
     */
    private String readDocument(Path path) {
        try {
            if (!Files.exists(path)) return "Error: File not found: %s".formatted(path.getFileName());
            var size = Files.size(path);
            if (size > MAX_DOCUMENT_READ_BYTES) {
                return "Error: Document exceeds read limit (%d bytes). File size: %d bytes."
                        .formatted(MAX_DOCUMENT_READ_BYTES, size);
            }

            var parser = new AutoDetectParser();
            var handler = new BodyContentHandler(MAX_DOCUMENT_TEXT_CHARS);
            var metadata = new Metadata();
            boolean truncated = false;
            try (InputStream in = Files.newInputStream(path)) {
                parser.parse(in, handler, metadata, new ParseContext());
            } catch (SAXException e) {
                if (!e.getClass().getSimpleName().contains("WriteLimitReached")) {
                    return "Error parsing document: %s".formatted(e.getMessage());
                }
                truncated = true;
            }
            var text = handler.toString();
            if (text.isBlank()) {
                return "(Document parsed but contained no extractable text: "
                        + path.getFileName() + ")";
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
}
