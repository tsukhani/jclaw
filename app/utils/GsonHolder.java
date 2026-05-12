package utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.time.Instant;

/**
 * Shared Gson instance for the entire application.
 * Replaces per-class {@code private static final Gson gson = new Gson()} declarations.
 *
 * <p>{@code serializeNulls()} is enabled so records produce the same wire
 * shape as the {@code Map.of(...)} / {@code HashMap.put(k, null)} idioms
 * that record-typed DTOs are progressively replacing — Gson default
 * serializes null map values but omits null object fields, and the
 * frontend has historically seen the former. Keeping the keys present
 * with explicit nulls maintains wire-format invariance across the
 * JCLAW-278 DTO migration without per-controller divergence.
 *
 * <p>An {@link Instant} type adapter (JCLAW-282) emits ISO-8601 strings.
 * Default reflective serialization tries to read {@code Instant.seconds}
 * via {@code Field.setAccessible}, which JDK 25 refuses without an
 * {@code --add-opens java.base/java.time=ALL-UNNAMED} JVM flag. Emitting
 * a string is also better wire format than the internal {seconds,nanos}
 * struct — frontends parse strings, not numeric epoch tuples.
 */
public final class GsonHolder {

    public static final Gson INSTANCE = new GsonBuilder()
            .serializeNulls()
            .registerTypeAdapter(Instant.class, new InstantTypeAdapter())
            .create();

    private GsonHolder() {}

    /** ISO-8601 codec for {@link Instant}. {@code null} round-trips
     *  cleanly so records carrying nullable {@code Instant} fields
     *  (e.g. {@code lastUsedAt}, {@code revokedAt}) don't need
     *  per-field guards. */
    private static final class InstantTypeAdapter extends TypeAdapter<Instant> {
        @Override
        public void write(JsonWriter out, Instant value) throws IOException {
            if (value == null) out.nullValue();
            else out.value(value.toString());
        }

        @Override
        public Instant read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            return Instant.parse(in.nextString());
        }
    }
}
