import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;
import services.GithubSkillFetcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * Behavior coverage for {@link GithubSkillFetcher}: default-branch resolution,
 * recursive-tree parsing, skill-directory location (basename match vs sole
 * SKILL.md fallback vs ambiguity), root-level convention filtering,
 * path-containment of staged writes, per-file failure tolerance, HTTP error
 * mapping, and the optional token header.
 *
 * <p>Uses {@code mockwebserver3} (same pattern as {@code WhisperModelManagerTest})
 * so the fetcher makes real OkHttp calls to a server we control, pointed at it
 * via the {@code jclaw.skills.catalog.github.{api,raw}.url} config seam (the
 * GitHub analog of {@code ClawhubSkillFetcher}'s BASE_URL_PROPERTY). Only this
 * test class reads or writes those properties, so the concurrently running
 * functional lane is unaffected.
 */
class GithubSkillFetcherTest extends UnitTest {

    private static final String API_URL_PROPERTY = "jclaw.skills.catalog.github.api.url";
    private static final String RAW_URL_PROPERTY = "jclaw.skills.catalog.github.raw.url";
    private static final String TOKEN_PROPERTY = "jclaw.skills.catalog.github.token";

    private MockWebServer server;
    private Path stagedDir;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        var base = baseUrlOf(server);
        Play.configuration.setProperty(API_URL_PROPERTY, base);
        Play.configuration.setProperty(RAW_URL_PROPERTY, base);
        stagedDir = Files.createTempDirectory("github-skill-fetcher-test-");
    }

    @AfterEach
    void tearDown() throws Exception {
        Play.configuration.remove(API_URL_PROPERTY);
        Play.configuration.remove(RAW_URL_PROPERTY);
        Play.configuration.remove(TOKEN_PROPERTY);
        server.close();
        deleteRecursive(stagedDir);
    }

    // ==================== happy path ====================

    @Test
    void fetchStagesSkillFilesRelativeToSkillDir() throws Exception {
        server.enqueue(json(200, "{\"default_branch\":\"trunk\"}"));
        server.enqueue(json(200, """
                {"tree":[
                  {"path":"skills/my-skill","type":"tree"},
                  {"path":"skills/my-skill/SKILL.md","type":"blob"},
                  {"path":"skills/my-skill/tools/run.sh","type":"blob"},
                  {"path":"README.md","type":"blob"}
                ],"truncated":false}
                """));
        server.enqueue(raw(200, "# My Skill"));
        server.enqueue(raw(200, "#!/bin/sh"));

        var result = GithubSkillFetcher.fetch("acme", "toolbox", "my-skill", stagedDir);

        assertTrue(result.ok(), "happy path must succeed: " + result.message());
        assertEquals(2, result.fileCount(), "SKILL.md + tools/run.sh; tree node and README excluded");
        assertEquals("fetched 2 files", result.message());
        // Files land relative to the skill dir, SKILL.md at the staged root.
        assertEquals("# My Skill", Files.readString(stagedDir.resolve("SKILL.md")));
        assertEquals("#!/bin/sh", Files.readString(stagedDir.resolve("tools/run.sh")));
        assertFalse(Files.exists(stagedDir.resolve("README.md")), "file outside the skill dir must not be staged");

        // URL construction: repo detail, tree on the resolved branch, raw per file.
        assertEquals("/repos/acme/toolbox", server.takeRequest().getUrl().encodedPath());
        var tree = server.takeRequest().getUrl();
        assertEquals("/repos/acme/toolbox/git/trees/trunk", tree.encodedPath(),
                "tree must be fetched on the repo's default branch");
        assertEquals("recursive=1", tree.encodedQuery());
        assertEquals("/acme/toolbox/trunk/skills/my-skill/SKILL.md", server.takeRequest().getUrl().encodedPath());
        assertEquals("/acme/toolbox/trunk/skills/my-skill/tools/run.sh", server.takeRequest().getUrl().encodedPath());
    }

    @Test
    void fetchFallsBackToMainWhenDefaultBranchIsNull() throws Exception {
        server.enqueue(json(200, "{\"default_branch\":null}"));
        server.enqueue(json(200, treeOf("git/SKILL.md")));
        server.enqueue(raw(200, "doc"));

        var result = GithubSkillFetcher.fetch("o", "r", "git", stagedDir);

        assertTrue(result.ok(), result.message());
        server.takeRequest(); // repo detail
        assertEquals("/repos/o/r/git/trees/main", server.takeRequest().getUrl().encodedPath(),
                "null default_branch must fall back to 'main'");
        assertEquals("/o/r/main/git/SKILL.md", server.takeRequest().getUrl().encodedPath());
    }

    // ==================== skill-directory location ====================

    @Test
    void fetchPrefersDirectoryWhoseBasenameMatchesSkillId() throws Exception {
        server.enqueue(json(200, "{}")); // no default_branch key -> "main"
        server.enqueue(json(200, treeOf("other/SKILL.md", "skills/target/SKILL.md")));
        server.enqueue(raw(200, "target doc"));

        var result = GithubSkillFetcher.fetch("o", "r", "target", stagedDir);

        assertTrue(result.ok(), result.message());
        assertEquals(1, result.fileCount(), "only the basename-matching dir's files are fetched");
        assertEquals("target doc", Files.readString(stagedDir.resolve("SKILL.md")));
        assertEquals(3, server.getRequestCount(), "other/SKILL.md must never be downloaded");
        server.takeRequest();
        server.takeRequest();
        assertEquals("/o/r/main/skills/target/SKILL.md", server.takeRequest().getUrl().encodedPath());
    }

    @Test
    void fetchFallsBackToSoleSkillMdWhenBasenameDiffers() throws Exception {
        server.enqueue(json(200, "{}"));
        server.enqueue(json(200, treeOf("pkg/cool-skill/SKILL.md", "pkg/cool-skill/extra.md")));
        server.enqueue(raw(200, "doc"));
        server.enqueue(raw(200, "extra"));

        var result = GithubSkillFetcher.fetch("o", "r", "different-name", stagedDir);

        assertTrue(result.ok(), "sole SKILL.md must be used even when the dir name differs: " + result.message());
        assertEquals(2, result.fileCount());
        assertEquals("extra", Files.readString(stagedDir.resolve("extra.md")));
    }

    @Test
    void fetchFailsWhenMultipleSkillMdsAndNoneMatchesSkillId() {
        server.enqueue(json(200, "{}"));
        server.enqueue(json(200, treeOf("a/SKILL.md", "b/SKILL.md")));

        var result = GithubSkillFetcher.fetch("acme", "toolbox", "c", stagedDir);

        assertFalse(result.ok(), "ambiguous SKILL.md set must not guess");
        assertEquals(0, result.fileCount());
        assertEquals("could not find skill 'c' in acme/toolbox", result.message());
        assertEquals(2, server.getRequestCount(), "nothing may be downloaded on ambiguity");
    }

    @Test
    void fetchFailsWhenTreeHasNoTreeArray() {
        server.enqueue(json(200, "{}"));
        server.enqueue(json(200, "{\"sha\":\"abc\"}")); // no "tree" key -> zero blobs

        var result = GithubSkillFetcher.fetch("o", "r", "x", stagedDir);

        assertFalse(result.ok());
        assertTrue(result.message().contains("could not find skill 'x'"),
                "empty tree must surface as skill-not-found: " + result.message());
    }

    // ==================== root-level skill filtering ====================

    @Test
    void rootLevelSkillTakesOnlyConventionSubtrees() throws Exception {
        server.enqueue(json(200, "{}"));
        server.enqueue(json(200, treeOf(
                "SKILL.md", "tools/run.sh", "credentials/key.txt", "README.md", "src/Main.java")));
        server.enqueue(raw(200, "root doc"));
        server.enqueue(raw(200, "run"));
        server.enqueue(raw(200, "key"));

        var result = GithubSkillFetcher.fetch("o", "r", "whatever", stagedDir);

        assertTrue(result.ok(), result.message());
        assertEquals(3, result.fileCount(), "SKILL.md + tools/ + credentials/ only");
        assertEquals("root doc", Files.readString(stagedDir.resolve("SKILL.md")));
        assertEquals("run", Files.readString(stagedDir.resolve("tools/run.sh")));
        assertEquals("key", Files.readString(stagedDir.resolve("credentials/key.txt")));
        assertFalse(Files.exists(stagedDir.resolve("README.md")), "repo README must not be dragged in");
        assertFalse(Files.exists(stagedDir.resolve("src")), "repo sources must not be dragged in");
        assertEquals(5, server.getRequestCount(), "README/src must never even be requested");
    }

    // ==================== containment ====================

    @Test
    void pathEscapingStagedDirIsSkippedNotWritten() {
        server.enqueue(json(200, "{}"));
        server.enqueue(json(200, treeOf("skill/SKILL.md", "skill/../../evil.txt")));
        server.enqueue(raw(200, "doc"));

        var result = GithubSkillFetcher.fetch("o", "r", "skill", stagedDir);

        assertTrue(result.ok(), "escape attempt must be skipped, not fail the fetch: " + result.message());
        assertEquals(1, result.fileCount());
        assertFalse(Files.exists(stagedDir.getParent().resolve("evil.txt")),
                "traversal path must never be written outside the staged dir");
        assertFalse(Files.exists(stagedDir.getParent().getParent().resolve("evil.txt")));
        assertEquals(3, server.getRequestCount(), "the escaping path must be skipped before any download");
    }

    // ==================== HTTP error mapping ====================

    @Test
    void fetchReportsRepoLookup404WithoutAuthHeaderByDefault() throws Exception {
        server.enqueue(json(404, "{\"message\":\"Not Found\"}"));

        var result = GithubSkillFetcher.fetch("acme", "gone", "x", stagedDir);

        assertFalse(result.ok());
        assertEquals(0, result.fileCount());
        assertTrue(result.message().startsWith("could not resolve repo acme/gone:"),
                "404 must map to the repo-resolution failure: " + result.message());
        assertTrue(result.message().contains("HTTP 404"), result.message());
        assertNull(server.takeRequest().getHeaders().get("Authorization"),
                "no token configured -> no Authorization header");
    }

    @Test
    void fetchReportsTreeRateLimitAsTreeReadFailure() {
        server.enqueue(json(200, "{\"default_branch\":\"main\"}"));
        server.enqueue(json(403, "{\"message\":\"API rate limit exceeded\"}")); // GitHub rate-limit status

        var result = GithubSkillFetcher.fetch("o", "r", "x", stagedDir);

        assertFalse(result.ok());
        assertTrue(result.message().startsWith("could not read repo tree:"), result.message());
        assertTrue(result.message().contains("HTTP 403"), result.message());
    }

    @Test
    void fetchReportsConnectionFailureAsRepoResolutionFailure() {
        server.close(); // nothing listening -> connect fails fast

        var result = GithubSkillFetcher.fetch("acme", "toolbox", "x", stagedDir);

        assertFalse(result.ok(), "network failure must be caught, not thrown");
        assertEquals(0, result.fileCount());
        assertTrue(result.message().startsWith("could not resolve repo acme/toolbox:"), result.message());
    }

    // ==================== per-file failure tolerance ====================

    @Test
    void failedFileDownloadIsSkippedButOthersStillStage() {
        server.enqueue(json(200, "{}"));
        server.enqueue(json(200, treeOf("s/SKILL.md", "s/broken.md")));
        server.enqueue(raw(200, "doc"));
        server.enqueue(raw(404, "gone"));

        var result = GithubSkillFetcher.fetch("o", "r", "s", stagedDir);

        assertTrue(result.ok(), "one failed file must not fail the fetch: " + result.message());
        assertEquals(1, result.fileCount());
        assertEquals("fetched 1 files", result.message());
        assertTrue(Files.exists(stagedDir.resolve("SKILL.md")));
        assertFalse(Files.exists(stagedDir.resolve("broken.md")), "failed download must leave no file behind");
    }

    @Test
    void fetchFailsWhenEveryFileDownloadFails() {
        server.enqueue(json(200, "{}"));
        server.enqueue(json(200, treeOf("s/SKILL.md")));
        server.enqueue(raw(500, "boom"));

        var result = GithubSkillFetcher.fetch("o", "r", "s", stagedDir);

        assertFalse(result.ok());
        assertEquals(0, result.fileCount());
        assertEquals("no files downloaded for skill 's'", result.message());
    }

    // ==================== tree edge cases ====================

    @Test
    void truncatedTreeStillFetchesWhatIsListed() {
        server.enqueue(json(200, "{}"));
        server.enqueue(json(200, """
                {"tree":[{"path":"s/SKILL.md","type":"blob"}],"truncated":true}
                """));
        server.enqueue(raw(200, "doc"));

        var result = GithubSkillFetcher.fetch("o", "r", "s", stagedDir);

        assertTrue(result.ok(), "truncation warns but must not abort: " + result.message());
        assertEquals(1, result.fileCount());
    }

    @Test
    void malformedRepoJsonCurrentlyEscapesAsRuntimeException() {
        // FLAG: the fetch() Javadoc says "Never throws", but Gson's
        // JsonSyntaxException is a RuntimeException, not the IOException the
        // catch blocks handle — so a malformed API body escapes the contract.
        // This test documents the current observable behavior; if fetch() is
        // later hardened to return ok=false here, update this assertion.
        server.enqueue(json(200, "not-json{"));

        assertThrows(RuntimeException.class,
                () -> GithubSkillFetcher.fetch("o", "r", "x", stagedDir),
                "malformed repo JSON currently propagates out of fetch()");
    }

    // ==================== auth header ====================

    @Test
    void configuredTokenIsTrimmedAndSentAsBearer() throws Exception {
        Play.configuration.setProperty(TOKEN_PROPERTY, "  ghp_test123  ");
        server.enqueue(json(404, "{}")); // one request is enough to observe the header

        GithubSkillFetcher.fetch("o", "r", "x", stagedDir);

        assertEquals("Bearer ghp_test123", server.takeRequest().getHeaders().get("Authorization"),
                "token must be trimmed and sent as a Bearer header");
    }

    @Test
    void blankTokenSendsNoAuthorizationHeader() throws Exception {
        Play.configuration.setProperty(TOKEN_PROPERTY, "   ");
        server.enqueue(json(404, "{}"));

        GithubSkillFetcher.fetch("o", "r", "x", stagedDir);

        assertNull(server.takeRequest().getHeaders().get("Authorization"),
                "blank token must not produce an Authorization header");
    }

    // ==================== helpers ====================

    /** Recursive-tree response with the given paths as type=blob entries. */
    private static String treeOf(String... blobPaths) {
        var sb = new StringBuilder("{\"tree\":[");
        for (int i = 0; i < blobPaths.length; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"path\":\"").append(blobPaths[i]).append("\",\"type\":\"blob\"}");
        }
        return sb.append("],\"truncated\":false}").toString();
    }

    private static MockResponse json(int code, String body) {
        return new MockResponse.Builder()
                .code(code)
                .addHeader("Content-Type", "application/json")
                .body(body)
                .build();
    }

    private static MockResponse raw(int code, String body) {
        return new MockResponse.Builder()
                .code(code)
                .addHeader("Content-Type", "text/plain")
                .body(body)
                .build();
    }

    private static String baseUrlOf(MockWebServer server) {
        var url = server.url("/").toString();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static void deleteRecursive(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }
}
