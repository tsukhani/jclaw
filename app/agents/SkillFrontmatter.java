package agents;

import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A SKILL.md frontmatter in the Agent Skills layout (agentskills.io): the spec's keys at the top
 * level and JClaw's own under {@code metadata}, which is what lets a JClaw skill pass Anthropic's
 * validator and an imported skill keep every key it arrived with. {@link #parse} also reads the
 * earlier layout, which kept JClaw's keys at the top level; {@link #render} always writes the spec's.
 *
 * <p>The spec makes every {@code metadata} value a string, so JClaw's lists are space-separated
 * words, and a list element that is not one word is refused: written as a word list, a file named
 * {@code "x bash"} would read back as two commands.
 */
public final class SkillFrontmatter {

    public static final String NAME = "name";
    public static final String DESCRIPTION = "description";
    public static final String METADATA = "metadata";
    public static final String VERSION = "version";
    public static final String AUTHOR = "author";
    public static final String ICON = "jclaw-icon";
    public static final String TOOLS = "jclaw-tools";
    public static final String COMMANDS = "jclaw-commands";
    public static final String MCP_SERVERS = "jclaw-mcp-servers";

    /** The earlier layout's top-level keys, in the order their metadata keys are written. */
    private static final List<Map.Entry<String, String>> LEGACY_KEYS = List.of(
            Map.entry("version", VERSION), Map.entry("author", AUTHOR), Map.entry("icon", ICON),
            Map.entry("tools", TOOLS), Map.entry("commands", COMMANDS), Map.entry("mcp_servers", MCP_SERVERS));
    private static final Set<String> LIST_KEYS = Set.of(TOOLS, COMMANDS, MCP_SERVERS);

    private static final Pattern FENCED = Pattern.compile("^---\\s*\\n(.*?)\\n---", Pattern.DOTALL);
    private static final Pattern WORD = Pattern.compile("[A-Za-z0-9._+-]++");
    private static final Pattern WORD_SEPARATOR = Pattern.compile("[\\s,]++");
    private static final Pattern PLAIN_SCALAR = Pattern.compile("[A-Za-z_][A-Za-z0-9 _./()@+,=-]*+");
    private static final Pattern PLAIN_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*+");
    /** YAML 1.1 reads these as booleans or null, so Python's validator would see a non-string. */
    private static final Set<String> RESERVED_WORDS = Set.of("y", "n", "yes", "no", "true", "false", "on", "off", "null");

    private final LinkedHashMap<String, Object> top;
    private final LinkedHashMap<String, Object> meta;
    /** Metadata keys {@link #parse} moved up from the earlier layout's top level. */
    private final Set<String> fromEarlierLayout;

    private SkillFrontmatter(LinkedHashMap<String, Object> top, LinkedHashMap<String, Object> metadata,
                             Set<String> fromEarlierLayout) {
        this.top = top;
        this.meta = metadata;
        this.fromEarlierLayout = fromEarlierLayout;
    }

    public static SkillFrontmatter empty() {
        return new SkillFrontmatter(new LinkedHashMap<>(), new LinkedHashMap<>(), new HashSet<>());
    }

    public SkillFrontmatter copy() {
        return new SkillFrontmatter(new LinkedHashMap<>(top), new LinkedHashMap<>(meta), new HashSet<>(fromEarlierLayout));
    }

    /** The frontmatter opening a whole SKILL.md, or null when it has none or it is not a YAML mapping. */
    public static @Nullable SkillFrontmatter of(@Nullable String skillMd) {
        if (skillMd == null) return null;
        var matcher = FENCED.matcher(skillMd);
        return matcher.find() ? parse(matcher.group(1)) : null;
    }

    /**
     * Parse the YAML between the fences, moving the earlier layout's top-level keys under
     * {@code metadata} (where a metadata key of the same meaning already exists, it wins).
     * Returns null when the text is not YAML or not a mapping; the caller then falls back to
     * {@link SkillLoader}'s line reader, which tolerates what YAML refuses.
     */
    public static @Nullable SkillFrontmatter parse(@Nullable String yaml) {
        if (yaml == null) return null;
        Object root;
        try {
            root = loader().load(yaml);
        } catch (YAMLException _) {
            return null;
        }
        if (root == null) return empty();
        if (!(root instanceof Map<?, ?> map)) return null;
        var top = new LinkedHashMap<String, Object>();
        map.forEach((k, v) -> top.put(String.valueOf(k), v == null ? "" : v));
        var metadata = new LinkedHashMap<String, Object>();
        var declared = top.remove(METADATA);
        if (declared instanceof Map<?, ?> m) {
            m.forEach((k, v) -> metadata.put(String.valueOf(k), v == null ? "" : v));
        } else if (declared != null && !"".equals(declared)) {
            return null;
        }
        var moved = new HashSet<String>();
        for (var legacy : LEGACY_KEYS) {
            var key = legacy.getValue();
            var value = top.remove(legacy.getKey());
            if (value == null || metadata.containsKey(key)) continue;
            metadata.put(key, LIST_KEYS.contains(key) ? joinWords(value) : value);
            moved.add(key);
        }
        return new SkillFrontmatter(top, metadata, moved);
    }

    // --- reading ---

    public @Nullable String name() { return text(top.get(NAME)); }

    public @Nullable String description() { return text(top.get(DESCRIPTION)); }

    public @Nullable String version() { return text(meta.get(VERSION)); }

    public @Nullable String author() { return text(meta.get(AUTHOR)); }

    public @Nullable String icon() { return text(meta.get(ICON)); }

    public List<String> tools() { return words(meta.get(TOOLS)); }

    /** True when the skill declares its tools, even as none: only an undeclared skill has its body scanned for them. */
    public boolean toolsDeclared() { return meta.containsKey(TOOLS); }

    public List<String> commands() { return words(meta.get(COMMANDS)); }

    public List<String> mcpServers() { return words(meta.get(MCP_SERVERS)); }

    /**
     * True when {@code key} came from the earlier layout's top level rather than from
     * {@code metadata}. On an import that is a foreign skill's own key, not JClaw's: another
     * runtime's {@code tools: Bash, Read} is not a JClaw tool list.
     */
    public boolean fromEarlierLayout(String key) { return fromEarlierLayout.contains(key); }

    /** One top-level value as written, or null when absent. */
    public @Nullable Object get(String key) { return top.get(key); }

    // --- writing ---

    /** Set a top-level value; null removes the key. */
    public SkillFrontmatter put(String key, @Nullable Object value) {
        if (value == null) top.remove(key);
        else top.put(key, value);
        return this;
    }

    /** Set a metadata value; null removes the key. */
    public SkillFrontmatter putMetadata(String key, @Nullable String value) {
        if (value == null) meta.remove(key);
        else meta.put(key, value);
        return this;
    }

    public SkillFrontmatter setVersion(@Nullable String version) { return putMetadata(VERSION, version); }

    // The three list setters declare the key even for an empty list: declared as none is not undeclared.

    public SkillFrontmatter setTools(List<String> tools) { return putMetadata(TOOLS, requireWords(TOOLS, tools)); }

    public SkillFrontmatter setCommands(List<String> commands) { return putMetadata(COMMANDS, requireWords(COMMANDS, commands)); }

    public SkillFrontmatter setMcpServers(List<String> servers) { return putMetadata(MCP_SERVERS, requireWords(MCP_SERVERS, servers)); }

    /** True when {@code s} survives a round trip through a word list as exactly one element. */
    public static boolean isWord(@Nullable String s) {
        return s != null && WORD.matcher(s).matches();
    }

    /**
     * The fenced block, ending in a newline: name and description first, the other top-level
     * keys in their order, then {@code metadata}. Every value reads back unchanged by any YAML
     * parser, Python's included.
     */
    public String render() {
        var out = new StringBuilder("---\n");
        var name = top.get(NAME);
        if (name != null) line(out, "", NAME, name);
        var description = top.get(DESCRIPTION);
        if (description instanceof String s && foldable(s)) {
            out.append(DESCRIPTION).append(": >-\n  ").append(s.strip()).append('\n');
        } else if (description != null) {
            line(out, "", DESCRIPTION, description);
        }
        top.forEach((key, value) -> {
            if (!key.equals(NAME) && !key.equals(DESCRIPTION)) line(out, "", key, value);
        });
        if (!meta.isEmpty()) {
            out.append(METADATA).append(":\n");
            meta.forEach((key, value) -> line(out, "  ", key, value));
        }
        return out.append("---\n").toString();
    }

    // --- helpers ---

    /** Every scalar loads as a string: {@code 1.0} would otherwise be a double and {@code no} false. */
    private static Yaml loader() {
        var loading = new LoaderOptions();
        var dumping = new DumperOptions();
        return new Yaml(new SafeConstructor(loading), new Representer(dumping), dumping, loading, new Resolver() {
            @Override
            protected void addImplicitResolvers() {
                // none: see the method's Javadoc
            }
        });
    }

    private static @Nullable String text(@Nullable Object value) {
        return value instanceof String s && !s.isBlank() ? s.strip() : null;
    }

    private static List<String> words(@Nullable Object value) {
        var out = new ArrayList<String>();
        if (value instanceof String s) {
            for (var word : WORD_SEPARATOR.split(s.strip())) {
                if (isWord(word) && !out.contains(word)) out.add(word);
            }
        } else if (value instanceof List<?> list) {
            for (var item : list) {
                var word = String.valueOf(item).strip();
                if (isWord(word) && !out.contains(word)) out.add(word);
            }
        }
        return out;
    }

    /** A legacy list as one metadata string. An element that is not a word is dropped rather than split into several. */
    private static String joinWords(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(item -> String.valueOf(item).strip())
                    .filter(SkillFrontmatter::isWord).collect(Collectors.joining(" "));
        }
        var single = String.valueOf(value).strip();
        return isWord(single) ? single : "";
    }

    private static String requireWords(String key, List<String> values) {
        for (var value : values) {
            if (!isWord(value)) {
                throw new IllegalArgumentException("%s entry is not a single word: \"%s\"".formatted(key, value));
            }
        }
        return String.join(" ", values);
    }

    private static boolean foldable(String s) {
        var stripped = s.strip();
        return !stripped.isEmpty() && stripped.codePoints().noneMatch(SkillFrontmatter::escaped);
    }

    private static void line(StringBuilder out, String indent, String key, Object value) {
        out.append(indent).append(key(key)).append(": ")
                .append(value instanceof String s ? scalar(s) : flow(value)).append('\n');
    }

    private static String key(String key) {
        return PLAIN_KEY.matcher(key).matches() && !RESERVED_WORDS.contains(key.toLowerCase(Locale.ROOT))
                ? key : quoted(key);
    }

    private static String scalar(String s) {
        return PLAIN_SCALAR.matcher(s).matches() && s.equals(s.strip())
                && !RESERVED_WORDS.contains(s.toLowerCase(Locale.ROOT)) ? s : quoted(s);
    }

    /** A non-string value (a nested map or list another client wrote) as a flow collection of quoted strings. */
    private static String flow(@Nullable Object value) {
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .map(e -> quoted(String.valueOf(e.getKey())) + ": " + flow(e.getValue()))
                    .collect(Collectors.joining(", ", "{", "}"));
        }
        if (value instanceof List<?> list) {
            return list.stream().map(SkillFrontmatter::flow).collect(Collectors.joining(", ", "[", "]"));
        }
        return quoted(value == null ? "" : String.valueOf(value));
    }

    private static String quoted(String s) {
        var out = new StringBuilder("\"");
        s.codePoints().forEach(cp -> {
            switch (cp) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (escaped(cp)) out.append("\\u%04x".formatted(cp));
                    else out.appendCodePoint(cp);
                }
            }
        });
        return out.append('"').toString();
    }

    /** Outside YAML's printable set, or a line break to YAML 1.1. */
    private static boolean escaped(int cp) {
        return cp < 0x20 || (cp >= 0x7F && cp <= 0x9F) || cp == 0x2028 || cp == 0x2029
                || (cp >= 0xD800 && cp <= 0xDFFF) || cp == 0xFEFF || cp == 0xFFFE || cp == 0xFFFF;
    }
}
