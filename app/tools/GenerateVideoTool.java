package tools;

import agents.ToolAction;
import agents.ToolRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.Agent;
import models.VideoGenerationJob;
import services.ConfigService;
import services.Tx;
import services.videogen.VideoGenerationJobService;
import services.videogen.VideoGenerationRouter;
import services.videogen.VideoGenerationService.VideoGenRequest;

import java.util.List;
import java.util.Map;

/**
 * {@code generate_video} (JCLAW-235): generate a short video from a text prompt. Unlike
 * {@code generate_image}, video generation is asynchronous — this tool <em>submits</em> a job and
 * returns immediately with a confirmation. The commit path ({@code agents.ParallelToolExecutor})
 * creates a placeholder {@code MessageAttachment} linked to the job on the assistant turn (JCLAW-234),
 * and {@code jobs.VideoGenerationJobRunner} fills it once the provider finishes (minutes later); the
 * chat shows a generating card that swaps to an inline player on completion.
 *
 * <p>Default-OFF per agent ({@code ToolRegistry.computeDisabledTools}) — video generation is costly and
 * slow, so an operator opts each agent in via the agent editor.
 */
public class GenerateVideoTool implements ToolRegistry.Tool {

    private static final String ARG_PROMPT = "prompt";
    private static final String ARG_DURATION = "duration_seconds";
    private static final String ARG_FPS = "fps";
    private static final String ARG_ASPECT = "aspect_ratio";

    /** Effective defaults recorded in the metadata when the caller omits them, so the chat's video chip
     *  always has an fps/aspect to show. Mirror {@code LocalVideoGenerationClient}'s client-side defaults
     *  (fps 24, landscape). */
    private static final int DEFAULT_FPS = 24;
    private static final String DEFAULT_ASPECT = "16:9";

    @Override public String name() { return "generate_video"; }
    @Override public String category() { return "Utilities"; }
    @Override public String icon() { return "video"; }

    @Override
    public List<ToolAction> actions() {
        return List.of(new ToolAction("generate",
                "Submit an async text-to-video job; the clip appears in chat when ready"));
    }

    @Override
    public String description() {
        return """
                Generate a short video from a text prompt and show it to the user inline. Provide a \
                detailed 'prompt'. Optionally set 'duration_seconds', 'fps' (frames per second, default \
                24), or an 'aspect_ratio' (1:1, 16:9, 9:16). Generation is asynchronous and takes \
                minutes — the tool returns immediately and the finished video appears in the chat on its \
                own when ready.""";
    }

    @Override
    public String summary() {
        return "Generate a short video from a text prompt (async; appears in chat when ready).";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                SchemaKeys.TYPE, SchemaKeys.OBJECT,
                SchemaKeys.PROPERTIES, Map.of(
                        ARG_PROMPT, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.DESCRIPTION, "A detailed description of the video to generate."),
                        ARG_DURATION, Map.of(SchemaKeys.TYPE, SchemaKeys.INTEGER,
                                SchemaKeys.DESCRIPTION, "Optional clip length in seconds (provider-dependent bounds)."),
                        ARG_FPS, Map.of(SchemaKeys.TYPE, SchemaKeys.INTEGER,
                                SchemaKeys.DESCRIPTION, "Optional frames per second (local engine; default 24)."),
                        ARG_ASPECT, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.ENUM, List.of("1:1", "16:9", "9:16"),
                                SchemaKeys.DESCRIPTION, "Optional aspect ratio.")
                ),
                SchemaKeys.REQUIRED, List.of(ARG_PROMPT)
        );
    }

    /** One submit per call; the long-running generation happens in the runner, not here. */
    @Override public boolean parallelSafe() { return false; }

    @Override
    public String execute(String argsJson, Agent agent) {
        // execute() is the text-only fallback; the dispatcher uses executeRich() (which carries the
        // submitted-job ref). Delegating keeps a single code path.
        return executeRich(argsJson, agent).text();
    }

    @Override
    public ToolRegistry.ToolResult executeRich(String argsJson, Agent agent) {
        JsonObject args;
        try {
            args = JsonParser.parseString(argsJson).getAsJsonObject();
        } catch (RuntimeException _) {
            return ToolRegistry.ToolResult.text("Error: invalid arguments for generate_video.");
        }
        var prompt = optString(args, ARG_PROMPT);
        if (prompt == null || prompt.isBlank()) {
            return ToolRegistry.ToolResult.text("Error: 'prompt' is required.");
        }
        if (VideoGenerationRouter.configuredService().isEmpty()) {
            return ToolRegistry.ToolResult.text(
                    "Video generation is not configured. Ask the operator to enable a provider in "
                            + "Settings → Video Generation.");
        }

        var req = new VideoGenRequest(prompt, null, optInt(args, ARG_DURATION), optString(args, ARG_ASPECT),
                optInt(args, ARG_FPS));
        // Tool execution runs on the [agent-stream] thread, which Play's JPAPlugin never wraps in a JPA
        // transaction, so submit()'s job.save() has no EntityManager of its own. Open one here — the same
        // Tx.run convention every other DB-touching tool (ShellExecTool, TaskTool, …) follows.
        var job = Tx.run(() -> VideoGenerationJobService.submit(agent.id, null, req));
        var text = "Started generating a video for the prompt; it runs in the background (typically a "
                + "few minutes) and appears in the chat automatically when ready. Do not re-embed or "
                + "link it — it shows on its own.";
        return ToolRegistry.ToolResult.withVideoJob(text, job.id, buildMetadata(req, job));
    }

    /** Metadata persisted on the placeholder attachment and surfaced by the chat's video chip
     *  (size · aspect · fps · duration · prompt). {@code fps}/{@code aspectRatio} record the effective
     *  request values (defaults filled in when the caller omits them); {@code durationSeconds} only when
     *  the caller asked for one — the chip prefers the rendered clip's real duration off the {@code
     *  <video>} element and falls back to this. */
    private static String buildMetadata(VideoGenRequest req, VideoGenerationJob job) {
        var meta = new JsonObject();
        meta.addProperty(ARG_PROMPT, req.prompt());
        if (job.provider != null) meta.addProperty("provider", job.provider);
        var model = ConfigService.get("videogen.cloud.model");
        if (model != null && !model.isBlank()) meta.addProperty("model", model);
        meta.addProperty("fps", req.fps() != null && req.fps() > 0 ? req.fps() : DEFAULT_FPS);
        meta.addProperty("aspectRatio",
                req.aspectRatio() != null && !req.aspectRatio().isBlank() ? req.aspectRatio() : DEFAULT_ASPECT);
        if (req.durationSeconds() != null && req.durationSeconds() > 0) {
            meta.addProperty("durationSeconds", req.durationSeconds());
        }
        return meta.toString();
    }

    private static String optString(JsonObject args, String key) {
        return args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsString() : null;
    }

    private static Integer optInt(JsonObject args, String key) {
        return args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsInt() : null;
    }
}
