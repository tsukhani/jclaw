package tools;

import agents.AgentRunner;
import com.google.gson.JsonObject;
import models.Agent;
import models.Conversation;
import models.Message;
import models.MessageRole;
import models.SubagentRun;
import services.AgentService;
import services.ConfigService;
import services.ConversationService;
import services.EventLogger;
import services.NotificationBus;
import services.SubagentRegistry;
import services.Tx;
import utils.GsonHolder;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * JCLAW-677 / JCLAW-499: external-harness ({@code runtime=acp}) integration,
 * extracted from {@link SubagentSpawnTool}. Runtime validation, the per-run
 * harness command registry, channel-approval gating, workdir resolution, and the
 * batch / streaming / rpc launch + line-parse machinery. The native
 * {@link AgentRunner} path also flows through {@link #executeChildRun}.
 */
final class SubagentAcpRunner {

    private static final String NO_OUTPUT = "(no output)";
    private static final String ACP_EXIT_MSG = "ACP harness exited %d: %s";
    private static final int ACP_MAX_OUTPUT_BYTES = 400_000;

    // Recognized harness ids. "pi"/"claude" have dedicated streaming adapters;
    // "codex"/"gemini"/"opencode" are valid ids without a dedicated adapter yet —
    // they run via the batch/generic path (an unregistered id degrades to batch,
    // never errors) until a bespoke adapter lands. "generic" is the fallback.
    private static final Set<String> ACP_HARNESS_IDS =
            Set.of("pi", "claude", "codex", "gemini", "opencode", "antigravity",
                    SubagentSpawnTool.DEFAULT_ACP_HARNESS);
    private static final Set<String> ACP_MODES = Set.of(SubagentSpawnTool.DEFAULT_ACP_MODE, "json", "rpc");

    /** JCLAW-499: the per-spawn external-harness command for a run, set when
     *  runtime=acp, consumed once by {@link #executeChildRun}. */
    static final ConcurrentHashMap<Long, List<String>> ACP_RUNS = new ConcurrentHashMap<>();

    /** JCLAW-659: harness-id → adapter registry. JCLAW-660 seeds the {@code pi}
     *  (streaming JSONL) and {@code generic} (line-tail) adapters; JCLAW-667 adds
     *  the {@code claude} (streaming NDJSON) adapter; the {@code codex} adapter
     *  lands in a later JCLAW-657 story. */
    private static final Map<String, HarnessAdapter> HARNESS_ADAPTERS = new ConcurrentHashMap<>();

    static {
        registerAdapter("pi", new PiAdapter());
        registerAdapter("claude", new ClaudeAdapter());
        registerAdapter(SubagentSpawnTool.DEFAULT_ACP_HARNESS, new GenericAdapter());
    }

    private SubagentAcpRunner() {}

    /** JCLAW-660: folds a harness event stream into a single reply — a RESULT
     *  frame wins, else concatenated TOKEN output, else the STEP log. */
    private static final class ReplyAccumulator {
        private final StringBuilder tokens = new StringBuilder();
        private final StringBuilder steps = new StringBuilder();
        private String resultText;

        void fold(HarnessEvent ev) {
            switch (ev.kind()) {
                case HarnessEvent.TOKEN -> tokens.append(ev.text());
                case HarnessEvent.RESULT -> resultText = ev.text();
                case HarnessEvent.STEP -> {
                    if (!steps.isEmpty()) steps.append('\n');
                    steps.append(ev.text());
                }
                default -> { /* tool_call / error dispatched but not part of the reply */ }
            }
        }

        String reply() {
            if (resultText != null && !resultText.isBlank()) return resultText;
            if (!tokens.isEmpty()) return tokens.toString();
            return steps.toString();
        }
    }

    /** JCLAW-659: register a harness adapter under a harness id (see
     *  {@link #ACP_HARNESS_IDS}). Called by later stories' adapter classes. */
    static void registerAdapter(String id, HarnessAdapter adapter) {
        HARNESS_ADAPTERS.put(id, adapter);
    }

    /**
     * JCLAW-669: a coding-harness run whose ORIGIN is an unsafe channel (anything
     * but web chat) must be operator-approved before the process launches — an
     * inbound Telegram/Slack message can prompt-inject an agent into spawning
     * arbitrary code execution. Web spawns pass untouched (the pi -p / claude -p
     * uninterrupted contract); Telegram/Slack route through DangerousActionGate;
     * other channels follow tool.approval.offChannelPolicy. Throws on denial.
     */
    private static void enforceChannelApproval(Long runId, Agent childAgent, String task) {
        var originChannel = parentChannelType(runId);
        if (originChannel == null || "web".equals(originChannel)) return;
        var decision = agents.DangerousActionGate.guardHarnessPermission(
                childAgent, parentConversationId(runId), "coding_harness_run", task);
        var approved = decision == agents.DangerousActionGate.Decision.PROCEED;
        dispatchHarnessEvent(runId, new HarnessEvent(
                approved ? HarnessEvent.STEP : HarnessEvent.ERROR,
                "channel approval (%s): coding run %s".formatted(
                        originChannel, approved ? "approved" : "denied"),
                null), 0);
        if (!approved) {
            throw new IllegalStateException(
                    "coding harness run denied: origin channel '%s' requires operator "
                            + "approval and it was not granted".formatted(originChannel));
        }
    }

    /**
     * JCLAW-499: run the child body — the configured external harness
     * ({@code runtime:"acp"}) when an ACP command was registered for this run,
     * otherwise the native {@link AgentRunner}. Shared by the sync and detached
     * dispatch paths so ACP composes with sync, async, and batch fan-out.
     */
    static AgentRunner.RunResult executeChildRun(Long runId, Agent childAgent,
                                                 Conversation childConv, String task, boolean inlineMode) {
        var acpCommand = ACP_RUNS.remove(runId);
        if (acpCommand != null) {
            // JCLAW-669: a coding-harness run whose ORIGIN is an unsafe channel
            // (anything but the operator's web chat) must be operator-approved
            // before the process launches — an inbound Telegram/Slack message
            // can prompt-inject an agent into spawning arbitrary code
            // execution. Web spawns pass untouched (the pi -p / claude -p
            // uninterrupted contract); Telegram/Slack route through the
            // existing DangerousActionGate approval prompt; other channels
            // follow tool.approval.offChannelPolicy.
            enforceChannelApproval(runId, childAgent, task);
            // JCLAW-657 (finding A): scope the harness's cwd to a configured
            // workdir or the child agent's workspace instead of the backend's
            // CWD. This ORGANIZES output; it does not confine the process —
            // containment is JCLAW-669/670/671 (channel gating, permission
            // flags, sandboxing). acpAllowed is the security boundary today.
            var workdir = resolveAcpWorkdir(childAgent, task);
            recordWorkdir(runId, workdir);
            // JCLAW-660: batch stays one-shot; json/rpc stream the harness output
            // line-by-line through the selected adapter.
            var mode = resolveAcpMode();
            if (SubagentSpawnTool.DEFAULT_ACP_MODE.equals(mode)) {
                return runAcpBatch(runId, acpCommand, task, workdir);
            }
            var adapter = resolveAdapter();
            if (adapter == null) {
                // No adapter registered for the configured harness — degrade to the
                // one-shot batch path rather than fail the run.
                return runAcpBatch(runId, acpCommand, task, workdir);
            }
            // JCLAW-665: rpc mode against a harness that advertises a bidirectional
            // session routes the harness's mid-run permission requests through the
            // operator approval gate (decision written back on stdin). Strictly
            // capability-gated: any non-bidirectional harness — or json mode — falls
            // back to one-way streaming.
            if ("rpc".equals(mode) && adapter.capabilities().bidirectional()) {
                return runAcpRpc(runId, acpCommand, task, adapter, childAgent, workdir);
            }
            return runAcpStreaming(acpCommand, task, runId, adapter, workdir);
        }
        if (inlineMode) {
            // JCLAW-267: inline runs in the parent Conversation (queue owned), with
            // a ThreadLocal marker stamping every Message AgentRunner persists.
            return ConversationService.withSubagentRunIdMarker(runId,
                    () -> AgentRunner.runWithOwnedQueue(childAgent, childConv, task));
        }
        return AgentRunner.run(childAgent, childConv, task);
    }

    /**
     * JCLAW-657 (finding A): resolve the directory the acp harness process runs
     * in. An operator-set {@link SubagentSpawnTool#ACP_WORKDIR_KEY} wins; otherwise
     * the child agent's own workspace, so a real coding harness (whose file writes
     * are outside JClaw's tool confinement) is scoped there rather than the
     * backend's CWD. Returns {@code null} — inherit the server CWD — only when
     * neither is resolvable or the directory can't be created.
     */
    private static File resolveAcpWorkdir(Agent childAgent, String task) {
        var configured = ConfigService.get(SubagentSpawnTool.ACP_WORKDIR_KEY, null);
        Path dir;
        if (configured != null && !configured.isBlank()) {
            dir = Path.of(configured.strip());
        } else if (childAgent != null && childAgent.name != null) {
            // JCLAW-666: each coding session gets its own directory under the
            // agent workspace's coding/ parent, named from the task ("create
            // fibonacci program" -> coding/create-fibonacci-program/). An
            // existing directory is never reused — collisions get -2, -3, …
            // so consecutive sessions' artifacts don't interleave.
            var base = AgentService.workspacePath(childAgent.name).resolve("coding");
            var slug = SubagentSpawnTool.codingSlug(task);
            dir = base.resolve(slug);
            for (int n = 2; Files.exists(dir); n++) {
                dir = base.resolve(slug + "-" + n);
            }
        } else {
            return null;
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            EventLogger.warn(SubagentSpawnTool.SUBAGENT_CHANNEL, null, null,
                    "acp workdir '%s' could not be created (%s); harness inherits the server CWD"
                            .formatted(dir, e.getMessage()));
            return null;
        }
        return dir.toFile();
    }

    /** JCLAW-666: persist the resolved session directory on the run row so the
     *  operator and the CodingRunMonitor can find the coding artifacts (they
     *  live in the workspace, not in MessageAttachment). Best-effort. */
    private static void recordWorkdir(Long runId, File workdir) {
        if (runId == null || workdir == null) return;
        try {
            Tx.run(() -> {
                SubagentRun run = SubagentRun.findById(runId);
                if (run != null) {
                    run.workdir = workdir.getAbsolutePath();
                    run.save();
                }
                return null;
            });
        } catch (RuntimeException e) {
            EventLogger.warn(SubagentSpawnTool.SUBAGENT_CHANNEL, null, null,
                    "could not record acp workdir on run %d: %s".formatted(runId, e.getMessage()));
        }
    }

    /**
     * JCLAW-499: run an external agent harness (Codex / Claude / Gemini CLI, …)
     * one-shot ({@code subagent.acp.mode=batch}): launch the operator-configured
     * command, deliver {@code task} on stdin, and capture stdout (bounded) as the
     * child reply. Bounded by the wall-clock ceiling — a runaway harness is
     * force-killed. A non-zero exit raises with the harness's stderr so the spawn
     * records a FAILED outcome.
     */
    private static AgentRunner.RunResult runAcpBatch(Long runId, List<String> command, String task,
                                                     File workdir) {
        Process proc = null;
        try {
            // JCLAW-672: batch mode has no streaming adapter; sandbox with the
            // generic (no HOME allowances) profile when enabled. JCLAW-709: pass
            // the origin trust so the untrusted-only mode can confine this run.
            var launched = HarnessSandbox.wrap(
                    command, workdir, new GenericAdapter(), sandboxTrustedOrigin(runId));
            var pb = new ProcessBuilder(launched);
            if (workdir != null) pb.directory(workdir);
            proc = pb.start();
            // JCLAW-664: track the live process so SubagentRegistry.kill (and the
            // idle/ceiling timeout via requestStop) can force-terminate it and its
            // descendants instead of leaving it orphaned.
            SubagentRegistry.registerProcess(runId, proc);
            try (var stdin = proc.getOutputStream()) {
                stdin.write(task.getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            }
            var out = drainAsync(proc.getInputStream());
            var err = drainAsync(proc.getErrorStream());
            int ceiling = ConfigService.getInt(SubagentSpawnTool.MAX_WALLCLOCK_KEY,
                    SubagentSpawnTool.DEFAULT_MAX_WALLCLOCK_SECONDS);
            boolean done;
            if (ceiling <= 0) {
                proc.waitFor();   // no ceiling configured — wait until the harness exits
                done = true;
            } else {
                done = proc.waitFor(ceiling, TimeUnit.SECONDS);
            }
            if (!done) {
                proc.destroyForcibly();
                throw new IllegalStateException("ACP harness exceeded the %ds ceiling and was killed.".formatted(ceiling));
            }
            return finishAcpRun(proc, out, err);
        } catch (InterruptedException e) {
            // proc is non-null here: InterruptedException only comes from waitFor(),
            // which runs after ProcessBuilder.start() above has already succeeded.
            proc.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted awaiting the ACP harness.", e);
        } catch (IOException | ExecutionException | TimeoutException e) {
            if (proc != null) proc.destroyForcibly();
            throw new IllegalStateException("ACP harness failed: " + e.getMessage(), e);
        } finally {
            SubagentHarnessPermissions.evictStdinLock(runId);
            SubagentRegistry.unregisterProcess(runId);
        }
    }

    /**
     * JCLAW-660: run an external harness in streaming mode ({@code
     * subagent.acp.mode=json|rpc}). Launch the argv the {@code adapter} builds,
     * deliver {@code task} on stdin when the adapter left it out of the argv, then
     * read stdout LINE BY LINE — each line is parsed into a {@link HarnessEvent}
     * and fanned out via {@link #dispatchHarnessEvent} while the reply text
     * accumulates. Same guardrails as {@link #runAcpBatch}: the {@link
     * #ACP_MAX_OUTPUT_BYTES} output cap, the wall-clock ceiling, and {@code
     * destroyForcibly} on either overrun. A non-zero exit raises with the
     * harness's stderr so the spawn records a FAILED outcome.
     */
    private static AgentRunner.RunResult runAcpStreaming(List<String> command, String task,
                                                         Long runId, HarnessAdapter adapter, File workdir) {
        var argv = SubagentSpawnTool.withPermissionArgs(adapter, adapter.launchArgs(command, task));
        // The adapter delivers the task on stdin unless it placed it in the argv.
        boolean taskOnStdin = !argv.contains(task);
        Process proc = null;
        try {
            var pb = new ProcessBuilder(
                    HarnessSandbox.wrap(argv, workdir, adapter, sandboxTrustedOrigin(runId)));
            if (workdir != null) pb.directory(workdir);
            proc = pb.start();
            // JCLAW-664: track the live process so SubagentRegistry.kill and the
            // idle/ceiling timeout (via requestStop) can force-terminate it and
            // its descendants — the harness has no cooperative checkpoint.
            SubagentRegistry.registerProcess(runId, proc);
            try (var stdin = proc.getOutputStream()) {
                if (taskOnStdin) {
                    stdin.write(task.getBytes(StandardCharsets.UTF_8));
                    stdin.flush();
                }
            }
            var err = drainAsync(proc.getErrorStream());
            var reply = streamStdout(proc, runId, adapter, null);
            // JCLAW-664: the idle + wall-clock budgets are enforced by the outer
            // awaitFuture — each streamed line resets the idle clock (touch(), see
            // streamStdout), and on idle/ceiling expiry stopChildOnTimeout calls
            // requestStop, which destroys this process and records TIMEOUT with the
            // partial transcript. So wait unbounded here: a timeout or a kill
            // destroys the process, which unblocks this waitFor.
            proc.waitFor();
            return finishAcpRun(proc, reply, err);
        } catch (InterruptedException e) {
            // proc is non-null here: InterruptedException only comes from waitFor(),
            // which runs after ProcessBuilder.start() above has already succeeded.
            proc.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted awaiting the ACP harness.", e);
        } catch (IOException | ExecutionException | TimeoutException e) {
            if (proc != null) proc.destroyForcibly();
            throw new IllegalStateException("ACP harness failed: " + e.getMessage(), e);
        } finally {
            SubagentRegistry.unregisterProcess(runId);
        }
    }

    /**
     * JCLAW-665: run an external harness in bidirectional rpc mode ({@code
     * subagent.acp.mode=rpc}) against an adapter that advertises {@code
     * capabilities().bidirectional()}. Same launch + line-streaming machinery as
     * {@link #runAcpStreaming}, with one addition: stdin is kept OPEN for the whole
     * run, and each parsed line is inspected for a permission-request frame (see
     * {@link SubagentHarnessPermissions#detectPermission}). A detected request is
     * routed through {@link agents.DangerousActionGate#guardHarnessPermission} for
     * operator approval and the approve/deny decision is written back to the
     * harness on stdin — a denial cleanly aborts just that action, leaving the run
     * itself alive (no {@code destroyForcibly}). Non-bidirectional harnesses never
     * reach here; {@link #executeChildRun} falls them back to one-way
     * {@link #runAcpStreaming}.
     */
    private static AgentRunner.RunResult runAcpRpc(Long runId, List<String> command, String task,
                                                   HarnessAdapter adapter, Agent childAgent, File workdir) {
        var argv = SubagentSpawnTool.withPermissionArgs(adapter, adapter.launchArgs(command, task));
        boolean taskOnStdin = !argv.contains(task);
        // Route approval prompts to the PARENT conversation — the child's own
        // conversation is channelType="subagent" with no approval surface, so a
        // Telegram/Slack prompt must reach the operator where they're watching.
        var conversationId = parentConversationId(runId);
        Process proc = null;
        OutputStream stdin = null;
        try {
            var pb = new ProcessBuilder(
                    HarnessSandbox.wrap(argv, workdir, adapter, sandboxTrustedOrigin(runId)));
            if (workdir != null) pb.directory(workdir);
            proc = pb.start();
            // JCLAW-664: track the live process so the kill / idle-timeout paths can
            // force-terminate it and its descendants.
            SubagentRegistry.registerProcess(runId, proc);
            // JCLAW-665: unlike batch/streaming (which close stdin right after the
            // task), rpc keeps it OPEN so permission decisions can be written back
            // mid-run. Not a try-with-resources — closed in the finally.
            stdin = proc.getOutputStream();
            if (taskOnStdin) {
                stdin.write((task + "\n").getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            }
            var err = drainAsync(proc.getErrorStream());
            final var stdinRef = stdin;
            var reply = streamStdout(proc, runId, adapter,
                    ev -> SubagentHarnessPermissions.arbitratePermission(ev, stdinRef, runId, childAgent, conversationId));
            // See runAcpStreaming: the outer awaitFuture enforces the idle/ceiling
            // budgets and force-kills on expiry, which unblocks this waitFor.
            proc.waitFor();
            return finishAcpRun(proc, reply, err);
        } catch (InterruptedException e) {
            // proc is non-null here: InterruptedException only comes from waitFor(),
            // which runs after ProcessBuilder.start() above has already succeeded.
            proc.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted awaiting the ACP harness.", e);
        } catch (IOException | ExecutionException | TimeoutException e) {
            if (proc != null) proc.destroyForcibly();
            throw new IllegalStateException("ACP harness failed: " + e.getMessage(), e);
        } finally {
            SubagentHarnessPermissions.closeQuietly(stdin);
            SubagentRegistry.unregisterProcess(runId);
        }
    }

    /**
     * JCLAW-729: the exit-check the batch / streaming / rpc launch paths share.
     * Read the process's exit code and captured stdout; on a non-zero exit raise
     * {@link IllegalStateException} carrying the harness's stderr (falling back to
     * stdout, then {@value #NO_OUTPUT}) so the spawn records a FAILED outcome; on a
     * clean exit return the trimmed stdout as the child reply. The 10-second
     * {@code .get} reads surface the same checked exceptions the callers already
     * translate in their catch blocks.
     */
    private static AgentRunner.RunResult finishAcpRun(Process proc, CompletableFuture<String> stdout,
                                                      CompletableFuture<String> stderr)
            throws InterruptedException, ExecutionException, TimeoutException {
        int exit = proc.exitValue();
        String out = stdout.get(10, TimeUnit.SECONDS);
        if (exit != 0) {
            String err = stderr.get(10, TimeUnit.SECONDS);
            String detail;
            if (!err.isBlank()) {
                detail = err.strip();
            } else if (!out.isBlank()) {
                detail = out.strip();
            } else {
                detail = NO_OUTPUT;
            }
            throw new IllegalStateException(ACP_EXIT_MSG.formatted(exit, detail));
        }
        return new AgentRunner.RunResult(out.strip(), null);
    }

    /** JCLAW-665: the operator-facing (parent) conversation's channelType for a
     *  run, used to decide whether a coding run needs channel approval. Null when
     *  the run has no parent-conversation context. */
    private static String parentChannelType(Long runId) {
        if (runId == null) return null;
        return Tx.run(() -> {
            SubagentRun run = SubagentRun.findById(runId);
            return run != null && run.parentConversation != null
                    ? run.parentConversation.channelType : null;
        });
    }

    private static Long parentConversationId(Long runId) {
        if (runId == null) return null;
        return Tx.run(() -> {
            var run = (SubagentRun) SubagentRun.findById(runId);
            return run != null && run.parentConversation != null ? run.parentConversation.id : null;
        });
    }

    /** JCLAW-709: a coding run is "trusted" for sandbox purposes when its origin is
     *  the operator's own web chat (or has no channel origin) — the same web-vs-
     *  unsafe boundary {@link #enforceChannelApproval} uses. The untrusted-only
     *  sandbox mode ({@code subagent.acp.sandbox=untrusted}) confines exactly the
     *  runs this returns {@code false} for. */
    private static boolean sandboxTrustedOrigin(Long runId) {
        var origin = parentChannelType(runId);
        return origin == null || "web".equals(origin);
    }

    /**
     * JCLAW-660: read harness stdout line-by-line on a VT — parse each line into a
     * {@link HarnessEvent}, dispatch it, and accumulate the reply. The process is
     * force-killed (never {@link Thread#interrupt interrupted}, since the reader
     * may touch the DB via {@link #dispatchHarnessEvent}) once cumulative output
     * crosses {@link #ACP_MAX_OUTPUT_BYTES}. The reply prefers a {@code result}
     * event, else concatenated {@code token} text, else newline-joined {@code
     * step} lines (the generic line-tail case).
     */
    private static CompletableFuture<String> streamStdout(Process proc, Long runId,
                                                          HarnessAdapter adapter,
                                                          Consumer<HarnessEvent> permissionArbiter) {
        var f = new CompletableFuture<String>();
        Thread.ofVirtual().start(() -> {
            var acc = new ReplyAccumulator();
            long bytes = 0;
            int seq = 1;   // seq 0 is the JCLAW-669 channel-approval step (when gated)
            try (var reader = proc.inputReader(StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // JCLAW-664: each streamed line is activity — reset the idle clock.
                    SubagentRegistry.touch(runId);
                    bytes += line.getBytes(StandardCharsets.UTF_8).length + 1L;
                    var ev = adapter.parse(line);
                    // A null event is the adapter dropping a noise/duplicate line.
                    if (ev != null) {
                        // JCLAW-665: rpc permission requests route to the gate first.
                        if (permissionArbiter != null) permissionArbiter.accept(ev);
                        dispatchHarnessEvent(runId, ev, seq++);
                        acc.fold(ev);
                    }
                    if (bytes >= ACP_MAX_OUTPUT_BYTES) {
                        proc.destroyForcibly();
                        break;
                    }
                }
            } catch (IOException _) {
                // EOF, force-kill, or overrun closed the stream — complete with what we have.
            }
            String reply = acc.reply();
            // JCLAW-662: the harness output stream has ended — signal terminal once
            // per run (every adapter) so a live monitor stops tailing and, on
            // reconnect, falls back to the persisted transcript.
            var done = new LinkedHashMap<String, Object>();
            done.put(SubagentSpawnTool.BUS_RUN_ID, runId);
            done.put("seq", seq);
            done.put(SubagentSpawnTool.FIELD_REPLY, reply);
            NotificationBus.publish(NotificationBus.BUS_CODINGRUN_DONE, done);
            f.complete(reply);
        });
        return f;
    }

    /**
     * JCLAW-660/662: fan a parsed {@link HarnessEvent} out to the run's rails —
     * (a) publish it live on the {@link NotificationBus} so a connected monitor
     * sees it immediately, and (b) persist it as a child-Conversation
     * {@link Message} row so a client that reconnects mid-run can replay the
     * steps it missed via {@link controllers.ApiSubagentRunsController#steps}.
     */
    private static void dispatchHarnessEvent(Long runId, HarnessEvent ev, int seq) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put(SubagentSpawnTool.BUS_RUN_ID, runId);
        payload.put("seq", seq);
        payload.put("kind", ev.kind());
        payload.put("text", ev.text());
        NotificationBus.publish(NotificationBus.BUS_CODINGRUN_STEP, payload);
        persistHarnessStep(runId, seq, ev);
        streamToChat(runId, ev);
    }

    /**
     * JCLAW-661: Rail A — when a chat turn is watching this run, map the harness
     * event onto its live SSE callbacks: {@code token} → onToken, {@code tool_call}
     * → onToolCall, everything else (step / error / result) → onStatus. Never fires
     * onComplete/onError — those belong to the chat turn's own lifecycle, not the
     * run's. Best-effort: a write to an already-closed SSE throws, and swallowing it
     * here keeps the harness reader VT (which touches the DB and must never be
     * interrupted) and Rail B intact; the turn's own onComplete/onError unregisters
     * the callbacks.
     */
    private static void streamToChat(Long runId, HarnessEvent ev) {
        var cb = SubagentChatBridge.callbacksFor(runId);
        if (cb == null) return;
        try {
            switch (ev.kind()) {
                case HarnessEvent.TOKEN -> cb.onToken().accept(ev.text());
                case HarnessEvent.TOOL_CALL -> cb.onToolCall().accept(toToolCallEvent(ev));
                default -> cb.onStatus().accept(ev.text());
            }
        } catch (RuntimeException _) {
            // Chat SSE closed mid-run — drop Rail A for this event; Rail B already fired.
        }
    }

    /** JCLAW-661: adapt a harness {@code tool_call} event to the chat SSE's
     *  {@link AgentRunner.ToolCallEvent} shape — name from the event text, the raw
     *  JSON frame (when the line was JSON) as arguments; no result payload. */
    private static AgentRunner.ToolCallEvent toToolCallEvent(HarnessEvent ev) {
        var arguments = ev.raw() == null ? "" : ev.raw().toString();
        return new AgentRunner.ToolCallEvent(null, ev.text(), "harness", arguments, "", null, null,
                List.of());
    }

    /**
     * JCLAW-662: persist one harness step as a Message row on the run's child
     * Conversation (short {@link Tx}) so the transcript survives a monitor
     * disconnect. Best-effort — a transcript-write failure is logged and
     * swallowed so it never aborts the stdout reader (whose VT touches the DB
     * and is therefore only ever force-killed, never interrupted).
     */
    private static void persistHarnessStep(Long runId, int seq, HarnessEvent ev) {
        try {
            Tx.run(() -> {
                var run = (SubagentRun) SubagentRun.findById(runId);
                if (run == null || run.childConversation == null) return;
                var msg = new Message();
                msg.conversation = run.childConversation;
                msg.subagentRunId = runId;
                msg.role = MessageRole.ASSISTANT.value;
                msg.messageKind = SubagentSpawnTool.MESSAGE_KIND_CODINGRUN_STEP;
                msg.content = ev.text();
                msg.metadata = GsonHolder.INSTANCE.toJson(
                        Map.of("seq", seq, "kind", ev.kind()), Map.class);
                msg.save();
            });
        } catch (RuntimeException e) {
            EventLogger.warn(SubagentSpawnTool.SUBAGENT_CHANNEL, null, null,
                    "Failed to persist coding-run step seq=%d for run %s: %s"
                            .formatted(seq, runId, e.getMessage()));
        }
    }

    /** Drain a process stream on a VT, bounded to {@link #ACP_MAX_OUTPUT_BYTES}. */
    private static CompletableFuture<String> drainAsync(InputStream in) {
        var f = new CompletableFuture<String>();
        Thread.ofVirtual().start(() -> {
            try (in) {
                var bytes = in.readNBytes(ACP_MAX_OUTPUT_BYTES);
                f.complete(new String(bytes, StandardCharsets.UTF_8));
            } catch (IOException _) {
                f.complete("");
            }
        });
        return f;
    }

    /**
     * JCLAW-499: if {@code runtime} requests the ACP harness, validate it and
     * register the configured command for {@code runId} (consumed by
     * {@link #executeChildRun}). Returns an error string to short-circuit the
     * spawn, or null to proceed (native or successfully-registered acp).
     *
     * <p>JCLAW-500 (Change 2): acp is a privileged per-agent capability. The
     * harness is an operator-configured external process that runs OUTSIDE
     * JClaw's tool gating and workspace confinement, so only the main agent
     * (always) and agents an operator has granted {@link Agent#acpAllowed} may
     * request it. The gate is on the SPAWNING agent, so a confined custom agent
     * cannot break out via acp — and a subagent of main is itself non-main, so
     * it cannot escalate either.
     */
    static String acpRuntimeError(JsonObject args, Agent spawningAgent) {
        var runtime = SubagentSpawnArgs.optString(args, SubagentSpawnTool.ARG_RUNTIME);
        if (runtime == null || runtime.isBlank() || "native".equalsIgnoreCase(runtime)) {
            return null;
        }
        if (!"acp".equalsIgnoreCase(runtime)) {
            return "Error: 'runtime' must be \"native\" (default) or \"acp\"" + SubagentSpawnTool.GOT_LITERAL + runtime + "').";
        }
        if (spawningAgent != null && !spawningAgent.isMain() && !spawningAgent.acpAllowed) {
            return "Error: runtime=\"acp\" is not permitted for agent '" + spawningAgent.name
                    + "'. The acp runtime launches an external harness outside JClaw's tool and "
                    + "workspace confinement, so it is restricted to the main agent and agents an "
                    + "operator has explicitly granted acp.";
        }
        if (resolveAcpCommand().isEmpty()) {
            return "Error: runtime=\"acp\" needs an external harness — the operator must configure '"
                    + SubagentSpawnTool.ACP_COMMAND_KEY + "' (e.g. \"claude -p\" or \"codex exec\").";
        }
        // JCLAW-659: reject an operator-misconfigured harness id / mode up front,
        // with a clear message, rather than silently falling back to defaults.
        var harness = ConfigService.get(SubagentSpawnTool.ACP_HARNESS_KEY, SubagentSpawnTool.DEFAULT_ACP_HARNESS);
        if (harness != null && !harness.isBlank()
                && !ACP_HARNESS_IDS.contains(harness.strip().toLowerCase())) {
            return "Error: '" + SubagentSpawnTool.ACP_HARNESS_KEY + "' must be one of " + ACP_HARNESS_IDS
                    + SubagentSpawnTool.GOT_LITERAL + harness.strip() + "').";
        }
        var mode = ConfigService.get(SubagentSpawnTool.ACP_MODE_KEY, SubagentSpawnTool.DEFAULT_ACP_MODE);
        if (mode != null && !mode.isBlank()
                && !ACP_MODES.contains(mode.strip().toLowerCase())) {
            return "Error: '" + SubagentSpawnTool.ACP_MODE_KEY + "' must be one of " + ACP_MODES
                    + SubagentSpawnTool.GOT_LITERAL + mode.strip() + "').";
        }
        return null;
    }

    static boolean isAcpRuntime(JsonObject args) {
        return "acp".equalsIgnoreCase(SubagentSpawnArgs.optString(args, SubagentSpawnTool.ARG_RUNTIME));
    }

    /** Operator-configured ACP harness command, whitespace-split. Empty when unset. */
    static List<String> resolveAcpCommand() {
        var configured = ConfigService.get(SubagentSpawnTool.ACP_COMMAND_KEY, null);
        if (configured == null || configured.isBlank()) return List.of();
        return List.of(configured.strip().split("\\s+"));
    }

    /** JCLAW-659: configured harness id, normalized and falling back to
     *  {@link SubagentSpawnTool#DEFAULT_ACP_HARNESS} when unset or unrecognized. */
    private static String resolveHarnessId() {
        var configured = ConfigService.get(SubagentSpawnTool.ACP_HARNESS_KEY, SubagentSpawnTool.DEFAULT_ACP_HARNESS);
        if (configured == null || configured.isBlank()) return SubagentSpawnTool.DEFAULT_ACP_HARNESS;
        var id = configured.strip().toLowerCase();
        return ACP_HARNESS_IDS.contains(id) ? id : SubagentSpawnTool.DEFAULT_ACP_HARNESS;
    }

    /** JCLAW-659: configured acp mode, normalized and falling back to
     *  {@link SubagentSpawnTool#DEFAULT_ACP_MODE} when unset or unrecognized. */
    static String resolveAcpMode() {
        var configured = ConfigService.get(SubagentSpawnTool.ACP_MODE_KEY, SubagentSpawnTool.DEFAULT_ACP_MODE);
        if (configured == null || configured.isBlank()) return SubagentSpawnTool.DEFAULT_ACP_MODE;
        var mode = configured.strip().toLowerCase();
        return ACP_MODES.contains(mode) ? mode : SubagentSpawnTool.DEFAULT_ACP_MODE;
    }

    /** JCLAW-659: the {@link HarnessAdapter} for the configured harness, falling
     *  back to the {@link SubagentSpawnTool#DEFAULT_ACP_HARNESS} adapter. JCLAW-660
     *  wires this into {@link #runAcpStreaming} for the json/rpc modes; returns
     *  {@code null} only if no adapter is registered for the configured harness. */
    static HarnessAdapter resolveAdapter() {
        var adapter = HARNESS_ADAPTERS.get(resolveHarnessId());
        if (adapter == null) {
            adapter = HARNESS_ADAPTERS.get(SubagentSpawnTool.DEFAULT_ACP_HARNESS);
        }
        return adapter;
    }
}
