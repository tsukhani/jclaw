import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;
import services.ConfigKeyCatalog;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Holds {@link ConfigKeyCatalog} to the source (JF-440). {@code /quality} reports every stored row the catalog does not
 * list as read as dead, so a key the code reads but the catalog misses sends a live setting, a credential among them,
 * toward retirement; and a key the code stopped reading but the catalog still lists keeps its dead row out of that
 * report and off the boot deletion.
 *
 * <p><b>What counts as named.</b> Every string literal in {@code app/} and the frontend source whose shape is a config
 * key and whose first segment is a namespace the catalog uses, every String constant resolving to one, the key at
 * every {@code ConfigService} call that resolves, and every concatenation or template literal that builds a key from a
 * catalog namespace. Scanning literals rather than call sites is deliberate: keys reach the Config DB through
 * helpers, wrapper objects and maps of every row, and a first version that read only {@code ConfigService} calls left
 * 74 of the live instance's 250 rows unmatched.
 *
 * <p><b>Known blind spots.</b> A key the code builds from a name no catalog namespace starts (a sidecar's
 * {@code configPrefix() + ".port"}) is invisible to the scan, which is why {@link #UNRESOLVED_KEY_SITES} names each
 * call whose key it cannot resolve. A literal that is still named but whose reader is gone (a seed with no consumer)
 * passes.
 */
class ConfigKeyCatalogConformanceTest extends UnitTest {

    /**
     * The {@code ConfigService} calls whose key the scan cannot resolve, named one by one so a new runtime-built
     * family fails here until someone checks that a {@code READ} line covers the keys it builds. Each is
     * {@code <path under app/>: <method>(<key expression>}.
     */
    private static final Set<String> UNRESOLVED_KEY_SITES = Set.of(
            // Pass-through: the operator's own key, or a migration over keys named on the line above it.
            "controllers/ApiConfigController.java: entry(key",
            "controllers/ApiConfigController.java: setWithSideEffects(key",
            "controllers/ApiProvidersController.java: get(key",
            "controllers/ApiProvidersController.java: setWithSideEffects(key",
            "controllers/ApiRouterController.java: setWithSideEffects(key",
            "jobs/DefaultConfigJob.java: get(key",
            "jobs/DefaultConfigJob.java: isUnreadable(key",
            "jobs/DefaultConfigJob.java: set(key",
            "jobs/DefaultConfigJob.java: get(localModelKey",
            "jobs/DefaultConfigJob.java: set(localModelKey",
            // A helper's parameter: its callers pass literals or constants the scan reads.
            "agents/ContextWindowManager.java: get(key",
            "jobs/ProbeJobs.java: get(baseUrlConfigKey",
            "jobs/RetentionDays.java: get(keyName",
            "services/SessionCompactor.java: getInt(key",
            "services/printing/PrinterDefaults.java: set(key",
            "services/scanners/ScannerDependencies.java: get(key",
            "services/videogen/BflVideoGenerationClient.java: get(key",
            "services/videogen/ReplicateVideoGenerationClient.java: get(key",
            "services/voice/VoiceVad.java: get(key",
            "tools/DocumentsTool.java: get(key",
            "tools/HarnessSandbox.java: get(configKey",
            "tools/SubagentLimits.java: getInt(key",
            "controllers/ApiToolsController.java: getBoolean(key",
            "services/ConversationService.java: getInt(perTypeKey",
            // A local or helper building a family the READ patterns name.
            "controllers/ApiProvidersController.java: get(modelsKey(name)",
            "jobs/TokenizerCalibrationJob.java: get(configKey",
            "jobs/TokenizerCalibrationJob.java: set(configKey",
            "llm/ToolCapabilityMemo.java: get(key",
            "llm/ToolCapabilityMemo.java: set(key",
            "llm/routing/RouterPolicy.java: get(modelsKey(c)",
            "services/ChromeBridgePolicy.java: getBoolean(key(agent.name, ALLOW_ALL_SITES)",
            "services/ChromeBridgePolicy.java: getBoolean(key(agent.name, CLICK_WITHOUT_APPROVAL)",
            "services/ChromeBridgePolicy.java: getBoolean(key(agent.name, ALLOW_SCRIPT)",
            "services/ChromeBridgePolicy.java: get(key(agent.name, ALLOWED_SITES)",
            "services/PricingRefreshService.java: get(configKey",
            "services/PricingRefreshService.java: set(configKey",
            "services/openaicompat/OpenAiCompatibleClientBase.java: get(baseUrlKey",
            "services/openaicompat/OpenAiCompatibleClientBase.java: get(apiKeyKey",
            "services/tts/TtsReferenceVoice.java: get(configKey(engine)",
            "services/tts/TtsReferenceVoice.java: set(configKey(engine)",
            "tools/MessageTool.java: get(key",
            "tools/WebSearchTool.java: get(p.apiKeyKey()",
            "tools/WebSearchTool.java: get(enabledKey()",
            "tools/WebSearchTool.java: get(baseUrlKey()",
            "tools/scrape/DataImpulsePlans.java: get(loginKey(plan)",
            "tools/scrape/DataImpulsePlans.java: get(passwordKey(plan)",
            "jobs/DefaultConfigJob.java: set(DataImpulsePlans.loginKey(plan)",
            "jobs/DefaultConfigJob.java: set(DataImpulsePlans.passwordKey(plan)",
            // A sidecar daemon's own prefix, which the READ patterns name per sidecar.
            "services/LocalSidecarDaemon.java: getInt(cfg.configPrefix() + \".port\"",
            "services/LocalSidecarDaemon.java: get(cfg.configPrefix() + \".hfToken\"",
            "services/LocalSidecarDaemon.java: getInt(cfg.configPrefix() + \".idleTimeoutMinutes\"",
            "services/LocalSidecarDaemon.java: getInt(cfg.configPrefix() + \".timeoutSeconds\"",
            "services/LocalSidecarDaemon.java: getInt(cfg.configPrefix() + \".startupTimeoutSeconds\""
    );

    private static final Pattern KEY_SHAPE = Pattern.compile("[a-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_-]+)+");
    private static final Pattern CONSTANT =
            Pattern.compile("\\b(?:static\\s+final|final\\s+static)\\s+String\\s+(\\w+)\\s*=\\s*([^;]+);");
    private static final Pattern CONSTANT_REF = Pattern.compile("(?:([A-Z]\\w*)\\.)?([A-Z][A-Z0-9_]+)");
    private static final Pattern CONFIG_CONSTANT_NAME = Pattern.compile("\\w*_KEY|\\w*_PREFIX|CFG_\\w+|KEY_\\w+");
    private static final Pattern CONFIG_CALL = Pattern.compile("\\bConfigService\\.(get|getInt|getLong|getBoolean"
            + "|getDouble|isUnreadable|entry|set|setIfAbsent|setWithSideEffects)\\(");
    private static final Pattern STRING_PIECE =
            Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"(\\.formatted\\(.*\\))?", Pattern.DOTALL);
    /** A key built only to delete its row (a legacy key's cleanup) is no read. */
    private static final Pattern DELETE_CALL_BEFORE = Pattern.compile("\\b(delete|deleteWithSideEffects)\\(\\s*$");
    private static final Pattern PREFIX_LITERAL_THEN_PLUS =
            Pattern.compile("\"[a-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_-]+)*\\.\"\\s*\\+");

    /** One source file reduced to what the scan reads: its code with comments gone, and its string literals. */
    private record Source(String path, String className, String code, List<String> literals) {}

    private static Scan scan;

    /** Built once per class; a failure inside is rethrown with its frames, which a static initializer's would lose. */
    private static synchronized Scan scan() {
        if (scan == null) {
            try {
                scan = new Scan();
            } catch (RuntimeException e) {
                var frames = new StringBuilder();
                for (var frame : e.getStackTrace()) {
                    if (frame.getClassName().startsWith("ConfigKeyCatalogConformanceTest")) frames.append(" at ").append(frame);
                }
                throw new AssertionError("the scan failed: " + e + frames, e);
            }
        }
        return scan;
    }

    @Test
    void everyKeyTheCodeNamesIsOnTheCatalog() {
        var missing = new TreeMap<String, String>();
        scan().namedKeys.forEach((key, where) -> {
            if (!onACatalogList(key)) missing.put(key, where);
        });
        assertTrue(missing.isEmpty(), "the code names these config keys, and ConfigKeyCatalog lists none of them, so "
                + "/quality would report a stored row under any of them as dead. Add each to READ, or to NOT_CONFIG "
                + "when no Config DB row is ever stored under it (an application.conf key, a bus event): " + missing);
    }

    @Test
    void everyKeyFamilyTheBackendBuildsIsRead() {
        var missing = new TreeMap<String, String>();
        scan().builtFamilies.forEach((family, where) -> {
            if (!ConfigKeyCatalog.isRead(family.replace("*", "x"))) missing.put(family, where);
        });
        assertTrue(missing.isEmpty(), "the backend builds keys of these shapes at run time (* is the part it fills "
                + "in), and no ConfigKeyCatalog.READ line matches them. Add a READ pattern for each: " + missing);
    }

    /**
     * A Settings panel builds keys from a field list (web_scrape.proxy.${field}) the backend reads one by one, so a
     * frontend shape need only meet some READ line; demanding a pattern would mark every instance read.
     */
    @Test
    void everyKeyFamilyTheFrontendBuildsMeetsAReadLine() {
        var missing = new TreeMap<String, String>();
        scan().frontendFamilies.forEach((family, where) -> {
            var shape = Pattern.compile(ConfigKeyCatalog.regexOf(family));
            if (ConfigKeyCatalog.readLines().stream().noneMatch(l -> shape.matcher(l).matches())) {
                missing.put(family, where);
            }
        });
        assertTrue(missing.isEmpty(), "the frontend builds keys of these shapes (* is the part it fills in), and no "
                + "ConfigKeyCatalog.READ line is one of them, so every row they write reads as unused. Add the keys "
                + "the backend reads to READ: " + missing);
    }

    @Test
    void everyCatalogLineIsStillNamedByTheCode() {
        var gone = new TreeSet<String>();
        for (var line : ConfigKeyCatalog.readLines()) {
            if (!scan().names(line)) gone.add(line);
        }
        assertTrue(gone.isEmpty(), "no code names these ConfigKeyCatalog.READ lines any more, so a stored row under "
                + "one is read by nothing. Move each exact key to RETIRED, which deletes its row at boot, and delete "
                + "a pattern outright: " + gone);

        var stale = new TreeSet<String>();
        for (var line : ConfigKeyCatalog.notConfig()) {
            if (!scan().namedKeys.containsKey(line)) stale.add(line);
        }
        assertTrue(stale.isEmpty(), "no code names these ConfigKeyCatalog.NOT_CONFIG lines any more: delete them: "
                + stale);
    }

    @Test
    void aRetiredKeyIsExactAndNothingReadsIt() {
        var named = new TreeSet<String>();
        var patterns = new TreeSet<String>();
        var stillRead = new TreeSet<String>();
        for (var key : ConfigKeyCatalog.retired()) {
            if (scan().everyLiteral.contains(key)) named.add(key);
            if (key.contains("*")) patterns.add(key);
            if (ConfigKeyCatalog.isRead(key) || ConfigKeyCatalog.notConfig().contains(key)) stillRead.add(key);
        }
        assertTrue(patterns.isEmpty(), "a RETIRED line is deleted at boot by exact key; a pattern there would "
                + "delete every row it matched. List the keys one by one: " + patterns);
        assertTrue(named.isEmpty(), "the code names these RETIRED keys again. A retired key's row is deleted at "
                + "every boot, so reading it again reads nothing: name the setting something new, or move the key "
                + "back to READ and drop it from RETIRED: " + named);
        assertTrue(stillRead.isEmpty(), "these RETIRED keys are also on READ or NOT_CONFIG; a key is on one list: "
                + stillRead);
    }

    @Test
    void theUnresolvedKeySitesAreExactlyThese() {
        var appeared = new TreeSet<>(scan().unresolvedSites);
        appeared.removeAll(UNRESOLVED_KEY_SITES);
        var closed = new TreeSet<>(UNRESOLVED_KEY_SITES);
        closed.removeAll(scan().unresolvedSites);
        assertTrue(appeared.isEmpty(), "these ConfigService calls take a key the scan cannot resolve. Check that a "
                + "ConfigKeyCatalog.READ line matches every key each can build, then add the site to "
                + "UNRESOLVED_KEY_SITES: " + appeared);
        assertTrue(closed.isEmpty(), "these calls are gone or now resolve: delete them from UNRESOLVED_KEY_SITES: "
                + closed);
    }

    /** The scan must see keys, or every check above passes on nothing. */
    @Test
    void theScanReadsTheKeysItIsMeantTo() {
        assertTrue(scan().namedKeys.containsKey("chat.maxToolRounds"), "a literal at a ConfigService call");
        assertTrue(scan().namedKeys.containsKey("scanner.virustotal.apiKey"), "a literal a wrapper reads");
        assertTrue(scan().namedKeys.containsKey("chat.compaction.factsBudgetPercent"), "a constant a helper reads");
        assertTrue(scan().frontendLiterals.contains("tts.local.idleTimeoutMinutes"), "a frontend literal");
        assertTrue(scan().frontendFamilies.containsKey("web_scrape.proxy.*"), "a frontend template literal");
        assertTrue(scan().builtFamilies.containsKey("provider.*.paymentModality"), "a constant-prefix concatenation");
        assertTrue(scan().unresolvedSites.contains("services/LocalSidecarDaemon.java: getInt(cfg.configPrefix() "
                + "+ \".port\""), "a key built from a prefix no namespace starts");
        assertFalse(ConfigKeyCatalog.isRead("chat.compaction.onToolRound"), "a key JF-236 stopped reading");
    }

    private static boolean onACatalogList(String key) {
        return ConfigKeyCatalog.isRead(key) || ConfigKeyCatalog.retired().contains(key)
                || ConfigKeyCatalog.notConfig().contains(key);
    }

    private static final class Scan {
        final Map<String, String> namedKeys = new TreeMap<>();
        final Map<String, String> builtFamilies = new TreeMap<>();
        final Map<String, String> frontendFamilies = new TreeMap<>();
        final Set<String> everyLiteral = new HashSet<>();
        final Set<String> frontendLiterals = new HashSet<>();
        final Set<String> unresolvedSites = new TreeSet<>();
        private final Set<String> namespaces = new HashSet<>();
        private final Map<String, Map<String, String>> constantsByClass = new HashMap<>();
        private final Map<String, List<String>> classesByConstant = new HashMap<>();

        Scan() {
            for (var line : allCatalogLines()) namespaces.add(line.substring(0, line.indexOf('.')));
            var java = javaSources();
            for (var source : java) {
                var constants = constantsByClass.computeIfAbsent(source.className(), _ -> new HashMap<>());
                var m = CONSTANT.matcher(source.code());
                while (m.find()) {
                    constants.put(m.group(1), m.group(2).strip());
                    classesByConstant.computeIfAbsent(m.group(1), _ -> new ArrayList<>()).add(source.className());
                }
            }
            codeNamespaces(java);
            for (var source : java) {
                source.literals().forEach(l -> name(l, source.path()));
                scanCalls(source);
                scanConcatenations(source);
            }
            constantsByClass.forEach((cls, constants) -> constants.forEach((name, expr) -> {
                var value = resolve(expr, cls, new HashSet<>());
                if (value != null && !value.contains("*")) name(value, cls + "." + name);
            }));
            for (var source : frontendSources()) {
                for (var literal : source.literals()) {
                    if (literal.contains("*")) family(frontendFamilies, literal, source.path());
                    else name(literal, source.path());
                    frontendLiterals.add(literal);
                }
            }
        }

        /**
         * Namespaces from the code as well as the catalog: taken from the catalog alone, deleting a namespace's last
         * line (jtokkit.safetyMultiplier.*, its 52 live rows) blinded the scan to every key in it.
         */
        private void codeNamespaces(List<Source> java) {
            constantsByClass.forEach((cls, constants) -> constants.forEach((name, expr) -> {
                if (!CONFIG_CONSTANT_NAME.matcher(name).matches()) return;
                var value = resolve(expr, cls, new HashSet<>());
                if (value != null && !value.startsWith("*") && value.indexOf('.') > 0) {
                    namespaces.add(value.substring(0, value.indexOf('.')));
                }
            }));
            for (var source : java) {
                var m = CONFIG_CALL.matcher(source.code());
                while (m.find()) {
                    var arg = expressionAt(source.code(), m.end()).strip();
                    if (!arg.startsWith("\"") && !CONSTANT_REF.matcher(arg).matches()) continue;
                    var value = resolve(arg, source.className(), new HashSet<>());
                    if (value != null && !value.startsWith("*") && value.indexOf('.') > 0) {
                        namespaces.add(value.substring(0, value.indexOf('.')));
                    }
                }
            }
        }

        /** Whether the code still names a catalog line: an exact key verbatim, a pattern by each of its fixed pieces. */
        boolean names(String line) {
            if (!line.contains("*")) return namedKeys.containsKey(line);
            for (var piece : line.split("\\*")) {
                var fixed = piece.replaceAll("^\\.+|\\.+$", "");
                if (!fixed.isEmpty() && everyLiteral.stream().noneMatch(l -> l.contains(fixed))) return false;
            }
            return true;
        }

        private void name(String literal, String where) {
            everyLiteral.add(literal);
            if (KEY_SHAPE.matcher(literal).matches() && namespaces.contains(literal.substring(0, literal.indexOf('.')))) {
                namedKeys.putIfAbsent(literal, where);
            }
        }

        private void family(Map<String, String> into, String pattern, String where) {
            var sample = pattern.replace("*", "x");
            if (KEY_SHAPE.matcher(sample).matches() && namespaces.contains(sample.substring(0, sample.indexOf('.')))) {
                into.putIfAbsent(pattern, where);
            }
        }

        private void scanCalls(Source source) {
            var m = CONFIG_CALL.matcher(source.code());
            while (m.find()) {
                var arg = expressionAt(source.code(), m.end()).strip();
                var resolvable = arg.startsWith("\"") || CONSTANT_REF.matcher(arg).matches()
                        || splitTopLevel(arg, '+').stream().anyMatch(p -> isStringPiece(p.strip(), source.className()));
                var value = resolvable ? resolve(arg, source.className(), new HashSet<>()) : null;
                if (value == null || value.startsWith("*")) {
                    unresolvedSites.add(source.path() + ": " + m.group(1) + "(" + arg);
                } else if (value.contains("*")) {
                    family(builtFamilies, value, source.path());
                } else {
                    everyLiteral.add(value);
                    if (KEY_SHAPE.matcher(value).matches()) namedKeys.putIfAbsent(value, source.path());
                }
            }
        }

        /** {@code "ns.part." + x + ".suffix"} or {@code PREFIX + x}, wherever the code builds a key from a prefix. */
        private void scanConcatenations(Source source) {
            var code = source.code();
            var starts = new TreeSet<Integer>();
            var literal = PREFIX_LITERAL_THEN_PLUS.matcher(code);
            while (literal.find()) starts.add(literal.start());
            var ref = Pattern.compile("(?<![\\w.])((?:[A-Z]\\w*\\.)?[A-Z][A-Z0-9_]+)\\s*\\+").matcher(code);
            while (ref.find()) {
                var value = resolve(ref.group(1), source.className(), new HashSet<>());
                if (value != null && !value.contains("*") && value.endsWith(".")) starts.add(ref.start());
            }
            for (int start : starts) {
                if (DELETE_CALL_BEFORE.matcher(code.substring(Math.max(0, start - 40), start)).find()) continue;
                var value = resolve(expressionAt(code, start), source.className(), new HashSet<>());
                if (value != null && value.contains("*")) family(builtFamilies, value, source.path());
            }
        }

        private boolean isStringPiece(String piece, String cls) {
            if (STRING_PIECE.matcher(piece).matches()) return true;
            var value = CONSTANT_REF.matcher(piece).matches() ? resolve(piece, cls, new HashSet<>()) : null;
            return value != null && !value.contains("*");
        }

        /** The value {@code expr} builds, {@code *} for each piece it cannot know; null when nothing in it is fixed. */
        private String resolve(String expr, String cls, Set<String> seen) {
            var out = new StringBuilder();
            boolean anyFixed = false;
            for (var raw : splitTopLevel(expr, '+')) {
                var piece = raw.strip();
                var str = STRING_PIECE.matcher(piece);
                if (str.matches()) {
                    var text = str.group(1);
                    out.append(str.group(2) != null ? text.replaceAll("%[sd]", "*") : text);
                    anyFixed = true;
                    continue;
                }
                var constant = CONSTANT_REF.matcher(piece);
                if (constant.matches()) {
                    var owner = owner(constant.group(1) != null ? constant.group(1) : cls, constant.group(2));
                    if (owner != null && seen.add(owner + "." + constant.group(2))) {
                        var value = resolve(constantsByClass.get(owner).get(constant.group(2)), owner, seen);
                        if (value != null) {
                            out.append(value);
                            anyFixed = true;
                            continue;
                        }
                    }
                }
                out.append('*');
            }
            return anyFixed ? out.toString() : null;
        }

        private String owner(String cls, String name) {
            if (constantsByClass.getOrDefault(cls, Map.of()).containsKey(name)) return cls;
            var owners = classesByConstant.getOrDefault(name, List.of());
            return owners.size() == 1 ? owners.getFirst() : null;
        }
    }

    private static List<String> allCatalogLines() {
        var lines = new ArrayList<>(ConfigKeyCatalog.readLines());
        lines.addAll(ConfigKeyCatalog.retired());
        lines.addAll(ConfigKeyCatalog.notConfig());
        return lines;
    }

    /** The expression starting at {@code from}: up to a top-level comma, semicolon or closing bracket. */
    private static String expressionAt(String code, int from) {
        int depth = 0;
        for (int i = from; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '"') {
                i = endOfString(code, i, '"');
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                if (depth == 0) return code.substring(from, i);
                depth--;
            } else if (depth == 0 && (c == ',' || c == ';' || c == '?' || c == ':')) {
                return code.substring(from, i);
            }
        }
        return code.substring(from);
    }

    private static List<String> splitTopLevel(String expr, char separator) {
        var parts = new ArrayList<String>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (c == '"' || c == '\'') {
                i = endOfString(expr, i, c);
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (c == separator && depth == 0) {
                parts.add(expr.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(expr.substring(start));
        return parts;
    }

    /** The index of the quote closing the string that opens at {@code open}, or of the line end that cuts it short. */
    private static int endOfString(String s, int open, char quote) {
        int i = open + 1;
        while (i < s.length() && s.charAt(i) != quote && (quote == '`' || s.charAt(i) != '\n')) {
            i += s.charAt(i) == '\\' ? 2 : 1;
        }
        return Math.min(i, s.length() - 1);
    }

    /**
     * Comments dropped and string literals collected. A Java text block is skipped whole: the keys inside one are a
     * prompt's or a query's text, not a read. A template literal's {@code ${...}} becomes {@code *}.
     */
    private static Source lex(String path, String className, String src, boolean javaScript) {
        var code = new StringBuilder();
        var literals = new ArrayList<String>();
        int i = 0;
        while (i < src.length()) {
            char c = src.charAt(i);
            if (!javaScript && src.startsWith("\"\"\"", i)) {
                int end = src.indexOf("\"\"\"", i + 3);
                i = end < 0 ? src.length() : end + 3;
                code.append("\"\"");
            } else if (c == '"' || c == '\'' || (javaScript && c == '`')) {
                int end = Math.max(endOfString(src, i, c), i);
                var body = end > i ? src.substring(i + 1, end) : "";
                if (c != '\'' || javaScript) literals.add(c == '`' ? body.replaceAll("\\$\\{[^}]*}", "*") : body);
                code.append(src, i, end + 1);
                i = end + 1;
            } else if (src.startsWith("//", i)) {
                int end = src.indexOf('\n', i);
                i = end < 0 ? src.length() : end;
            } else if (src.startsWith("/*", i)) {
                int end = src.indexOf("*/", i + 2);
                i = end < 0 ? src.length() : end + 2;
                code.append(' ');
            } else {
                code.append(c);
                i++;
            }
        }
        return new Source(path, className, code.toString(), literals);
    }

    private static Path repo(String relative) {
        return Path.of(Play.applicationPath.getAbsolutePath()).resolve(relative);
    }

    private static List<Source> javaSources() {
        var app = repo("app");
        var catalog = repo("app/services/ConfigKeyCatalog.java");
        return files(app, ".java").stream()
                .filter(p -> !p.equals(catalog))
                .map(p -> {
                    var name = p.getFileName().toString();
                    return lex(app.relativize(p).toString(), name.substring(0, name.length() - 5), read(p), false);
                })
                .toList();
    }

    private static final Pattern VUE_SCRIPT = Pattern.compile("<script[^>]*>([\\s\\S]*?)</script>");
    private static final Pattern VUE_EXPRESSION =
            Pattern.compile("\\s[:@#\\w.-]+=\"([^\"]*)\"|\\{\\{([\\s\\S]*?)}}");

    /** A .vue file's scripts are lexed whole; its template only inside attribute values and interpolations. */
    private static List<Source> frontendSources() {
        var frontend = repo("frontend");
        var sources = new ArrayList<Source>();
        for (var dir : List.of("components", "composables", "layouts", "middleware", "pages", "plugins", "utils")) {
            for (var p : files(frontend.resolve(dir), ".vue", ".ts")) sources.add(frontendSource(frontend, p));
        }
        for (var root : List.of("app.vue", "error.vue")) sources.add(frontendSource(frontend, frontend.resolve(root)));
        return sources;
    }

    private static Source frontendSource(Path frontend, Path p) {
        var path = frontend.relativize(p).toString();
        var src = read(p);
        if (path.endsWith(".ts")) return lex(path, "", src, true);
        var scripts = new StringBuilder();
        var script = VUE_SCRIPT.matcher(src);
        while (script.find()) scripts.append(script.group(1)).append('\n');
        var template = VUE_SCRIPT.matcher(src).replaceAll("").replaceAll("<!--[\\s\\S]*?-->", "");
        var expression = VUE_EXPRESSION.matcher(template);
        while (expression.find()) {
            scripts.append(expression.group(1) != null ? expression.group(1) : expression.group(2)).append('\n');
        }
        return lex(path, "", scripts.toString(), true);
    }

    private static List<Path> files(Path root, String... suffixes) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> Stream.of(suffixes).anyMatch(s -> p.toString().endsWith(s)))
                    .filter(p -> !p.toString().endsWith(".d.ts"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
