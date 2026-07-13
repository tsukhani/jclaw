package utils;

import com.google.gson.JsonObject;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Null-tolerant readers for optional fields on a Gson {@link JsonObject} — the
 * {@code obj.get(key) → isJsonNull → getAs…} guard that a dozen tool, controller,
 * service, and channel classes each re-implemented (JCLAW-729). Each method pins
 * one contract (missing-or-null default, and how a present-but-uncoercible value
 * is handled) so a call site reads intent instead of repeating the dance.
 *
 * <p>Coercion failures — a value that is present but not coercible to the target
 * type (a non-numeric string, or a nested object/array where a scalar was
 * expected) — collapse to the method's default rather than propagating, matching
 * the defensive posture of the accessors this replaces.
 */
public final class JsonArgs {

    private JsonArgs() {}

    /**
     * The value at {@code key} as a string, or {@code null} when the key is absent
     * or JSON-null. A present non-string primitive is coerced via
     * {@code getAsString()}; a non-primitive (object/array) value throws, as the
     * inline reads this replaces did.
     */
    public static @Nullable String optString(@NonNull JsonObject obj, @NonNull String key) {
        var el = obj.get(key);
        if (el == null || el.isJsonNull()) return null;
        return el.getAsString();
    }

    /**
     * As {@link #optString(JsonObject, String)} but returns {@code def} when the
     * key is absent or JSON-null. A blank value is returned verbatim (it is not
     * collapsed to {@code def}).
     */
    public static @Nullable String optString(@NonNull JsonObject obj, @NonNull String key, @Nullable String def) {
        var el = obj.get(key);
        if (el == null || el.isJsonNull()) return def;
        return el.getAsString();
    }

    /**
     * The value at {@code key} as a string, or {@code null} when {@code obj} is
     * null, the key is absent/JSON-null, or the value is blank. The returned string
     * is never blank (blank collapses to {@code null}); it is not trimmed.
     */
    public static @Nullable String optNonBlankString(@Nullable JsonObject obj, @NonNull String key) {
        if (obj == null) return null;
        var el = obj.get(key);
        if (el == null || el.isJsonNull()) return null;
        var s = el.getAsString();
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * The value at {@code key} as an {@code int}, or {@code fallback} when the key
     * is absent, JSON-null, or not coercible to an int.
     */
    public static int optInt(@NonNull JsonObject obj, @NonNull String key, int fallback) {
        var el = obj.get(key);
        if (el == null || el.isJsonNull()) return fallback;
        try {
            return el.getAsInt();
        } catch (NumberFormatException | UnsupportedOperationException | IllegalStateException _) {
            return fallback;
        }
    }

    /**
     * The value at {@code key} as an {@link Integer}, or {@code null} when the key
     * is absent, JSON-null, or not coercible to an int.
     */
    public static @Nullable Integer optInteger(@NonNull JsonObject obj, @NonNull String key) {
        var el = obj.get(key);
        if (el == null || el.isJsonNull()) return null;
        try {
            return el.getAsInt();
        } catch (NumberFormatException | UnsupportedOperationException | IllegalStateException _) {
            return null;
        }
    }

    /**
     * The value at {@code key} as a {@link Long}, or {@code null} when the key is
     * absent, JSON-null, or not coercible to a long.
     */
    public static @Nullable Long optLong(@NonNull JsonObject obj, @NonNull String key) {
        var el = obj.get(key);
        if (el == null || el.isJsonNull()) return null;
        try {
            return el.getAsLong();
        } catch (NumberFormatException | UnsupportedOperationException | IllegalStateException _) {
            return null;
        }
    }

    /**
     * The value at {@code key} as a {@code boolean}, or {@code false} when the key
     * is absent, JSON-null, or not coercible to a boolean.
     */
    public static boolean optBool(@NonNull JsonObject obj, @NonNull String key) {
        var el = obj.get(key);
        if (el == null || el.isJsonNull()) return false;
        try {
            return el.getAsBoolean();
        } catch (UnsupportedOperationException | IllegalStateException _) {
            return false;
        }
    }

    /**
     * The value at {@code key} as a {@link Boolean}, or {@code null} when the key
     * is absent, JSON-null, or not coercible to a boolean.
     */
    public static @Nullable Boolean optBoolean(@NonNull JsonObject obj, @NonNull String key) {
        var el = obj.get(key);
        if (el == null || el.isJsonNull()) return null;
        try {
            return el.getAsBoolean();
        } catch (UnsupportedOperationException | IllegalStateException _) {
            return null;
        }
    }
}
