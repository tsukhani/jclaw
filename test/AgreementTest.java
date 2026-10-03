import memory.ontology.OntologySchema;
import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;
import services.graphspike.Agreement;
import services.graphspike.GraphCases;
import services.graphspike.GraphCases.Case;
import services.graphspike.GraphCases.Entity;
import services.graphspike.GraphCases.Relation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** JCLAW-1356: the blind subset is fixed by hash, and agreement is measured over it alone. */
class AgreementTest extends UnitTest {

    private static List<Case> committed() throws Exception {
        return GraphCases.load(Play.applicationPath.toPath().resolve(GraphCases.DEFAULT_PATH), OntologySchema.seed());
    }

    private static List<Case> selected(List<Case> cases) {
        var ids = new HashSet<>(Agreement.blindSelection(cases));
        return cases.stream().filter(c -> ids.contains(c.id())).toList();
    }

    @Test
    void theBlindSelectionIsFifteenPercentRoundedUpAndOrderIndependent() throws Exception {
        var cases = committed();
        var selection = Agreement.blindSelection(cases);
        assertEquals((int) Math.ceil(cases.size() * 0.15), selection.size());
        var reversed = new ArrayList<>(cases);
        Collections.reverse(reversed);
        assertEquals(selection, Agreement.blindSelection(reversed));
    }

    @Test
    void theBlindSheetCarriesOnlyIdsAndText() throws Exception {
        var sheet = Agreement.blindSheet(committed());
        var rows = sheet.getAsJsonArray("cases");
        assertEquals(Agreement.blindSelection(committed()).size(), rows.size());
        for (var row : rows) assertEquals(Set.of("id", "text"), row.getAsJsonObject().keySet());
    }

    @Test
    void identicalSecondLabelsAgreeCompletely() throws Exception {
        var cases = committed();
        var r = Agreement.compare(cases, selected(cases));
        assertTrue(r.complete());
        assertEquals(r.selected(), r.covered());
        assertEquals(1.0, r.entityF1());
        assertEquals(1.0, r.typeKappa());
        assertEquals(1.0, r.relationF1());
    }

    @Test
    void noSecondLabelsOrAPartialSetIsIncomplete() throws Exception {
        var cases = committed();
        var none = Agreement.compare(cases, List.of());
        assertFalse(none.complete());
        assertEquals(0, none.covered());
        assertNull(none.entityF1());
        var partial = Agreement.compare(cases, selected(cases).subList(1, selected(cases).size()));
        assertFalse(partial.complete());
        assertEquals(partial.selected() - 1, partial.covered());
    }

    @Test
    void aSecondLabellerMatchesByAnySpanAndDisagreementsLowerTheScores() {
        var first = new Case("x", List.of("plain"), "The user works at Harborlight Analytics, a lab, with Wren.",
                List.of(Entity.of("operator", "The user", "Person"),
                        Entity.of("harborlight", "Harborlight Analytics", "Organization", "a lab"),
                        Entity.of("wren", "Wren", "Person")),
                List.of(Relation.of("operator", "works_at", "harborlight")), List.of());
        var second = new Case("x", List.of(), first.text(),
                List.of(Entity.of("me", "The user", "Person"), Entity.of("lab", "a lab", "Organization"),
                        Entity.of("w", "Wren", "Topic")),
                List.of(Relation.of("me", "works_at", "lab"), Relation.of("w", "works_at", "lab")), List.of());
        var a = Agreement.compare(List.of(first), List.of(second));
        assertTrue(a.complete());
        assertEquals(1.0, a.entityF1());
        assertTrue(a.typeKappa() < 1.0);
        assertEquals(2.0 / 3, a.relationF1(), 1e-9);
    }
}
