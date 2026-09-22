package llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The JSON in a model reply: its last complete top-level object or array, whether the model sent
 * it bare, fenced, after leaked reasoning ({@code …</think>{…}}) or before trailing prose.
 *
 * <p>Ask for the shape the caller can use. A caller reading the last value of any shape takes a
 * JSON-ish fragment in the trailing prose — a footnote {@code [1]} — as the answer.
 */
public final class ReplyJson {

    private static final String FENCE = "```";

    private ReplyJson() {}

    /**
     * @param value     the object the reply ends on
     * @param endsReply whether nothing but whitespace and one closing code fence follows it
     */
    public record Found(JsonObject value, boolean endsReply) {}

    /** The last value of either shape, parsed as leniently as {@code JsonParser.parseString}. */
    public static Optional<JsonElement> lenient(@Nullable String reply) {
        return last(reply, Strictness.LENIENT, _ -> true).map(Match::value);
    }

    /** The last object, parsed leniently; a trailing array is skipped rather than taken. */
    public static Optional<JsonObject> lenientObject(@Nullable String reply) {
        return last(reply, Strictness.LENIENT, JsonElement::isJsonObject).map(m -> m.value().getAsJsonObject());
    }

    /** The last array, parsed leniently; a trailing object is skipped rather than taken. */
    public static Optional<JsonArray> lenientArray(@Nullable String reply) {
        return last(reply, Strictness.LENIENT, JsonElement::isJsonArray).map(m -> m.value().getAsJsonArray());
    }

    /** The last object that is strict JSON, and whether the reply ends on it. */
    public static Optional<Found> strictObject(@Nullable String reply) {
        return last(reply, Strictness.STRICT, JsonElement::isJsonObject)
                .map(m -> new Found(m.value().getAsJsonObject(), m.endsReply()));
    }

    private record Match(JsonElement value, boolean endsReply) {}

    private static Optional<Match> last(@Nullable String reply, Strictness strictness, Predicate<JsonElement> shape) {
        if (reply == null) return Optional.empty();
        JsonElement value = null;
        int end = -1;
        for (var span : topLevelSpans(reply, strictness)) {
            var parsed = parse(reply.substring(span[0], span[1] + 1), strictness);
            if (parsed != null && shape.test(parsed)) {
                value = parsed;
                end = span[1] + 1;
            }
        }
        if (value == null) return Optional.empty();
        var rest = reply.substring(end).strip();
        return Optional.of(new Match(value, rest.isEmpty() || rest.equals(FENCE)));
    }

    /**
     * One pass: the matched bracket pairs no other matched pair contains, in order. Quotes count
     * only inside brackets, so an apostrophe in the reasoning cannot swallow the answer, and a pair
     * inside an opening that never closes still stands on its own.
     */
    private static List<int[]> topLevelSpans(String reply, Strictness strictness) {
        var spans = new ArrayList<int[]>();
        var openings = new ArrayList<int[]>();
        char quote = 0;
        for (int i = 0; i < reply.length(); i++) {
            char c = reply.charAt(i);
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
            } else if (!openings.isEmpty() && (c == '"' || (c == '\'' && strictness == Strictness.LENIENT))) {
                quote = c;
            } else if (c == '{' || c == '[') {
                openings.add(new int[] {i, spans.size()});
            } else if ((c == '}' || c == ']') && !openings.isEmpty()) {
                var opening = openings.removeLast();
                spans.subList(opening[1], spans.size()).clear();
                spans.add(new int[] {opening[0], i});
            }
        }
        return spans;
    }

    private static @Nullable JsonElement parse(String span, Strictness strictness) {
        try {
            var reader = new JsonReader(new StringReader(span));
            reader.setStrictness(strictness);
            var value = JsonParser.parseReader(reader);
            return reader.peek() == JsonToken.END_DOCUMENT ? value : null;
        } catch (JsonParseException | IOException _) {
            return null;
        }
    }
}
