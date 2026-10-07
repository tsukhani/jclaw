package services.grapheval;

import com.google.gson.JsonObject;
import memory.graph.GraphStore;
import memory.graph.RunLedger;
import memory.graph.RunLedger.DecisionEntry;
import memory.graph.RunLedger.Outcome;
import services.decision.JevApi;
import services.grapheval.ExtractionPipeline.CaseRun;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/**
 * Mints run ids and ledger entries (JCLAW-1371). A run id is a hash of what the run read and what read it, so
 * extracting an unchanged memory again under the same model version, certificate and fingerprints names the same run.
 */
public final class RunEntries {

    private RunEntries() {}

    /** {@code run@<12 hex>} over the canonical {@code {agentId, source, text, digest, certificate, schema, extraction}}. */
    public static String runId(long agentId, String source, String memoryText, String digest, String certificate,
                               String schemaFp, String extractionFp) {
        var o = new JsonObject();
        o.addProperty("agentId", agentId);
        o.addProperty("source", source);
        o.addProperty("text", textHash(memoryText));
        o.addProperty("digest", digest);
        o.addProperty("certificate", certificate);
        o.addProperty("schema", schemaFp);
        o.addProperty("extraction", extractionFp);
        return Fingerprints.hex12("run", o);
    }

    /** {@code sha256:} and the full lowercase hex SHA-256 of the text as UTF-8. */
    public static String textHash(String text) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /**
     * The ledger entry for {@code run} over memory {@code memoryId}, its decisions in run order.
     *
     * @throws IllegalArgumentException when the run carries no schema
     */
    public static RunLedger.Entry entry(long agentId, long memoryId, String memoryText, CaseRun run, Outcome outcome,
                                        String model, String digest, String certificate) {
        var schema = run.schema();
        if (schema == null) throw new IllegalArgumentException("a run without its schema has no fingerprints");
        var schemaFp = schema.fingerprint();
        var extractionFp = ExtractionPipeline.fingerprint(schema);
        var source = GraphStore.memorySource(memoryId);
        var decisions = new ArrayList<DecisionEntry>();
        for (var d : run.decisions()) {
            var relation = d.stage().equals(ExtractionPipeline.RELATION) ? d.choice() : null;
            if (d.failed()) {
                decisions.add(new DecisionEntry(d.stage(), d.subject(), relation, Map.of(), null,
                        Objects.requireNonNullElse(d.failure(), JevApi.INVALID), false));
            } else if (d.operator()) {
                decisions.add(new DecisionEntry(d.stage(), d.subject(), relation, Map.of(), null, null, true));
            } else if (!d.probabilities().isEmpty()) {
                decisions.add(new DecisionEntry(d.stage(), d.subject(), relation, d.probabilities(), null, null,
                        false));
            } else {
                decisions.add(new DecisionEntry(d.stage(), d.subject(), relation, Map.of(), d.confidence(), null,
                        false));
            }
        }
        return new RunLedger.Entry(runId(agentId, source, memoryText, digest, certificate, schemaFp, extractionFp),
                agentId, source, textHash(memoryText), outcome, model, digest, certificate, schemaFp, extractionFp,
                false, decisions);
    }
}
