package controllers;

import channels.InboundCallback;
import channels.InboundMessage;
import channels.TelegramAccessPolicy;
import channels.TelegramBotIdentity;
import channels.TelegramCallbackDispatcher;
import channels.TelegramChannel;
import channels.TelegramForwardCoalesceBuffer;
import channels.TelegramInboundTextBuffer;
import channels.TelegramInboundTurn;
import channels.TelegramMediaGroupBuffer;
import channels.TelegramReactionNotifier;
import channels.TelegramWebhookRateLimiter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import models.Agent;
import models.TelegramBinding;
import org.telegram.telegrambots.meta.api.objects.Update;
import play.Play;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.results.Result;
import services.BindingService;
import services.EventLogger;
import services.Tx;
import utils.ApiErrorTemplates;
import utils.ApiResponses;
import utils.PlayConfig;
import utils.Strings;
import utils.WebhookUtil;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Webhook receiver for per-user Telegram bindings (JCLAW-89). The route carries
 * the {@code bindingId} so the controller can look up the matching
 * {@link TelegramBinding} without a global "current telegram config" read.
 * Authentication is the per-binding {@code webhook_secret}, verified constant-time
 * against the {@code X-Telegram-Bot-Api-Secret-Token} header registered with
 * setWebhook. JCLAW-784 (VULN-011) dropped the secret from the URL path — a path
 * segment leaks into proxy / access logs and browser history, and the header
 * already proves possession. Unknown or disabled bindings get dropped at 404/403
 * rather than being silently accepted.
 */
public class WebhookTelegramController extends Controller {

    private static final String CHANNEL_TELEGRAM = "telegram";
    private static final String CATEGORY_CHANNEL = "channel";
    private static final String THREAD_NAME_PROCESS = "webhook-telegram-process";

    /**
     * JCLAW-371: deserialize the webhook JSON body into an SDK {@link Update}
     * so the identity-aware {@code parseUpdate(Update, username, userId)} overload
     * can flag whether the bot was directly addressed. The polling path already
     * receives SDK Updates; this gives the webhook path the same shape.
     */
    private static final ObjectMapper JACKSON = new ObjectMapper();

    /** Snapshot of the fields {@link #webhook} needs off the request thread. */
    private record BindingCtx(Long bindingId, String botToken, String telegramUserId,
                              Agent agent, String webhookSecret, boolean enabled) {}

    // M1 webhook ingress hardening config keys (read via Play.configuration so
    // the documented defaults below hold until an operator overrides them).
    private static final String CFG_RATE_LIMIT_MAX = "telegram.webhook.rate-limit.max";
    private static final String CFG_RATE_LIMIT_WINDOW_SECONDS = "telegram.webhook.rate-limit.window-seconds";
    private static final String CFG_MAX_BODY_BYTES = "telegram.webhook.max-body-bytes";
    private static final String CFG_TRUSTED_PROXY = "telegram.webhook.trusted-proxy";
    private static final int DEFAULT_RATE_LIMIT_MAX = 60;
    private static final long DEFAULT_RATE_LIMIT_WINDOW_SECONDS = 60;
    private static final long DEFAULT_MAX_BODY_BYTES = 1_048_576L;

    @SuppressWarnings("java:S2259")
    @ChatHidden("inbound provider callback, authenticated by the per-binding secret token")
    public static void webhook(Long bindingId) {
        BindingCtx ctx = loadBindingCtx(bindingId);
        if (ctx == null) {
            EventLogger.warn(CATEGORY_CHANNEL, null, CHANNEL_TELEGRAM,
                    "Webhook for unknown binding id=%s".formatted(bindingId));
            notFound();
        }
        if (!ctx.enabled()) {
            EventLogger.warn(CATEGORY_CHANNEL, null, CHANNEL_TELEGRAM,
                    "Webhook for disabled binding %d".formatted(bindingId));
            ok();
        }

        // M1: pre-auth rate limit. Runs AFTER the binding is loaded (so we have
        // a bindingId to key on) but BEFORE verifySecret, so a wrong-secret
        // flood against one binding is rejected cheaply with HTTP 429 instead of
        // paying the constant-time secret compares on every request. The real
        // client IP is resolved for accurate logging (it does not gate).
        String clientIp = resolveClientIp();
        if (!TelegramWebhookRateLimiter.allow(bindingId, rateLimitMax(), rateLimitWindowSeconds())) {
            EventLogger.warn(CATEGORY_CHANNEL, null, CHANNEL_TELEGRAM,
                    "Rate-limited webhook for binding %d from %s".formatted(bindingId, clientIp));
            ApiResponses.error(429, ApiResponses.RATE_LIMITED, "Too Many Requests");
        }

        // M1: body-size limit. Check Content-Length first so an oversized POST
        // is rejected before the body is read at all; the read-length backstop
        // below guards a missing / lying Content-Length header.
        long maxBodyBytes = maxBodyBytes();
        if (contentLengthExceeds(maxBodyBytes)) {
            EventLogger.warn(CATEGORY_CHANNEL, null, CHANNEL_TELEGRAM,
                    "Oversized webhook body (Content-Length) for binding %d from %s".formatted(bindingId, clientIp));
            ApiResponses.errorWithTemplate(413, ApiResponses.PAYLOAD_TOO_LARGE, "Payload Too Large",
                    ApiErrorTemplates.payloadTooLarge(WebhookIngressGate.declaredContentLength(), maxBodyBytes,
                            CFG_MAX_BODY_BYTES));
        }

        if (!verifySecret(ctx, bindingId)) {
            unauthorized("Invalid signature");
        }

        try {
            var rawBody = WebhookUtil.readRawBody();
            // Backstop the Content-Length check against the actual read length
            // (a chunked / unset Content-Length request bypasses the early guard).
            long readBytes = rawBody.getBytes(StandardCharsets.UTF_8).length;
            if (readBytes > maxBodyBytes) {
                EventLogger.warn(CATEGORY_CHANNEL, null, CHANNEL_TELEGRAM,
                        "Oversized webhook body (read length) for binding %d from %s".formatted(bindingId, clientIp));
                ApiResponses.errorWithTemplate(413, ApiResponses.PAYLOAD_TOO_LARGE, "Payload Too Large",
                        ApiErrorTemplates.payloadTooLarge(readBytes, maxBodyBytes, CFG_MAX_BODY_BYTES));
            }
            dispatchUpdate(ctx, rawBody, bindingId);
        } catch (Result r) {
            // 413 from the read-length backstop above: rethrow so Play renders
            // it; do not swallow it as a generic parse error.
            throw r;
        } catch (Exception e) {
            EventLogger.error(CATEGORY_CHANNEL, null, CHANNEL_TELEGRAM,
                    "Webhook parse error for binding %d: %s".formatted(bindingId, e.getMessage()));
        }

        ok();
    }

    /**
     * M1: read the rate-limit ceiling ({@code telegram.webhook.rate-limit.max},
     * default 60). Unparseable / unset values fall back to the default.
     */
    private static int rateLimitMax() {
        return PlayConfig.intOr(CFG_RATE_LIMIT_MAX, DEFAULT_RATE_LIMIT_MAX);
    }

    /** M1: rate-limit window in seconds ({@code telegram.webhook.rate-limit.window-seconds}, default 60). */
    private static long rateLimitWindowSeconds() {
        return PlayConfig.intOr(CFG_RATE_LIMIT_WINDOW_SECONDS, (int) DEFAULT_RATE_LIMIT_WINDOW_SECONDS);
    }

    /** M1: maximum accepted body size in bytes ({@code telegram.webhook.max-body-bytes}, default 1 MiB). */
    private static long maxBodyBytes() {
        return PlayConfig.longOr(CFG_MAX_BODY_BYTES, DEFAULT_MAX_BODY_BYTES);
    }

    /**
     * M1: true when the request's {@code Content-Length} header is present and
     * exceeds {@code maxBodyBytes}. A missing / unparseable header returns false
     * here; the read-length backstop in {@link #webhook} catches those.
     */
    private static boolean contentLengthExceeds(long maxBodyBytes) {
        var header = Http.Request.current().headers.get("content-length");
        if (header == null || header.value() == null) return false;
        try {
            return Long.parseLong(header.value().trim()) > maxBodyBytes;
        } catch (NumberFormatException _) {
            return false;
        }
    }

    /**
     * M1: resolve the real client IP for logging. When
     * {@code telegram.webhook.trusted-proxy=false} (default) the socket peer
     * ({@code remoteAddress}) is authoritative. When {@code true} — JClaw sits
     * behind a trusted reverse proxy — prefer the left-most entry of
     * {@code X-Forwarded-For}, falling back to {@code remoteAddress}. This is a
     * best-effort logging aid; it never gates the request.
     */
    private static String resolveClientIp() {
        var req = Http.Request.current();
        if (trustedProxy()) {
            var xff = req.headers.get("x-forwarded-for");
            if (xff != null && xff.value() != null && !xff.value().isBlank()) {
                return xff.value().split(",")[0].trim();
            }
        }
        return req.remoteAddress;
    }

    private static boolean trustedProxy() {
        var raw = Play.configuration.getProperty(CFG_TRUSTED_PROXY, "false");
        return raw != null && raw.trim().equalsIgnoreCase("true");
    }

    private static BindingCtx loadBindingCtx(Long bindingId) {
        return Tx.run(() -> {
            TelegramBinding b = BindingService.findTelegramBindingById(bindingId);
            if (b == null) return null;
            if (b.agent != null) {
                var _ = b.agent.name; // touch inside tx to avoid detached-proxy access later
            }
            return new BindingCtx(b.id, b.botToken, b.telegramUserId, b.agent,
                    b.webhookSecret, b.enabled);
        });
    }

    /**
     * JCLAW-16 / JCLAW-784: Telegram's published auth mechanism is the secret_token
     * registered with setWebhook and echoed back as X-Telegram-Bot-Api-Secret-Token.
     * We authenticate SOLELY on that header, constant-time. VULN-011 removed the
     * secret from the URL path (it leaked into proxy / access logs and browser
     * history and added nothing the header didn't already prove). A null stored
     * secret, or a missing / blank header, is rejected — fail closed.
     */
    private static boolean verifySecret(BindingCtx ctx, Long bindingId) {
        var agentName = ctx.agent() != null ? ctx.agent().name : null;
        var secretHeader = Http.Request.current().headers.get("x-telegram-bot-api-secret-token");
        var headerValue = secretHeader != null ? secretHeader.value() : null;
        if (ctx.webhookSecret() == null || headerValue == null || headerValue.isBlank()
                || !MessageDigest.isEqual(
                        ctx.webhookSecret().getBytes(StandardCharsets.UTF_8),
                        headerValue.getBytes(StandardCharsets.UTF_8))) {
            EventLogger.warn(EventLogger.WEBHOOK_SIGNATURE_FAILURE, agentName, CHANNEL_TELEGRAM,
                    "Missing or invalid secret-token header for binding %d".formatted(bindingId));
            return false;
        }
        return true;
    }

    /**
     * JCLAW-109: route inline-keyboard callback queries before the
     * message parse path. parseCallback returns null for non-callback
     * updates, so this falls through cleanly to the message path
     * when the update is a regular text message.
     */
    private static void dispatchUpdate(BindingCtx ctx, String rawBody, Long bindingId)
            throws JsonProcessingException {
        Update sdkUpdate = JACKSON.readValue(rawBody, Update.class);
        var callback = TelegramChannel.parseCallback(sdkUpdate);
        if (callback != null) {
            handleCallback(ctx, callback, bindingId);
            return;
        }
        // JCLAW-375: a message_reaction update is neither a callback nor a
        // parseable message. Surface it as a gated system event (shared gate +
        // synthesis with the polling path) before the non-message drop below.
        // NOTE: Telegram only DELIVERS message_reaction to the webhook when the
        // registrar (TelegramWebhookRegistrar — outside this story's file set)
        // names it in allowed_updates; this handler is ready for that follow-up.
        var reaction = TelegramReactionNotifier.parseReaction(sdkUpdate);
        if (reaction != null) {
            TelegramReactionNotifier.handleReaction(
                    ctx.agent(), ctx.botToken(), ctx.telegramUserId(), reaction);
            return;
        }
        // JCLAW-371: resolve the bot's own identity so parseUpdate can flag an
        // @mention / text_mention / /cmd@botname / reply-to-bot addressing THIS
        // bot — the group access gate in handleInboundMessage reads
        // InboundMessage.botMentioned.
        var identity = TelegramBotIdentity.resolve(ctx.botToken());
        var message = TelegramChannel.parseUpdate(sdkUpdate, identity.username(), identity.userId());
        if (message == null) return; // non-message update (edited_message, etc.)
        // JCLAW-387 B1: detect a forward off the RAW update (the parsed
        // InboundMessage drops the forward fields) so handleInboundMessage can
        // route a forward burst through the coalesce lane.
        handleInboundMessage(ctx, message, bindingId,
                TelegramReactionNotifier.isForward(sdkUpdate));
    }

    private static void handleCallback(BindingCtx ctx, InboundCallback callback, Long bindingId) {
        if (!ctx.telegramUserId().equals(callback.fromId())) {
            EventLogger.warn(CATEGORY_CHANNEL,
                    ctx.agent() != null ? ctx.agent().name : null, CHANNEL_TELEGRAM,
                    "Rejected callback from user %s: binding %d is bound to user %s".formatted(
                            callback.fromId(), bindingId, ctx.telegramUserId()));
            return;
        }
        dispatchOffThread("webhook-telegram-callback", () ->
                TelegramCallbackDispatcher.dispatch(
                        ctx.botToken(), ctx.agent(), callback));
    }

    /**
     * Spawn an off-request virtual thread so the webhook handler can return
     * HTTP 200 immediately while the agent turn / callback runs asynchronously.
     */
    private static void dispatchOffThread(String name, Runnable r) {
        Thread.ofVirtual().name(name).start(r);
    }

    private static void handleInboundMessage(BindingCtx ctx, InboundMessage message,
                                             Long bindingId, boolean isForward) {
        // JCLAW-371 access policy: a DM is served only for the binding owner; a
        // group/supergroup is served for any member but only when the bot was
        // directly addressed (@mention / reply-to-bot etc.). See TelegramAccessPolicy.
        boolean ownerMatches = ctx.telegramUserId().equals(message.fromId());
        if (!TelegramAccessPolicy.isAllowed(ownerMatches, message.chatType(), message.botMentioned())) {
            EventLogger.warn(CATEGORY_CHANNEL,
                    ctx.agent() != null ? ctx.agent().name : null, CHANNEL_TELEGRAM,
                    "Rejected inbound from %s (id=%s) in %s chat: binding %d (owner %s, mentioned=%s)".formatted(
                            message.fromUsername() != null ? message.fromUsername() : "?",
                            message.fromId(), message.chatType(), bindingId, ctx.telegramUserId(),
                            message.botMentioned()));
            return;
        }

        EventLogger.info(CATEGORY_CHANNEL,
                ctx.agent() != null ? ctx.agent().name : null, CHANNEL_TELEGRAM,
                "Webhook received from %s: %s".formatted(
                        message.fromUsername() != null ? message.fromUsername() : message.fromId(),
                        Strings.truncate(message.text(), 50)));

        // Off-request virtual thread so the HTTP 200 returns immediately.
        // JCLAW-136: multi-photo albums ride through a reassembly buffer
        // so N photos sharing a media_group_id dispatch as ONE turn, not N.
        // Plain-text and single-attachment messages skip the buffer (null
        // media_group_id → immediate dispatch).
        //
        // M2 inbound reassembly: an eligible plain-text message (no
        // attachments, no media_group_id) routes through the inbound-text
        // buffer so a long paste auto-split by the client into consecutive
        // pieces coalesces into ONE turn. Sub-threshold pieces dispatch
        // immediately there, so normal messages keep today's zero-latency path.
        // Everything else (attachments / media groups) stays on the existing
        // media-group buffer unchanged.
        //
        // JCLAW-387 B1: a FORWARD takes priority over the text/media lanes — a
        // burst of consecutive forwards coalesces into ONE turn. Checked first
        // because a forwarded plain text would otherwise fall into the
        // text-reassembly lane and a forwarded photo into the media-group lane.
        if (isForward) {
            TelegramForwardCoalesceBuffer.add(message, merged ->
                    dispatchOffThread(THREAD_NAME_PROCESS, () -> processMessage(ctx, merged)));
        } else if (TelegramInboundTextBuffer.isEligible(message)) {
            TelegramInboundTextBuffer.add(message, merged ->
                    dispatchOffThread(THREAD_NAME_PROCESS, () -> processMessage(ctx, merged)));
        } else {
            TelegramMediaGroupBuffer.add(message, merged ->
                    dispatchOffThread(THREAD_NAME_PROCESS, () -> processMessage(ctx, merged)));
        }
    }

    private static void processMessage(BindingCtx ctx, InboundMessage message) {
        TelegramInboundTurn.run(ctx.bindingId(), ctx.botToken(), ctx.telegramUserId(),
                ctx.agent(), message);
    }
}
