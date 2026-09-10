package tools;

import org.jspecify.annotations.Nullable;
import services.AcpCapabilityCatalog;

import java.util.ArrayList;
import java.util.List;

/**
 * What a {@code runtime="acp"} spawn launches under the current Settings: the configured
 * {@code subagent.acp.command} plus whatever the {@code subagent.acp.model*} override adds.
 * Read-only — the Coding panel renders it beside the editable command.
 *
 * <p>The override is deliberately not written back into {@code subagent.acp.command}:
 * {@link HarnessModelBinding} appends the flags per launch path, so a stored copy would be
 * passed twice, and codex's form is an inline TOML block whose quoted strings would not
 * survive the whitespace split in {@link SubagentAcpRunner#resolveAcpCommand()}.
 *
 * @param command    the configured base command, blank when ACP is disabled
 * @param harness    the resolved {@code subagent.acp.harness} adapter id
 * @param effective  the argv a spawn actually launches, or blank when ACP is disabled
 * @param env        names of the environment variables the override travels in, never values
 * @param rejection  why this harness cannot take the override, or {@code null} when it can
 * @param acpAdapter whether real ACP over stdio replaces {@code command} at launch
 */
public record AcpCommandPreview(String command, String harness, String effective,
                                List<String> env, @Nullable String rejection, boolean acpAdapter) {

    public static AcpCommandPreview current() {
        var base = SubagentAcpRunner.resolveAcpCommand();
        var harnessId = SubagentAcpRunner.resolveHarnessId();
        // An unset command refuses every acp spawn (SubagentAcpRunner.acpRuntimeError), so
        // there is no launch to probe the adapter for.
        var acpLaunch = base.isEmpty() ? null : AcpCapabilityCatalog.stdioAcpLaunchCommand(harnessId);
        return of(harnessId, base, SubagentAcpRunner.settingsModel(), acpLaunch);
    }

    /**
     * The preview for one resolved configuration. Split from {@link #current()} so the
     * composition is testable without a host whose PATH decides {@code acpLaunch}.
     *
     * @param acpLaunch the harness's stdio ACP command when one is installed — it replaces
     *                  {@code base} at launch ({@link SubagentAcpRunner#runAcpSdk}) — else {@code null}
     */
    public static AcpCommandPreview of(String harnessId, List<String> base, @Nullable HarnessModel model,
                                       @Nullable String acpLaunch) {
        var command = String.join(" ", base);
        var launch = acpLaunch == null ? base : List.of(acpLaunch.strip().split("\\s+"));
        var launched = String.join(" ", launch);
        if (model == null) {
            return new AcpCommandPreview(command, harnessId, launched, List.of(), null, acpLaunch != null);
        }
        var rejection = HarnessModelBinding.rejection(harnessId, model);
        if (rejection != null) {
            return new AcpCommandPreview(command, harnessId, launched, List.of(), rejection, acpLaunch != null);
        }
        var argv = new ArrayList<>(launch);
        argv.addAll(acpLaunch == null ? HarnessModelBinding.wrapperArgs(harnessId, model)
                : HarnessModelBinding.acpArgs(harnessId, model));
        return new AcpCommandPreview(command, harnessId, String.join(" ", argv),
                List.copyOf(HarnessModelBinding.env(harnessId, model).keySet()), null, acpLaunch != null);
    }
}
