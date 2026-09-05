package tools;

import agents.ToolAction;
import agents.ToolContext;
import agents.ToolRegistry;
import com.google.gson.JsonParser;
import models.Agent;
import models.Message;
import play.db.jpa.JPA;
import services.TimezoneResolver;
import services.Tx;
import services.search.LuceneIndexer;
import services.search.MessageSearch;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * JCLAW-1065: keyword search over conversation history, on the BM25 index the
 * write path already maintains. Companion to {@link ConversationHistoryTool},
 * which reads one known transcript start-to-finish; this finds the transcript
 * when the caller does not know which one it is.
 *
 * <p><b>Permission model.</b> A caller reaches its own conversations and those of
 * every agent beneath it in the subagent tree, at any depth — work it delegated is
 * still its own. It reaches no other agent's: a second top-level agent's history is
 * invisible, and so is a sibling's. The boundary is a subtree-ownership predicate
 * applied in SQL against {@code callingAgent}, which the runtime supplies; no tool
 * argument takes part in it.
 */
public class ConversationSearchTool implements ToolRegistry.Tool {

    /** Public so the default-package tests can name it without a literal. */
    public static final String TOOL_NAME = "conversation_search";
    private static final String PARAM_QUERY = "query";
    private static final String PARAM_LIMIT = "limit";

    /**
     * Stands in for "no current conversation" — the tool also runs outside a chat turn,
     * from a task fire. No row carries a negative id, so the exclusion matches nothing
     * and the query stays one static string rather than two assembled at runtime.
     */
    private static final long NO_CONVERSATION = -1L;

    /**
     * The permission boundary and the self-exclusion, as one query. Every value is bound;
     * nothing is concatenated at runtime, so there is no shape here for a reader — or a
     * scanner — to mistake for an injection site.
     */
    private static final String READABLE_JPQL = """
            SELECT m FROM Message m
             WHERE m.id IN :ids
               AND m.conversation.agent.id IN :agentIds
               AND m.conversation.id <> :excludedId
            """;
    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;

    /**
     * Message ids pulled from Lucene before the permission filter runs. The index
     * carries no owner field, so the boundary can only be applied afterwards in SQL
     * — a window near {@link #MAX_LIMIT} would let another agent's hits consume it
     * and return nothing. Kept far above the ceiling so the filter has slack.
     */
    private static final int LUCENE_WINDOW = 500;

    /**
     * Result timestamps, in the operator's zone, labeled with the zone's NAME rather
     * than its numeric offset. A model reads "11:09 +08:00" as a time it may normalize
     * and hands back 03:09 — the UTC value — still labeled +08. A named zone cannot be
     * applied arithmetically, so the value stays put. Per line, not once in a header,
     * because the compression pipeline can summarize whole lines away.
     */
    private static final DateTimeFormatter STAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** Characters of message body returned per hit. */
    private static final int SNIPPET_CHARS = 300;

    @Override
    public String name() { return TOOL_NAME; }

    @Override
    public String category() { return "System"; }

    @Override
    public String icon() { return "search"; }

    @Override
    public String shortDescription() {
        return "Search your conversation history by keyword and return matching messages.";
    }

    @Override
    public List<ToolAction> actions() {
        return List.of(new ToolAction("search",
                "Find messages matching a keyword across the conversations you can read."));
    }

    @Override
    public String description() {
        return """
                Search conversation history by keyword. Returns matching messages with their \
                conversation id, timestamp, role and a content snippet, best match first. Use \
                this to recall what was actually said in an earlier conversation when you do \
                not know which conversation it was — then pass the conversation id to \
                conversation_history for the full transcript. \
                Required: `query` (keywords; matching is whole-word, not substring). \
                Optional: `limit` (1-%d, default %d). \
                Scope: your own conversations and those of any subagent beneath you, \
                excluding the one you are in — this finds earlier conversations, not the \
                current one. It does not reach conversations belonging to any other agent. \
                Timestamps are already the user's local wall-clock time in the named zone: \
                repeat them as given, and do not convert or re-express them in UTC. The \
                timestamp beside a result is when that message was written; any time \
                mentioned inside the snippet text is quoted conversation content and may \
                be stale, so prefer the timestamp when the two disagree."""
                .formatted(MAX_LIMIT, DEFAULT_LIMIT);
    }

    @Override
    public String summary() {
        return "Keyword-search the conversations you can read.";
    }

    @Override
    public Map<String, Object> parameters() {
        var props = new LinkedHashMap<String, Object>();
        props.put(PARAM_QUERY, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                SchemaKeys.DESCRIPTION, "Keywords to search for (required)."));
        props.put(PARAM_LIMIT, Map.of(SchemaKeys.TYPE, SchemaKeys.INTEGER,
                SchemaKeys.DESCRIPTION,
                "Maximum messages to return (1-" + MAX_LIMIT + ", default " + DEFAULT_LIMIT + ")."));
        return Map.of(
                SchemaKeys.TYPE, SchemaKeys.OBJECT,
                SchemaKeys.PROPERTIES, props,
                SchemaKeys.REQUIRED, List.of(PARAM_QUERY));
    }

    /** Read-only. */
    @Override
    public boolean parallelSafe() { return true; }

    @Override
    public String execute(String argsJson, Agent callingAgent) {
        var args = JsonParser.parseString(argsJson).getAsJsonObject();
        if (!args.has(PARAM_QUERY) || args.get(PARAM_QUERY).isJsonNull()
                || args.get(PARAM_QUERY).getAsString().isBlank()) {
            return "Error: 'query' is required.";
        }
        var query = args.get(PARAM_QUERY).getAsString().strip();
        int limit = DEFAULT_LIMIT;
        if (args.has(PARAM_LIMIT) && !args.get(PARAM_LIMIT).isJsonNull()) {
            try {
                limit = Math.clamp(args.get(PARAM_LIMIT).getAsInt(), 1, MAX_LIMIT);
            } catch (NumberFormatException _) {
                return "Error: 'limit' must be an integer.";
            }
        }

        List<Long> hitIds;
        try {
            hitIds = MessageSearch.searchIds(LuceneIndexer.Scope.CONVERSATION_MESSAGE, query, LUCENE_WINDOW);
        } catch (IOException e) {
            return "Error: conversation search is unavailable (" + e.getMessage() + ").";
        }
        if (hitIds.isEmpty()) return "No messages matched \"" + query + "\".";

        final var agentId = callingAgent.id;
        final int cap = limit;
        return Tx.run(() -> render(query, readable(hitIds, agentId), hitIds, cap));
    }

    /**
     * The permission boundary: messages among {@code hitIds} owned by the caller or by
     * any agent beneath it. Ownership, not run linkage — a subagent two levels down is
     * still the caller's own work, and a conversation belonging to an unrelated agent
     * never is.
     */
    private static List<Message> readable(List<Long> hitIds, Long agentId) {
        // The caller's own question is already in this conversation and was indexed
        // before the tool ran, so without this the top hit is always the turn that
        // asked — "which conversation discussed X" answers "this one". Excluding the
        // whole conversation, not just that message, keeps a long thread from
        // crowding out the older ones the caller is actually looking for.
        var current = ToolContext.conversationId();
        @SuppressWarnings("unchecked")
        List<Message> rows = JPA.em().createQuery(READABLE_JPQL)
                .setParameter("ids", hitIds)
                .setParameter("agentIds", subtreeIds(agentId))
                .setParameter("excludedId", current != null ? current : NO_CONVERSATION)
                .getResultList();
        return rows;
    }

    /**
     * The caller plus every agent transitively beneath it, walked breadth-first because
     * JPQL has no recursive query. Subagent trees are shallow, so this costs one query
     * per level — and a cycle cannot spin it, since an id already seen is never re-queued.
     */
    private static List<Long> subtreeIds(Long rootId) {
        var all = new LinkedHashSet<Long>();
        all.add(rootId);
        var frontier = List.of(rootId);
        while (!frontier.isEmpty()) {
            @SuppressWarnings("unchecked")
            List<Long> children = JPA.em().createQuery(
                            "SELECT a.id FROM Agent a WHERE a.parentAgent.id IN :ids")
                    .setParameter("ids", frontier)
                    .getResultList();
            frontier = children.stream().filter(all::add).toList();
        }
        return List.copyOf(all);
    }

    /** Restore Lucene's relevance order, which the SQL round-trip does not preserve. */
    private static String render(String query, List<Message> rows, List<Long> order, int limit) {
        if (rows.isEmpty()) return "No messages matched \"" + query + "\".";
        var byId = new LinkedHashMap<Long, Message>();
        for (var m : rows) byId.put(m.id, m);

        var out = order.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .limit(limit)
                .map(m -> "- conversation %d | %s | %s: %s".formatted(
                        m.conversation.id, stamp(m.createdAt), m.role, snippet(m.content)))
                .toList();
        return "%d match(es) for \"%s\":%n%s".formatted(out.size(), query, String.join("\n", out));
    }

    /**
     * Wall-clock time in the operator's zone. {@code Instant.toString()} renders UTC,
     * which reads as hours adrift from the clock the operator and the assistant both
     * treat as now.
     */
    private static String stamp(Instant createdAt) {
        if (createdAt == null) return "unknown time";
        var zone = TimezoneResolver.appZone();
        return STAMP_FMT.format(createdAt.atZone(zone)) + " (" + zone.getId() + ")";
    }

    private static String snippet(String content) {
        if (content == null) return "";
        var flat = content.replace('\n', ' ').strip();
        return flat.length() <= SNIPPET_CHARS ? flat : flat.substring(0, SNIPPET_CHARS) + "…";
    }
}
