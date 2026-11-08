import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.FunctionalTest;
import slash.Commands;

/**
 * JCLAW-1071: /api/slash-commands feeds the web composer's "/" menu. The
 * endpoint exists so the menu is derived from {@link Commands.Command} rather
 * than a hand-kept frontend list, so the assertions here are about that
 * derivation — every enum constant present, with the description Telegram's
 * native dropdown already uses.
 */
class ApiSlashCommandsControllerTest extends FunctionalTest {

    private static final String TEST_PASSWORD = "testpass-slashcmds";

    @BeforeEach
    void seedAndLogin() {
        AuthFixture.seedAdminPassword(TEST_PASSWORD);
        var loginBody = """
                {"username":"admin","password":"%s"}
                """.formatted(TEST_PASSWORD);
        assertIsOk(POST("/api/auth/login", "application/json", loginBody));
    }

    @AfterEach
    void cleanup() {
        AuthFixture.clearAdminPassword();
    }

    @Test
    void listsEveryCommandWithLiteralNameAndDescription() {
        var response = GET("/api/slash-commands");
        assertIsOk(response);
        assertContentType("application/json", response);
        var body = getContent(response);
        for (var cmd : Commands.Command.values()) {
            assertTrue(body.contains("\"literal\":\"" + cmd.literal + "\""),
                    cmd.literal + " literal present: " + body);
            assertTrue(body.contains("\"name\":\"" + cmd.bareName() + "\""),
                    cmd.literal + " bare name present: " + body);
            assertTrue(body.contains(cmd.shortDescription),
                    cmd.literal + " description present: " + body);
        }
    }

    @Test
    void includesPromptSoTheMenuAndHelpCannotDisagree() {
        // /prompt is a real command since JCLAW-1073, so it arrives here like any
        // other rather than being appended client-side.
        var body = getContent(GET("/api/slash-commands"));
        assertTrue(body.contains("\"literal\":\"/prompt\""), "/prompt present: " + body);
    }

    @Test
    void listsQueueAfterStopWithItsDescription() {
        var body = com.google.gson.JsonParser.parseString(getContent(GET("/api/slash-commands"))).getAsJsonArray();
        var literals = new java.util.ArrayList<String>();
        body.forEach(e -> literals.add(e.getAsJsonObject().get("literal").getAsString()));

        assertEquals(java.util.List.of("/new", "/reset", "/compact", "/help", "/model", "/think", "/usage", "/stop",
                "/queue", "/subagent", "/prompt"), literals);
        assertEquals(com.google.gson.JsonParser.parseString(
                        "{\"literal\":\"/queue\",\"name\":\"queue\",\"description\":\"Show or clear the waiting messages\"}"),
                body.get(literals.indexOf("/queue")));
    }

    @Test
    void requiresAuthentication() {
        POST("/api/auth/logout", "application/json", "{}");
        var response = GET("/api/slash-commands");
        assertStatus(401, response);
    }
}
