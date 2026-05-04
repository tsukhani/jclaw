package models;

import jakarta.persistence.*;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import play.cache.CacheConfig;
import play.cache.Caches;
import play.db.jpa.Model;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Entity
@Table(name = "channel_config")
// JCLAW-205: Hibernate L2 cache via Caffeine. Catches direct entity-by-ID
// reads. The findByType lookup below is a separate Caches.named layer
// because L2 caches by primary key, not by secondary unique fields like
// channelType — the JPQL query still runs without it. The two layers are
// complementary: L2 cuts the field re-fetch on hit, the named cache cuts
// the SQL altogether.
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
public class ChannelConfig extends Model {

    @Column(name = "channel_type", nullable = false, unique = true)
    public String channelType;

    @Column(name = "config_json", nullable = false, columnDefinition = "TEXT")
    public String configJson;

    @Column(nullable = false)
    public boolean enabled = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @PrePersist
    void onCreate() {
        var now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    // Keep the TTL cache coherent with JPA lifecycle. Without this hook a test
    // (or background job) that reads "telegram" before a row exists poisons the
    // cache with an empty Optional for 60 s — subsequent writes inside that
    // window are invisible to the next read.
    @PostPersist
    @PostUpdate
    @PostRemove
    void invalidateCache() {
        evictCache(channelType);
    }

    // JCLAW-203: secondary-key lookup cache. Stores Optional<ChannelConfig>
    // so a missing row is memoized as Optional.empty rather than re-queried
    // on every miss (negative caching). Caller reads scalar fields only
    // (configJson, enabled) so the entity can safely outlive its tx.
    private static final play.cache.Cache<String, Optional<ChannelConfig>> cache = Caches.named(
            "channel-configs",
            CacheConfig.newBuilder()
                    .expireAfterWrite(Duration.ofSeconds(60))
                    .build());

    public static ChannelConfig findByType(String channelType) {
        // Callers on SDK threads (Telegram long-polling executor) and virtual
        // threads spawned from webhook controllers have no JPA transaction
        // bound. Wrap the cache-miss DB read in Tx.run — it short-circuits
        // when the caller is already inside a transaction (the admin save
        // path), so managed-entity semantics are preserved there.
        return cache.get(channelType, k -> services.Tx.run(() ->
                Optional.ofNullable((ChannelConfig) ChannelConfig.find("channelType", k).first())))
                .orElse(null);
    }

    /** Evict the cache for a specific channel type (call after admin updates). */
    public static void evictCache(String channelType) {
        cache.invalidate(channelType);
    }

    /** Evict all cached channel configs. */
    public static void evictAllCache() {
        cache.invalidateAll();
    }
}
