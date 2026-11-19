import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.ConfigService;
import services.PrivilegedConfig;
import services.PrivilegedConfig.Tightening;
import services.Tx;

/**
 * JCLAW-1022: application.conf caps the config keys that carry privilege, and a stored row may
 * only tighten that cap.
 *
 * <p>The reconciliation cases pass the ceiling explicitly rather than setting it in
 * {@code Play.configuration}: that is a process-global and play1 runs test classes concurrently,
 * so a test that mutated it would reconfigure its siblings mid-run.
 *
 * <p>The one case that does exercise the live wiring uses {@code provider.__conftest__.baseUrl},
 * pinned under {@code %test.} in application.conf. It is deliberately a key nothing reads, so
 * proving the choke point works cannot disturb a real control.
 */
class PrivilegedConfigTest extends UnitTest {

    private static final String ALLOWLIST = "shell.allowlist";
    private static final String BYPASS = "agent.main.shell.bypassAllowlist";
    private static final String POLICY = "tool.approval.offChannelPolicy";
    private static final String BASE_URL = "provider.openai.baseUrl";
    private static final String PROBE_URL = "provider.__conftest__.baseUrl";

    // --- which keys are privileged at all ---

    @Test
    void everyPrivilegedFamilyIsClassified() {
        assertEquals(Tightening.SET_INTERSECTION, PrivilegedConfig.ruleFor(ALLOWLIST));
        assertEquals(Tightening.BOOLEAN_AND, PrivilegedConfig.ruleFor(BYPASS));
        assertEquals(Tightening.BOOLEAN_AND, PrivilegedConfig.ruleFor("agent.main.shell.allowGlobalPaths"));
        assertEquals(Tightening.POLICY_FLOOR, PrivilegedConfig.ruleFor(POLICY));
        assertEquals(Tightening.PINNED, PrivilegedConfig.ruleFor(BASE_URL));
        // The bypass rule is per-agent, so it must match whatever the main agent is named.
        assertEquals(Tightening.BOOLEAN_AND, PrivilegedConfig.ruleFor("agent.anything.shell.bypassAllowlist"));
    }

    @Test
    void ordinaryKeysAreUntouched() {
        assertNull(PrivilegedConfig.ruleFor("chat.compression.enabled"));
        assertEquals("whatever", PrivilegedConfig.reconcile("chat.compression.enabled", "whatever", "ceiling"));
        assertNull(PrivilegedConfig.rejectionFor("chat.compression.enabled", "whatever", "ceiling"));
    }

    // --- the ceiling is opt-in ---

    @Test
    void noCeilingLeavesTheStoredRowExactlyAsItWas() {
        // The upgrade path: an operator who widened the allowlist through Settings keeps it.
        assertEquals("ls,cat,curl", PrivilegedConfig.reconcile(ALLOWLIST, "ls,cat,curl", null));
        assertEquals("ls,cat,curl", PrivilegedConfig.reconcile(ALLOWLIST, "ls,cat,curl", "   "));
        assertEquals("true", PrivilegedConfig.reconcile(BYPASS, "true", null));
        assertNull(PrivilegedConfig.rejectionFor(ALLOWLIST, "ls,curl", null));
    }

    @Test
    void aCeilingWithNoRowBecomesTheValue() {
        // Otherwise the caller's code default would outrank the ceiling the operator declared.
        assertEquals("ls,cat", PrivilegedConfig.reconcile(ALLOWLIST, null, "ls,cat"));
        assertEquals("deny", PrivilegedConfig.reconcile(POLICY, null, "deny"));
    }

    // --- per-family tightening ---

    @Test
    void allowlistIntersectsAndCannotAddABinary() {
        assertEquals("ls,cat", PrivilegedConfig.reconcile(ALLOWLIST, "ls,cat", "ls,cat,grep"));
        assertEquals("ls", PrivilegedConfig.reconcile(ALLOWLIST, "ls,curl", "ls,cat"));
        assertEquals("", PrivilegedConfig.reconcile(ALLOWLIST, "curl,nc", "ls,cat"));
        assertEquals("ls,cat", PrivilegedConfig.reconcile(ALLOWLIST, " ls , cat ", "ls,cat,grep"));
    }

    @Test
    void bypassGrantCanBeWithdrawnButNotTakenBack() {
        assertEquals("false", PrivilegedConfig.reconcile(BYPASS, "true", "false"));
        assertEquals("false", PrivilegedConfig.reconcile(BYPASS, "false", "true"));
        assertEquals("true", PrivilegedConfig.reconcile(BYPASS, "true", "true"));
    }

    @Test
    void approvalPolicyMayOnlyGetStricter() {
        assertEquals("deny", PrivilegedConfig.reconcile(POLICY, "deny", "ask"));
        assertEquals("ask", PrivilegedConfig.reconcile(POLICY, "allow", "ask"));
        assertEquals("ask", PrivilegedConfig.reconcile(POLICY, "ask", "allow"));
        // Unrecognized reads as "allow" at the gate, so it must rank that way here too.
        assertEquals("ask", PrivilegedConfig.reconcile(POLICY, "nonsense", "ask"));
    }

    @Test
    void aPinnedBaseUrlIgnoresTheStoredRowEntirely() {
        assertEquals("https://api.openai.com/v1",
                PrivilegedConfig.reconcile(BASE_URL, "https://attacker.example", "https://api.openai.com/v1"));
    }

    // --- the operator gets told, rather than silently ignored ---

    @Test
    void aLooseningSaveIsRefusedWithTheCeilingNamed() {
        var rejection = PrivilegedConfig.rejectionFor(ALLOWLIST, "ls,curl", "ls,cat");
        assertNotNull(rejection, "a row adding curl above the ceiling must be refused");
        assertTrue(rejection.contains("application.conf"), rejection);
        assertTrue(rejection.contains("ls,cat"), "the rejection must name the ceiling: " + rejection);

        assertNotNull(PrivilegedConfig.rejectionFor(BYPASS, "true", "false"));
        assertNotNull(PrivilegedConfig.rejectionFor(POLICY, "allow", "deny"));
        assertNotNull(PrivilegedConfig.rejectionFor(BASE_URL, "https://attacker.example", "https://api.openai.com/v1"));
    }

    @Test
    void aTighteningSaveIsAccepted() {
        assertNull(PrivilegedConfig.rejectionFor(ALLOWLIST, "ls", "ls,cat"));
        assertNull(PrivilegedConfig.rejectionFor(ALLOWLIST, "cat, ls", "ls,cat"), "order and spacing are not a change");
        assertNull(PrivilegedConfig.rejectionFor(BYPASS, "false", "true"));
        assertNull(PrivilegedConfig.rejectionFor(POLICY, "deny", "ask"));
        assertNull(PrivilegedConfig.rejectionFor(BASE_URL, "https://api.openai.com/v1", "https://api.openai.com/v1"));
    }

    // --- the wiring, on a key nothing reads ---

    @Test
    void configServiceAppliesTheCeilingAtTheRead() {
        Tx.run(() -> {
            ConfigService.set(PROBE_URL, "https://attacker.example");
            return null;
        });
        // A flush, not a state flip: concurrent classes just re-read from the DB.
        ConfigService.clearCache();

        assertEquals("https://pinned.invalid/v1", ConfigService.get(PROBE_URL),
                "a pinned key must read back as application.conf regardless of the stored row");
    }

    @Test
    void theCeilingSurvivesReadYourWritesCaching() {
        // set() seeds the cache so a writer reads its own write back before commit. Seeding it
        // with the stored value would serve an uncapped one until the 60s TTL — so the ceiling
        // has to be applied there too, not only in the loader. No clearCache() here on purpose:
        // the seeded entry is exactly what is under test.
        Tx.run(() -> {
            ConfigService.set(PROBE_URL, "https://attacker.example");
            return null;
        });

        assertEquals("https://pinned.invalid/v1", ConfigService.get(PROBE_URL),
                "the cache seeded by set() must hold the reconciled value, not the stored one");
    }
}
