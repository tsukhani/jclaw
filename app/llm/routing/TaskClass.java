package llm.routing;

import org.jspecify.annotations.Nullable;

import java.util.Locale;

/** The kinds of work the router tells apart; each has its own ordered model list in the policy. */
public enum TaskClass {

    /** Quick conversation, and the list every other class downshifts to. */
    CHAT,
    SUMMARIZE,
    AGENTIC,
    REASONING,
    CODING;

    /** The lowercase name used in config keys and in the route persisted on each message. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The effort a reasoning model gets when the keyword rules, not the classifier model, chose the class. */
    public ReasoningEffort defaultEffort() {
        return switch (this) {
            case CHAT, SUMMARIZE -> ReasoningEffort.LOW;
            case AGENTIC, CODING -> ReasoningEffort.MEDIUM;
            case REASONING -> ReasoningEffort.HIGH;
        };
    }

    public static @Nullable TaskClass fromId(@Nullable String id) {
        if (id == null) return null;
        for (var c : values()) {
            if (c.id().equals(id)) return c;
        }
        return null;
    }
}
