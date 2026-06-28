package services.videogen;

import services.SidecarCapabilityProbe;

/**
 * Adaptive host-capability probe for local video generation (SV-2 / JCLAW-232/233). A thin domain facade
 * over the shared {@link SidecarCapabilityProbe} (the same split as {@code LocalSidecarDaemon} ↔ the
 * per-domain sidecar managers): it runs the video sidecar's one-shot {@code uv run serve.py --probe} to
 * detect the GPU + free VRAM and tier every engine, which drives the Settings dropdown so the operator
 * only sees what THIS host can actually run (and WAN is greyed out off NVIDIA).
 */
public final class VideoCapabilityProbe {

    private static final SidecarCapabilityProbe PROBE =
            new SidecarCapabilityProbe("sidecar/video", "videogen-capability-probe", "videogen");

    private VideoCapabilityProbe() {}

    /** Snapshot for the Settings UI (UNAVAILABLE when {@code uv} is absent). */
    public static SidecarCapabilityProbe.Snapshot snapshot() {
        return PROBE.snapshot();
    }

    /** Kick off a background probe if one isn't already running (idempotent). */
    public static void probe() {
        PROBE.probe();
    }
}
