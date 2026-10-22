import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import utils.GsonHolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Checked-in expected text for an approval test (JF-17). A mismatch writes the received text to its own file and
 * fails; moving that file over the approved one is the approval.
 */
public final class ApprovedText {

    private ApprovedText() {}

    public static void assertApproved(Path approved, Path received, String text) throws IOException {
        assertApproved(approved, received, text, "");
    }

    /** {@code then}: what else the approval needs, such as regenerating what is built from the approved file. */
    public static void assertApproved(Path approved, Path received, String text, String then) throws IOException {
        if (Files.exists(approved) && Files.readString(approved).equals(text)) {
            Files.deleteIfExists(received);
            return;
        }
        Files.createDirectories(received.getParent());
        Files.writeString(received, text);
        fail((Files.exists(approved) ? "The approved text changed" : "No approved text yet")
                + ": compare " + received + " with " + approved
                + ", and if it should now read exactly that, move the received file over the approved one"
                + (then.isEmpty() ? "" : ", then " + then));
    }

    public static String canonicalJson(JsonElement json) {
        return GsonHolder.GSON.newBuilder().setPrettyPrinting().create().toJson(sorted(json)) + "\n";
    }

    /**
     * Object keys and lists of plain values ({@code enum}, {@code required}, where order means nothing) sorted; lists of
     * objects keep their order. Schemas built with {@code Map.of} and {@code Set.of} iterate in an order the JDK salts per JVM.
     */
    private static JsonElement sorted(JsonElement json) {
        if (json instanceof JsonObject object) {
            var copy = new JsonObject();
            object.keySet().stream().sorted().forEach(key -> copy.add(key, sorted(object.get(key))));
            return copy;
        }
        if (json instanceof JsonArray array) {
            var elements = array.asList().stream().map(ApprovedText::sorted).toList();
            if (elements.stream().allMatch(JsonElement::isJsonPrimitive)) {
                elements = elements.stream().sorted(Comparator.comparing(JsonElement::getAsString)).toList();
            }
            var copy = new JsonArray();
            elements.forEach(copy::add);
            return copy;
        }
        return json;
    }
}
