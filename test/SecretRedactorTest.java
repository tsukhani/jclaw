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

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "password", "secret", "token", "api_key", "authorization", "cookie", "x-a-key", "A_KEY", "bearer ", " --token ",
            "sshpass -p", "://a:", " -u a:", "\"key\":\"", "?key", "sk-", "eyJaaaaaaaa-", "-----BEGIN PRIVATE KEY-----", "12345678:", "\\\\\\\\"})
    void aTextThatRepeatsATriggerIsScannedInLinearTime(String trigger) {
        // The text is model-controlled; a pattern that rescans from every trigger is quadratic, minutes at this size.
        var text = trigger.repeat(400_000 / trigger.length()) + "A".repeat(100_000);
        var started = System.nanoTime();
        SecretRedactor.redact(text);
        var millis = (System.nanoTime() - started) / 1_000_000;
        assertTrue(millis < 3_000, () -> "500,000 characters of '" + trigger + "' took " + millis + " ms");
    }

    @Test
    void masksAValueWrittenWithJsonUnicodeEscapes() {
        // An HTML-safe serializer writes = as \u003d and & as \u0026 inside logged arguments.
        var out = SecretRedactor.redact("args: {\"command\":\"curl https://x.example.com/api?apikey\\u003d" + KEY
                + "\\u0026page\\u003d2 MEDIA_TOKEN\\u003dabc12345\"}");
        assertFalse(out.contains(KEY) || out.contains("abc12345"), out);
        assertTrue(out.contains("page\\u003d2"), "the other parameter stays: " + out);
    }

    @Test
    void masksAQuotedValueUpToItsClosingQuote() {
        assertMasked("args: {\"password\":\"correct horse battery staple\"}", "horse battery staple", "\"password\"");
        assertMasked("args: {\"client_secret\":\"a1,b2;c3}d4\",\"scope\":\"read\"}", "b2;c3}d4", "client_secret");
        assertTrue(SecretRedactor.redact("args: {\"client_secret\":\"a1,b2\",\"scope\":\"read\"}").contains("\"scope\":\"read\""),
                "what follows the closing quote is kept");
    }

    @Test
    void neverJudgesAValueByItsShape() {
        assertMasked("exec: curl https://x/?token=12345678", "12345678", "token=");
        assertMasked("args: {\"access_token\": 99887766}", "99887766", "access_token");
        assertMasked("args: {\"password\":\"true\"}", "\"true\"", "\"password\"");
        assertMasked("exec: PIN_SECRET=42 ./run.sh", "=42", "PIN_SECRET=");
    }

    @Test
    void masksTheOtherPlacesACommandCarriesACredential() {
        assertMasked("exec: curl -H \"Cookie: session=abc123; theme=dark\" https://x", "session=abc123; theme=dark", "Cookie:");
        assertMasked("exec: mytool --password hunter2 --verbose", "hunter2", "--password");
        assertMasked("exec: mytool --token 'tk_98765' run", "tk_98765", "--token");
        assertMasked("exec: sshpass -p 'pw12345' ssh host", "pw12345", "sshpass -p");
        assertMasked("exec: curl \"https://x/login?user=sam&pass=hunter2&pwd=x1y2\"", "hunter2", "pass=");
        assertMasked("exec: SERVICE_KEY=abc123xyz ./deploy.sh", "abc123xyz", "SERVICE_KEY=");
        assertMasked("exec: curl -H 'X-Functions-Key: fk-123456' https://x", "fk-123456", "X-Functions-Key:");
        assertMasked("exec: curl -H 'Ocp-Apim-Subscription-Key: sub-123456' https://x", "sub-123456", "Subscription-Key:");
        assertMasked("Tool 'filesystem' returned: -----BEGIN RSA PRIVATE KEY-----\\nMIIEowIBAAKCAQEA7x\\n-----END RSA PRIVATE KEY----- ok",
                "MIIEowIBAAKCAQEA7x", "returned:");
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
            "Rescheduled Task 'nightly' next CRON fire for 2026-10-18T04:00:00Z; primary_key: 42, sort-key: name",
            "Operator updated factory credentials: jiraUrl, jiraPersonalToken, githubToken",
            "args: {\"tool\":\"search_messages\",\"args\":{\"page_token\":\"CAESBggCEAEYAQ\",\"nextPageToken\":\"abc123def456\"}}"})
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
