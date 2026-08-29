package utils;

import java.lang.management.ManagementFactory;

/**
 * Point-in-time JVM runtime state for the Settings → Maintenance panel (JCLAW-1057).
 *
 * <p>Memory is reported as three separate figures because collapsing them is how a
 * healthy JVM gets read as a sick one: the heap is what the JVM allocates inside its
 * own arena, non-heap is metaspace / code cache / direct buffers (where the OkHttp and
 * Okio buffers live, invisible to every heap figure), and {@link #rssBytes} is what the
 * OS actually charges the process.
 *
 * @param heapUsed            bytes live in the heap
 * @param heapCommitted       bytes the JVM currently holds for the heap
 * @param heapMax             heap ceiling, or -1 when undefined
 * @param nonHeapUsed         metaspace + code cache + direct buffers in use
 * @param nonHeapCommitted    bytes currently held for non-heap
 * @param rssBytes            resident set size, or null where unreadable — see
 *                            {@link ProcessRss}; never a substituted heap figure
 * @param gcCount             collections across every collector since start, or -1
 * @param gcTimeMs            accumulated collection time, or -1
 * @param platformThreads     live <em>platform</em> threads — see note below
 * @param peakPlatformThreads platform-thread high-water mark since start
 * @param uptimeMs            time since JVM start
 * @param processCpuLoad      0..1 share of the machine this process is using, or null
 *                            when the JVM declines to report it
 * @param availableProcessors cores visible to this JVM
 * @param machineMemoryBytes  total physical RAM, or null when unavailable — the bound
 *                            that makes {@code rssBytes} drawable as a proportion
 * @param llmCallsRunning     outbound LLM calls executing now
 * @param llmCallsQueued      outbound LLM calls waiting on the dispatcher — non-zero
 *                            means the cap is throttling, not the provider
 * @param llmCallsMax         the live dispatcher ceiling those two are measured against
 */
public record JvmStats(long heapUsed, long heapCommitted, long heapMax,
                       long nonHeapUsed, long nonHeapCommitted,
                       Long rssBytes,
                       long gcCount, long gcTimeMs,
                       int platformThreads, int peakPlatformThreads,
                       long uptimeMs,
                       Double processCpuLoad,
                       int availableProcessors,
                       Long machineMemoryBytes,
                       int llmCallsRunning, int llmCallsQueued, int llmCallsMax) {

    /**
     * Read every figure from the platform MXBeans.
     *
     * <p>The thread counts are named "platform" deliberately: {@code ThreadMXBean} does
     * not see virtual threads, and this fork runs virtual-thread-only for request and
     * tool work. A flat, low count here is therefore expected and is not evidence that
     * nothing is running — labeling it "threads" would invite exactly that misreading.
     *
     * <p>Absent values stay absent. GC beans may report -1, and {@code getProcessCpuLoad}
     * returns a negative sentinel when unavailable (commonly on its first call, which is
     * why the panel polls rather than taking a single sample) — a negative CPU share is
     * mapped to null instead of being rendered as a real number.
     */
    public static JvmStats snapshot() {
        var memory = ManagementFactory.getMemoryMXBean();
        var heap = memory.getHeapMemoryUsage();
        var nonHeap = memory.getNonHeapMemoryUsage();
        var threads = ManagementFactory.getThreadMXBean();

        long gcCount = 0;
        long gcTimeMs = 0;
        for (var gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            var count = gc.getCollectionCount();
            var time = gc.getCollectionTime();
            if (count > 0) gcCount += count;
            if (time > 0) gcTimeMs += time;
        }

        Double cpu = null;
        Long machineMemory = null;
        if (ManagementFactory.getOperatingSystemMXBean()
                instanceof com.sun.management.OperatingSystemMXBean sun) {
            var load = sun.getProcessCpuLoad();
            if (load >= 0) cpu = load;
            // Only a bounded value can be drawn as a proportion, and "352 MB" has no
            // bound until you know the machine it is resident on.
            var total = sun.getTotalMemorySize();
            if (total > 0) machineMemory = total;
        }

        return new JvmStats(
                heap.getUsed(), heap.getCommitted(), heap.getMax(),
                nonHeap.getUsed(), nonHeap.getCommitted(),
                ProcessRss.bytes(),
                gcCount, gcTimeMs,
                threads.getThreadCount(), threads.getPeakThreadCount(),
                ManagementFactory.getRuntimeMXBean().getUptime(),
                cpu,
                Runtime.getRuntime().availableProcessors(),
                machineMemory,
                // Process state rather than JVM state strictly speaking, but it is what
                // the caps rendered directly beneath this panel are tuned against, and a
                // second endpoint polled on the same 5s cadence would buy nothing.
                HttpFactories.llmDispatcherRunningCalls(),
                HttpFactories.llmDispatcherQueuedCalls(),
                HttpFactories.llmDispatcherMaxRequests());
    }
}
