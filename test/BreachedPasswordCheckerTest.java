import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.BreachedPasswordChecker;

import java.util.Optional;

/**
 * JCLAW-741: unit coverage for breach screening. Exercises the pure pieces —
 * the online/offline fallback composition, the k-anonymity range parse (incl.
 * the Add-Padding count-0 decoys), SHA-1 hashing, and the offline list — with
 * no network and no global mutable state, so it is deterministic under the
 * concurrent unit+functional lanes.
 */
class BreachedPasswordCheckerTest extends UnitTest {

    @Test
    void onlineVerdictWinsWhenPresent() {
        assertTrue(BreachedPasswordChecker.decide("anything", Optional.of(true)));
        // Online "not breached" is authoritative — it wins even over a word
        // that also sits in the offline list.
        assertFalse(BreachedPasswordChecker.decide("1qaz2wsx3edc", Optional.of(false)),
                "an online 'safe' verdict must win over the offline list");
    }

    @Test
    void fallsBackToOfflineListWhenOnlineAbsent() {
        // 1qaz2wsx3edc is a keyboard-walk seeded in conf/common-passwords.txt.
        assertTrue(BreachedPasswordChecker.decide("1qaz2wsx3edc", Optional.empty()));
        assertTrue(BreachedPasswordChecker.decide("1QAZ2wsx3EDC", Optional.empty()),
                "offline match is case-insensitive");
        assertFalse(BreachedPasswordChecker.decide("a-very-unlikely-unique-passphrase-42", Optional.empty()));
    }

    @Test
    void rangeParseRequiresPositiveCount() {
        // Body lines are SUFFIX:count; Add-Padding decoys carry count 0.
        String body = "AAA:5\r\nBBB:0";
        assertTrue(BreachedPasswordChecker.suffixInRange(body, "AAA"));
        assertFalse(BreachedPasswordChecker.suffixInRange(body, "BBB"),
                "a count-0 padding line must not read as breached");
        assertFalse(BreachedPasswordChecker.suffixInRange(body, "CCC"),
                "an absent suffix is not breached");
    }

    @Test
    void rangeParseIsCaseInsensitive() {
        assertTrue(BreachedPasswordChecker.suffixInRange("abc:3", "ABC"));
    }

    @Test
    void sha1HexMatchesKnownVector() {
        // SHA-1("password") = 5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8
        assertEquals("5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8",
                BreachedPasswordChecker.sha1Hex("password"));
    }

    @Test
    void offlineListMatchesKnownCommonPassword() {
        assertTrue(BreachedPasswordChecker.offlineContains("password"));
        assertFalse(BreachedPasswordChecker.offlineContains("a-very-unlikely-unique-passphrase-42"));
    }
}
