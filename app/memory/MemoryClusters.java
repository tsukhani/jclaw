package memory;

import models.Memory;
import models.MemoryScope;
import org.jspecify.annotations.Nullable;
import services.ConfigService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * Widens a recall to whole linked clusters (GH-15): a routine and the addenda that extend it are
 * stored as separate rows, and a query that matches one step must still inject the full procedure.
 *
 * <p>A cluster is the connected component over {@code extendsId} and {@code supersededById},
 * followed both ways through retired rows, so an addendum linked to a routine that was later
 * replaced still reaches the replacement. Only live rows visible in the recall scope and not
 * already shown are injected. Runs inside the caller's transaction.
 */
public final class MemoryClusters {

    public static final String CLUSTER_MAX_KEY = "memory.recall.clusterMax";

    /** Rows one cluster's traversal may discover before it stops. */
    static final int MAX_VISITED = 50;

    private MemoryClusters() {}

    /**
     * {@code selected} with each member's live cluster injected at that member's rank position,
     * contiguous and in ascending id order. A cluster over {@code memory.recall.clusterMax} keeps
     * every ranker-selected member, then the oldest of the rest.
     */
    public static List<MemoryStore.MemoryEntry> expand(String agentId, List<MemoryStore.MemoryEntry> selected,
                                                       Set<String> excludeIds, @Nullable MemoryScope scope) {
        if (selected.isEmpty()) return selected;
        Long pk;
        var byId = new LinkedHashMap<Long, MemoryStore.MemoryEntry>();
        try {
            pk = Long.valueOf(agentId.strip());
            for (var e : selected) byId.put(Long.valueOf(e.id()), e);
        } catch (NumberFormatException _) {
            return selected;
        }
        var linked = linkedSeeds(pk, byId.keySet(), scope);
        if (linked.isEmpty()) return selected;

        int clusterMax = Math.max(1, ConfigService.getInt(CLUSTER_MAX_KEY, 10));
        var out = new ArrayList<MemoryStore.MemoryEntry>();
        var emitted = new HashSet<Long>();
        for (var entry : selected) {
            long id = Long.parseLong(entry.id());
            if (emitted.contains(id)) continue;
            if (!linked.contains(id)) {
                out.add(entry);
                emitted.add(id);
                continue;
            }
            var live = component(pk, id, scope).stream()
                    .filter(m -> m.supersededAt == null && !excludeIds.contains(String.valueOf(m.id))
                            && !emitted.contains(m.id))
                    .sorted(Comparator.comparing((Memory m) -> m.id))
                    .toList();
            var kept = keep(live, byId.keySet(), clusterMax);
            var cluster = new TreeMap<Long, MemoryStore.MemoryEntry>();
            for (var m : kept) {
                var own = byId.get(m.id);
                cluster.put(m.id, own != null ? own : toEntry(m, entry.relevance()));
            }
            // The seed is live and visible by construction; this keeps it if a fetch cap cut it.
            cluster.putIfAbsent(id, entry);
            out.addAll(cluster.values());
            emitted.addAll(cluster.keySet());
        }
        return out;
    }

    /** The ranker-selected members, then the oldest others, up to {@code clusterMax}; {@code live} is in id order. */
    private static List<Memory> keep(List<Memory> live, Set<Long> selected, int clusterMax) {
        var kept = new ArrayList<Memory>();
        for (var m : live) if (selected.contains(m.id)) kept.add(m);
        for (var m : live) {
            if (kept.size() >= clusterMax) break;
            if (!selected.contains(m.id)) kept.add(m);
        }
        return kept;
    }

    /** The seeds with at least one edge, found in one query; all of them when that query hit its cap. */
    private static Set<Long> linkedSeeds(Long pk, Set<Long> seeds, @Nullable MemoryScope scope) {
        int cap = MAX_VISITED * seeds.size();
        var rows = neighbours(pk, seeds, scope, cap);
        if (rows.size() >= cap) return seeds;
        var linked = new HashSet<Long>();
        for (var row : rows) {
            if (seeds.contains(row.id) && (row.extendsId != null || row.supersededById != null)) linked.add(row.id);
            if (row.extendsId != null && seeds.contains(row.extendsId)) linked.add(row.extendsId);
            if (row.supersededById != null && seeds.contains(row.supersededById)) linked.add(row.supersededById);
        }
        return linked;
    }

    /** Breadth-first over both edges in both directions, one query per round, at most {@link #MAX_VISITED} rows. */
    private static Collection<Memory> component(Long pk, Long seed, @Nullable MemoryScope scope) {
        var discovered = new LinkedHashSet<Long>(List.of(seed));
        var loaded = new HashMap<Long, Memory>();
        Set<Long> frontier = Set.of(seed);
        while (!frontier.isEmpty()) {
            var next = new HashSet<Long>();
            for (var row : neighbours(pk, frontier, scope, 2 * MAX_VISITED)) {
                if (!discovered.contains(row.id)) {
                    if (discovered.size() >= MAX_VISITED) continue;
                    discovered.add(row.id);
                    next.add(row.id);
                }
                loaded.putIfAbsent(row.id, row);
                for (var pointer : new Long[] {row.extendsId, row.supersededById}) {
                    if (pointer != null && discovered.size() < MAX_VISITED && discovered.add(pointer)) next.add(pointer);
                }
            }
            frontier = next;
        }
        return loaded.values();
    }

    /** The visible rows of {@code ids}, and those pointing at one of them, retired rows included. */
    private static List<Memory> neighbours(Long pk, Collection<Long> ids, @Nullable MemoryScope scope, int limit) {
        return Memory.findVisible(scope,
                "agent.id = ?1 AND (id IN (?2) OR extendsId IN (?2) OR supersededById IN (?2)) ORDER BY id",
                pk, List.copyOf(ids)).fetch(limit);
    }

    private static MemoryStore.MemoryEntry toEntry(Memory m, double relevance) {
        return new MemoryStore.MemoryEntry(String.valueOf(m.id), String.valueOf(m.agent.id), m.text, m.category,
                m.importance, m.createdAt, relevance, m.recencyAnchor());
    }
}
