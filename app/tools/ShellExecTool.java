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

        var command = args.has("command") ? args.get("command").getAsString().strip() : "";
        if (command.isEmpty()) {
            return "Error: command is required and must not be empty.";
        }

        // Allowlist validation (bypass only for the main agent — identity-checked by name, not config)
        boolean bypassAllowlist = agent.isMain() && "true".equals(
                ConfigService.get("agent." + agent.name + ".shell.bypassAllowlist", "false"));
        if (!bypassAllowlist) {
            var allowlistError = validateAllowlist(command);
            if (allowlistError != null) return allowlistError;
        }

        // Working directory resolution (allowGlobalPaths only for the main agent)
        boolean agentAllowGlobal = agent.isMain() && "true".equals(
                ConfigService.get("agent." + agent.name + ".shell.allowGlobalPaths", "false"));
        var workspace = AgentService.workspacePath(agent.name).toAbsolutePath().normalize();
        Path workdir;
        try {
            workdir = resolveWorkdir(args, workspace, agentAllowGlobal, agent.name);
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }

        // Timeout
        var defaultTimeout = ConfigService.getInt("shell.defaultTimeoutSeconds", 30);
        var maxTimeout = ConfigService.getInt("shell.maxTimeoutSeconds", 300);
        var timeout = defaultTimeout;
        if (args.has("timeout")) {
            timeout = Math.min(args.get("timeout").getAsInt(), maxTimeout);
            if (timeout <= 0) timeout = defaultTimeout;
        }

        // Max output
        var maxOutputBytes = ConfigService.getInt("shell.maxOutputBytes", 102400);

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
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();

        if (allowed.isEmpty() || !allowed.contains(firstToken)) {
            return "Error: Command '%s' is not in the allowed commands list. Allowed: %s"
                    .formatted(firstToken, String.join(", ", allowed));
        }
        return null;
    }

    static String extractFirstToken(String command) {
        var trimmed = command.strip();
        var idx = -1;
        for (int i = 0; i < trimmed.length(); i++) {
            if (Character.isWhitespace(trimmed.charAt(i))) {
                idx = i;
                break;
            }
        }
        return idx == -1 ? trimmed : trimmed.substring(0, idx);
    }

    public Path resolveWorkdir(JsonObject args, Path workspace, boolean allowGlobal, String agentName) {

        if (!args.has("workdir") || args.get("workdir").getAsString().strip().isEmpty()) {
            if (!Files.isDirectory(workspace)) {
                try { Files.createDirectories(workspace); } catch (IOException _) {}
            }
            return workspace;
        }

        var workdirStr = args.get("workdir").getAsString().strip();
        var workdirPath = Path.of(workdirStr);

        if (workdirPath.isAbsolute()) {
            if (!allowGlobal) {
                throw new IllegalArgumentException(
                        ("Working directory must be within the agent workspace. Absolute paths require "
                                + "agent.%s.shell.allowGlobalPaths=true and are only honored for the main agent.")
                                .formatted(agentName));
            }
            return workdirPath;
        }

        // Relative path — resolve within workspace via the canonical helper.
        // acquireContained does lexical + canonical (realpath) validation plus
        // a double-resolve, so a symlink inside the workspace pointing outside
        // is rejected here as well, not just textual `..` traversal.
        try {
            return AgentService.acquireContained(workspace, workdirStr);
        } catch (SecurityException e) {
            throw new IllegalArgumentException(
                    "Working directory must be within the agent workspace.");
        }
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

            // Read output with blocking reads and a watchdog thread that
            // enforces the overall deadline. Previous approach used
            // is.available() polling + 100ms sleep (busy-wait) and a 5-second
            // idle timeout that prematurely cut off slow commands (npm install,
            // long builds) that simply paused between writes. Blocking is.read()
            // on a virtual thread is free (no platform thread consumed during
            // the block) and returns naturally when the process writes or exits.
            var is = process.getInputStream();
            var out = new StringBuilder();
            int totalRead = 0;
            boolean truncated = false;
            boolean foundTerminalImage = false;

            // Watchdog: destroy the process after the configured timeout.
            // When destroyed, is.read() in the main loop returns -1 or throws,
            // breaking the loop cleanly. The flag distinguishes a watchdog kill
            // from a normal exit — process.isAlive() is false in both cases
            // after the loop, but only a watchdog kill should produce the
            // "timed out" markers in the result.
            var timedOut = new java.util.concurrent.atomic.AtomicBoolean(false);
            Thread.startVirtualThread(() -> {
                try {
                    if (!process.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                        timedOut.set(true);
                        process.destroyForcibly();
                    }
                } catch (InterruptedException _) {}
            });

            // Blocking read loop — wrap in InputStreamReader with explicit UTF-8
            // so multi-byte characters split across read() boundaries are decoded
            // correctly (raw new String(byte[]) corrupts partial sequences).
            var reader = new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8);
            var cbuf = new char[4096];
            int n;
            while ((n = reader.read(cbuf)) != -1) {
                totalRead += n;
                if (!truncated) {
                    int remaining = maxOutputBytes - out.length();
                    if (remaining <= 0) {
                        truncated = true;
                    } else if (n > remaining) {
                        out.append(cbuf, 0, remaining);
                        truncated = true;
                    } else {
                        out.append(cbuf, 0, n);
                    }
                }

                // Check for terminal image — if found, return early but keep process alive
                if (!foundTerminalImage && hasTerminalImage(out.toString())) {
                    foundTerminalImage = true;
                    var processedOutput = replaceTerminalImagesInOutput(out.toString(), agent);
                    long durationMs = System.currentTimeMillis() - startTime;

                    // The watchdog thread already babysits the process for the
                    // full timeout. We just return; the process stays alive so
                    // the user can interact (e.g., scan QR code).
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
            }

            // Ensure process is fully dead before collecting exit code.
            if (!process.waitFor(1, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);
            }

            if (truncated) {
                out.append("\n[Output truncated at %dKB. Total output: %d bytes]"
                        .formatted(maxOutputBytes / 1024, totalRead));
            }

            long durationMs = System.currentTimeMillis() - startTime;
            var processedOutput = replaceTerminalImagesInOutput(out.toString(), agent);

            var result = new JsonObject();
            result.addProperty("exitCode", timedOut.get() ? -1 : process.exitValue());
            result.addProperty("output", processedOutput + (timedOut.get() ? "\n[Process killed: timeout after %d seconds]".formatted(timeoutSec) : ""));
            result.addProperty("durationMs", durationMs);
            result.addProperty("truncated", truncated);
            result.addProperty("timedOut", timedOut.get());
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

    /** Quick check if the output contains enough consecutive block art lines to constitute a terminal image. */
    private boolean hasTerminalImage(String output) {
        int blockLines = 0;
        int lineStart = 0;
        int len = output.length();
        for (int i = 0; i <= len; i++) {
            if (i == len || output.charAt(i) == '\n') {
                var line = output.substring(lineStart, i);
                if (isBlockArtLine(line)) {
                    blockLines++;
                    if (blockLines >= 5) return true;
                } else {
                    blockLines = 0;
                }
                lineStart = i + 1;
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
