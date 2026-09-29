import jobs.DefaultConfigJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.ConfigService;
import tools.scrape.DataImpulsePlans;
import tools.scrape.ScrapeProxy;
import tools.scrape.WebScrapeSettings;

import java.util.List;

/** DataImpulse plans (JCLAW-1334): which credentials the proxy uses, what a write may hold, and the boot move. */
class DataImpulsePlansTest extends UnitTest {

    private static final String GATEWAY = "http://gw.dataimpulse.com:823";

    private final ScrapeConfigGuard config = new ScrapeConfigGuard();

    @AfterEach
    void restore() {
        config.restore();
    }

    private void clearPlans() {
        for (var plan : DataImpulsePlans.PLANS.keySet()) {
            config.delete(DataImpulsePlans.loginKey(plan));
            config.delete(DataImpulsePlans.passwordKey(plan));
        }
        config.delete(WebScrapeSettings.PROXY_DATAIMPULSE_PLAN);
        config.delete(WebScrapeSettings.PROXY_DATAIMPULSE_TARGETING);
        config.delete(WebScrapeSettings.PROXY_USERNAME);
        config.delete(WebScrapeSettings.PROXY_PASSWORD);
        config.set(WebScrapeSettings.PROXY_ENABLED, "true");
    }

    private void savePlan(String plan, String login, String password) {
        config.set(DataImpulsePlans.loginKey(plan), login);
        config.set(DataImpulsePlans.passwordKey(plan), password);
    }

    private static String reject(String key, String value) {
        return WebScrapeSettings.rejectionFor(key, value);
    }

    @Test
    void theActivePlansCredentialsCarryTheTargeting() {
        clearPlans();
        config.set(WebScrapeSettings.PROXY_URL, GATEWAY);
        savePlan("residential", "res", "res-pass");
        savePlan("mobile", "abc", "mob-pass");
        config.set(WebScrapeSettings.PROXY_DATAIMPULSE_PLAN, "mobile");
        config.set(WebScrapeSettings.PROXY_DATAIMPULSE_TARGETING, "cr.de");
        config.set(WebScrapeSettings.PROXY_USERNAME, "generic");

        var proxy = ScrapeProxy.current().orElseThrow();
        assertEquals("abc__cr.de", proxy.username());
        assertEquals("mob-pass", proxy.password());

        config.set(WebScrapeSettings.PROXY_DATAIMPULSE_TARGETING, "");
        assertEquals("abc", ScrapeProxy.current().orElseThrow().username(), "no targeting, no separator");

        config.set(WebScrapeSettings.PROXY_URL, "http://74.81.81.81:10000");
        assertEquals("abc", ScrapeProxy.current().orElseThrow().username(), "the IP gateway is the same gateway");
    }

    @Test
    void aManualProxyIgnoresThePlans() {
        clearPlans();
        config.set(WebScrapeSettings.PROXY_URL, "http://proxy.example:3128");
        savePlan("mobile", "abc", "mob-pass");
        config.set(WebScrapeSettings.PROXY_DATAIMPULSE_PLAN, "mobile");
        config.set(WebScrapeSettings.PROXY_USERNAME, "generic");
        config.set(WebScrapeSettings.PROXY_PASSWORD, "generic-pass");

        var proxy = ScrapeProxy.current().orElseThrow();
        assertEquals("generic", proxy.username());
        assertEquals("generic-pass", proxy.password());
    }

    @Test
    void aGatewayWithNoPlanUsesTheGenericKeys() {
        clearPlans();
        config.set(WebScrapeSettings.PROXY_URL, GATEWAY);
        config.set(WebScrapeSettings.PROXY_USERNAME, "generic");
        assertEquals("generic", ScrapeProxy.current().orElseThrow().username());
        assertFalse(DataImpulsePlans.isGateway("socks5://gw.dataimpulse.com:824"), "SOCKS5 carries no credentials");
        assertFalse(DataImpulsePlans.isGateway("http://gw.dataimpulse.com.example:823"));
        assertTrue(DataImpulsePlans.isGateway("http://GW.DataImpulse.com:823/"), "the host's case does not matter");
        assertTrue(DataImpulsePlans.isGateway(" http://74.81.81.81:15000 "));
    }

    @Test
    void aPlanCanBeChosenOnlyOnceItHasALoginAndAPassword() {
        clearPlans();
        config.set(DataImpulsePlans.loginKey("datacenter"), "dc");
        var refused = reject(WebScrapeSettings.PROXY_DATAIMPULSE_PLAN, "datacenter");
        assertNotNull(refused);
        assertTrue(refused.contains("Datacenter"), refused);

        config.set(DataImpulsePlans.passwordKey("datacenter"), "dc-pass");
        assertNull(reject(WebScrapeSettings.PROXY_DATAIMPULSE_PLAN, "datacenter"));
        assertNull(reject(WebScrapeSettings.PROXY_DATAIMPULSE_PLAN, ""), "blank clears the plan");
        assertNotNull(reject(WebScrapeSettings.PROXY_DATAIMPULSE_PLAN, "enterprise"));
        assertNotNull(reject(WebScrapeSettings.PROXY_DATAIMPULSE_PLAN, "Datacenter"));
    }

    @Test
    void onlyThePlanKeysExistUnderTheDataImpulsePrefix() {
        for (var plan : DataImpulsePlans.PLANS.keySet()) {
            assertNull(reject(DataImpulsePlans.loginKey(plan), "abc"), plan);
            assertNull(reject(DataImpulsePlans.passwordKey(plan), "p@ss:w;rd"), plan);
        }
        for (var key : List.of("web_scrape.proxy.dataimpulse.enterprise.login", "web_scrape.proxy.dataimpulse.mobile.username",
                "web_scrape.proxy.dataimpulse.mobile", "web_scrape.proxy.dataimpulse.gateway")) {
            assertNotNull(reject(key, "abc"), key);
        }
        assertNotNull(reject(DataImpulsePlans.passwordKey("mobile"), "pass\u0000word"));
    }

    @Test
    void aPlanLoginFollowsTheCardsRules() {
        var key = DataImpulsePlans.loginKey("residential");
        for (var ok : List.of("abc", "a_b", "_abc", "abc-1", "a.b", "")) {
            assertNull(reject(key, ok), ok);
        }
        for (var bad : List.of("abc__cr.de", "a b", "a:b", "a;b", "a@b", "abc_", "a\u0007b")) {
            assertNotNull(reject(key, bad), bad);
        }
    }

    @Test
    void targetingTakesTheParametersButNoSeparators() {
        var key = WebScrapeSettings.PROXY_DATAIMPULSE_TARGETING;
        for (var ok : List.of("cr.de", "cr.de,au;sessttl.30", "sessttl.45", "")) {
            assertNull(reject(key, ok), ok);
        }
        for (var bad : List.of("cr.de sessttl.30", "cr:de", "cr@de", "cr.de\u0000")) {
            assertNotNull(reject(key, bad), bad);
        }
    }

    @Test
    void legacyCredentialsMoveToTheResidentialPlanOnceAtBoot() throws Exception {
        clearPlans();
        config.set(WebScrapeSettings.PROXY_URL, GATEWAY);
        config.set(WebScrapeSettings.PROXY_USERNAME, "abc__cr.us;sessttl.45");
        config.set(WebScrapeSettings.PROXY_PASSWORD, "p");
        var job = new DefaultConfigJob();
        var migrate = DefaultConfigJob.class.getDeclaredMethod("moveDataImpulseCredentialsToAPlan");
        migrate.setAccessible(true);

        migrate.invoke(job);

        assertEquals("abc", ConfigService.get(DataImpulsePlans.loginKey("residential")));
        assertEquals("p", ConfigService.get(DataImpulsePlans.passwordKey("residential")));
        assertEquals("cr.us;sessttl.45", ConfigService.get(WebScrapeSettings.PROXY_DATAIMPULSE_TARGETING));
        assertEquals("residential", ConfigService.get(WebScrapeSettings.PROXY_DATAIMPULSE_PLAN));
        assertNull(ConfigService.get(WebScrapeSettings.PROXY_USERNAME));
        assertNull(ConfigService.get(WebScrapeSettings.PROXY_PASSWORD));
        assertEquals("abc__cr.us;sessttl.45", ScrapeProxy.current().orElseThrow().username(), "the proxy sends what it sent before");

        // A second boot changes nothing, even with a generic username written since.
        config.set(WebScrapeSettings.PROXY_USERNAME, "later");
        migrate.invoke(job);
        assertEquals("abc", ConfigService.get(DataImpulsePlans.loginKey("residential")));
        assertEquals("later", ConfigService.get(WebScrapeSettings.PROXY_USERNAME));
    }

    @Test
    void aManualProxysCredentialsStayWhereTheyAre() throws Exception {
        clearPlans();
        config.set(WebScrapeSettings.PROXY_URL, "http://proxy.example:3128");
        config.set(WebScrapeSettings.PROXY_USERNAME, "abc__cr.us");
        var migrate = DefaultConfigJob.class.getDeclaredMethod("moveDataImpulseCredentialsToAPlan");
        migrate.setAccessible(true);

        migrate.invoke(new DefaultConfigJob());

        assertEquals("abc__cr.us", ConfigService.get(WebScrapeSettings.PROXY_USERNAME));
        assertNull(ConfigService.get(WebScrapeSettings.PROXY_DATAIMPULSE_PLAN));
        assertNull(ConfigService.get(DataImpulsePlans.loginKey("residential")));
    }
}
