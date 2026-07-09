package controllers;

import agents.SystemPromptAssembler;
import agents.SystemPromptAssembler.PromptBreakdown;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import models.Agent;
import models.AgentSkillAllowedTool;
import models.AgentSkillConfig;
import play.libs.MimeTypes;
import play.mvc.Controller;
import play.mvc.With;
import services.AgentService;
import services.ConfigService;
import services.LoadTestRunner;
import services.compression.TextCompressor;
import tools.ShellExecTool;
import utils.ApiResponses;
import utils.HttpKeys;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static utils.GsonHolder.INSTANCE;

@With(AuthCheck.class)
public class ApiAgentsController extends Controller {

    private static final Gson gson = INSTANCE;

    // JCLAW-682: canonical error codes for the ApiResponses envelope.

    // JSON body keys reused across create/update/serve paths.
    private static final String KEY_MODEL_PROVIDER = "modelProvider";
    private static final String KEY_MODEL_ID = "modelId";
    private static final String KEY_THINKING_MODE = "thinkingMode";
    private static final String KEY_DESCRIPTION = "description";
    private static final String KEY_CONTENT = "content";
    private static final String KEY_COMPRESSION_ENABLED = "compressionEnabled";
    private static final String KEY_COMPRESSION_JSON = "compressionJson";
    private static final String KEY_COMPRESSION_CODE = "compressionCode";
    private static final String KEY_COMPRESSION_TEXT = "compressionText";
    private static final String KEY_COMPRESSION_TARGET_RATIO = "compressionTargetRatio";
    private static final String KEY_ACP_ALLOWED = "acpAllowed";
    private static final String KEY_MEMORY_AUTOCAPTURE_ENABLED = "memoryAutocaptureEnabled";
    private static final String KEY_MEMORY_AUTOCAPTURE_PROVIDER = "memoryAutocaptureProvider";
    private static final String KEY_MEMORY_AUTOCAPTURE_MODEL = "memoryAutocaptureModel";

    /**
     * Slug regex enforced on every {@code name} received from the public
     * API (JCLAW-115). Must begin with an alphanumeric character or
     * underscore (existing reserved-pattern rows like {@code __loadtest__}
     * qualify) and use only alphanumerics, hyphens, or underscores
     * thereafter. Max length 64 chars. Deliberately excludes path
     * separators, dot segments, whitespace, and absolute-path leading
     * slashes — names flow through {@code AgentService.workspacePath}
     * which does a {@code Path.resolve} that would otherwise happily
     * escape the workspace root.
     */
    private static final Pattern AGENT_NAME_RE =
            Pattern.compile("^\\w[\\w-]{0,63}$");

    /**
     * Reject any name that fails the slug regex with 400. Called from
     * {@code create} and {@code update} before we touch the service layer.
     * Returning {@code badRequest()} halts the controller action — the
     * {@code notFound}-style flow Play uses.
     */
    private static void validateAgentName(String name) {
        if (name == null || !AGENT_NAME_RE.matcher(name).matches()) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "Agent name must match " + AGENT_NAME_RE.pattern()
                    + " (letters, digits, hyphen, underscore; 1-64 chars; starts with alphanumeric)");
        }
    }

    /**
     * Reserved agent names that no user-facing API surface can create, read,
     * update, or delete. Internal harnesses write these rows via JPA directly
     * and are unaffected. Case-insensitive match — spelling variations like
     * {@code __LoadTest__} are also rejected.
     */
    private static boolean isReservedName(String name) {
        return name != null
                && LoadTestRunner.LOADTEST_AGENT_NAME.equalsIgnoreCase(name);
    }

    @SuppressWarnings("java:S2259")
    private static Agent requireAgent(Long id) {
        var agent = AgentService.findById(id);
        if (agent == null || isReservedName(agent.name)) {
            notFound();
            throw new AssertionError("unreachable: notFound() throws");
        }
        return agent;
    }

    public record AgentRequest(String name, String modelProvider, String modelId,
                               String thinkingMode, String description, Boolean enabled) {}

    public record WorkspaceFileRequest(String content) {}

    private record EffectiveAllowlistResponse(List<String> global,
                                              Map<String, List<String>> bySkill) {}

    private record WorkspaceFileResponse(String filename, String content) {}

    private record AgentView(Long id, String name, String description,
                             String modelProvider, String modelId,
                             boolean enabled, boolean isMain, String thinkingMode,
                             String createdAt, String updatedAt, boolean providerConfigured,
                             boolean compressionEnabled,
                             boolean compressionJson, boolean compressionCode,
                             boolean compressionText, double compressionTargetRatio,
                             boolean acpAllowed,
                             boolean memoryAutocaptureEnabled,
                             boolean memoryAutocaptureModelInherited,
                             String memoryAutocaptureProvider,
                             String memoryAutocaptureModel) {
        static AgentView of(Agent a) {
            return of(a, AgentService.isProviderConfigured(a.modelProvider, a.modelId));
        }

        /**
         * Bulk path: callers that map many agents pre-build a set of
         * configured keys via {@link AgentService#configuredModelKeys} and
         * pass per-agent O(1) lookups in here. Avoids the per-agent
         * {@code Stream.anyMatch} over the provider's full model list,
         * which was O(N*M) on the {@code GET /api/agents} hot path.
         */
        static AgentView of(Agent a, Set<String> configuredKeys) {
            return of(a, configuredKeys.contains(a.modelProvider + ":" + a.modelId));
        }

        private static AgentView of(Agent a, boolean configured) {
            return new AgentView(a.id, a.name, a.description, a.modelProvider, a.modelId,
                    a.enabled, a.isMain(), a.thinkingMode,
                    a.createdAt.toString(), a.updatedAt.toString(), configured,
                    a.compressionEffective(),
                    a.compressionJsonEffective(), a.compressionCodeEffective(),
                    a.compressionTextEffective(),
                    a.compressionTargetRatio != null
                            ? a.compressionTargetRatio
                            : TextCompressor.DEFAULT_TARGET_RATIO,
                    a.acpAllowed,
                    a.memoryAutocaptureEnabled,
                    a.memoryAutocaptureProvider == null && a.memoryAutocaptureModel == null,
                    a.autocaptureProviderEffective(),
                    a.autocaptureModelEffective());
        }
    }

    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = AgentView.class))))
    @Operation(summary = "List agents (id, name, modelProvider, modelId, enabled, isMain)")
    public static void list() {
        var agents = AgentService.listAll();
        var configuredKeys = AgentService.configuredModelKeys();
        // Subagents (parentAgent != null) are scoped to their parent's spawn
        // tree and don't belong in the user-facing dropdown — they appear on
        // the /subagents admin page, where their transcripts are viewable.
        // Filtering here keeps the chat UI's "Agent" selector strictly for
        // top-level agents.
        var result = agents.stream()
                .filter(a -> !isReservedName(a.name))
                .filter(a -> a.parentAgent == null)
                .map(a -> AgentView.of(a, configuredKeys))
                .toList();
        renderJSON(gson.toJson(result));
    }

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = AgentView.class)))
    @Operation(summary = "Get one agent's full details by id")
    public static void get(Long id) {
        var agent = requireAgent(id);
        renderJSON(gson.toJson(AgentView.of(agent)));
    }

    /**
     * Return a per-section breakdown of the system prompt this agent would receive
     * on its next turn. Feeds the Settings UI introspection dialog. Memory recall is
     * skipped (null user message) so the breakdown is deterministic for a given
     * agent state and doesn't depend on a hypothetical user query.
     *
     * @param id the agent id to break down
     */
    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = PromptBreakdown.class)))
    @Operation(summary = "Per-section breakdown of the system prompt this agent would receive next turn")
    public static void promptBreakdown(Long id) {
        var agent = requireAgent(id);
        // channelType is required: every real chat lives on a channel, so the
        // UI always sends one. Reject missing/unknown values up-front rather
        // than silently assembling a prompt that doesn't match any runtime path.
        var rawChannel = params.get("channelType");
        if (rawChannel == null || rawChannel.isBlank()) badRequest();
        var channelType = rawChannel.trim().toLowerCase();
        if (!VALID_BREAKDOWN_CHANNELS.contains(channelType)) badRequest();
        var breakdown = SystemPromptAssembler.breakdown(agent, null, channelType);
        renderJSON(gson.toJson(breakdown));
    }

    private static final Set<String> VALID_BREAKDOWN_CHANNELS =
            Set.of("web", "telegram", "slack", "whatsapp");

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
     *
     * @param id the agent id whose effective allowlist is requested
     */
    @Operation(summary = "Effective shell allowlist for an agent: global config unioned with enabled-skill commands")
    public static void effectiveShellAllowlist(Long id) {
        var agent = requireAgent(id);

        // Global portion: re-parse the raw config string rather than call
        // parsedAllowlist() directly, which lives on a tool instance. The parse
        // is cheap (bounded length, split-and-trim).
        var rawGlobal = ConfigService.get("shell.allowlist",
                ShellExecTool.DEFAULT_ALLOWLIST);
        var global = Arrays.stream(rawGlobal.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .sorted()
                .toList();

        // Per-skill contributions: only surface skills that are currently
        // enabled for this agent (enabled-by-default when no config row).
        var configs = AgentSkillConfig.findByAgent(agent);
        var disabledSkills = new HashSet<String>();
        for (var c : configs) {
            if (!c.enabled) disabledSkills.add(c.skillName);
        }
        var bySkill = new TreeMap<String, List<String>>();
        for (var row : AgentSkillAllowedTool.findByAgent(agent)) {
            if (disabledSkills.contains(row.skillName)) continue;
            bySkill.computeIfAbsent(row.skillName, _ -> new ArrayList<>()).add(row.toolName);
        }
        for (var entry : bySkill.entrySet()) {
            entry.getValue().sort(String::compareTo);
        }

        renderJSON(gson.toJson(new EffectiveAllowlistResponse(global, bySkill)));
    }

    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = AgentView.class)))
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = AgentRequest.class)))
    @Operation(summary = "Create an agent")
    public static void create() {
        var body = JsonBodyReader.readJsonBody();
        if (body == null) badRequest();

        var name = requireString(body, "name");
        validateAgentName(name);
        if (Agent.MAIN_AGENT_NAME.equalsIgnoreCase(name)) {
            ApiResponses.error(409, ApiResponses.CONFLICT, "The agent name 'main' is reserved for the built-in agent");
        }
        if (isReservedName(name)) {
            ApiResponses.error(409, ApiResponses.CONFLICT, "The agent name '%s' is reserved for internal use"
                    .formatted(LoadTestRunner.LOADTEST_AGENT_NAME));
        }
        // Agent.name carries a unique constraint at the DB level. Without this
        // pre-check the duplicate surfaces only at JPA flush time as an
        // unhandled JdbcSQLIntegrityConstraintViolationException → HTTP 500,
        // which the operator sees as an opaque error toast. 409 with the
        // taken name makes the conflict actionable.
        if (Agent.findByName(name) != null) {
            ApiResponses.error(409, ApiResponses.CONFLICT, "An agent named '" + name + "' already exists");
        }
        var modelProvider = requireString(body, KEY_MODEL_PROVIDER);
        var modelId = requireString(body, KEY_MODEL_ID);
        var thinkingMode = readOptionalString(body, KEY_THINKING_MODE);
        var description = readOptionalString(body, KEY_DESCRIPTION);

        var agent = AgentService.create(name, modelProvider, modelId, thinkingMode, description);
        renderJSON(gson.toJson(AgentView.of(agent)));
    }

    /**
     * Read a JSON string field that may be missing, null, or blank, returning
     * {@code null} in all of those cases. Used for optional nullable fields
     * like {@code thinkingMode} where the frontend sends {@code null} to clear.
     */
    private static String readOptionalString(JsonObject body, String key) {
        if (!body.has(key)) return null;
        var el = body.get(key);
        if (el.isJsonNull()) return null;
        var s = el.getAsString();
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * Read a REQUIRED string field. Returns 400 — not a 500 NPE — when the field
     * is absent, JSON null, or blank. Matters on the agent-facing jclaw_api path:
     * a missing field should surface as an actionable error, not a stack trace.
     */
    @SuppressWarnings("java:S2259")
    private static String requireString(JsonObject body, String key) {
        var v = readOptionalString(body, key);
        if (v == null) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "'" + key + "' is required");
            throw new AssertionError("unreachable: error() throws");
        }
        return v;
    }

    /** Read an optional string, falling back when absent OR JSON null (no NPE on a present-but-null field). */
    private static String optStringOr(JsonObject body, String key, String fallback) {
        if (!body.has(key)) return fallback;
        var el = body.get(key);
        return el.isJsonNull() ? fallback : el.getAsString();
    }

    @SuppressWarnings("java:S2259")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = AgentView.class)))
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = AgentRequest.class)))
    @Operation(summary = "Update an agent by id")
    public static void update(Long id) {
        var agent = requireAgent(id);

        var body = JsonBodyReader.readJsonBody();
        if (body == null) badRequest();

        var name = optStringOr(body, "name", agent.name);
        validateRenameRules(agent, name);

        var modelProvider = optStringOr(body, KEY_MODEL_PROVIDER, agent.modelProvider);
        var modelId = optStringOr(body, KEY_MODEL_ID, agent.modelId);
        var enabled = body.has("enabled") ? body.get("enabled").getAsBoolean() : agent.enabled;
        // The main agent cannot be disabled. Service-layer enforcement would also
        // catch this, but we reject at the API boundary so the operator sees an
        // explicit error instead of a silently-ignored toggle.
        if (agent.isMain() && !enabled) {
            ApiResponses.error(409, ApiResponses.CONFLICT, "The main agent cannot be disabled");
        }

        // thinkingMode is optional on update: absent key leaves the stored value
        // untouched, explicit null/blank clears it, any other string is validated
        // downstream against the model's advertised levels.
        var thinkingMode = body.has(KEY_THINKING_MODE)
                ? readOptionalString(body, KEY_THINKING_MODE)
                : agent.thinkingMode;

        // description follows the same absent-leaves-untouched convention; an
        // explicit null or blank clears the field.
        var description = body.has(KEY_DESCRIPTION)
                ? readOptionalString(body, KEY_DESCRIPTION)
                : agent.description;

        // JCLAW-463/464/465: apply the per-agent compression fields present in the
        // body. Absent keys leave the stored value untouched.
        applyCompressionSettings(agent, body);

        // JCLAW-500: per-agent acp-runtime grant. Absent/null key leaves it
        // unchanged (partial-PUT convention, same as the compression fields).
        if (body.has(KEY_ACP_ALLOWED) && !body.get(KEY_ACP_ALLOWED).isJsonNull()) {
            agent.acpAllowed = body.get(KEY_ACP_ALLOWED).getAsBoolean();
        }

        // JCLAW-534: per-agent memory auto-capture enable + model override.
        applyMemorySettings(agent, body);

        agent = AgentService.update(agent, name, modelProvider, modelId, enabled, thinkingMode,
                description);
        renderJSON(gson.toJson(AgentView.of(agent)));
    }

    /**
     * JCLAW-463/464/465: apply the per-agent compression fields present in {@code body}
     * onto {@code agent}. Absent or explicit-null keys leave the stored value untouched;
     * the master toggle gates the per-type sub-toggles downstream. Values are set directly
     * on the entity so {@link AgentService#update}'s save() persists them with the rest.
     */
    private static void applyCompressionSettings(Agent agent, JsonObject body) {
        if (body.has(KEY_COMPRESSION_ENABLED) && !body.get(KEY_COMPRESSION_ENABLED).isJsonNull()) {
            agent.compressionEnabled = body.get(KEY_COMPRESSION_ENABLED).getAsBoolean();
        }
        if (body.has(KEY_COMPRESSION_JSON) && !body.get(KEY_COMPRESSION_JSON).isJsonNull()) {
            agent.compressionJson = body.get(KEY_COMPRESSION_JSON).getAsBoolean();
        }
        if (body.has(KEY_COMPRESSION_CODE) && !body.get(KEY_COMPRESSION_CODE).isJsonNull()) {
            agent.compressionCode = body.get(KEY_COMPRESSION_CODE).getAsBoolean();
        }
        if (body.has(KEY_COMPRESSION_TEXT) && !body.get(KEY_COMPRESSION_TEXT).isJsonNull()) {
            agent.compressionText = body.get(KEY_COMPRESSION_TEXT).getAsBoolean();
        }
        // JCLAW-464: clamp lives in TextCompressor; persist the operator's raw value.
        if (body.has(KEY_COMPRESSION_TARGET_RATIO) && !body.get(KEY_COMPRESSION_TARGET_RATIO).isJsonNull()) {
            agent.compressionTargetRatio = body.get(KEY_COMPRESSION_TARGET_RATIO).getAsDouble();
        }
    }

    /**
     * JCLAW-534: apply the per-agent memory auto-capture settings present in
     * {@code body}. Absent keys leave the stored value untouched (partial-PUT).
     * For the model override an explicit null/blank means "inherit the agent's
     * default model"; a concrete value is an explicit override. Set directly on
     * the entity so {@link AgentService#update}'s save() persists them.
     */
    private static void applyMemorySettings(Agent agent, JsonObject body) {
        if (body.has(KEY_MEMORY_AUTOCAPTURE_ENABLED) && !body.get(KEY_MEMORY_AUTOCAPTURE_ENABLED).isJsonNull()) {
            agent.memoryAutocaptureEnabled = body.get(KEY_MEMORY_AUTOCAPTURE_ENABLED).getAsBoolean();
        }
        if (body.has(KEY_MEMORY_AUTOCAPTURE_PROVIDER)) {
            agent.memoryAutocaptureProvider = readOptionalString(body, KEY_MEMORY_AUTOCAPTURE_PROVIDER);
        }
        if (body.has(KEY_MEMORY_AUTOCAPTURE_MODEL)) {
            agent.memoryAutocaptureModel = readOptionalString(body, KEY_MEMORY_AUTOCAPTURE_MODEL);
        }
    }

    /**
     * Enforce the full rename ruleset on a proposed {@code name} against the
     * current {@code agent}: slug format, "main" singleton rules, reserved-name
     * gate, and unique-name collision. Each rule short-circuits via
     * {@code error(...)} which throws a Result.
     */
    @SuppressWarnings("java:S2259")
    private static void validateRenameRules(Agent agent, String name) {
        // JCLAW-115: only validate when the name actually changes. Existing
        // agents grandfathered in — rejecting their current name on an
        // unrelated PUT (thinking-mode toggle, etc.) would break workflows.
        if (!name.equals(agent.name)) validateAgentName(name);
        // The reserved name "main" is a singleton: no other agent may take the name,
        // and the main agent may not be renamed away from it.
        if (!agent.isMain() && Agent.MAIN_AGENT_NAME.equalsIgnoreCase(name)) {
            ApiResponses.error(409, ApiResponses.CONFLICT, "The agent name 'main' is reserved for the built-in agent");
        }
        if (agent.isMain() && !Agent.MAIN_AGENT_NAME.equalsIgnoreCase(name)) {
            ApiResponses.error(409, ApiResponses.CONFLICT, "The main agent cannot be renamed");
        }
        if (isReservedName(name)) {
            ApiResponses.error(409, ApiResponses.CONFLICT, "The agent name '%s' is reserved for internal use"
                    .formatted(LoadTestRunner.LOADTEST_AGENT_NAME));
        }
        // Rename collision: another agent already owns this name. Skip the
        // check when the name is unchanged so a PUT that only updates
        // unrelated fields (thinking-mode, enabled, ...) doesn't false-
        // positive on its own row.
        if (!name.equals(agent.name)) {
            var conflicting = Agent.findByName(name);
            if (conflicting != null && !conflicting.id.equals(agent.id)) {
                ApiResponses.error(409, ApiResponses.CONFLICT, "An agent named '" + name + "' already exists");
            }
        }
    }

    @SuppressWarnings("java:S2259")
    @Operation(summary = "Delete an agent by id (the built-in 'main' agent cannot be deleted)")
    public static void delete(Long id) {
        var agent = requireAgent(id);
        if (agent.isMain()) {
            ApiResponses.error(409, ApiResponses.CONFLICT, "The built-in 'main' agent cannot be deleted");
        }
        AgentService.delete(agent);
        ApiResponses.ok();
    }

    // --- Workspace file endpoints ---

    /**
     * GET /api/agents/{id}/files/{filePath} — Serve a workspace file with proper content type.
     * Supports images, PDFs, and other binary files for inline rendering or download.
     *
     * @param id       the agent id whose workspace to serve from
     * @param filePath path inside the agent's workspace
     */
    @SuppressWarnings("java:S2259")
    @Operation(summary = "Serve a binary workspace file with its content type for inline rendering or download")
    public static void serveWorkspaceFile(Long id, String filePath) {
        var agent = requireAgent(id);

        // Two-layer (lexical + canonical) path validation with double-resolve.
        // The previous substring check (`filePath.contains("..")`) didn't
        // normalize the path before checking, and never compared against the
        // workspace root. acquireWorkspacePath does both, plus realpath
        // resolution so a symlink inside the workspace pointing outside is
        // also rejected.
        Path path;
        try {
            path = AgentService.acquireWorkspacePath(agent.name, filePath);
        } catch (SecurityException _) {
            forbidden();
            return;  // javac definite-assignment: path is unassigned on this catch path
        }
        var file = path.toFile();
        if (!file.exists() || !file.isFile()) notFound();

        // Content type: Play's MimeTypes resolver covers the bundled database plus
        // any custom mimetype.* entries declared in application.conf.
        var contentType = MimeTypes.getContentType(filePath);

        response.setHeader("Cache-Control", "private, max-age=300");

        var inline = contentType.startsWith("image/") || contentType.startsWith("application/pdf");
        response.setHeader(HttpKeys.CONTENT_TYPE, contentType);
        if (inline) {
            response.setHeader("Content-Disposition", "inline; filename=\"%s\"".formatted(file.getName()));
        }
        renderBinary(file);
    }

    @SuppressWarnings("java:S2259")
    @Operation(summary = "Read a text workspace file's contents by filename")
    public static void getWorkspaceFile(Long id, String filename) {
        var agent = requireAgent(id);
        var content = AgentService.readWorkspaceFile(agent.name, filename);
        if (content == null) notFound();
        renderJSON(gson.toJson(new WorkspaceFileResponse(filename, content)));
    }

    @SuppressWarnings("java:S2259")
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = WorkspaceFileRequest.class)))
    @Operation(summary = "Write a text workspace file's contents by filename")
    public static void saveWorkspaceFile(Long id, String filename) {
        var agent = requireAgent(id);
        var body = JsonBodyReader.readJsonBody();
        if (body == null || !body.has(KEY_CONTENT) || body.get(KEY_CONTENT).isJsonNull()) {
            badRequest();
            throw new AssertionError("unreachable: badRequest() throws");
        }
        AgentService.writeWorkspaceFile(agent.name, filename, body.get(KEY_CONTENT).getAsString());
        ApiResponses.ok("filename", filename);
    }

}
