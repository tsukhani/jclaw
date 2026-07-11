package channels;

import okhttp3.OkHttpClient;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.TelegramUrl;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.concurrent.TimeUnit;

/**
 * Builder for the two hand-tuned Bot-API {@link TelegramClient}s a
 * {@link TelegramSender} needs (JCLAW-702, extracted from that class's
 * constructor). The timeouts here are deliberately NOT one of the
 * {@code HttpFactories} tiers: the text path fails fast (2 s connect / 3 s read
 * / 2 s write) into the streaming-sink retry tick, while the upload path is
 * tolerant of multi-second file bodies (5 s / 60 s / 60 s) and disables OkHttp's
 * unbounded connection-retry — neither profile matches the general 10/30/30 tier,
 * so a dedicated builder is the right home rather than forcing a bad fit.
 *
 * <p>Each call constructs a fresh {@link OkHttpClient} (its own default pool +
 * dispatcher) wrapped for the given bot token, preserving the per-instance,
 * per-token client ownership {@code TelegramSender} had before the split.
 */
final class TelegramBotApiHttpClients {

    private TelegramBotApiHttpClients() { }

    /**
     * Bot-API HTTP timeouts (JCLAW-100). OkHttp defaults to 10 s on every
     * timeout, which means a slow or hung Telegram edge can burn ~10 s of
     * critical path (inside TelegramStreamingSink.seal → final edit) before
     * the existing scheduled-retry logic engages. The values below fail
     * fast — 2 s connect, 3 s read, 2 s write — so a transient stall is
     * caught and absorbed by the sink's retry tick rather than stretching
     * the user-visible turn.
     */
    private static final int BOT_API_CONNECT_TIMEOUT_SEC = 2;
    private static final int BOT_API_READ_TIMEOUT_SEC = 3;
    private static final int BOT_API_WRITE_TIMEOUT_SEC = 2;

    /**
     * Timeouts for {@code sendPhoto} / {@code sendDocument} (JCLAW-122). The
     * 2 s write timeout the text paths use is far too tight for a multi-MB
     * upload — a 1–2 MB screenshot on a typical home connection needs
     * several seconds of write bandwidth, plus Telegram server processing.
     * Before this split, every photo upload silently timed out into a
     * {@code TelegramApiException("Unable to execute sendphoto method")}
     * and the planner dropped the file segment. Generous read and write
     * accommodate the largest files the planner will deliver (10 MB photo
     * limit, 50 MB document limit in practice).
     */
    private static final int BOT_API_UPLOAD_CONNECT_TIMEOUT_SEC = 5;
    private static final int BOT_API_UPLOAD_READ_TIMEOUT_SEC = 60;
    private static final int BOT_API_UPLOAD_WRITE_TIMEOUT_SEC = 60;

    /**
     * Fast client for text paths: sendMessage, editMessageText, typing,
     * callback answers, etc. Tight timeouts fail fast into the
     * streaming-sink retry tick (JCLAW-98).
     */
    static TelegramClient textClient(String botToken, TelegramUrl urlOverride) {
        var http = new OkHttpClient.Builder()
                .connectTimeout(BOT_API_CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
                .readTimeout(BOT_API_READ_TIMEOUT_SEC, TimeUnit.SECONDS)
                .writeTimeout(BOT_API_WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
                .build();
        return wrap(http, botToken, urlOverride);
    }

    /**
     * Upload client for sendPhoto / sendDocument (JCLAW-122). Must be
     * tolerant of multi-second upload bodies and Telegram's slower
     * file-processing path.
     *
     * <p>JCLAW-126: retryOnConnectionFailure set to false. OkHttp's default
     * of true silently retries on any transient connection issue —
     * including a server-side RST mid-upload — with no upper bound on
     * total attempts until the write timeout expires. Combined with our
     * 60 s write timeout, one transient reset can compound into 60-120 s
     * of invisible wait. Disabling auto-retry makes a failure visible
     * (the SDK throws, we log a warn, the planner surfaces it) instead
     * of hiding in socket-level replay. The streaming-sink and
     * outbound-planner retry at a higher level where we control the
     * policy.
     */
    static TelegramClient uploadClient(String botToken, TelegramUrl urlOverride) {
        var http = new OkHttpClient.Builder()
                .connectTimeout(BOT_API_UPLOAD_CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
                .readTimeout(BOT_API_UPLOAD_READ_TIMEOUT_SEC, TimeUnit.SECONDS)
                .writeTimeout(BOT_API_UPLOAD_WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build();
        return wrap(http, botToken, urlOverride);
    }

    /**
     * Wrap a tuned {@link OkHttpClient} into an {@link OkHttpTelegramClient}
     * bound to {@code botToken}. A non-null {@code urlOverride} redirects the
     * SDK at a mock server (JCLAW-96); null uses the SDK's default Bot-API URL.
     */
    private static TelegramClient wrap(OkHttpClient http, String botToken, TelegramUrl urlOverride) {
        return urlOverride != null
                ? new OkHttpTelegramClient(http, botToken, urlOverride)
                : new OkHttpTelegramClient(http, botToken);
    }
}
