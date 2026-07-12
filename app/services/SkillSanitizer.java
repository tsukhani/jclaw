package services;

import com.google.gson.JsonParser;
import llm.LlmProvider;
import llm.LlmTypes.ChatMessage;
import llm.ProviderRegistry;
import models.Agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The LLM sanitizer pass of the skill-promotion pipeline (JCLAW-727), extracted from
 * {@link SkillPromotionService}. Given the text payload of a skill folder, it batches the files by
 * size and asks an LLM to redact any embedded secrets / PII, replacing each with a descriptive
 * placeholder. Every failure mode degrades to the original content — a missing provider, an absent
 * main agent, a non-JSON model response, or a per-batch transport error all fall back so a sanitizer
 * outage never blocks (or silently corrupts) a promotion.
 *
 * <p>Provider/model resolution prefers the dedicated {@code skillsPromotion.*} config, then falls
 * back to the main agent's provider/model — the same resolution the rest of the pipeline uses.
 */
public final class SkillSanitizer {

    private SkillSanitizer() {}

    /** Event-log category for sanitization entries; matches the parent pipeline's category. */
    private static final String EVENT_CATEGORY_SKILLS = "skills";

    private static final int DEFAULT_BATCH_KB = 100;
    private static final int DEFAULT_TIMEOUT_SECONDS = 300;

    private static final String SANITIZE_SYSTEM_PROMPT = """
            You are a security reviewer. You will receive text files from an AI agent's skill folder.
            A skill folder has this structure:
            - SKILL.md — the main skill instructions, BODY ONLY (the YAML frontmatter has already been extracted and will be reinjected verbatim after your review, so do NOT emit or fabricate frontmatter for SKILL.md)
            - credentials.json — already pre-stripped, no action needed
            - tools/ — optional tool scripts that may contain hardcoded secrets or personal data

            Your job is to identify and redact any secrets, API keys, tokens, passwords, bearer tokens, \
            webhook URLs, personal information (names, emails, phone numbers, addresses, usernames), \
            or other sensitive data that may have been embedded in the files.

            Replace each redacted value with a descriptive placeholder like [API_KEY], [PASSWORD], [EMAIL], \
            [PHONE_NUMBER], [PERSONAL_NAME], [TOKEN], [WEBHOOK_URL], [USERNAME], etc.

            Return ONLY a valid JSON object mapping each filename to its sanitized content. \
            Do not include any other text, markdown formatting, or code fences. \
            Example: {"SKILL.md": "sanitized content here"}

            If no sensitive data is found in a file, return its content unchanged.
            """;

    public static LinkedHashMap<String, String> sanitize(LinkedHashMap<String, String> fileContents) {
        // Resolve provider and model — prefer dedicated sanitization config, fall back to main agent
        var configProvider = ConfigService.get("skillsPromotion.provider");
        var configModel = ConfigService.get("skillsPromotion.model");
        int batchKb = ConfigService.getInt("skillsPromotion.batchSizeKb", DEFAULT_BATCH_KB);
        int timeoutSeconds = ConfigService.getInt("skillsPromotion.timeoutSeconds", DEFAULT_TIMEOUT_SECONDS);

        LlmProvider provider = null;
        String modelId = null;

        if (configProvider != null && !configProvider.isBlank()) {
            provider = ProviderRegistry.get(configProvider);
            modelId = configModel;
        }

        // Fall back to main agent's provider/model if not explicitly configured
        if (provider == null || modelId == null || modelId.isBlank()) {
            Agent mainAgent = Agent.findByName(Agent.MAIN_AGENT_NAME);
            if (mainAgent == null) {
                EventLogger.warn(EVENT_CATEGORY_SKILLS, "Sanitization skipped: main agent not found");
                return fileContents;
            }
            if (provider == null) provider = ProviderRegistry.get(mainAgent.modelProvider);
            if (modelId == null || modelId.isBlank()) modelId = mainAgent.modelId;
        }

        if (provider == null) {
            EventLogger.warn(EVENT_CATEGORY_SKILLS, "Sanitization skipped: no provider configured");
            return fileContents;
        }

        EventLogger.info(EVENT_CATEGORY_SKILLS, "Starting LLM sanitization of %d file(s) via %s / %s (batch=%dKB, timeout=%ds)"
                .formatted(fileContents.size(), provider.config().name(), modelId, batchKb, timeoutSeconds));

        for (var entry : fileContents.entrySet()) {
            EventLogger.info(EVENT_CATEGORY_SKILLS, "Sanitizing file: %s (%d chars)"
                    .formatted(entry.getKey(), entry.getValue().length()));
        }

        // Batch files by KB to avoid overwhelming the model with a single massive payload
        var batches = buildBatchesBySize(fileContents, batchKb * 1024);
        var result = new LinkedHashMap<String, String>();

        for (int i = 0; i < batches.size(); i++) {
            EventLogger.info(EVENT_CATEGORY_SKILLS, "Sending batch %d/%d (%d files)"
                    .formatted(i + 1, batches.size(), batches.get(i).size()));

            var batchResult = sanitizeBatch(provider, modelId, batches.get(i), fileContents, timeoutSeconds);
            result.putAll(batchResult);
        }

        EventLogger.info(EVENT_CATEGORY_SKILLS, "Sanitization complete");
        return result;
    }

    private static List<List<Map.Entry<String, String>>> buildBatchesBySize(
            LinkedHashMap<String, String> fileContents, int maxBatchBytes) {

        var batches = new ArrayList<List<Map.Entry<String, String>>>();
        var currentBatch = new ArrayList<Map.Entry<String, String>>();
        int currentSize = 0;

        for (var entry : fileContents.entrySet()) {
            int entrySize = entry.getValue().length() * 2; // rough byte estimate (UTF-16 chars → bytes)
            // If this single file exceeds the limit, send it alone
            if (!currentBatch.isEmpty() && currentSize + entrySize > maxBatchBytes) {
                batches.add(currentBatch);
                currentBatch = new ArrayList<>();
                currentSize = 0;
            }
            currentBatch.add(entry);
            currentSize += entrySize;
        }
        if (!currentBatch.isEmpty()) batches.add(currentBatch);
        return batches;
    }

    private static LinkedHashMap<String, String> sanitizeBatch(
            LlmProvider provider, String modelId,
            List<Map.Entry<String, String>> batch,
            LinkedHashMap<String, String> originals, int timeoutSeconds) {

        var sb = new StringBuilder();
        for (var entry : batch) {
            sb.append("=== FILE: %s ===\n".formatted(entry.getKey()));
            sb.append(entry.getValue());
            sb.append("\n\n");
        }

        var messages = List.of(
                ChatMessage.system(SANITIZE_SYSTEM_PROMPT),
                ChatMessage.user(sb.toString())
        );

        try {
            // No reasoning (null thinkingMode) — sanitization is classification, not reasoning.
            // Skill promotion is a programmatic operation with no chat-channel
            // context; dispatcher_wait records under "unknown".
            var response = provider.chat(modelId, messages, null, null, null, timeoutSeconds, null);
            var text = response.choices().getFirst().message().content().toString().strip();

            EventLogger.info(EVENT_CATEGORY_SKILLS,
                    "LLM sanitization batch response (%d chars)".formatted(text.length()));

            if (text.startsWith("```")) {
                text = text.replaceFirst("^```(?:json)?\\s*\\n?", "").replaceFirst("\\n?```$", "").strip();
            }

            var json = JsonParser.parseString(text).getAsJsonObject();
            var result = new LinkedHashMap<String, String>();
            for (var entry : batch) {
                if (json.has(entry.getKey())) {
                    var sanitized = json.get(entry.getKey()).getAsString();
                    var changed = !sanitized.equals(entry.getValue());
                    result.put(entry.getKey(), sanitized);
                    EventLogger.info(EVENT_CATEGORY_SKILLS, "  %s: %s"
                            .formatted(entry.getKey(), changed ? "REDACTED (content changed)" : "clean (no changes)"));
                } else {
                    result.put(entry.getKey(), originals.get(entry.getKey()));
                    EventLogger.info(EVENT_CATEGORY_SKILLS, "  %s: not in LLM response, kept original"
                            .formatted(entry.getKey()));
                }
            }
            return result;
        } catch (Exception e) {
            EventLogger.warn(EVENT_CATEGORY_SKILLS, "LLM sanitization batch failed, using originals: " + e.getMessage());
            var result = new LinkedHashMap<String, String>();
            for (var entry : batch) {
                result.put(entry.getKey(), originals.get(entry.getKey()));
            }
            return result;
        }
    }
}
