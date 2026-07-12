package services;

import jakarta.persistence.EntityManager;
import memory.MemoryStoreFactory;
import models.Agent;
import play.db.jpa.JPA;
import services.search.LuceneIndexer;

import java.util.ArrayList;
import java.util.List;

/**
 * Deletion-cascade collaborator for agents (JCLAW-728), extracted from {@link AgentService#delete}.
 * The DB-level {@code ON DELETE CASCADE} (JCLAW-542) removes every FK-linked child when the root
 * Agent row goes, but three resources live outside the FK graph and need an explicit sweep over the
 * sub-agent subtree. This class owns that out-of-band teardown across the five subsystems it used to
 * be feature-envious of — memory store, native-SQL config rows, the external Lucene index (three
 * scopes), and the filesystem workspace — then triggers the single cascading root delete.
 *
 * <p>Coordinates with the FK/cascade design (JCLAW-540): introduces <b>no new DB-level cascades</b>.
 * It only reaches the stores the DB cascade structurally cannot (external index, string-keyed config,
 * on-disk files).
 */
public final class AgentDeletionCascade {

    private AgentDeletionCascade() {}

    private static final String LOG_CATEGORY = "agent";

    /**
     * Delete an agent and its entire sub-agent subtree. Every FK from a child
     * row back to this agent (or any descendant) carries {@code ON DELETE CASCADE}
     * (JCLAW-542), so deleting the root Agent row removes all descendant agents
     * and every FK-linked child — conversations, messages, attachments, session
     * compactions, tasks, task runs and their messages, subagent-run audit rows,
     * channel bindings, skill/tool configs, tool-approval grants, and
     * notifications — in one statement whose delete order the database computes.
     *
     * <p>Three resources live outside the FK graph and are still cleaned up by an
     * explicit walk over the subtree, since {@code ON DELETE CASCADE} governs only
     * rows in FK-linked tables:
     * <ul>
     *   <li>the on-disk workspace directory of each agent;</li>
     *   <li>{@code agent.{name}.*} config rows (keyed by a string LIKE, not an FK);</li>
     *   <li>Lucene index docs for each agent's memories — an external store the DB
     *       cascade of the memory rows themselves cannot reach, so deletion is
     *       routed through {@link MemoryStoreFactory}.</li>
     * </ul>
     *
     * <p>Workspace directories are removed last, after DB state is clean, so a
     * failed delete leaves the filesystem in a recoverable state.
     *
     * @param agent the agent to delete (must have a persisted id)
     */
    public static void delete(Agent agent) {
        var rootId = agent.id;

        // Collect the whole sub-agent subtree (root + all transitive
        // descendants) up front, before anything is deleted. We need the set
        // only for the out-of-band cleanup below; the database computes the
        // actual row-delete order via the cascade when the root is removed.
        var subtree = collectSubtree(agent);
        var names = subtree.stream().map(a -> a.name).toList();

        var em = JPA.em();
        // Out-of-band cleanup, per subtree node — the cascade only governs rows
        // in FK-linked tables, never the filesystem, string-keyed config, or the
        // external Lucene index:
        //   - Memory rows cascade at the DB, but their Lucene docs are external,
        //     so route deletion through the store to evict the index entries too.
        //   - agent.<name>.* config rows are keyed by a LIKE, not an FK. Native
        //     SQL, not a bulk HQL Config.delete: the HQL id-table DDL emits the
        //     entity attribute `key` unquoted, which H2 rejects as reserved.
        for (var node : subtree) {
            MemoryStoreFactory.get().deleteAll(String.valueOf(node.id));
            em.createNativeQuery("DELETE FROM config WHERE config_key LIKE ?1")
                    .setParameter(1, AgentService.AGENT_CONFIG_PREFIX + node.name + ".%").executeUpdate();
        }
        ConfigService.clearCache();
        // JCLAW-673: evict the subtree's SUBAGENT_RUN + TASK full-text docs while
        // the rows still exist. The DB cascade removes the rows but never fires
        // SubagentRun/Task @PostRemove, so their Lucene docs would orphan — the
        // same problem MEMORY avoids above by routing through MemoryStoreFactory.
        evictSubtreeLuceneDocs(em, subtree);
        em.flush();
        em.clear();
        // Re-fetch the root as a managed entity; its delete cascades the whole
        // subtree of Agent rows plus every FK-linked child at the DB level.
        Agent root = Agent.findById(rootId);
        root.delete();
        em.flush();

        // Workspace dirs last, after DB state is clean. One per subtree node.
        for (var name : names) {
            WorkspaceFiles.deleteWorkspaceDirectory(name);
            EventLogger.info(LOG_CATEGORY, name, null, "Agent deleted");
        }
    }

    /**
     * JCLAW-673: remove the SUBAGENT_RUN, TASK, and TASK_RUN_MESSAGE Lucene docs
     * for an entire agent subtree before its rows cascade-delete. A cascade /
     * bulk delete never fires the entities' {@code @PostRemove} hooks, so their
     * full-text docs would linger after the rows are gone. Collects the doc ids
     * by querying the still-present rows (SubagentRun on either agent end of the
     * subtree; Task owned by any subtree agent; TaskRunMessage under those
     * tasks' runs, which cascade-delete transitively when the agent is removed),
     * removes each doc, and commits each scope once. No-op scopes (empty result,
     * or a closed index) cost nothing.
     */
    private static void evictSubtreeLuceneDocs(EntityManager em, List<Agent> subtree) {
        var subtreeIds = subtree.stream().map(a -> a.id).toList();
        List<Long> subagentRunIds = em.createQuery(
                "SELECT sr.id FROM SubagentRun sr "
                        + "WHERE sr.parentAgent.id IN :ids OR sr.childAgent.id IN :ids", Long.class)
                .setParameter("ids", subtreeIds).getResultList();
        List<Long> taskIds = em.createQuery(
                "SELECT t.id FROM Task t WHERE t.agent.id IN :ids", Long.class)
                .setParameter("ids", subtreeIds).getResultList();
        List<Long> taskRunMessageIds = em.createQuery(
                "SELECT m.id FROM TaskRunMessage m WHERE m.taskRun.task.agent.id IN :ids", Long.class)
                .setParameter("ids", subtreeIds).getResultList();
        for (Long runId : subagentRunIds) {
            LuceneIndexer.remove(LuceneIndexer.Scope.SUBAGENT_RUN, runId);
        }
        if (!subagentRunIds.isEmpty()) {
            LuceneIndexer.commit(LuceneIndexer.Scope.SUBAGENT_RUN);
        }
        for (Long taskId : taskIds) {
            LuceneIndexer.remove(LuceneIndexer.Scope.TASK, taskId);
        }
        if (!taskIds.isEmpty()) {
            LuceneIndexer.commit(LuceneIndexer.Scope.TASK);
        }
        // TASK_RUN_MESSAGE docs orphan too: an agent delete cascades
        // Task -> TaskRun -> TaskRunMessage, and none of those fire @PostRemove.
        for (Long messageId : taskRunMessageIds) {
            LuceneIndexer.remove(messageId);
        }
        if (!taskRunMessageIds.isEmpty()) {
            LuceneIndexer.commit(LuceneIndexer.Scope.TASK_RUN_MESSAGE);
        }
    }

    /**
     * Direct children of {@code parentId} — Agent rows whose
     * {@code parent_agent_id} equals it. Fetched eagerly into a typed list so
     * {@link #delete} can mutate the underlying rows during iteration without
     * tripping a ConcurrentModificationException from a live result-set cursor.
     * Sub-agents typically have shallow fan-out (1–3 children per spawn round)
     * so the per-call cost is bounded.
     */
    private static List<Agent> findDirectChildren(Long parentId) {
        return Agent.<Agent>find("parentAgent.id = ?1", parentId).fetch();
    }

    /**
     * The agent plus all transitive sub-agent descendants (rows reachable by
     * walking {@code parent_agent_id} downward), gathered depth-first. Used by
     * {@link #delete} to run the out-of-band cleanup (workspace dirs, config
     * rows, Lucene docs) on every node before one cascading root delete clears
     * the DB rows themselves.
     */
    private static List<Agent> collectSubtree(Agent root) {
        var acc = new ArrayList<Agent>();
        collectSubtreeInto(root, acc);
        return acc;
    }

    private static void collectSubtreeInto(Agent node, List<Agent> acc) {
        for (var child : findDirectChildren(node.id)) {
            collectSubtreeInto(child, acc);
        }
        acc.add(node);
    }
}
