package services;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe cached cell for a one-shot probe result. Holds an "unrun"
 * sentinel until the first probe lands, exposes the current value, and offers a
 * test seam to force a result without invoking the underlying probe.
 *
 * <p>Extracted from the per-engine probe classes ({@link OllamaLocalProbe},
 * {@link LmStudioProbe}, {@link services.transcription.FfmpegProbe},
 * {@link services.imagegen.FluxSidecarProbe}), which all wrapped their own typed
 * result record in this identical holder scaffolding. Each probe keeps its own
 * public {@code ProbeResult} record and static facade; only the caching
 * mechanism is shared here.
 *
 * @param <T> the probe's result type
 */
public final class ProbeCache<T> {

    private final T unrun;
    private final AtomicReference<T> ref;

    public ProbeCache(T unrun) {
        this.unrun = unrun;
        this.ref = new AtomicReference<>(unrun);
    }

    /** Current cached result; the unrun sentinel until {@link #set} has run. */
    public T get() {
        return ref.get();
    }

    /** True when {@code value} is still the unrun sentinel (identity compare). */
    public boolean isUnrun(T value) {
        return value == unrun;
    }

    /** Cache and return {@code value}. */
    public T set(T value) {
        ref.set(value);
        return value;
    }

    /** Test seam: force a cached result, or reset to the unrun sentinel when {@code null}. */
    public void setForTest(T forced) {
        ref.set(forced == null ? unrun : forced);
    }
}
