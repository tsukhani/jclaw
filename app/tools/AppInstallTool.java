package tools;

import agents.ToolAction;
import agents.ToolRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.Agent;
import play.Play;
import services.WorkspaceFiles;
import utils.GsonHolder;
import utils.JsonArgs;
import utils.WorkspacePathGuard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * JCLAW-768: sandbox-safe lifecycle tool for hosted apps (the Apps page's
 * static sites under {@code public/apps/<slug>/}).
 *
 * <p>Agent filesystem tools are confined to the agent workspace by
 * {@link WorkspacePathGuard}, so they can neither read nor write
 * {@code public/apps/} — the app-creator skill's direct writes there always
 * "escape the workspace". This tool bridges the boundary: the agent builds an
 * app inside its own workspace (allowed), then this tool — running as trusted
 * JClaw code, not the sandboxed agent — copies between the workspace and
 * {@code public/apps/}. Every path is still containment-checked on both sides,
 * so the bridge never becomes an arbitrary-write hole.
 *
 * <p>One {@code action}:
 * <ul>
 *   <li>{@code stage} — {@code public/apps/<slug>/} → workspace (for updates; idempotent);</li>
 *   <li>{@code validate} — check a built workspace app is well-formed, writing nothing;</li>
 *   <li>{@code install} — validate, then workspace → {@code public/apps/<slug>/} (clean replace).</li>
 * </ul>
 */
public class AppInstallTool implements ToolRegistry.Tool {

    public static final String TOOL_NAME = "app_install";

    /** Slug = the {@code public/apps/} directory name. Lowercase alnum + hyphen,
     *  starting alnum: no dots, slashes, or {@code ..}, so it can't traverse. */
    static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]*$");

    private static final String PARAM_ACTION = "action";
    private static final String PARAM_SLUG = "slug";
    private static final String PARAM_SOURCE = "source"; // workspace dir for validate/install
    private static final String PARAM_DEST = "dest";     // workspace dir for stage
    private static final String PARAM_OVERWRITE = "overwrite";

    private static final String ACTION_STAGE = "stage";
    private static final String ACTION_VALIDATE = "validate";
    private static final String ACTION_INSTALL = "install";

    @Override public String name() { return TOOL_NAME; }

    @Override public String category() { return "Files"; }

    @Override public String icon() { return "folder"; }

    @Override public String summary() {
        return "Stage, validate, and install hosted apps between your workspace and public/apps/.";
    }

    @Override public String shortDescription() {
        return "Manage a hosted app's files: stage an installed app into your workspace, validate a built "
                + "app, and install it to public/apps/ so the Apps page serves it.";
    }

    @Override public List<ToolAction> actions() {
        return List.of(
                new ToolAction(ACTION_STAGE,
                        "Copy an installed app from public/apps/<slug>/ into your workspace to edit it (for updates)."),
                new ToolAction(ACTION_VALIDATE,
                        "Check that a built app in your workspace is well-formed (app.json + index.html, valid slug) without installing."),
                new ToolAction(ACTION_INSTALL,
                        "Install a built app from your workspace to public/apps/<slug>/ so the Apps page serves it."));
    }

    @Override public String description() {
        return """
                Manage the files of a hosted app (the Apps page's static sites under public/apps/<slug>/). \
                Your filesystem tools are confined to your workspace and cannot read or write public/apps/ — \
                this tool bridges the two, so build apps in your workspace, then install them here. One `action`:
                - `stage`: copy an already-installed app from public/apps/<slug>/ into your workspace (default \
                dir `<slug>`) so you can edit it for an update. Idempotent — an existing workspace copy is kept \
                unless `overwrite` is true.
                - `validate`: check that the built app in your workspace (`source`, default `<slug>`) is \
                well-formed — a parseable app.json with name and version, an index.html, and a valid slug — \
                without writing anything.
                - `install`: validate, then publish the built app from your workspace (`source`, default \
                `<slug>`) to public/apps/<slug>/, replacing any existing app at that slug. Returns the \
                /apps/<slug>/ url.
                Params: `action` (required: stage|validate|install), `slug` (required; lowercase letters, \
                digits, hyphens), `source` (validate/install; workspace dir), `dest` (stage; workspace dir), \
                `overwrite` (stage; default false).""";
    }

    @Override public Map<String, Object> parameters() {
        var props = new LinkedHashMap<String, Object>();
        props.put(PARAM_ACTION, Map.of(
                SchemaKeys.TYPE, SchemaKeys.STRING,
                SchemaKeys.ENUM, List.of(ACTION_STAGE, ACTION_VALIDATE, ACTION_INSTALL),
                SchemaKeys.DESCRIPTION,
                "stage (public/apps -> workspace), validate (check a built app), or install (workspace -> public/apps)."));
        props.put(PARAM_SLUG, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                SchemaKeys.DESCRIPTION,
                "App id under public/apps/. Lowercase letters, digits, and hyphens only."));
        props.put(PARAM_SOURCE, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                SchemaKeys.DESCRIPTION,
                "For validate/install: workspace-relative directory holding the built app (app.json + index.html). Defaults to the slug."));
        props.put(PARAM_DEST, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                SchemaKeys.DESCRIPTION,
                "For stage: workspace-relative directory to copy the app into. Defaults to the slug."));
        props.put(PARAM_OVERWRITE, Map.of(SchemaKeys.TYPE, SchemaKeys.BOOLEAN,
                SchemaKeys.DESCRIPTION,
                "For stage: overwrite an existing workspace copy (default false — an existing copy is kept)."));
        return Map.of(
                SchemaKeys.TYPE, SchemaKeys.OBJECT,
                SchemaKeys.PROPERTIES, props,
                SchemaKeys.REQUIRED, List.of(PARAM_ACTION, PARAM_SLUG));
    }

    /** File I/O — not safe to race with other calls in the same round. */
    @Override public boolean parallelSafe() { return false; }

    @Override
    public String execute(String argsJson, Agent agent) {
        if (agent == null) return err("no agent context.");
        var args = JsonParser.parseString(argsJson).getAsJsonObject();
        var action = JsonArgs.optString(args, PARAM_ACTION);
        var slug = JsonArgs.optString(args, PARAM_SLUG);
        if (slug == null || !SLUG.matcher(slug).matches()) {
            return err("'slug' must match ^[a-z0-9][a-z0-9-]*$ (got '" + slug + "').");
        }
        if (action == null) return err("'action' is required: stage, validate, or install.");
        return switch (action) {
            case ACTION_STAGE -> stage(args, slug, agent);
            case ACTION_VALIDATE -> validate(args, slug, agent);
            case ACTION_INSTALL -> install(args, slug, agent);
            default -> err("unknown action '" + action + "' — use stage, validate, or install.");
        };
    }

    // ---- actions -------------------------------------------------------------

    private String validate(JsonObject args, String slug, Agent agent) {
        Path src = resolveWorkspaceDir(args, PARAM_SOURCE, slug, agent);
        if (src == null) return err("source escapes your workspace.");
        if (!Files.isDirectory(src)) {
            return jsonValid(false, List.of("no directory at that workspace path — build the app there first."));
        }
        var issues = validateBuiltApp(src);
        return jsonValid(issues.isEmpty(), issues);
    }

    private String install(JsonObject args, String slug, Agent agent) {
        Path src = resolveWorkspaceDir(args, PARAM_SOURCE, slug, agent);
        if (src == null) return err("source escapes your workspace.");
        if (!Files.isDirectory(src)) return err("no directory at that workspace path — build the app there first.");
        var issues = validateBuiltApp(src);
        if (!issues.isEmpty()) return err("built app is not well-formed: " + String.join("; ", issues));

        Path target = resolvePublicApp(slug);
        if (target == null) return err("slug '" + slug + "' resolves outside public/apps/.");
        try {
            deleteTree(target);            // clean replace so an update drops removed files
            // Creates public/apps/<slug> AND its parent public/apps/ when absent —
            // public/apps/ is gitignored runtime data that need not exist on a fresh checkout.
            Files.createDirectories(target);
            int files = copyTree(src, target);
            var payload = new LinkedHashMap<String, Object>();
            payload.put("installed", true);
            payload.put("slug", slug);
            payload.put("url", "/apps/" + slug + "/");
            payload.put("files", files);
            return GsonHolder.INSTANCE.toJson(payload, Map.class);
        } catch (IOException e) {
            return err("install failed: " + e.getMessage());
        }
    }

    private String stage(JsonObject args, String slug, Agent agent) {
        Path appDir = resolvePublicApp(slug);
        if (appDir == null || !Files.isDirectory(appDir) || !isBuiltApp(appDir)) {
            return err("no installed app at public/apps/" + slug + "/.");
        }
        Path dest = resolveWorkspaceDir(args, PARAM_DEST, slug, agent);
        if (dest == null) return err("dest escapes your workspace.");
        boolean overwrite = JsonArgs.optBool(args, PARAM_OVERWRITE);
        boolean present = Files.isDirectory(dest);
        try {
            if (present && !overwrite) {
                return jsonStaged(slug, args, true);
            }
            if (present) deleteTree(dest);
            Files.createDirectories(dest);
            copyTree(appDir, dest);
            return jsonStaged(slug, args, false);
        } catch (IOException e) {
            return err("stage failed: " + e.getMessage());
        }
    }

    // ---- helpers -------------------------------------------------------------

    /** Resolve {@code public/apps/<slug>}, containment-checked. Null on escape.
     *  Read-only probe — never creates the directory. */
    static Path resolvePublicApp(String slug) {
        return WorkspacePathGuard.resolveContained(publicAppsDir(), slug);
    }

    static Path publicAppsDir() {
        return Play.getFile("public/apps").toPath();
    }

    /** Resolve a workspace-relative dir param (falling back to the slug) inside
     *  the agent's workspace. Null when the arg escapes the workspace. */
    private static Path resolveWorkspaceDir(JsonObject args, String param, String slug, Agent agent) {
        var dir = JsonArgs.optString(args, param);
        if (dir == null || dir.isBlank()) dir = slug;
        try {
            return WorkspaceFiles.acquireWorkspacePath(agent.name, dir);
        } catch (SecurityException _) {
            return null;
        }
    }

    static boolean isBuiltApp(Path dir) {
        return Files.isRegularFile(dir.resolve("app.json"))
                && Files.isRegularFile(dir.resolve("index.html"));
    }

    /** Empty list = well-formed. Checks the two required files plus a parseable
     *  app.json carrying non-blank name and version (what the Apps page reads). */
    static List<String> validateBuiltApp(Path dir) {
        var issues = new ArrayList<String>();
        if (!Files.isRegularFile(dir.resolve("index.html"))) issues.add("missing index.html");
        var appJson = dir.resolve("app.json");
        if (!Files.isRegularFile(appJson)) {
            issues.add("missing app.json");
            return issues;
        }
        try {
            var m = JsonParser.parseString(Files.readString(appJson)).getAsJsonObject();
            if (!m.has("name") || m.get("name").isJsonNull() || m.get("name").getAsString().isBlank()) {
                issues.add("app.json is missing a non-empty 'name'");
            }
            if (!m.has("version") || m.get("version").isJsonNull() || m.get("version").getAsString().isBlank()) {
                issues.add("app.json is missing a non-empty 'version'");
            }
        } catch (IOException | RuntimeException e) {
            issues.add("app.json is not valid JSON: " + e.getMessage());
        }
        return issues;
    }

    /** Recursively copy the {@code src} tree into {@code dst}; returns the file count. */
    static int copyTree(Path src, Path dst) throws IOException {
        int files = 0;
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path p : walk.toList()) {
                Path target = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                    files++;
                }
            }
        }
        return files;
    }

    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException _) {
                    // best-effort; a genuine failure resurfaces at the next createDirectories
                }
            });
        }
    }

    private static String jsonValid(boolean valid, List<String> issues) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("valid", valid);
        payload.put("issues", issues);
        return GsonHolder.INSTANCE.toJson(payload, Map.class);
    }

    private static String jsonStaged(String slug, JsonObject args, boolean alreadyPresent) {
        var dest = JsonArgs.optString(args, PARAM_DEST);
        if (dest == null || dest.isBlank()) dest = slug;
        var payload = new LinkedHashMap<String, Object>();
        payload.put("staged", true);
        payload.put("slug", slug);
        payload.put("workspacePath", dest);
        payload.put("alreadyPresent", alreadyPresent);
        return GsonHolder.INSTANCE.toJson(payload, Map.class);
    }

    private static String err(String msg) {
        return "Error: " + msg;
    }
}
