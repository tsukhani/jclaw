import memory.MemoryTrust;
import memory.MemoryTrust.GraphTier;
import memory.MemoryTrust.TrustTier;
import models.MemoryVerification;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.util.List;

/** JCLAW-1318: the two computed tiers and the firm-threshold fallback. */
class MemoryTrustTest extends UnitTest {

    private static MemoryVerification verification(String actor) {
        var v = new MemoryVerification();
        v.actor = actor;
        v.kind = MemoryVerification.Kind.CONFIRMED;
        return v;
    }

    @Test
    void trustTierRanksAHumanVerificationAboveAMachineOne() {
        assertEquals(TrustTier.UNVERIFIED, MemoryTrust.trustTier(List.of()));
        assertEquals(TrustTier.MACHINE_CONFIRMED, MemoryTrust.trustTier(List.of(verification("process:judge"))));
        assertEquals(TrustTier.HUMAN_REVIEWED,
                MemoryTrust.trustTier(List.of(verification("process:judge"), verification("human:operator"))));
    }

    @Test
    void corroborationReachesFirmAtTheDefaultThreshold() {
        int t = MemoryTrust.DEFAULT_FIRM_THRESHOLD;
        assertEquals(GraphTier.TENTATIVE, MemoryTrust.graphTier("extractor/m1", List.of(), 1, t));
        assertEquals(GraphTier.FIRM, MemoryTrust.graphTier("extractor/m1", List.of(), 2, t));
        assertEquals(GraphTier.FIRM, MemoryTrust.graphTier("extractor/m1", List.of(), 3, t));
    }

    @Test
    void aHumanActorOrAHumanVerificationIsFirmWithoutCorroboration() {
        int t = MemoryTrust.DEFAULT_FIRM_THRESHOLD;
        assertEquals(GraphTier.FIRM, MemoryTrust.graphTier("human:operator", List.of(), 0, t));
        assertEquals(GraphTier.FIRM,
                MemoryTrust.graphTier("extractor/m1", List.of(verification("human:operator")), 0, t));
        assertEquals(GraphTier.TENTATIVE,
                MemoryTrust.graphTier("extractor/m1", List.of(verification("process:judge")), 0, t),
                "a machine verification does not make a memory firm");
    }

    @Test
    void aGuestActorIsTentativeBelowTheThresholdLikeAnyNonHuman() {
        int t = MemoryTrust.DEFAULT_FIRM_THRESHOLD;
        assertEquals(GraphTier.TENTATIVE, MemoryTrust.graphTier("guest:telegram", List.of(), t - 1, t),
                "guest: is not human:, so a guest's words alone are never firm (JCLAW-1353)");
        assertEquals(GraphTier.FIRM, MemoryTrust.graphTier("guest:telegram", List.of(), t, t));
        assertEquals(GraphTier.FIRM,
                MemoryTrust.graphTier("guest:telegram", List.of(verification("human:operator")), 0, t));
    }

    @Test
    void aLegacyRowIsTentative() {
        assertEquals(GraphTier.TENTATIVE, MemoryTrust.graphTier(null, List.of(), 0, 2));
    }

    @Test
    void theThresholdFallsBackToTheDefaultBelowOneOrWhenNotANumber() {
        assertEquals(3, MemoryTrust.parseFirmThreshold("3"));
        assertEquals(2, MemoryTrust.parseFirmThreshold("0"));
        assertEquals(2, MemoryTrust.parseFirmThreshold("-1"));
        assertEquals(2, MemoryTrust.parseFirmThreshold("abc"));
        assertEquals(2, MemoryTrust.parseFirmThreshold(null));
        assertEquals(GraphTier.TENTATIVE, MemoryTrust.graphTier("extractor/m1", List.of(), 2,
                MemoryTrust.parseFirmThreshold("3")));
    }
}
