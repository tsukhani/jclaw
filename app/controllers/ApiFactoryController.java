package controllers;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.jspecify.annotations.Nullable;
import play.mvc.Controller;
import play.mvc.With;
import services.EventLogger;
import services.factory.FactoryHome;
import services.factory.FactoryProcess;
import utils.ApiResponses;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.regex.Pattern;

import static controllers.AgentAccess.Level.OPERATOR_ONLY;
import static utils.GsonHolder.GSON;

/**
 * Software Factory controls (JCLAW-1392): start or stop the AFK harness, pause or resume its
 * gateway, stop one sandbox, and read or edit {@code settings.env}. The terminal equivalents are
 * in {@code .sandcastle/README.md} under Operating and Settings.
 */
@With(AuthCheck.class)
public class ApiFactoryController extends Controller {

    private static final String CATEGORY = "factory";
    private static final String GATEWAY = "jclaw-factory-gateway";
    private static final Pattern SANDBOX_NAME = Pattern.compile(
            "^sandcastle-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", Pattern.CASE_INSENSITIVE);

    // install-agent.sh may build the sandbox image the first time.
    private static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration REMOVE_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration DOCKER_ACTION_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration DOCKER_QUERY_TIMEOUT = Duration.ofSeconds(15);

    static final String PAUSE_MESSAGE = "Gateway paused: no new stories start, and stories already running will fail.";
    static final String SETTINGS_MESSAGE = "The harness applies changes once it is idle.";

    /**
     * @param exitCode the command's exit code; -1 on a timeout or when it could not start
     * @param output   the tail of stdout and stderr together
     */
    public record CommandResult(int exitCode, boolean timedOut, String output, String message) {}

    /** {@link CommandResult} plus the story whose sandbox was stopped, or null when the board names none. */
    public record SandboxStopResult(int exitCode, boolean timedOut, String output, String message,
                                    @Nullable String story) {}

    /** @param set whether {@code settings.env} holds the key; {@code value} is the default otherwise */
    public record SettingEntry(String key, String value, String defaultValue, boolean set) {}

    /** @param message null on a read; what happens next on a write */
    public record SettingsView(List<SettingEntry> settings, @Nullable String message) {}

    /** POST /api/factory/harness/start — run {@code .sandcastle/install-agent.sh}. */
    @AgentAccess(value = OPERATOR_ONLY, reason = "installs and starts the AFK harness on the operator's machine")
    public static void harnessStart() {
        requireInstalled("start the harness");
        var installer = FactoryHome.installer().toString();
        renderCommand("harness start", FactoryProcess.run(List.of(installer), INSTALL_TIMEOUT), "");
    }

    /** POST /api/factory/harness/stop — run {@code .sandcastle/install-agent.sh --remove}. */
    @AgentAccess(value = OPERATOR_ONLY, reason = "stops the AFK harness on the operator's machine")
    public static void harnessStop() {
        requireInstalled("stop the harness");
        var installer = FactoryHome.installer().toString();
        renderCommand("harness stop",
                FactoryProcess.run(List.of(installer, "--remove"), REMOVE_TIMEOUT), "");
    }

    /** POST /api/factory/gateway/pause — {@code docker stop jclaw-factory-gateway}. */
    @AgentAccess(value = OPERATOR_ONLY, reason = "pauses the factory, failing stories already running")
    public static void gatewayPause() {
        renderCommand("gateway pause",
                FactoryProcess.run(List.of("docker", "stop", GATEWAY), DOCKER_ACTION_TIMEOUT), PAUSE_MESSAGE);
    }

    /** POST /api/factory/gateway/resume — {@code docker start jclaw-factory-gateway}. */
    @AgentAccess(value = OPERATOR_ONLY, reason = "resumes the factory's model and egress gateway")
    public static void gatewayResume() {
        renderCommand("gateway resume",
                FactoryProcess.run(List.of("docker", "start", GATEWAY), DOCKER_ACTION_TIMEOUT), "Gateway resumed.");
    }

    /** POST /api/factory/sandboxes/{name}/stop — stop one running {@code sandcastle-<uuid>} container. */
    @AgentAccess(value = OPERATOR_ONLY, reason = "stops a factory sandbox, failing the story running in it")
    public static void sandboxStop(String name) {
        if (!SANDBOX_NAME.matcher(name).matches()) {
            throw refuse(404, ApiResponses.NOT_FOUND, "stop sandbox " + name, "it is not a sandcastle sandbox name");
        }
        if (!isRunning(name)) {
            throw refuse(404, ApiResponses.NOT_FOUND, "stop sandbox " + name, "Docker lists no such running container");
        }
        var story = FactoryHome.storyForSandbox(FactoryHome.boardFile(), name);
        var res = FactoryProcess.run(List.of("docker", "stop", name), DOCKER_ACTION_TIMEOUT);
        var message = story != null ? "Story " + story + " will fail." : "No story on the board ran in this sandbox.";
        log("sandbox stop " + name + (story != null ? " (story " + story + ")" : ""), res);
        renderJSON(GSON.toJson(new SandboxStopResult(res.exitCode(), res.timedOut(), res.tail(), message, story)));
    }

    /** GET /api/factory/settings — the four settings with their values and defaults. */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = SettingsView.class)))
    @AgentAccess(OPERATOR_ONLY)
    public static void settings() {
        renderJSON(GSON.toJson(new SettingsView(readView(), null)));
    }

    /** PUT /api/factory/settings — partial update of {@code settings.env}, validated, written atomically. */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = SettingsView.class)))
    @AgentAccess(value = OPERATOR_ONLY, reason = "rewrites the AFK harness's settings.env")
    public static void updateSettings() {
        var action = "update factory settings";
        var body = JsonBodyReader.readJsonBody();
        if (body == null) throw refuse(400, ApiResponses.INVALID_REQUEST, action, "the body is not a JSON object");
        var updates = new LinkedHashMap<String, String>();
        for (var e : body.entrySet()) {
            if (!e.getValue().isJsonPrimitive()) {
                throw refuse(400, ApiResponses.INVALID_REQUEST, action, e.getKey() + " must be a string or a number");
            }
            updates.put(e.getKey(), e.getValue().getAsString());
        }
        var cpus = updates.containsKey(FactoryHome.KEY_CPUS) ? dockerCpus() : OptionalInt.empty();
        var invalid = FactoryHome.validate(updates, cpus);
        if (invalid != null) throw refuse(400, ApiResponses.INVALID_REQUEST, action, invalid);
        if (!Files.isDirectory(FactoryHome.home())) {
            throw refuse(409, ApiResponses.CONFLICT, action, "the factory home " + FactoryHome.home() + " does not exist");
        }

        String failure = null;
        try {
            FactoryHome.writeSettings(FactoryHome.settingsFile(), updates);
        } catch (IOException e) {
            failure = ApiResponses.messageOf(e);
        }
        if (failure != null) {
            EventLogger.warn(CATEGORY, "Operator could not update factory settings: " + failure);
            ApiResponses.error(500, ApiResponses.INTERNAL_ERROR, "Could not write settings.env: " + failure);
        }
        EventLogger.info(CATEGORY, "Operator updated factory settings: " + String.join(", ", updates.keySet()));
        renderJSON(GSON.toJson(new SettingsView(readView(), SETTINGS_MESSAGE)));
    }

    private static List<SettingEntry> readView() {
        Map<String, String> current;
        try {
            current = FactoryHome.readSettings(FactoryHome.settingsFile());
        } catch (IOException _) {
            current = Map.of();
        }
        var out = new ArrayList<SettingEntry>();
        for (var e : FactoryHome.SETTINGS.entrySet()) {
            var value = current.get(e.getKey());
            out.add(new SettingEntry(e.getKey(), value != null ? value : e.getValue(), e.getValue(), value != null));
        }
        return out;
    }

    private static void requireInstalled(String action) {
        if (!FactoryHome.installed()) {
            throw refuse(409, ApiResponses.CONFLICT, action,
                    "the factory is not installed (it needs .sandcastle/install-agent.sh, "
                            + "<factory home>/.env and <factory home>/jira.env)");
        }
    }

    private static boolean isRunning(String name) {
        var res = FactoryProcess.run(
                List.of("docker", "ps", "--filter", "name=^" + name + "$", "--format", "{{.Names}}"),
                DOCKER_QUERY_TIMEOUT);
        return res.ok() && res.output().lines().map(String::trim).anyMatch(name::equals);
    }

    private static OptionalInt dockerCpus() {
        var res = FactoryProcess.run(List.of("docker", "info", "--format", "{{.NCPU}}"), DOCKER_QUERY_TIMEOUT);
        if (!res.ok()) return OptionalInt.empty();
        try {
            return OptionalInt.of(Integer.parseInt(res.output().trim()));
        } catch (NumberFormatException _) {
            return OptionalInt.empty();
        }
    }

    private static AssertionError refuse(int status, String code, String action, String reason) {
        EventLogger.warn(CATEGORY, "Operator request to " + action + " refused: " + reason);
        ApiResponses.error(status, code, "Cannot " + action + ": " + reason + ".");
        return ApiResponses.unreachable();
    }

    private static void log(String did, FactoryProcess.ExecResult res) {
        if (res.timedOut()) {
            EventLogger.warn(CATEGORY, "Operator ran " + did + ": timed out", res.tail());
        } else if (res.exitCode() != 0) {
            EventLogger.warn(CATEGORY, "Operator ran " + did + ": failed with exit code " + res.exitCode(), res.tail());
        } else {
            EventLogger.info(CATEGORY, "Operator ran " + did + ": exit code 0");
        }
    }

    private static void renderCommand(String did, FactoryProcess.ExecResult res, String successMessage) {
        log(did, res);
        String message;
        if (res.timedOut()) message = "The command timed out.";
        else if (res.exitCode() != 0) message = "The command failed with exit code " + res.exitCode() + ".";
        else message = successMessage;
        renderJSON(GSON.toJson(new CommandResult(res.exitCode(), res.timedOut(), res.tail(), message)));
    }
}
