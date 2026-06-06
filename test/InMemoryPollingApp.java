import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.telegram.telegrambots.longpolling.BotSession;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.meta.TelegramUrl;
import org.telegram.telegrambots.meta.api.methods.updates.GetUpdates;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Test double for {@link TelegramBotsLongPollingApplication} that tracks
 * registered tokens in an in-memory set without dialing
 * {@code api.telegram.org} (JCLAW-316). Subclasses the SDK class so it
 * fits the existing {@code APP} reference in
 * {@code TelegramPollingRunner} via the package-private
 * {@code setAppForTest} seam (exposed publicly through
 * {@code TelegramPollingRunnerTestHooks}).
 *
 * <p>All five touchpoints {@link TelegramPollingRunner} uses are
 * overridden to be pure in-memory operations:
 * <ul>
 *   <li>{@link #registerBot} — record token, no network call.</li>
 *   <li>{@link #unregisterBot} — drop token, no network call.</li>
 *   <li>{@link #start} — flip a flag.</li>
 *   <li>{@link #isRunning} — read the flag.</li>
 *   <li>{@link #close} — flip the flag back.</li>
 * </ul>
 *
 * <p>{@link #registeredTokens()} is exposed for assertions.
 */
final class InMemoryPollingApp extends TelegramBotsLongPollingApplication {

    private final Set<String> registered = ConcurrentHashMap.newKeySet();
    // JCLAW-361: capture the consumer + getUpdates generator the runner passes
    // to the 4-arg registerBot, so offset-persistence/seeding tests can drive
    // them without dialing api.telegram.org.
    private final ConcurrentHashMap<String, LongPollingUpdateConsumer> consumers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Function<Integer, GetUpdates>> generators = new ConcurrentHashMap<>();
    // JCLAW-429: the per-session executor handed to each BotSession, so a test
    // can assert the runner shuts it down on unregister (the SDK's unregisterBot
    // never does). Kept after unregister so executorFor() can still be asserted.
    private final ConcurrentHashMap<String, ScheduledExecutorService> executors = new ConcurrentHashMap<>();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final OkHttpClient HTTP = new OkHttpClient();
    private volatile boolean running = false;
    // JCLAW-429: the isRunning() value each registered session reports. Default
    // false (the fake never start()s a session, so it's genuinely not running);
    // a test sets it true to simulate an alive-but-idle poller.
    private volatile boolean sessionsRunning = false;

    @Override
    public BotSession registerBot(String botToken, LongPollingUpdateConsumer updatesConsumer) {
        registered.add(botToken);
        consumers.put(botToken, updatesConsumer);
        return null;
    }

    @Override
    public BotSession registerBot(String botToken,
                                  Supplier<TelegramUrl> telegramUrlSupplier,
                                  Function<Integer, GetUpdates> getUpdatesGenerator,
                                  LongPollingUpdateConsumer updatesConsumer) {
        registered.add(botToken);
        consumers.put(botToken, updatesConsumer);
        generators.put(botToken, getUpdatesGenerator);
        // JCLAW-429: return a real BotSession wrapping a tracked executor — never
        // started (start() is the runner-app's job, which the fake stubs), so no
        // network. Mirrors the SDK: registerBot owns the per-session executor, and
        // the runner must shut it down on unregister. BackOff supplier is unused
        // because the session never polls, so a null-returning supplier is safe.
        var exec = Executors.newSingleThreadScheduledExecutor();
        executors.put(botToken, exec);
        return new TestBotSession(sessionsRunning, MAPPER, HTTP, exec, botToken,
                telegramUrlSupplier, getUpdatesGenerator, updatesConsumer);
    }

    /**
     * JCLAW-429: a BotSession whose {@link #isRunning()} returns a fixed flag —
     * lets a test put a registered poller in the "alive but idle" state (running)
     * vs "wedged" (not running) to exercise the watchdog's liveness check. Never
     * started, so no network.
     */
    private static final class TestBotSession extends BotSession {
        private final boolean running;
        TestBotSession(boolean running, ObjectMapper mapper, OkHttpClient http,
                       ScheduledExecutorService exec, String token,
                       Supplier<TelegramUrl> url, Function<Integer, GetUpdates> gen,
                       LongPollingUpdateConsumer consumer) {
            super(mapper, http, exec, token, url, gen, () -> null, consumer);
            this.running = running;
        }
        @Override public boolean isRunning() { return running; }
    }

    @Override
    public void unregisterBot(String botToken) {
        registered.remove(botToken);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public void close() {
        running = false;
        registered.clear();
        consumers.clear();
        generators.clear();
    }

    /** Snapshot of tokens currently registered with this fake. */
    Set<String> registeredTokens() {
        return Set.copyOf(registered);
    }

    /** Consumer the runner registered for {@code botToken} (JCLAW-361), or null. */
    LongPollingUpdateConsumer consumerFor(String botToken) {
        return consumers.get(botToken);
    }

    /** getUpdates generator the runner registered for {@code botToken} (JCLAW-361), or null. */
    Function<Integer, GetUpdates> generatorFor(String botToken) {
        return generators.get(botToken);
    }

    /** JCLAW-429: the per-session executor handed to {@code botToken}'s BotSession,
     *  or null. A test asserts the runner shut it down ({@code isShutdown()}). */
    ScheduledExecutorService executorFor(String botToken) {
        return executors.get(botToken);
    }

    /** JCLAW-429: teardown cleanup — shut down any executors a test left behind
     *  (e.g. a binding registered but never unregistered through the runner). */
    void shutdownExecutors() {
        for (var e : executors.values()) e.shutdownNow();
        executors.clear();
    }

    /** JCLAW-429: control what {@link #isRunning()} the next registered session
     *  reports — true = alive-but-idle, false = wedged. Set before registering. */
    void setSessionsRunning(boolean v) {
        sessionsRunning = v;
    }
}
