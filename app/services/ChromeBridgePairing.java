package services;

import models.ChromeBridgeToken;
import utils.AppClock;
import utils.TokenHasher;

/**
 * Pairs one browser extension with this instance. A new pairing revokes the previous one, so
 * there is never more than one credential that can open the bridge socket.
 */
public final class ChromeBridgePairing {

    private static final String CATEGORY = "chrome-bridge";

    private ChromeBridgePairing() {}

    /** Mints a credential and returns its plaintext, which exists nowhere else afterwards. */
    public static String mint() {
        var plaintext = TokenHasher.mint();
        Tx.run(() -> {
            revokeActive();
            var row = new ChromeBridgeToken();
            row.secretHash = TokenHasher.hash(plaintext);
            row.save();
        });
        EventLogger.info(CATEGORY, "Chrome bridge paired; any earlier pairing revoked");
        return plaintext;
    }

    /** Revokes every active pairing; returns how many there were. */
    public static int revoke() {
        int revoked = Tx.run(ChromeBridgePairing::revokeActive);
        if (revoked > 0) EventLogger.info(CATEGORY, "Chrome bridge pairing revoked");
        return revoked;
    }

    public static boolean isPaired() {
        return Tx.run(() -> ChromeBridgeToken.count("revokedAt IS NULL") > 0);
    }

    /** True when {@code plaintext} is the active credential; records the connect time. */
    public static boolean verify(String plaintext) {
        return Tx.run(() -> {
            var row = ChromeBridgeToken.findActiveByPlaintext(plaintext);
            if (row == null) return false;
            row.lastConnectedAt = AppClock.now();
            row.save();
            return true;
        });
    }

    private static int revokeActive() {
        var now = AppClock.now();
        int n = 0;
        for (Object o : ChromeBridgeToken.find("revokedAt IS NULL").fetch()) {
            var row = (ChromeBridgeToken) o;
            row.revokedAt = now;
            row.save();
            n++;
        }
        return n;
    }
}
