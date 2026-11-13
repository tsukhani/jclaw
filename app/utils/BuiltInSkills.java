package utils;

import java.util.List;

/**
 * The skills JClaw ships and installs itself, which only the Main Agent may change (JF-392): a custom agent's copy is
 * read-only to it through the file tool and its shell alike, and no registry import may take one of these names.
 */
public final class BuiltInSkills {

    public static final List<String> NAMES = List.of("skill-creator", "jclaw-api", "task-creator");

    private BuiltInSkills() {}

    /** Whether a skill folder named {@code name} is a built-in one, in any letter case, as a macOS volume matches it. */
    public static boolean isBuiltIn(String name) {
        return NAMES.stream().anyMatch(n -> n.equalsIgnoreCase(name));
    }
}
