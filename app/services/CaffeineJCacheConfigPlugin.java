package services;

import com.github.benmanes.caffeine.jcache.configuration.TypesafeConfigurator;
import com.typesafe.config.ConfigFactory;
import play.PlayPlugin;

/**
 * JCLAW-205: route caffeine-jcache's HOCON config lookup away from Play's
 * {@code conf/application.conf}.
 *
 * <p>caffeine-jcache uses Typesafe Config under the hood, and Typesafe Config's
 * default {@code ConfigFactory.load()} eagerly slurps any classpath-resident
 * {@code application.conf}. Play 1.x's {@code application.conf} uses a
 * {@code key=value} properties syntax that contains bare {@code :} characters
 * (URLs, time formats, etc.) — illegal in HOCON outside quoted strings — so the
 * Typesafe parse fails, which fails caffeine-jcache's {@code CacheManager}
 * construction, which fails Hibernate's L2 region init, which fails the whole
 * SessionFactory. End result: app won't boot.
 *
 * <p>Fix: install a config supplier that returns only the merged
 * {@code reference.conf} graph (caffeine-jcache's own bundled defaults),
 * bypassing the {@code application.conf} lookup entirely. Per-region tuning
 * (TTL, size, eviction) is left at Hibernate's defaults — adequate for the
 * current candidate entities (Agent, ChannelConfig, AgentToolConfig,
 * AgentSkillConfig, Config); revisit if a future entity needs custom region
 * config by extending the supplier to overlay a HOCON file from the conf dir.
 *
 * <p>Registered at priority 350 in {@code conf/play.plugins} so {@link #onLoad}
 * fires before {@code play.db.jpa.JPAPlugin} (priority 400) builds the
 * SessionFactory.
 */
public class CaffeineJCacheConfigPlugin extends PlayPlugin {

    @Override
    public void onLoad() {
        TypesafeConfigurator.setConfigSource(ConfigFactory::defaultReference);
    }
}
