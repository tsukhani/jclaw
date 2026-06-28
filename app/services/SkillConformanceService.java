package services;

import agents.SkillLoader;
import agents.ToolCatalog;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import llm.LlmProvider;
import llm.LlmTypes.ChatMessage;
import llm.ProviderRegistry;
import models.Agent;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Conforms an externally-authored skill (downloaded from GitHub via the
 * importable-skills catalog) to JClaw's skill format/contract — the same
 * contract the {@code skill-creator} skill applies when it creates or refactors
 * skills via chat. External skills are written for Claude Code / Cursor / etc.,
 * so their frontmatter and tool names ({@code Bash}, {@code Read}, {@code WebFetch})
 * don't match JClaw's vocabulary ({@code exec}, {@code filesystem}, {@code web_fetch});
 * conformance is therefore a TRANSFORMATION, not a pass/fail gate.
 *
 * <h2>Generation vs. validation</h2>
 * The transformation is split deliberately:
 * <ul>
 *   <li><b>Generation (LLM):</b> {@link #proposeWithLlm} rewrites the skill to
 *       the contract — maps foreign tool names onto JClaw's live Tool Catalog,
 *       derives a kebab-case name + icon, rewrites the body. The model is good
 *       at the fuzzy mapping but is never trusted as the gate.</li>
 *   <li><b>Validation (code):</b> {@link #applyHardGates} is a pure, deterministic
 *       function that accepts or rejects the proposal against the real
 *       {@link ToolCatalog}/{@link agents.ToolRegistry}: every declared tool MUST
 *       exist or the import is rejected, and {@code commands:} is derived strictly
 *       from the binaries actually on disk — never from the model.</li>
 * </ul>
 * Binary/credential directory structure is enforced downstream by
 * {@link SkillPromotionService#publishToGlobal} (binaries → {@code tools/}, etc.).
 */
public final class SkillConformanceService {

    private static final String CATEGORY = "skills";
    private static final String SKILL_MD = "SKILL.md";
    // Possessive quantifiers (++ / *+) keep the match identical for this anchored
    // validator while disabling the backtracking that could overflow the stack on
    // pathological input (java:S5998).
    private static final Pattern KEBAB = Pattern.compile("^[a-z0-9]++(?:-[a-z0-9]++)*+$");
    private static final String DEFAULT_ICON = "🛠️";
    private static final int LLM_TIMEOUT_SECONDS = 120;

    private SkillConformanceService() {}

    /** Terminal outcome of conforming a staged skill. */
    public record ConformanceResult(boolean ok, String skillName, String message) {
        static ConformanceResult fail(String message) { return new ConformanceResult(false, null, message); }
        static ConformanceResult ok(String skillName) { return new ConformanceResult(true, skillName, "conformed"); }
    }

    /** The LLM's proposed normalization (generation half) — frontmatter only;
     *  the body is preserved verbatim, so the model never re-emits it. */
    public record ProposedSkill(String name, String description, String icon, List<String> tools) {}

    /** A skill that passed the deterministic gate, ready to render as SKILL.md. */
    public record ConformedSkill(String name, String description, String icon,
                                 List<String> tools, List<String> commands, String author, String body) {
        /** Render the conforming SKILL.md (frontmatter + body) in the skill-creator
         *  field order. {@code version:} is intentionally omitted — it is
         *  system-managed and auto-bumped by the write path. */
        public String toSkillMd() {
            return """
                   ---
                   name: %s
                   description: %s
                   author: %s
                   tools: [%s]
                   commands: [%s]
                   icon: %s
                   ---

                   %s
                   """.formatted(name, description, author,
                    String.join(", ", tools), String.join(", ", commands), icon,
                    body == null ? "" : body.strip());
        }
    }

    /** Accept ({@code skill} set) or reject ({@code reason} set) decision from the gate. */
    public record GateOutcome(boolean ok, String reason, ConformedSkill skill) {
        static GateOutcome reject(String reason) { return new GateOutcome(false, reason, null); }
        static GateOutcome accept(ConformedSkill skill) { return new GateOutcome(true, null, skill); }
    }

    /**
     * Conform the staged skill IN PLACE: rewrite {@code stagedDir/SKILL.md} with
     * normalized frontmatter + body. Returns the conformed skill name on success.
     *
     * @param stagedDir    the downloaded skill folder (SKILL.md at its root)
     * @param fallbackName kebab-able fallback name (the catalog skillId)
     * @param provenance   recorded as the skill's {@code author:} (e.g. "owner/repo")
     */
    public static ConformanceResult conform(Path stagedDir, String fallbackName, String provenance) {
        var skillFile = stagedDir.resolve(SKILL_MD);
        if (!Files.isRegularFile(skillFile)) {
            return ConformanceResult.fail("no SKILL.md found in the imported skill");
        }
        String raw;
        try {
            raw = Files.readString(skillFile);
        } catch (IOException e) {
            return ConformanceResult.fail("could not read SKILL.md: " + e.getMessage());
        }

        var stagedBinaries = listStagedBinaries(stagedDir);
        // Preserve the original body verbatim; the LLM only normalizes frontmatter.
        var split = SkillLoader.splitFrontmatter(raw);
        String originalBody;
        if (split != null && split.frontmatter() != null && !split.frontmatter().isBlank()) {
            originalBody = split.body() != null ? split.body() : "";
        } else {
            originalBody = raw;  // no frontmatter to strip — the whole file is the body
        }

        var proposed = proposeWithLlm(raw, fallbackName);
        if (proposed == null) {
            return ConformanceResult.fail("conformance pass failed (LLM unavailable or returned an invalid response)");
        }

        var gate = applyHardGates(proposed, fallbackName, stagedBinaries, provenance, originalBody);
        if (!gate.ok()) return ConformanceResult.fail(gate.reason());

        try {
            Files.writeString(skillFile, gate.skill().toSkillMd());
        } catch (IOException e) {
            return ConformanceResult.fail("could not write conformed SKILL.md: " + e.getMessage());
        }
        return ConformanceResult.ok(gate.skill().name());
    }

    /**
     * Deterministic accept/reject over the LLM proposal — the VALIDATION half.
     * Pure (no I/O, no LLM): unit-testable in isolation. Enforces the
     * skill-creator contract:
     * <ul>
     *   <li><b>name</b> — kebab-case; falls back to the kebab-ed catalog id.</li>
     *   <li><b>tools</b> — every declared tool MUST exist in this build's
     *       {@link agents.ToolRegistry} (via {@link ToolCatalog#validateSkillTools});
     *       any {@code unknown} name → REJECT (the LLM failed to map it).</li>
     *   <li><b>commands</b> — derived ONLY from {@code stagedBinaries} (the files
     *       actually on disk), never from the model. This satisfies both contract
     *       directions by construction: every tools/ binary is declared, and every
     *       declared command exists.</li>
     *   <li><b>icon</b> — non-blank; defaults to 🛠️.</li>
     * </ul>
     */
    public static GateOutcome applyHardGates(ProposedSkill proposed, String fallbackName,
                                             Set<String> stagedBinaries, String author, String body) {
        if (proposed == null) return GateOutcome.reject("empty conformance proposal");

        var name = kebabOrNull(proposed.name());
        if (name == null) name = kebabCase(fallbackName);
        if (name == null || name.isBlank()) {
            return GateOutcome.reject("could not derive a kebab-case skill name");
        }

        var description = proposed.description() != null ? proposed.description().strip() : "";
        if (description.isBlank()) description = name;

        var tools = normalizeTools(proposed.tools());
        var validation = ToolCatalog.validateSkillTools(Set.of(), tools);
        if (!validation.unknown().isEmpty()) {
            return GateOutcome.reject(
                    "imported skill declares tools not available in this JClaw build: %s — the conformance pass could not map them to a real tool"
                            .formatted(String.join(", ", validation.unknown())));
        }

        var commands = new ArrayList<>(stagedBinaries != null ? stagedBinaries : Set.<String>of());

        var icon = proposed.icon() != null ? proposed.icon().strip() : "";
        if (icon.isBlank()) icon = DEFAULT_ICON;

        var safeBody = body != null ? body : "";
        var safeAuthor = author != null && !author.isBlank() ? author.strip() : "imported";
        return GateOutcome.accept(new ConformedSkill(name, description, icon, tools, commands, safeAuthor, safeBody));
    }

    // --- generation (LLM) ---

    private static ProposedSkill proposeWithLlm(String rawSkillMd, String fallbackName) {
        var provider = resolveProvider();
        if (provider == null) {
            EventLogger.warn(CATEGORY, "Conformance skipped: no LLM provider resolved");
            return null;
        }
        var modelId = resolveModel();
        if (modelId == null || modelId.isBlank()) {
            EventLogger.warn(CATEGORY, "Conformance skipped: no model resolved");
            return null;
        }
        var catalog = ToolCatalog.formatCatalogForPrompt(Set.of());
        var messages = List.of(
                ChatMessage.system(conformanceSystemPrompt(catalog)),
                ChatMessage.user("Skill id (fallback name): %s\n\nCurrent SKILL.md:\n%s".formatted(fallbackName, rawSkillMd)));
        try {
            var response = provider.chat(modelId, messages, null, null, null, LLM_TIMEOUT_SECONDS, null);
            var text = response.choices().getFirst().message().content().toString();
            return parseProposed(parseJsonObjectLenient(text));
        } catch (Exception e) {
            EventLogger.warn(CATEGORY, "Conformance LLM pass failed: " + e.getMessage());
            return null;
        }
    }

    private static ProposedSkill parseProposed(JsonObject o) {
        return new ProposedSkill(str(o, "name"), str(o, "description"), str(o, "icon"), strList(o, "tools"));
    }

    /** Tolerant JSON extraction for LLM output: strip code fences + leading/trailing
     *  prose (first '{' .. last '}'), then parse leniently. The model returns only
     *  small frontmatter now (no body), so this rarely has to do much. */
    private static JsonObject parseJsonObjectLenient(String raw) {
        var t = raw == null ? "" : raw.strip();
        if (t.startsWith("```")) {
            t = t.replaceFirst("^```(?:json)?\\s*\\n?", "").replaceFirst("\\n?```$", "").strip();
        }
        int i = t.indexOf('{');
        int j = t.lastIndexOf('}');
        if (i >= 0 && j > i) t = t.substring(i, j + 1);
        var reader = new JsonReader(new StringReader(t));
        reader.setStrictness(Strictness.LENIENT);
        return JsonParser.parseReader(reader).getAsJsonObject();
    }

    private static String conformanceSystemPrompt(String toolCatalog) {
        return """
               You produce JClaw skill FRONTMATTER for an external agent "skill" (authored for Claude
               Code, Cursor, or another runtime). The skill's BODY is preserved verbatim by JClaw — you
               do NOT rewrite or return it; you only read it to decide which tools the skill needs.

               Return these JClaw frontmatter fields:
               - name: kebab-case (e.g. web-scraper). Derive from the skill's purpose if the source name
                 isn't already kebab-case.
               - description: one concise sentence describing what the skill does.
               - tools: the EXACT JClaw tool names the skill needs, chosen ONLY from the Tool Catalog
                 below, based on what the body actually does. Map foreign tool names onto JClaw
                 equivalents (e.g. Bash -> exec; Read/Write/Edit/Glob/Grep -> filesystem;
                 WebFetch -> web_fetch; WebSearch -> web_search). If a needed capability has no Tool
                 Catalog entry, omit it. Use [] when the skill is pure reasoning with no tool use.
                 NEVER invent a name that is not in the catalog.
               - icon: a single emoji representing the skill.

               Tool Catalog (the ONLY valid tool names):
               %s

               Return ONLY a JSON object — no prose, no code fences, no body field:
               {"name": "...", "description": "...", "icon": "X", "tools": ["..."]}
               """.formatted(toolCatalog);
    }

    private static LlmProvider resolveProvider() {
        var configProvider = ConfigService.get("skillsPromotion.provider");
        if (configProvider != null && !configProvider.isBlank()) {
            var p = ProviderRegistry.get(configProvider);
            if (p != null) return p;
        }
        var main = Agent.findByName(Agent.MAIN_AGENT_NAME);
        return main != null ? ProviderRegistry.get(main.modelProvider) : null;
    }

    private static String resolveModel() {
        var configModel = ConfigService.get("skillsPromotion.model");
        if (configModel != null && !configModel.isBlank()) return configModel;
        var main = Agent.findByName(Agent.MAIN_AGENT_NAME);
        return main != null ? main.modelId : null;
    }

    // --- helpers ---

    /** Basenames of non-text files anywhere under the staged skill — the binaries
     *  that {@code commands:} must declare (and that the malware scan will vet). */
    private static Set<String> listStagedBinaries(Path stagedDir) {
        var bins = new TreeSet<String>();
        try (var walk = Files.walk(stagedDir)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> !SkillLoader.isTextFile(p.getFileName().toString()))
                .forEach(p -> bins.add(p.getFileName().toString()));
        } catch (IOException e) {
            EventLogger.warn(CATEGORY, "Conformance: could not list staged binaries: " + e.getMessage());
        }
        return bins;
    }

    static String kebabCase(String s) {
        if (s == null) return null;
        var kebab = s.strip().toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-++", "")    // strip leading hyphens
                .replaceAll("-++$", "");   // strip trailing hyphens (single anchored possessive pattern — no group, no alternation, no backtracking)
        return kebab.isBlank() ? null : kebab;
    }

    static String kebabOrNull(String s) {
        if (s == null) return null;
        var t = s.strip();
        return KEBAB.matcher(t).matches() ? t : null;
    }

    static List<String> normalizeTools(List<String> tools) {
        if (tools == null) return List.of();
        var out = new ArrayList<String>();
        for (var t : tools) {
            if (t == null) continue;
            var n = t.strip();
            if (!n.isBlank() && !out.contains(n)) out.add(n);
        }
        return out;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    private static List<String> strList(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonArray()) return List.of();
        var out = new ArrayList<String>();
        for (var el : o.getAsJsonArray(key)) {
            if (!el.isJsonNull()) out.add(el.getAsString());
        }
        return out;
    }
}
