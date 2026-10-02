package memory.graph;

import models.Agent;
import org.jspecify.annotations.Nullable;
import play.db.jpa.JPA;
import services.EventLogger;
import services.Tx;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

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
     * @param recordsRemoved  records withdrawn because their memory is gone, superseded or another agent's
     */
    public record ReconcileResult(int agents, int recovered, int orphansDeleted, int recordsRemoved) {}

    @FunctionalInterface
    private interface GraphAction {
        void run() throws Exception;
    }

    private GraphLifecycle() {}

    public static void withdrawAfterCommit(long agentId, long memoryId) {
        Tx.afterCommit(() -> guarded(agentId, "withdraw memory " + memoryId,
                () -> GraphStore.get().withdraw(agentId, Set.of(memoryId))));
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

    /** Recover, drop orphaned agents, and withdraw every memory source that no longer backs a live row. */
    public static ReconcileResult reconcile(GraphStore store) throws IOException {
        int agents = 0;
        int recovered = 0;
        int orphans = 0;
        int removed = 0;
        for (var agentId : store.agentIds()) {
            agents++;
            if (store.recover(agentId)) recovered++;
            if (Tx.run(() -> Agent.findById(agentId) == null)) {
                store.deleteAgent(agentId);
                orphans++;
                continue;
            }
            var referenced = new TreeSet<Long>();
            for (var record : store.read(agentId)) {
                var memoryId = memoryId(GraphStore.sourceOf(record));
                if (memoryId != null) referenced.add(memoryId);
            }
            if (referenced.isEmpty()) continue;
            var live = liveMemoryIds(agentId, referenced);
            var stale = new TreeSet<>(referenced);
            stale.removeAll(live);
            if (!stale.isEmpty()) removed += store.withdraw(agentId, stale).size();
        }
        return new ReconcileResult(agents, recovered, orphans, removed);
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

    private static Set<Long> liveMemoryIds(long agentId, Set<Long> ids) {
        var live = new TreeSet<Long>();
        var batch = new ArrayList<Long>();
        for (var id : ids) {
            batch.add(id);
            if (batch.size() == 500) {
                live.addAll(liveBatch(agentId, batch));
                batch.clear();
            }
        }
        if (!batch.isEmpty()) live.addAll(liveBatch(agentId, batch));
        return live;
    }

    private static List<Long> liveBatch(long agentId, List<Long> ids) {
        var copy = List.copyOf(ids);
        return Tx.run(() -> JPA.em().createQuery(
                        "SELECT m.id FROM Memory m WHERE m.id IN :ids AND m.agent.id = :agent "
                                + "AND m.supersededAt IS NULL", Long.class)
                .setParameter("ids", copy)
                .setParameter("agent", agentId)
                .getResultList());
    }
}
