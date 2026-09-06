package services;

import models.Memory;
import org.jspecify.annotations.Nullable;

/**
 * JCLAW-153: entity-lookup accessor for Memory rows so controllers route their
 * finder calls through the service layer instead of reaching into raw
 * {@code Memory.findById(...)}. Thin passthrough that relies on the caller's
 * ambient JPA transaction — no {@link Tx} wrapper — matching
 * {@link AgentService#findById}.
 */
public final class MemoryService {

    private MemoryService() {}

    /** Play's {@code Model.findById} returns null for a missing row, so this can too
     *  (JCLAW-1160). */
    public static @Nullable Memory findById(Long id) {
        return Memory.findById(id);
    }
}
