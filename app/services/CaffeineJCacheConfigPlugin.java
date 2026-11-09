package services;

import com.github.benmanes.caffeine.jcache.configuration.TypesafeConfigurator;
import com.github.benmanes.caffeine.jcache.copy.JavaSerializationCopier;
import com.typesafe.config.ConfigFactory;
import play.Play;
import play.PlayPlugin;

import java.util.Map;

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
 * cached entities; revisit if a future entity needs custom region
 * config by extending the supplier to overlay a HOCON file from the conf dir.
 *
 * <p>The one overlay is the copy strategy, {@link AppClassLoaderCopier}: Hibernate creates
 * every region with a plain {@code MutableConfiguration}, which stores by value, so each
 * cache read and write is a Java-serialization copy.
 *
 * <p>Registered at priority 350 in {@code conf/play.plugins} so {@link #onLoad}
 * fires before {@code play.db.jpa.JPAPlugin} (priority 400) builds the
 * SessionFactory.
 */
public class CaffeineJCacheConfigPlugin extends PlayPlugin {

    private static final String COPY_STRATEGY = "caffeine.jcache.default.store-by-value.strategy";

    @Override
    public void onLoad() {
        TypesafeConfigurator.setConfigSource(() -> ConfigFactory
                .parseMap(Map.of(COPY_STRATEGY, AppClassLoaderCopier.class.getName()))
                .withFallback(ConfigFactory.defaultReference()));
    }

    /**
     * Java-serialization copies whose classes come from the running application. The default
     * copier resolves them through one JVM-wide loader that tries the thread's context loader
     * first, and the JVM keeps the first class that loader returns for a name: a first copy on a
     * thread whose context loader is the system loader, or a Play reload, pinned a second
     * {@code ChannelTransport} and every later cached read failed with
     * {@code ChannelTransport cannot be cast to ChannelTransport}.
     */
    public static final class AppClassLoaderCopier extends JavaSerializationCopier {
        @Override
        protected Object deserialize(byte[] data, ClassLoader classLoader) {
            return super.deserialize(data, Play.classloader);
        }
    }
}
