import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.FunctionalTest;


/**
 * JCLAW-911's printer endpoints. Everything here runs without a printer on the network:
 * discovery is a live mDNS browse that legitimately finds nothing on a test host, and
 * reachability probes legitimately fail — those are real branches, not degraded ones, and
 * the contract each has to honour is that an absent printer is reported as absent rather
 * than as an error.
 */
class ApiPrintersControllerTest extends FunctionalTest {

    @BeforeEach
    void setup() {
        // deleteDatabase already empties CONFIG, which is where PrinterDefaults lives, so an
        // explicit clear() here would be a third mutation of a table the concurrently-running
        // lane also touches — that raced into "Concurrent update in table CONFIG".
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
    }

    private void login() {
        var resp = POST("/api/auth/login", "application/json",
                "{\"username\": \"admin\", \"password\": \"changeme\"}");
        assertIsOk(resp);
    }

    private static play.mvc.Http.Response putJson(String url, String json) {
        return PUT(url, "application/json", json);
    }

    // --- auth ---

    @Test
    void everyPrinterEndpointRequiresAuth() {
        assertEquals(401, GET("/api/printers").status.intValue());
        assertEquals(401, GET("/api/printers/default").status.intValue());
        assertEquals(401, GET("/api/printers/default/status").status.intValue());
        assertEquals(401, putJson("/api/printers/default", "{\"host\":\"1.2.3.4\"}").status.intValue());
    }

    // --- default: round trip ---

    @Test
    void withNoDefaultSavedTheStatusReportsUnconfiguredRatherThanUnreachable() {
        // The distinction matters to the UI: "you haven't picked a printer" is not a fault,
        // and rendering it as one sends the operator hunting for a network problem.
        login();
        var resp = GET("/api/printers/default/status");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"configured\":false"), body);
        assertTrue(body.contains("\"reachable\":false"), body);
    }

    @Test
    void savingADefaultThenReadingItBackReturnsWhatWasSaved() {
        login();
        var save = putJson("/api/printers/default",
                "{\"name\":\"Front Desk\",\"host\":\"192.0.2.10\",\"port\":631,\"protocol\":\"IPP\"}");
        assertIsOk(save);

        var got = getContent(GET("/api/printers/default"));
        assertTrue(got.contains("192.0.2.10"), got);
        assertTrue(got.contains("Front Desk"), got);
        assertTrue(got.contains("631"), got);
    }

    @Test
    void anEmptyHostClearsTheDefaultEntirely() {
        // The only route back to "no default" — a half-cleared default pointing at an
        // unplugged printer is worse than none.
        login();
        assertIsOk(putJson("/api/printers/default", "{\"host\":\"192.0.2.10\",\"port\":631}"));
        assertTrue(getContent(GET("/api/printers/default")).contains("192.0.2.10"));

        var cleared = putJson("/api/printers/default", "{\"host\":\"\"}");
        assertIsOk(cleared);
        assertTrue(getContent(cleared).contains("cleared"), getContent(cleared));

        var after = getContent(GET("/api/printers/default/status"));
        assertTrue(after.contains("\"configured\":false"), after);
    }

    @Test
    void aSavedDefaultIsReportedAsConfiguredEvenWhenNothingAnswers() {
        login();
        assertIsOk(putJson("/api/printers/default",
                "{\"host\":\"192.0.2.10\",\"port\":9100,\"protocol\":\"RAW\"}"));

        var body = getContent(GET("/api/printers/default/status"));
        assertTrue(body.contains("\"configured\":true"), body);
        assertTrue(body.contains("192.0.2.10"), body);
        // 192.0.2.0/24 is TEST-NET-1 (RFC 5737) and never routes, so this is
        // deterministically unreachable rather than dependent on the test host's network.
        assertTrue(body.contains("\"reachable\":false"), body);
    }

    @Test
    void anOmittedPortFallsBackToTheProtocolDefault() {
        login();
        assertIsOk(putJson("/api/printers/default", "{\"host\":\"192.0.2.11\",\"protocol\":\"IPP\"}"));

        var body = getContent(GET("/api/printers/default/status"));
        assertTrue(body.contains("\"port\":631"), "IPP's default port should fill in: " + body);
    }

    // --- validation ---

    @Test
    void anInvalidSidesValueIsRejectedRatherThanSaved() {
        login();
        var resp = putJson("/api/printers/default",
                "{\"host\":\"192.0.2.10\",\"options\":{\"sides\":\"three-sided\"}}");
        assertEquals(400, resp.status.intValue());
        assertTrue(getContent(resp).contains("sides"), getContent(resp));
        assertTrue(getContent(GET("/api/printers/default/status")).contains("\"configured\":false"),
                "a rejected save must not have persisted anything");
    }

    @Test
    void anInvalidColorModeIsRejected() {
        login();
        var resp = putJson("/api/printers/default",
                "{\"host\":\"192.0.2.10\",\"options\":{\"print-color-mode\":\"ultraviolet\"}}");
        assertEquals(400, resp.status.intValue());
        assertTrue(getContent(resp).contains("color"), getContent(resp));
    }

    @Test
    void vendorOptionsJClawDoesNotInterpretAreSavedUntouched() {
        // The printer announced them and is the authority on whether they are valid;
        // second-guessing here would reject values a device legitimately offers.
        login();
        var resp = putJson("/api/printers/default",
                "{\"host\":\"192.0.2.10\",\"options\":{\"output-bin\":\"stacker-2\",\"sides\":\"one-sided\"}}");
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("stacker-2"), getContent(resp));
    }

    @Test
    void blankAndNullOptionValuesAreDroppedRatherThanStoredAsEmpty() {
        login();
        var resp = putJson("/api/printers/default",
                "{\"host\":\"192.0.2.10\",\"options\":{\"media\":\"   \",\"output-bin\":null,\"sides\":\"two-sided-long-edge\"}}");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("two-sided-long-edge"), body);
        assertFalse(body.contains("output-bin"), "a null option is not a setting: " + body);
        // The blank half was unasserted, so widening the guard to `value != null` survived.
        // media is not validated, so a stored "" would have gone unnoticed.
        assertFalse(body.contains("media"), "a whitespace-only option is not a setting: " + body);
    }

    @Test
    void anOptionsFieldThatIsNotAnObjectSavesNoOptions() {
        login();
        var resp = putJson("/api/printers/default",
                "{\"host\":\"192.0.2.10\",\"options\":\"sides=two-sided-long-edge\"}");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"options\":{}"), body);
    }

    @Test
    void aMalformedBodyIsRejected() {
        login();
        assertEquals(400, putJson("/api/printers/default", "not json").status.intValue());
    }

    // --- discovery and options ---

    @Test
    void discoveryReturnsAJsonArrayEvenWhenNoPrinterAnswers() {
        login();
        var resp = GET("/api/printers");
        assertIsOk(resp);
        assertTrue(getContent(resp).trim().startsWith("["), getContent(resp));
    }

    @Test
    void jobOptionsWithoutAHostSayNothingCameFromAPrinter() {
        // The UI renders this as "pick a printer first" rather than as an empty form.
        login();
        var body = getContent(GET("/api/printers/options"));
        assertTrue(body.contains("\"fromPrinter\":false"), body);
        assertTrue(body.contains("IPP"), "the transports JClaw speaks are not printer-specific: " + body);
    }

    @Test
    void jobOptionsFromAnUnreachablePrinterAreEmptyRatherThanInvented() {
        login();
        var body = getContent(GET("/api/printers/options?host=192.0.2.10&port=631&protocol=IPP"));
        assertTrue(body.contains("\"fromPrinter\":false"), body);
        assertTrue(body.contains("\"options\":[]"), "inventing options would offer settings the device may reject: " + body);
    }
}
