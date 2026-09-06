package services.scrape;

import org.jspecify.annotations.Nullable;

/**
 * The fetch sidecar could not be started or reached (JCLAW-1087).
 *
 * <p>Its own type rather than a bare {@code RuntimeException} so the escalation
 * ladder can tell "rung 2 is unavailable on this install" — which must fall back
 * to rung 1 — apart from "the origin refused us", which must not.
 */
public class ScrapeSidecarException extends RuntimeException {

    public ScrapeSidecarException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
