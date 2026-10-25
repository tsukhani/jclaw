package agents;

import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The checks Anthropic's {@code skill-creator/scripts/quick_validate.py} runs on a SKILL.md, in
 * its order and with its messages, plus the three Agent Skills spec rules it leaves out: the name
 * matches the skill's directory, every {@code metadata} value is a string, and
 * {@code allowed-tools} is a string. A skill with no problems here is one Claude accepts.
 *
 * <p>The YAML is read with SnakeYAML's YAML 1.1 types, as the validator's PyYAML reads it, so
 * {@code name: yes} is a boolean here too.
 */
public final class SkillSpec {

    public static final Set<String> ALLOWED_PROPERTIES =
            Set.of("name", "description", "license", "allowed-tools", "metadata", "compatibility");
    private static final Pattern FRONTMATTER = Pattern.compile("^---\\n(.*?)\\n---", Pattern.DOTALL);
    private static final Pattern KEBAB = Pattern.compile("[a-z0-9-]++");
    private static final int MAX_NAME = 64;
    private static final int MAX_DESCRIPTION = 1024;
    private static final int MAX_COMPATIBILITY = 500;

    private SkillSpec() {}

    /**
     * Every problem with {@code skillMd}, empty when it conforms.
     *
     * @param directoryName the folder the skill ships in, or null to skip that one check
     */
    public static List<String> problems(String skillMd, @Nullable String directoryName) {
        if (!skillMd.startsWith("---")) return List.of("No YAML frontmatter found");
        var matcher = FRONTMATTER.matcher(skillMd);
        if (!matcher.find()) return List.of("Invalid frontmatter format");
        Object root;
        try {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).load(matcher.group(1));
        } catch (YAMLException e) {
            return List.of("Invalid YAML in frontmatter: " + e.getMessage());
        }
        if (!(root instanceof Map<?, ?> frontmatter)) return List.of("Frontmatter must be a YAML dictionary");

        var out = new ArrayList<String>();
        var unexpected = new TreeSet<String>();
        frontmatter.keySet().forEach(k -> {
            if (!ALLOWED_PROPERTIES.contains(String.valueOf(k))) unexpected.add(String.valueOf(k));
        });
        if (!unexpected.isEmpty()) {
            out.add("Unexpected key(s) in SKILL.md frontmatter: %s. Allowed properties are: %s"
                    .formatted(String.join(", ", unexpected), String.join(", ", new TreeSet<>(ALLOWED_PROPERTIES))));
        }
        if (!frontmatter.containsKey("name")) out.add("Missing 'name' in frontmatter");
        if (!frontmatter.containsKey("description")) out.add("Missing 'description' in frontmatter");

        if (frontmatter.containsKey("name")) checkName(frontmatter.get("name"), directoryName, out);
        if (frontmatter.containsKey("description")) checkDescription(frontmatter.get("description"), out);

        var compatibility = frontmatter.get("compatibility");
        if (compatibility != null && !"".equals(compatibility)) {
            if (!(compatibility instanceof String s)) out.add("Compatibility must be a string, got " + typeName(compatibility));
            else if (length(s) > MAX_COMPATIBILITY) {
                out.add("Compatibility is too long (%d characters). Maximum is %d characters.".formatted(length(s), MAX_COMPATIBILITY));
            }
        }
        var metadata = frontmatter.get("metadata");
        if (metadata != null && !(metadata instanceof Map<?, ?>)) out.add("Metadata must be a mapping, got " + typeName(metadata));
        if (metadata instanceof Map<?, ?> m) {
            m.forEach((k, v) -> {
                if (!(v instanceof String)) out.add("Metadata value '%s' must be a string, got %s".formatted(k, typeName(v)));
            });
        }
        var allowedTools = frontmatter.get("allowed-tools");
        if (allowedTools != null && !(allowedTools instanceof String)) {
            out.add("Allowed-tools must be a space-separated string, got " + typeName(allowedTools));
        }
        return out;
    }

    private static void checkName(@Nullable Object value, @Nullable String directoryName, List<String> out) {
        if (!(value instanceof String raw)) {
            out.add("Name must be a string, got " + typeName(value));
            return;
        }
        var name = raw.strip();
        if (name.isEmpty()) return;
        if (!KEBAB.matcher(name).matches()) {
            out.add("Name '%s' should be kebab-case (lowercase letters, digits, and hyphens only)".formatted(name));
        } else if (name.startsWith("-") || name.endsWith("-") || name.contains("--")) {
            out.add("Name '%s' cannot start/end with hyphen or contain consecutive hyphens".formatted(name));
        } else if (length(name) > MAX_NAME) {
            out.add("Name is too long (%d characters). Maximum is %d characters.".formatted(length(name), MAX_NAME));
        }
        if (directoryName != null && !name.equals(directoryName)) {
            out.add("Name '%s' must match the skill's directory '%s'".formatted(name, directoryName));
        }
    }

    private static void checkDescription(@Nullable Object value, List<String> out) {
        if (!(value instanceof String raw)) {
            out.add("Description must be a string, got " + typeName(value));
            return;
        }
        var description = raw.strip();
        if (description.isEmpty()) return;
        if (description.contains("<") || description.contains(">")) {
            out.add("Description cannot contain angle brackets (< or >)");
        } else if (length(description) > MAX_DESCRIPTION) {
            out.add("Description is too long (%d characters). Maximum is %d characters."
                    .formatted(length(description), MAX_DESCRIPTION));
        }
    }

    /** Python's {@code len}, which counts code points. */
    private static int length(String s) {
        return s.codePointCount(0, s.length());
    }

    /** The Python type name the validator would print for what PyYAML built. */
    private static String typeName(@Nullable Object value) {
        return switch (value) {
            case null -> "NoneType";
            case Boolean _ -> "bool";
            case Integer _, Long _, BigInteger _ -> "int";
            case Double _, Float _ -> "float";
            case Date _ -> "datetime";
            case List<?> _ -> "list";
            case Map<?, ?> _ -> "dict";
            default -> value.getClass().getSimpleName();
        };
    }
}
