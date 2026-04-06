package services;

import play.db.jpa.JPA;
import play.libs.F;

/**
 * Transaction helper for running JPA operations from any thread context.
 * On Play request threads, the JPA context already exists — runs directly.
 * On virtual threads (no JPA context), wraps in JPA.withTransaction.
 */
public class Tx {

    /**
     * Run a block that returns a value, ensuring a JPA transaction is active.
     */
    public static <T> T run(F.Function0<T> block) {
        if (JPA.isInitialized()) {
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
