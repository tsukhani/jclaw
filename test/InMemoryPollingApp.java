import org.telegram.telegrambots.longpolling.BotSession;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.meta.TelegramUrl;
import org.telegram.telegrambots.meta.api.methods.updates.GetUpdates;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
    private volatile boolean running = false;

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
        return null;
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
}
