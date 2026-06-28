package services;

import com.google.gson.JsonParser;
import okhttp3.Request;
import play.Play;
import utils.HttpFactories;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Downloads a skill's files directly from a public GitHub repo into a staged
 * directory — no skills.sh / mastra-ai involvement (the catalog only points at
 * the repo; delivery is pure GitHub). Resolves the default branch, walks the
 * repo tree to locate the directory holding the skill's {@code SKILL.md}, and
 * downloads every file under it via {@code raw.githubusercontent.com}.
 *
 * <h2>Trust boundary</h2>
 * The {@code owner/repo/skillId} come from untrusted catalog rows, so the host
 * is pinned to GitHub (api + raw) and every write is path-contained via
 * {@link AgentService#resolveContained}. An optional
 * {@value #TOKEN_PROPERTY} raises the unauthenticated GitHub API rate limit.
 */
public final class GithubSkillFetcher {

    private static final String CATEGORY = "skills";
    private static final String API = "https://api.github.com";
    private static final String RAW = "https://raw.githubusercontent.com";
    private static final String TOKEN_PROPERTY = "jclaw.skills.catalog.github.token";
    private static final String SKILL_MD = "SKILL.md";
    private static final String KEY_DEFAULT_BRANCH = "default_branch";
    private static final int TIMEOUT_SECONDS = 60;

    private GithubSkillFetcher() {}

    public record FetchResult(boolean ok, int fileCount, String message) {}

    /**
     * Fetch {@code owner/repo}'s {@code skillId} skill into {@code stagedDir},
     * with SKILL.md at the staged root. Never throws — failures return
     * {@code ok=false} with a human-readable message.
     */
    public static FetchResult fetch(String owner, String repo, String skillId, Path stagedDir) {
        String branch;
        try {
            branch = defaultBranch(owner, repo);
        } catch (IOException e) {
            return new FetchResult(false, 0, "could not resolve repo " + owner + "/" + repo + ": " + e.getMessage());
        }

        List<String> tree;
        try {
            tree = repoBlobPaths(owner, repo, branch);
        } catch (IOException e) {
            return new FetchResult(false, 0, "could not read repo tree: " + e.getMessage());
        }

        var prefix = locateSkillDir(tree, skillId);
        if (prefix == null) {
            return new FetchResult(false, 0,
                    "could not find skill '%s' in %s/%s".formatted(skillId, owner, repo));
        }

        int n = 0;
        for (var path : tree) {
            if (!underPrefix(path, prefix)) continue;
            var rel = relativize(path, prefix);
            try {
                var dest = AgentService.resolveContained(stagedDir, rel);
                if (dest == null) {
                    EventLogger.warn(CATEGORY, "Import: skipping path escaping staged dir: " + path);
                    continue;
                }
                Files.createDirectories(dest.getParent());
                Files.write(dest, downloadRaw(owner, repo, branch, path));
                n++;
            } catch (IOException e) {
                EventLogger.warn(CATEGORY, "Import: failed to download %s: %s".formatted(path, e.getMessage()));
            }
        }
        if (n == 0) return new FetchResult(false, 0, "no files downloaded for skill '" + skillId + "'");
        return new FetchResult(true, n, "fetched %d files".formatted(n));
    }

    // --- GitHub API ---

    private static String defaultBranch(String owner, String repo) throws IOException {
        var body = getString(API + "/repos/" + owner + "/" + repo);
        var json = JsonParser.parseString(body).getAsJsonObject();
        if (json.has(KEY_DEFAULT_BRANCH) && !json.get(KEY_DEFAULT_BRANCH).isJsonNull()) {
            return json.get(KEY_DEFAULT_BRANCH).getAsString();
        }
        return "main";
    }

    /** Recursive tree of blob (file) paths for the branch. */
    private static List<String> repoBlobPaths(String owner, String repo, String branch) throws IOException {
        var body = getString(API + "/repos/" + owner + "/" + repo + "/git/trees/" + branch + "?recursive=1");
        var json = JsonParser.parseString(body).getAsJsonObject();
        var out = new ArrayList<String>();
        if (json.has("tree") && json.get("tree").isJsonArray()) {
            for (var el : json.getAsJsonArray("tree")) {
                var node = el.getAsJsonObject();
                if (node.has("type") && "blob".equals(node.get("type").getAsString())
                        && node.has("path")) {
                    out.add(node.get("path").getAsString());
                }
            }
        }
        if (json.has("truncated") && json.get("truncated").getAsBoolean()) {
            EventLogger.warn(CATEGORY, "Import: repo tree for %s/%s is truncated; some files may be missing"
                    .formatted(owner, repo));
        }
        return out;
    }

    /**
     * Find the directory that holds the skill's SKILL.md. Prefers a directory
     * whose basename equals {@code skillId}; falls back to the sole SKILL.md when
     * the repo contains exactly one. Returns the dir prefix ("" = repo root), or
     * null when no SKILL.md matches.
     */
    static String locateSkillDir(List<String> blobPaths, String skillId) {
        String onlySkillMd = null;
        int skillMdCount = 0;
        for (var path : blobPaths) {
            if (!isSkillMd(path)) continue;
            skillMdCount++;
            var dir = parentDir(path);
            if (basename(dir).equals(skillId)) return dir;
            onlySkillMd = dir;
        }
        return skillMdCount == 1 ? onlySkillMd : null;
    }

    private static boolean isSkillMd(String path) {
        return path.equals(SKILL_MD) || path.endsWith("/" + SKILL_MD);
    }

    private static boolean underPrefix(String path, String prefix) {
        if (prefix.isEmpty()) {
            // Skill at repo root: take SKILL.md + the two convention subtrees only,
            // so we don't drag the whole repo (README, .github, src, ...) in.
            return path.equals(SKILL_MD) || path.startsWith("tools/") || path.startsWith("credentials/");
        }
        return path.equals(prefix + "/" + SKILL_MD) || path.startsWith(prefix + "/");
    }

    private static String relativize(String path, String prefix) {
        return prefix.isEmpty() ? path : path.substring(prefix.length() + 1);
    }

    private static byte[] downloadRaw(String owner, String repo, String branch, String path) throws IOException {
        var call = HttpFactories.general().newCall(authorized(
                new Request.Builder().url(RAW + "/" + owner + "/" + repo + "/" + branch + "/" + path).get()).build());
        call.timeout().timeout(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        try (var resp = call.execute()) {
            var rb = resp.body();
            if (!resp.isSuccessful()) {
                throw new IOException("HTTP " + resp.code() + " for " + path);
            }
            return rb.bytes();
        }
    }

    private static String getString(String url) throws IOException {
        var call = HttpFactories.general().newCall(authorized(new Request.Builder().url(url).get()).build());
        call.timeout().timeout(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        try (var resp = call.execute()) {
            var rb = resp.body();
            if (!resp.isSuccessful()) {
                throw new IOException("HTTP " + resp.code() + " for " + url);
            }
            return rb.string();
        }
    }

    /** Attach a GitHub token when configured, to raise the API rate limit. */
    private static Request.Builder authorized(Request.Builder b) {
        var token = Play.configuration.getProperty(TOKEN_PROPERTY);
        if (token != null && !token.isBlank()) {
            b.header("Authorization", "Bearer " + token.trim());
        }
        return b;
    }

    private static String parentDir(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? "" : path.substring(0, i);
    }

    private static String basename(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }
}
