package tools.jev;

/** Ends a Jev run. The message is shown to the agent after {@code "Error: "}, so it never carries the key. */
public final class JevException extends RuntimeException {

    public JevException(String message) {
        super(message);
    }
}
