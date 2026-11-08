package controllers;

import play.db.jpa.NoTransaction;
import play.mvc.Http;
import play.mvc.WebSocketController;
import services.ChromeBridge;
import services.ChromeBridgeSession;
import utils.RetryScheduler;

import static controllers.AgentAccess.Level.OPERATOR_ONLY;

/**
 * The extension's socket. It authenticates itself with a pairing token in its first frame
 * ({@link ChromeBridgeSession}), not the operator's session cookie, so it carries no session and
 * reaches nothing but the bridge.
 */
public class ChromeBridgeSocketController extends WebSocketController {

    private static final long HELLO_DEADLINE_MS = 5_000;
    private static final String EXTENSION_ORIGIN = "chrome-extension://";

    /** No transaction: the socket is long-lived, and pairing verification opens its own. */
    @NoTransaction
    @AgentAccess(OPERATOR_ONLY)
    public static void socket() {
        if (!fromExtension()) {
            disconnect();
            return;
        }
        var out = outbound;
        var lock = new Object();
        var transport = new ChromeBridge.Transport() {
            @Override
            public void send(String frame) {
                synchronized (lock) {
                    if (out.isOpen()) out.send(frame);
                }
            }

            @Override
            public void close() {
                synchronized (lock) {
                    if (out.isOpen()) out.close();
                }
            }
        };
        var session = new ChromeBridgeSession(transport);
        RetryScheduler.schedule(() -> {
            session.expireIfUnauthenticated();
            return null;
        }, HELLO_DEADLINE_MS);
        try {
            for (Http.WebSocketEvent event : inbound) {
                switch (event) {
                    case Http.TextFrame(var text) -> session.onText(text);
                    case Http.WebSocketClose _ -> {
                        return;
                    }
                    case Http.BinaryFrame _ -> { /* the protocol is text only */ }
                }
            }
        } finally {
            session.closed();
        }
    }

    /** A page cannot forge Origin, so this keeps a website from opening the socket. */
    private static boolean fromExtension() {
        var origin = request.headers.get("origin");
        return origin != null && origin.value().startsWith(EXTENSION_ORIGIN);
    }
}
