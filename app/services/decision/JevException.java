package services.decision;

/** A JEV call that returned no usable answer. The message is shown to an agent or logged, so it never carries the key. */
public class JevException extends RuntimeException {

    public JevException(String message) {
        super(message);
    }

    /** JEV's circuit breaker, open or isolated by the operator, turned the call away before anything was sent. */
    public static final class BreakerOpen extends JevException {
        BreakerOpen(String message) {
            super(message);
        }
    }

    /** A failure the breaker counts: a transport error, a timeout, HTTP 5xx or 429. */
    public static final class Outage extends JevException {
        private final boolean timedOut;

        Outage(String message, boolean timedOut) {
            super(message);
            this.timedOut = timedOut;
        }

        /** Whether the last attempt ran out of time, which on Ollama can mean a model still loading. */
        public boolean timedOut() {
            return timedOut;
        }
    }
}
