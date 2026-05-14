package channels;

import llm.LlmProvider;
import llm.LlmTypes.ModelInfo;
import llm.ProviderRegistry;
import models.Agent;
import models.Conversation;
import models.TelegramBinding;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import services.ConfigService;
import services.ConversationService;
import services.EventLogger;
import services.Tx;

import java.util.List;
import java.util.Optional;

/**
 * Telegram-specific entry points for the {@code /model} selector
 * (JCLAW-109). Splits the Telegram UI concerns (keyboard layout, inline
 * edits, callback ack) out of {@link slash.Commands} so that the slash
 * layer stays channel-agnostic and testable without a Telegram client.
 *
 * <p>All methods here assume an authenticated, authorized caller — the
 * polling runner / webhook controller has already verified that the
 * conversation and the Telegram user are bound to this agent.
 */
public final class TelegramModelSelector {

    private TelegramModelSelector() {}

    // ── Public entry points ────────────────────────────────────────────

    /**
     * Initial {@code /model} response: providers-list body text plus the
     * 2-per-row provider keyboard. The user picks a provider directly from
     * this first bubble — there's no intermediate "Browse providers"
     * button. Persists an assistant-message row with the body so the
     * scrollback shows the content on reload. Returns true when both
     * the send and the persistence succeeded; false surfaces to the
     * caller so it can fall back to the text-only detail response.
     */
    public static boolean sendSummary(Agent agent, Conversation conversation) {
        if (conversation == null) return false;
        var botToken = botTokenForAgent(agent);
        if (botToken == null) return false;
        var text = summaryText(agent, conversation);
        var keyboard = TelegramModelKeyboard.providersKeyboard(
                conversation.id, currentProviderName(agent, conversation));
        var messageId = TelegramChannel.sendMessageWithKeyboard(
                botToken, conversation.peerId, text, keyboard);
        if (messageId == null) return false;
        persistSummaryAck(conversation.id, text);
        return true;
    }

    // ── Text builders ──────────────────────────────────────────────────

    /**
     * Body rendered above the providers grid. Header lines name the
     * conversation's current model + provider so the user has reference
     * before tapping; the trailing usage hints document the equivalent
     * text-command path for users who prefer typing.
     *
     * <p>HTML parse mode: {@code <b>} for emphasis on the "Current model"
     * line label, {@code <code>} on the model id and the inline command
     * examples (Telegram renders {@code <code>} in monospace, which
     * disambiguates command syntax from prose).
     */
    public static String summaryText(Agent agent, Conversation conversation) {
        var overrideActive = services.ModelOverrideResolver.hasOverride(conversation);
        var resolved = services.ModelOverrideResolver.resolve(conversation, agent);
        var provider = resolved.provider();
        var modelId = resolved.modelId();
        var providerLabel = TelegramModelKeyboard.providerLabel(provider);
        var sb = new StringBuilder();
        sb.append("⚙️ <b>Model Configuration</b>\n\n");
        sb.append("Current model: <code>").append(escape(modelId)).append("</code>\n");
        sb.append("Provider: ").append(escape(providerLabel));
        if (overrideActive) {
            sb.append("  <i>(conversation override)</i>");
        }
        sb.append("\n\n<b>Select a provider:</b>\n\n");
        sb.append("<code>/model &lt;provider/model&gt;</code> to switch\n");
        sb.append("<code>/model status</code> for current model details");
        return sb.toString();
    }

    /**
     * The provider registry name currently driving this conversation —
     * conversation override when set, otherwise the agent default. Used
     * by the providers keyboard to render the {@code ✓} checkmark on the
     * matching row.
     */
    public static String currentProviderName(Agent agent, Conversation conversation) {
        return services.ModelOverrideResolver.provider(conversation, agent);
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ── Binding / routing helpers ──────────────────────────────────────

    /**
     * Resolve the agent's Telegram bot token via {@link TelegramBinding}.
     * Returns null when the agent has no Telegram binding (e.g. a web-only
     * agent whose conversation happens to be on the web channel — in that
     * case {@link slash.Commands#executeModel} wouldn't route here at all,
     * but the guard makes the API robust to unexpected callers).
     */
    public static String botTokenForAgent(Agent agent) {
        if (agent == null) return null;
        return Tx.run(() -> {
            var binding = TelegramBinding.findByAgent(agent);
            return binding != null ? binding.botToken : null;
        });
    }

    private static void persistSummaryAck(long conversationId, String summaryText) {
        Tx.run(() -> {
            var conv = (Conversation) Conversation.findById(conversationId);
            if (conv != null) {
                ConversationService.appendAssistantMessage(conv, summaryText, null);
            }
        });
    }

    // ── Helpers shared with the callback dispatcher ────────────────────

    /**
     * User-visible providers, the single source of truth for index-based
     * keyboard rendering (JCLAW-109). Filters out providers that are
     * explicitly disabled via {@code provider.<name>.enabled=false} — the
     * convention used today by the load-test harness (which flips
     * {@code provider.loadtest-mock.enabled} on for the duration of a run
     * and off afterward) and extensible to any future internal provider
     * that ships disabled by default.
     *
     * <p>Providers without the {@code .enabled} config key are assumed
     * visible — that preserves today's behavior for every operator-added
     * provider (openrouter, ollama-cloud, anthropic, etc.) where the
     * presence of {@code baseUrl} and {@code apiKey} alone means "ready to
     * use."
     *
     * <p>Both {@link TelegramModelKeyboard} and {@link #resolveByIndex}
     * route through this method so the indices encoded in
     * {@code callback_data} always line up with what the user saw on
     * screen.
     */
    public static List<LlmProvider> userVisibleProviders() {
        return ProviderRegistry.listAll().stream()
                .filter(p -> !isProviderDisabled(p.config().name()))
                .toList();
    }

    private static boolean isProviderDisabled(String name) {
        var flag = ConfigService.get("provider." + name + ".enabled");
        return "false".equalsIgnoreCase(flag);
    }

    /**
     * Locate {@link ModelInfo} for a given (providerIdx, modelIdx) tuple
     * from a previously-rendered keyboard. Returns empty when either
     * index is out of range — the registry may have changed since the
     * keyboard was rendered. Callers surface the failure to the user via
     * {@code answerCallbackQuery} with {@code show_alert}.
     */
    public static Optional<ResolvedModel> resolveByIndex(int providerIdx, int modelIdx) {
        var providers = userVisibleProviders();
        if (providerIdx < 0 || providerIdx >= providers.size()) return Optional.empty();
        var provider = providers.get(providerIdx);
        var models = provider.config().models();
        if (modelIdx < 0 || modelIdx >= models.size()) return Optional.empty();
        return Optional.of(new ResolvedModel(provider.config().name(), models.get(modelIdx)));
    }

    /** Tuple of provider name and its model row, returned by {@link #resolveByIndex}. */
    public record ResolvedModel(String providerName, ModelInfo model) {}

    /** Info logger convenience for the callback dispatcher. */
    public static void logEvent(Agent agent, String message) {
        EventLogger.info("SLASH_COMMAND",
                agent != null ? agent.name : null, "telegram", message);
    }
}
