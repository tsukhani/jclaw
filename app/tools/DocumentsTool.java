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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Tool for reading and (eventually) writing rich document formats. Uses
 * Apache Tika's {@link AutoDetectParser} to extract text from PDF, DOCX, DOC,
 * XLSX, PPTX, RTF, ODT, HTML, EPUB and more. A future {@code writeDocument}
 * action will produce documents via Apache POI / PDFBox.
 */
public class DocumentsTool implements ToolRegistry.Tool {

    private static final long MAX_DOCUMENT_READ_BYTES = 25L * 1024 * 1024;
    private static final int MAX_DOCUMENT_TEXT_CHARS = 2_000_000;

    @Override
    public String name() { return "documents"; }

    @Override
    public String description() {
        return """
                Read and extract text from rich document formats: PDF, DOCX, DOC, \
                XLSX, PPTX, RTF, ODT, HTML, EPUB, and more. Uses Apache Tika for \
                parsing. Paths are relative to the agent's workspace. Use this \
                tool instead of filesystem.readFile for any non-plaintext document. \
                Future actions will include writeDocument for authoring documents.""";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "action", Map.of("type", "string",
                                "enum", List.of("readDocument"),
                                "description", "The document operation to perform"),
                        "path", Map.of("type", "string",
                                "description", "File path relative to the agent workspace")
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
            default -> "Error: Unknown action '%s'".formatted(action);
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
