package services;

import models.ApiToken;
import org.jspecify.annotations.Nullable;
import utils.TokenHasher;

/**
 * Bootstrap and resolve the bearer token the in-process {@code jclaw_api}
 * tool uses to call its own {@code /api/**} endpoints (JCLAW-282).
 *
 * <p>JClaw's bearer-auth path needs an {@link ApiToken} row to validate the
 * {@code Authorization: Bearer <plaintext>} header against, and the tool cannot
 * recompute the secret from that row's hash — so the plaintext has to live
 * somewhere readable at request time. Since JCLAW-1266 that somewhere is this
 * process and nowhere else: the row keeps the hash, the field below keeps the
 * plaintext, and the database holds no copy of the credential.
 *
 * <p><b>Why not a config row, as it was until JCLAW-1266?</b> A row is recoverable
 * by anything that reads the Config table without going through the API — a shell
 * query, the database file, or a backup archive — and the backup route was itself
 * only hidden from the agent tool rather than gated. The masking on
 * {@code /api/config} never covered those paths. A process-lifetime secret costs a
 * fresh token per restart, which nothing depends on, and removes the copy entirely.
 *
 * <p>Revocation still outlives the process. It lives on the {@link ApiToken} row,
 * which is why {@link #ensureToken} refuses to mint while a revoked system row
 * exists — without that check a restart would silently undo an operator's
 * revocation (JCLAW-1034 established the semantics; JCLAW-1266 kept them).
 */
public final class InternalApiTokenService {

    /** The pre-JCLAW-1266 home of the plaintext token. Retained only so
     *  {@link #dropLegacyPlaintextRow} can find and delete it on upgrade; nothing writes it. */
    public static final String INTERNAL_TOKEN_CONFIG_KEY = "auth.internal.apiToken";

    /** Every key starting with this prefix is reserved for JClaw-internal
     *  state (bearer tokens, future system secrets) and refused by the
     *  Config API. Operators who need to inspect it can read the DB
     *  directly — same posture as the password hash. */
    public static final String INTERNAL_KEY_PREFIX = "auth.internal.";

    /** Token owner reserved for auto-managed system tokens. Stashed on
     *  the {@link ApiToken} row so the bearer-auth filter can stamp
     *  {@code session.username} with a stable value when admitting
     *  internal requests. */
    public static final String SYSTEM_OWNER = "system";

    /** The live credential, for this process only. Never persisted: see the class Javadoc. */
    private static volatile @Nullable String plaintext;

    private InternalApiTokenService() {}

    /** Visible for testing: forget the process-held plaintext, as a restart would. Safe to leak
     *  across concurrently-running test classes — an unset field only causes the next caller to
     *  mint, which every path already handles. */
    public static void resetForTest() {
        plaintext = null;
    }

    /** Return the plaintext bearer token, bootstrapping it on first call and
     *  re-minting if its backing row has gone.
     *
     *  <p>JCLAW-852: this used to memoize the plaintext in a static field and
     *  verify the row only on a cache miss. Nothing missed after boot —
     *  {@code DefaultConfigJob} warms it at startup and only tests ever cleared
     *  it — so a deleted {@link ApiToken} row left the service handing out a
     *  credential that authenticated against nothing for the life of the JVM,
     *  breaking every {@code jclaw_api} tool call with a bare 401. The
     *  self-healing branch in {@link #ensureToken} existed and was tested the
     *  whole time; the cache simply made it unreachable.
     *
     *  <p>Verifying on every call closes that window outright rather than
     *  narrowing it, and costs little: {@code ConfigService.get} is TTL-cached
     *  and {@code findActiveByPlaintext} carries an L2 query cache sized for
     *  exactly this pattern. {@code AuthCheck} already runs the same lookup on
     *  every inbound request, so this is symmetric with the server side.
     *
     *  <p>{@link Tx#run} is what makes it safe to call from anywhere. The only
     *  production caller outside boot is {@code JClawApiTool}, which runs in the
     *  agent tool loop with no transaction open by design — reading the config
     *  and the token row there would otherwise throw the same
     *  "No active EntityManager" that JCLAW-849 fixed in the bearer filter. */
    public static String token() {
        return Tx.run(InternalApiTokenService::ensureToken);
    }

    private static String ensureToken() {
        dropLegacyPlaintextRow();

        var current = plaintext;
        if (current != null && !current.isBlank()) {
            // JCLAW-852: verify the row on every call rather than trusting the field. A cache
            // that skipped this left the service handing out a credential authenticating
            // against nothing for the life of the JVM, and made the self-healing branch below
            // unreachable. Holding the plaintext in memory does not re-open that: only the
            // lookup decides whether it is still good.
            if (ApiToken.findActiveByPlaintext(current) != null) return current;

            // JCLAW-1034: an absent row is self-healing, a revoked one is not — it is handed
            // back so every call 401s, which is what revocation means. An expired one re-mints.
            var row = ApiToken.findAnyByPlaintext(current);
            if (row != null && row.revokedAt != null) {
                EventLogger.warn("auth",
                        "Internal jclaw_api token is revoked — not re-minting; the tool stays "
                                + "unauthenticated until an operator clears the revocation");
                return current;
            }
            EventLogger.info("auth",
                    "Internal jclaw_api token row missing or expired — re-minting");
        }

        // The plaintext no longer survives a restart, so a revocation made before one would be
        // undone by the mint below unless the row is consulted first.
        if (ApiToken.hasRevokedTokenFor(SYSTEM_OWNER)) {
            EventLogger.warn("auth",
                    "A revoked internal jclaw_api token row exists — not minting a replacement; "
                            + "clear the revocation to restore the tool");
            return "";
        }
        return mintAndStore();
    }

    /**
     * Remove the pre-JCLAW-1266 config row and the credential it named.
     *
     * <p>Deleting the row alone would leave a working token behind: its plaintext was readable
     * from the database for as long as it existed, so any copy taken is still valid. The
     * {@link ApiToken} row goes with it. Deleted rather than revoked on purpose — a revocation
     * here would trip the guard above and refuse to mint the replacement.
     *
     * <p>Pre-v1 migration: delete this method and its call once every install has booted past it.
     */
    private static void dropLegacyPlaintextRow() {
        var legacy = ConfigService.get(INTERNAL_TOKEN_CONFIG_KEY);
        if (legacy == null || legacy.isBlank()) return;
        var row = ApiToken.findAnyByPlaintext(legacy);
        if (row != null) row.delete();
        ConfigService.delete(INTERNAL_TOKEN_CONFIG_KEY);
        EventLogger.warn("auth",
                "Removed the legacy plaintext jclaw_api token config row and the credential it "
                        + "named (JCLAW-1266); a fresh token is minted below");
    }

    /** Mint a fresh token, persist only its hash, and keep the plaintext in memory. Commits on
     *  a fresh tx so startup code running outside a request thread is safe. */
    private static String mintAndStore() {
        var minted = TokenHasher.mint();
        Tx.run(() -> {
            var row = new ApiToken();
            row.ownerUsername = SYSTEM_OWNER;
            row.secretHash = TokenHasher.hash(minted);
            row.save();
        });
        plaintext = minted;
        EventLogger.info("auth",
                "Bootstrapped internal jclaw_api token (owner=%s)".formatted(SYSTEM_OWNER));
        return minted;
    }
}
