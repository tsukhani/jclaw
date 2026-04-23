import controllers.ApiAuthController;
import services.ConfigService;
import services.Tx;
import utils.PasswordHasher;

/**
 * Test-only helper — seed a known admin password hash into the Config table
 * so FunctionalTests that exercise the login flow can use a predictable
 * plaintext. Commits on a fresh virtual thread so the write lands before
 * the HTTP request under test runs; FunctionalTest's carrier thread is
 * already inside a JPA transaction and an inline {@code ConfigService.set}
 * would otherwise sit uncommitted until after the test returns, invisible
 * to the in-process HTTP handler (see WebhookControllerTest.commitInFreshTx
 * for the canonical prior art and the JPA-isolation memory note for the
 * reasoning).
 */
public final class AuthFixture {

    private AuthFixture() {}

    public static void seedAdminPassword(String plaintext) {
        var hash = PasswordHasher.hash(plaintext);
        runInFreshTx(() -> ConfigService.set(ApiAuthController.PASSWORD_HASH_KEY, hash));
    }

    /** Counterpart to {@link #seedAdminPassword} — commits the delete on a
     *  fresh tx so tests that exercise the "password unset" flow see an
     *  empty Config row from the HTTP-handler side. */
    public static void clearAdminPassword() {
        runInFreshTx(() -> ConfigService.delete(ApiAuthController.PASSWORD_HASH_KEY));
    }

    private static void runInFreshTx(Runnable block) {
        var t = Thread.ofVirtual().start(() -> Tx.run(block));
        try {
            t.join();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        ConfigService.clearCache();
    }
}
