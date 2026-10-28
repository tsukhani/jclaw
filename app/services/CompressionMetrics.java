package services;

import models.CompressionMetric;
import models.CompressionMetric.Kind;
import org.jspecify.annotations.Nullable;
import play.Logger;

/**
 * Records compression-pipeline events (JCLAW-467). Recording is best-effort — a
 * failed write never disrupts an agent run — and skipped for agent-less events
 * (the pure pipeline test seam passes a null agentId). Aggregation happens
 * client-side over the raw rows the metrics endpoint returns (mirroring the Chat
 * Cost dashboard), so this service only writes and resets.
 */
public final class CompressionMetrics {

    private CompressionMetrics() {}

    public static void recordCompression(@Nullable String agentId, @Nullable String channel, @Nullable String toolName,
                                         String resultHash, String modelId, String contentType, String algorithm,
                                         int tokensBefore, int tokensAfter) {
        if (agentId == null) return; // agent-less (test seam) — nothing to attribute
        var m = new CompressionMetric();
        m.kind = Kind.COMPRESSION;
        m.agentId = agentId;
        m.channel = channel;
        m.toolName = toolName;
        m.resultHash = resultHash;
        m.modelId = modelId;
        m.contentType = contentType;
        m.algorithm = algorithm;
        m.tokensBefore = tokensBefore;
        m.tokensAfter = tokensAfter;
        persist(m);
    }

    public static void recordInflationGuard(@Nullable String agentId, @Nullable String channel, String modelId, String contentType,
                                            int tokensBefore, int tokensAfter) {
        if (agentId == null) return;
        var m = new CompressionMetric();
        m.kind = Kind.INFLATION_GUARD;
        m.agentId = agentId;
        m.channel = channel;
        m.modelId = modelId;
        m.contentType = contentType;
        m.tokensBefore = tokensBefore;
        m.tokensAfter = tokensAfter;
        persist(m);
    }

    /** One {@code ccr_retrieve} lookup: the calling agent and, on a hit, the tool and result that came back. */
    public static void recordCcrRetrieval(@Nullable String agentId, @Nullable String toolName,
                                          @Nullable String resultHash, boolean hit) {
        var m = new CompressionMetric();
        m.kind = Kind.CCR_RETRIEVAL;
        m.agentId = agentId;
        m.algorithm = "ccr";
        m.ccrHit = hit;
        m.toolName = hit ? toolName : null;
        m.resultHash = hit ? resultHash : null;
        persist(m);
    }

    /** Delete every recorded metric (operator-triggered reset). Returns the row count removed. */
    public static long reset() {
        return Tx.run(() -> (long) CompressionMetric.deleteAll());
    }

    private static void persist(CompressionMetric m) {
        try {
            Tx.run(() -> {
                m.save();
                return null;
            });
        } catch (RuntimeException e) {
            // Metrics are best-effort; a write failure must never disrupt the run.
            Logger.debug("[metrics] dropped a compression metric: %s", e.getMessage());
        }
    }
}
