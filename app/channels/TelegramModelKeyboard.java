package channels;

import llm.LlmProvider;
import llm.LlmTypes.ModelInfo;
import llm.ProviderRegistry;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Inline-keyboard builders for the {@code /model} Telegram selector
 * (JCLAW-109). Thin, pure-logic layer over the telegrambots SDK's
 * {@code InlineKeyboardMarkup} record — unit-testable without any
 * Telegram I/O.
 *
 * <p>Layouts:
 * <ul>
 *   <li>Providers list: 2-per-row grid, each button labelled
 *       {@code "{label} ({modelCount})"} with a leading {@code ✓} on the
 *       conversation's current provider. Bottom row is {@code × Cancel}.</li>
 *   <li>Models list: 2-per-row grid, paginated at {@link #MODELS_PER_PAGE}.
 *       Pagination row appears only on multi-page providers and adapts to
 *       first/middle/last position. Final row pairs {@code ◀ Back} (to the
 *       providers list) with {@code × Cancel}.</li>
 *   <li>Post-switch confirmation: no keyboard (null).</li>
 * </ul>
 */
public final class TelegramModelKeyboard {

    private TelegramModelKeyboard() {}

    /** Page size when listing models. 2-col grid × 4 rows = 8 buttons fits the
     *  mobile bubble without forcing the user to scroll past the pagination row. */
    public static final int MODELS_PER_PAGE = 8;

    /** Display labels for the canonical provider IDs jclaw ships with. Mirrors
     *  the frontend's PROVIDER_LABELS in {@code frontend/pages/settings.vue}. Any
     *  provider not in this map falls back to its raw registry name — operators
     *  who add a custom provider see exactly the name they configured. */
    private static final Map<String, String> PROVIDER_LABELS = Map.of(
            "ollama-cloud", "Ollama Cloud",
            "ollama-local", "Ollama Local",
            "openrouter", "OpenRouter",
            "openai", "OpenAI",
            "together", "TogetherAI",
            "lm-studio", "LM Studio",
            "groq", "Groq",
            "anthropic", "Anthropic"
    );

    /** Public so {@link TelegramModelSelector} can reuse the same labelling
     *  in body text (Provider line, switch confirmations, etc.). */
    public static String providerLabel(String registryName) {
        if (registryName == null) return "";
        var label = PROVIDER_LABELS.get(registryName);
        return label != null ? label : registryName;
    }

    /**
     * Providers list: 2-per-row grid keyed off {@link TelegramModelSelector#userVisibleProviders}.
     * The button matching {@code currentProviderName} gets a leading {@code ✓}
     * so the user can see at a glance where their conversation is pointing.
     * Each label includes the configured model count; zero-model providers are
     * still shown so the user can drill in and discover the empty state.
     *
     * <p>{@code × Cancel} sits in its own full-width final row — large
     * tap-target away from the provider buttons reduces accidental dismiss.
     */
    public static InlineKeyboardMarkup providersKeyboard(long conversationId, String currentProviderName) {
        var providers = TelegramModelSelector.userVisibleProviders();
        var builder = InlineKeyboardMarkup.builder();
        var row = new ArrayList<InlineKeyboardButton>();
        for (int i = 0; i < providers.size(); i++) {
            var p = providers.get(i);
            var name = p.config().name();
            var label = providerLabel(name);
            var count = p.config().models().size();
            var prefix = name.equals(currentProviderName) ? "✓ " : "";
            var text = prefix + label + " (" + count + ")";
            var button = InlineKeyboardButton.builder()
                    .text(text)
                    .callbackData(TelegramModelCallback.encodeProviderPage(conversationId, i, 0))
                    .build();
            row.add(button);
            if (row.size() == 2) {
                builder.keyboardRow(new InlineKeyboardRow(row));
                row = new ArrayList<>();
            }
        }
        if (!row.isEmpty()) {
            // Odd-count last row: still a single button; Telegram pads it.
            builder.keyboardRow(new InlineKeyboardRow(row));
        }
        var cancel = InlineKeyboardButton.builder()
                .text("× Cancel")
                .callbackData(TelegramModelCallback.encodeCancel(conversationId))
                .build();
        builder.keyboardRow(new InlineKeyboardRow(cancel));
        return builder.build();
    }

    /**
     * Backwards-compatible variant for callers that don't supply the current
     * provider — no checkmark is rendered. Stale BROWSE callbacks from old
     * chat-history buttons land here when the dispatcher can't easily resolve
     * the live conversation state.
     */
    public static InlineKeyboardMarkup providersKeyboard(long conversationId) {
        return providersKeyboard(conversationId, null);
    }

    /**
     * Models list for a specific provider index, paginated. Models render in a
     * 2-per-row grid; pagination row beneath shows {@code ◀ Prev} / page
     * indicator / {@code Next ▶}, with Prev hidden on the first page and Next
     * hidden on the last. The page indicator is itself a no-op button (taps
     * re-render the same page) — Telegram requires every inline button to
     * carry a callback_data, so a self-referential page-page payload is the
     * cleanest "looks like a label" trick.
     *
     * <p>Final row pairs {@code ◀ Back} (to the providers list) with
     * {@code × Cancel} — both navigation actions on the same row keeps them
     * one thumb-stretch apart and clearly distinct from model selection.
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
        int clampedPage = Math.clamp(page, 0, totalPages - 1);
        int start = clampedPage * MODELS_PER_PAGE;
        int end = Math.min(start + MODELS_PER_PAGE, models.size());

        var builder = InlineKeyboardMarkup.builder();
        var row = new ArrayList<InlineKeyboardButton>();
        for (int i = start; i < end; i++) {
            var m = models.get(i);
            var button = InlineKeyboardButton.builder()
                    .text(labelFor(m))
                    .callbackData(TelegramModelCallback.encodeSelect(conversationId, providerIdx, i))
                    .build();
            row.add(button);
            if (row.size() == 2) {
                builder.keyboardRow(new InlineKeyboardRow(row));
                row = new ArrayList<>();
            }
        }
        if (!row.isEmpty()) {
            builder.keyboardRow(new InlineKeyboardRow(row));
        }

        if (totalPages > 1) {
            var pagination = new ArrayList<InlineKeyboardButton>();
            if (clampedPage > 0) {
                pagination.add(InlineKeyboardButton.builder()
                        .text("◀ Prev")
                        .callbackData(TelegramModelCallback.encodeProviderPage(
                                conversationId, providerIdx, clampedPage - 1))
                        .build());
            }
            pagination.add(InlineKeyboardButton.builder()
                    .text((clampedPage + 1) + "/" + totalPages)
                    // Self-referential: tapping the indicator re-renders the
                    // same page. Avoids needing a separate NOOP callback kind.
                    .callbackData(TelegramModelCallback.encodeProviderPage(
                            conversationId, providerIdx, clampedPage))
                    .build());
            if (clampedPage < totalPages - 1) {
                pagination.add(InlineKeyboardButton.builder()
                        .text("Next ▶")
                        .callbackData(TelegramModelCallback.encodeProviderPage(
                                conversationId, providerIdx, clampedPage + 1))
                        .build());
            }
            builder.keyboardRow(new InlineKeyboardRow(pagination));
        }

        var nav = new ArrayList<InlineKeyboardButton>();
        nav.add(InlineKeyboardButton.builder()
                .text("◀ Back")
                .callbackData(TelegramModelCallback.encodeBrowse(conversationId))
                .build());
        nav.add(InlineKeyboardButton.builder()
                .text("× Cancel")
                .callbackData(TelegramModelCallback.encodeCancel(conversationId))
                .build());
        builder.keyboardRow(new InlineKeyboardRow(nav));
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

    @SuppressWarnings("unused")
    private static final Class<?> _UNUSED_REGISTRY_HOLDER = ProviderRegistry.class;
}
