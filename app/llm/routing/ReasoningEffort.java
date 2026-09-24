package llm.routing;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/** How hard a routed reasoning model should think about one prompt, before it is fitted to that model's ladder. */
public enum ReasoningEffort {
    LOW,
    MEDIUM,
    HIGH;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static @Nullable ReasoningEffort fromId(@Nullable String id) {
        if (id == null) return null;
        for (var e : values()) {
            if (e.id().equals(id)) return e;
        }
        return null;
    }

    /**
     * The rung of {@code ladder} this effort lands on: the same name when the model advertises it, else
     * the bottom, middle or top rung ({@code low/high/max}: medium → high, high → high). Null for an
     * empty ladder.
     */
    public @Nullable String fit(List<String> ladder) {
        if (ladder.isEmpty()) return null;
        if (ladder.contains(id())) return id();
        return switch (this) {
            case LOW -> ladder.getFirst();
            case MEDIUM -> ladder.get(ladder.size() / 2);
            case HIGH -> ladder.getLast();
        };
    }
}
