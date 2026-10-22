import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import play.test.UnitTest;
import services.EventLogger;
import utils.SecretRedactor;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

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
            "sshpass -p", "://a:", " -u a:", " -u a:b", "\"key\":\"", "?key", "&key=a", "?key=a#", "sk-", "eyJaaaaaaaa-",
            "-----BEGIN PRIVATE KEY-----", "12345678:", "\\\\\\\\", "password=\"", "password: \"a ", "password=a\n", "password=''",
            "token: ", "\"x\":\"y\",", "'", "x'\\''", "X-Api-Key: a\\\"", "credentials=", "credentials\":"})
    void aTextThatRepeatsATriggerIsScannedInLinearTime(String trigger) {
        // The text is model-controlled. Bare, and again inside one long quoted string, where every match
        // shares one far closing quote: a scan that restarts from each match is quadratic, minutes at this size.
        for (var open : List.of("", "\"", "'", "{\"command\":\"curl \\\"")) {
            var text = open + trigger.repeat(400_000 / trigger.length()) + "A".repeat(100_000);
            var started = System.nanoTime();
            SecretRedactor.redact(text);
            var millis = (System.nanoTime() - started) / 1_000_000;
            assertTrue(millis < 3_000, () -> "500,000 characters of '" + trigger + "' after '" + open + "' took " + millis + " ms");
        }
    }

    // ---- a secret holding awkward characters, written the way each context escapes it ----

    private static String shellDoubleQuoted(String s) { return s.replaceAll("([\\\\\"$`])", "\\\\$1"); }

    private static String shellSingleQuoted(String s) { return s.replace("'", "'\\''"); }

    private static String shellBare(String s) { return s.replaceAll("([^A-Za-z0-9])", "\\\\$1"); }

    private static String json(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"); }

    /** As an HTML-safe serializer writes it, which is how tool arguments reach the log. */
    private static String htmlSafeJson(String s) {
        return json(s).replace("=", "\\u003d").replace("&", "\\u0026").replace("'", "\\u0027").replace("<", "\\u003c").replace(">", "\\u003e");
    }

    private static String url(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private record Context(String name, UnaryOperator<String> command, String after) {}

    private static final List<Context> COMMANDS = List.of(
            new Context("header in double quotes", s -> "curl -s \"https://x.example.com/v1\" -H \"X-Api-Key: " + shellDoubleQuoted(s) + "\" --max-time 5", "--max-time 5"),
            new Context("header in single quotes", s -> "curl -H 'X-Api-Key: " + shellSingleQuoted(s) + "' https://x.example.com", "https://x.example.com"),
            new Context("bare assignment", s -> "API_KEY=" + shellBare(s) + " ./run.sh --flag", "./run.sh --flag"),
            new Context("--password 'x'", s -> "mytool --password '" + shellSingleQuoted(s) + "' --verbose", "--verbose"),
            new Context("--password=\"x\"", s -> "mytool --password=\"" + shellDoubleQuoted(s) + "\" --verbose", "--verbose"),
            new Context("URL parameter", s -> "curl \"https://x.example.com/v1?apikey=" + url(s) + "&page=2\"", "page=2"),
            new Context("curl -u in quotes", s -> "curl -u 'admin:" + shellSingleQuoted(s) + "' https://x.example.com", "https://x.example.com"),
            new Context("bearer header", s -> "curl -H \"Authorization: Bearer " + shellDoubleQuoted(s) + "\" https://x", "https://x"),
            new Context("cookie header", s -> "curl -H \"Cookie: session=" + shellDoubleQuoted(s) + "; theme=dark\" https://x", "https://x"),
            new Context("sshpass", s -> "sshpass -p '" + shellSingleQuoted(s) + "' ssh host", "ssh host"),
            new Context("token variable with a suffix", s -> "export GITHUB_TOKEN_2=" + shellBare(s) + " && ./deploy", "./deploy"),
            new Context("form body in quotes", s -> "curl -d \"user=sam&password=" + shellDoubleQuoted(s) + "\" https://x", "https://x"),
            new Context("URL userinfo", s -> "git clone https://sam:" + url(s) + "@git.example.com/r.git", "git.example.com"),
            new Context("field far into a long form body", s -> "curl -d \"note=" + "x".repeat(900) + "&password=" + shellDoubleQuoted(s) + "\" https://x", "https://x"),
            new Context("header after a long quoted URL", s -> "curl \"https://x.example.com/" + "p/".repeat(400) + "\" -H \"X-Api-Key: " + shellDoubleQuoted(s) + "\" --max-time 5", "--max-time 5"),
            new Context("bare bearer in quotes", s -> "notify \"using bearer " + shellDoubleQuoted(s) + "\" now", "now"));

    /** A line break can only be part of a value that is quoted. */
    private static final List<String> QUOTED_ONLY = List.of("--password 'x'", "--password=\"x\"", "sshpass", "curl -u in quotes");

    private static void collect(List<String> leaks, String where, String separator, String text) {
        var out = SecretRedactor.redact(text);
        if (out.contains("S3x9") || out.contains("cr3T") || out.contains("t41L")) {
            leaks.add(where + " with '" + separator + "' between the parts: " + out);
        } else if (!out.equals(SecretRedactor.redact(out))) {
            leaks.add(where + " with '" + separator + "' is not stable when masked twice: " + out);
        }
    }

    @Test
    void aSecretIsMaskedWholeHoweverItsContextEscapesIt() {
        var leaks = new ArrayList<String>();
        var lost = new ArrayList<String>();
        for (var separator : List.of("\n", " ", ",", ";", "&", "|", ")", "}", "=", ":", "$", "#", "\"", "'", "\\", "%", "+", "/", "!", "*", "@")) {
            // The first part is long enough to pass for a bearer token on its own.
            var secret = "S3x9aaaaaaaaaaaaaaaa" + separator + "cr3T" + separator + "t41L";
            for (var context : COMMANDS) {
                // A raw & separates the fields of a form body, so one field cannot hold it.
                if (separator.equals("&") && context.name().contains("form body")) continue;
                if (separator.equals("\n") && !QUOTED_ONLY.contains(context.name())) continue;
                var command = context.command().apply(secret);
                collect(leaks, "exec: " + context.name(), separator, "exec: " + command);
                collect(leaks, "logged arguments: " + context.name(), separator,
                        "Executing tool 'exec' (id: call_1, args: {\"command\":\"" + json(command) + "\",\"why\":\"testing\"})");
                collect(leaks, "HTML-safe logged arguments: " + context.name(), separator,
                        "Executing tool 'exec' (id: call_1, args: {\"command\":\"" + htmlSafeJson(command) + "\",\"why\":\"testing\"})");
                if (!SecretRedactor.redact("exec: " + command).contains(context.after())) lost.add(context.name() + " with '" + separator + "'");
            }
            var body = "{\"username\":\"sam\",\"password\":\"" + json(secret) + "\",\"remember\":true}";
            collect(leaks, "JSON body", separator, "args: " + body);
            collect(leaks, "JSON body inside a JSON string", separator, "args: {\"method\":\"POST\",\"body\":\"" + json(body) + "\"}");
            collect(leaks, "JSON header map", separator, "args: {\"headers\":{\"X-Api-Key\":\"" + json(secret) + "\"},\"url\":\"https://x\"}");
            collect(leaks, "config write", separator, "args: {\"key\":\"provider.acme.apiKey\",\"value\":\"" + json(secret) + "\"}");
            collect(leaks, "JSON credentials", separator, "args: {\"credentials\": \"" + json(secret) + "\", \"x\": 1}");
            if (!SecretRedactor.redact("args: " + body).contains("\"remember\":true")) lost.add("JSON body with '" + separator + "'");
        }
        assertEquals(List.of(), leaks, "part of a secret survived");
        assertEquals(List.of(), lost, "the mask ran past the value and took what follows it");
    }

    @Test
    void masksWhatTheFirstVersionsMissed() {
        assertMasked("exec: credentials=dXNlcjpwYXNz ./run", "dXNlcjpwYXNz", "credentials=");
        assertMasked("args: {\"credentials\": \"s3cr3tValue\"}", "s3cr3tValue", "credentials");
        assertMasked("exec: HF_TOKEN_READ=hf_abc123 TOKEN_VALUE=tv_456 ./run", "hf_abc123", "HF_TOKEN_READ=");
        assertFalse(SecretRedactor.redact("exec: HF_TOKEN_READ=hf_abc123 TOKEN_VALUE=tv_456 ./run").contains("tv_456"));
        assertMasked("exec: password        =    hunter2x ./run", "hunter2x", "password");
        assertMasked("args: {\\\\\\\"password\\\\\\\":\\\\\\\"deepNested9\\\\\\\"}", "deepNested9", "password");
        assertMasked("exec: curl 'https://x/?a=1' password=ab|cd)ef;gh next", "cd)ef;gh", "password=");
        assertMasked("exec: curl -H \"X-Note: a-sk-proj-abcdefghijklmnopqrstuvwx0123\" https://x", "sk-proj-abcdefghijklmnopqrstuvwx0123", "X-Note");
    }

    @Test
    void aLaterPassFinishesWhatAnEarlierOneStartedAndAQuotedValueCrossesLines() {
        // A bearer token holding a character outside the usual set: every pass must find the value's own end.
        assertMasked("exec: curl -H \"Authorization: Bearer abcdefghijklmnopqrstu!vwx9\" https://x", "vwx9", "https://x");
        assertMasked("exec: mytool --password 'line1\nline2zz' --verbose", "line2zz", "--verbose");
        assertMasked("exec: mytool --password \"ab12\"\"cd34\" --verbose", "cd34", "--verbose");
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
            "TOKEN_LIMIT=8000 tokensUsed: 12 Tokens: 500 tokenizer=jtokkit MAX_TOKENS=4096 ./run",
            "args: {\"key\":\"chat.compression.firstViewMaxTokens\",\"value\":\"6000\"}",
            "Executing tool 'jclaw_api' (id: call_z0, args: {\"action\":\"call\",\"method\":\"GET\",\"path\":\"/api/tasks/4352/runs\",\"query\":{\"limit\":3}})",
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

    @Test
    void masksExactlyTheValueAndKeepsWhatFollowsIt() {
        // assertMasked proves the secret is gone; these pin where the value ends, which is where a tail leaks or a line is eaten.
        var m = SecretRedactor.MASK;
        assertEquals("args: {\"command\":\"curl https://x.example.com/api?apikey\\u003d" + m + "\\u0026page\\u003d2 next\"}",
                SecretRedactor.redact("args: {\"command\":\"curl https://x.example.com/api?apikey\\u003d" + KEY + "\\u0026page\\u003d2 next\"}"));
        assertEquals("args: {\"command\":\"mytool --password \\u0022" + m + "\\u0022 --verbose\"}",
                SecretRedactor.redact("args: {\"command\":\"mytool --password \\u0022abc def\\u0022 --verbose\"}"));
        assertEquals("args: {\"command\":\"curl -H \\\"X-Api-Key: " + m + "\\\" https://x\"}",
                SecretRedactor.redact("args: {\"command\":\"curl -H \\\"X-Api-Key: " + KEY + "\\\" https://x\"}"));
        assertEquals("args: {\"access_token\": " + m + ", \"scope\": \"read\"}",
                SecretRedactor.redact("args: {\"access_token\": 99887766, \"scope\": \"read\"}"));
        assertEquals("exec: mytool --password '" + m + "' --verbose", SecretRedactor.redact("exec: mytool --password 'it'\\''s-a-secret' --verbose"));
        assertEquals("exec: run --token \"" + m + "\" --verbose", SecretRedactor.redact("exec: run --token \"tk1\"\"tk2\" --verbose"));
        assertEquals("exec: curl -u admin:" + m + " https://x", SecretRedactor.redact("exec: curl -u admin:hunter2 https://x"));
        assertEquals("exec: curl https://x/?token=" + m + "&page=2", SecretRedactor.redact("exec: curl https://x/?token=ab\\&cd&page=2"));
        assertEquals("exec: PASSWORD=" + m + " next", SecretRedactor.redact("exec: PASSWORD=ab\\ cd next"));
        assertEquals("exec: curl -H 'Authorization: Bearer " + m + "' https://x\nnext line",
                SecretRedactor.redact("exec: curl -H 'Authorization: Bearer abcdefghij0123456789' https://x\nnext line"));
        assertEquals("args: {\"command\":\"curl https://x.example.com/api?a\\u003d1\\u0026media_api_key\\u003d" + m + "\\u0026page\\u003d2 next\"}",
                SecretRedactor.redact("args: {\"command\":\"curl https://x.example.com/api?a\\u003d1\\u0026media_api_key\\u003dSECRET\\u0026page\\u003d2 next\"}"));
        assertEquals("exec: PASSWORD=" + m + " next", SecretRedactor.redact("exec: PASSWORD=ab&cd next"));
        assertEquals("exec: sh -c \"PASSWORD=" + m + "\" next", SecretRedactor.redact("exec: sh -c \"PASSWORD=abc def\" next"));
        assertEquals("args: {\"command\":\"sh -c \\u0027PASSWORD\\u003d" + m + "\\u0027 next\"}",
                SecretRedactor.redact("args: {\"command\":\"sh -c \\u0027PASSWORD\\u003dabc def\\u0027 next\"}"));
        assertEquals("?token=" + m + "&page=2", SecretRedactor.redact("?token=abc&page=2"));
    }

    @Test
    void aHeaderWithNoQuotesIsMaskedToTheEndOfItsLine() {
        assertEquals("Request headers: Authorization: Bearer " + SecretRedactor.MASK + "\nnext line",
                SecretRedactor.redact("Request headers: Authorization: Bearer abc.def-ghi_jkl012345\nnext line"));
        assertEquals("Request headers: Cookie: " + SecretRedactor.MASK + "\nAccept: */*",
                SecretRedactor.redact("Request headers: Cookie: session=abc123; theme=dark\nAccept: */*"));
    }

    @Test
    void aFragmentEndsAValueInAUrlButBelongsToItInAFormBody() {
        // A # inside a query value arrives as %23, so a raw one starts the fragment; a form body has no fragment.
        assertEquals("GET https://x.test/cb?a=1&token=" + SecretRedactor.MASK + "#frag", SecretRedactor.redact("GET https://x.test/cb?a=1&token=abc123#frag"));
        assertEquals("GET https://x.test/cb?token=" + SecretRedactor.MASK + "#frag", SecretRedactor.redact("GET https://x.test/cb?token=abc123#frag"));
        assertEquals("api?a\\u003d1\\u0026media_api_key\\u003d" + SecretRedactor.MASK + "#frag",
                SecretRedactor.redact("api?a\\u003d1\\u0026media_api_key\\u003dSECRET#frag"));
        assertEquals("body a=1&token=" + SecretRedactor.MASK, SecretRedactor.redact("body a=1&token=abc123#frag"));
    }

    @Test
    void aFieldIsInAUrlQueryWhenItsQuestionMarkIsWithin512Characters() {
        var query = "q=" + "v".repeat(509);   // puts the ? exactly 512 characters behind the &
        assertEquals("https://x.test/p?" + query + "&token=" + SecretRedactor.MASK + "#frag",
                SecretRedactor.redact("https://x.test/p?" + query + "&token=abc#frag"));
        assertEquals("https://x.test/p?v" + query + "&token=" + SecretRedactor.MASK, SecretRedactor.redact("https://x.test/p?v" + query + "&token=abc#frag"));
    }

    @Test
    void aFieldNameIsTracedBackUpTo128CharactersToTheAmpersandBeforeIt() {
        // Past the bound the value runs to the end of the word: too much is masked, never too little.
        var name = "p".repeat(128) + "api_key";
        assertEquals("x=1&" + name + "=" + SecretRedactor.MASK + "&next=1", SecretRedactor.redact("x=1&" + name + "=SECRET&next=1"));
        assertEquals("x=1&p" + name + "=" + SecretRedactor.MASK, SecretRedactor.redact("x=1&p" + name + "=SECRET&next=1"));
    }

    @Test
    void anEmptyValueHasNothingToMask() {
        for (var line : List.of("GET https://x.test/?api_key=&page=2", "args: {\"password\": \"\", \"user\": \"bob\"}")) {
            assertEquals(line, SecretRedactor.redact(line));
        }
    }

    @Test
    void anEmptyAssignmentMasksTheWordAfterIt() {
        // The text cannot say whether that word is the value, so it is masked, even on the next line.
        assertEquals("exec: export API_KEY= " + SecretRedactor.MASK + " run", SecretRedactor.redact("exec: export API_KEY= && run"));
        assertEquals("password=\n" + SecretRedactor.MASK + " line", SecretRedactor.redact("password=\nnext line"));
    }

    @Test
    void aQuoteLeftOpenOnOneLineDoesNotChangeHowTheNextIsRead() {
        assertEquals("echo \"unterminated\nsay \"hi\" password=" + SecretRedactor.MASK + " def",
                SecretRedactor.redact("echo \"unterminated\nsay \"hi\" password=abc def"));
    }

    @Test
    void aSecretIsMaskedInJsonNestedFiveLevelsDeep() {
        // Five levels put 31 backslashes before the inner quotes; six (63) is past the 32 the redactor reads and leaks (JF-18).
        var inner = "{\"password\":\"%s\",\"next\":1}";
        assertEquals(nested(5, inner.formatted(SecretRedactor.MASK)), SecretRedactor.redact(nested(5, inner.formatted("s3cret"))));
        var atTheLimit = "{%1$spassword%1$s:%1$s%2$s%1$s,%1$snext%1$s:1}";
        var q = "\\".repeat(32) + "\"";
        assertEquals(atTheLimit.formatted(q, SecretRedactor.MASK), SecretRedactor.redact(atTheLimit.formatted(q, "s3cret")));
    }

    /** {@code s} serialized as a JSON string value {@code levels} times over, the way logged arguments nest. */
    private static String nested(int levels, String s) {
        for (int i = 0; i < levels; i++) {
            s = "{\"args\":\"" + json(s) + "\"}";
        }
        return s;
    }
}
