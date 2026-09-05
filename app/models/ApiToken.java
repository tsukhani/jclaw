package models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import play.db.jpa.JPA;
import play.db.jpa.Model;
import utils.AppClock;
import utils.TokenHasher;

import java.time.Instant;

/**
 * Bearer-credential row for the in-process {@code jclaw_api} tool
 * (JCLAW-282, simplified after the external-surface drop).
 *
 * <p>JClaw's bearer-auth path needs an indexed-lookup record that
 * validates the {@code Authorization: Bearer <plaintext>} header an
 * agent's HTTP call carries when {@code jclaw_api} loopbacks to its
 * own {@code /api/**}. {@link services.InternalApiTokenService}
 * bootstraps a single row at first boot, stores the plaintext under
 * {@code auth.internal.apiToken} in the Config table, and the bearer
 * filter resolves incoming headers to this row via
 * {@link #findActiveByPlaintext}.
 *
 * <p>The operator-minted-token surface that originally shipped with
 * JCLAW-282 (Settings → API Tokens, {@code ApiTokensController},
 * {@code /api/api-tokens/**} routes) was dropped — after the standalone
 * mcp-server jar pivot, no in-tree caller consumed external tokens, so
 * the related fields ({@code name}, {@code displayPrefix}, {@code Scope},
 * {@code revokedAt}) collapsed into dead weight. They live in git
 * history if external tokens come back as an explicit need.
 *
 * <p><b>L2 cache.</b> Query-cache enabled on
 * {@link #findActiveByPlaintext} so repeated bearer calls within the
 * {@link #markUsed} throttle window resolve from cache instead of
 * hitting H2. The throttle is what keeps the entry valid — without it,
 * the save() that follows each successful auth would invalidate the
 * cache before the next call could reuse it.
 */
@Entity
@Table(name = "api_token")
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
public class ApiToken extends Model {

    @Column(name = "secret_hash", nullable = false, length = 64, unique = true)
    public String secretHash;

    /** Token owner — always {@code "system"} in single-operator Personal Edition.
     *  Read by the bearer-auth filter and stashed in {@code session.username} so
     *  downstream code that reads identity sees a stable value. */
    @Column(name = "owner_username", nullable = false, length = 100)
    public String ownerUsername;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "last_used_at")
    public Instant lastUsedAt;

    /**
     * When this token stops being accepted, or null for one that never expires
     * (JCLAW-1034). Nullable on purpose: a NOT NULL column cannot be added to a
     * populated table, and rows minted before this existed are legitimately open-ended.
     */
    @Column(name = "expires_at")
    public Instant expiresAt;

    /**
     * When an operator withdrew this token, or null while it stands (JCLAW-1034).
     * Recorded rather than deleted so revocation survives {@code ensureToken}, which
     * re-mints a <em>missing</em> internal row — deleting was self-defeating.
     */
    @Column(name = "revoked_at")
    public Instant revokedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = AppClock.now();
    }

    /** True when this row may still authenticate a request. */
    public boolean isActive() {
        var now = AppClock.now();
        return revokedAt == null && (expiresAt == null || expiresAt.isAfter(now));
    }

    /** Threshold for the throttle in {@link #markUsed}. Bearer auth fires
     *  on every {@code /api/**} call; without throttling, each request
     *  would UPDATE this row, which invalidates the Hibernate L2 query-
     *  cache entry for {@link #findActiveByPlaintext} that the same
     *  request just populated. 60 seconds keeps the "last used"
     *  diagnostic honest at minute granularity while letting the query
     *  cache actually serve repeated lookups within the window.
     *
     *  <p>Public so the default-package test class can pin the value
     *  without duplicating it (the test classpath isn't in the
     *  {@code models} package, so a package-private constant would be
     *  invisible). */
    public static final long MARK_USED_THROTTLE_SECONDS = 60;

    /** Update {@link #lastUsedAt} only if it's null or older than
     *  {@link #MARK_USED_THROTTLE_SECONDS}. When the field doesn't
     *  change, Hibernate's dirty-check skips the UPDATE entirely on
     *  the next save() — preserving both the query-cache entry and the
     *  L2 entity-cache entry for this row. */
    public void markUsed() {
        var now = AppClock.now();
        if (lastUsedAt == null || lastUsedAt.isBefore(now.minusSeconds(MARK_USED_THROTTLE_SECONDS))) {
            lastUsedAt = now;
        }
    }

    /** The row for {@code plaintext} whatever its state, or null if none exists. Lets a caller
     *  tell a <em>missing</em> row (safe to re-mint) from a <em>revoked</em> one (must not be),
     *  a distinction {@link #findActiveByPlaintext} collapses by design (JCLAW-1034). */
    public static ApiToken findAnyByPlaintext(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) return null;
        return ApiToken.find("secretHash = ?1", TokenHasher.hash(plaintext)).first();
    }

    /** Resolve a plaintext bearer token to its row. Returns null if no
     *  row matches. The 64-char {@code secret_hash} unique index makes
     *  this an O(1) hit even with many tokens (we only ever expect one
     *  today, but the index is the right shape regardless).
     *
     *  <p>Hibernate query-cache enabled: same plaintext within the
     *  {@link #markUsed} throttle window returns from cache without
     *  hitting the DB. Drops to {@code JPA.em().createQuery} so we can
     *  attach the {@code org.hibernate.cacheable} hint — Play 1.x's
     *  {@code Model.find} doesn't surface a hook for it.
     *
     *  <p>Does NOT update {@link #lastUsedAt} on its own — that's the
     *  bearer-auth filter's job after it's decided to admit the request,
     *  so an unrelated 4xx (bad input) doesn't fake a usage record. */
    public static ApiToken findActiveByPlaintext(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) return null;
        var hash = TokenHasher.hash(plaintext);
        // JCLAW-1034: the name promised "active" and the predicate did not deliver it — a
        // revoked or expired row still authenticated. Revocation is a static predicate and
        // stays in the query; expiry is checked in Java on the row deliberately, because
        // binding a :now parameter would give every call its own L2 query-cache key and
        // discard the cache this lookup exists on the hot path to use.
        var query = JPA.em().createQuery(
                "SELECT t FROM ApiToken t WHERE t.secretHash = :hash AND t.revokedAt IS NULL",
                ApiToken.class);
        query.setParameter("hash", hash);
        query.setHint("org.hibernate.cacheable", true);
        var results = query.getResultList();
        if (results.isEmpty()) return null;
        var token = results.getFirst();
        return token.isActive() ? token : null;
    }
}
