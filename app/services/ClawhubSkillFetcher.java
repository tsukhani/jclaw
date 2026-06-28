package services;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.HttpUrl;
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
 * Downloads a skill's files from the live ClawHub registry (clawhub.ai) into a
 * staged directory — the dynamic-catalog analog of {@link GithubSkillFetcher}.
 *
 * <h2>How ClawHub serves files</h2>
 * ClawHub has no file-list/tarball endpoint; it serves files one at a time via
 * {@code GET /api/v1/skills/{slug}/file?path=...}. The manifest lives on the
 * version-detail endpoint: {@code GET /api/v1/skills/{slug}/versions/{version}}
 * returns {@code version.files[].path}. So: resolve the latest version, read its
 * file list, download each file. Host-pinned to clawhub; every write is
 * path-contained via {@link AgentService#resolveContained}.
 */
public final class ClawhubSkillFetcher {

    private static final String CATEGORY = "skills";
    private static final String BASE_URL_PROPERTY = "jclaw.skills.clawhub.url";
    private static final String DEFAULT_BASE_URL = "https://clawhub.ai";
    private static final int TIMEOUT_SECONDS = 60;

    private ClawhubSkillFetcher() {}

    public record FetchResult(boolean ok, int fileCount, String message) {}

    /**
     * Fetch the ClawHub skill {@code slug} (its latest version) into
     * {@code stagedDir}. Never throws — failures return {@code ok=false}.
     */
    public static FetchResult fetch(String slug, Path stagedDir) {
        if (slug == null || slug.isBlank()) return new FetchResult(false, 0, "missing clawhub slug");

        String version;
        List<String> paths;
        try {
            version = latestVersion(getJson(url("api/v1/skills/" + slug)));
            if (version == null) return new FetchResult(false, 0, "no published version for clawhub skill '" + slug + "'");
            paths = parseFilePaths(getJson(url("api/v1/skills/" + slug + "/versions/" + version)));
        } catch (IOException e) {
            return new FetchResult(false, 0, "could not read clawhub skill '" + slug + "': " + e.getMessage());
        }
        if (paths.isEmpty()) return new FetchResult(false, 0, "clawhub skill '" + slug + "' lists no files");

        int n = 0;
        for (var path : paths) {
            try {
                var dest = AgentService.resolveContained(stagedDir, path);
                if (dest == null) {
                    EventLogger.warn(CATEGORY, "Import: skipping path escaping staged dir: " + path);
                    continue;
                }
                Files.createDirectories(dest.getParent());
                var fileUrl = base().addPathSegments("api/v1/skills/" + slug + "/file")
                        .addQueryParameter("path", path)
                        .addQueryParameter("version", version)
                        .build();
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
        if (detail == null || !detail.has("latestVersion") || !detail.get("latestVersion").isJsonObject()) return null;
        var lv = detail.getAsJsonObject("latestVersion");
        return lv.has("version") && !lv.get("version").isJsonNull() ? lv.get("version").getAsString() : null;
    }

    /** File paths from a {@code /skills/{slug}/versions/{version}} response ({@code version.files[].path}). */
    public static List<String> parseFilePaths(JsonObject versionDetail) {
        var out = new ArrayList<String>();
        if (versionDetail == null || !versionDetail.has("version") || !versionDetail.get("version").isJsonObject()) return out;
        var version = versionDetail.getAsJsonObject("version");
        if (!version.has("files") || !version.get("files").isJsonArray()) return out;
        for (var el : version.getAsJsonArray("files")) {
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
            if (!resp.isSuccessful() || b == null) throw new IOException("clawhub HTTP " + resp.code() + " for " + u.encodedPath());
            return JsonParser.parseString(b.string()).getAsJsonObject();
        }
    }

    private static byte[] getBytes(HttpUrl u) throws IOException {
        var call = HttpFactories.general().newCall(new Request.Builder().url(u).get().build());
        call.timeout().timeout(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        try (var resp = call.execute()) {
            var b = resp.body();
            if (!resp.isSuccessful() || b == null) throw new IOException("clawhub file HTTP " + resp.code());
            return b.bytes();
        }
    }

    private static HttpUrl url(String segments) {
        return base().addPathSegments(segments).build();
    }

    private static HttpUrl.Builder base() {
        var configured = Play.configuration.getProperty(BASE_URL_PROPERTY);
        var b = (configured != null && !configured.isBlank()) ? configured.trim() : DEFAULT_BASE_URL;
        var u = HttpUrl.parse(b);
        if (u == null) throw new IllegalStateException("invalid clawhub base url: " + b);
        return u.newBuilder();
    }
}
