package controllers;

import jakarta.persistence.Query;
import play.db.jpa.JPA;
import utils.JpqlFilter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JCLAW-722: shared assembly for the paginated JPQL list endpoints. Mirrors the
 * {@code TaskListQueryService} query core so the three admin list controllers
 * ({@link ApiConversationsController}, {@link ApiSubagentRunsController},
 * {@link ApiMemoryController}) stop hand-rolling the same
 * WHERE&nbsp;&rarr;&nbsp;bind&nbsp;&rarr;&nbsp;COUNT&nbsp;&rarr;&nbsp;paginate
 * sequence — each with its own risk of the COUNT and the SELECT drifting apart
 * when only one of the two parameter-binding loops is updated.
 *
 * <p>The guarantee this class exists to make: the COUNT and the SELECT bind
 * their parameters from a single source ({@link #bind}), so a positional value
 * or a named collection can never be applied to one query and forgotten on the
 * other. The COUNT deliberately omits any {@code JOIN FETCH} (a fetch join on a
 * {@code COUNT} is meaningless and some providers reject it) but shares the
 * exact same WHERE body and parameters.
 *
 * <p>Positional parameters come from a {@link JpqlFilter} (its {@code ?1..?N}
 * ordinals); named parameters (e.g. the JCLAW-304 {@code :fts} id-set) are
 * applied only when non-null, matching the {@code if (ids != null)
 * setParameter(...)} idiom the controllers used inline. The class holds no
 * per-request state beyond the builder inputs and relies on the caller's
 * ambient JPA transaction.
 *
 * <p>Lives in the {@code controllers} package alongside the sibling request
 * helpers {@link JsonBodyReader} and {@link BindingKeys} — it is a controller
 * collaborator, not a general model-layer utility.
 */
public final class PagedJpqlQuery<T> {

    /**
     * The single, named page-size cap for every migrated list endpoint. Replaces
     * the inconsistent inline caps the endpoints carried before JCLAW-722
     * (conversations capped at 100, subagent-runs and memories at 500). Each
     * endpoint keeps its own <em>default</em> page size (a product choice about
     * how many rows to return when the caller names none) but shares this upper
     * bound, so a caller can never request an unbounded page.
     */
    public static final int MAX_LIMIT = 500;

    /** One page of results plus the full match count (pre-pagination). */
    public record Page<R>(List<R> rows, long total) {}

    private final Class<T> type;
    private final String entityAndAlias;
    private final String alias;
    private String joinFetch = "";
    private String where = "";
    private String orderBy = "";
    private List<?> positional = List.of();
    private final Map<String, Object> named = new LinkedHashMap<>();
    private int offset = 0;
    private int limit = MAX_LIMIT;

    private PagedJpqlQuery(Class<T> type, String entityAndAlias, String alias) {
        this.type = type;
        this.entityAndAlias = entityAndAlias;
        this.alias = alias;
    }

    /**
     * Start a query over {@code type}, e.g.
     * {@code of(Conversation.class, "Conversation c", "c")}. {@code entityAndAlias}
     * is the FROM body ({@code "Conversation c"}); {@code alias} is the row/count
     * variable ({@code "c"}) used in {@code SELECT c} / {@code SELECT COUNT(c)}.
     */
    public static <T> PagedJpqlQuery<T> of(Class<T> type, String entityAndAlias, String alias) {
        return new PagedJpqlQuery<>(type, entityAndAlias, alias);
    }

    /** SELECT-only fetch join (e.g. {@code "JOIN FETCH c.agent"}); never applied to the COUNT. */
    public PagedJpqlQuery<T> joinFetch(String joinFetchClause) {
        this.joinFetch = joinFetchClause == null ? "" : joinFetchClause;
        return this;
    }

    /** The WHERE body without the {@code WHERE} keyword; empty string means no filter. */
    public PagedJpqlQuery<T> where(String whereBody) {
        this.where = whereBody == null ? "" : whereBody;
        return this;
    }

    /** The ORDER BY clause including the keyword (e.g. {@code "ORDER BY c.updatedAt DESC"}). */
    public PagedJpqlQuery<T> orderBy(String orderByClause) {
        this.orderBy = orderByClause == null ? "" : orderByClause;
        return this;
    }

    /** Positional {@code ?1..?N} values, in order — typically {@link JpqlFilter#paramList()}. */
    public PagedJpqlQuery<T> positionalParams(List<?> params) {
        this.positional = params == null ? List.of() : params;
        return this;
    }

    /** Bind a named parameter to BOTH queries, or skip it entirely when {@code value} is null. */
    public PagedJpqlQuery<T> namedParam(String name, Object value) {
        if (value != null) named.put(name, value);
        return this;
    }

    /** The page window: zero-based {@code offset} and page-size {@code limit}. */
    public PagedJpqlQuery<T> page(int offset, int limit) {
        this.offset = offset;
        this.limit = limit;
        return this;
    }

    /** Run the SELECT (paginated) and the COUNT (full), binding both from one source. */
    public Page<T> execute() {
        return new Page<>(rows(), count());
    }

    /** Run only the paginated SELECT — for callers (e.g. bulk delete) that don't need the total. */
    public List<T> rows() {
        var q = JPA.em().createQuery(selectJpql(), type);
        bind(q);
        return q.setFirstResult(offset).setMaxResults(limit).getResultList();
    }

    /** Run only the COUNT over the same WHERE/params. */
    public long count() {
        var q = JPA.em().createQuery(countJpql(), Long.class);
        bind(q);
        return q.getSingleResult();
    }

    private void bind(Query q) {
        for (int i = 0; i < positional.size(); i++) {
            q.setParameter(i + 1, positional.get(i));
        }
        named.forEach(q::setParameter);
    }

    private String selectJpql() {
        var sb = new StringBuilder("SELECT ").append(alias).append(" FROM ").append(entityAndAlias);
        if (!joinFetch.isEmpty()) sb.append(' ').append(joinFetch);
        if (!where.isEmpty()) sb.append(" WHERE ").append(where);
        if (!orderBy.isEmpty()) sb.append(' ').append(orderBy);
        return sb.toString();
    }

    private String countJpql() {
        var sb = new StringBuilder("SELECT COUNT(").append(alias).append(") FROM ").append(entityAndAlias);
        if (!where.isEmpty()) sb.append(" WHERE ").append(where);
        return sb.toString();
    }
}
