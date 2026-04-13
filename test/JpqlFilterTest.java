import org.junit.jupiter.api.*;
import play.test.*;
import utils.JpqlFilter;

public class JpqlFilterTest extends UnitTest {

    @Test
    public void eqAddsEqualityPredicate() {
        var filter = new JpqlFilter().eq("status", "active");
        assertEquals("status = ?1", filter.toWhereClause());
        assertArrayEquals(new Object[]{"active"}, filter.params());
    }

    @Test
    public void likeAddsLikePredicate() {
        var filter = new JpqlFilter().like("LOWER(name)", "%test%");
        assertEquals("LOWER(name) LIKE ?1", filter.toWhereClause());
        assertArrayEquals(new Object[]{"%test%"}, filter.params());
    }

    @Test
    public void gteAddsGreaterThanOrEqual() {
        var filter = new JpqlFilter().gte("age", 18);
        assertEquals("age >= ?1", filter.toWhereClause());
        assertArrayEquals(new Object[]{18}, filter.params());
    }

    @Test
    public void lteAddsLessThanOrEqual() {
        var filter = new JpqlFilter().lte("price", 99.99);
        assertEquals("price <= ?1", filter.toWhereClause());
        assertArrayEquals(new Object[]{99.99}, filter.params());
    }

    @Test
    public void multipleClauses() {
        var filter = new JpqlFilter()
                .eq("status", "active")
                .gte("age", 18)
                .lte("age", 65);
        assertEquals("status = ?1 AND age >= ?2 AND age <= ?3", filter.toWhereClause());
        assertArrayEquals(new Object[]{"active", 18, 65}, filter.params());
    }

    @Test
    public void nullValueSkipped() {
        var filter = new JpqlFilter()
                .eq("status", null)
                .eq("name", "Alice");
        assertEquals("name = ?1", filter.toWhereClause());
        assertArrayEquals(new Object[]{"Alice"}, filter.params());
    }

    @Test
    public void blankStringSkipped() {
        var filter = new JpqlFilter().eq("name", "   ");
        assertFalse(filter.hasFilters());
        assertEquals("", filter.toWhereClause());
        assertEquals(0, filter.params().length);
    }

    @Test
    public void emptyStringSkipped() {
        var filter = new JpqlFilter().eq("name", "");
        assertFalse(filter.hasFilters());
        assertEquals("", filter.toWhereClause());
    }

    @Test
    public void likeNullValueSkipped() {
        var filter = new JpqlFilter().like("LOWER(message)", null);
        assertFalse(filter.hasFilters());
    }

    @Test
    public void likeBlankValueSkipped() {
        var filter = new JpqlFilter().like("LOWER(message)", "  ");
        assertFalse(filter.hasFilters());
    }

    @Test
    public void numericNullSkippedButZeroKept() {
        var filter = new JpqlFilter()
                .gte("count", null)
                .gte("count", 0);
        assertTrue(filter.hasFilters());
        assertEquals("count >= ?1", filter.toWhereClause());
        assertArrayEquals(new Object[]{0}, filter.params());
    }

    @Test
    public void noFiltersProducesEmptyClause() {
        var filter = new JpqlFilter();
        assertFalse(filter.hasFilters());
        assertEquals("", filter.toWhereClause());
        assertEquals(0, filter.params().length);
    }

    @Test
    public void allNullProducesEmptyClause() {
        var filter = new JpqlFilter()
                .eq("a", null)
                .like("b", null)
                .gte("c", null)
                .lte("d", null);
        assertFalse(filter.hasFilters());
        assertEquals("", filter.toWhereClause());
    }

    @Test
    public void paramListMatchesParams() {
        var filter = new JpqlFilter()
                .eq("x", "one")
                .eq("y", "two");
        var list = filter.paramList();
        assertEquals(2, list.size());
        assertEquals("one", list.get(0));
        assertEquals("two", list.get(1));
    }

    @Test
    public void paramListIsUnmodifiable() {
        var filter = new JpqlFilter().eq("x", "val");
        var list = filter.paramList();
        assertThrows(UnsupportedOperationException.class, () -> list.add("extra"));
    }

    @Test
    public void positionalParametersIncrementCorrectly() {
        var filter = new JpqlFilter()
                .eq("a", "1")
                .like("b", "%2%")
                .gte("c", 3)
                .lte("d", 4);
        assertEquals("a = ?1 AND b LIKE ?2 AND c >= ?3 AND d <= ?4", filter.toWhereClause());
        assertEquals(4, filter.params().length);
    }

    @Test
    public void skippedNullDoesNotConsumeParameterIndex() {
        var filter = new JpqlFilter()
                .eq("a", "first")
                .eq("b", null)
                .eq("c", "second");
        assertEquals("a = ?1 AND c = ?2", filter.toWhereClause());
        assertArrayEquals(new Object[]{"first", "second"}, filter.params());
    }
}
