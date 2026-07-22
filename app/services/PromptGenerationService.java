package services;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import llm.LlmTypes.ChatMessage;
import llm.ProviderRegistry;
import models.Agent;
import models.Prompt;

import java.util.List;

/**
 * Generates a reusable prompt (title, category, content, tags) from a short
 * operator description via one LLM call (JCLAW-813). Powers the New Prompt
 * "describe it" flow: the result pre-populates the create form for the operator
 * to review/edit before saving — this service never persists anything.
 *
 * <p>Reuses the main agent's provider/model (no separate config), mirroring
 * {@link SkillConformanceService}. Returns {@code null} when no LLM is
 * configured or the call fails, so the controller can surface a clean error.
 *
 * <p>The LLM call sits behind a {@link Generator} seam (mirrors
 * {@code MemoryAutoCapture.Extractor}) so the message-build + JSON-parse core is
 * unit-testable without a live model.
 */
public final class PromptGenerationService {

    private PromptGenerationService() {}

    /** Testable seam over {@code LlmProvider.chat}. */
    @FunctionalInterface
    public interface Generator {
        // Production lambda calls LlmProvider.chat, which surfaces provider-specific checked exceptions.
        @SuppressWarnings("java:S112")
        String generate(List<ChatMessage> messages) throws Exception;
    }

    /** Generated fields that pre-fill the create form (nothing is saved here). */
    public record Generated(String title, String category, String content, String tags) {}

    private static final String INSTRUCTIONS = """
            You write reusable AI prompts for a prompt library. Given a short description of what
            the operator wants, produce ONE polished, reusable prompt they can run later (use
            <angle-bracket placeholders> for the parts they will fill in each time).

            Respond with ONLY a JSON object — no prose, no code fences:
            {
              "title": "a short, specific title (about 60 chars max)",
              "category": "one of: CODING, WRITING, ANALYSIS, CREATIVE, BUSINESS, CUSTOM",
              "content": "the full reusable prompt text",
              "tags": "3-6 short, comma-separated tags"
            }
            Pick the single best-fit category. Keep the prompt practical and self-contained.""";

    private static final int MAX_TOKENS = 900;

    /**
     * Production entry: resolve the main agent's provider/model, call the LLM,
     * and parse. Returns {@code null} when no provider/model is configured or the
     * call fails — the controller maps that to an error response.
     */
    public static Generated generate(String description) {
        var main = Agent.findByName(Agent.MAIN_AGENT_NAME);
        var provider = main != null ? ProviderRegistry.get(main.modelProvider) : null;
        var model = main != null ? main.modelId : null;
        if (provider == null || model == null || model.isBlank()) return null;
        try {
            final var p = provider;
            final var m = model;
            return generate(description, msgs ->
                    SessionCompactor.firstChoiceText(p.chat(m, msgs, List.of(), MAX_TOKENS, null, null)));
        } catch (Exception e) {
            EventLogger.warn("prompts", "Prompt generation failed: " + e.getMessage());
            return null;
        }
    }

    /** Testable core: build the messages, invoke the seam, parse the JSON.
     *  Public so unit tests can inject a canned {@link Generator}. */
    public static Generated generate(String description, Generator gen) throws Exception {
        var msgs = List.of(
                ChatMessage.system(INSTRUCTIONS),
                ChatMessage.user("Description:\n" + (description == null ? "" : description.strip())));
        return parse(gen.generate(msgs));
    }

    /**
     * Parse the model's JSON into {@link Generated}, coercing the category to a
     * valid {@link Prompt.Category} (unknown/blank → CUSTOM) and falling back to
     * sensible defaults so the operator always gets an editable form rather than
     * an empty one. Public for unit testing.
     */
    public static Generated parse(String raw) {
        var text = raw == null ? "" : raw.strip();
        String title = "";
        String content = "";
        String tags = "";
        var category = Prompt.Category.CUSTOM;
        try {
            var el = JsonParser.parseString(stripFences(text));
            if (el.isJsonObject()) {
                JsonObject o = el.getAsJsonObject();
                title = str(o, "title");
                content = str(o, "content");
                tags = str(o, "tags");
                var cat = Prompt.Category.fromValue(str(o, "category"));
                if (cat != null) category = cat;
            }
        } catch (RuntimeException e) {
            // Non-JSON / malformed — leave content blank so the fallback below
            // hands the operator the raw text to edit instead of nothing.
            content = "";
        }
        if (content.isBlank()) content = text;
        if (title.isBlank()) title = "Untitled prompt";
        return new Generated(title, category.name(), content, tags);
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString().strip() : "";
    }

    /** Strip a leading ```/```json fence and trailing ``` if the model wrapped its JSON. */
    private static String stripFences(String s) {
        var t = s.strip();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl >= 0) t = t.substring(nl + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        }
        return t.strip();
    }
}
