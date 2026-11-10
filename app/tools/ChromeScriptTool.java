package tools;

import agents.ToolAction;
import agents.ToolRegistry;
import com.google.gson.JsonObject;
import models.Agent;
import org.jspecify.annotations.Nullable;
import services.ChromeBridge;
import services.ChromeBridgePolicy;

import java.util.List;
import java.util.Map;

/**
 * Runs JavaScript in a tab of the JClaw group. A script reads the page's own state and acts as the
 * signed-in operator, and nothing a click check does applies to it, so it is a tool of its own: a
 * standing grant for {@code chrome_control} cannot cover it, and it is off unless the operator turns
 * it on for the main agent. Every call that can run still waits for the operator.
 */
public class ChromeScriptTool implements ToolRegistry.Tool {

    public static final String NAME = "chrome_script";

    private static final String ARG_TAB = "tabId";
    private static final String ARG_EXPRESSION = "expression";
    private static final int MAX_EXPRESSION = 10_000;
    private static final int MAX_RESULT = 20_000;
    private static final String OFF = ChromeControlTool.ERROR + "running page script is turned off for this agent.";

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
        return "Run JavaScript in a tab of the operator's Chrome JClaw group. Off unless the operator enables it for the main agent.";
    }

    @Override
    public String summary() {
        return "Run a JavaScript expression in a tab of the JClaw group and get its value; each call waits for the operator.";
    }

    @Override
    public String description() {
        return """
                Run a JavaScript expression in a tab of the operator's Chrome JClaw group and get its value back. \
                Use it for what chrome_control cannot do, such as reading a value the page keeps in script. \
                Give a tabId and an expression; a Promise is awaited, and the value is returned as text. \
                It is off unless the operator turned it on for you, and every call waits for the operator to approve it.""";
    }

    @Override
    public List<ToolAction> actions() {
        return List.of(new ToolAction("evaluate", "Run a JavaScript expression in a tab and return its value"));
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                SchemaKeys.TYPE, SchemaKeys.OBJECT,
                SchemaKeys.PROPERTIES, Map.of(
                        ARG_TAB, Map.of(SchemaKeys.TYPE, SchemaKeys.NUMBER,
                                SchemaKeys.DESCRIPTION, "Tab id from chrome_control tabs_list or navigate"),
                        ARG_EXPRESSION, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.DESCRIPTION, "The JavaScript expression to run in the page")),
                SchemaKeys.REQUIRED, List.of(ARG_TAB, ARG_EXPRESSION));
    }

    /** Only a call that can run is worth asking about; an agent that may not run script is told so without a prompt. */
    @Override
    public boolean dangerous(String argsJson, Agent agent) {
        return ChromeBridgePolicy.scriptAllowed(agent);
    }

    @Override
    public boolean neverRunsUnasked() {
        return true;
    }

    @Override
    public @Nullable String describeCall(String argsJson) {
        var args = ChromeControlTool.parse(argsJson);
        if (args == null) return null;
        var code = ChromeControlTool.string(args, ARG_EXPRESSION);
        var shown = code == null ? "" : code;
        if (shown.length() > 80) shown = shown.substring(0, 80) + "…";
        return "run script on tab " + ChromeControlTool.string(args, ARG_TAB) + ": " + shown;
    }

    @Override
    public String execute(String argsJson, Agent agent) {
        if (!ChromeBridgePolicy.scriptAllowed(agent)) return OFF;
        var args = ChromeControlTool.parse(argsJson);
        if (args == null) return ChromeControlTool.ERROR + "the arguments were not valid JSON.";
        var tab = ChromeControlTool.longOf(args, ARG_TAB);
        var code = ChromeControlTool.string(args, ARG_EXPRESSION);
        if (tab == null || code == null || code.isBlank()) return ChromeControlTool.ERROR + "a tabId and an expression are needed.";
        if (code.length() > MAX_EXPRESSION) return ChromeControlTool.ERROR + "the expression is longer than " + MAX_EXPRESSION + " characters.";
        try {
            var refusal = ChromeControlTool.tabRefusal(agent, tab);
            if (refusal != null) return ChromeControlTool.ERROR + refusal;
            var call = ChromeControlTool.withTab(tab);
            call.addProperty(ARG_EXPRESSION, code);
            var result = ChromeBridge.call("javascript", call).getAsJsonObject();
            // The script may have navigated away; what it returned then came from a page the agent may not read.
            var after = ChromeControlTool.tabRefusal(agent, tab);
            if (after != null) return ChromeControlTool.ERROR + after + "; the result was discarded.";
            return render(result);
        } catch (ChromeBridge.BridgeException e) {
            return ChromeControlTool.ERROR + e.getMessage();
        }
    }

    private static String render(JsonObject result) {
        var value = ChromeControlTool.string(result, "value");
        var text = value == null ? "undefined" : value;
        var cut = text.length() > MAX_RESULT
                ? text.substring(0, MAX_RESULT) + "\n[Result cut at " + MAX_RESULT + " of " + text.length() + " characters.]"
                : text;
        return ChromeControlTool.UNTRUSTED + "\n\n" + cut;
    }
}
