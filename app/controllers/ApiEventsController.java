package controllers;

import play.libs.F;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.With;
import services.NotificationBus;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * GET /api/events — Server-Sent Events endpoint for real-time notifications.
 * The frontend connects once on app load and receives events from the NotificationBus.
 *
 * Uses Play's async continuation (await) to avoid blocking the thread pool.
 */
@With(AuthCheck.class)
public class ApiEventsController extends Controller {

    public static void stream() {
        var res = Http.Response.current();
        res.contentType = "text/event-stream";
        res.setHeader("Cache-Control", "no-cache");
        res.setHeader("Connection", "keep-alive");
        res.setHeader("X-Accel-Buffering", "no");

        var disconnected = new java.util.concurrent.atomic.AtomicBoolean(false);

        // Subscribe to the notification bus
        var unsubscribe = NotificationBus.subscribe(ssePayload -> {
            if (disconnected.get()) return;
            try {
                res.writeChunk(ssePayload.getBytes(StandardCharsets.UTF_8));
            } catch (Exception _) {
                disconnected.set(true);
            }
        });

        // Send heartbeat every 30 seconds to keep the connection alive
        var heartbeat = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                r -> Thread.ofVirtual().unstarted(r));
        heartbeat.scheduleAtFixedRate(() -> {
            if (disconnected.get()) return;
            try {
                res.writeChunk(": heartbeat\n\n".getBytes(StandardCharsets.UTF_8));
            } catch (Exception _) {
                disconnected.set(true);
            }
        }, 30, 30, TimeUnit.SECONDS);

        // Use Play's async continuation — releases the thread back to the pool
        // and resumes when the promise completes (client disconnect or timeout)
        var promise = new F.Promise<Void>();
        heartbeat.schedule((Runnable) () -> promise.invoke(null), 24, TimeUnit.HOURS);

        await(promise);

        unsubscribe.run();
        heartbeat.shutdownNow();
    }
}
