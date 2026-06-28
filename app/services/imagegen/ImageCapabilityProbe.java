package services.imagegen;

import services.SidecarCapabilityProbe;

/**
 * Host-capability probe for local image generation. A thin domain facade over the shared
 * {@link SidecarCapabilityProbe} (the same split as {@code LocalSidecarDaemon} ↔ the per-domain sidecar
 * managers): it runs the image sidecar's one-shot {@code uv run serve.py --probe} to detect the GPU + free
 * VRAM and decide whether THIS machine can run Flux locally, which gates the Settings "Self-Hosted" radio
 * (disabled when no capable hardware).
 */
public final class ImageCapabilityProbe {

    private static final SidecarCapabilityProbe PROBE =
            new SidecarCapabilityProbe("sidecar/image", "imagegen-capability-probe", "imagegen");

    private ImageCapabilityProbe() {}

    /** Snapshot for the Settings UI (UNAVAILABLE when {@code uv} is absent). */
    public static SidecarCapabilityProbe.Snapshot snapshot() {
        return PROBE.snapshot();
    }

    /** Kick off a background probe if one isn't already running (idempotent). */
    public static void probe() {
        PROBE.probe();
    }
}
