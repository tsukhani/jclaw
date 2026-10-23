package utils;

/** Whether the JVM has begun shutting down, which nothing in Play says until it stops the application. */
public final class JvmShutdown {

    // Never started: registered and removed again only to ask the JVM whether its hooks are running.
    private static final Thread PROBE = Thread.ofPlatform().unstarted(() -> {});

    private JvmShutdown() {}

    /**
     * True from the moment the JVM starts its shutdown hooks, before any of them runs: H2's closes the
     * database then, while Play's is still draining HTTP (JF-23). Read from the
     * {@link IllegalStateException} that {@link Runtime#addShutdownHook} documents for that state, so
     * nothing is registered for good and a dev reload leaks no hook.
     */
    public static synchronized boolean inProgress() {
        try {
            Runtime.getRuntime().addShutdownHook(PROBE);
            Runtime.getRuntime().removeShutdownHook(PROBE);
            return false;
        } catch (IllegalStateException _) {
            return true;
        }
    }
}
