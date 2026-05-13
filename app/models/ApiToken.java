package models;

import jakarta.persistence.*;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import play.db.jpa.Model;
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

    /** Token owner — only ever {@code "system"} today. Read by the
     *  bearer-auth filter and stashed in {@code session.username} so
     *  downstream code that reads identity sees a stable value. Kept
     *  as a column (rather than hardcoded) so the multi-tenancy
     *  follow-up has an obvious extension point. */
    @Column(name = "owner_username", nullable = false, length = 100)
    public String ownerUsername;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "last_used_at")
    public Instant lastUsedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
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
        var now = Instant.now();
        if (lastUsedAt == null || lastUsedAt.isBefore(now.minusSeconds(MARK_USED_THROTTLE_SECONDS))) {
            lastUsedAt = now;
        }
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
        var query = play.db.jpa.JPA.em().createQuery(
                "SELECT t FROM ApiToken t WHERE t.secretHash = :hash",
                ApiToken.class);
        query.setParameter("hash", hash);
        query.setHint("org.hibernate.cacheable", true);
        var results = query.getResultList();
        return results.isEmpty() ? null : results.get(0);
    }
}
