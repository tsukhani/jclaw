package services.grapheval;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * The use-once ledger (JCLAW-1368): {@code <root>/split-uses.jsonl}, append-only, one {@code {split, model, digest,
 * state}} line per certification run. The latest line for a (split, model, digest) is its state.
 */
public final class SplitUses {

    public static final String FILE = "split-uses.jsonl";
    public static final String USED = "used";
    public static final String NEEDS_SECOND_RUN = "needs-second-run";
    public static final String VOID = "void";

    private SplitUses() {}

    /** The latest state and the line that set it, or null when the split was never run on this model version. */
    public record Latest(String state, String line) {}

    /** What a run may do: {@code secondRun} when it is the second run of a use whose spot-check differed. */
    public record Admission(boolean secondRun) {}

    public static @Nullable Latest latest(Path root, String split, String model, String digest) throws IOException {
        var file = root.resolve(FILE);
        if (!Files.exists(file)) return null;
        Latest latest = null;
        for (var line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            JsonObject o;
            try {
                o = JsonParser.parseString(line).getAsJsonObject();
            } catch (JsonParseException | IllegalStateException e) {
                throw new IllegalArgumentException(FILE + " holds a line that is not a JSON object: " + line, e);
            }
            if (split.equals(string(o, "split")) && model.equals(string(o, "model"))
                    && digest.equals(string(o, "digest"))) {
                latest = new Latest(String.valueOf(string(o, "state")), line.strip());
            }
        }
        return latest;
    }

    /**
     * Whether a certification run of {@code runs} runs may use the split: never after {@code used}; only a run of 2
     * after {@code needs-second-run}; any after {@code void} or none.
     *
     * @throws IllegalArgumentException quoting the ledger line that refuses it
     */
    public static Admission admit(Path root, String split, String model, String digest, int runs) throws IOException {
        var latest = latest(root, split, model, digest);
        if (latest == null || latest.state().equals(VOID)) return new Admission(false);
        if (latest.state().equals(NEEDS_SECOND_RUN)) {
            if (runs == 2) return new Admission(true);
            throw new IllegalArgumentException("split '%s' awaits its second run on %s: only runs 2 is accepted (%s: %s)"
                    .formatted(split, model, FILE, latest.line()));
        }
        throw new IllegalArgumentException("split '%s' was already used for %s at this digest (%s: %s)"
                .formatted(split, model, FILE, latest.line()));
    }

    public static void append(Path root, String split, String model, String digest, String state) throws IOException {
        var o = new JsonObject();
        o.addProperty("split", split);
        o.addProperty("model", model);
        o.addProperty("digest", digest);
        o.addProperty("state", state);
        Files.createDirectories(root);
        Files.writeString(root.resolve(FILE), o + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    private static @Nullable String string(JsonObject o, String key) {
        var e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }
}
