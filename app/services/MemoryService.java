package services;

import models.Memory;

/**
 * JCLAW-153: entity-lookup accessor for Memory rows so controllers route their
 * finder calls through the service layer instead of reaching into raw
 * {@code Memory.findById(...)}. Thin passthrough that relies on the caller's
 * ambient JPA transaction — no {@link Tx} wrapper — matching
 * {@link AgentService#findById}.
 */
public final class MemoryService {

    private MemoryService() {}

    public static Memory findById(Long id) {
        return Memory.findById(id);
    }
}
