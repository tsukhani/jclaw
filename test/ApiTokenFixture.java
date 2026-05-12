import models.ApiToken;
import services.Tx;
import utils.TokenHasher;

/**
 * Seed an {@link ApiToken} row visible to the in-process HTTP handler.
 *
 * <p>The carrier thread for {@code FunctionalTest} is already inside a
 * JPA transaction that doesn't commit until the test returns — so
 * a plain {@code new ApiToken().save()} on the test thread is invisible
 * to the request handler. Same JPA-isolation gotcha {@link AuthFixture}
 * works around. Commits on a fresh virtual thread + new tx, then waits
 * for the join so the HTTP request that follows sees the row.
 */
public final class ApiTokenFixture {

    private ApiTokenFixture() {}

    /** Mint and commit a token row. Returns the plaintext bearer the
     *  test should send in {@code Authorization: Bearer <plaintext>}. */
    public static String seedToken(String name, ApiToken.Scope scope, String owner) {
        var plaintext = TokenHasher.mint();
        var hash = TokenHasher.hash(plaintext);
        var prefix = TokenHasher.prefix(plaintext);
        runInFreshTx(() -> {
            var row = new ApiToken();
            row.name = name;
            row.scope = scope;
            row.ownerUsername = owner;
            row.secretHash = hash;
            row.displayPrefix = prefix;
            row.save();
        });
        return plaintext;
    }

    public static String seedReadOnly() {
        return seedToken("test-readonly", ApiToken.Scope.READ_ONLY, "admin");
    }

    public static String seedFull() {
        return seedToken("test-full", ApiToken.Scope.FULL, "admin");
    }

    /** Revoke every existing row. Used in @AfterEach so leakage across
     *  tests can't accumulate hashes that future tests then collide on. */
    public static void clearAll() {
        runInFreshTx(ApiToken::deleteAll);
    }

    /** Revoke a specific token by plaintext, on a fresh tx so the change
     *  is visible to a subsequent HTTP request. */
    public static void revokeByPlaintext(String plaintext) {
        var hash = TokenHasher.hash(plaintext);
        runInFreshTx(() -> {
            // Explicit type rather than `var` — JPAQuery.first() returns
            // Object and `var` would erase the inference target. Same
            // gotcha bit ApiToken.findActiveByPlaintext().
            ApiToken row = ApiToken.find("secretHash = ?1", hash).first();
            if (row != null) {
                row.revoke();
                row.save();
            }
        });
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
    }
}
