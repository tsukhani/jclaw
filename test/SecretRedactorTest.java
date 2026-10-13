import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import play.test.UnitTest;
import services.EventLogger;
import utils.SecretRedactor;

/**
 * {@link SecretRedactor}: a credential on a logged command line or in logged tool arguments loses its
 * value and keeps its name, and ordinary log text (token counts, paths, prose) is left alone.
 */
class SecretRedactorTest extends UnitTest {

    private static final String KEY = "0123456789abcdef0123456789abcdef";

    private static void assertMasked(String text, String secret, String kept) {
        var out = SecretRedactor.redact(text);
        assertFalse(out.contains(secret), "the value must be gone: " + out);
        assertTrue(out.contains(kept), "the name must stay so the line is still readable: " + out);
        assertTrue(out.contains(SecretRedactor.MASK), out);
        assertEquals(out, SecretRedactor.redact(out), "masking twice changes nothing");
    }

    @Test
    void masksAnApiKeyHeaderOnACommandLine() {
        assertMasked("exec: curl -s \"https://media.example.com/api/v3/queue?pageSize=100\" -H \"X-Api-Key: " + KEY + "\"",
                KEY, "X-Api-Key:");
    }

    @Test
    void masksTheSameHeaderInsideLoggedToolArguments() {
        // The second copy: "Executing tool 'exec' (args: {...})", where the command's quotes are JSON-escaped.
        assertMasked("Executing tool 'exec' (id: call_1, args: {\"command\":\"curl -H \\\"X-Api-Key: " + KEY + "\\\" https://x.example.com\"})",
                KEY, "X-Api-Key:");
    }

    @Test
    void masksAKeyInAUrlAndKeepsTheOtherParameters() {
        var out = SecretRedactor.redact("exec: curl \"https://api.example.com/v1/items?apikey=" + KEY + "&page=2&key=abcd1234efgh5678\"");
        assertFalse(out.contains(KEY), out);
        assertFalse(out.contains("abcd1234efgh5678"), out);
        assertTrue(out.contains("apikey=" + SecretRedactor.MASK) && out.contains("page=2"), out);
    }

    @Test
    void masksBearerAndBasicCredentials() {
        assertMasked("curl -H \"Authorization: Bearer abc.def-ghi_jkl012345\" https://x", "abc.def-ghi_jkl012345", "Authorization: Bearer");
        assertMasked("curl -H 'Authorization: Basic dXNlcjpwYXNz' https://x", "dXNlcjpwYXNz", "Authorization: Basic");
        assertMasked("retrying with bearer abcdefghij0123456789xyz", "abcdefghij0123456789xyz", "bearer");
    }

    @Test
    void masksNamedSecretsInEnvironmentJsonAndFlags() {
        assertMasked("exec: MEDIA_API_KEY=" + KEY + " ./sync.sh", KEY, "MEDIA_API_KEY=");
        assertMasked("args: {\"username\":\"sam\",\"password\":\"hunter2\"}", "hunter2", "\"password\"");
        assertMasked("args: {\\\"client_secret\\\":\\\"s3cr3t-value\\\"}", "s3cr3t-value", "client_secret");
        assertMasked("header X-Plex-Token: Zx9Qw3Er5Ty7Ui1Op", "Zx9Qw3Er5Ty7Ui1Op", "X-Plex-Token:");
        assertMasked("exec: curl -u admin:letmein123 https://x", "letmein123", "-u admin:");
        assertMasked("exec: git clone https://sam:ghs_pass99@git.example.com/r.git", "ghs_pass99", "https://sam:");
    }

    @Test
    void masksTheValueOfAConfigWriteThatNamesACredential() {
        assertMasked("Executing tool 'jclaw_api' (args: {\"method\":\"POST\",\"path\":\"/api/config\",\"body\":"
                + "{\"key\":\"provider.acme.apiKey\",\"value\":\"plain-credential-42\"}})", "plain-credential-42", "provider.acme.apiKey");
        var plain = "args: {\"key\":\"chat.maxToolRounds\",\"value\":\"100\"}";
        assertEquals(plain, SecretRedactor.redact(plain), "an ordinary config write is left alone");
    }

    @Test
    void aLongRunOfWordCharactersIsScannedInLinearTime() {
        // A base64 blob in a tool result: the name patterns must not backtrack through it.
        var blob = "secret".repeat(3) + "A".repeat(400_000);
        var started = System.nanoTime();
        SecretRedactor.redact("Tool 'filesystem' returned: " + blob);
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000, "400,000 characters took over 2 s");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "sk-proj-abcdefghijklmnopqrstuvwx0123",
            "ghp_abcdefghijklmnopqrstuvwxyz0123456789",
            "xoxb-123456789012-abcdefghijkl",
            "AKIAABCDEFGHIJKLMNOP",
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTYifQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV",
            "123456789:AAEhBP0av18zrZ-abcdefghijklmnopqrstu"})
    void masksWellKnownTokenShapesWithNoNameBesideThem(String token) {
        var out = SecretRedactor.redact("Tool 'web_fetch' returned: the page shows " + token + " in its source");
        assertFalse(out.contains(token), out);
        assertTrue(out.contains("the page shows") && out.contains("in its source"), out);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Streaming complete (2275 chars) [668612 prompt, 3002 completion, 671614 total tokens, 808 reasoning, 607232 cached, 56.5s]",
            "calling ollama-cloud / kimi-k2.6 (3 messages, 49 tools, 34 skills, thinking=low) maxTokens=4096",
            "usage {\"prompt_tokens\": 668612, \"completion_tokens\": 3002}",
            "tokenCount=5 token_budget: 120000",
            "exec: cd /Users/sam/project && PWD=/Users/sam/project ls -la",
            "basic authentication is required for this endpoint",
            "Task 'weekly-report' (id=4352) updated via API",
            "Tool 'filesystem' returned: wrote 41 bytes to notes/keynote-outline.md",
            "exec: curl -s https://example.com/search?q=secret+garden&page=2",
            "Tool 'jclaw_api' returned: HTTP 200 {\"id\":7,\"hasAppSecret\":true,\"hasBotToken\":false,\"password\":null}"})
    void leavesOrdinaryLogTextAlone(String line) {
        assertEquals(line, SecretRedactor.redact(line));
    }

    @Test
    void anEventIsMaskedBeforeItIsWrittenAnywhere() {
        var seen = EventLogger.captureForTest(() -> EventLogger.info("tool", "main", "exec",
                "exec: curl -H \"X-Api-Key: " + KEY + "\" https://x.example.com"));
        assertEquals(1, seen.size());
        assertFalse(seen.getFirst().message().contains(KEY), "the file line and the stored row are built from this message");
        assertTrue(seen.getFirst().message().contains("X-Api-Key: " + SecretRedactor.MASK), seen.getFirst().message());
    }
}
