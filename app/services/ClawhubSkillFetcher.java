package services;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.HttpUrl;
import okhttp3.Request;
import play.Play;
import utils.HttpFactories;
import utils.WorkspacePathGuard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Downloads a skill's files from the live ClawHub registry (clawhub.ai) into a
 * staged directory — the dynamic-catalog analog of {@link GithubSkillFetcher}.
 *
 * <h2>How ClawHub serves files</h2>
 * ClawHub has no file-list/tarball endpoint; it serves files one at a time via
 * {@code GET /api/v1/skills/{slug}/file?path=...}. The manifest lives on the
 * version-detail endpoint: {@code GET /api/v1/skills/{slug}/versions/{version}}
 * returns {@code version.files[].path}. So: resolve the latest version, read its
 * file list, download each file. Host-pinned to clawhub; every write is
 * path-contained via {@link WorkspacePathGuard#resolveContained}.
 *
 * <h2>Owner-scoped slugs</h2>
 * ClawHub slugs are <em>not</em> globally unique — several authors can publish
 * the same slug, and a bare {@code /skills/{slug}} request then 404s with
 * {@code AMBIGUOUS_SKILL_SLUG}. A {@code ?owner=} query param disambiguates (and
 * is harmless on unique slugs), so every endpoint is owner-qualified whenever the
 * caller knows the owner (search rows do; browse rows don't).
 */
public final class ClawhubSkillFetcher {

    private static final String CATEGORY = "skills";
    private static final String BASE_URL_PROPERTY = "jclaw.skills.clawhub.url";
    private static final String DEFAULT_BASE_URL = "https://clawhub.ai";
    private static final int TIMEOUT_SECONDS = 60;
    private static final String SKILLS_PATH = "api/v1/skills/";
    private static final String KEY_VERSION = "version";
    private static final String KEY_LATEST_VERSION = "latestVersion";
    private static final String KEY_FILES = "files";

    private ClawhubSkillFetcher() {}

    public record FetchResult(boolean ok, int fileCount, String message) {}

    /**
     * Fetch the ClawHub skill {@code slug} (its latest version) into
     * {@code stagedDir}, owner-qualified by {@code owner} when non-blank to
     * disambiguate same-slug skills. Never throws — failures return
     * {@code ok=false}.
     */
    public static FetchResult fetch(String slug, String owner, Path stagedDir) {
        if (slug == null || slug.isBlank()) return new FetchResult(false, 0, "missing clawhub slug");

        String version;
        List<String> paths;
        try {
            version = latestVersion(getJson(skillApi(owner, SKILLS_PATH + slug)));
            if (version == null) return new FetchResult(false, 0, "no published version for clawhub skill '" + slug + "'");
            paths = parseFilePaths(getJson(skillApi(owner, SKILLS_PATH + slug + "/versions/" + version)));
        } catch (IOException e) {
            return new FetchResult(false, 0, "could not read clawhub skill '" + slug + "': " + e.getMessage());
        }
        if (paths.isEmpty()) return new FetchResult(false, 0, "clawhub skill '" + slug + "' lists no files");

        int n = 0;
        for (var path : paths) {
            try {
                var dest = WorkspacePathGuard.resolveContained(stagedDir, path);
                if (dest == null) {
                    EventLogger.warn(CATEGORY, "Import: skipping path escaping staged dir: " + path);
                    continue;
                }
                Files.createDirectories(dest.getParent());
                var fileUrl = skillApi(owner, SKILLS_PATH + slug + "/file", "path", path, KEY_VERSION, version);
                Files.write(dest, getBytes(fileUrl));
                n++;
            } catch (IOException e) {
                EventLogger.warn(CATEGORY, "Import: failed to download %s: %s".formatted(path, e.getMessage()));
            }
        }
        if (n == 0) return new FetchResult(false, 0, "no files downloaded for clawhub skill '" + slug + "'");
        return new FetchResult(true, n, "fetched %d files".formatted(n));
    }

    // --- parsing (public for tests) ---

    /** Latest published version string from a {@code /skills/{slug}} detail response. */
    public static String latestVersion(JsonObject detail) {
        if (detail == null || !detail.has(KEY_LATEST_VERSION) || !detail.get(KEY_LATEST_VERSION).isJsonObject()) return null;
        var lv = detail.getAsJsonObject(KEY_LATEST_VERSION);
        return lv.has(KEY_VERSION) && !lv.get(KEY_VERSION).isJsonNull() ? lv.get(KEY_VERSION).getAsString() : null;
    }

    /** File paths from a {@code /skills/{slug}/versions/{version}} response ({@code version.files[].path}). */
    public static List<String> parseFilePaths(JsonObject versionDetail) {
        var out = new ArrayList<String>();
        if (versionDetail == null || !versionDetail.has(KEY_VERSION) || !versionDetail.get(KEY_VERSION).isJsonObject()) return out;
        var version = versionDetail.getAsJsonObject(KEY_VERSION);
        if (!version.has(KEY_FILES) || !version.get(KEY_FILES).isJsonArray()) return out;
        for (var el : version.getAsJsonArray(KEY_FILES)) {
            if (!el.isJsonObject()) continue;
            var o = el.getAsJsonObject();
            if (o.has("path") && !o.get("path").isJsonNull()) out.add(o.get("path").getAsString());
        }
        return out;
    }

    private static JsonObject getJson(HttpUrl u) throws IOException {
        var call = HttpFactories.general().newCall(
                new Request.Builder().url(u).header("Accept", "application/json").get().build());
        call.timeout().timeout(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        try (var resp = call.execute()) {
            var b = resp.body();
            if (!resp.isSuccessful()) throw new IOException("clawhub HTTP " + resp.code() + " for " + u.encodedPath());
            return JsonParser.parseString(b.string()).getAsJsonObject();
        }
    }

    private static byte[] getBytes(HttpUrl u) throws IOException {
        var call = HttpFactories.general().newCall(new Request.Builder().url(u).get().build());
        call.timeout().timeout(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        try (var resp = call.execute()) {
            var b = resp.body();
            if (!resp.isSuccessful()) throw new IOException("clawhub file HTTP " + resp.code());
            return b.bytes();
        }
    }

    /**
     * Build a clawhub skill-API URL with optional {@code key,value,...} query
     * pairs, owner-qualifying it via {@code ?owner=} when {@code owner} is set
     * (clawhub slugs are owner-scoped; the param disambiguates same-slug skills).
     */
    private static HttpUrl skillApi(String owner, String segments, String... queryKv) {
        var b = base().addPathSegments(segments);
        for (int i = 0; i + 1 < queryKv.length; i += 2) b.addQueryParameter(queryKv[i], queryKv[i + 1]);
        if (owner != null && !owner.isBlank()) b.addQueryParameter("owner", owner);
        return b.build();
    }

    private static HttpUrl.Builder base() {
        var configured = Play.configuration.getProperty(BASE_URL_PROPERTY);
        var b = (configured != null && !configured.isBlank()) ? configured.trim() : DEFAULT_BASE_URL;
        var u = HttpUrl.parse(b);
        if (u == null) throw new IllegalStateException("invalid clawhub base url: " + b);
        return u.newBuilder();
    }
}
