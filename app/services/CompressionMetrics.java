package services;

import models.CompressionMetric;
import models.CompressionMetric.Kind;
import play.db.jpa.JPA;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Records and aggregates compression-pipeline events (JCLAW-467). Recording is
 * best-effort — a failed metric write is swallowed so it can never disrupt an
 * agent run — and skipped for agent-less events (the pure pipeline test seam
 * passes a null agentId). Aggregation happens at read time over the
 * {@link CompressionMetric} rows.
 */
public final class CompressionMetrics {

    private CompressionMetrics() {}

    // Below this token-savings ratio (after/before) a type is flagged as
    // suspiciously aggressive; above this inflation-guard rate the algorithm is
    // flagged as frequently inflating; below this CCR hit rate the model isn't
    // retrieving when it should. Mirrors the JCLAW-467 alert thresholds.
    private static final double LOW_RATIO = 0.10;
    private static final double HIGH_GUARD_RATE = 0.05;
    private static final double LOW_CCR_HIT_RATE = 0.50;

    // ------------------------------------------------------------- recording

    public static void recordCompression(String agentId, String modelId, String contentType,
                                         String algorithm, int tokensBefore, int tokensAfter) {
        if (agentId == null) return; // agent-less (test seam) — nothing to attribute
        var m = new CompressionMetric();
        m.kind = Kind.COMPRESSION;
        m.agentId = agentId;
        m.modelId = modelId;
        m.contentType = contentType;
        m.algorithm = algorithm;
        m.tokensBefore = tokensBefore;
        m.tokensAfter = tokensAfter;
        persist(m);
    }

    public static void recordInflationGuard(String agentId, String modelId, String contentType,
                                            int tokensBefore, int tokensAfter) {
        if (agentId == null) return;
        var m = new CompressionMetric();
        m.kind = Kind.INFLATION_GUARD;
        m.agentId = agentId;
        m.modelId = modelId;
        m.contentType = contentType;
        m.tokensBefore = tokensBefore;
        m.tokensAfter = tokensAfter;
        persist(m);
    }

    public static void recordCcrRetrieval(String hash, boolean hit) {
        var m = new CompressionMetric();
        m.kind = Kind.CCR_RETRIEVAL;
        m.algorithm = "ccr"; // hash itself isn't stored — only the hit/miss matters for the rate
        m.ccrHit = hit;
        persist(m);
    }

    private static void persist(CompressionMetric m) {
        try {
            Tx.run(() -> {
                m.save();
                return null;
            });
        } catch (RuntimeException e) {
            // Metrics are best-effort; a write failure must never disrupt the run.
            play.Logger.debug("[metrics] dropped a compression metric: %s", e.getMessage());
        }
    }

    // ----------------------------------------------------------- aggregation

    public record Summary(
            long tokensSaved24h, long tokensSaved7d, long tokensSaved30d,
            List<TypeRatio> ratioByType,
            List<AlgoUsage> algorithmUsage,
            long inflationGuardCount,
            long ccrRetrievals, long ccrHits, double ccrHitRate,
            List<String> alerts) {}

    public record TypeRatio(String contentType, long tokensBefore, long tokensAfter, double ratio) {}

    public record AlgoUsage(String algorithm, long count, long tokensSaved) {}

    /** Full per-agent dashboard summary over the trailing 30 days (with 24h/7d savings rollups). */
    public static Summary summary(String agentId) {
        return Tx.run(() -> {
            var now = Instant.now();
            var since30 = now.minus(30, ChronoUnit.DAYS);
            var byType = ratioByType(agentId, since30);
            var algos = algorithmUsage(agentId, since30);
            long guards = countByKind(agentId, Kind.INFLATION_GUARD, since30);
            long compEvents = countByKind(agentId, Kind.COMPRESSION, since30);
            long ccrTotal = ccrCount(null);
            long ccrHits = ccrCount(Boolean.TRUE);
            double hitRate = ccrTotal == 0 ? 0.0 : (double) ccrHits / ccrTotal;
            return new Summary(
                    tokensSaved(agentId, now.minus(1, ChronoUnit.DAYS)),
                    tokensSaved(agentId, now.minus(7, ChronoUnit.DAYS)),
                    tokensSaved(agentId, since30),
                    byType, algos, guards,
                    ccrTotal, ccrHits, hitRate,
                    computeAlerts(byType, guards, compEvents, ccrTotal, hitRate));
        });
    }

    private static long tokensSaved(String agentId, Instant since) {
        var n = (Number) JPA.em().createQuery(
                        "select coalesce(sum(m.tokensBefore - m.tokensAfter), 0) from CompressionMetric m "
                                + "where m.kind = :k and m.agentId = :a and m.createdAt >= :s")
                .setParameter("k", Kind.COMPRESSION)
                .setParameter("a", agentId)
                .setParameter("s", since)
                .getSingleResult();
        return n.longValue();
    }

    private static List<TypeRatio> ratioByType(String agentId, Instant since) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = JPA.em().createQuery(
                        "select m.contentType, sum(m.tokensBefore), sum(m.tokensAfter) from CompressionMetric m "
                                + "where m.kind = :k and m.agentId = :a and m.createdAt >= :s group by m.contentType")
                .setParameter("k", Kind.COMPRESSION)
                .setParameter("a", agentId)
                .setParameter("s", since)
                .getResultList();
        var out = new ArrayList<TypeRatio>();
        for (var r : rows) {
            long before = ((Number) r[1]).longValue();
            long after = ((Number) r[2]).longValue();
            out.add(new TypeRatio((String) r[0], before, after, before == 0 ? 0.0 : (double) after / before));
        }
        return out;
    }

    private static List<AlgoUsage> algorithmUsage(String agentId, Instant since) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = JPA.em().createQuery(
                        "select m.algorithm, count(m), coalesce(sum(m.tokensBefore - m.tokensAfter), 0) "
                                + "from CompressionMetric m "
                                + "where m.kind = :k and m.agentId = :a and m.createdAt >= :s group by m.algorithm")
                .setParameter("k", Kind.COMPRESSION)
                .setParameter("a", agentId)
                .setParameter("s", since)
                .getResultList();
        var out = new ArrayList<AlgoUsage>();
        for (var r : rows) {
            out.add(new AlgoUsage((String) r[0], ((Number) r[1]).longValue(), ((Number) r[2]).longValue()));
        }
        return out;
    }

    private static long countByKind(String agentId, Kind kind, Instant since) {
        var n = (Number) JPA.em().createQuery(
                        "select count(m) from CompressionMetric m "
                                + "where m.kind = :k and m.agentId = :a and m.createdAt >= :s")
                .setParameter("k", kind)
                .setParameter("a", agentId)
                .setParameter("s", since)
                .getSingleResult();
        return n.longValue();
    }

    /** CCR retrievals are agent-less (the tool has no agent in scope), so this is a global rate. */
    private static long ccrCount(Boolean hit) {
        var q = JPA.em().createQuery(hit == null
                ? "select count(m) from CompressionMetric m where m.kind = :k"
                : "select count(m) from CompressionMetric m where m.kind = :k and m.ccrHit = :h")
                .setParameter("k", Kind.CCR_RETRIEVAL);
        if (hit != null) q.setParameter("h", hit);
        return ((Number) q.getSingleResult()).longValue();
    }

    private static List<String> computeAlerts(List<TypeRatio> byType, long guards, long compEvents,
                                              long ccrTotal, double ccrHitRate) {
        var alerts = new ArrayList<String>();
        for (var t : byType) {
            if (t.tokensBefore() > 0 && t.ratio() < LOW_RATIO) {
                alerts.add("%s compression ratio is very low (%.0f%% kept) — verify it isn't dropping needed data"
                        .formatted(t.contentType(), t.ratio() * 100));
            }
        }
        long attempts = compEvents + guards;
        if (attempts > 0 && (double) guards / attempts > HIGH_GUARD_RATE) {
            alerts.add("Inflation-guard rate is %.0f%% — the compressor is frequently inflating"
                    .formatted(100.0 * guards / attempts));
        }
        if (ccrTotal > 0 && ccrHitRate < LOW_CCR_HIT_RATE) {
            alerts.add("CCR cache hit rate is %.0f%% — the model may not be retrieving when it should"
                    .formatted(ccrHitRate * 100));
        }
        return alerts;
    }
}
