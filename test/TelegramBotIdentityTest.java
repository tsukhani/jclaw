import channels.TelegramBotIdentity;
import channels.TelegramBotIdentityTestHooks;
import channels.TelegramChannel;
import org.junit.jupiter.api.*;
import play.test.*;

/**
 * Coverage for {@link TelegramBotIdentity} (JCLAW-371). The bot user id is
 * derived from the token prefix with no API call; the username is resolved via
 * a {@code getMe} call routed (in tests) to an embedded {@link MockTelegramServer}.
 * On a {@code getMe} failure the identity degrades to {@code (userId, null)} so
 * mention-by-handle stays dormant rather than aborting the inbound turn.
 *
 * <p>Mirrors {@code ApiTelegramBindingsProbeTest}'s getMe harness: redirect a
 * token to the mock via {@link TelegramChannel#installForTest}, shape the getMe
 * response with {@link MockTelegramServer#respondWith}, and clear the per-token
 * caches in teardown so identities don't leak across tests.
 */
class TelegramBotIdentityTest extends FunctionalTest {

    private static final String OK_TOKEN = "424242:identity-ok";
    private static final String FAIL_TOKEN = "777:identity-fail";

    private static final String GET_ME_OK =
            "{\"ok\":true,\"result\":{\"id\":424242,\"is_bot\":true,"
                    + "\"first_name\":\"JClaw Bot\",\"username\":\"jclaw_id_bot\"}}";
    private static final String UNAUTHORIZED =
            "{\"ok\":false,\"error_code\":401,\"description\":\"Unauthorized\"}";

    private MockTelegramServer server;

    @BeforeEach
    void setup() throws Exception {
        server = new MockTelegramServer();
        server.start();
        TelegramChannel.installForTest(OK_TOKEN, server.telegramUrl());
        TelegramChannel.installForTest(FAIL_TOKEN, server.telegramUrl());
        TelegramBotIdentityTestHooks.clear(OK_TOKEN);
        TelegramBotIdentityTestHooks.clear(FAIL_TOKEN);
    }

    @AfterEach
    void teardown() {
        if (server != null) server.close();
        TelegramChannel.clearForTest(OK_TOKEN);
        TelegramChannel.clearForTest(FAIL_TOKEN);
        TelegramBotIdentityTestHooks.clear(OK_TOKEN);
        TelegramBotIdentityTestHooks.clear(FAIL_TOKEN);
    }

    @Test
    void derivesUserIdFromTokenPrefixWithoutApiCall() {
        // No getMe override needed for the id — it's parsed from the token.
        server.respondWith("getMe", 200, GET_ME_OK);
        var identity = TelegramBotIdentity.resolve(OK_TOKEN);
        assertEquals(Long.valueOf(424242L), identity.userId(),
                "user id should be the numeric token prefix before ':'");
    }

    @Test
    void resolvesUsernameViaGetMe() {
        server.respondWith("getMe", 200, GET_ME_OK);
        var identity = TelegramBotIdentity.resolve(OK_TOKEN);
        assertEquals("jclaw_id_bot", identity.username(),
                "username should come from the getMe result");
        assertEquals(1, server.countRequests("getMe"),
                "resolve should call getMe exactly once");
    }

    @Test
    void cachesIdentityPerTokenSoGetMeFiresOnce() {
        server.respondWith("getMe", 200, GET_ME_OK);
        TelegramBotIdentity.resolve(OK_TOKEN);
        TelegramBotIdentity.resolve(OK_TOKEN);
        TelegramBotIdentity.resolve(OK_TOKEN);
        assertEquals(1, server.countRequests("getMe"),
                "the per-token cache must serve repeat resolves without re-calling getMe");
    }

    @Test
    void fallsBackToNullUsernameWhenGetMeFails() {
        server.respondWith("getMe", 401, UNAUTHORIZED);
        var identity = TelegramBotIdentity.resolve(FAIL_TOKEN);
        assertEquals(Long.valueOf(777L), identity.userId(),
                "user id should still be derived from the token on getMe failure");
        assertNull(identity.username(),
                "username should be null when getMe fails — mention-by-handle stays dormant");
    }

    @Test
    void blankTokenResolvesToEmptyIdentityWithoutApiCall() {
        var identity = TelegramBotIdentity.resolve("   ");
        assertNull(identity.userId());
        assertNull(identity.username());
        assertEquals(0, server.countRequests("getMe"),
                "a blank token must not trigger a getMe call");
    }
}
