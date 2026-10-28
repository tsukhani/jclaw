package tools;

import agents.SkillLoader;
import agents.ToolAction;
import agents.ToolRegistry;
import com.google.gson.JsonParser;
import models.Agent;
import org.jspecify.annotations.Nullable;
import services.AgentService;
import utils.WorkspacePathGuard;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Returns the SKILL.md of one skill listed in the calling agent's prompt, by name (JF-146), so an agent
 * kept off {@code filesystem}, which also writes, can still open its skills. It reaches nothing else in a
 * skill's folder.
 */
public class SkillReadTool implements ToolRegistry.Tool {

    private static final String ARG_NAME = "name";

    /** The largest SKILL.md on the instance that asked for this tool was 35,022 characters. */
    private static final int MAX_CHARS = 40_000;

    @Override
    public String name() {
        return "skill_read";
    }

    @Override
    public String category() {
        return "Utilities";
    }

    @Override
    public String icon() {
        return "book";
    }

    @Override
    public List<ToolAction> actions() {
        return List.of(new ToolAction("read", "Return the SKILL.md of one skill listed in the agent's prompt"));
    }

    /** An {@code exec} or {@code filesystem} write to a SKILL.md in the same message lands before this read. */
    @Override
    public String serializationGroup() {
        return ToolRegistry.WORKSPACE_GROUP;
    }

    /** MAX_CHARS already bounds the result, and a compressed skill would only come back through ccr_retrieve. */
    @Override
    public boolean verbatimResult(String argsJson) {
        return true;
    }

    @Override
    public boolean keepsResultInContext(String argsJson) {
        return true;
    }

    @Override
    public String shortDescription() {
        return "Read the instructions of a skill attached to the agent, for an agent without the filesystem tool.";
    }

    @Override
    public String description() {
        return """
                Read the SKILL.md of one of your skills. Pass the skill's <name> exactly as <available_skills> \
                lists it; the tool returns that skill's instructions. It reads only SKILL.md, and only for a skill \
                listed in your prompt.""";
    }

    @Override
    public String summary() {
        return "Read one listed skill's SKILL.md. Arg: name (the skill's name from <available_skills>).";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                SchemaKeys.TYPE, SchemaKeys.OBJECT,
                SchemaKeys.PROPERTIES, Map.of(
                        ARG_NAME, Map.of(
                                SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.DESCRIPTION, "The skill's name, exactly as <available_skills> lists it.")),
                SchemaKeys.REQUIRED, List.of(ARG_NAME));
    }

    @Override
    public String execute(String argsJson, Agent agent) {
        var name = nameArg(argsJson);
        if (name == null) {
            return "Error: pass the name of a skill from <available_skills> as 'name'.";
        }
        if (!isSkillName(name)) {
            return "Error: '%s' is not a skill name. Pass a name from <available_skills>, not a path.".formatted(name);
        }

        var skills = SkillLoader.loadSkills(agent.name);
        var skill = skills.stream().filter(s -> s.name().equals(name)).findFirst().orElse(null);
        if (skill == null) {
            return notListed(name, skills);
        }

        String content;
        try {
            var workspace = AgentService.workspacePath(agent.name);
            var path = WorkspacePathGuard.acquireContained(workspace, skill.location().toString());
            // A SKILL.md linked to another workspace file passes the guard, and the folder is still listed by name.
            var folder = WorkspacePathGuard.acquireContained(workspace,
                    Objects.requireNonNull(skill.location().getParent(), "a listed skill is <folder>/SKILL.md").toString());
            if (!path.equals(folder.resolve("SKILL.md"))) {
                return "Error: skill '%s' cannot be read: its SKILL.md is a link to another file.".formatted(name);
            }
            content = Files.readString(path);
        } catch (SecurityException e) {
            return "Error: skill '%s' cannot be read: %s".formatted(name, e.getMessage());
        } catch (IOException e) {
            return "Error: skill '%s' could not be read (%s).".formatted(name, e.getClass().getSimpleName());
        }
        return capped(content);
    }

    private static @Nullable String nameArg(String argsJson) {
        var parsed = JsonParser.parseString(argsJson);
        if (!parsed.isJsonObject()) return null;
        var value = parsed.getAsJsonObject().get(ARG_NAME);
        if (value == null || !value.isJsonPrimitive()) return null;
        var name = value.getAsString().strip();
        return name.isEmpty() ? null : name;
    }

    /** Checked before the skill list is read, so a path never reaches the disk. */
    private static boolean isSkillName(String name) {
        return name.indexOf('/') < 0 && name.indexOf('\\') < 0 && !name.contains("..") && !name.startsWith(".");
    }

    /** An excluded skill gets this too, so the refusal does not reveal that it exists. */
    private static String notListed(String name, List<SkillLoader.SkillInfo> skills) {
        if (skills.isEmpty()) {
            return "Error: no skill named '%s' is listed for you, and you have no skills to read.".formatted(name);
        }
        var names = String.join(", ", skills.stream().map(SkillLoader.SkillInfo::name).toList());
        return "Error: no skill named '%s' is listed for you. Skills you can read: %s.".formatted(name, names);
    }

    /** Never between a surrogate pair: a lone half does not survive the JSON sent to the provider. */
    private static String capped(String content) {
        if (content.length() <= MAX_CHARS) return content;
        var cut = Character.isHighSurrogate(content.charAt(MAX_CHARS - 1)) ? MAX_CHARS - 1 : MAX_CHARS;
        return content.substring(0, cut) + String.format(Locale.ROOT,
                "\n[SKILL.md truncated: showed the first %,d of %,d characters]", cut, content.length());
    }
}
