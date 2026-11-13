import llm.LlmTypes.ToolDef;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.EventLogger;
import services.TaskToolPolicy;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JCLAW-1068: per-task toolset restriction.
 *
 * <p>Pins both halves of the gate — that {@code Task.enabledToolNames} parses to an
 * allow-list, and that the allow-list narrows the {@code ToolDef} list. The second is
 * what makes the restriction real: {@code ToolCallLoopRunner.offeredToolNames} builds
 * the JCLAW-883 dispatch guard from this same list, so a def withheld here is also
 * refused by {@code ToolRegistry.executeRich}.
 */
class TaskToolPolicyTest extends UnitTest {

    private static ToolDef def(String name) {
        return ToolDef.of(name, name + " description", Map.of("type", "object"));
    }

    private static List<ToolDef> defs(String... names) {
        return java.util.Arrays.stream(names).map(TaskToolPolicyTest::def).toList();
    }

    private static List<String> namesOf(List<ToolDef> defs) {
        return defs.stream().map(d -> d.function().name()).toList();
    }

    // ==================== parse ====================

    @Test
    void absentOrBlankAllowlistIsUnrestricted() {
        assertNull(TaskToolPolicy.parse(null));
        assertNull(TaskToolPolicy.parse(""));
        assertNull(TaskToolPolicy.parse("   "));
    }

    @Test
    void emptyArrayIsUnrestrictedNotZeroTools() {
        // A cleared multi-select must not strand the task with no tools.
        assertNull(TaskToolPolicy.parse("[]"));
    }

    @Test
    void parsesJsonArrayOfNames() {
        assertEquals(Set.of("exec", "filesystem"),
                TaskToolPolicy.parse("[\"exec\",\"filesystem\"]"));
    }

    @Test
    void trimsAndDropsBlankEntries() {
        assertEquals(Set.of("exec"), TaskToolPolicy.parse("[\"  exec  \",\"\",\"   \"]"));
    }

    @Test
    void acceptsTheCommaSeparatedFormAlreadyInTheColumn() {
        // TaskTool stored whatever the model wrote and nothing validated it, so live rows
        // hold this shape. Rejecting it would run a task the operator believed was fenced.
        assertEquals(Set.of("datetime", "web_search", "web_fetch", "mcp_google-workspace-mcp"),
                TaskToolPolicy.parse("datetime,web_search,web_fetch,mcp_google-workspace-mcp"));
    }

    @Test
    void acceptsABareSingleToolName() {
        assertEquals(Set.of("mcp_google-workspace-mcp"),
                TaskToolPolicy.parse("mcp_google-workspace-mcp"));
    }

    @Test
    void truncatedJsonIsMalformedNotASingleToolName() {
        // The trap the delimited form opens: read as a name list, {@code ["exec",} would
        // fence the task down to nothing instead of failing open.
        assertNull(TaskToolPolicy.parse("[\"exec\","));
        assertNull(TaskToolPolicy.parse("{\"exec\""));
    }

    @Test
    void malformedAllowlistFailsOpen() {
        // Fail open rather than strand the task; the parse path logs a warning.
        assertNull(TaskToolPolicy.parse("[\"exec\","));
        assertNull(TaskToolPolicy.parse("not json at all"));
        assertNull(TaskToolPolicy.parse("{\"exec\":true}"));
        assertNull(TaskToolPolicy.parse("[\"exec\", 7]"));
    }

    // ==================== restrict ====================

    @Test
    void nullAllowlistLeavesDefsUntouched() {
        var all = defs("exec", "filesystem", "web_search");
        assertSame(all, TaskToolPolicy.restrict(all, null, "t", "a"));
    }

    @Test
    void withholdsToolsOutsideTheAllowlist() {
        var kept = TaskToolPolicy.restrict(defs("exec", "filesystem", "web_search"),
                Set.of("filesystem"), "payslip", "main");
        assertEquals(List.of("filesystem"), namesOf(kept));
    }

    @Test
    void keepsEveryToolWhenAllowlistCoversThemAll() {
        var kept = TaskToolPolicy.restrict(defs("exec", "filesystem"),
                Set.of("exec", "filesystem"), "t", "a");
        assertEquals(List.of("exec", "filesystem"), namesOf(kept));
    }

    @Test
    void allowlistNameMatchingNoToolIsIgnoredNotFatal() {
        // Operator typo: the run proceeds with whatever did match.
        var kept = TaskToolPolicy.restrict(defs("exec", "filesystem"),
                Set.of("filesystem", "no_such_tool"), "t", "a");
        assertEquals(List.of("filesystem"), namesOf(kept));
    }

    @Test
    void allowlistMatchingNothingYieldsNoTools() {
        var kept = TaskToolPolicy.restrict(defs("exec", "filesystem"),
                Set.of("totally_unknown"), "t", "a");
        assertTrue(kept.isEmpty());
    }

    @Test
    void mcpServersAreAddressedByTheirServerLevelHandle() {
        // getToolDefsForAgent offers one entry per server (JCLAW-281), so that is the
        // name an allow-list has to carry.
        var kept = TaskToolPolicy.restrict(defs("exec", "mcp_google-workspace-mcp"),
                Set.of("mcp_google-workspace-mcp"), "payslip", "main");
        assertEquals(List.of("mcp_google-workspace-mcp"), namesOf(kept));
    }

    // ==================== JF-373: single actions of an MCP server ====================

    @Test
    void parsesEntriesNamingSingleActionsInBothShapes() {
        assertEquals(Set.of("mcp_gw/search_gmail_messages", "web_search"),
                TaskToolPolicy.parse("[\"mcp_gw/search_gmail_messages\",\"web_search\"]"));
        assertEquals(Set.of("mcp_gw/search_gmail_messages", "web_search"),
                TaskToolPolicy.parse("mcp_gw/search_gmail_messages,web_search"));
    }

    @Test
    void anActionEntryOffersItsServersHandle() {
        var kept = TaskToolPolicy.restrict(defs("exec", "mcp_gw", "web_search"),
                Set.of("mcp_gw/search_gmail_messages", "web_search"), "labeler", "main");
        assertEquals(List.of("mcp_gw", "web_search"), namesOf(kept));
    }

    @Test
    void theAuditLineCountsAnActionEntryAsMatchingItsServer() {
        var events = EventLogger.captureForTest(() -> TaskToolPolicy.restrict(defs("exec", "mcp_gw"),
                Set.of("mcp_gw/search_gmail_messages", "mcp_absent/search"), "labeler", "main"));
        assertEquals(List.of("INFO TOOL_BLOCKED_BY_TASK_POLICY Task 'labeler' (agent 'main') restricted to 1 of 2 "
                        + "tools; withheld: exec; allow-list names matching no available tool: mcp_absent/search"),
                events.stream().map(e -> e.level() + " " + e.category() + " " + e.message()).toList());
    }

    @Test
    void anActionEntryOffersOnlyTheServerItNames() {
        // mcp_git is a prefix of mcp_github: only an entry continuing with the separator names an action.
        var allowed = Set.of("mcp_git/clone");
        assertTrue(TaskToolPolicy.allows(allowed, "mcp_git"));
        assertFalse(TaskToolPolicy.allows(allowed, "mcp_github"));
    }

    @Test
    void anEntryWithNoActionAfterTheSeparatorOffersNothing() {
        assertFalse(TaskToolPolicy.allows(Set.of("mcp_gw/"), "mcp_gw"));
    }

    @Test
    void aSeparatorAfterANativeToolsNameDoesNotOfferIt() {
        // Only an MCP server has actions; a stray entry must not grant a native tool whole.
        assertFalse(TaskToolPolicy.allows(Set.of("web_search/anything"), "web_search"));
    }

    @Test
    void mcpActionsIsNullWhenTheRunMayUseEveryAction() {
        assertNull(TaskToolPolicy.mcpActions(null, "mcp_gw"));
        assertNull(TaskToolPolicy.mcpActions(Set.of("mcp_gw", "mcp_gw/search"), "mcp_gw"));
    }

    @Test
    void mcpActionsListsTheNamedActionsOfThatServerAlone() {
        assertEquals(Set.of("search", "label"),
                TaskToolPolicy.mcpActions(Set.of("mcp_gw/search", "mcp_gw/label", "mcp_gwx/send", "exec"), "mcp_gw"));
    }

    @Test
    void mcpActionsIsEmptyWhenTheListNamesNoneOfTheServersActions() {
        assertEquals(Set.of(), TaskToolPolicy.mcpActions(Set.of("exec", "mcp_other/search"), "mcp_gw"));
    }

    // ==================== JF-378: a named action its server no longer offers ====================

    private static java.util.function.Function<String, Set<String>> servers(Map<String, Set<String>> lists) {
        return lists::get;
    }

    private static List<String> unofferedWarnings(Set<String> allowed, Map<String, Set<String>> lists) {
        return EventLogger.captureForTest(() ->
                        TaskToolPolicy.warnOfUnofferedActions(allowed, "labeler", "main", servers(lists)))
                .stream().map(e -> e.level() + " " + e.category() + " " + e.message()).toList();
    }

    @Test
    void oneWarningNamesEachMissingActionByServer() {
        var warnings = unofferedWarnings(
                Set.of("mcp_gw/search", "mcp_gw/old_search", "mcp_gw/older_label", "mcp_sx/web", "mcp_sx/gone", "exec"),
                Map.of("mcp_gw", Set.of("search", "label"), "mcp_sx", Set.of("web")));
        assertEquals(List.of("WARN TOOL_BLOCKED_BY_TASK_POLICY Task 'labeler' (agent 'main') names MCP actions their "
                        + "servers do not offer, so its runs cannot use them: mcp_gw: old_search, older_label; mcp_sx: gone"),
                warnings);
    }

    @Test
    void everyNamedActionOfferedWarnsNothing() {
        assertEquals(List.of(), unofferedWarnings(Set.of("mcp_gw/search", "exec"), Map.of("mcp_gw", Set.of("search"))));
    }

    @Test
    void aServerWhoseActionsAreUnknownWarnsNothing() {
        // Not connected, or not listed yet: no evidence the action is gone.
        assertEquals(List.of(), unofferedWarnings(Set.of("mcp_gw/search"), Map.of()));
    }

    @Test
    void noListWarnsNothing() {
        assertEquals(List.of(), EventLogger.captureForTest(() ->
                TaskToolPolicy.warnOfUnofferedActions(null, "labeler", "main", servers(Map.of("mcp_gw", Set.of()))))
                .stream().map(EventLogger.Captured::message).toList());
    }

    @Test
    void aServerNamedWithTheSeparatorIsJudgedByItsOwnList() {
        // Server "a/b": the handle mcp_a answers nothing, so the entry is judged against mcp_a/b's list.
        var lists = Map.of("mcp_a/b", Set.of("x"));
        assertEquals(List.of(), unofferedWarnings(Set.of("mcp_a/b/x"), lists));
        assertEquals(List.of("WARN TOOL_BLOCKED_BY_TASK_POLICY Task 'labeler' (agent 'main') names MCP actions their "
                        + "servers do not offer, so its runs cannot use them: mcp_a/b: y"),
                unofferedWarnings(Set.of("mcp_a/b/y"), lists));
    }

    // ==================== JF-386: a tool for subagents only ====================

    @Test
    void parsesEntriesMarkedForSubagentsInBothShapes() {
        assertEquals(Set.of("subagents:exec", "web_search"), TaskToolPolicy.parse("[\"subagents:exec\",\"web_search\"]"));
        assertEquals(Set.of("subagents:exec", "web_search"), TaskToolPolicy.parse("subagents:exec,web_search"));
    }

    @Test
    void theFiresOwnRunDropsEveryMarkedEntry() {
        assertEquals(Set.of("web_search", "mcp_gw/search"),
                TaskToolPolicy.forOwnRun(Set.of("web_search", "subagents:exec", "mcp_gw/search", "subagents:mcp_gw/send")));
    }

    @Test
    void aListMarkingEveryEntryLeavesTheFiresOwnRunNoToolsNotEveryTool() {
        assertEquals(Set.of(), TaskToolPolicy.forOwnRun(Set.of("subagents:exec")));
    }

    @Test
    void aSubagentGetsTheWholeListWithTheMarksRemoved() {
        assertEquals(Set.of("web_search", "exec", "mcp_gw/search", "mcp_gw/send"),
                TaskToolPolicy.forSubagents(Set.of("web_search", "subagents:exec", "mcp_gw/search", "subagents:mcp_gw/send")));
    }

    @Test
    void noListStaysNoListForBothViews() {
        assertNull(TaskToolPolicy.forOwnRun(null));
        assertNull(TaskToolPolicy.forSubagents(null));
    }

    @Test
    void aMarkWithNoToolAfterItGivesTheSubagentNothing() {
        assertEquals(Set.of("web_search"), TaskToolPolicy.forSubagents(Set.of("web_search", "subagents:")));
    }

    @Test
    void aSubagentsInheritedListOffersItsMarkedTools() {
        var kept = TaskToolPolicy.restrictSubagent(defs("exec", "web_search", "filesystem"),
                "[\"web_search\",\"subagents:exec\"]", "helper");
        assertEquals(List.of("exec", "web_search"), namesOf(kept));
    }

    @Test
    void aMarkCannotOfferAToolTheSubagentsOwnAgentLacks() {
        // The list only narrows the subagent's own definitions: a tool its agent is not given stays absent.
        var kept = TaskToolPolicy.restrictSubagent(defs("web_search", "filesystem"),
                "[\"web_search\",\"subagents:exec\"]", "helper");
        assertEquals(List.of("web_search"), namesOf(kept));
    }

    @Test
    void theFiresAuditLineNeitherWithholdsNorMissesAMarkedTool() {
        var events = EventLogger.captureForTest(() -> TaskToolPolicy.restrict(defs("exec", "web_search"),
                TaskToolPolicy.forOwnRun(Set.of("web_search", "subagents:exec")), "stamper", "main"));
        assertEquals(List.of("INFO TOOL_BLOCKED_BY_TASK_POLICY Task 'stamper' (agent 'main') restricted to 1 of 2 tools; "
                        + "withheld: exec"),
                events.stream().map(e -> e.level() + " " + e.category() + " " + e.message()).toList());
    }

    @Test
    void aSubagentsInheritedActionEntryOffersTheHandle() {
        var kept = TaskToolPolicy.restrictSubagent(defs("exec", "mcp_gw"), "[\"mcp_gw/search\"]", "helper");
        assertEquals(List.of("mcp_gw"), namesOf(kept));
    }
}
