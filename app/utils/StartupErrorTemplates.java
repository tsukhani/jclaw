package utils;

import java.util.Map;

/**
 * Templates for startup and boot failures — the seam JCLAW-1136 fills (JCLAW-60).
 *
 * <p>Empty on purpose. Each surface owns a file so the stories that populate them land on
 * disjoint paths instead of contending for one table; a table with no rows registers nothing
 * and {@link ErrorTemplates#forCode} keeps falling back for those codes until it has some.
 */
final class StartupErrorTemplates {

    private StartupErrorTemplates() {}

    static Map<String, ErrorTemplate> templates() {
        return Map.of();
    }
}
