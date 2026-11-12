package tools;

import agents.ToolAction;
import agents.ToolRegistry;
import models.Agent;
import org.jspecify.annotations.Nullable;
import services.ChromeBridge;
import services.ChromeBridgePolicy;
import services.PersonaFileApprovals;
import utils.ToolErrorTemplates;
import utils.WorkspacePathGuard;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/**
 * Puts one file from the agent's own workspace into a file input in a tab of the JClaw group. The path is
 * resolved by the same guard as every other workspace file tool, so nothing outside the workspace can be
 * sent, and the persona files that hold the operator's private context never leave it. A tool of its own, so
 * a standing grant for clicks cannot cover sending a file.
 */
public class ChromeUploadTool implements ToolRegistry.Tool {

    public static final String NAME = "chrome_upload";

    private static final String ARG_TAB = "tabId";
    private static final String ARG_REF = "ref";
    private static final String ARG_PATH = "path";
    private static final long MAX_BYTES = 25L * 1024 * 1024;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String category() {
        return "Web";
    }

    @Override
    public String icon() {
        return "browser";
    }

    @Override
    public String shortDescription() {
        return "Attach a file from the agent's workspace to a file input in the operator's Chrome JClaw group.";
    }

    @Override
    public String summary() {
        return "Attach one workspace file to a file input on a page in the JClaw group; it waits for the operator unless exempt.";
    }

    @Override
    public String description() {
        return """
                Attach one file from your own workspace to a file input on a page in the operator's Chrome JClaw group. \
                Give a tabId, the ref of the file input from the latest chrome_control read or find (it shows as input[file]), \
                and the file's path inside your workspace. Only files in your workspace can be sent, and a file of at most 25 MB. \
                This only attaches the file; submitting the form is a separate chrome_control click.""";
    }

    @Override
    public List<ToolAction> actions() {
        return List.of(new ToolAction("upload", "Attach a workspace file to a file input"));
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                SchemaKeys.TYPE, SchemaKeys.OBJECT,
                SchemaKeys.PROPERTIES, Map.of(
                        ARG_TAB, Map.of(SchemaKeys.TYPE, SchemaKeys.NUMBER, SchemaKeys.DESCRIPTION, "Tab id from chrome_control"),
                        ARG_REF, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING, SchemaKeys.DESCRIPTION, "Ref of the file input"),
                        ARG_PATH, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING, SchemaKeys.DESCRIPTION, "Path of the file inside your workspace")),
                SchemaKeys.REQUIRED, List.of(ARG_TAB, ARG_REF, ARG_PATH));
    }

    /** The main agent with the operator's click override is exempt, as it is for a click; everyone else waits. */
    @Override
    public boolean dangerous(String argsJson, Agent agent) {
        return ChromeBridgePolicy.clickNeedsApproval(agent);
    }

    @Override
    public boolean neverRunsUnasked() {
        return true;
    }

    @Override
    public @Nullable String describeCall(String argsJson) {
        var args = ChromeControlTool.parse(argsJson);
        if (args == null) return null;
        return "upload " + ChromeControlTool.string(args, ARG_PATH) + " to " + ChromeControlTool.string(args, ARG_REF)
                + " on tab " + ChromeControlTool.string(args, ARG_TAB);
    }

    @Override
    public String execute(String argsJson, Agent agent) {
        var args = ChromeControlTool.parse(argsJson);
        if (args == null) return ChromeControlTool.ERROR + "the arguments were not valid JSON.";
        var tab = ChromeControlTool.longOf(args, ARG_TAB);
        var ref = ChromeControlTool.string(args, ARG_REF);
        if (tab == null || ref == null || !args.has(ARG_PATH)) {
            return ChromeControlTool.ERROR + "upload needs a tabId, a ref and a path.";
        }
        var resolved = FsPaths.resolveTargetPath(args, agent, "upload");
        if (resolved.error() != null) return ChromeControlTool.ERROR + resolved.resolvedError().whatBroke();
        if (WorkspacePathGuard.isInSkillCredentials(resolved.resolvedWorkspace(), resolved.resolvedTarget())) {
            return ChromeControlTool.ERROR
                    + ToolErrorTemplates.fsCredentials(args.get(ARG_PATH).getAsString()).whatBroke();
        }
        var file = resolved.resolvedTarget().toAbsolutePath().normalize();
        var name = file.getFileName();
        if (name == null || !Files.isRegularFile(file)) return ChromeControlTool.ERROR + "that is not a file in your workspace.";
        var workspace = resolved.resolvedWorkspace().toAbsolutePath().normalize();
        if (PersonaFileApprovals.isPersonaFile(name.toString()) && workspace.equals(file.getParent())) {
            return ChromeControlTool.ERROR + "persona files are never uploaded.";
        }
        try {
            if (Files.size(file) > MAX_BYTES) return ChromeControlTool.ERROR + "the file is larger than 25 MB.";
        } catch (IOException _) {
            return ChromeControlTool.ERROR + "the file could not be read.";
        }
        try {
            var refusal = ChromeControlTool.tabRefusal(agent, tab);
            if (refusal != null) return ChromeControlTool.ERROR + refusal;
            var call = ChromeControlTool.withTab(tab);
            call.addProperty(ARG_REF, ref);
            call.addProperty(ARG_PATH, file.toString());
            ChromeBridge.call("upload", call);
            return "Attached " + name + " to " + ref + ".";
        } catch (ChromeBridge.BridgeException e) {
            return ChromeControlTool.ERROR + e.getMessage();
        }
    }
}
