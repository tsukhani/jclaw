import channels.ChannelTransport;
import channels.TelegramInboundTurn;
import models.Agent;
import models.AgentBoundBinding;
import models.SlackBinding;
import models.TelegramBinding;
import models.WhatsAppBinding;
import org.hibernate.Session;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import play.Play;
import play.db.jpa.JPA;
import play.test.UnitTest;
import services.AgentService;
import services.Tx;
import utils.CredentialCipher;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * JCLAW-1455: the channel-binding credentials as the application sees them once they are stored encrypted. The rows
 * are this class's own, each binding with an agent of its own, since other classes share the tables. Each step
 * commits in a transaction of its own: a refused insert would otherwise poison the test body's ambient one.
 */
class BindingCredentialEncryptionTest extends UnitTest {

    private final List<Long> agents = new ArrayList<>();

    @AfterEach
    void deleteOwnAgents() {
        for (var id : agents) {
            commitInFreshTx(() -> {
                Agent agent = Agent.findById(id);
                if (agent != null) AgentService.delete(agent);
            });
        }
    }

    @Test
    void aCiphertextCopiedIntoAnotherColumnReadsAsUnset() {
        var token = fake("xoxb");
        long slack = commitInFreshTx(() -> slack(token).id);
        long telegram = commitInFreshTx(() -> telegram(fake("1455")).id);

        sql("UPDATE slack_binding SET app_token = bot_token WHERE id = " + slack);
        sql("UPDATE telegram_binding SET bot_token = (SELECT bot_token FROM slack_binding WHERE id = " + slack
                + ") WHERE id = " + telegram);

        var reread = reload(SlackBinding.class, slack);
        assertEquals(token, reread.botToken);
        assertNull(reread.appToken, "the bot token's ciphertext does not open as the app token");
        assertNull(reload(TelegramBinding.class, telegram).botToken,
                "nor as another table's bot token: the table is part of the field name");
    }

    @Test
    void aCredentialThatWillNotDecryptReadsAsUnsetAndAnEditLeavesItsCiphertext() {
        var elsewhere = CredentialCipher.of("fake-secret-of-another-installation", null);
        long telegram = commitInFreshTx(() -> telegram(fake("1455")).id);
        long slack = commitInFreshTx(() -> slack(fake("xoxb")).id);
        var botToken = elsewhere.encrypt("telegram_binding.bot_token", "1455:fake-restored-from-elsewhere");
        var signing = elsewhere.encrypt("slack_binding.signing_secret", "fake-signing-from-elsewhere");
        sql("UPDATE telegram_binding SET bot_token = '" + botToken + "' WHERE id = " + telegram);
        sql("UPDATE slack_binding SET signing_secret = '" + signing + "' WHERE id = " + slack);

        assertNull(reload(TelegramBinding.class, telegram).botToken);
        assertNull(reload(SlackBinding.class, slack).signingSecret);

        commitInFreshTx(() -> {
            evict(TelegramBinding.class, telegram);
            TelegramBinding b = TelegramBinding.findById(telegram);
            b.replyToMode = "all";
            b.save();
            evict(SlackBinding.class, slack);
            SlackBinding s = SlackBinding.findById(slack);
            s.enabled = false;
            s.save();
        });

        assertEquals(botToken, raw("SELECT bot_token FROM telegram_binding WHERE id = " + telegram),
                "an edit to another field leaves the ciphertext for the right secret to recover");
        assertEquals("all", raw("SELECT reply_to_mode FROM telegram_binding WHERE id = " + telegram));
        assertEquals(signing, raw("SELECT signing_secret FROM slack_binding WHERE id = " + slack));
    }

    @Test
    void bindingsAreFoundByTokenUnderTheCurrentAndThePreviousSecret() {
        var telegramToken = fake("1455");
        var slackToken = fake("xoxb");
        var verifyToken = fake("verify");
        var telegram = commitInFreshTx(() -> telegram(telegramToken));
        long slack = commitInFreshTx(() -> slack(slackToken).id);
        long whatsapp = commitInFreshTx(() -> {
            var b = new WhatsAppBinding();
            b.agent = agent();
            b.verifyToken = verifyToken;
            b.save();
            return b.id;
        });
        var bystander = commitInFreshTx(BindingCredentialEncryptionTest.this::agent);

        assertEquals(telegram.id, commitInFreshTx(() -> TelegramBinding.findByBotToken(telegramToken).id));
        assertEquals(telegram.agent.id,
                TelegramInboundTurn.resolveTopicAgent(telegramToken, "1455", null, bystander).id,
                "an inbound Telegram update resolves its binding by the token");
        assertEquals(slack, commitInFreshTx(() -> SlackBinding.findByBotToken(slackToken).id));
        assertEquals(whatsapp, commitInFreshTx(() -> WhatsAppBinding.findByVerifyToken(verifyToken).id));

        var rotated = CredentialCipher.of("fake-secret-after-a-rotation-1455", Play.secretKey);
        var forgotten = CredentialCipher.of("fake-secret-after-a-rotation-1455", null);
        assertEquals(telegram.id, commitInFreshTx(() -> TelegramBinding.findByBotToken(telegramToken, rotated).id),
                "before the boot pass re-keys a row, the previous secret's lookup finds it");
        assertEquals(slack, commitInFreshTx(() -> SlackBinding.findByBotToken(slackToken, rotated).id));
        assertEquals(whatsapp, commitInFreshTx(() -> WhatsAppBinding.findByVerifyToken(verifyToken, rotated).id));
        assertNull(commitInFreshTx(() -> TelegramBinding.findByBotToken(telegramToken, forgotten)),
                "the fixture must need the previous secret, or the line above proves nothing");
    }

    @Test
    void changingATokenMovesItsLookup() {
        var before = fake("1455");
        var after = fake("1455");
        long id = commitInFreshTx(() -> telegram(before).id);

        commitInFreshTx(() -> {
            TelegramBinding b = TelegramBinding.findById(id);
            b.botToken = after;
            b.save();
        });

        assertEquals(id, commitInFreshTx(() -> TelegramBinding.findByBotToken(after).id));
        assertNull(commitInFreshTx(() -> TelegramBinding.findByBotToken(before)));
    }

    @Test
    void theDatabaseRefusesASecondBindingWithTheSameBotToken() {
        var telegramToken = fake("1455");
        var slackToken = fake("xoxb");
        commitInFreshTx(() -> telegram(telegramToken));
        commitInFreshTx(() -> slack(slackToken));

        // Saved directly, past the controllers' duplicate check.
        assertThrows(RuntimeException.class, () -> commitInFreshTx(() -> telegram(telegramToken)));
        assertThrows(RuntimeException.class, () -> commitInFreshTx(() -> slack(slackToken)));
    }

    private Agent agent() {
        var agent = AgentService.create("jclaw1455b-" + HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextInt()),
                "openrouter", "gpt-4.1", null, null, false);
        agents.add(agent.id);
        return agent;
    }

    private TelegramBinding telegram(String token) {
        var b = new TelegramBinding();
        b.agent = agent();
        b.botToken = token;
        b.telegramUserId = "1455";
        b.webhookSecret = "fake-webhook-secret";
        b.save();
        return b;
    }

    private SlackBinding slack(String token) {
        var b = new SlackBinding();
        b.agent = agent();
        b.botToken = token;
        b.signingSecret = "fake-signing-secret";
        b.appToken = "xapp-fake-app-token";
        b.transport = ChannelTransport.HTTP;
        b.save();
        return b;
    }

    private static String fake(String prefix) {
        return prefix + "-fake-" + HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextLong());
    }

    private static <T> T commitInFreshTx(Supplier<T> block) {
        var ref = new AtomicReference<T>();
        var err = new AtomicReference<Throwable>();
        var t = Thread.ofPlatform().start(() -> {
            try {
                ref.set(Tx.run(block::get));
            } catch (Throwable ex) {
                err.set(ex);
            }
        });
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        if (err.get() != null) throw new RuntimeException(err.get());
        return ref.get();
    }

    private static void commitInFreshTx(Runnable block) {
        commitInFreshTx(() -> {
            block.run();
            return null;
        });
    }

    private static <T extends AgentBoundBinding> T reload(Class<T> type, long id) {
        return commitInFreshTx(() -> {
            evict(type, id);
            return JPA.em().find(type, id);
        });
    }

    // The second-level cache would answer with the entity as written, not the row as it now stands.
    private static void evict(Class<?> type, long id) {
        JPA.em().getEntityManagerFactory().getCache().evict(type, id);
        JPA.em().clear();
    }

    private static void sql(String statement) {
        commitInFreshTx(() -> JPA.em().unwrap(Session.class).doWork(connection -> {
            try (var s = connection.createStatement()) {
                s.executeUpdate(statement);
            }
        }));
    }

    private static @Nullable String raw(String query) {
        return commitInFreshTx(() -> JPA.em().unwrap(Session.class).doReturningWork(connection -> {
            try (var s = connection.createStatement(); var rows = s.executeQuery(query)) {
                return rows.next() ? rows.getString(1) : null;
            }
        }));
    }
}
