package models;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;
import services.ConfigService;
import utils.CredentialCipher;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The JSON columns that hold third-party credentials among other settings (JCLAW-1455). A value is secret where the
 * column's read API masks it, and each is sealed with {@link CredentialCipher} under its own {@link #field} name, so a
 * ciphertext moved to another value does not open there.
 *
 * <p>Unlike a binding column, a value that will not decrypt stays in the entity's JSON as its ciphertext, so a save of
 * the rest of the JSON writes it back unchanged for the right secret to recover. {@link #unreadable} names those
 * values, and a reader treats them as unset.
 */
public enum JsonCredential {

    /** Every header value, and each env value whose name {@link ConfigService#isSensitive} flags: what {@code McpServerService.View} masks. */
    MCP_SERVER("mcp_server") {
        @Override
        void collect(JsonObject json, List<Slot> out) {
            members(json, "env", ConfigService::isSensitive, out);
            members(json, "headers", _ -> true, out);
        }
    },

    /** Each top-level value whose name {@link ConfigService#isSensitive} flags: what {@code ApiChannelsController} masks. */
    CHANNEL_CONFIG("channel_config") {
        @Override
        void collect(JsonObject json, List<Slot> out) {
            members(json, null, ConfigService::isSensitive, out);
        }
    };

    public static final String COLUMN = "config_json";

    public final String table;

    JsonCredential(String table) {
        this.table = table;
    }

    /** One secret string value: its object, its member name, and its path from the root ({@code headers.Authorization}). */
    record Slot(JsonObject parent, String name, String path) {

        String value() {
            return parent.get(name).getAsString();
        }

        void set(String value) {
            parent.addProperty(name, value);
        }
    }

    abstract void collect(JsonObject json, List<Slot> out);

    /** The cipher's additional authenticated data for the value at {@code path}. */
    public String field(String path) {
        return table + "." + COLUMN + "." + path;
    }

    /**
     * {@code json} with each secret value sealed for storage. A ciphertext no secret opens is one a read left in place
     * and goes back as it came; any other value is sealed whole, a ciphertext typed in as a value included, so a write
     * can never have one opened.
     */
    public @Nullable String sealed(@Nullable String json, CredentialCipher cipher) {
        var parsed = parse(json);
        if (parsed == null) return json;
        boolean changed = false;
        for (var slot : slots(parsed)) {
            var value = slot.value();
            if (value.isEmpty()) continue;
            if (CredentialCipher.isEncrypted(value) && !cipher.open(field(slot.path()), value).readable()) continue;
            slot.set(cipher.encrypt(field(slot.path()), value));
            changed = true;
        }
        return changed ? parsed.toString() : json;
    }

    /** {@code stored} with each secret value decrypted; a value no secret opens keeps its ciphertext. */
    public @Nullable String opened(@Nullable String stored, CredentialCipher cipher) {
        if (stored == null || !stored.contains(CredentialCipher.PREFIX)) return stored;
        var parsed = parse(stored);
        if (parsed == null) return stored;
        boolean changed = false;
        for (var slot : slots(parsed)) {
            var value = slot.value();
            if (!CredentialCipher.isEncrypted(value)) continue;
            var opened = cipher.open(field(slot.path()), value);
            if (!opened.readable()) continue;
            slot.set(opened.resolvedPlaintext());
            changed = true;
        }
        return changed ? parsed.toString() : stored;
    }

    /** The boot pass over one stored row: its JSON with every secret value sealed under the current secret, or null when none needed it. */
    public @Nullable String reseal(String stored, CredentialCipher.Reseal pass) {
        var parsed = parse(stored);
        if (parsed == null) return null;
        boolean changed = false;
        for (var slot : slots(parsed)) {
            var resealed = pass.reseal(field(slot.path()), slot.value());
            if (resealed == null) continue;
            slot.set(resealed);
            changed = true;
        }
        return changed ? parsed.toString() : null;
    }

    /** The paths of the secret values in {@code json}, an entity's decrypted attribute, that did not decrypt. */
    public List<String> unreadable(@Nullable String json) {
        var parsed = parse(json);
        if (parsed == null) return List.of();
        return slots(parsed).stream().filter(slot -> CredentialCipher.isEncrypted(slot.value())).map(Slot::path).toList();
    }

    /** Why {@code json}'s credentials cannot be used, or null when every one decrypted. */
    public @Nullable String unreadableReason(@Nullable String json) {
        var paths = unreadable(json);
        if (paths.isEmpty()) return null;
        return String.join(", ", paths) + (paths.size() == 1 ? " does" : " do")
                + " not decrypt under this installation's secret: enter " + (paths.size() == 1 ? "it" : "them")
                + " again in Settings";
    }

    private List<Slot> slots(JsonObject json) {
        var out = new ArrayList<Slot>();
        collect(json, out);
        return out;
    }

    /** The string members of {@code json}'s {@code section} object, or of {@code json} itself when null, that {@code secret} names. */
    private static void members(JsonObject json, @Nullable String section, Predicate<String> secret, List<Slot> out) {
        JsonObject parent = json;
        if (section != null) {
            if (!json.has(section) || !json.get(section).isJsonObject()) return;
            parent = json.getAsJsonObject(section);
        }
        for (var member : parent.entrySet()) {
            var value = member.getValue();
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || !secret.test(member.getKey())) {
                continue;
            }
            var name = member.getKey();
            out.add(new Slot(parent, name, section == null ? name : section + "." + name));
        }
    }

    private static @Nullable JsonObject parse(@Nullable String json) {
        if (json == null || json.isBlank()) return null;
        try {
            var parsed = JsonParser.parseString(json);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (RuntimeException _) {
            return null;
        }
    }
}
