package utils;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Factory methods for virtual-thread executors used across the application.
 */
public final class VirtualThreads {

    private VirtualThreads() {}

    /** Create a single-thread scheduled executor backed by a virtual thread. */
    public static ScheduledExecutorService newSingleThreadScheduledExecutor() {
        return Executors.newSingleThreadScheduledExecutor(r -> Thread.ofVirtual().unstarted(r));
    }
}
