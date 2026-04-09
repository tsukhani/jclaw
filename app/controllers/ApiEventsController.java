package controllers;

import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.With;
import services.NotificationBus;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * GET /api/events — Server-Sent Events endpoint for real-time notifications.
 * The frontend connects once on app load and receives events from the NotificationBus.
 */
@With(AuthCheck.class)
public class ApiEventsController extends Controller {

    public static void stream() {
        var res = Http.Response.current();
        res.contentType = "text/event-stream";
        res.setHeader("Cache-Control", "no-cache");
        res.setHeader("Connection", "keep-alive");
        res.setHeader("X-Accel-Buffering", "no");

        var latch = new CountDownLatch(1);
        var disconnected = new java.util.concurrent.atomic.AtomicBoolean(false);

        // Subscribe to the notification bus
        var unsubscribe = NotificationBus.subscribe(ssePayload -> {
            if (disconnected.get()) return;
            try {
                res.writeChunk(ssePayload.getBytes(StandardCharsets.UTF_8));
            } catch (Exception _) {
                disconnected.set(true);
                latch.countDown();
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
                latch.countDown();
            }
        }, 30, 30, TimeUnit.SECONDS);

        try {
            // Hold the connection open until client disconnects or server shuts down
            latch.await(24, TimeUnit.HOURS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        } finally {
            unsubscribe.run();
            heartbeat.shutdownNow();
        }
    }
}
