import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.decision.LayaDecision;
import services.decision.OllamaDecision;

import java.util.Set;
import java.util.TreeSet;

/**
 * Pins which methods dial through the provider-guarded tier (JCLAW-1229, JCLAW-1448). The guarded and plain
 * clients differ only in what they refuse to connect to, and a canned transport answers for both, so a caller
 * switched to the plain tier would otherwise fail no test.
 */
class GuardedTierCallersTest extends UnitTest {

    private static final String FACTORIES = "utils.HttpFactories";
    private static final Set<String> GUARDED = Set.of("llmStreamingGuarded", "llmSingleShotGuarded", "generalGuarded");
    private static final Set<String> PLAIN = Set.of("llmStreaming", "llmSingleShot", "general");

    /** Each dials an address an operator or an agent chose: a provider, an MCP server, a decision server. */
    private static final Set<String> GUARDED_CALLERS = Set.of(
            "llm.OkHttpLlmHttpDriver.send",
            "llm.OkHttpLlmHttpDriver.streamSse",
            "llm.OkHttpLlmHttpDriver.streamNdjson",
            "mcp.transport.McpStreamableHttpTransport.send",
            "mcp.transport.McpStreamableHttpTransport.listenClient",
            "services.ModelDiscoveryService.listAllModelIds",
            "services.discovery.LmStudioDiscoveryStrategy.fetchNative",
            "services.discovery.OllamaDiscoveryStrategy.fetchTags",
            "services.discovery.OllamaDiscoveryStrategy.fetchShow",
            "services.discovery.OpenAiCompatDiscoveryStrategy.discover",
            "services.discovery.LeaderboardRanker.fetchLeaderboard",
            "services.decision.JevApi.send",
            "services.decision.OllamaDecision.status",
            "services.decision.OllamaDecision.get",
            "services.decision.OllamaDecision.pin",
            "services.decision.OllamaDecision.unload",
            "services.decision.LayaDecision.status");

    /**
     * TypeSafe's JEV is a fixed vendor endpoint, so {@code Target.guarded} picks the tier per target; that pick is
     * pinned by {@code ProviderRedirectScreenTest.aDecisionTargetDialsTheTierItNames}.
     */
    private static final Set<String> ALSO_DIALS_THE_PLAIN_TIER = Set.of("services.decision.JevApi.send");

    @Test
    void theMethodsThatDialAChosenAddressUseTheGuardedTier() {
        var guarded = callersOf(GUARDED);
        assertEquals(new TreeSet<>(GUARDED_CALLERS), guarded,
                "a method left this list by switching to the plain tier, or a new caller of the guarded tier "
                        + "needs pinning here");

        var mixed = callersOf(PLAIN);
        mixed.retainAll(guarded);
        mixed.removeAll(ALSO_DIALS_THE_PLAIN_TIER);
        assertEquals(Set.of(), mixed, "these methods dial a chosen address and also the unscreened tier");
    }

    /** {@code JevApi.send} dials whichever tier its target names, so the decision targets are pinned too. */
    @Test
    void theDecisionTargetsAskForTheGuardedTier() {
        assertTrue(OllamaDecision.target("http://127.0.0.1:11434", "tev1").guarded(), "Ollama");
        assertTrue(LayaDecision.target("http://127.0.0.1:8000", null).guarded(), "Laya");
    }

    private static TreeSet<String> callersOf(Set<String> accessors) {
        var callers = new TreeSet<String>();
        for (var javaClass : ArchitectureTest.APP_CLASSES) {
            for (var access : javaClass.getAccessesFromSelf()) {
                if (FACTORIES.equals(access.getTargetOwner().getName())
                        && accessors.contains(access.getTarget().getName())) {
                    callers.add(access.getOrigin().getOwner().getName() + "." + access.getOrigin().getName());
                }
            }
        }
        return callers;
    }
}
