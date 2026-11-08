import models.ChromeBridgeToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.ChromeBridgePairing;
import utils.TokenHasher;

import java.util.List;

class ChromeBridgePairingTest extends UnitTest {

    @BeforeEach
    @AfterEach
    void clear() {
        ChromeBridgeToken.deleteAll();
    }

    private static ChromeBridgeToken onlyRow() {
        List<ChromeBridgeToken> rows = ChromeBridgeToken.findAll();
        assertEquals(1, rows.size());
        return rows.getFirst();
    }

    @Test
    void mintStoresTheHashAndNeverThePlaintext() {
        var token = ChromeBridgePairing.mint();

        var row = onlyRow();
        assertEquals(TokenHasher.hash(token), row.secretHash);
        assertNotEquals(token, row.secretHash);
        assertNull(row.revokedAt);
    }

    @Test
    void verifyAcceptsTheMintedTokenAndNothingElse() {
        var token = ChromeBridgePairing.mint();

        assertTrue(ChromeBridgePairing.verify(token));
        assertFalse(ChromeBridgePairing.verify(token + "x"));
        assertFalse(ChromeBridgePairing.verify(TokenHasher.mint()));
        assertFalse(ChromeBridgePairing.verify(""));
        assertFalse(ChromeBridgePairing.verify(null));
    }

    @Test
    void aNewPairingRevokesTheOldOne() {
        var first = ChromeBridgePairing.mint();
        var second = ChromeBridgePairing.mint();

        assertFalse(ChromeBridgePairing.verify(first));
        assertTrue(ChromeBridgePairing.verify(second));
        assertEquals(1L, ChromeBridgeToken.count("revokedAt IS NULL"));
        assertEquals(2L, ChromeBridgeToken.count());
    }

    @Test
    void revokeCutsTheExtensionOffAndReportsHowManyWereActive() {
        var token = ChromeBridgePairing.mint();
        assertTrue(ChromeBridgePairing.isPaired());

        assertEquals(1, ChromeBridgePairing.revoke());

        assertFalse(ChromeBridgePairing.verify(token));
        assertFalse(ChromeBridgePairing.isPaired());
        assertEquals(0, ChromeBridgePairing.revoke());
    }

    @Test
    void verifyRecordsWhenTheExtensionLastConnected() {
        var token = ChromeBridgePairing.mint();
        var before = onlyRow();
        assertNull(before.lastConnectedAt);

        ChromeBridgePairing.verify(token);

        var after = onlyRow();
        assertNotNull(after.lastConnectedAt);
    }

    @Test
    void nothingIsPairedToStartWith() {
        assertFalse(ChromeBridgePairing.isPaired());
    }
}
