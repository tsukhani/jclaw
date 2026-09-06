package services.scrape;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;
import play.Play;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the scrape corpus (JCLAW-1081). Format contract: {@code evals/scrape/README.md}.
 *
 * <p>Entries carry three independent axes rather than one difficulty tier. Conflating
 * them is what broke the first corpus: an edge vendor in front does not mean the vendor
 * is blocking, and a page with no server-rendered text may be a JS gate or an ordinary
 * client-rendered app.
 */
public final class ScrapeCorpus {

    private ScrapeCorpus() {}

    public static final String DEFAULT_PATH = "evals/scrape/corpus.json";

    /** ASCII unit separator between canonical fields: it cannot occur in a URL or a label,
     *  so no two corpora render to the same canonical string by shifting a field boundary. */
    private static final char FIELD_SEP = (char) 0x1F;

    /** How far a stratum may sit from the mean and still be scored as equal allocation.
     *  Re-classification legitimately moves entries between strata, so exact equality would
     *  make "re-classify before the run" and "gate on equal allocation" mutually exclusive;
     *  the band is what keeps each stratum's floor scored against a comparable n. */
    private static final double ALLOCATION_TOLERANCE = 0.20;

    /**
     * @param stratum  the sampling bucket, e.g. {@code unprotected-spa}
     * @param vendor   detected edge vendor, or {@code none} — fingerprint-based, so
     *                 {@code none} is an upper bound on unprotected, not a guarantee
     * @param outcome  what the origin did: served / denied / challenge / interactive
     * @param rendering {@code ssr} or {@code spa}; null unless the origin served us
     */
    public record Entry(String url, @Nullable String stratum, @Nullable String vendor, @Nullable String outcome,
                        @Nullable String rendering, int rank, GroundTruth groundTruth) {}

    public record Identity(@Nullable String trancoListId, @Nullable String probedOn, @Nullable String reclassifiedOn,
                           String fingerprint, Map<String, Integer> realisedStrata,
                           int allocationSpread) {}

    public record Corpus(Identity identity, @Nullable String allocation, List<String> strata,
                         List<Entry> entries) {

        /** The counts the entries realise, which is what the gate is scored against; the
         *  builder's recorded {@code realised_strata} is provenance and can be stale. */
        public Map<String, Integer> realisedCounts() {
            var counts = new LinkedHashMap<String, Integer>();
            strata.forEach(s -> counts.put(s, 0));
            entries.forEach(e -> counts.merge(e.stratum(), 1, Integer::sum));
            return counts;
        }

        /** Guards the epic gate: an aggregate threshold only forces work on the hard
         *  strata under equal allocation. Proportionally sampled, the unprotected
         *  strata alone would carry it.
         *
         *  <p>Scored on realised counts, not on the declared label: {@code build_corpus.py}
         *  deliberately never moves that label, so a check of it alone can never fire. */
        public boolean isEqualAllocation() {
            if (!"equal".equals(allocation) || entries.isEmpty()) return false;
            var counts = realisedCounts();
            double mean = (double) entries.size() / counts.size();
            return counts.values().stream()
                    .allMatch(n -> Math.abs(n - mean) <= mean * ALLOCATION_TOLERANCE);
        }
    }

    public static Corpus load() throws IOException {
        return load(Path.of(Play.applicationPath.getAbsolutePath(), DEFAULT_PATH));
    }

    public static Corpus load(Path path) throws IOException {
        var root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8))
                .getAsJsonObject();
        var entries = new ArrayList<Entry>();
        for (var el : root.getAsJsonArray("entries")) {
            var o = el.getAsJsonObject();
            entries.add(new Entry(
                    requireStr(o, "url"), str(o, "stratum"), str(o, "vendor"),
                    str(o, "outcome"), str(o, "rendering"),
                    o.has("rank") ? o.get("rank").getAsInt() : 0,
                    groundTruth(o.getAsJsonObject("ground_truth"))));
        }
        var strata = new ArrayList<String>();
        if (root.has("strata")) {
            root.getAsJsonArray("strata").forEach(s -> strata.add(s.getAsString()));
        }
        var realised = new LinkedHashMap<String, Integer>();
        if (root.has("realised_strata")) {
            root.getAsJsonObject("realised_strata").entrySet()
                    .forEach(e -> realised.put(e.getKey(), e.getValue().getAsInt()));
        }
        var identity = new Identity(
                str(root, "tranco_list_id"), str(root, "probed_on"),
                str(root, "reclassified_on"), fingerprint(entries), Map.copyOf(realised),
                root.has("allocation_spread") ? root.get("allocation_spread").getAsInt() : 0);
        return new Corpus(identity, str(root, "allocation"),
                List.copyOf(strata), List.copyOf(entries));
    }

    /** {@code rank} and the probe's observed sizes are excluded as provenance, the rule
     *  {@code EvalSuite.fingerprint()} applies to a rubric: only what decides how this
     *  corpus scores a run may move the hash. */
    private static String fingerprint(List<Entry> entries) {
        var canonical = new StringBuilder();
        for (var e : entries) {
            var gt = e.groundTruth();
            canonical.append(FIELD_SEP).append(e.url())
                    .append(FIELD_SEP).append(e.stratum())
                    .append(FIELD_SEP).append(e.vendor())
                    .append(FIELD_SEP).append(e.outcome())
                    .append(FIELD_SEP).append(e.rendering())
                    .append(FIELD_SEP).append(gt.minChars())
                    .append(FIELD_SEP).append(gt.rejectMarkers())
                    .append(FIELD_SEP).append(gt.expectTitle());
        }
        return sha256Prefix(canonical.toString());
    }

    /** First 12 hex chars of the SHA-256, the width {@code EvalSuite} prints. */
    private static String sha256Prefix(String canonical) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable — JDK install broken?", e);
        }
    }

    private static GroundTruth groundTruth(JsonObject gt) {
        var markers = new ArrayList<String>();
        if (gt.has("reject_markers")) {
            gt.getAsJsonArray("reject_markers").forEach(m -> markers.add(m.getAsString()));
        }
        return new GroundTruth(
                gt.has("min_chars") ? gt.get("min_chars").getAsInt() : 500,
                List.copyOf(markers),
                gt.has("expect_title") ? gt.get("expect_title").getAsString() : null);
    }

    /** A corpus entry is identified by its url; an entry without one is malformed. */
    private static String requireStr(JsonObject o, String key) {
        var v = str(o, key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("corpus entry is missing '" + key + "'");
        }
        return v;
    }

    private static @Nullable String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }
}
