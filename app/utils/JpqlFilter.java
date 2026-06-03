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
public final class JpqlFilter {

    private final List<String> clauses = new ArrayList<>();
    private final List<Object> params = new ArrayList<>();
    private int idx = 1;

    /** Add an equality predicate if the value is non-null (and non-blank for strings). */
    public JpqlFilter eq(String field, Object value) {
        return add("%1$s = ?%2$d", field, value);
    }

    /**
     * Add a "{@code field IS NULL OR field <> value}" predicate if the value
     * is non-null (and non-blank for strings). The IS-NULL leg matters when
     * the column is nullable and "exclude X" should include rows that never
     * set the column at all — e.g. excluding {@code payloadType="reminder"}
     * tasks should still surface tasks whose {@code payloadType} is null.
     *
     * @param field column or path
     * @param value value to exclude; null/blank skips the predicate entirely
     * @return this filter for chaining
     */
    public JpqlFilter notEqOrNull(String field, Object value) {
        return add("(%1$s IS NULL OR %1$s <> ?%2$d)", field, value);
    }

    /** Add a LIKE predicate if the value is non-null and non-blank. */
    public JpqlFilter like(String field, String value) {
        return add("%1$s LIKE ?%2$d", field, value);
    }

    /** Add a >= predicate if the value is non-null. */
    public JpqlFilter gte(String field, Object value) {
        return add("%1$s >= ?%2$d", field, value);
    }

    /** Add a {@code <=} predicate if the value is non-null. */
    public JpqlFilter lte(String field, Object value) {
        return add("%1$s <= ?%2$d", field, value);
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

    /**
     * Shared predicate skeleton: when {@code value} is present, append the
     * positional-parameter clause and record the value, consuming one index.
     * {@code template} uses {@code %1$s} for the field and {@code %2$d} for the
     * positional index, so multi-field predicates (e.g. {@code IS NULL OR <>})
     * can reference the field more than once without repeating it as an arg.
     */
    private JpqlFilter add(String template, String field, Object value) {
        if (isPresent(value)) {
            clauses.add(template.formatted(field, idx++));
            params.add(value);
        }
        return this;
    }

    private static boolean isPresent(Object value) {
        if (value == null) return false;
        if (value instanceof String s) return !s.isBlank();
        return true;
    }
}
