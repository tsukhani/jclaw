package services;

import play.db.jpa.JPA;
import services.search.LuceneIndexer;

import java.util.List;
import java.util.stream.Gatherers;

/**
 * Deletion-cascade collaborator for conversations, extracted from
 * {@link ConversationService#deleteByIds} (JCLAW-829) and mirroring the
 * established {@link AgentDeletionCascade} pattern. A Conversation delete now
 * carries {@code ON DELETE CASCADE} for its FK-linked children (JCLAW-542), but
 * three things live outside the FK graph and need an explicit sweep over the
 * subagent-child subtree before the root rows go:
 * <ul>
 *   <li>the on-disk attachment bytes under {@code workspace/{agent}/attachments/{conversationId}/};</li>
 *   <li>{@code SubagentRun} audit rows that FK both ends of a parent/child pair;</li>
 *   <li>the external Lucene index docs for messages and subagent runs — a store
 *       the DB cascade structurally cannot reach, and which a bulk / cascade
 *       delete never fires {@code @PostRemove} for.</li>
 * </ul>
 *
 * <p>Introduces <b>no new DB-level cascades</b> (JCLAW-540): it only reaches the
 * stores the DB cascade can't (external index, on-disk files, cross-linked audit
 * rows). The id list is chunked so no single JPQL {@code IN}-clause exceeds the
 * prod pgjdbc bind-parameter ceiling.
 */
public final class ConversationDeletionCascade {

    private ConversationDeletionCascade() {}

    /** Max conversation ids bound into one JPQL IN-clause (pgjdbc bind-param headroom). */
    private static final int ID_CHUNK_SIZE = 1000;

    /**
     * Bulk-delete conversations (and their messages) by ID using JPQL.
     * Chunks the id list so no single JPQL IN-clause exceeds the prod
     * pgjdbc bind-parameter ceiling on a large "delete all" / big-selection
     * sweep. Each chunk carries its own cascade + Lucene eviction; the
     * caller's transaction still spans them all.
     *
     * @param ids conversation ids to delete (children, SubagentRuns, and
     *            attached messages cascade automatically)
     * @return the number of conversations deleted
     */
    public static int deleteByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        int deleted = 0;
        for (var chunk : ids.stream().gather(Gatherers.windowFixed(ID_CHUNK_SIZE)).toList()) {
            deleted += deleteIdChunk(chunk);
        }
        return deleted;
    }

    /** Delete one already-chunked (≤ {@link #ID_CHUNK_SIZE}) id batch. See {@link #deleteByIds}. */
    private static int deleteIdChunk(List<Long> ids) {
        var em = JPA.em();
        // Subagent-tree cascade: a Conversation can be the parent of one or
        // more child Conversations (subagent runs), and SubagentRun audit
        // rows hold FKs to both ends. Pre-fix, bulk-delete-by-filter
        // happened to include the children in the same SQL statement so
        // the FKs were satisfied at statement-end. Now that the listing
        // filter excludes children (so /conversations doesn't double-show
        // them), explicit cascade-cleanup keeps deleteByIds working when
        // a parent has subagent children.
        // 1. Recursive deleteByIds for child conversations (depth-first —
        //    handles grandchildren / SubagentRuns / Messages / etc).
        List<Long> childIds = em.createQuery(
                "SELECT c.id FROM Conversation c WHERE c.parentConversation.id IN :ids",
                Long.class).setParameter("ids", ids).getResultList();
        if (!childIds.isEmpty()) {
            deleteByIds(childIds);
        }
        // 2. SubagentRun rows referencing any of these conversations on
        //    either side. Done after the child cascade so a recursive call
        //    doesn't try to double-delete a SubagentRun the parent's
        //    cleanup would have removed. JCLAW-673: collect the ids first so
        //    their SUBAGENT_RUN full-text docs can be evicted after the bulk
        //    JPQL DELETE, which never fires SubagentRun.@PostRemove.
        List<Long> subagentRunIds = em.createQuery(
                "SELECT sr.id FROM SubagentRun sr "
                        + "WHERE sr.parentConversation.id IN :ids "
                        + "   OR sr.childConversation.id IN :ids", Long.class)
                .setParameter("ids", ids).getResultList();
        em.createQuery(
                "DELETE FROM SubagentRun sr "
                        + "WHERE sr.parentConversation.id IN :ids "
                        + "   OR sr.childConversation.id IN :ids")
                .setParameter("ids", ids).executeUpdate();
        // 3a. JCLAW-209: free the on-disk attachment bytes before dropping the rows that
        // point at them. All of a conversation's attachments live under one directory
        // (workspace/{agent}/attachments/{conversationId}/), so a per-conversation sweep
        // reclaims them; without this the files would be orphaned on disk forever once the
        // rows are cascade-deleted below. Child conversations are handled by the recursive
        // call above. Done while the conversation→agent join is still intact.
        @SuppressWarnings("unchecked")
        List<Object[]> agentDirs = em.createQuery(
                "SELECT c.id, c.agent.name FROM Conversation c WHERE c.id IN :ids")
                .setParameter("ids", ids).getResultList();
        for (var row : agentDirs) {
            AttachmentService.deleteConversationAttachments((String) row[1], (Long) row[0]);
        }
        // 3b. JCLAW-135: collect the message ids so their CONVERSATION_MESSAGE
        // Lucene docs can be evicted after the delete. The DB cascades
        // chat_message_attachment / message / session_compaction off the
        // Conversation delete below (ON DELETE CASCADE, JCLAW-542), so the old
        // hand-ordered JPQL sweep of those three tables is gone — but a bulk /
        // cascade delete never fires Message.@PostRemove, so the full-text docs
        // would orphan without this explicit cleanup. (Child-conversation
        // messages are handled by the recursive call in step 1.)
        List<Long> messageIds = em.createQuery(
                "SELECT m.id FROM Message m WHERE m.conversation.id IN :ids", Long.class)
                .setParameter("ids", ids).getResultList();
        int deleted = em.createQuery("DELETE FROM Conversation c WHERE c.id IN :ids")
                .setParameter("ids", ids).executeUpdate();
        evictAndCommit(LuceneIndexer.Scope.CONVERSATION_MESSAGE, messageIds);
        // JCLAW-673: evict the SUBAGENT_RUN docs for the rows swept in step 2.
        evictAndCommit(LuceneIndexer.Scope.SUBAGENT_RUN, subagentRunIds);
        return deleted;
    }

    /**
     * Remove every doc for {@code ids} from {@code scope}, then commit once —
     * a bulk / cascade DELETE never fires the entities' {@code @PostRemove}, so
     * their external Lucene docs would orphan without this explicit sweep. No-op
     * (and no commit) when {@code ids} is empty.
     */
    private static void evictAndCommit(LuceneIndexer.Scope scope, List<Long> ids) {
        for (Long id : ids) {
            LuceneIndexer.remove(scope, id);
        }
        if (!ids.isEmpty()) {
            LuceneIndexer.commit(scope);
        }
    }
}
