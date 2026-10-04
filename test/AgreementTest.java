import memory.ontology.OntologySchema;
import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;
import services.grapheval.Agreement;
import services.grapheval.GraphCases;
import services.grapheval.GraphCases.Case;
import services.grapheval.GraphCases.DateLabel;
import services.grapheval.GraphCases.Entity;
import services.grapheval.GraphCases.Relation;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** JCLAW-1356: the blind subset is fixed by hash, and agreement is measured over it alone. */
class AgreementTest extends UnitTest {

    private static final Set<String> SYMMETRIC = OntologySchema.seed().symmetricSet();
    private static final LocalDate ROOT_ANCHOR = LocalDate.of(2026, 2, 15);

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
    void theBlindSheetCarriesOnlyIdsTextCapturedAtAndTheOwner() throws Exception {
        var sheet = Agreement.blindSheet(committed(), "Name: Avery Lin", ROOT_ANCHOR);
        assertEquals(Set.of("userMd", "capturedAt", "cases"), sheet.keySet());
        assertEquals("Name: Avery Lin", sheet.get("userMd").getAsString());
        assertEquals("2026-02-15", sheet.get("capturedAt").getAsString());
        var rows = sheet.getAsJsonArray("cases");
        assertEquals(Agreement.blindSelection(committed()).size(), rows.size());
        for (var row : rows) {
            assertEquals(Set.of("id", "text", "capturedAt"), row.getAsJsonObject().keySet());
            assertEquals("2026-02-15", row.getAsJsonObject().get("capturedAt").getAsString(), "inherited from the root");
        }
        assertEquals(Set.of("capturedAt", "cases"), Agreement.blindSheet(committed(), null, ROOT_ANCHOR).keySet(),
                "no owner declared, none written");
    }

    @Test
    void theBlindSheetHoldsOnlyTheSelectedIdsTextCapturedAtAndOwner() {
        var cases = new ArrayList<Case>();
        for (int i = 0; i < 10; i++) {
            cases.add(new Case("s" + i, List.of("plain"), "Text " + i + ".", List.of(), List.of(), List.of(),
                    LocalDate.of(2026, 3, 1 + i), List.of()));
        }
        var sheet = Agreement.blindSheet(cases, "Name: Avery Lin", ROOT_ANCHOR);
        var selection = Agreement.blindSelection(cases);
        var rows = sheet.getAsJsonArray("cases");
        assertEquals(selection.size(), rows.size());
        for (var row : rows) {
            var o = row.getAsJsonObject();
            var c = cases.stream().filter(x -> x.id().equals(o.get("id").getAsString())).findFirst().orElseThrow();
            assertTrue(selection.contains(c.id()));
            assertEquals(c.text(), o.get("text").getAsString());
            assertEquals(c.capturedAt().toString(), o.get("capturedAt").getAsString(), "the case's own anchor");
        }
    }

    @Test
    void identicalSecondLabelsAgreeCompletely() throws Exception {
        var cases = committed();
        var r = Agreement.compare(cases, selected(cases), SYMMETRIC);
        assertTrue(r.complete());
        assertEquals(r.selected(), r.covered());
        assertEquals(1.0, r.entityF1());
        assertEquals(1.0, r.typeKappa());
        assertEquals(1.0, r.relationF1());
    }

    @Test
    void noSecondLabelsOrAPartialSetIsIncomplete() throws Exception {
        var cases = committed();
        var none = Agreement.compare(cases, List.of(), SYMMETRIC);
        assertFalse(none.complete());
        assertEquals(0, none.covered());
        assertNull(none.entityF1());
        var partial = Agreement.compare(cases, selected(cases).subList(1, selected(cases).size()), SYMMETRIC);
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
        var a = Agreement.compare(List.of(first), List.of(second), SYMMETRIC);
        assertTrue(a.complete());
        assertEquals(1.0, a.entityF1());
        assertTrue(a.typeKappa() < 1.0);
        assertEquals(2.0 / 3, a.relationF1(), 1e-9);
    }

    @Test
    void statusKappaAndTheQualifierAgreementsAreOverMatchedItems() {
        var text = "The user worked at Harborlight Analytics until 2019, loves Rust and went to the Fair in May 2024.";
        var first = new Case("x", List.of("plain"), text,
                List.of(Entity.of("operator", "The user", "Person"),
                        Entity.of("harborlight", "Harborlight Analytics", "Organization"),
                        Entity.of("rust", "Rust", "Topic"),
                        new Entity("fair", "the Fair", "Event", List.of(), false, false, "2024-05")),
                List.of(new Relation("operator", "works_at", "harborlight", GraphCases.ENDED, "/2019", null, false),
                        new Relation("operator", "holds_view_on", "rust", GraphCases.HOLDS, null, "favorable", false)),
                List.of(), ROOT_ANCHOR, List.of(new DateLabel("2019", "2019"), new DateLabel("May 2024", "2024-05")));
        var second = new Case("x", List.of(), text,
                List.of(Entity.of("me", "The user", "Person"),
                        Entity.of("h", "Harborlight Analytics", "Organization"),
                        Entity.of("r", "Rust", "Topic"),
                        new Entity("f", "the Fair", "Event", List.of(), false, false, "2024-05-01")),
                List.of(new Relation("me", "works_at", "h", GraphCases.HOLDS, "/2019", null, false),
                        new Relation("me", "holds_view_on", "r", GraphCases.HOLDS, null, "unfavorable", false)),
                List.of(), ROOT_ANCHOR, List.of(new DateLabel("2019", "2019"), new DateLabel("May 2024", null)));
        var a = Agreement.compare(List.of(first), List.of(second), SYMMETRIC);
        assertEquals(1.0, a.relationF1());
        assertTrue(a.statusKappa() < 1.0, "ended against holds");
        assertEquals(1.0, a.validAgreement(), "only works_at carries a valid on either side");
        assertEquals(0.0, a.occursAgreement(), "a month is not a day");
        assertEquals(0.0, a.valenceAgreement());
        assertEquals(0.5, a.datesAgreement(), "an excluded span against a value disagrees");

        var same = Agreement.compare(List.of(first), List.of(first), SYMMETRIC);
        assertEquals(1.0, same.statusKappa());
        assertEquals(1.0, same.validAgreement());
        assertEquals(1.0, same.occursAgreement());
        assertEquals(1.0, same.valenceAgreement());
        assertEquals(1.0, same.datesAgreement());
        assertNull(same.reason());
    }

    @Test
    void aPositiveAndADenialOfOneTypeOnOnePairAreTwoItems() {
        var text = "The user used Kestrel CI until 2019, does not use it now, and works at Harborlight Analytics.";
        var entities = List.of(Entity.of("operator", "The user", "Person"),
                Entity.of("kestrel", "Kestrel CI", "System"),
                Entity.of("harborlight", "Harborlight Analytics", "Organization"));
        var ended = new Relation("operator", "uses", "kestrel", GraphCases.ENDED, null, null, false);
        var denied = new Relation("operator", "uses", "kestrel", GraphCases.DENIED, null, null, false);
        var both = new Case("x", List.of("plain"), text, entities, List.of(ended, denied), List.of());
        var same = Agreement.compare(List.of(both), List.of(both), SYMMETRIC);
        assertEquals(1.0, same.relationF1());
        assertEquals(1.0, same.statusKappa());

        var first = new Case("x", List.of("plain"), text, entities,
                List.of(ended, denied, Relation.of("operator", "works_at", "harborlight")), List.of());
        var missed = Agreement.compare(List.of(first), List.of(both), SYMMETRIC);
        assertEquals(2.0 * 2 / (3 + 2), missed.relationF1(), 1e-9, "both uses triples count on each side");
    }

    @Test
    void secondLabelsThatPredateV3GiveAnIncompleteResultWithTheReason() {
        var r = Agreement.Result.predatesV3("relation 0: missing status");
        assertFalse(r.complete());
        assertEquals("second labels predate v3: relation 0: missing status", r.reason());
        assertNull(r.statusKappa());
    }
}
