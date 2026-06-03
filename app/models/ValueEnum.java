package models;

import java.util.HashMap;
import java.util.Map;

/**
 * Shared contract for the string-backed enums whose constants are persisted
 * as plain VARCHAR (no JPA {@code @Enumerated}) and resolved back from the
 * raw database/wire string — see {@link ChannelType} and {@link MessageRole}.
 *
 * <p>Centralizes the reverse-lookup that each enum used to hand-write as a
 * {@code fromValue} switch, so a new constant only has to declare its
 * {@code value()} once instead of also appearing in a parallel switch arm
 * that a later edit could forget.
 */
public interface ValueEnum {

    /** The lowercase string this constant is stored/transmitted as. */
    String value();

    /**
     * Build a {@code value -> constant} lookup over an enum's constants, for
     * a {@code fromValue} resolver. Preserves the prior switch semantics:
     * {@link #fromValue} returns {@code null} for {@code null} or unrecognised
     * input rather than throwing.
     */
    static <E extends Enum<E> & ValueEnum> Map<String, E> indexOf(E[] constants) {
        var index = new HashMap<String, E>(constants.length * 2);
        for (var c : constants) {
            index.put(c.value(), c);
        }
        return index;
    }

    /**
     * Resolve a raw string against a lookup built by {@link #indexOf}. Returns
     * {@code null} for {@code null} or unrecognised input so callers can fall
     * through to a default branch without throwing.
     */
    static <E extends Enum<E> & ValueEnum> E fromValue(Map<String, E> index, String value) {
        return value == null ? null : index.get(value);
    }
}
