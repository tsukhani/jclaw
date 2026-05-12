package services;

import models.ApiToken;
import utils.TokenHasher;

/**
 * Bootstrap and cache the bearer token the in-process {@code jclaw_api}
 * tool uses to call its own {@code /api/**} endpoints (JCLAW-282).
 *
 * <p>JClaw's bearer-auth path needs an {@link ApiToken} row to validate
 * the {@code Authorization: Bearer <plaintext>} header against. For the
 * tool-to-localhost call, plaintext has to live somewhere the tool can
 * read at request time — it can't recompute the secret from the row's
 * hash. So at first boot we mint the token, save the row (FULL scope,
 * owner {@code "system"}), and stash the plaintext under the
 * {@link #INTERNAL_TOKEN_CONFIG_KEY} config row. Subsequent boots reuse
 * the existing value; if the config row was wiped but the ApiToken row
 * survives, we mint fresh and replace both so the system stays self-
 * healing.
 *
 * <p><b>Why a config row and not an environment variable?</b> The token
 * must be readable from any thread, persisted across restarts, and live
 * in the same backing store as the rest of JClaw's secrets. Env vars
 * also require operator action on every fresh install; the auto-bootstrap
 * approach matches {@code DefaultConfigJob}'s broader posture of "make
 * it work without operator setup".
 *
 * <p>The config key is filtered from {@code /api/config} listings (see
 * {@code ApiConfigController.RESERVED_KEY_PREFIX}-style guard) and the
 * row's owner of {@code "system"} keeps it out of the Settings UI
 * token listing (which filters to the admin username). The plaintext
 * never leaks through any HTTP-visible surface.
 */
public final class InternalApiTokenService {

    /** Plaintext bearer token used by {@code jclaw_api}. Filtered out
     *  of all {@code /api/config**} surfaces by the
     *  {@link #INTERNAL_KEY_PREFIX} guard in {@code ApiConfigController}. */
    public static final String INTERNAL_TOKEN_CONFIG_KEY = "auth.internal.apiToken";

    /** Every key starting with this prefix is reserved for JClaw-internal
     *  state (bearer tokens, future system secrets) and refused by the
     *  Config API. Operators who need to inspect it can read the DB
     *  directly — same posture as the password hash. */
    public static final String INTERNAL_KEY_PREFIX = "auth.internal.";

    /** Token owner reserved for auto-managed system tokens. The
     *  {@code ApiTokensController.list} path filters to the configured
     *  admin username, so {@code system}-owned tokens stay invisible
     *  in the Settings UI. */
    public static final String SYSTEM_OWNER = "system";

    private static final String INTERNAL_TOKEN_NAME = "jclaw-internal-api";

    private static volatile String cachedToken;

    private InternalApiTokenService() {}

    /** Return the plaintext bearer token, bootstrapping it on first call.
     *  Thread-safe: the heavy path runs once under a class-monitor; later
     *  callers see the cached value without locking. Token validity is
     *  verified against the {@link ApiToken} table on cache miss so a
     *  surviving config row whose matching ApiToken row was deleted
     *  doesn't keep a broken token alive. */
    public static String token() {
        var cached = cachedToken;
        if (cached != null) return cached;
        synchronized (InternalApiTokenService.class) {
            if (cachedToken != null) return cachedToken;
            cachedToken = ensureToken();
            return cachedToken;
        }
    }

    /** Visible-for-tests: reset the cache so the next {@link #token()}
     *  re-reads from the DB. Production code never needs this — left
     *  public because the test lives in the default package, not in
     *  {@code services}, so a package-private modifier would hide it. */
    public static void invalidateCache() {
        cachedToken = null;
    }

    private static String ensureToken() {
        var stored = ConfigService.get(INTERNAL_TOKEN_CONFIG_KEY);
        if (stored != null && !stored.isBlank()) {
            // Verify the row still exists. If an operator manually
            // revoked or deleted it, treat the cached plaintext as
            // stale and re-mint.
            var row = ApiToken.findActiveByPlaintext(stored);
            if (row != null) return stored;
            EventLogger.info("auth",
                    "Internal jclaw_api token row missing or revoked — re-minting");
        }
        return mintAndStore();
    }

    /** Mint a fresh token, persist both halves (config row carrying the
     *  plaintext, ApiToken row carrying the hash), commit on a fresh tx
     *  so startup code that runs outside a request thread is safe. */
    private static String mintAndStore() {
        var plaintext = TokenHasher.mint();
        Tx.run(() -> {
            ConfigService.set(INTERNAL_TOKEN_CONFIG_KEY, plaintext);
            var row = new ApiToken();
            row.name = INTERNAL_TOKEN_NAME;
            row.scope = ApiToken.Scope.FULL;
            row.ownerUsername = SYSTEM_OWNER;
            row.secretHash = TokenHasher.hash(plaintext);
            row.displayPrefix = TokenHasher.prefix(plaintext);
            row.save();
        });
        EventLogger.info("auth",
                "Bootstrapped internal jclaw_api token (owner=%s, scope=FULL)"
                        .formatted(SYSTEM_OWNER));
        return plaintext;
    }
}
