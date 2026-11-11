import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.mvc.Http.Response;
import play.test.FunctionalTest;

import java.util.List;

/**
 * What the config routes show of a saved secret (JF-294), driven over HTTP: the save's own answer, the single
 * read and the list each carry the mask alone, whatever the secret's length.
 *
 * <p>No {@code Fixtures.deleteDatabase()}: the two rows here are this class's by their key, and are deleted after
 * each test.
 */
class ConfigSecretMaskReadTest extends FunctionalTest {

    private static final String SECRET_KEY = "jf294mask.probe.apiKey";
    private static final String PLAIN_KEY = "jf294mask.probe.note";

    @BeforeEach
    void login() {
        AuthFixture.seedAdminPassword("changeme");
        assertIsOk(POST("/api/auth/login", "application/json", "{\"username\":\"admin\",\"password\":\"changeme\"}"));
    }

    @AfterEach
    void dropRows() {
        DELETE("/api/config/" + SECRET_KEY);
        DELETE("/api/config/" + PLAIN_KEY);
        clearCookies();
    }

    private static Response save(String key, String value) {
        var body = new JsonObject();
        body.addProperty("key", key);
        body.addProperty("value", value);
        return POST("/api/config", "application/json", body.toString());
    }

    private static JsonObject json(Response resp) {
        return JsonParser.parseString(getContent(resp)).getAsJsonObject();
    }

    /** An error body's message, or null when the body carries none. */
    private static String message(Response resp) {
        var message = json(resp).get("message");
        return message == null ? null : message.getAsString();
    }

    /** The value {@code GET /api/config} lists for {@code key}. */
    private static String listed(String key) {
        for (var entry : json(GET("/api/config")).getAsJsonArray("entries")) {
            if (key.equals(entry.getAsJsonObject().get("key").getAsString())) {
                return entry.getAsJsonObject().get("value").getAsString();
            }
        }
        throw new AssertionError(key + " is not listed");
    }

    /** What the save answered, the single read and the list show for {@code key}, in that order. */
    private static List<String> shown(Response saved, String key) {
        assertIsOk(saved);
        return List.of(json(saved).get("value").getAsString(),
                json(GET("/api/config/" + key)).get("value").getAsString(), listed(key));
    }

    @Test
    void aSavedSecretReadsBackAsTheMaskAloneWhateverItsLength() {
        for (var secret : List.of("a", "abcd", "abcde", "sk-fake-0123456789")) {
            assertEquals(List.of("****", "****", "****"), shown(save(SECRET_KEY, secret), SECRET_KEY), secret);
        }
    }

    @Test
    void aClearedSecretReadsAsEmptyAndAValueThatIsNoSecretReadsWhole() {
        assertIsOk(save(SECRET_KEY, "sk-fake-0123456789"));

        assertEquals(List.of("", "", ""), shown(save(SECRET_KEY, ""), SECRET_KEY));
        assertEquals(List.of("abc", "abc", "abc"), shown(save(PLAIN_KEY, "abc"), PLAIN_KEY));
    }

    @Test
    void aMaskSentBackIsRefusedAndTheSecretStays() {
        assertIsOk(save(SECRET_KEY, "abc"));

        var refused = save(SECRET_KEY, json(GET("/api/config/" + SECRET_KEY)).get("value").getAsString());

        assertEquals(List.of("403", "That is the saved value's mask, not a new value. Enter the whole new value, or leave "
                        + SECRET_KEY + " unchanged."),
                List.of(String.valueOf(refused.status), String.valueOf(message(refused))));
        assertEquals("abc", services.ConfigService.entry(SECRET_KEY).value());
    }
}
