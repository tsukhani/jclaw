package tools;

import agents.ToolRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.Agent;
import models.AgentSkillAllowedTool;
import models.AgentSkillConfig;
import services.AgentService;
import services.ConfigService;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Shell execution tool for agent-invoked commands.
 *
 * <h2>Security posture (JCLAW-146)</h2>
 *
 * <p>This tool is <strong>not</strong> a sandbox. It invokes commands via
 * {@code /bin/sh -c}, which means the full shell grammar is available:
 * composition ({@code ;}, {@code &&}, {@code ||}), pipes ({@code |}), command
 * substitution ({@code $(...)}, backticks), redirection ({@code > < >>}),
 * subshells ({@code (cmd)}), and variable expansion. Only the <em>first token</em>
 * of the command is validated against {@link #DEFAULT_ALLOWLIST} /
 * {@code shell.allowlist}. A command like {@code echo hi; rm -rf foo} passes
 * the allowlist (first token is {@code echo}) and runs both the echo and the rm.
 *
 * <p>This is <strong>intentional</strong>, not a gap. The rationale:
 * <ul>
 *   <li>The agent runs with the same OS privileges as the Play process. A
 *       hostile prompt that reaches this tool at all has already breached the
 *       prompt-injection perimeter — further per-token gating just moves the
 *       goalposts, not the defensive line. Legitimate shell composition
 *       ({@code cd build && make}, {@code git log | head}, shell-redirect into
 *       a file) is load-bearing for real agent workflows.</li>
 *   <li>Sandboxing happens at two layers <em>below</em> this one:
 *       (1) the {@code resolveWorkdir} path-containment check, which confines
 *       the working directory to the agent's workspace unless
 *       {@code agent.main.shell.allowGlobalPaths=true} is explicitly set for
 *       the main agent; and (2) the environment-variable filter that strips
 *       sensitive keys before handing the map to {@link ProcessBuilder}.</li>
 *   <li>The {@link #validateAllowlist(String, Agent)} check exists for <em>UX
 *       guardrails</em> — catching accidental LLM misfires on obvious bad
 *       commands ({@code rm -rf /}, {@code curl | sh}) — not as a
 *       metacharacter-hardened sandbox. Anyone who needs metacharacter-level
 *       isolation must wrap this tool in an external sandbox
 *       (firejail, Docker, etc.) at the platform-operator layer.</li>
 * </ul>
 *
 * <p>Regression tests in {@code ShellExecToolTest} pin this posture —
 * {@code commandCompositionRunsBothCommands} specifically asserts that
 * {@code echo hi; echo world} executes both statements, so any future attempt
 * to harden the allowlist into per-token gating will fail loudly.
 */
public class ShellExecTool implements ToolRegistry.Tool {

    public static final String DEFAULT_ALLOWLIST = "git,npm,npx,pnpm,node,python,python3,pip,ls,cat,head,tail,grep,find,wc,sort,uniq,diff,mkdir,cp,mv,echo,curl,wget,jq,tar,zip,unzip,test,pwd,which,whoami,uname,date,file,stat,env,printenv,awk,sed,tr,cut,tee,xargs,touch,cmp,sleep";

    /** Atomically cached parsed allowlist: invalidated when the raw config string changes. */
    private record AllowlistCache(String raw, Set<String> set) {}
    private static final java.util.concurrent.atomic.AtomicReference<AllowlistCache> cachedAllowlist =
            new java.util.concurrent.atomic.AtomicReference<>(new AllowlistCache("", Set.of()));

    private static Set<String> parsedAllowlist() {
        var raw = ConfigService.get("shell.allowlist", DEFAULT_ALLOWLIST);
        var current = cachedAllowlist.get();
        if (raw.equals(current.raw())) return current.set();
        var newSet = Arrays.stream(raw.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        cachedAllowlist.set(new AllowlistCache(raw, newSet));
        return newSet;
    }

    private static final Set<String> SENSITIVE_NAME_PATTERNS = Set.of(
            "key", "secret", "token", "password", "credential"
    );
    private static final Set<String> SENSITIVE_PREFIXES = Set.of(
            "AWS_", "ANTHROPIC_", "OPENAI_", "GOOGLE_", "AZURE_"
    );

    @Override
    public String name() { return "exec"; }

    @Override
    public String category() { return "System"; }

    @Override
    public String icon() { return "terminal"; }

    @Override
    public String shortDescription() {
        return "Execute shell commands on the host system with allowlist-based security controls.";
    }

    @Override
    public String requiresConfig() { return "shell.enabled"; }

    @Override
    public java.util.List<agents.ToolAction> actions() {
        return java.util.List.of(
                new agents.ToolAction("exec", "Run a shell command; validated against the permitted binary allowlist before execution")
        );
    }

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
            var allowlistError = validateAllowlist(command, agent);
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

    /**
     * Back-compat overload retained for tests and external callers that don't
     * need per-agent skill-allowlist contributions. Production use should go
     * through {@link #validateAllowlist(String, Agent)} so the agent's enabled
     * skills can contribute commands.
     */
    public String validateAllowlist(String command) {
        return validateAllowlist(command, null);
    }

    /**
     * Validate a command against the effective allowlist for this agent:
     * {@code global shell.allowlist ∪ commands from every enabled skill's
     * AgentSkillAllowedTool rows}. Matches by either the raw first token
     * (supports path-literal entries like {@code "./skills/foo/wacli"}) or
     * the path basename (so {@code "wacli"} covers {@code wacli},
     * {@code ./wacli}, or {@code ./path/to/wacli}). When {@code agent} is
     * null, only the global allowlist is consulted.
     */
    public String validateAllowlist(String command, Agent agent) {
        var firstToken = extractFirstToken(command);
        if (firstToken.isEmpty()) {
            return "Error: command is required and must not be empty.";
        }

        var effective = effectiveAllowlistFor(agent);
        if (effective.isEmpty()) {
            return "Error: Command '%s' is not in the allowed commands list. Allowed: %s"
                    .formatted(firstToken, String.join(", ", effective));
        }
        var basename = commandBasename(firstToken);
        if (!effective.contains(firstToken) && (basename.isEmpty() || !effective.contains(basename))) {
            return "Error: Command '%s' is not in the allowed commands list. Allowed: %s"
                    .formatted(firstToken, String.join(", ", effective));
        }
        return null;
    }

    /**
     * Compute the effective allowlist for this agent: the union of the global
     * {@code shell.allowlist} with every command granted by the agent's enabled
     * skills. "Enabled" follows the {@link AgentSkillConfig} convention where
     * absence of a row means enabled-by-default; only rows where
     * {@code enabled=false} exclude a skill's contribution.
     *
     * <p>The per-agent portion involves JPA reads and is wrapped in
     * {@link services.Tx#run(play.libs.F.Function0)}. Tool execution runs on
     * a path that deliberately has no active EntityManager — LLM HTTP calls
     * span minutes and we don't want a DB connection held across them — so
     * any DB touch from inside a tool needs its own short transaction. Uncached
     * by design: the global portion is already cached in
     * {@link #parsedAllowlist()}; the per-agent portion is a bounded JPA query
     * (tens of rows in realistic setups). If this surfaces as a hot-path cost,
     * add a per-agent cache with explicit invalidation hooks in
     * {@code SkillPromotionService}.
     */
    public static Set<String> effectiveAllowlistFor(Agent agent) {
        var global = parsedAllowlist();
        if (agent == null) return global;

        return services.Tx.run(() -> {
            // Re-fetch the agent inside the transaction. The caller may have
            // obtained the entity from a different (already-committed) Tx.run
            // block, which detached it — findByAgent on a detached entity is
            // legal but safer to re-attach. Explicit Agent type required:
            // Model.findById() declares JPABase as the return type, and var
            // would infer JPABase here, failing the downstream signature match.
            models.Agent managed = models.Agent.findById(agent.id);
            if (managed == null) return global;

            // Build disabled-skill filter from AgentSkillConfig. Absent config row
            // means enabled-by-default, matching SkillLoader's existing semantics.
            var configs = AgentSkillConfig.findByAgent(managed);
            var disabledSkills = new java.util.HashSet<String>();
            for (var c : configs) {
                if (!c.enabled) disabledSkills.add(c.skillName);
            }

            var union = new java.util.HashSet<>(global);
            for (var row : AgentSkillAllowedTool.findByAgent(managed)) {
                if (!disabledSkills.contains(row.skillName)) union.add(row.toolName);
            }
            return java.util.Collections.unmodifiableSet(union);
        });
    }

    /**
     * Extract the final path segment of a command token: {@code "./a/b/wacli"}
     * → {@code "wacli"}, {@code "/usr/bin/grep"} → {@code "grep"}, plain
     * {@code "ls"} → {@code "ls"}. Returns an empty string for tokens that
     * resolve to no filename (e.g. {@code "/"}). Defensive against malformed
     * input: any exception from {@code Path.of} (invalid path syntax on the
     * platform) is treated as "no basename," forcing the exact-token check.
     */
    static String commandBasename(String token) {
        if (token == null || token.isEmpty()) return "";
        try {
            var name = Path.of(token).getFileName();
            return name == null ? "" : name.toString();
        } catch (Exception _) {
            return "";
        }
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
            Thread.ofVirtual().start(() -> {
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
