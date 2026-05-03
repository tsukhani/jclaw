package controllers;

import play.db.jpa.NoTransaction;
import play.mvc.Controller;
import play.mvc.SseStream;
import play.mvc.With;
import services.NotificationBus;

import java.time.Duration;

/**
 * GET /api/events — Server-Sent Events endpoint for real-time notifications.
 * The frontend connects once on app load and receives events from the
 * NotificationBus. Connection lifetime is capped at 24 hours; heartbeats
 * fire every 30s. SSE plumbing — chunked headers, framing, heartbeat
 * scheduling, disconnect detection — lives in the play1 fork's
 * {@link SseStream} (PF-16).
 */
@With(AuthCheck.class)
public class ApiEventsController extends Controller {

    /**
     * JCLAW-199: {@code @NoTransaction} keeps Play's per-request JPA wrapper
     * from holding a HikariCP connection for the full 24 h subscription lifetime.
     * The body does no DB work — just bridges {@link NotificationBus} pushes
     * into the SSE stream — and {@link AuthCheck}'s {@code ConfigService.get}
     * already wraps its own DB hit in {@link services.Tx#run}, so the outer
     * Play tx was pure overhead.
     */
    @NoTransaction
    public static void stream() {
        SseStream sse = openSSE().heartbeat(Duration.ofSeconds(30)).timeout(Duration.ofHours(24));
        Runnable unsubscribe = NotificationBus.subscribe(sse::sendRaw);
        sse.onClose(unsubscribe::run);
        await(sse.completion());
    }
}
