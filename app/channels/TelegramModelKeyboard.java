package channels;

import llm.LlmProvider;
import llm.LlmTypes.ModelInfo;
import llm.ProviderRegistry;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.util.ArrayList;
import java.util.List;

/**
 * Inline-keyboard builders for the {@code /model} Telegram selector
 * (JCLAW-109). Thin, pure-logic layer over the telegrambots SDK's
 * {@code InlineKeyboardMarkup} record — unit-testable without any
 * Telegram I/O.
 *
 * <p>Layouts:
 * <ul>
 *   <li>Summary keyboard: two rows — "Browse providers" and "Show details".</li>
 *   <li>Providers list: one provider per row.</li>
 *   <li>Models list: one model per row, paginated at {@link #MODELS_PER_PAGE}.
 *       Navigation row (Back / Prev / Next) appears as needed.</li>
 *   <li>Post-switch confirmation: no keyboard (null).</li>
 * </ul>
 */
public final class TelegramModelKeyboard {

    private TelegramModelKeyboard() {}

    /** Page size when listing models. Keeps the keyboard bubble compact on mobile. */
    public static final int MODELS_PER_PAGE = 10;

    /**
     * The single-button keyboard attached to the initial {@code /model}
     * response — invites the user to browse the provider list. Full model
     * details are reachable via the {@code /model status} text command,
     * so no redundant "Show details" button is needed here.
     */
    public static InlineKeyboardMarkup summaryKeyboard(long conversationId) {
        var browse = InlineKeyboardButton.builder()
                .text("Browse providers")
                .callbackData(TelegramModelCallback.encodeBrowse(conversationId))
                .build();
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(browse))
                .build();
    }

    /**
     * Providers list: one button per configured provider, ordered by
     * {@link ProviderRegistry#listAll()} (deterministic within a JVM —
     * backed by the registry's LinkedHashMap). Providers with zero
     * models are still shown so the user can see them; tapping will
     * open an empty models page (with a "no models" hint).
     */
    public static InlineKeyboardMarkup providersKeyboard(long conversationId) {
        var builder = InlineKeyboardMarkup.builder();
        // JCLAW-109: route through the user-visible filter so reserved
        // providers (loadtest-mock) don't show up in the keyboard.
        // The callback dispatcher's resolveByIndex uses the same list,
        // so indices encoded here match what gets looked up on tap.
        var providers = TelegramModelSelector.userVisibleProviders();
        for (int i = 0; i < providers.size(); i++) {
            var p = providers.get(i);
            var button = InlineKeyboardButton.builder()
                    .text(p.config().name())
                    .callbackData(TelegramModelCallback.encodeProviderPage(conversationId, i, 0))
                    .build();
            builder.keyboardRow(new InlineKeyboardRow(button));
        }
        // Give the user a way back to the summary without starting over.
        var back = InlineKeyboardButton.builder()
                .text("⬅ Back")
                .callbackData(TelegramModelCallback.encodeBack(conversationId))
                .build();
        builder.keyboardRow(new InlineKeyboardRow(back));
        return builder.build();
    }

    /**
     * Models list for a specific provider index, paginated. Each model
     * button's callback_data selects THAT model when tapped. Navigation
     * row below includes "Back to providers" and Prev/Next when the
     * provider has more than one page.
     */
    public static InlineKeyboardMarkup modelsKeyboard(long conversationId, int providerIdx, int page) {
        var providers = TelegramModelSelector.userVisibleProviders();
        if (providerIdx < 0 || providerIdx >= providers.size()) {
            // Defensive: stale index. Return an empty keyboard so the
            // caller can render a "provider is gone" message instead.
            return InlineKeyboardMarkup.builder().build();
        }
        LlmProvider provider = providers.get(providerIdx);
        List<ModelInfo> models = provider.config().models();
        int totalPages = Math.max(1, (models.size() + MODELS_PER_PAGE - 1) / MODELS_PER_PAGE);
        int clampedPage = Math.max(0, Math.min(page, totalPages - 1));
        int start = clampedPage * MODELS_PER_PAGE;
        int end = Math.min(start + MODELS_PER_PAGE, models.size());

        var builder = InlineKeyboardMarkup.builder();
        for (int i = start; i < end; i++) {
            var m = models.get(i);
            var button = InlineKeyboardButton.builder()
                    .text(labelFor(m))
                    .callbackData(TelegramModelCallback.encodeSelect(conversationId, providerIdx, i))
                    .build();
            builder.keyboardRow(new InlineKeyboardRow(button));
        }

        // Navigation: back to providers always; prev/next when multi-page.
        var navButtons = new ArrayList<InlineKeyboardButton>();
        navButtons.add(InlineKeyboardButton.builder()
                .text("⬅ Providers")
                .callbackData(TelegramModelCallback.encodeBrowse(conversationId))
                .build());
        if (totalPages > 1) {
            if (clampedPage > 0) {
                navButtons.add(InlineKeyboardButton.builder()
                        .text("◀ Prev")
                        .callbackData(TelegramModelCallback.encodeProviderPage(
                                conversationId, providerIdx, clampedPage - 1))
                        .build());
            }
            if (clampedPage < totalPages - 1) {
                navButtons.add(InlineKeyboardButton.builder()
                        .text("Next ▶")
                        .callbackData(TelegramModelCallback.encodeProviderPage(
                                conversationId, providerIdx, clampedPage + 1))
                        .build());
            }
        }
        builder.keyboardRow(new InlineKeyboardRow(navButtons));
        return builder.build();
    }

    /**
     * Button label for a model — prefer {@link ModelInfo#name()} when set,
     * fall back to {@link ModelInfo#id()}. Telegram truncates long labels
     * visibly, so keep it compact.
     */
    private static String labelFor(ModelInfo m) {
        if (m.name() != null && !m.name().isBlank()) return m.name();
        return m.id();
    }
}
