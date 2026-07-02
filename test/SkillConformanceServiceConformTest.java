import agents.SkillLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.SkillConformanceService;
import services.SkillConformanceService.ConformedSkill;
import services.SkillConformanceService.ProposedSkill;
import services.SkillPromotionService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Coverage for {@link SkillConformanceService#conform}'s offline failure paths
 * and the {@code applyHardGates} edges the base {@code SkillConformanceServiceTest}
 * doesn't reach: missing SKILL.md, LLM-unresolvable (no provider configured, no
 * main agent), null/underivable proposals, and tool-list normalization
 * (strip/dedupe/null-skip) observed through the accepted skill.
 *
 * <p>No network, no LLM: the DB is wiped in setup so {@code resolveProvider}
 * finds neither a {@code skillsPromotion.provider} config nor a main agent and
 * the generation half fails fast. The successful conform round-trip (which
 * requires a chat completion) lives in {@code SkillLlmMockedPipelineTest}.
 */
class SkillConformanceServiceConformTest extends UnitTest {

    private static final String BODY = "# Body\n\nDo the thing.";

    private Path stagedDir;

    @BeforeEach
    void setup() throws Exception {
        Fixtures.deleteDatabase();
        services.ConfigService.clearCache();
        stagedDir = Files.createTempDirectory("skill-conform-test-");
    }

    @AfterEach
    void teardown() throws Exception {
        if (stagedDir != null && Files.exists(stagedDir)) {
            SkillPromotionService.deleteRecursive(stagedDir);
        }
    }

    private static ProposedSkill proposed(String name, String desc, String icon, List<String> tools) {
        return new ProposedSkill(name, desc, icon, tools);
    }

    // ==================== conform — failure paths ====================

    @Test
    void conformFailsWhenSkillMdMissing() {
        var result = SkillConformanceService.conform(stagedDir, "some-skill", "owner/repo");
        assertFalse(result.ok(), "a staged dir without SKILL.md cannot conform");
        assertNull(result.skillName());
        assertTrue(result.message().contains("no SKILL.md"),
                "failure names the missing file: " + result.message());
    }

    @Test
    void conformFailsWhenNoLlmResolvableAndLeavesSkillMdUntouched() throws Exception {
        // Empty DB: no skillsPromotion.provider config and no main agent, so
        // proposeWithLlm resolves no provider and returns null — conform must
        // fail with the LLM-unavailable message and must NOT rewrite SKILL.md.
        var original = "---\nname: Foreign Skill\ndescription: external\n---\n" + BODY + "\n";
        Files.writeString(stagedDir.resolve("SKILL.md"), original);

        var result = SkillConformanceService.conform(stagedDir, "foreign-skill", "owner/repo");

        assertFalse(result.ok(), "conform must fail when no LLM can be resolved");
        assertTrue(result.message().contains("conformance pass failed"),
                "failure explains the LLM was unavailable: " + result.message());
        assertEquals(original, Files.readString(stagedDir.resolve("SKILL.md")),
                "a failed conformance pass must leave the staged SKILL.md untouched");
    }

    // ==================== applyHardGates — reject edges ====================

    @Test
    void applyHardGatesRejectsNullProposal() {
        var gate = SkillConformanceService.applyHardGates(null, "fallback", Set.of(), "o/r", BODY);
        assertFalse(gate.ok());
        assertNull(gate.skill());
        assertTrue(gate.reason().contains("empty conformance proposal"),
                "reject reason: " + gate.reason());
    }

    @Test
    void applyHardGatesRejectsWhenNoKebabNameDerivable() {
        // Proposed name is not kebab-case AND the fallback reduces to nothing
        // after kebab-casing ("###" strips to empty) — no name can be derived.
        var gate = SkillConformanceService.applyHardGates(
                proposed("Not Kebab!!", "d", "🛠️", List.of()),
                "###", Set.of(), "o/r", BODY);
        assertFalse(gate.ok(), "no derivable name must reject");
        assertTrue(gate.reason().contains("kebab-case"),
                "reject reason names the kebab constraint: " + gate.reason());
    }

    // ==================== applyHardGates — name derivation / normalization ====================

    @Test
    void applyHardGatesKebabsFallbackWhenProposedNameIsNull() {
        var gate = SkillConformanceService.applyHardGates(
                proposed(null, "d", "🛠️", List.of()),
                "My Skill  Name", Set.of(), "o/r", BODY);
        assertTrue(gate.ok(), gate.reason());
        assertEquals("my-skill-name", gate.skill().name(),
                "null proposal name falls back to the kebab-cased catalog id");
    }

    @Test
    void applyHardGatesNormalizesToolsStripDedupeAndNullSkip() {
        // "  exec ", "exec" (dup after strip), null, and blank must normalize
        // to a single canonical "exec" entry; the tool exists in the registry
        // so the gate accepts.
        var tools = Arrays.asList("  exec ", "exec", null, "   ");
        var gate = SkillConformanceService.applyHardGates(
                proposed("my-skill", "d", "🛠️", tools),
                "my-skill", Set.of(), "o/r", BODY);
        assertTrue(gate.ok(), gate.reason());
        assertEquals(List.of("exec"), gate.skill().tools(),
                "tools must be stripped, deduped, and null/blank-skipped");
    }

    @Test
    void applyHardGatesTreatsNullToolsListAsEmpty() {
        var gate = SkillConformanceService.applyHardGates(
                proposed("my-skill", "d", "🛠️", null),
                "my-skill", Set.of(), "o/r", BODY);
        assertTrue(gate.ok(), gate.reason());
        assertTrue(gate.skill().tools().isEmpty(), "null tools list means no tools");
    }

    @Test
    void applyHardGatesDefaultsBlankAuthorToImported() {
        var gate = SkillConformanceService.applyHardGates(
                proposed("my-skill", "d", "🛠️", List.of()),
                "my-skill", Set.of(), "   ", BODY);
        assertTrue(gate.ok(), gate.reason());
        assertEquals("imported", gate.skill().author(),
                "blank provenance falls back to 'imported'");
    }

    @Test
    void applyHardGatesPreservesNullBodyAsEmpty() {
        var gate = SkillConformanceService.applyHardGates(
                proposed("my-skill", "d", "🛠️", List.of()),
                "my-skill", Set.of(), "o/r", null);
        assertTrue(gate.ok(), gate.reason());
        assertEquals("", gate.skill().body(), "null body must become empty, not the string 'null'");
    }

    // ==================== ConformedSkill.toSkillMd — null body ====================

    @Test
    void toSkillMdRendersParseableFrontmatterWhenBodyIsNull() {
        var skill = new ConformedSkill("bodyless-skill", "desc", "🛠️",
                List.of(), List.of(), "o/r", null);
        var rendered = skill.toSkillMd();
        assertFalse(rendered.contains("null"), "null body must not leak into the render: " + rendered);

        var info = SkillLoader.parseSkillContent(rendered, null);
        assertNotNull(info, "rendered SKILL.md must still parse without a body");
        assertEquals("bodyless-skill", info.name());
        assertEquals("desc", info.description());
    }
}
