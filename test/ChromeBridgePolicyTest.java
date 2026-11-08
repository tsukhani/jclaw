import models.Agent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.AgentService;
import services.ChromeBridgePolicy;
import services.ConfigService;
import utils.SsrfGuard;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

class ChromeBridgePolicyTest extends UnitTest {

    private static final String NEVER = "loopback, private-network and malformed addresses are never allowed";
    private static final String MAIN_ONLY =
            "Allowing every site, clicking without approval and running page script can only be set for the main agent.";

    private static final Map<String, String> HOSTS = Map.of(
            "example.com", "93.184.216.34",
            "app.example.com", "93.184.216.35",
            "evil.com", "1.1.1.1",
            "intranet.example.com", "10.0.0.5");

    private final List<Agent> created = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void clear() {
        for (var name : List.of("main", "helper", "second")) {
            for (var setting : List.of(ChromeBridgePolicy.ALLOWED_SITES, ChromeBridgePolicy.ALLOW_ALL_SITES,
                    ChromeBridgePolicy.CLICK_WITHOUT_APPROVAL, ChromeBridgePolicy.ALLOW_SCRIPT)) {
                ConfigService.delete(ChromeBridgePolicy.key(name, setting));
            }
        }
        created.forEach(AgentService::delete);
        created.clear();
    }

    private static Agent unsaved(String name) {
        var a = new Agent();
        a.name = name;
        return a;
    }

    private Agent saved() {
        var a = AgentService.create("bridge-" + UUID.randomUUID().toString().substring(0, 8), "openrouter", "gpt-4.1");
        created.add(a);
        return a;
    }

    private static String refusal(Agent agent, String url) {
        return SsrfGuard.callWithHostsForTest(HOSTS, () -> ChromeBridgePolicy.visitRefusal(agent, url));
    }

    private static void set(Agent agent, String setting, String value) {
        ConfigService.set(ChromeBridgePolicy.key(agent.name, setting), value);
    }

    @Test
    void anEmptyAllowlistRefusesEverySite() {
        assertEquals("site not on the allowlist: example.com", refusal(unsaved("helper"), "https://example.com/"));
    }

    @Test
    void aListedHostIsAllowedButItsSubdomainsAreNot() {
        var helper = unsaved("helper");
        set(helper, ChromeBridgePolicy.ALLOWED_SITES, "example.com");

        assertNull(refusal(helper, "https://example.com/page"));
        assertEquals("site not on the allowlist: app.example.com", refusal(helper, "https://app.example.com/"));
    }

    @Test
    void aWildcardAllowsSubdomainsButNotTheApex() {
        var helper = unsaved("helper");
        set(helper, ChromeBridgePolicy.ALLOWED_SITES, "*.example.com");

        assertNull(refusal(helper, "https://app.example.com/"));
        assertEquals("site not on the allowlist: example.com", refusal(helper, "https://example.com/"));
    }

    @Test
    void hostMatchingIgnoresCase() {
        var helper = unsaved("helper");
        set(helper, ChromeBridgePolicy.ALLOWED_SITES, "Example.COM");

        assertNull(refusal(helper, "https://EXAMPLE.com/x"));
    }

    @Test
    void oneAgentsAllowlistDoesNotOpenSitesForAnother() {
        var helper = unsaved("helper");
        set(helper, ChromeBridgePolicy.ALLOWED_SITES, "example.com");

        assertNull(refusal(helper, "https://example.com/"));
        assertEquals("site not on the allowlist: example.com", refusal(unsaved("second"), "https://example.com/"));
    }

    @Test
    void allowAllOpensAnySiteForTheMainAgent() {
        var main = unsaved("main");
        set(main, ChromeBridgePolicy.ALLOW_ALL_SITES, "true");

        assertNull(refusal(main, "https://evil.com/"));
    }

    @Test
    void allowAllIsIgnoredForAnyOtherAgentEvenIfTheKeyIsPlantedDirectly() {
        var helper = unsaved("helper");
        set(helper, ChromeBridgePolicy.ALLOW_ALL_SITES, "true");

        assertEquals("site not on the allowlist: evil.com", refusal(helper, "https://evil.com/"));
    }

    @Test
    void allowAllStillRefusesLoopbackAndPrivateAddresses() {
        var main = unsaved("main");
        set(main, ChromeBridgePolicy.ALLOW_ALL_SITES, "true");

        assertEquals(NEVER, refusal(main, "http://127.0.0.1:9000/chat"));
        assertEquals(NEVER, refusal(main, "http://localhost:9000/chat"));
        assertEquals(NEVER, refusal(main, "http://192.168.1.1/"));
        assertEquals(NEVER, refusal(main, "http://10.0.0.5/"));
    }

    @Test
    void anAllowlistedHostThatResolvesToAPrivateAddressIsRefused() {
        var helper = unsaved("helper");
        set(helper, ChromeBridgePolicy.ALLOWED_SITES, "intranet.example.com");

        assertEquals(NEVER, refusal(helper, "https://intranet.example.com/"));
    }

    @Test
    void onlyHttpAndHttpsOpen() {
        var main = unsaved("main");
        set(main, ChromeBridgePolicy.ALLOW_ALL_SITES, "true");
        var expected = "only http(s) URLs can be opened";

        assertEquals(expected, refusal(main, "file:///etc/hosts"));
        assertEquals(expected, refusal(main, "javascript:alert(1)"));
        assertEquals(expected, refusal(main, "chrome://settings"));
        assertEquals(expected, refusal(main, "ftp://example.com/"));
    }

    @Test
    void aUserinfoUrlIsJudgedByTheHostTheBrowserConnectsTo() {
        var helper = unsaved("helper");
        set(helper, ChromeBridgePolicy.ALLOWED_SITES, "example.com");

        assertEquals("site not on the allowlist: evil.com", refusal(helper, "https://example.com@evil.com/"));
        assertEquals(NEVER, refusal(helper, "https://evil.com@example.com/"));
    }

    @Test
    void aBackslashAuthorityTrickIsRefusedAsNotAValidUrl() {
        var helper = unsaved("helper");
        set(helper, ChromeBridgePolicy.ALLOWED_SITES, "example.com");

        assertEquals("not a valid URL", refusal(helper, "https://evil.com\\@example.com/"));
    }

    @Test
    void clicksNeedApprovalByDefaultForEveryAgent() {
        assertTrue(ChromeBridgePolicy.clickNeedsApproval(unsaved("main")));
        assertTrue(ChromeBridgePolicy.clickNeedsApproval(unsaved("helper")));
    }

    @Test
    void pageScriptIsOffByDefaultAndOnlyTheMainAgentCanHaveItOn() {
        assertFalse(ChromeBridgePolicy.scriptAllowed(unsaved("main")));
        set(unsaved("main"), ChromeBridgePolicy.ALLOW_SCRIPT, "true");
        set(unsaved("helper"), ChromeBridgePolicy.ALLOW_SCRIPT, "true");

        assertTrue(ChromeBridgePolicy.scriptAllowed(unsaved("main")));
        assertFalse(ChromeBridgePolicy.scriptAllowed(unsaved("helper")), "a planted key does nothing for another agent");
    }

    @Test
    void theClickOverrideReachesTheMainAgentOnly() {
        set(unsaved("main"), ChromeBridgePolicy.CLICK_WITHOUT_APPROVAL, "true");
        set(unsaved("helper"), ChromeBridgePolicy.CLICK_WITHOUT_APPROVAL, "true");

        assertFalse(ChromeBridgePolicy.clickNeedsApproval(unsaved("main")));
        assertTrue(ChromeBridgePolicy.clickNeedsApproval(unsaved("helper")));
    }

    @Test
    void theAllowlistAcceptsHostNamesAndWildcardsOnly() {
        var key = ChromeBridgePolicy.key(saved().name, ChromeBridgePolicy.ALLOWED_SITES);

        assertNull(ChromeBridgePolicy.rejectionFor(key, "example.com *.example.org, other.net"));
        assertNull(ChromeBridgePolicy.rejectionFor(key, ""));
        for (var bad : List.of("https://example.com", "example.com:8080", "example.com/path",
                "localhost", "*example.com", "user@example.com")) {
            assertNotNull(ChromeBridgePolicy.rejectionFor(key, "ok.example.com " + bad), "should refuse: " + bad);
        }
    }

    @Test
    void theAllowlistHoldsAtMostTwoHundredSites() {
        var key = ChromeBridgePolicy.key(saved().name, ChromeBridgePolicy.ALLOWED_SITES);
        var atLimit = new StringBuilder();
        for (int i = 0; i < 200; i++) atLimit.append("s").append(i).append(".example.com ");

        assertNull(ChromeBridgePolicy.rejectionFor(key, atLimit.toString()));
        assertEquals(key + " holds at most 200 sites.",
                ChromeBridgePolicy.rejectionFor(key, atLimit + "extra.example.com"));
    }

    @Test
    void anAllowlistForAnUnknownAgentIsRefused() {
        var key = ChromeBridgePolicy.key("no-such-agent-" + UUID.randomUUID(), ChromeBridgePolicy.ALLOWED_SITES);

        assertTrue(ChromeBridgePolicy.rejectionFor(key, "example.com").startsWith("No agent named 'no-such-agent-"));
    }

    @Test
    void theTwoPrivilegeKeysTakeTrueOrFalseForTheMainAgent() {
        for (var setting : List.of(ChromeBridgePolicy.ALLOW_ALL_SITES, ChromeBridgePolicy.CLICK_WITHOUT_APPROVAL,
                ChromeBridgePolicy.ALLOW_SCRIPT)) {
            var main = unsaved("main");
            var key = ChromeBridgePolicy.key(main.name, setting);
            assertNull(ChromeBridgePolicy.rejectionFor(key, "true", main));
            assertNull(ChromeBridgePolicy.rejectionFor(key, "false", main));
            assertEquals(key + " must be true or false.", ChromeBridgePolicy.rejectionFor(key, "yes", main));
        }
    }

    @Test
    void theTwoPrivilegeKeysAreRefusedForEveryOtherAgent() {
        var other = saved();
        for (var setting : List.of(ChromeBridgePolicy.ALLOW_ALL_SITES, ChromeBridgePolicy.CLICK_WITHOUT_APPROVAL,
                ChromeBridgePolicy.ALLOW_SCRIPT)) {
            assertEquals(MAIN_ONLY, ChromeBridgePolicy.rejectionFor(ChromeBridgePolicy.key(other.name, setting), "true"));
        }
    }

    @Test
    void aBadWriteIsRefusedAtTheConfigSeamAndNothingIsStored() {
        var key = ChromeBridgePolicy.key(saved().name, ChromeBridgePolicy.ALLOWED_SITES);

        assertNotNull(ConfigService.setWithSideEffects(key, "https://example.com"));
        assertNull(ConfigService.get(key));
        assertNull(ConfigService.setWithSideEffects(key, "example.com"));
        assertEquals("example.com", ConfigService.get(key));
    }

    @Test
    void deletingAnAgentRemovesItsAllowlist() {
        var agent = saved();
        var key = ChromeBridgePolicy.key(agent.name, ChromeBridgePolicy.ALLOWED_SITES);
        assertNull(ConfigService.setWithSideEffects(key, "example.com"));

        AgentService.delete(agent);
        created.remove(agent);

        assertNull(ConfigService.get(key));
    }
}
