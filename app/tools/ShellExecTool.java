package tools;

import agents.ToolAction;
import agents.ToolRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.Agent;
import models.AgentSkillAllowedTool;
import models.AgentSkillConfig;
import services.AgentService;
import services.ConfigService;
import services.Tx;
import utils.WorkspacePathGuard;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

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

    private static final String PARAM_COMMAND = "command";
    private static final String PARAM_WORKDIR = "workdir";
    private static final String PARAM_TIMEOUT = "timeout";

    /** Atomically cached parsed allowlist: invalidated when the raw config string changes. */
    private record AllowlistCache(String raw, Set<String> set) {}
    private static final AtomicReference<AllowlistCache> cachedAllowlist =
            new AtomicReference<>(new AllowlistCache("", Set.of()));

    private static Set<String> parsedAllowlist() {
        var raw = ConfigService.get("shell.allowlist", DEFAULT_ALLOWLIST);
        var current = cachedAllowlist.get();
        if (raw.equals(current.raw())) return current.set();
        var newSet = Arrays.stream(raw.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
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

    /** JCLAW-382: shell execution is the prototypical sensitive, irreversible
     *  action — it runs arbitrary commands at the Play process's OS
     *  privileges (see the security-posture Javadoc above). When the agent is
     *  Telegram-bound, {@link agents.DangerousActionGate} surfaces an
     *  approve/deny prompt before each {@code exec} runs. */
    @Override
    public boolean dangerous() { return true; }

    @Override
    public String icon() { return "terminal"; }

    @Override
    public String shortDescription() {
        return "Execute shell commands on the host system with allowlist-based security controls.";
    }

    @Override
    public List<ToolAction> actions() {
        return List.of(
                new ToolAction("exec", "Run a shell command; validated against the permitted binary allowlist before execution")
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
                SchemaKeys.TYPE, SchemaKeys.OBJECT,
                SchemaKeys.PROPERTIES, Map.of(
                        PARAM_COMMAND, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.DESCRIPTION, "Shell command to execute"),
                        PARAM_WORKDIR, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.DESCRIPTION, "Working directory (relative to workspace, or absolute if global paths enabled)"),
                        PARAM_TIMEOUT, Map.of(SchemaKeys.TYPE, SchemaKeys.INTEGER,
                                SchemaKeys.DESCRIPTION, "Timeout in seconds (default: 30, max: 300)"),
                        "env", Map.of(SchemaKeys.TYPE, SchemaKeys.OBJECT,
                                SchemaKeys.DESCRIPTION, "Additional environment variables as key-value pairs",
                                SchemaKeys.ADDITIONAL_PROPERTIES, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING))
                ),
                SchemaKeys.REQUIRED, List.of(PARAM_COMMAND)
        );
    }

    @Override
    public String execute(String argsJson, Agent agent) {
        long startTime = System.currentTimeMillis();
        var args = JsonParser.parseString(argsJson).getAsJsonObject();

        var command = args.has(PARAM_COMMAND) ? args.get(PARAM_COMMAND).getAsString().strip() : "";
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
        if (args.has(PARAM_TIMEOUT)) {
            timeout = Math.min(args.get(PARAM_TIMEOUT).getAsInt(), maxTimeout);
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

        return Tx.run(() -> {
            // Re-fetch the agent inside the transaction. The caller may have
            // obtained the entity from a different (already-committed) Tx.run
            // block, which detached it — findByAgent on a detached entity is
            // legal but safer to re-attach. Explicit Agent type required:
            // Model.findById() declares JPABase as the return type, and var
            // would infer JPABase here, failing the downstream signature match.
            Agent managed = Agent.findById(agent.id);
            if (managed == null) return global;

            // Build disabled-skill filter from AgentSkillConfig. Absent config row
            // means enabled-by-default, matching SkillLoader's existing semantics.
            var configs = AgentSkillConfig.findByAgent(managed);
            var disabledSkills = new HashSet<String>();
            for (var c : configs) {
                if (!c.enabled) disabledSkills.add(c.skillName);
            }

            var union = new HashSet<>(global);
            for (var row : AgentSkillAllowedTool.findByAgent(managed)) {
                if (!disabledSkills.contains(row.skillName)) union.add(row.toolName);
            }
            return Collections.unmodifiableSet(union);
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

        if (!args.has(PARAM_WORKDIR) || args.get(PARAM_WORKDIR).getAsString().strip().isEmpty()) {
            if (!Files.isDirectory(workspace)) {
                try { Files.createDirectories(workspace); } catch (IOException _) { /* best-effort; a real failure surfaces at first write */ }
            }
            return workspace;
        }

        var workdirStr = args.get(PARAM_WORKDIR).getAsString().strip();
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
            return WorkspacePathGuard.acquireContained(workspace, workdirStr);
        } catch (SecurityException _) {
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
            var process = startProcess(command, workdir, env);

            // Watchdog: destroy the process after the configured timeout.
            // When destroyed, is.read() in the main loop returns -1 or throws,
            // breaking the loop cleanly. The flag distinguishes a watchdog kill
            // from a normal exit — process.isAlive() is false in both cases
            // after the loop, but only a watchdog kill should produce the
            // "timed out" markers in the result.
            var timedOut = new AtomicBoolean(false);
            Thread.ofVirtual().name("shell-exec-watchdog").start(() -> {
                try {
                    if (!process.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                        timedOut.set(true);
                        process.destroyForcibly();
                    }
                } catch (InterruptedException _) { Thread.currentThread().interrupt(); }
            });

            var readResult = readProcessOutput(process, maxOutputBytes, agent, timeoutSec, startTime);
            if (readResult.earlyReturn() != null) return readResult.earlyReturn();

            // Ensure process is fully dead before collecting exit code.
            if (!process.waitFor(1, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);
            }

            var out = readResult.output();
            if (readResult.truncated()) {
                out.append("\n[Output truncated at %dKB. Total output: %d bytes]"
                        .formatted(maxOutputBytes / 1024, readResult.totalRead()));
            }

            long durationMs = System.currentTimeMillis() - startTime;
            var processedOutput = TerminalImageRenderer.replaceTerminalImagesInOutput(out.toString(), agent);

            var result = new JsonObject();
            result.addProperty("exitCode", timedOut.get() ? -1 : process.exitValue());
            result.addProperty("output", processedOutput + (timedOut.get() ? "\n[Process killed: timeout after %d seconds]".formatted(timeoutSec) : ""));
            result.addProperty("durationMs", durationMs);
            result.addProperty("truncated", readResult.truncated());
            result.addProperty("timedOut", timedOut.get());
            return result.toString();

        } catch (IOException e) {
            return "Error: Failed to execute command: " + e.getMessage();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return "Error: Command execution interrupted.";
        }
    }

    private static Process startProcess(String command, Path workdir, Map<String, String> env)
            throws IOException {
        var pb = new ProcessBuilder("/bin/sh", "-c", command);
        pb.directory(workdir.toFile());
        pb.redirectErrorStream(true);
        pb.environment().clear();
        pb.environment().putAll(env);
        return pb.start();
    }

    /** Output read result. {@code earlyReturn} non-null short-circuits the
     *  caller — used by the terminal-image early-return path. */
    private record ReadResult(StringBuilder output, int totalRead, boolean truncated, String earlyReturn) {}

    /**
     * Blocking-read loop. Reads the process's combined stdout/stderr through
     * a UTF-8 decoder, applying the {@code maxOutputBytes} truncation cap and
     * watching for terminal-image (QR-code) block art. When a terminal image
     * is detected mid-stream the method short-circuits with a fully-rendered
     * result envelope so the user sees the image without waiting for the
     * (potentially long-running) interactive process to exit.
     */
    private ReadResult readProcessOutput(Process process, int maxOutputBytes, Agent agent,
                                         int timeoutSec, long startTime) throws IOException {
        // Read output with blocking reads and a watchdog thread that
        // enforces the overall deadline. Previous approach used
        // is.available() polling + 100ms sleep (busy-wait) and a 5-second
        // idle timeout that prematurely cut off slow commands (npm install,
        // long builds) that simply paused between writes. Blocking is.read()
        // on a virtual thread is free (no platform thread consumed during
        // the block) and returns naturally when the process writes or exits.
        var out = new StringBuilder();
        int totalRead = 0;
        boolean truncated = false;

        // Blocking read loop — wrap in InputStreamReader with explicit UTF-8
        // so multi-byte characters split across read() boundaries are decoded
        // correctly (raw new String(byte[]) corrupts partial sequences).
        var reader = new InputStreamReader(process.getInputStream(),
                StandardCharsets.UTF_8);
        var cbuf = new char[4096];
        int n;
        // JCLAW-404: scan for terminal-image block art incrementally rather than
        // re-toString()-ing and re-scanning the whole accumulated buffer on every
        // chunk (which was O(N²)). The detector only sees the chars actually
        // appended to `out` this iteration and carries the consecutive-block-run
        // state across chunks, yielding the same detection result as a full rescan.
        var imageScan = new TerminalImageScanner();
        while ((n = reader.read(cbuf)) != -1) {
            totalRead += n;
            int before = out.length();
            truncated = appendBounded(out, cbuf, n, maxOutputBytes, truncated);

            // Check for terminal image — if found, return early but keep process alive
            if (imageScan.acceptAppended(out, before)) {
                return new ReadResult(out, totalRead, truncated,
                        buildTerminalImageEarlyReturn(out.toString(), agent, timeoutSec,
                                startTime, truncated));
            }
        }
        return new ReadResult(out, totalRead, truncated, null);
    }

    /**
     * Incremental terminal-image (QR block-art) detector. Equivalent to
     * re-scanning the entire accumulated buffer for {@code >= 5} consecutive
     * block-art lines after each chunk, but it inspects only the newly-appended
     * chars and carries the consecutive-block-art-line run across chunks —
     * avoiding the quadratic {@code out.toString()} + full rescan (JCLAW-404).
     *
     * <p>Parity with the full-buffer scan is preserved by mirroring its two
     * line-counting cases:
     * <ul>
     *   <li><strong>Newline-terminated lines</strong> bump or reset the run as
     *       they complete (the {@code charAt(i) == '\n'} branch).</li>
     *   <li>The <strong>final unterminated tail</strong> is evaluated
     *       provisionally without being consumed (mirroring the {@code i == len}
     *       branch the full scan applies on every call), so a 5th block-art line
     *       that is still mid-line triggers detection at the same moment.</li>
     * </ul>
     * Block-art-ness of each line is decided by {@link #isBlockArtLine}, the
     * same predicate the full scan used.
     */
    private final class TerminalImageScanner {
        /** Completed consecutive block-art lines seen so far. */
        private int blockRun = 0;
        /** The current not-yet-newline-terminated line, accumulated across chunks. */
        private final StringBuilder lineBuf = new StringBuilder();

        /**
         * Feed the chars appended to {@code out} since offset {@code from}.
         * Returns {@code true} as soon as {@code >= 5} consecutive block-art
         * lines have been observed.
         */
        boolean acceptAppended(StringBuilder out, int from) {
            int len = out.length();
            for (int i = from; i < len; i++) {
                char ch = out.charAt(i);
                if (ch == '\n') {
                    if (TerminalImageRenderer.isBlockArtLine(lineBuf.toString())) {
                        if (++blockRun >= TerminalImageRenderer.MIN_BLOCK_ART_LINES) return true;
                    } else {
                        blockRun = 0;
                    }
                    lineBuf.setLength(0);
                } else {
                    lineBuf.append(ch);
                }
            }
            // Provisional check on the unterminated tail — mirrors the full
            // scan's i==len branch, which counts the trailing segment on every
            // invocation. The run is left unconsumed so the same line isn't
            // double-counted when its newline finally arrives.
            return blockRun + (TerminalImageRenderer.isBlockArtLine(lineBuf.toString()) ? 1 : 0)
                    >= TerminalImageRenderer.MIN_BLOCK_ART_LINES;
        }
    }

    /**
     * Append {@code n} chars from {@code cbuf} to {@code out} respecting the
     * {@code maxOutputBytes} cap. Returns the new truncated flag.
     */
    private static boolean appendBounded(StringBuilder out, char[] cbuf, int n,
                                         int maxOutputBytes, boolean truncated) {
        if (truncated) return true;
        int remaining = maxOutputBytes - out.length();
        if (remaining <= 0) return true;
        if (n > remaining) {
            out.append(cbuf, 0, remaining);
            return true;
        }
        out.append(cbuf, 0, n);
        return false;
    }

    /**
     * Build the early-return envelope when a terminal image is detected
     * mid-stream. The watchdog thread already babysits the process for the
     * full timeout. We just return; the process stays alive so the user can
     * interact (e.g., scan QR code).
     */
    private String buildTerminalImageEarlyReturn(String rawOutput, Agent agent,
                                                  int timeoutSec, long startTime, boolean truncated) {
        var processedOutput = TerminalImageRenderer.replaceTerminalImagesInOutput(rawOutput, agent);
        long durationMs = System.currentTimeMillis() - startTime;

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
