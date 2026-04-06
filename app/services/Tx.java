package services;

import play.db.jpa.JPA;
import play.libs.F;

/**
 * Transaction helper for running JPA operations from any thread context.
 * If already inside a JPA transaction (request thread, @OnApplicationStart job),
 * runs the block directly to avoid orphaning the existing EntityManager.
 * Otherwise delegates to JPA.withTransaction to create a new one.
 */
public class Tx {

    /**
     * Run a block that returns a value, ensuring a JPA transaction is active.
     */
    public static <T> T run(F.Function0<T> block) {
        if (JPA.isInsideTransaction()) {
            try {
                return block.apply();
            } catch (Throwable t) {
                if (t instanceof RuntimeException re) throw re;
                throw new RuntimeException(t);
            }
        }
        try {
            return JPA.withTransaction("default", false, block);
        } catch (Throwable t) {
            if (t instanceof RuntimeException re) throw re;
            throw new RuntimeException(t);
        }
    }

    /**
     * Run a void block, ensuring a JPA transaction is active.
     */
    public static void run(Runnable block) {
        run(() -> {
            block.run();
            return null;
        });
    }
}
