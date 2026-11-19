package services.secrets;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import okhttp3.MediaType;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.StringReader;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The fields of an HTTP body as a server reads them, for {@link SecretCapture}: multipart parts, urlencoded fields and
 * the top-level fields of a JSON object.
 */
public final class BodyFields {

    private static final Pattern PART_NAME = Pattern.compile("(?i)(?:^|;)\\s*name\\s*=\\s*(?:\"([^\"]*)\"|([^;\\s]*))");

    private BodyFields() {}

    public static boolean multipart(MediaType media) {
        return media.type().equalsIgnoreCase("multipart") && media.subtype().equalsIgnoreCase("form-data");
    }

    /** Whether a content type MediaType cannot parse still names a multipart form. */
    public static boolean namesMultipart(String contentType) {
        return contentType.strip().toLowerCase(Locale.ROOT).startsWith("multipart/form-data");
    }

    /**
     * The parts of a multipart body, each its name and its content as Latin-1, as a lenient server reads them: a last
     * part with no closing delimiter runs to the end, and a head may end in a bare blank line. Null when the body has
     * no boundary or no delimiter to start at, or when a delimiter follows a bare LF, not CRLF: a strict server reads
     * that as content and a lenient one as a new part, so which field holds what cannot be told.
     */
    public static @Nullable List<Map.Entry<String, String>> multipartParts(byte[] body, MediaType media) {
        var boundary = media.parameter("boundary");
        if (boundary == null || boundary.isEmpty()) return null;
        var text = new String(body, StandardCharsets.ISO_8859_1);
        var delimiter = "--" + boundary;
        if (delimiterAfterBareLf(text, delimiter)) return null;
        int at = text.startsWith(delimiter) ? 0 : text.indexOf("\r\n" + delimiter);
        if (at < 0) return null;
        if (at > 0) at += 2;
        var out = new ArrayList<Map.Entry<String, String>>();
        while (at >= 0) at = readPart(text, at, delimiter, out);
        return out;
    }

    /** The content of each part of a multipart body named {@code name}; null when its parts cannot be read. */
    public static @Nullable List<String> multipartValues(byte[] body, MediaType media, String name) {
        var parts = multipartParts(body, media);
        if (parts == null) return null;
        var out = new ArrayList<String>();
        for (var part : parts) {
            if (part.getKey().equals(name)) out.add(part.getValue());
        }
        return out;
    }

    private static boolean delimiterAfterBareLf(String text, String delimiter) {
        for (int lf = text.indexOf("\n" + delimiter); lf >= 0; lf = text.indexOf("\n" + delimiter, lf + 1)) {
            if (lf == 0 || text.charAt(lf - 1) != '\r') return true;
        }
        return false;
    }

    /**
     * Reads the part whose delimiter starts at {@code at} into {@code out}, a part with no name read past; returns where
     * the next part's delimiter starts, or -1 when none follows.
     */
    private static int readPart(String text, int at, String delimiter, List<Map.Entry<String, String>> out) {
        int after = at + delimiter.length();
        if (text.startsWith("--", after)) return -1;
        int lineEnd = text.indexOf('\n', after);
        if (lineEnd < 0) return -1;
        int headStart = lineEnd + 1;
        var head = head(text, headStart);
        if (head == null) return -1;
        int next = text.indexOf("\r\n" + delimiter, head.contentStart());
        int contentEnd = next < 0 ? text.length() : next;
        var name = partName(text.substring(headStart, head.end()));
        if (name != null) {
            out.add(Map.entry(name, text.substring(head.contentStart(), Math.max(head.contentStart(), contentEnd))));
        }
        return next < 0 ? -1 : next + 2;
    }

    /** Where a part's head ends, and where its content starts. */
    private record Head(int end, int contentStart) {}

    /** The head that starts at {@code start}, up to the first blank line, CRLF or bare LF; null when none ends it. */
    private static @Nullable Head head(String text, int start) {
        if (text.startsWith("\r\n", start) || text.startsWith("\n", start)) {
            return new Head(start, start + (text.charAt(start) == '\r' ? 2 : 1));
        }
        int blank = text.indexOf("\r\n\r\n", start);
        int lfBlank = text.indexOf("\n\n", start);
        if (blank >= 0 && (lfBlank < 0 || blank < lfBlank)) return new Head(blank, blank + 4);
        if (lfBlank >= 0) return new Head(lfBlank, lfBlank + 2);
        return null;
    }

    private static @Nullable String partName(String head) {
        for (var line : head.split("\r?\n")) {
            int colon = line.indexOf(':');
            if (colon < 0 || !line.substring(0, colon).strip().equalsIgnoreCase("Content-Disposition")) continue;
            var m = PART_NAME.matcher(line.substring(colon + 1));
            if (m.find()) return m.group(1) != null ? m.group(1) : m.group(2);
        }
        return null;
    }

    /** Each value of the urlencoded field {@code name}, as written; names are matched decoded, as a server reads them. */
    public static List<String> urlencodedValues(byte[] body, String name) {
        var out = new ArrayList<String>();
        for (var pair : new String(body, StandardCharsets.ISO_8859_1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && formDecoded(pair.substring(0, eq)).equals(name)) out.add(pair.substring(eq + 1));
        }
        return out;
    }

    /** A form field decoded as a server decodes it, or as written when its escapes are malformed. */
    public static String formDecoded(String text) {
        try {
            return URLDecoder.decode(text, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException _) {
            return text;
        }
    }

    /** A top-level field of a JSON object: its decoded name, and its string value as written, or null if no string. */
    public record RawField(String name, @Nullable String raw) {}

    /** The top-level fields of {@code text}, which {@link #jsonFields} has read as one strict JSON object. */
    public static List<RawField> rawJsonFields(String text) {
        var scan = new RawFieldScan();
        int i = 0;
        while (i < text.length()) i = scan.read(text, i);
        return scan.out;
    }

    /** How deep a scan of an object is, and the name of the top-level field whose value comes next. */
    private static final class RawFieldScan {
        final List<RawField> out = new ArrayList<>();
        int depth;
        @Nullable String name;

        /** Reads the string, bracket or scalar at {@code i}, or skips the character there; returns the index after it. */
        int read(String text, int i) {
            char c = text.charAt(i);
            if (c == '"') {
                int end = stringEnd(text, i);
                string(text.substring(i + 1, end));
                return end + 1;
            }
            if (c == '{' || c == '[') {
                open();
            } else if (c == '}' || c == ']') {
                depth--;
            } else if (awaitsValue() && c != ':' && c != ',' && !Character.isWhitespace(c)) {
                value(null);
                return scalarEnd(text, i) + 1;
            }
            return i + 1;
        }

        boolean awaitsValue() {
            return depth == 1 && name != null;
        }

        void string(String raw) {
            if (depth != 1) return;
            if (name == null) name = JsonParser.parseString("\"" + raw + "\"").getAsString();
            else value(raw);
        }

        void open() {
            if (awaitsValue()) value(null);
            depth++;
        }

        void value(@Nullable String raw) {
            out.add(new RawField(Objects.requireNonNull(name), raw));
            name = null;
        }

        /** The index of the quote closing the string whose opening quote is at {@code open}. */
        private static int stringEnd(String text, int open) {
            int end = open + 1;
            while (text.charAt(end) != '"') end += text.charAt(end) == '\\' ? 2 : 1;
            return end;
        }

        /** The index of the last character of the number, {@code true}, {@code false} or {@code null} starting at {@code i}. */
        private static int scalarEnd(String text, int i) {
            while (i + 1 < text.length() && ",}".indexOf(text.charAt(i + 1)) < 0 && !Character.isWhitespace(text.charAt(i + 1))) {
                i++;
            }
            return i;
        }
    }

    /** The top-level fields of {@code text} read strictly as one JSON object, in order, repeats kept; null if it is none. */
    public static @Nullable List<Map.Entry<String, JsonElement>> jsonFields(String text) {
        try {
            var reader = new JsonReader(new StringReader(text));
            reader.setStrictness(Strictness.STRICT);
            if (reader.peek() != JsonToken.BEGIN_OBJECT) return null;
            var out = new ArrayList<Map.Entry<String, JsonElement>>();
            reader.beginObject();
            while (reader.hasNext()) {
                var name = reader.nextName();
                out.add(Map.entry(name, JsonParser.parseReader(reader)));
            }
            reader.endObject();
            return reader.peek() == JsonToken.END_DOCUMENT ? out : null;
        } catch (IOException | RuntimeException _) {
            return null;
        }
    }

    /** {@code body} as UTF-8; null when it is not valid UTF-8. */
    public static @Nullable String utf8(byte[] body) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
        } catch (CharacterCodingException _) {
            return null;
        }
    }
}
