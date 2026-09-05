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
     * The canonical label to <em>store</em> a raw category under: one of the six, with
     * anything unrecognized becoming {@link #FACT} — the same bucket a missing category
     * already falls back to.
     *
     * <p>Distinct from {@link #normalize}, which is the read path and must keep passing
     * unrecognized values through so pre-existing rows still render. Coercing on write and
     * passing through on read is the split that lets the taxonomy tighten without a data
     * migration.
     *
     * <p>Unrecognized labels are not mapped to a "nearest" bucket. The extractor is given
     * a closed set of six and told to pick one; when it returns {@code opinion} or
     * {@code project} instead, any specific bucket we chose would be our guess at an intent
     * the model did not express. {@code fact} is the neutral option that adds no meaning.
     */
    public static String coerceForStorage(String raw) {
        return from(raw).map(c -> c.label).orElse(FACT.label);
    }

    /**
     * As {@link #coerceForStorage}, but {@link #CORE} is not available: auto-capture may
     * never assign it (JCLAW-981). Core is the always-loaded tier, so membership is the
     * operator's to grant — through an explicit "remember that…", which reaches storage
     * via {@code MemoryTool} rather than through here.
     *
     * <p>Enforced in code rather than only by dropping {@code core} from the extractor
     * prompt. The extractor already returns labels outside the closed set it is given
     * ({@code opinion}, {@code project} — see JCLAW-927), so an instruction not to use a
     * bucket is not a guarantee it won't.
     *
     * <p>Demotes to {@link #FACT} for the reason given above: it is the neutral bucket that
     * adds no meaning the model did not express.
     */
    public static String coerceForCapture(String raw) {
        var coerced = coerceForStorage(raw);
        return CORE.label.equals(coerced) ? FACT.label : coerced;
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
