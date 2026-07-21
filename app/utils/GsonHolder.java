package utils;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
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

    public static final Gson GSON = new GsonBuilder()
            // JCLAW-686: this is a JSON API consumed via JSON.parse (which un-escapes
            // transparently), never embedded raw into an HTML <script> context — so
            // Gson's default HTML-escaping of '=' (\u003d), apostrophes (\u0027), and
            // < > & only makes raw bodies unreadable and error messages fragile.
            .disableHtmlEscaping()
            .serializeNulls()
            .registerTypeAdapter(Instant.class, new InstantTypeAdapter())
            // JCLAW-730: Gson does not honor Jackson's @JsonIgnore, so credential
            // fields marked @JsonIgnore on the binding models would still serialize
            // in the clear through this (the app's primary) serializer. Skip them
            // here too, making redaction safe-by-construction instead of dependent
            // on hand-written projections. Serialization-only: reading a credential
            // back from a request body is unaffected. @JsonIgnore is used solely on
            // those credential fields today, so this cannot over-exclude.
            .addSerializationExclusionStrategy(new ExclusionStrategy() {
                @Override
                public boolean shouldSkipField(FieldAttributes f) {
                    return f.getAnnotation(JsonIgnore.class) != null;
                }

                @Override
                public boolean shouldSkipClass(Class<?> clazz) {
                    return false;
                }
            })
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
