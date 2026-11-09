import channels.TelegramMarkdownFormatter.TableMode;
import org.hibernate.cache.jcache.internal.JCacheRegionFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.UnitTest;
import services.Tx;

import javax.cache.CacheManager;
import javax.cache.configuration.MutableConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A copy out of Hibernate's second-level cache holds the running application's classes, whatever
 * the context class loader of the thread that first copies a class.
 *
 * <p>Hibernate creates each region with a plain {@link MutableConfiguration}, which stores by value,
 * so caffeine-jcache copies every entry by Java serialization. Its default copier resolves classes
 * through one JVM-wide loader that asks the thread's context loader first, and the JVM keeps the
 * first answer that loader gives for a name. In the test server the system loader also sees
 * {@code build/classes/java/main}, so a first copy on a thread with that context loader pinned the
 * system loader's {@code ChannelTransport}, and every later cached {@code TelegramBinding} read
 * failed with {@code ChannelTransport cannot be cast to ChannelTransport}. A Play reload pins the
 * old loader's classes the same way.
 *
 * <p>{@link TableMode} stands in for the entity's enum: no cached entity holds it, so this test is
 * the first to copy it, and the cache is created the way Hibernate creates its regions.
 */
class SecondLevelCacheContextLoaderTest extends UnitTest {

    private @Nullable String cacheName;

    @AfterEach
    void dropCache() throws Exception {
        var name = cacheName;
        if (name != null) cacheManager().destroyCache(name);
    }

    @Test
    void aFirstCopyOnAThreadWhoseContextLoaderIsTheSystemLoaderStillHoldsTheAppsClass() throws Exception {
        var system = ClassLoader.getSystemClassLoader();
        assertNotSame(TableMode.class, Class.forName(TableMode.class.getName(), false, system),
                "the system loader must hold its own copy of the app's classes, or this test proves nothing");

        var name = "l2-context-loader-" + System.nanoTime();
        cacheName = name;
        var cache = cacheManager().createCache(name, new MutableConfiguration<String, Object>());

        Object copied = onThread(system, () -> {
            cache.put("modes", new ArrayList<>(List.of(TableMode.CODE)));
            return ((List<?>) cache.get("modes")).get(0);
        });

        assertSame(TableMode.CODE, copied, "the copy must hold the app's TableMode, not the system loader's");
    }

    private static CacheManager cacheManager() throws Exception {
        return onThread(null, () -> Tx.run(() -> ((JCacheRegionFactory) JPA.em().getEntityManagerFactory()
                .unwrap(SessionFactoryImplementor.class).getCache().getRegionFactory()).getCacheManager()));
    }

    /** Runs {@code body} on a new platform thread, with {@code contextLoader} as its context loader when one is given. */
    private static <T> T onThread(@Nullable ClassLoader contextLoader, Callable<T> body) throws Exception {
        var result = new AtomicReference<T>();
        var failure = new AtomicReference<Throwable>();
        var thread = Thread.ofPlatform().unstarted(() -> {
            try {
                result.set(body.call());
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        if (contextLoader != null) thread.setContextClassLoader(contextLoader);
        thread.start();
        thread.join();
        if (failure.get() != null) throw new AssertionError("the call failed: " + failure.get(), failure.get());
        return result.get();
    }
}
