package controllers;

import com.google.gson.Gson;
import models.Agent;
import play.mvc.Controller;
import play.mvc.With;
import services.AgentService;

import static utils.GsonHolder.INSTANCE;

@With(AuthCheck.class)
public class ApiAgentsController extends Controller {

    private static final Gson gson = INSTANCE;

    /**
     * Reserved agent names that no user-facing API surface can create, read,
     * update, or delete. Internal harnesses write these rows via JPA directly
     * and are unaffected. Case-insensitive match — spelling variations like
     * {@code __LoadTest__} are also rejected.
     */
    private static boolean isReservedName(String name) {
        return name != null
                && services.LoadTestRunner.LOADTEST_AGENT_NAME.equalsIgnoreCase(name);
    }

    private static Agent requireAgent(Long id) {
        var agent = AgentService.findById(id);
        if (agent == null || isReservedName(agent.name)) notFound();
        return agent;
    }

    private record AgentView(Long id, String name, String modelProvider, String modelId,
                             boolean enabled, boolean isMain, String thinkingMode,
                             Boolean visionEnabled, Boolean audioEnabled,
                             String createdAt, String updatedAt, boolean providerConfigured) {
        static AgentView of(Agent a) {
            var configured = AgentService.isProviderConfigured(a.modelProvider, a.modelId);
            return new AgentView(a.id, a.name, a.modelProvider, a.modelId,
                    a.enabled, a.isMain(), a.thinkingMode,
                    a.visionEnabled, a.audioEnabled,
                    a.createdAt.toString(), a.updatedAt.toString(), configured);
        }
    }

    public static void list() {
        var agents = AgentService.listAll();
        var result = agents.stream()
                .filter(a -> !isReservedName(a.name))
                .map(AgentView::of)
                .toList();
        renderJSON(gson.toJson(result));
    }

    public static void get(Long id) {
        var agent = requireAgent(id);
        renderJSON(gson.toJson(AgentView.of(agent)));
    }

    /**
     * Return a per-section breakdown of the system prompt this agent would receive
     * on its next turn. Feeds the Settings UI introspection dialog. Memory recall is
     * skipped (null user message) so the breakdown is deterministic for a given
     * agent state and doesn't depend on a hypothetical user query.
     */
    public static void promptBreakdown(Long id) {
        var agent = requireAgent(id);
        // channelType is required: every real chat lives on a channel, so the
        // UI always sends one. Reject missing/unknown values up-front rather
        // than silently assembling a prompt that doesn't match any runtime path.
        var rawChannel = params.get("channelType");
        if (rawChannel == null || rawChannel.isBlank()) badRequest();
        var channelType = rawChannel.trim().toLowerCase();
        if (!VALID_BREAKDOWN_CHANNELS.contains(channelType)) badRequest();
        var breakdown = agents.SystemPromptAssembler.breakdown(agent, null, channelType);
        renderJSON(gson.toJson(breakdown));
    }

    private static final java.util.Set<String> VALID_BREAKDOWN_CHANNELS =
            java.util.Set.of("web", "telegram", "slack", "whatsapp");

    /**
     * GET /api/agents/{id}/shell/effective-allowlist — Derived view of the
     * effective shell allowlist this agent would be checked against at exec
     * time: the global {@code shell.allowlist} unioned with every command
     * contributed by the agent's currently-enabled skills. Response shape:
     * {@code { global: string[], bySkill: { <skill>: string[] } }} so the UI
     * can render provenance without recomputing the join on the client.
     *
     * <p>Read-only: nothing about the allowlist is mutable from this page —
     * global is edited via Settings; per-skill contributions are set at skill
     * install time. Operators who need to remove a per-skill grant do so by
     * disabling or removing the skill.
     */
    public static void effectiveShellAllowlist(Long id) {
        var agent = requireAgent(id);

        // Global portion: re-parse the raw config string rather than call
        // parsedAllowlist() directly, which lives on a tool instance. The parse
        // is cheap (bounded length, split-and-trim).
        var rawGlobal = services.ConfigService.get("shell.allowlist",
                tools.ShellExecTool.DEFAULT_ALLOWLIST);
        var global = java.util.Arrays.stream(rawGlobal.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .sorted()
                .toList();

        // Per-skill contributions: only surface skills that are currently
        // enabled for this agent (enabled-by-default when no config row).
        var configs = models.AgentSkillConfig.findByAgent(agent);
        var disabledSkills = new java.util.HashSet<String>();
        for (var c : configs) {
            if (!c.enabled) disabledSkills.add(c.skillName);
        }
        var bySkill = new java.util.TreeMap<String, java.util.List<String>>();
        for (var row : models.AgentSkillAllowedTool.findByAgent(agent)) {
            if (disabledSkills.contains(row.skillName)) continue;
            bySkill.computeIfAbsent(row.skillName, _ -> new java.util.ArrayList<>()).add(row.toolName);
        }
        for (var entry : bySkill.entrySet()) {
            entry.getValue().sort(String::compareTo);
        }

        renderJSON(gson.toJson(java.util.Map.of(
                "global", global,
                "bySkill", bySkill
        )));
    }

    public static void create() {
        var body = JsonBodyReader.readJsonBody();
        if (body == null) badRequest();

        var name = body.get("name").getAsString();
        if (Agent.MAIN_AGENT_NAME.equalsIgnoreCase(name)) {
            error(409, "The agent name 'main' is reserved for the built-in agent");
        }
        if (isReservedName(name)) {
            error(409, "The agent name '%s' is reserved for internal use"
                    .formatted(services.LoadTestRunner.LOADTEST_AGENT_NAME));
        }
        var modelProvider = body.get("modelProvider").getAsString();
        var modelId = body.get("modelId").getAsString();
        var thinkingMode = readOptionalString(body, "thinkingMode");

        var agent = AgentService.create(name, modelProvider, modelId, thinkingMode);
        renderJSON(gson.toJson(AgentView.of(agent)));
    }

    /**
     * Read a JSON string field that may be missing, null, or blank, returning
     * {@code null} in all of those cases. Used for optional nullable fields
     * like {@code thinkingMode} where the frontend sends {@code null} to clear.
     */
    private static String readOptionalString(com.google.gson.JsonObject body, String key) {
        if (!body.has(key)) return null;
        var el = body.get(key);
        if (el.isJsonNull()) return null;
        var s = el.getAsString();
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * Read a JSON boolean field that may be missing or explicit {@code null},
     * returning {@code null} in both cases so the caller can distinguish
     * "operator cleared the override" from "operator picked false".
     */
    private static Boolean readOptionalBoolean(com.google.gson.JsonObject body, String key) {
        if (!body.has(key)) return null;
        var el = body.get(key);
        if (el.isJsonNull()) return null;
        return el.getAsBoolean();
    }

    public static void update(Long id) {
        var agent = requireAgent(id);

        var body = JsonBodyReader.readJsonBody();
        if (body == null) badRequest();

        var name = body.has("name") ? body.get("name").getAsString() : agent.name;
        // The reserved name "main" is a singleton: no other agent may take the name,
        // and the main agent may not be renamed away from it.
        if (!agent.isMain() && Agent.MAIN_AGENT_NAME.equalsIgnoreCase(name)) {
            error(409, "The agent name 'main' is reserved for the built-in agent");
        }
        if (agent.isMain() && !Agent.MAIN_AGENT_NAME.equalsIgnoreCase(name)) {
            error(409, "The main agent cannot be renamed");
        }
        if (isReservedName(name)) {
            error(409, "The agent name '%s' is reserved for internal use"
                    .formatted(services.LoadTestRunner.LOADTEST_AGENT_NAME));
        }
        var modelProvider = body.has("modelProvider") ? body.get("modelProvider").getAsString() : agent.modelProvider;
        var modelId = body.has("modelId") ? body.get("modelId").getAsString() : agent.modelId;
        var enabled = body.has("enabled") ? body.get("enabled").getAsBoolean() : agent.enabled;
        // The main agent cannot be disabled. Service-layer enforcement would also
        // catch this, but we reject at the API boundary so the operator sees an
        // explicit error instead of a silently-ignored toggle.
        if (agent.isMain() && !enabled) {
            error(409, "The main agent cannot be disabled");
        }

        // thinkingMode is optional on update: absent key leaves the stored value
        // untouched, explicit null/blank clears it, any other string is validated
        // downstream against the model's advertised levels.
        var thinkingMode = body.has("thinkingMode")
                ? readOptionalString(body, "thinkingMode")
                : agent.thinkingMode;

        // visionEnabled / audioEnabled follow the same absent-leaves-untouched
        // convention as thinkingMode. Three-state semantics (null/true/false)
        // are preserved end-to-end: a JSON null in the body clears the override
        // (falling back to the model's capability default); true or false pins
        // the operator's explicit choice. Both pills on the chat page PUT
        // boolean values only; null arrives only from a rare API consumer.
        var visionEnabled = body.has("visionEnabled")
                ? readOptionalBoolean(body, "visionEnabled")
                : agent.visionEnabled;
        var audioEnabled = body.has("audioEnabled")
                ? readOptionalBoolean(body, "audioEnabled")
                : agent.audioEnabled;

        agent = AgentService.update(agent, name, modelProvider, modelId, enabled, thinkingMode,
                visionEnabled, audioEnabled);
        renderJSON(gson.toJson(AgentView.of(agent)));
    }

    public static void delete(Long id) {
        var agent = requireAgent(id);
        if (agent.isMain()) {
            error(409, "The built-in 'main' agent cannot be deleted");
        }
        AgentService.delete(agent);
        renderJSON(gson.toJson(java.util.Map.of("status", "ok")));
    }

    // --- Workspace file endpoints ---

    /**
     * GET /api/agents/{id}/files/{filePath} — Serve a workspace file with proper content type.
     * Supports images, PDFs, and other binary files for inline rendering or download.
     */
    public static void serveWorkspaceFile(Long id, String filePath) {
        var agent = requireAgent(id);

        // Two-layer (lexical + canonical) path validation with double-resolve.
        // The previous substring check (`filePath.contains("..")`) didn't
        // normalize the path before checking, and never compared against the
        // workspace root. acquireWorkspacePath does both, plus realpath
        // resolution so a symlink inside the workspace pointing outside is
        // also rejected.
        java.nio.file.Path path;
        try {
            path = AgentService.acquireWorkspacePath(agent.name, filePath);
        } catch (SecurityException e) {
            forbidden();
            return;
        }
        var file = path.toFile();
        if (!file.exists() || !file.isFile()) notFound();

        // Content type: Play's MimeTypes resolver covers the bundled database plus
        // any custom mimetype.* entries declared in application.conf.
        var contentType = play.libs.MimeTypes.getContentType(filePath);

        response.setHeader("Cache-Control", "private, max-age=300");

        var inline = contentType.startsWith("image/") || contentType.startsWith("application/pdf");
        response.setHeader("Content-Type", contentType);
        if (inline) {
            response.setHeader("Content-Disposition", "inline; filename=\"%s\"".formatted(file.getName()));
        }
        renderBinary(file);
    }

    public static void getWorkspaceFile(Long id, String filename) {
        var agent = requireAgent(id);
        var content = AgentService.readWorkspaceFile(agent.name, filename);
        if (content == null) notFound();
        renderJSON(gson.toJson(java.util.Map.of("filename", filename, "content", content)));
    }

    public static void saveWorkspaceFile(Long id, String filename) {
        var agent = requireAgent(id);
        var body = JsonBodyReader.readJsonBody();
        if (body == null || !body.has("content")) badRequest();
        AgentService.writeWorkspaceFile(agent.name, filename, body.get("content").getAsString());
        renderJSON(gson.toJson(java.util.Map.of("status", "ok", "filename", filename)));
    }

}
