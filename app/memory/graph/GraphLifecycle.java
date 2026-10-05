package memory.graph;

import memory.graph.GraphWithdrawal.Retirement;
import models.Agent;
import org.jspecify.annotations.Nullable;
import play.db.jpa.JPA;
import services.EventLogger;
import services.Tx;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Keeps each agent's graph from outliving the memories and the agent it rests on. The hooks
 * run only once the memory change commits, and never throw into the commit; {@link #reconcile}
 * repairs at boot whatever a crash kept a hook from doing.
 */
public final class GraphLifecycle {

    private static final String CATEGORY = "memory";

    /**
     * @param agents          agent directories examined
     * @param recovered       interrupted swaps repaired
     * @param orphansDeleted  directories removed because their agent row is gone
     * @param recordsRemoved  records withdrawn because their memory is gone or another agent's
     * @param evidenceRetired Evidence whose retirement was set, changed or cleared from its row
     * @param lineageCleared  Evidence whose lineage was cleared because its retiredBy changed or cleared
     */
    public record ReconcileResult(int agents, int recovered, int orphansDeleted, int recordsRemoved,
            int evidenceRetired, int lineageCleared) {}

    @FunctionalInterface
    private interface GraphAction {
        void run() throws Exception;
    }

    private GraphLifecycle() {}

    public static void withdrawAfterCommit(long agentId, long memoryId) {
        Tx.afterCommit(() -> guarded(agentId, "withdraw memory " + memoryId,
                () -> GraphStore.get().withdraw(agentId, Set.of(memoryId))));
    }

    /** Once the supersession commits, stamp the memory's Evidence with the committed row's values. */
    public static void retireAfterCommit(long agentId, long memoryId) {
        Tx.afterCommit(() -> guarded(agentId, "retire memory " + memoryId, () -> {
            var retirement = committedRetirement(agentId, memoryId);
            if (retirement == null || retirement.at() == null) return;
            GraphStore.get().retire(agentId, Map.of(memoryId, retirement));
        }));
    }

    /**
     * The row as committed, read on a fresh thread: the committing thread still has its JPA
     * context bound, so a {@code Tx.run} there would read the completed session's entity.
     */
    private static @Nullable Retirement committedRetirement(long agentId, long memoryId) throws Exception {
        var ref = new AtomicReference<@Nullable Retirement>();
        var err = new AtomicReference<@Nullable Throwable>();
        var reader = Thread.ofVirtual().start(() -> {
            try {
                ref.set(rowBatch(agentId, List.of(memoryId)).get(memoryId));
            } catch (Throwable t) {
                err.set(t);
            }
        });
        reader.join();
        var failure = err.get();
        if (failure instanceof Exception e) throw e;
        if (failure != null) throw new IllegalStateException(failure);
        return ref.get();
    }

    public static void withdrawAllAfterCommit(long agentId) {
        Tx.afterCommit(() -> guarded(agentId, "withdraw all memory evidence",
                () -> GraphStore.get().withdrawAllMemoryEvidence(agentId)));
    }

    public static void deleteAgentAfterCommit(long agentId) {
        Tx.afterCommit(() -> guarded(agentId, "delete graph", () -> GraphStore.get().deleteAgent(agentId)));
    }

    private static void guarded(long agentId, String what, GraphAction action) {
        try {
            action.run();
        } catch (Exception e) {
            EventLogger.warn(CATEGORY, String.valueOf(agentId), null,
                    "Memory graph %s failed: %s".formatted(what, e.getMessage()));
        }
    }

    public static ReconcileResult reconcile() throws IOException {
        return reconcile(GraphStore.get());
    }

    /**
     * Recover, drop orphaned agents, withdraw every memory source with no row of this agent's,
     * and sync each remaining source's retirement from its row.
     */
    public static ReconcileResult reconcile(GraphStore store) throws IOException {
        int agents = 0;
        int recovered = 0;
        int orphans = 0;
        int removed = 0;
        int retired = 0;
        int cleared = 0;
        for (var agentId : store.agentIds()) {
            agents++;
            try {
                if (store.recover(agentId)) recovered++;
                if (Boolean.TRUE.equals(Tx.run(() -> Agent.findById(agentId) == null))) {
                    store.deleteAgent(agentId);
                    orphans++;
                    continue;
                }
                var referenced = referencedMemoryIds(store, agentId);
                if (referenced.isEmpty()) continue;
                var rows = rows(agentId, referenced);
                var stale = new TreeSet<>(referenced);
                stale.removeAll(rows.keySet());
                // Withdraw first, so a refused retire cannot block a withdrawal.
                if (!stale.isEmpty()) removed += store.withdraw(agentId, stale).size();
                if (!rows.isEmpty()) {
                    var stamped = store.retire(agentId, rows);
                    retired += stamped.evidenceRetired();
                    cleared += stamped.lineageCleared();
                }
            } catch (IOException | RuntimeException e) {
                // One unreadable graph must not keep every later agent from being repaired.
                EventLogger.warn(CATEGORY, String.valueOf(agentId), null,
                        "Memory graph reconcile failed: %s".formatted(e.getMessage()));
            }
        }
        return new ReconcileResult(agents, recovered, orphans, removed, retired, cleared);
    }

    private static Set<Long> referencedMemoryIds(GraphStore store, long agentId) throws IOException {
        var referenced = new TreeSet<Long>();
        for (var record : store.read(agentId)) {
            var memoryId = memoryId(GraphStore.sourceOf(record));
            if (memoryId != null) referenced.add(memoryId);
        }
        return referenced;
    }

    private static @Nullable Long memoryId(@Nullable String source) {
        if (source == null || !source.startsWith(GraphStore.MEMORY_SOURCE_PREFIX)) return null;
        var digits = source.substring(GraphStore.MEMORY_SOURCE_PREFIX.length());
        if (digits.isEmpty() || !digits.chars().allMatch(Character::isDigit)) return null;
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException _) {
            return null;
        }
    }

    /** Each id's retirement from its row, for the rows that exist and belong to the agent. */
    private static Map<Long, Retirement> rows(long agentId, Set<Long> ids) {
        var rows = new TreeMap<Long, Retirement>();
        var batch = new ArrayList<Long>();
        for (var id : ids) {
            batch.add(id);
            if (batch.size() == 500) {
                rows.putAll(rowBatch(agentId, batch));
                batch.clear();
            }
        }
        if (!batch.isEmpty()) rows.putAll(rowBatch(agentId, batch));
        return rows;
    }

    private static Map<Long, Retirement> rowBatch(long agentId, List<Long> ids) {
        var copy = List.copyOf(ids);
        var result = Tx.run(() -> JPA.em().createQuery(
                        "SELECT m.id, m.supersededAt, m.supersededById FROM Memory m "
                                + "WHERE m.id IN :ids AND m.agent.id = :agent", Object[].class)
                .setParameter("ids", copy)
                .setParameter("agent", agentId)
                .getResultList());
        var rows = new TreeMap<Long, Retirement>();
        for (var row : result) {
            rows.put((Long) row[0], Retirement.of((@Nullable Instant) row[1], (@Nullable Long) row[2]));
        }
        return rows;
    }
}
