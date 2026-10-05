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
import services.factory.FactoryStatus;
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
 * Software Factory status (JCLAW-1391) and controls (JCLAW-1392): read the factory's state, board
 * and story logs; start or stop the AFK harness, pause or resume its gateway, stop one sandbox,
 * and read or edit {@code settings.env}. The terminal equivalents are
 * in {@code .sandcastle/README.md} under Operating and Settings.
 */
@With(AuthCheck.class)
public class ApiFactoryController extends Controller {

    private static final String CATEGORY = "factory";
    private static final String GATEWAY = "jclaw-factory-gateway";
    private static final String DOCKER = "docker";
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

    /** {@link CommandResult} plus the story whose sandbox was stopped, or null when it held none. */
    public record SandboxStopResult(int exitCode, boolean timedOut, String output, String message,
                                    @Nullable String story) {}

    /** @param set whether {@code settings.env} holds the key; {@code value} is the default otherwise */
    public record SettingEntry(String key, String value, String defaultValue, boolean set) {}

    /** @param message null on a read; what happens next on a write */
    public record SettingsView(List<SettingEntry> settings, @Nullable String message) {}

    /** GET /api/factory — installed, supported, harness, gateway, sandboxes and the parsed board. */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = FactoryStatus.StatusView.class)))
    @AgentAccess(OPERATOR_ONLY)
    public static void status() {
        renderJSON(GSON.toJson(FactoryStatus.current()));
    }

    /** GET /api/factory/stories/{key}/logs/{file} — the last 256 KB of a log board.json lists for the story. */
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = FactoryStatus.LogView.class)))
    @AgentAccess(OPERATOR_ONLY)
    public static void storyLog(String key, String file) {
        FactoryStatus.LogView view;
        try {
            view = FactoryStatus.log(key, file);
        } catch (IOException e) {
            EventLogger.warn(CATEGORY, "Could not read factory log " + file + ": " + ApiResponses.messageOf(e));
            view = null;
        }
        if (view == null) {
            ApiResponses.error(404, ApiResponses.NOT_FOUND, "No log " + file + " for story " + key + ".");
            throw ApiResponses.unreachable();
        }
        renderJSON(GSON.toJson(view));
    }

    /** POST /api/factory/harness/start — run {@code .sandcastle/install-agent.sh}. */
    @AgentAccess(value = OPERATOR_ONLY, reason = "installs and starts the AFK harness on the operator's machine")
    public static void harnessStart() {
        requireInstalled("start the harness");
        var installer = FactoryHome.installer().toString();
        renderCommand("harness start", exclusive("start the harness", List.of(installer), INSTALL_TIMEOUT), "");
    }

    /** POST /api/factory/harness/stop — run {@code .sandcastle/install-agent.sh --remove}. */
    @AgentAccess(value = OPERATOR_ONLY, reason = "stops the AFK harness on the operator's machine")
    public static void harnessStop() {
        requireInstalled("stop the harness");
        var installer = FactoryHome.installer().toString();
        renderCommand("harness stop",
                exclusive("stop the harness", List.of(installer, "--remove"), REMOVE_TIMEOUT), "");
    }

    /** POST /api/factory/gateway/pause — {@code docker stop jclaw-factory-gateway}. */
    @AgentAccess(value = OPERATOR_ONLY, reason = "pauses the factory, failing stories already running")
    public static void gatewayPause() {
        renderCommand("gateway pause",
                FactoryProcess.run(List.of(DOCKER, "stop", GATEWAY), DOCKER_ACTION_TIMEOUT), PAUSE_MESSAGE);
    }

    /** POST /api/factory/gateway/resume — {@code docker start jclaw-factory-gateway}. */
    @AgentAccess(value = OPERATOR_ONLY, reason = "resumes the factory's model and egress gateway")
    public static void gatewayResume() {
        renderCommand("gateway resume",
                FactoryProcess.run(List.of(DOCKER, "start", GATEWAY), DOCKER_ACTION_TIMEOUT), "Gateway resumed.");
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
        var story = storyIn(name);
        var res = FactoryProcess.run(List.of(DOCKER, "stop", name), DOCKER_ACTION_TIMEOUT);
        var message = res.ok() ? stoppedMessage(story) : failureMessage(res);
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

    private static FactoryProcess.ExecResult exclusive(String action, List<String> command, Duration timeout) {
        var res = FactoryProcess.runHarnessCommand(command, timeout);
        if (res == null) throw refuse(409, ApiResponses.CONFLICT, action, "another harness command is still running");
        return res;
    }

    private static void requireInstalled(String action) {
        if (!FactoryHome.installed()) {
            throw refuse(409, ApiResponses.CONFLICT, action,
                    "the factory is not installed (it needs .sandcastle/install-agent.sh, "
                            + "<factory home>/.env and <factory home>/jira.env)");
        }
    }

    private static @Nullable String storyIn(String name) {
        var res = FactoryProcess.run(
                List.of(DOCKER, "inspect", "--format", "{{range .Mounts}}{{println .Source}}{{end}}", name),
                DOCKER_QUERY_TIMEOUT);
        return res.ok() ? FactoryHome.storyFromMounts(res.output()) : null;
    }

    private static boolean isRunning(String name) {
        var res = FactoryProcess.run(
                List.of(DOCKER, "ps", "--filter", "name=^" + name + "$", "--format", "{{.Names}}"),
                DOCKER_QUERY_TIMEOUT);
        return res.ok() && res.output().lines().map(String::trim).anyMatch(name::equals);
    }

    private static OptionalInt dockerCpus() {
        var res = FactoryProcess.run(List.of(DOCKER, "info", "--format", "{{.NCPU}}"), DOCKER_QUERY_TIMEOUT);
        if (!res.ok()) return OptionalInt.empty();
        // stderr is merged in, so skip any WARNING lines docker prints around the count.
        for (var line : res.output().lines().map(String::trim).toList()) {
            if (!line.matches("\\d+")) continue;
            try {
                var n = Integer.parseInt(line);
                if (n >= 1) return OptionalInt.of(n);
            } catch (NumberFormatException _) {
                // beyond int range: not a CPU count
            }
        }
        return OptionalInt.empty();
    }

    private static AssertionError refuse(int status, String code, String action, String reason) {
        EventLogger.warn(CATEGORY, "Operator request to " + action + " refused: " + reason);
        ApiResponses.error(status, code, "Cannot " + action + ": " + reason + ".");
        return ApiResponses.unreachable();
    }

    private static void log(String did, FactoryProcess.ExecResult res) {
        var ran = "Operator ran " + did;
        if (res.timedOut()) {
            EventLogger.warn(CATEGORY, ran + ": timed out", res.tail());
        } else if (res.exitCode() != 0) {
            EventLogger.warn(CATEGORY, ran + ": failed with exit code " + res.exitCode(), res.tail());
        } else {
            EventLogger.info(CATEGORY, ran + ": exit code 0");
        }
    }

    private static String stoppedMessage(@Nullable String story) {
        return story != null ? "Story " + story + " will fail." : "No story ran in this sandbox.";
    }

    private static void renderCommand(String did, FactoryProcess.ExecResult res, String successMessage) {
        log(did, res);
        var message = res.ok() ? successMessage : failureMessage(res);
        renderJSON(GSON.toJson(new CommandResult(res.exitCode(), res.timedOut(), res.tail(), message)));
    }

    private static String failureMessage(FactoryProcess.ExecResult res) {
        return res.timedOut() ? "The command timed out." : "The command failed with exit code " + res.exitCode() + ".";
    }
}
