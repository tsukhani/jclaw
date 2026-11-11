import channels.SlackApprovalService;
import channels.TelegramApprovalService;
import channels.TelegramChannel;
import com.google.gson.JsonParser;
import com.slack.api.model.block.ActionsBlock;
import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.SectionBlock;
import com.slack.api.model.block.composition.MarkdownTextObject;
import com.slack.api.model.block.element.ButtonElement;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.TelegramUrl;
import play.test.UnitTest;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * JF-294: the parts of two approval prompts never mix in one chat. A prompt's messages go out under a lock for its
 * chat, from the first part to the one with the buttons, so the buttons always follow that prompt's own arguments. A
 * one-message prompt waits its turn too, another chat is not kept waiting, and the chat is free again after a prompt
 * that failed, was over the limit or threw.
 *
 * <p>Each case asks the approval service itself from real threads, against a stand-in for the channel that records
 * each message as it arrives and can hold one open.
 */
class ApprovalPromptOrderTest extends UnitTest {

    private static final String BOT_TOKEN = "order-bot-token";
    private static final String SLACK_TOKEN = "xoxb-order";
    private static final String SLACK_OWNER = "U0ORDER";
    /** Two Telegram chats and two Slack channels whose lock keys fall on different stripes of the 64 (46 and 47, 2 and 33). */
    private static final String CHAT_A = "9001";
    private static final String CHAT_B = "9002";
    private static final String CHANNEL_A = "C0ORDERA";
    private static final String CHANNEL_B = "C0ORDERB";

    private static final String EXPIRED = "EXPIRED";
    private static final CountDownLatch AT_ONCE = new CountDownLatch(0);
    private static final Pattern TELEGRAM_CALLBACK_ID = Pattern.compile("a:[osad]:([0-9a-f]+)");
    private static final Pattern SLACK_ACTION_ID = Pattern.compile("sa:[osad]:([0-9a-f]+)");
    private static final String TELEGRAM_TOOK_IT =
            "{\"ok\":true,\"result\":{\"message_id\":1,\"chat\":{\"id\":1,\"type\":\"private\"},\"date\":1,\"text\":\"mock\"}}";
    private static final String TELEGRAM_REFUSED_IT =
            "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: chat not found\"}";

    // ── Two prompts asked for at once ──────────────────────────────────

    @Test
    void twoTelegramPromptsAskedForAtOnceInOneChatNeverMixTheirParts() throws Exception {
        try (var telegram = new BotApi()) {
            neverMixed(telegram, CHAT_A);
        }
    }

    @Test
    void twoSlackPromptsAskedForAtOnceInOneChannelNeverMixTheirParts() throws Exception {
        try (var slack = new Slack()) {
            neverMixed(slack, CHANNEL_A);
        }
    }

    private static void neverMixed(Channel channel, String chat) throws Exception {
        var a = List.of("A part 1 of 3", "A part 2 of 3", "A part 3 of 3");
        var b = List.of("B part 1 of 3", "B part 2 of 3", "B part 3 of 3");
        var first = new AtomicBoolean();
        channel.held = _ -> first.compareAndSet(false, true);
        var go = new CountDownLatch(1);
        var askA = new Asker(go, () -> channel.ask(chat, a));
        var askB = new Asker(go, () -> channel.ask(chat, b));

        go.countDown();
        assertTrue(channel.holding.await(5, TimeUnit.SECONDS), "a first part arrived and is held open");
        boolean aWon = channel.arrived.getFirst().equals(chat + "|A part 1 of 3");
        // Held open until the other prompt has asked for the chat: it waits there, or with no lock has gone ahead.
        (aWon ? askB : askA).awaitWaitingOrDone();
        channel.letGo.countDown();
        askA.join();
        askB.join();

        var expected = new ArrayList<String>();
        for (var text : aWon ? a : b) expected.add(chat + "|" + text);
        for (var text : aWon ? b : a) expected.add(chat + "|" + text);
        assertEquals(expected, channel.arrived);
        assertEquals(2, channel.approvalIds.size(), "each prompt's last part carried its buttons");
    }

    // ── A one-message prompt waits its turn too ────────────────────────

    @Test
    void aOneMessageTelegramPromptDoesNotLandBetweenAnotherPromptsParts() throws Exception {
        try (var telegram = new BotApi()) {
            aOneMessagePromptWaits(telegram, CHAT_A);
        }
    }

    @Test
    void aOneMessageSlackPromptDoesNotLandBetweenAnotherPromptsParts() throws Exception {
        try (var slack = new Slack()) {
            aOneMessagePromptWaits(slack, CHANNEL_A);
        }
    }

    private static void aOneMessagePromptWaits(Channel channel, String chat) throws Exception {
        channel.held = message -> message.equals(chat + "|L part 1 of 3");
        var askLong = new Asker(AT_ONCE, () -> channel.ask(chat, List.of("L part 1 of 3", "L part 2 of 3", "L part 3 of 3")));
        assertTrue(channel.holding.await(5, TimeUnit.SECONDS), "the long prompt's first part arrived and is held open");

        var askShort = new Asker(AT_ONCE, () -> channel.ask(chat, List.of("S only")));
        askShort.awaitWaitingOrDone();
        channel.letGo.countDown();
        askLong.join();
        askShort.join();

        assertEquals(List.of(chat + "|L part 1 of 3", chat + "|L part 2 of 3", chat + "|L part 3 of 3", chat + "|S only"),
                channel.arrived);
    }

    // ── Another chat is not kept waiting ───────────────────────────────

    @Test
    void aPromptToAnotherTelegramChatGoesOutWhileOneChatsSendIsHeldOpen() throws Exception {
        try (var telegram = new BotApi()) {
            anotherChatDoesNotWait(telegram, CHAT_A, CHAT_B);
        }
    }

    @Test
    void aPromptToAnotherSlackChannelGoesOutWhileOneChannelsSendIsHeldOpen() throws Exception {
        try (var slack = new Slack()) {
            anotherChatDoesNotWait(slack, CHANNEL_A, CHANNEL_B);
        }
    }

    private static void anotherChatDoesNotWait(Channel channel, String busy, String other) throws Exception {
        channel.held = message -> message.equals(busy + "|A part 1 of 2");
        var askBusy = new Asker(AT_ONCE, () -> channel.ask(busy, List.of("A part 1 of 2", "A part 2 of 2")));
        assertTrue(channel.holding.await(5, TimeUnit.SECONDS), "the first chat's part arrived and is held open");

        var askOther = new Asker(AT_ONCE, () -> channel.ask(other, List.of("B part 1 of 2", "B part 2 of 2")));

        assertTrue(askOther.finishedWithin(3_000), "the other chat's prompt did not go out while the first chat's send "
                + "was held open. If the lock's key or its stripes changed, pick two chats on different stripes");
        assertEquals(List.of(busy + "|A part 1 of 2", other + "|B part 1 of 2", other + "|B part 2 of 2"), channel.arrived);
        assertEquals(1, channel.letGo.getCount(), "the first chat's send is still held open");

        channel.letGo.countDown();
        askBusy.join();
        assertEquals(List.of(busy + "|A part 1 of 2", other + "|B part 1 of 2", other + "|B part 2 of 2",
                busy + "|A part 2 of 2"), channel.arrived);
    }

    // ── The chat is free again after a prompt that did not go out ──────

    @Test
    void aTelegramChatIsFreeAgainAfterAPromptThatFailedWasOverTheLimitOrThrew() throws Exception {
        try (var telegram = new BotApi()) {
            telegram.refused = message -> message.equals(CHAT_A + "|F part 2 of 3");

            assertEquals(EXPIRED, telegram.ask(CHAT_A, List.of("F part 1 of 3", "F part 2 of 3", "F part 3 of 3")));
            assertEquals(EXPIRED, telegram.ask(CHAT_A, List.of("O part 1 of 2", "o".repeat(4097))));
            // A part that is null throws from inside the send, before anything is sent.
            assertThrows(NullPointerException.class, () -> telegram.ask(CHAT_A, Arrays.asList("T part 1 of 2", null)));

            // Asked from another thread: this one could walk back in through a lock it still held.
            var later = new Asker(AT_ONCE, () -> telegram.ask(CHAT_A, List.of("L part 1 of 2", "L part 2 of 2")));
            assertTrue(later.finishedWithin(3_000), "a later prompt to the same chat went out");
            assertEquals(List.of(CHAT_A + "|F part 1 of 3", CHAT_A + "|F part 2 of 3", CHAT_A + "|L part 1 of 2",
                    CHAT_A + "|L part 2 of 2"), telegram.arrived);
        }
    }

    @Test
    void aSlackChannelIsFreeAgainAfterAPromptThatFailedWasOverTheLimitOrThrew() throws Exception {
        try (var slack = new Slack()) {
            slack.refused = message -> message.equals(CHANNEL_A + "|F part 2 of 3");
            slack.broken = message -> message.equals(CHANNEL_A + "|T part 1 of 2");

            assertEquals(EXPIRED, slack.ask(CHANNEL_A, List.of("F part 1 of 3", "F part 2 of 3", "F part 3 of 3")));
            assertEquals(EXPIRED, slack.ask(CHANNEL_A, List.of("O part 1 of 2", "o".repeat(3001))));
            assertThrows(IllegalStateException.class, () -> slack.ask(CHANNEL_A, List.of("T part 1 of 2", "T part 2 of 2")));

            // Asked from another thread: this one could walk back in through a lock it still held.
            var later = new Asker(AT_ONCE, () -> slack.ask(CHANNEL_A, List.of("L part 1 of 2", "L part 2 of 2")));
            assertTrue(later.finishedWithin(3_000), "a later prompt to the same channel went out");
            assertEquals(List.of(CHANNEL_A + "|F part 1 of 3", CHANNEL_A + "|F part 2 of 3", CHANNEL_A + "|T part 1 of 2",
                    CHANNEL_A + "|L part 1 of 2", CHANNEL_A + "|L part 2 of 2"), slack.arrived);
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────

    /** A thread that asks for a prompt once {@code go} opens. */
    private static final class Asker {
        private final Thread thread;
        private volatile boolean asking;
        private volatile Throwable failure;

        Asker(CountDownLatch go, Runnable ask) {
            thread = Thread.ofPlatform().daemon().start(() -> {
                try {
                    go.await();
                    asking = true;
                    ask.run();
                } catch (Throwable t) {
                    failure = t;
                }
            });
        }

        /** Waits until the thread, having started to ask, is parked with nothing to wake it but a lock, or has finished. */
        void awaitWaitingOrDone() throws InterruptedException {
            for (int i = 0; i < 1_000; i++) {
                var state = thread.getState();
                if (asking && (state == Thread.State.WAITING || state == Thread.State.TERMINATED)) return;
                Thread.sleep(5);
            }
            throw new AssertionError("the second prompt neither waited for the chat nor went ahead: " + thread.getState());
        }

        boolean finishedWithin(long millis) throws InterruptedException {
            thread.join(millis);
            if (failure != null) throw new AssertionError("asking for the prompt failed", failure);
            return !thread.isAlive();
        }

        void join() throws InterruptedException {
            assertTrue(finishedWithin(5_000), "the prompt was sent within five seconds");
        }
    }

    /** A channel's stand-in: records each message as it arrives, and holds open or refuses the ones a case names. */
    private abstract static class Channel implements AutoCloseable {
        /** Each message as {@code chat|text}, in the order it arrived. */
        final List<String> arrived = new CopyOnWriteArrayList<>();
        /** The approval id of each message that carried buttons. */
        final List<String> approvalIds = new CopyOnWriteArrayList<>();
        final CountDownLatch holding = new CountDownLatch(1);
        final CountDownLatch letGo = new CountDownLatch(1);
        volatile Predicate<String> held = _ -> false;
        volatile Predicate<String> refused = _ -> false;

        /** Records an arriving message, holds it open when the case says so, and answers whether the channel takes it. */
        boolean take(String chat, String text) {
            var message = chat + "|" + text;
            boolean hold;
            synchronized (arrived) {
                arrived.add(message);
                hold = held.test(message);
            }
            if (hold) {
                holding.countDown();
                try {
                    letGo.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return !refused.test(message);
        }

        /** Asks the approval service for a prompt of {@code parts}: its outcome so far, {@code "null"} while it waits for a tap. */
        abstract String ask(String chat, List<String> parts);

        @Override public abstract void close();
    }

    /** Stands in for the Bot API over HTTP, where {@code TelegramChannel.installForTest} points the bot token. */
    private static final class BotApi extends Channel {
        private final HttpServer http;
        private final ExecutorService workers = Executors.newCachedThreadPool();

        BotApi() throws IOException {
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.setExecutor(workers);
            http.createContext("/", exchange -> {
                var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                boolean taken = true;
                if (exchange.getRequestURI().getPath().toLowerCase(Locale.ROOT).endsWith("/sendmessage")) {
                    var id = TELEGRAM_CALLBACK_ID.matcher(body);
                    if (id.find()) approvalIds.add(id.group(1));
                    var message = JsonParser.parseString(body).getAsJsonObject();
                    taken = take(message.get("chat_id").getAsString(), message.get("text").getAsString());
                }
                var answer = (taken ? TELEGRAM_TOOK_IT : TELEGRAM_REFUSED_IT).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(taken ? 200 : 400, answer.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(answer);
                }
            });
            http.start();
            TelegramChannel.installForTest(BOT_TOKEN, TelegramUrl.builder()
                    .schema("http").host("127.0.0.1").port(http.getAddress().getPort()).build());
        }

        @Override String ask(String chat, List<String> parts) {
            return String.valueOf(TelegramApprovalService.request(BOT_TOKEN, chat, chat, parts, true).getNow(null));
        }

        @Override public void close() {
            letGo.countDown();
            for (var id : approvalIds) TelegramApprovalService.expire(id, TelegramApprovalService.Outcome.EXPIRED);
            http.stop(0);
            workers.shutdownNow();
            TelegramChannel.clearForTest(BOT_TOKEN);
        }
    }

    /** Stands in for Slack through the wire {@code SlackApprovalService.installForTest} takes for one bot token. */
    private static final class Slack extends Channel {
        /** The messages whose post throws, as the SDK can. */
        volatile Predicate<String> broken = _ -> false;

        Slack() {
            SlackApprovalService.installForTest(SLACK_TOKEN, new SlackApprovalService.Wire() {
                @Override public String post(String channelId, String threadTs, String fallbackText, List<LayoutBlock> blocks) {
                    var text = ((MarkdownTextObject) ((SectionBlock) blocks.getFirst()).getText()).getText();
                    for (var block : blocks) {
                        if (!(block instanceof ActionsBlock actions)) continue;
                        var id = SLACK_ACTION_ID.matcher(((ButtonElement) actions.getElements().getFirst()).getActionId());
                        if (id.matches()) approvalIds.add(id.group(1));
                    }
                    boolean taken = take(channelId, text);
                    if (broken.test(channelId + "|" + text)) throw new IllegalStateException("the wire broke");
                    return taken ? "1700000000.000001" : null;
                }

                @Override public boolean update(String channelId, String ts, String fallbackText, List<LayoutBlock> blocks) {
                    return true;
                }
            });
        }

        @Override String ask(String chat, List<String> parts) {
            return String.valueOf(SlackApprovalService.request(SLACK_TOKEN, chat, null, SLACK_OWNER, parts, true).getNow(null));
        }

        @Override public void close() {
            letGo.countDown();
            for (var id : approvalIds) SlackApprovalService.expire(id, SlackApprovalService.Outcome.EXPIRED);
            SlackApprovalService.clearForTest(SLACK_TOKEN);
        }
    }
}
