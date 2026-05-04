package models;

import jakarta.persistence.*;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import play.db.jpa.Model;

import java.time.Instant;

@Entity
@Table(name = "config")
// JCLAW-205: Hibernate L2 cache via Caffeine. Config values are already
// cached at the service layer (services.ConfigService) — L2 here is
// belt-and-suspenders so direct Config.findById/findByKey calls (e.g.
// from tests or future call sites that bypass the service layer) get the
// same coverage.
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
public class Config extends Model {

    @Column(name = "config_key", nullable = false, unique = true)
    public String key;

    @Column(name = "config_value", columnDefinition = "TEXT")
    public String value;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @PrePersist
    void onCreate() {
        updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public static Config findByKey(String key) {
        return Config.find("key", key).first();
    }

    public static void upsert(String key, String value) {
        var config = findByKey(key);
        if (config == null) {
            config = new Config();
            config.key = key;
        }
        config.value = value;
        config.save();
    }
}
