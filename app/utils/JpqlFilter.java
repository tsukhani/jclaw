package utils;

import java.util.ArrayList;
import java.util.List;

/**
 * Fluent builder for dynamic JPQL WHERE clauses with positional parameters.
 *
 * <p>Replaces the repeated pattern of manually tracking a {@code StringBuilder},
 * a parameter list, and a {@code paramIdx} counter across multiple controllers.
 *
 * <p>Usage:
 * <pre>
 *   var filter = new JpqlFilter()
 *       .eq("status", status)
 *       .eq("agent.id", agentId)
 *       .like("LOWER(message)", search != null ? "%" + search.toLowerCase() + "%" : null);
 *   String where = filter.toWhereClause();     // "" or "status = ?1 AND agent.id = ?2 AND ..."
 *   Object[] params = filter.params();
 * </pre>
 */
public class JpqlFilter {

    private final List<String> clauses = new ArrayList<>();
    private final List<Object> params = new ArrayList<>();
    private int idx = 1;

    /** Add an equality predicate if the value is non-null (and non-blank for strings). */
    public JpqlFilter eq(String field, Object value) {
        if (isPresent(value)) {
            clauses.add("%s = ?%d".formatted(field, idx++));
            params.add(value);
        }
        return this;
    }

    /** Add a LIKE predicate if the value is non-null and non-blank. */
    public JpqlFilter like(String field, String value) {
        if (isPresent(value)) {
            clauses.add("%s LIKE ?%d".formatted(field, idx++));
            params.add(value);
        }
        return this;
    }

    /** Add a >= predicate if the value is non-null. */
    public JpqlFilter gte(String field, Object value) {
        if (isPresent(value)) {
            clauses.add("%s >= ?%d".formatted(field, idx++));
            params.add(value);
        }
        return this;
    }

    /** Add a <= predicate if the value is non-null. */
    public JpqlFilter lte(String field, Object value) {
        if (isPresent(value)) {
            clauses.add("%s <= ?%d".formatted(field, idx++));
            params.add(value);
        }
        return this;
    }

    /** Returns the WHERE clause body (without the "WHERE" keyword), or empty string if no filters. */
    public String toWhereClause() {
        return String.join(" AND ", clauses);
    }

    /** Returns true if at least one filter was added. */
    public boolean hasFilters() {
        return !clauses.isEmpty();
    }

    /** Returns the parameter values in positional order. */
    public Object[] params() {
        return params.toArray();
    }

    /** Returns the parameter values as a list. */
    public List<Object> paramList() {
        return List.copyOf(params);
    }

    private static boolean isPresent(Object value) {
        if (value == null) return false;
        if (value instanceof String s) return !s.isBlank();
        return true;
    }
}
