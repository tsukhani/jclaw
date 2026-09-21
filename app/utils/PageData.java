package utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.QueryParser;
import org.jsoup.select.Selector;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Structured extraction from a fetched page (JCLAW-1271): named CSS-selector fields, and the
 * metadata a page publishes about itself. Both run on HTML only — a PDF or a markdown body has
 * no DOM to select from — and both are bounded, so one page cannot flood the result.
 */
public final class PageData {

    public static final int MAX_MATCHES_PER_FIELD = 100;
    public static final int MAX_VALUE_CHARS = 2_000;
    /** Per page, for the fields and the metadata each. */
    public static final int MAX_DATA_CHARS = 20_000;

    /** What follows the last {@code @} in a field spec, when it looks like an attribute name. */
    private static final Pattern ATTRIBUTE = Pattern.compile("[A-Za-z_:][-A-Za-z0-9_:.]*");

    private PageData() {}

    /** One field: a CSS selector and, when given, the attribute to read instead of the text. */
    public record Field(String selector, @Nullable String attribute) {

        /** {@code "a.next@href"} reads each match's {@code href}; {@code "h1"} reads its text. */
        public static Field parse(String spec) {
            var at = spec.lastIndexOf('@');
            if (at > 0 && ATTRIBUTE.matcher(spec.substring(at + 1)).matches()) {
                return new Field(spec.substring(0, at).strip(), spec.substring(at + 1));
            }
            return new Field(spec.strip(), null);
        }
    }

    /** What an extraction produced, and whether a bound cut it short. */
    public record Extracted(JsonObject json, boolean truncated) {}

    /** The first field whose selector does not parse, named with the parser's reason; null when all do. */
    public static @Nullable String invalidSelector(Map<String, Field> fields) {
        for (var entry : fields.entrySet()) {
            try {
                if (entry.getValue().selector().isEmpty()) {
                    return "field '%s' has an empty selector".formatted(entry.getKey());
                }
                QueryParser.parse(entry.getValue().selector());
            } catch (Selector.SelectorParseException e) {
                return "field '%s': %s".formatted(entry.getKey(), e.getMessage());
            }
        }
        return null;
    }

    /**
     * Each field's matches, in document order, as an array of strings: the element's text, or the
     * named attribute resolved to an absolute URL where it is one. A field that matches nothing is
     * an empty array, never an error — a selector for an optional part of a page is normal.
     */
    public static Extracted extract(WebExtraction.FetchResult fetched, Map<String, Field> fields) {
        var out = new JsonObject();
        var doc = document(fetched);
        var budget = new Budget();
        for (var entry : fields.entrySet()) {
            var values = new JsonArray();
            out.add(entry.getKey(), values);
            if (doc == null) continue;
            var field = entry.getValue();
            var matches = doc.select(field.selector());
            if (matches.size() > MAX_MATCHES_PER_FIELD) budget.truncated = true;
            for (var el : matches.subList(0, Math.min(matches.size(), MAX_MATCHES_PER_FIELD))) {
                var value = field.attribute() == null ? el.text() : attributeOf(el, field.attribute());
                if (!budget.admit(value)) break;
                values.add(budget.clip(value));
            }
        }
        return new Extracted(out, budget.truncated);
    }

    /**
     * The metadata a page states about itself: title, description, canonical URL, language,
     * OpenGraph and Twitter card properties, and every JSON-LD block that parses. JSON-LD is where
     * many product, article, recipe and event pages already publish their facts.
     */
    public static Extracted metadata(WebExtraction.FetchResult fetched) {
        var out = new JsonObject();
        var doc = document(fetched);
        if (doc == null) return new Extracted(out, false);
        var budget = new Budget();
        putIfPresent(out, "title", doc.title(), budget);
        putIfPresent(out, "description", doc.select("meta[name=description]").attr("content"), budget);
        putIfPresent(out, "canonical", doc.select("link[rel=canonical]").attr("abs:href"), budget);
        putIfPresent(out, "language", doc.select("html").attr("lang"), budget);
        out.add("openGraph", properties(doc, "meta[property^=og:]", "property", budget));
        out.add("twitter", properties(doc, "meta[name^=twitter:]", "name", budget));
        var jsonLd = new JsonArray();
        for (var script : doc.select("script[type=application/ld+json]")) {
            var raw = script.data().strip();
            // Kept whole rather than clipped, so it is charged whole.
            if (!budget.admitWhole(raw)) break;
            try {
                jsonLd.add(JsonParser.parseString(raw));
            } catch (RuntimeException _) {
                // Hand-written JSON-LD is often invalid; one bad block must not lose the rest.
            }
        }
        out.add("jsonLd", jsonLd);
        return new Extracted(out, budget.truncated);
    }

    private static JsonObject properties(Document doc, String selector, String keyAttr, Budget budget) {
        var out = new JsonObject();
        for (var meta : doc.select(selector)) {
            var key = meta.attr(keyAttr);
            var value = meta.attr("content");
            if (key.isEmpty() || value.isEmpty() || out.has(key)) continue;
            if (!budget.admit(value)) break;
            out.addProperty(key, budget.clip(value));
        }
        return out;
    }

    private static void putIfPresent(JsonObject out, String key, String value, Budget budget) {
        if (!value.isBlank() && budget.admit(value)) out.addProperty(key, budget.clip(value.strip()));
    }

    private static String attributeOf(Element el, String attribute) {
        var absolute = el.attr("abs:" + attribute);
        return absolute.isEmpty() ? el.attr(attribute) : absolute;
    }

    private static @Nullable Document document(WebExtraction.FetchResult fetched) {
        if (!WebExtraction.isHtml(fetched.contentType(), fetched.body())) return null;
        var html = new String(fetched.body(), WebExtraction.charsetFor(fetched.contentType()));
        return Jsoup.parse(html, fetched.finalUrl());
    }

    /** The per-page character allowance, and whether anything was refused or clipped against it. */
    private static final class Budget {
        int used;
        boolean truncated;

        /** Whether {@code value} still fits; once it does not, every later value is refused too. */
        boolean admit(String value) {
            if (truncated && used >= MAX_DATA_CHARS) return false;
            var cost = Math.min(value.length(), MAX_VALUE_CHARS);
            if (used + cost > MAX_DATA_CHARS) {
                truncated = true;
                used = MAX_DATA_CHARS;
                return false;
            }
            used += cost;
            return true;
        }

        boolean admitWhole(String value) {
            if (used + value.length() > MAX_DATA_CHARS) {
                truncated = true;
                return false;
            }
            used += value.length();
            return true;
        }

        String clip(String value) {
            if (value.length() <= MAX_VALUE_CHARS) return value;
            truncated = true;
            return value.substring(0, MAX_VALUE_CHARS) + "…";
        }
    }
}
