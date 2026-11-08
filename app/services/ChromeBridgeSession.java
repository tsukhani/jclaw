package services;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;

/**
 * The protocol on one extension socket: nothing is accepted until a {@code hello} frame carries the
 * pairing token, and only then does the socket become the live bridge. The token travels in a frame,
 * not the URL, so it never lands in an access log.
 */
public final class ChromeBridgeSession {

    private static final String CATEGORY = "chrome-bridge";
    private static final String TYPE = "type";

    private final ChromeBridge.Transport transport;
    private volatile boolean authenticated;

    public ChromeBridgeSession(ChromeBridge.Transport transport) {
        this.transport = transport;
    }

    public boolean authenticated() {
        return authenticated;
    }

    public void onText(String text) {
        JsonObject msg;
        try {
            msg = JsonParser.parseString(text).getAsJsonObject();
        } catch (RuntimeException _) {
            transport.close();
            return;
        }
        var type = stringOf(msg, TYPE);
        if (!authenticated) {
            hello(msg, type);
            return;
        }
        if ("ping".equals(type)) {
            transport.send("{\"type\":\"pong\"}");
        } else {
            ChromeBridge.onResult(transport, msg);
        }
    }

    /** Closes a socket that never said hello; scheduled by the controller. */
    public void expireIfUnauthenticated() {
        if (!authenticated) transport.close();
    }

    public void closed() {
        ChromeBridge.detach(transport);
    }

    private void hello(JsonObject msg, @Nullable String type) {
        var token = stringOf(msg, "token");
        if (!"hello".equals(type) || token == null || !ChromeBridgePairing.verify(token)) {
            EventLogger.warn(CATEGORY, "Chrome bridge connection refused: no valid pairing token");
            transport.send("{\"type\":\"error\",\"message\":\"pairing token refused\"}");
            transport.close();
            return;
        }
        authenticated = true;
        ChromeBridge.attach(transport);
        transport.send("{\"type\":\"ready\"}");
    }

    private static @Nullable String stringOf(JsonObject msg, String key) {
        var v = msg.get(key);
        return v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() ? v.getAsString() : null;
    }
}
