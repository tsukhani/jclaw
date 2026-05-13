package models;

import jakarta.persistence.*;
import play.db.jpa.Model;
import utils.TokenHasher;

import java.time.Instant;
import java.util.List;

/**
 * Operator-minted API token for bearer authentication (JCLAW-282).
 *
 * <p>JClaw's existing session-cookie auth flow can't be carried by an
 * out-of-process MCP client (no cookie jar, no /api/auth/login round
 * trip). This model lets an operator mint a long-lived token in
 * Settings, hand it to a tool like Claude Desktop, and have every
 * incoming request bearing {@code Authorization: Bearer <token>}
 * resolve to that token's row — and thus to its owner.
 *
 * <p><b>Storage.</b> The plaintext token is shown to the operator
 * exactly once at mint time. The DB stores only:
 * <ul>
 *   <li>{@link #secretHash} — SHA-256 hex of the full token. Deterministic
 *       so the bearer-resolve path is an indexed lookup, not a row scan.</li>
 *   <li>{@link #displayPrefix} — short non-secret fragment
 *       ({@code jcl_xxxxxxxx}) for the listing UI.</li>
 * </ul>
 *
 * <p><b>Scope.</b> {@link Scope#READ_ONLY} tokens can only call HTTP
 * GET endpoints; everything else 403s in
 * {@code AuthCheck.checkAuthentication}. The MCP server enforces the
 * same rule at tool-generation time so a read-only token never sees a
 * mutating tool advertised at all — belt-and-suspenders against an
 * agent attempting a write.
 *
 * <p><b>Owner.</b> JClaw is currently single-admin so {@link #ownerUsername}
 * is always the configured admin name. The column exists in anticipation
 * of multi-tenancy (per the {@code multi_tenancy_design} memory note); the
 * bearer-resolve path already sets {@code session.username} from it, so
 * downstream controllers that grow per-owner ACLs need no schema change.
 *
 * <p><b>Revocation.</b> Soft-delete via {@link #revokedAt} rather than
 * row removal so operators can audit when a token was disabled. A
 * revoked token's hash stays in the DB and continues to 401 (with a
 * distinguishable message) until physically deleted.
 */
@Entity
@Table(name = "api_token", indexes = {
        @Index(name = "idx_api_token_owner", columnList = "owner_username"),
        @Index(name = "idx_api_token_revoked", columnList = "revoked_at")
})
public class ApiToken extends Model {

    public enum Scope {
        /** GET-only. Mutating verbs return 403. */
        READ_ONLY,
        /** All verbs allowed. */
        FULL
    }

    @Column(nullable = false, length = 100)
    public String name;

    @Column(name = "secret_hash", nullable = false, length = 64, unique = true)
    public String secretHash;

    @Column(name = "display_prefix", nullable = false, length = 16)
    public String displayPrefix;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    public Scope scope = Scope.READ_ONLY;

    @Column(name = "owner_username", nullable = false, length = 100)
    public String ownerUsername;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "last_used_at")
    public Instant lastUsedAt;

    @Column(name = "revoked_at")
    public Instant revokedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    /** True once {@link #revoke} has been called. Revoked tokens still
     *  match in {@link #findActiveByPlaintext} returning null — kept
     *  separate so a revoked-vs-unknown distinction is observable. */
    public boolean isRevoked() {
        return revokedAt != null;
    }

    public void revoke() {
        if (revokedAt == null) revokedAt = Instant.now();
    }

    /** Threshold for the throttle in {@link #markUsed}. Bearer auth fires
     *  on every {@code /api/**} call; without throttling, each request
     *  would UPDATE this row, which invalidates the Hibernate L2 query-
     *  cache entry for {@link #findActiveByPlaintext} that the same
     *  request just populated. 60 seconds keeps the "last used" Settings
     *  UI display honest at minute granularity while letting the query
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
     *  L2 entity-cache entry for this row.
     *
     *  <p>The audit signal isn't lost: an operator looking at the
     *  Settings → API Tokens page sees "last used N seconds/minutes
     *  ago" with a worst-case staleness of one throttle interval. For
     *  the UI's purpose ("is this token still in use?") that's plenty. */
    public void markUsed() {
        var now = Instant.now();
        if (lastUsedAt == null || lastUsedAt.isBefore(now.minusSeconds(MARK_USED_THROTTLE_SECONDS))) {
            lastUsedAt = now;
        }
    }

    /** Resolve a plaintext bearer token to its row. Returns null if no
     *  row matches OR the matching row is revoked — the caller can't
     *  tell them apart, which is intentional (don't leak that a token
     *  once existed). The 64-char {@code secret_hash} index makes this
     *  an O(1) hit even with many active tokens.
     *
     *  <p>Hibernate query-cache enabled: same plaintext within the
     *  {@link #markUsed} throttle window returns from cache without
     *  hitting the DB at all. The query cache is opted in per-query
     *  rather than globally — only the bearer-auth hot path needs it,
     *  and other ApiToken queries (e.g. {@link #listForOwner}) intentionally
     *  miss the cache so the Settings UI always sees a fresh list.
     *
     *  <p>Drops to {@code JPA.em().createQuery} so we can attach the
     *  {@code org.hibernate.cacheable} hint — Play 1.x's {@code Model.find}
     *  doesn't surface a hook for it.
     *
     *  <p>Does NOT update {@link #lastUsedAt} on its own — that's the
     *  bearer-auth filter's job after it's decided to admit the request,
     *  so an unrelated 4xx (bad input) doesn't fake a usage record. */
    public static ApiToken findActiveByPlaintext(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) return null;
        var hash = TokenHasher.hash(plaintext);
        var query = play.db.jpa.JPA.em().createQuery(
                "SELECT t FROM ApiToken t WHERE t.secretHash = :hash AND t.revokedAt IS NULL",
                ApiToken.class);
        query.setParameter("hash", hash);
        query.setHint("org.hibernate.cacheable", true);
        var results = query.getResultList();
        return results.isEmpty() ? null : results.get(0);
    }

    /** Same as {@link #findActiveByPlaintext} but returns revoked rows
     *  too — used by the listing endpoint so operators can see revoked
     *  tokens in their history.
     *
     *  <p>Sort: active rows (revokedAt IS NULL) first, then revoked
     *  ones; within each partition, newest first. {@code NULLS FIRST}
     *  is the Hibernate 6 JPQL form for this — the older
     *  {@code ORDER BY revokedAt IS NULL DESC} idiom doesn't parse
     *  (mismatched 'IS' against the JPQL grammar). */
    public static List<ApiToken> listForOwner(String ownerUsername) {
        return ApiToken.find(
                "ownerUsername = ?1 ORDER BY revokedAt NULLS FIRST, createdAt DESC",
                ownerUsername).fetch();
    }
}
