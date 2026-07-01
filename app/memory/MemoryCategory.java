package memory;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Canonical memory taxonomy (JCLAW-40). Every agent memory carries a free-form
 * category {@code String} in the DB, but auto-capture (JCLAW-39) and the admin
 * UI work against this fixed set of six buckets. Each bucket also carries a
 * default importance used when a caller stores a memory without an explicit
 * score.
 *
 * <p>The DB column stays a plain {@code String} on purpose — pre-existing rows
 * may hold arbitrary categories and keeping the column loose avoids a data
 * migration. This enum is the <em>normalization + defaults</em> layer on top,
 * not the storage type. {@link #normalize} canonicalizes case and passes
 * unrecognized values through untouched so legacy rows keep working.
 *
 * <p>Default importances rank the always-loaded {@code core} bucket highest and
 * keep {@code entity}/{@code fact} reference data lowest — those are recalled on
 * relevance rather than force-loaded at session start (see the core-memory
 * injection in {@code SystemPromptAssembler}).
 */
public enum MemoryCategory {
    CORE("core", 0.9),
    PREFERENCE("preference", 0.7),
    DECISION("decision", 0.7),
    LESSON("lesson", 0.6),
    FACT("fact", 0.5),
    ENTITY("entity", 0.5);

    /** Importance assigned to a null or unrecognized category. */
    public static final double BASELINE_IMPORTANCE = 0.5;

    /** Canonical lowercase label as stored in {@code Memory.category}. */
    public final String label;

    /** Default importance when a memory of this category is stored without an explicit score. */
    public final double defaultImportance;

    MemoryCategory(String label, double defaultImportance) {
        this.label = label;
        this.defaultImportance = defaultImportance;
    }

    /**
     * Match a raw category string (case-insensitive, trimmed) to a canonical
     * category, or empty when it isn't one of the six.
     */
    public static Optional<MemoryCategory> from(String raw) {
        if (raw == null) return Optional.empty();
        var key = raw.strip().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(c -> c.label.equals(key)).findFirst();
    }

    /**
     * Canonicalize a category for storage: case is lowered and surrounding
     * whitespace trimmed; an unrecognized non-blank value passes through (legacy
     * rows keep working); blank or null becomes {@code null}.
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return raw.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * Default importance for a raw category string, or
     * {@link #BASELINE_IMPORTANCE} when it isn't one of the six.
     */
    public static double defaultImportanceFor(String raw) {
        return from(raw).map(c -> c.defaultImportance).orElse(BASELINE_IMPORTANCE);
    }

    /** The six canonical labels, for admin-UI dropdowns and validation. */
    public static List<String> labels() {
        return Arrays.stream(values()).map(c -> c.label).toList();
    }
}
