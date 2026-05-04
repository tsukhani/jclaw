package models;

import jakarta.persistence.*;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import play.db.jpa.Model;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Entity
@Table(name = "channel_config")
// JCLAW-205: Hibernate L2 cache via Caffeine. Channel configs are tiny
// (~5 rows) and read on every inbound webhook message. The hand-rolled
// CachedSnapshot cache below predates L2; once JCLAW-203 lands the
// hand-rolled layer can be removed entirely since L2 covers it.
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
    // cache with a null snapshot for 60 s — subsequent writes inside that
    // window are invisible to the next read.
    @PostPersist
    @PostUpdate
    @PostRemove
    void invalidateCache() {
        evictCache(channelType);
    }

    // ── TTL cache for channel configs (avoids DB hit on every message send) ──
    // Returns a transient (non-managed) copy to avoid detached-entity errors.
    // Callers only read configJson/enabled — they never persist the result.

    private static final long CACHE_TTL_MS = 60_000; // 60 seconds
    private record CachedSnapshot(Long id, String channelType, String configJson, boolean enabled,
                                   Instant createdAt, Instant updatedAt, long expiresAt) {
        boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
        ChannelConfig toTransient() {
            var cc = new ChannelConfig();
            cc.id = id;
            cc.channelType = channelType;
            cc.configJson = configJson;
            cc.enabled = enabled;
            cc.createdAt = createdAt;
            cc.updatedAt = updatedAt;
            return cc;
        }
    }
    private static final ConcurrentHashMap<String, CachedSnapshot> cache = new ConcurrentHashMap<>();

    public static ChannelConfig findByType(String channelType) {
        var cached = cache.get(channelType);
        if (cached != null && !cached.isExpired()) {
            return cached.configJson() != null ? cached.toTransient() : null;
        }
        // Callers on SDK threads (Telegram long-polling executor) and virtual
        // threads spawned from webhook controllers have no JPA transaction
        // bound. Wrap the cache-miss DB read in Tx.run — it short-circuits
        // when the caller is already inside a transaction (the admin save
        // path), so managed-entity semantics are preserved there.
        return services.Tx.run(() -> {
            ChannelConfig config = ChannelConfig.find("channelType", channelType).first();
            cache.put(channelType, new CachedSnapshot(
                    config != null ? config.id : null,
                    channelType,
                    config != null ? config.configJson : null,
                    config != null && config.enabled,
                    config != null ? config.createdAt : null,
                    config != null ? config.updatedAt : null,
                    System.currentTimeMillis() + CACHE_TTL_MS));
            return config;
        });
    }

    /** Evict the cache for a specific channel type (call after admin updates). */
    public static void evictCache(String channelType) {
        cache.remove(channelType);
    }

    /** Evict all cached channel configs. */
    public static void evictAllCache() {
        cache.clear();
    }
}
