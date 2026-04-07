package tools;

import agents.ToolRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.Agent;
import play.Play;
import services.AgentService;
import services.ConfigService;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class ShellExecTool implements ToolRegistry.Tool {

    private static final String DEFAULT_ALLOWLIST = "git,npm,npx,pnpm,node,python,python3,pip,ls,cat,head,tail,grep,find,wc,sort,uniq,diff,mkdir,cp,mv,echo,curl,wget,jq,tar,zip,unzip";

    private static final Set<String> SENSITIVE_NAME_PATTERNS = Set.of(
            "key", "secret", "token", "password", "credential"
    );
    private static final Set<String> SENSITIVE_PREFIXES = Set.of(
            "AWS_", "ANTHROPIC_", "OPENAI_", "GOOGLE_", "AZURE_"
    );

    @Override
    public String name() { return "exec"; }

    @Override
    public String description() {
        return """
                Execute a shell command on the host system. Commands are validated against an \
                allowlist of permitted binaries. Working directory defaults to the agent workspace. \
                Returns exit code, output, and execution metadata.""";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "command", Map.of("type", "string",
                                "description", "Shell command to execute"),
                        "workdir", Map.of("type", "string",
                                "description", "Working directory (relative to workspace, or absolute if global paths enabled)"),
                        "timeout", Map.of("type", "integer",
                                "description", "Timeout in seconds (default: 30, max: 300)"),
                        "env", Map.of("type", "object",
                                "description", "Additional environment variables as key-value pairs",
                                "additionalProperties", Map.of("type", "string"))
                ),
                "required", List.of("command")
        );
    }

    @Override
    public String execute(String argsJson, Agent agent) {
        long startTime = System.currentTimeMillis();
        var args = JsonParser.parseString(argsJson).getAsJsonObject();

        var command = args.has("command") ? args.get("command").getAsString().trim() : "";
        if (command.isEmpty()) {
            return "Error: command is required and must not be empty.";
        }

        // Allowlist validation
        var allowlistError = validateAllowlist(command);
        if (allowlistError != null) return allowlistError;

        // Working directory resolution
        var workspace = AgentService.workspacePath(agent.name).toAbsolutePath().normalize();
        Path workdir;
        try {
            workdir = resolveWorkdir(args, workspace);
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }

        // Timeout
        int defaultTimeout = Integer.parseInt(
                Play.configuration.getProperty("jclaw.tools.shell.defaultTimeoutSeconds", "30"));
        int maxTimeout = Integer.parseInt(
                Play.configuration.getProperty("jclaw.tools.shell.maxTimeoutSeconds", "300"));
        int timeout = defaultTimeout;
        if (args.has("timeout")) {
            timeout = Math.min(args.get("timeout").getAsInt(), maxTimeout);
            if (timeout <= 0) timeout = defaultTimeout;
        }

        // Max output
        int maxOutputBytes = Integer.parseInt(
                Play.configuration.getProperty("jclaw.tools.shell.maxOutputBytes", "102400"));

        // Build environment
        var env = buildEnvironment(args);

        // Execute
        return executeCommand(command, workdir, timeout, maxOutputBytes, env, startTime);
    }

    String validateAllowlist(String command) {
        var firstToken = extractFirstToken(command);
        if (firstToken.isEmpty()) {
            return "Error: command is required and must not be empty.";
        }

        var allowlistStr = ConfigService.get("shell.allowlist", DEFAULT_ALLOWLIST);
        var allowed = Arrays.stream(allowlistStr.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        if (allowed.isEmpty() || !allowed.contains(firstToken)) {
            return "Error: Command '%s' is not in the allowed commands list. Allowed: %s"
                    .formatted(firstToken, String.join(", ", allowed));
        }
        return null;
    }

    static String extractFirstToken(String command) {
        var trimmed = command.trim();
        var idx = -1;
        for (int i = 0; i < trimmed.length(); i++) {
            if (Character.isWhitespace(trimmed.charAt(i))) {
                idx = i;
                break;
            }
        }
        return idx == -1 ? trimmed : trimmed.substring(0, idx);
    }

    Path resolveWorkdir(JsonObject args, Path workspace) {
        boolean allowGlobal = "true".equals(
                Play.configuration.getProperty("jclaw.tools.shell.allowGlobalPaths", "false"));

        if (!args.has("workdir") || args.get("workdir").getAsString().trim().isEmpty()) {
            if (!Files.isDirectory(workspace)) {
                try { Files.createDirectories(workspace); } catch (IOException ignored) {}
            }
            return workspace;
        }

        var workdirStr = args.get("workdir").getAsString().trim();
        var workdirPath = Path.of(workdirStr);

        if (workdirPath.isAbsolute()) {
            if (!allowGlobal) {
                throw new IllegalArgumentException(
                        "Working directory must be within the agent workspace. Absolute paths require shell.allowGlobalPaths=true.");
            }
            return workdirPath;
        }

        // Relative path — resolve within workspace
        var resolved = workspace.resolve(workdirStr).normalize();
        if (!resolved.startsWith(workspace)) {
            throw new IllegalArgumentException(
                    "Working directory must be within the agent workspace.");
        }
        return resolved;
    }

    Map<String, String> buildEnvironment(JsonObject args) {
        var env = new LinkedHashMap<String, String>();

        // Start with filtered host environment
        for (var entry : System.getenv().entrySet()) {
            if (!isSensitiveEnvVar(entry.getKey())) {
                env.put(entry.getKey(), entry.getValue());
            }
        }

        // Merge custom env vars (blocking sensitive names)
        if (args.has("env") && args.get("env").isJsonObject()) {
            var customEnv = args.getAsJsonObject("env");
            for (var key : customEnv.keySet()) {
                if (!isSensitiveEnvVar(key)) {
                    env.put(key, customEnv.get(key).getAsString());
                }
            }
        }

        return env;
    }

    static boolean isSensitiveEnvVar(String name) {
        var upper = name.toUpperCase();
        for (var prefix : SENSITIVE_PREFIXES) {
            if (upper.startsWith(prefix)) return true;
        }
        var lower = name.toLowerCase();
        for (var pattern : SENSITIVE_NAME_PATTERNS) {
            if (lower.contains(pattern)) return true;
        }
        return false;
    }

    private String executeCommand(String command, Path workdir, int timeoutSec,
                                  int maxOutputBytes, Map<String, String> env, long startTime) {
        try {
            var pb = new ProcessBuilder("/bin/sh", "-c", command);
            pb.directory(workdir.toFile());
            pb.redirectErrorStream(true);
            pb.environment().clear();
            pb.environment().putAll(env);

            var process = pb.start();
            var output = captureOutput(process.getInputStream(), maxOutputBytes);
            boolean completed = process.waitFor(timeoutSec, TimeUnit.SECONDS);

            if (!completed) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                long durationMs = System.currentTimeMillis() - startTime;
                var result = new JsonObject();
                result.addProperty("exitCode", -1);
                result.addProperty("output", output.text() + "\n[Process killed: timeout after %d seconds]".formatted(timeoutSec));
                result.addProperty("durationMs", durationMs);
                result.addProperty("truncated", output.truncated());
                result.addProperty("timedOut", true);
                return result.toString();
            }

            long durationMs = System.currentTimeMillis() - startTime;
            var result = new JsonObject();
            result.addProperty("exitCode", process.exitValue());
            result.addProperty("output", output.text());
            result.addProperty("durationMs", durationMs);
            result.addProperty("truncated", output.truncated());
            result.addProperty("timedOut", false);
            return result.toString();

        } catch (IOException e) {
            return "Error: Failed to execute command: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Error: Command execution interrupted.";
        }
    }

    private record CapturedOutput(String text, boolean truncated) {}

    private CapturedOutput captureOutput(InputStream is, int maxBytes) throws IOException {
        var buf = new byte[8192];
        var out = new StringBuilder();
        int totalRead = 0;
        boolean truncated = false;
        int n;

        while ((n = is.read(buf)) != -1) {
            totalRead += n;
            if (!truncated) {
                int remaining = maxBytes - out.length();
                if (remaining <= 0) {
                    truncated = true;
                } else if (n > remaining) {
                    out.append(new String(buf, 0, remaining));
                    truncated = true;
                } else {
                    out.append(new String(buf, 0, n));
                }
            }
            // Continue reading even after truncation to drain the stream and allow process to complete
        }

        if (truncated) {
            out.append("\n[Output truncated at %dKB. Total output: %d bytes]"
                    .formatted(maxBytes / 1024, totalRead));
        }

        return new CapturedOutput(out.toString(), truncated);
    }
}
