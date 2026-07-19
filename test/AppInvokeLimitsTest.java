import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.AppInvokeLimits;

/**
 * JCLAW-766 / AD-5: the per-app invoke rate-limit core. Both halves are pure — the
 * effective-limit clamp takes default+ceiling as arguments, and {@code tryAcquire}
 * takes an explicit {@code nowMillis} — so the window roll is tested deterministically
 * with no wall clock. Each test uses a unique slug, so the shared counter map can't
 * bleed across the concurrently-run suite.
 */
class AppInvokeLimitsTest extends UnitTest {

    // ----- effectiveLimit: app.json tighten-only within the ceiling (AD-5) -----

    @Test
    void effectiveLimit_noOverride_usesDefault() {
        assertEquals(30, AppInvokeLimits.effectiveLimit(null, 30, 120));
    }

    @Test
    void effectiveLimit_overrideBelowCeiling_applies() {
        assertEquals(5, AppInvokeLimits.effectiveLimit(5, 30, 120));
    }

    @Test
    void effectiveLimit_overrideAboveCeiling_clampedToCeiling() {
        // The app authors app.json; a limit above the ceiling must NOT be honored.
        assertEquals(120, AppInvokeLimits.effectiveLimit(999_999, 30, 120));
    }

    @Test
    void effectiveLimit_negativeOverride_treatedAsAbsent() {
        assertEquals(30, AppInvokeLimits.effectiveLimit(-1, 30, 120));
    }

    @Test
    void effectiveLimit_misconfiguredDefaultStillClampedByCeiling() {
        assertEquals(50, AppInvokeLimits.effectiveLimit(null, 500, 50));
    }

    // ----- tryAcquire: fixed-window counting + roll (AC2) -----

    @Test
    void tryAcquire_admitsUpToLimitThenRejects() {
        var slug = "unit-rl-basic";
        long now = 1_000_000_000L;
        assertTrue(AppInvokeLimits.tryAcquire(slug, 2, now, 60));   // 1st admitted
        assertTrue(AppInvokeLimits.tryAcquire(slug, 2, now, 60));   // 2nd admitted
        assertFalse(AppInvokeLimits.tryAcquire(slug, 2, now, 60));  // 3rd over the limit
    }

    @Test
    void tryAcquire_windowRollResetsCount() {
        var slug = "unit-rl-roll";
        assertTrue(AppInvokeLimits.tryAcquire(slug, 1, 0L, 60));       // window 0
        assertFalse(AppInvokeLimits.tryAcquire(slug, 1, 30_000L, 60)); // same window 0 — rejected
        assertTrue(AppInvokeLimits.tryAcquire(slug, 1, 60_000L, 60));  // window 1 — admitted again
    }

    @Test
    void tryAcquire_zeroLimitAdmitsNothing() {
        assertFalse(AppInvokeLimits.tryAcquire("unit-rl-zero", 0, 0L, 60));
    }

    @Test
    void tryAcquire_perSlugIsolation() {
        long now = 5_000L;
        assertTrue(AppInvokeLimits.tryAcquire("unit-rl-a", 1, now, 60));
        assertFalse(AppInvokeLimits.tryAcquire("unit-rl-a", 1, now, 60)); // a exhausted
        assertTrue(AppInvokeLimits.tryAcquire("unit-rl-b", 1, now, 60));  // b independent
    }
}
