import com.google.gson.JsonParser;
import models.ChromeBridgeToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.FunctionalTest;
import services.Tx;
import utils.TokenHasher;

import java.util.function.Supplier;

class ApiChromeBridgeControllerTest extends FunctionalTest {

    private static final String PASSWORD = "testpass-chrome-bridge";

    private static <T> T commitInFreshTx(Supplier<T> block) {
        var ref = new java.util.concurrent.atomic.AtomicReference<T>();
        var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();
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

    private static void clearTokens() {
        commitInFreshTx(() -> ChromeBridgeToken.deleteAll());
    }

    private static boolean tokenIsActive(String token) {
        return commitInFreshTx(() -> ChromeBridgeToken.findActiveByPlaintext(token) != null);
    }

    @BeforeEach
    void seed() {
        clearTokens();
        AuthFixture.seedAdminPassword(PASSWORD);
    }

    @AfterEach
    void cleanup() {
        clearTokens();
        AuthFixture.clearAdminPassword();
    }

    private void login() {
        var body = """
                {"username":"admin","password":"%s"}
                """.formatted(PASSWORD);
        assertIsOk(POST("/api/auth/login", "application/json", body));
    }

    @Test
    void anUnauthenticatedCallerIsRefusedAndNothingIsMinted() {
        assertStatus(401, POST("/api/chrome-bridge/pairing", "application/json", "{}"));
        assertStatus(401, GET("/api/chrome-bridge"));
        assertFalse(commitInFreshTx(() -> ChromeBridgeToken.count() > 0));
    }

    @Test
    void pairingReturnsATokenThatVerifiesAndStatusThenReportsPaired() {
        login();
        assertEquals("{\"paired\":false}", getContent(GET("/api/chrome-bridge")));

        var response = POST("/api/chrome-bridge/pairing", "application/json", "{}");
        assertIsOk(response);
        var token = JsonParser.parseString(getContent(response)).getAsJsonObject().get("token").getAsString();

        assertTrue(token.startsWith(TokenHasher.TOKEN_PREFIX));
        assertTrue(tokenIsActive(token));
        assertEquals("{\"paired\":true}", getContent(GET("/api/chrome-bridge")));
    }

    @Test
    void statusNeverCarriesTheToken() {
        login();
        var token = JsonParser.parseString(getContent(POST("/api/chrome-bridge/pairing", "application/json", "{}")))
                .getAsJsonObject().get("token").getAsString();

        assertFalse(getContent(GET("/api/chrome-bridge")).contains(token));
    }

    @Test
    void unpairingRevokesTheCredential() {
        login();
        var token = JsonParser.parseString(getContent(POST("/api/chrome-bridge/pairing", "application/json", "{}")))
                .getAsJsonObject().get("token").getAsString();

        var response = DELETE("/api/chrome-bridge/pairing");
        assertIsOk(response);

        assertEquals("{\"paired\":false}", getContent(response));
        assertFalse(tokenIsActive(token));
    }
}
