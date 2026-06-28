import agents.SkillLoader;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.SkillConformanceService;
import services.SkillConformanceService.ConformedSkill;
import services.SkillConformanceService.ProposedSkill;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Unit coverage for the deterministic VALIDATION half of skill conformance
 * ({@link SkillConformanceService#applyHardGates}) and the SKILL.md rendering.
 * No LLM, no network: the gate is a pure function over an already-proposed
 * normalization, so it tests in isolation.
 */
class SkillConformanceServiceTest extends UnitTest {

    private static final String BODY = "# Body\n\nDo the thing.";

    private static ProposedSkill proposed(String name, String desc, String icon, List<String> tools) {
        return new ProposedSkill(name, desc, icon, tools);
    }

    @Test
    void acceptsCleanProposalAndPreservesBody() {
        var gate = SkillConformanceService.applyHardGates(
                proposed("web-scraper", "Scrape a web page", "🕷️", List.of()),
                "web-scraper-fallback", Set.of(), "vercel-labs/agent-skills", BODY);

        assertTrue(gate.ok(), gate.reason());
        var skill = gate.skill();
        assertEquals("web-scraper", skill.name());
        assertEquals("🕷️", skill.icon());
        assertEquals("vercel-labs/agent-skills", skill.author());
        assertTrue(skill.tools().isEmpty());
        assertTrue(skill.commands().isEmpty());
        assertEquals(BODY, skill.body(), "the original body is preserved verbatim");
    }

    @Test
    void rejectsToolNotInThisBuild() {
        var gate = SkillConformanceService.applyHardGates(
                proposed("x", "d", "🛠️", List.of("totally_unknown_tool_xyz")),
                "x", Set.of(), "owner/repo", BODY);

        assertFalse(gate.ok());
        assertTrue(gate.reason().contains("totally_unknown_tool_xyz"),
                "rejection should name the offending tool: " + gate.reason());
    }

    @Test
    void fallsBackToKebabbedCatalogIdWhenLlmNameIsNotKebab() {
        var gate = SkillConformanceService.applyHardGates(
                proposed("Remotion Best Practices!", "d", "🎬", List.of()),
                "remotion-best-practices", Set.of(), "remotion-dev/skills", BODY);

        assertTrue(gate.ok(), gate.reason());
        assertEquals("remotion-best-practices", gate.skill().name());
    }

    @Test
    void derivesCommandsFromStagedBinariesNotFromLlm() {
        // The LLM proposal carries NO commands field at all; commands must come
        // from the binaries actually present on disk.
        var bins = new TreeSet<>(Set.of("wacli", "helper"));
        var gate = SkillConformanceService.applyHardGates(
                proposed("wa-notifier", "Send messages", "💬", List.of()),
                "wa-notifier", bins, "owner/repo", BODY);

        assertTrue(gate.ok(), gate.reason());
        assertEquals(List.of("helper", "wacli"), gate.skill().commands());
    }

    @Test
    void defaultsMissingIconAndDescription() {
        var gate = SkillConformanceService.applyHardGates(
                proposed("my-skill", "  ", "", List.of()),
                "my-skill", Set.of(), "owner/repo", BODY);

        assertTrue(gate.ok(), gate.reason());
        assertEquals("🛠️", gate.skill().icon());
        assertEquals("my-skill", gate.skill().description(), "blank description falls back to the name");
    }

    @Test
    void renderedSkillMdParsesBackThroughSkillLoader() {
        var skill = new ConformedSkill("whatsapp-notifier", "Send WhatsApp messages", "💬",
                List.of("exec", "filesystem"), List.of("wacli"),
                "vercel-labs/agent-skills", "# WhatsApp Notifier\n\nSend the message.");

        var info = SkillLoader.parseSkillContent(skill.toSkillMd(), null);

        assertNotNull(info, "rendered SKILL.md must parse");
        assertEquals("whatsapp-notifier", info.name());
        assertEquals("Send WhatsApp messages", info.description());
        assertEquals("vercel-labs/agent-skills", info.author());
        assertEquals("💬", info.icon());
        assertEquals(List.of("exec", "filesystem"), info.tools());
        assertEquals(List.of("wacli"), info.commands());
    }
}
