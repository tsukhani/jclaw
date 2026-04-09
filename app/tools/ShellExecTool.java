package tools;

import agents.ToolRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.Agent;
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

        // Allowlist validation (bypass only for the default/main agent)
        boolean bypassAllowlist = agent.isDefault && "true".equals(
                ConfigService.get("agent." + agent.name + ".shell.bypassAllowlist", "false"));
        if (!bypassAllowlist) {
            var allowlistError = validateAllowlist(command);
            if (allowlistError != null) return allowlistError;
        }

        // Working directory resolution (allowGlobalPaths only for the default/main agent)
        boolean agentAllowGlobal = agent.isDefault && "true".equals(
                ConfigService.get("agent." + agent.name + ".shell.allowGlobalPaths", "false"));
        var workspace = AgentService.workspacePath(agent.name).toAbsolutePath().normalize();
        Path workdir;
        try {
            workdir = resolveWorkdir(args, workspace, agentAllowGlobal);
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }

        // Timeout
        int defaultTimeout = Integer.parseInt(ConfigService.get("shell.defaultTimeoutSeconds", "30"));
        int maxTimeout = Integer.parseInt(ConfigService.get("shell.maxTimeoutSeconds", "300"));
        int timeout = defaultTimeout;
        if (args.has("timeout")) {
            timeout = Math.min(args.get("timeout").getAsInt(), maxTimeout);
            if (timeout <= 0) timeout = defaultTimeout;
        }

        // Max output
        int maxOutputBytes = Integer.parseInt(ConfigService.get("shell.maxOutputBytes", "102400"));

        // Build environment
        var env = buildEnvironment(args);

        // Execute
        return executeCommand(command, workdir, timeout, maxOutputBytes, env, startTime, agent);
    }

    public String validateAllowlist(String command) {
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

    public Path resolveWorkdir(JsonObject args, Path workspace, boolean allowGlobal) {

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

    public Map<String, String> buildEnvironment(JsonObject args) {
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

    public static boolean isSensitiveEnvVar(String name) {
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
                                  int maxOutputBytes, Map<String, String> env, long startTime,
                                  Agent agent) {
        try {
            var pb = new ProcessBuilder("/bin/sh", "-c", command);
            pb.directory(workdir.toFile());
            pb.redirectErrorStream(true);
            pb.environment().clear();
            pb.environment().putAll(env);

            var process = pb.start();

            // Read output incrementally with two early-return mechanisms:
            // 1. Terminal image detected (QR code) → return immediately so user sees it,
            //    but leave process running for the full timeout (user needs to interact).
            // 2. Idle timeout (5s of silence after output) → return for non-interactive commands.
            var is = process.getInputStream();
            var buf = new byte[8192];
            var out = new StringBuilder();
            int totalRead = 0;
            boolean truncated = false;
            long deadline = System.currentTimeMillis() + (timeoutSec * 1000L);
            long lastOutputAt = 0;
            boolean foundTerminalImage = false;
            long IDLE_TIMEOUT_MS = 5000;

            while (System.currentTimeMillis() < deadline) {
                if (is.available() > 0) {
                    int n = is.read(buf);
                    if (n == -1) break;
                    totalRead += n;
                    lastOutputAt = System.currentTimeMillis();
                    if (!truncated) {
                        int remaining = maxOutputBytes - out.length();
                        if (remaining <= 0) {
                            truncated = true;
                        } else if (n > remaining) {
                            out.append(new String(buf, 0, remaining));
                            truncated = true;
                        } else {
                            out.append(new String(buf, 0, n));
                        }
                    }

                    // Check for terminal image — if found, return early but keep process alive
                    if (!foundTerminalImage && hasTerminalImage(out.toString())) {
                        foundTerminalImage = true;
                        var processedOutput = replaceTerminalImagesInOutput(out.toString(), agent);
                        long durationMs = System.currentTimeMillis() - startTime;

                        // Keep the process running in the background for the full timeout
                        // so the user can interact (e.g., scan QR code, wait for sync)
                        Thread.startVirtualThread(() -> {
                            try {
                                process.waitFor(timeoutSec, TimeUnit.SECONDS);
                            } catch (InterruptedException _) {}
                            finally {
                                if (process.isAlive()) process.destroyForcibly();
                            }
                        });

                        var result = new JsonObject();
                        result.addProperty("exitCode", -1);
                        result.addProperty("output", processedOutput
                                + "\n[Process still running in background — waiting for user interaction. Will timeout after %d seconds."
                                        .formatted(timeoutSec)
                                + " The image above is already visible to the user in the chat. Do NOT try to read or fetch it.]");
                        result.addProperty("durationMs", durationMs);
                        result.addProperty("truncated", truncated);
                        result.addProperty("timedOut", false);
                        return result.toString();
                    }
                } else if (!process.isAlive()) {
                    break;
                } else if (lastOutputAt > 0 && (System.currentTimeMillis() - lastOutputAt) > IDLE_TIMEOUT_MS) {
                    break; // Non-interactive idle — return early
                } else {
                    Thread.sleep(100);
                }
            }

            // Drain remaining output
            while (is.available() > 0) {
                int n = is.read(buf);
                if (n == -1) break;
                totalRead += n;
                if (!truncated && out.length() < maxOutputBytes) {
                    out.append(new String(buf, 0, Math.min(n, maxOutputBytes - out.length())));
                }
            }

            boolean completed = !process.isAlive();
            if (!completed) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }

            if (truncated) {
                out.append("\n[Output truncated at %dKB. Total output: %d bytes]"
                        .formatted(maxOutputBytes / 1024, totalRead));
            }

            long durationMs = System.currentTimeMillis() - startTime;
            var processedOutput = replaceTerminalImagesInOutput(out.toString(), agent);

            var result = new JsonObject();
            result.addProperty("exitCode", completed ? process.exitValue() : -1);
            result.addProperty("output", processedOutput + (!completed ? "\n[Process killed: timeout after %d seconds]".formatted(timeoutSec) : ""));
            result.addProperty("durationMs", durationMs);
            result.addProperty("truncated", truncated);
            result.addProperty("timedOut", !completed);
            return result.toString();

        } catch (IOException e) {
            return "Error: Failed to execute command: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Error: Command execution interrupted.";
        }
    }

    // --- Terminal image rendering ---

    /**
     * Detect QR codes or other terminal-rendered images in command output,
     * render them as PNGs, and replace the block art in the output with
     * markdown image URLs that the LLM will include in its response.
     */
    private String replaceTerminalImagesInOutput(String output, Agent agent) {
        var lines = output.split("\n");
        var result = new StringBuilder();
        var qrLines = new java.util.ArrayList<String>();
        var qrStartIdx = -1;
        boolean inQrBlock = false;

        for (int i = 0; i < lines.length; i++) {
            var line = lines[i];
            boolean isBlockLine = isBlockArtLine(line);

            if (isBlockLine) {
                if (!inQrBlock) qrStartIdx = i;
                inQrBlock = true;
                qrLines.add(line);
            } else {
                if (inQrBlock) {
                    // End of block art — render if substantial
                    if (qrLines.size() >= 5) {
                        var imageUrl = renderBlockArtToPng(qrLines, agent);
                        if (imageUrl != null) {
                            result.append(imageUrl).append("\n");
                        }
                    } else {
                        // Too small, keep original lines
                        for (var ql : qrLines) result.append(ql).append("\n");
                    }
                    qrLines.clear();
                    inQrBlock = false;
                }
                result.append(line).append("\n");
            }
        }
        // Handle block art at end of output
        if (inQrBlock && qrLines.size() >= 5) {
            var imageUrl = renderBlockArtToPng(qrLines, agent);
            if (imageUrl != null) {
                result.append(imageUrl).append("\n");
            }
        } else {
            for (var ql : qrLines) result.append(ql).append("\n");
        }

        return result.toString().stripTrailing();
    }

    /** Quick check if the output contains enough block art lines to constitute a terminal image. */
    private boolean hasTerminalImage(String output) {
        int blockLines = 0;
        for (var line : output.split("\n")) {
            if (isBlockArtLine(line)) {
                blockLines++;
                if (blockLines >= 5) return true;
            } else {
                blockLines = 0;
            }
        }
        return false;
    }

    private boolean isBlockArtLine(String line) {
        if (line.length() <= 10) return false;
        long blockChars = line.chars().filter(c ->
                c == '\u2588' || c == '\u2580' || c == '\u2584' || c == '\u258C' || c == '\u2590' ||
                c == '\u2591' || c == '\u2592' || c == '\u2593' || c == '\u258A' || c == '\u258B' ||
                c == '\u258D' || c == '\u258E' || c == '\u258F' || c == ' '
        ).count();
        return blockChars > line.length() * 0.7;
    }

    /**
     * Render Unicode block art (QR code, etc.) to a PNG image using Java2D.
     *
     * Unicode half-block characters encode two vertical pixels per character:
     *   █ (U+2588) = top black, bottom black
     *   ▀ (U+2580) = top black, bottom white
     *   ▄ (U+2584) = top white, bottom black
     *   ' ' (space) = top white, bottom white
     *
     * Each character cell maps to cellSize x (cellSize*2) pixels to preserve the
     * 1:2 aspect ratio of half-block encoding.
     */
    private String renderBlockArtToPng(List<String> lines, Agent agent) {
        try {
            int cellW = 8;  // pixels per character width
            int cellH = 8;  // pixels per HALF character height (each char = 2 vertical halves)
            int maxWidth = lines.stream().mapToInt(String::length).max().orElse(0);
            int imgWidth = maxWidth * cellW;
            int imgHeight = lines.size() * cellH * 2; // *2 because each char line = 2 pixel rows

            if (imgWidth <= 0 || imgHeight <= 0 || imgWidth > 8000 || imgHeight > 8000) return null;

            var img = new java.awt.image.BufferedImage(imgWidth, imgHeight, java.awt.image.BufferedImage.TYPE_INT_RGB);
            var g = img.createGraphics();
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, imgWidth, imgHeight);

            for (int row = 0; row < lines.size(); row++) {
                var line = lines.get(row);
                int py = row * cellH * 2; // pixel y for this character row

                for (int col = 0; col < line.length(); col++) {
                    char c = line.charAt(col);
                    int px = col * cellW;

                    // Determine top half and bottom half colors
                    boolean topBlack = false, bottomBlack = false;
                    switch (c) {
                        case '\u2588': // █ full block
                        case '\u258A': // ▊
                        case '\u258B': // ▋
                            topBlack = true; bottomBlack = true; break;
                        case '\u2580': // ▀ upper half
                            topBlack = true; break;
                        case '\u2584': // ▄ lower half
                            bottomBlack = true; break;
                        case '\u258C': // ▌ left half — treat as full for QR
                        case '\u2590': // ▐ right half
                            topBlack = true; bottomBlack = true; break;
                        case '\u2593': // ▓ dark shade
                            topBlack = true; bottomBlack = true; break;
                        case '\u2592': // ▒ medium shade
                        case '\u2591': // ░ light shade
                            break; // leave white
                        case ' ':
                            break; // white
                        default:
                            // Unknown char — treat as black if it's a block-range char
                            if (c >= '\u2580' && c <= '\u259F') {
                                topBlack = true; bottomBlack = true;
                            }
                            break;
                    }

                    if (topBlack) {
                        g.setColor(java.awt.Color.BLACK);
                        g.fillRect(px, py, cellW, cellH);
                    }
                    if (bottomBlack) {
                        g.setColor(java.awt.Color.BLACK);
                        g.fillRect(px, py + cellH, cellW, cellH);
                    }
                }
            }
            g.dispose();

            var timestamp = System.currentTimeMillis();
            var filename = "terminal-image-%d.png".formatted(timestamp);
            var path = services.AgentService.workspacePath(agent.name).resolve(filename);
            javax.imageio.ImageIO.write(img, "PNG", path.toFile());

            var url = "/api/agents/%d/files/%s".formatted(agent.id, filename);
            // Use full markdown image syntax — this is a web URL for the chat UI, not a file path
            return "![QR Code](%s)".formatted(url);

        } catch (Exception e) {
            services.EventLogger.warn("tool", "Failed to render terminal image: %s".formatted(e.getMessage()));
            return null;
        }
    }
}
