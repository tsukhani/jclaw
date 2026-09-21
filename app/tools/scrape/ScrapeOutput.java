package tools.scrape;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;
import services.scrape.ScrapeRung;
import tools.SchemaKeys;
import utils.PageData;
import utils.WebExtraction;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What {@code web_fetch} and {@code web_scrape} return beyond Markdown (JCLAW-1271): plain text,
 * a JSON record per page, named CSS-selector fields, and the metadata a page states about itself.
 *
 * <p>The rule an agent has to learn is one line: asking for {@code extract} or {@code metadata}
 * makes the result JSON, and {@code extract} replaces the page content with the fields — which is
 * what saves the tokens. {@code format} decides how any content that remains is rendered.
 */
public final class ScrapeOutput {

    public enum Format { MARKDOWN, TEXT, JSON, HTML }

    public static final String ARG_FORMAT = "format";
    public static final String ARG_EXTRACT = "extract";
    public static final String ARG_METADATA = "metadata";

    /** Links listed in a single-page JSON record; a crawl follows its links rather than listing them. */
    public static final int MAX_LINKS = 200;

    private ScrapeOutput() {}

    /** A call's output request, parsed and validated. */
    public record Request(Format format, Map<String, PageData.Field> extract, boolean metadata) {

        public static final Request MARKDOWN = new Request(Format.MARKDOWN, Map.of(), false);

        /** Structured results are JSON whatever the format asked for. */
        public boolean json() {
            return format == Format.JSON || !extract.isEmpty() || metadata;
        }

        /** Selectors need a DOM, so ask for HTML rather than the markdown rendering a site may offer. */
        public boolean needsHtml() {
            return format == Format.HTML || !extract.isEmpty() || metadata;
        }

        public boolean contentOmitted() {
            return !extract.isEmpty();
        }
    }

    /**
     * Read {@code format}, {@code extract} and {@code metadata} from a call's arguments.
     *
     * @param fallback the format when the call names none, which lets {@code web_fetch} honour its
     *                 older {@code mode} argument
     * @throws IllegalArgumentException with an agent-facing message naming the bad argument
     */
    public static Request parse(JsonObject args, boolean htmlAllowed, Format fallback) {
        var format = fallback;
        if (args.has(ARG_FORMAT) && !args.get(ARG_FORMAT).isJsonNull()) {
            var requested = args.get(ARG_FORMAT).getAsString();
            var parsed = formatNamed(requested);
            if (parsed == null || (parsed == Format.HTML && !htmlAllowed)) {
                throw new IllegalArgumentException("format must be one of " + formatNames(htmlAllowed)
                        + "; got '" + requested + "'");
            }
            format = parsed;
        }
        var extract = new LinkedHashMap<String, PageData.Field>();
        if (args.has(ARG_EXTRACT) && !args.get(ARG_EXTRACT).isJsonNull()) {
            if (!args.get(ARG_EXTRACT).isJsonObject()) {
                throw new IllegalArgumentException(
                        "extract must be an object mapping a field name to a CSS selector");
            }
            for (var entry : args.getAsJsonObject(ARG_EXTRACT).entrySet()) {
                if (!entry.getValue().isJsonPrimitive()) {
                    throw new IllegalArgumentException(
                            "extract field '%s' must be a CSS selector string".formatted(entry.getKey()));
                }
                extract.put(entry.getKey(), PageData.Field.parse(entry.getValue().getAsString()));
            }
            var invalid = PageData.invalidSelector(extract);
            if (invalid != null) throw new IllegalArgumentException("extract " + invalid);
        }
        boolean metadata = args.has(ARG_METADATA) && !args.get(ARG_METADATA).isJsonNull()
                && args.get(ARG_METADATA).getAsBoolean();
        // Not Map.copyOf, which is unordered: fields come back in the order the agent named them.
        return new Request(format, Collections.unmodifiableMap(extract), metadata);
    }

    /** One page as a JSON record. {@code text} is the Markdown the page extracted to. */
    public static JsonObject pageRecord(Request request, String url, WebExtraction.FetchResult fetched,
                                        String text, ScrapeRung servedBy, boolean withLinks) {
        var record = header(url, fetched.finalUrl(), servedBy);
        boolean truncated = false;
        if (!request.contentOmitted()) {
            record.addProperty("content", request.format() == Format.TEXT
                    ? WebExtraction.toPlain(fetched, text) : text);
        }
        if (!request.extract().isEmpty()) {
            var fields = PageData.extract(fetched, request.extract());
            record.add("fields", fields.json());
            truncated = fields.truncated();
        }
        if (request.metadata()) {
            var metadata = PageData.metadata(fetched);
            record.add("metadata", metadata.json());
            truncated |= metadata.truncated();
        }
        if (withLinks) {
            var links = new JsonArray();
            WebExtraction.links(fetched).stream().limit(MAX_LINKS).forEach(uri -> links.add(uri.toString()));
            record.add("links", links);
        }
        if (truncated) record.addProperty("truncated", true);
        return record;
    }

    /** A page that could not be read, as a record that says why. */
    public static JsonObject failedRecord(String url, ScrapeRung servedBy, String reason) {
        var record = header(url, url, servedBy);
        record.addProperty("error", reason);
        return record;
    }

    private static JsonObject header(String url, String finalUrl, ScrapeRung servedBy) {
        var record = new JsonObject();
        record.addProperty("url", url);
        if (!finalUrl.equals(url)) record.addProperty("finalUrl", finalUrl);
        record.addProperty("fetchedBy", servedBy.name().toLowerCase(Locale.ROOT));
        return record;
    }

    /** The three output parameters, for a tool's schema. */
    public static Map<String, Object> schema(boolean htmlAllowed) {
        return Map.of(
                ARG_FORMAT, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                        SchemaKeys.ENUM, formatNames(htmlAllowed),
                        SchemaKeys.DESCRIPTION, "How to return the content: 'markdown' (default), 'text' "
                                + "(no markup) or 'json' (a record per page)"
                                + (htmlAllowed ? "; 'html' only when the user asks for the raw page source" : "")),
                ARG_EXTRACT, Map.of(SchemaKeys.TYPE, SchemaKeys.OBJECT,
                        SchemaKeys.ADDITIONAL_PROPERTIES, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING),
                        SchemaKeys.DESCRIPTION, "Return only these values instead of the page content: "
                                + "field name to CSS selector, with @attr to read an attribute, e.g. "
                                + "{\"price\": \".price\", \"next\": \"a.next@href\"}. The result is JSON."),
                ARG_METADATA, Map.of(SchemaKeys.TYPE, SchemaKeys.BOOLEAN,
                        SchemaKeys.DESCRIPTION, "Include the page's own structured data (title, description, "
                                + "OpenGraph, JSON-LD). The result is JSON."));
    }

    private static @Nullable Format formatNamed(String name) {
        for (var format : Format.values()) {
            if (format.name().equalsIgnoreCase(name.strip())) return format;
        }
        return null;
    }

    private static List<String> formatNames(boolean htmlAllowed) {
        return htmlAllowed ? List.of("markdown", "text", "json", "html") : List.of("markdown", "text", "json");
    }
}
