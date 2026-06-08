package services;

import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.util.ArrayList;
import java.util.List;

/**
 * JCLAW-260: a {@link models.Task}'s {@code description} optionally carries
 * an <em>ordered list of steps</em>, stored in the existing TEXT column as
 * a JSON array of strings (e.g. {@code ["Fetch orders","Post summary"]}).
 * A plain string is the single-step / free-text case.
 *
 * <p>This class is the single point that reverses that encoding:
 * <ul>
 *   <li>{@link #parse(String)} turns a stored {@code description} back into
 *       its ordered steps. The fire path and the admin UI (JCLAW-22) both
 *       go through here, so they agree on what "the steps" are.</li>
 *   <li>{@link #flattenForPrompt(String)} renders the steps into the single
 *       string injected into the agent prompt at fire time — a numbered list
 *       for multi-step tasks, verbatim for the single-step case.</li>
 * </ul>
 *
 * <p><b>Invariant that protects every existing task:</b> a one-element array
 * and an equivalent plain string flatten to the <em>same</em> prompt (no
 * numbering for a single step), so no migration or backfill is needed and
 * free-text tasks behave exactly as before.
 *
 * <p>Steps are <b>static</b> instructions — there is no per-step status or
 * re-planning. Reminders and {@code script}/{@code noAgent} tasks never reach
 * {@link #flattenForPrompt} on their delivery path (their description is
 * delivered verbatim), so they degrade naturally to a single step.
 */
public final class TaskSteps {

    private TaskSteps() {}

    /**
     * Parse a stored {@code description} into its ordered steps. A value that
     * deserializes to a JSON array whose elements are <em>all</em> strings
     * yields those strings in order; anything else — plain text, malformed
     * JSON, a JSON object, or a mixed/empty array — yields a single step
     * holding the raw text. {@code null} or blank yields an empty list.
     */
    public static List<String> parse(String description) {
        if (description == null) return List.of();
        var trimmed = description.strip();
        if (trimmed.isEmpty()) return List.of();
        // Only attempt a JSON parse when the value actually looks like an
        // array — keeps the common plain-text path allocation-free.
        if (trimmed.charAt(0) == '[') {
            try {
                var el = JsonParser.parseString(trimmed);
                if (el.isJsonArray()) {
                    var arr = el.getAsJsonArray();
                    var steps = new ArrayList<String>(arr.size());
                    for (var item : arr) {
                        if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                            // Not an array-of-strings — treat the whole value
                            // as one literal step rather than guessing.
                            return List.of(description);
                        }
                        steps.add(item.getAsString());
                    }
                    // An empty array carries no usable instruction; fall back
                    // to the raw text so the fire path still has a prompt.
                    return steps.isEmpty() ? List.of(description) : List.copyOf(steps);
                }
            } catch (JsonSyntaxException _) {
                // Looked like an array but wasn't valid JSON — treat as text.
            }
        }
        return List.of(description);
    }

    /**
     * Flatten a stored {@code description} into the single string injected
     * into the agent prompt at fire time. A single step renders verbatim
     * (no numbering) so existing free-text tasks produce exactly the same
     * prompt as before; multiple steps render as a numbered list.
     */
    public static String flattenForPrompt(String description) {
        var steps = parse(description);
        if (steps.isEmpty()) return description == null ? "" : description;
        if (steps.size() == 1) return steps.get(0);
        var sb = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(i + 1).append(". ").append(steps.get(i));
        }
        return sb.toString();
    }
}
