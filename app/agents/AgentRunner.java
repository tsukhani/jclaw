package agents;

import com.google.gson.Gson;
import llm.LlmProvider;
import llm.LlmTypes.*;
import llm.ProviderRegistry;
import models.Agent;
import models.Conversation;
import services.ConversationService;
import services.EventLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Core agent pipeline: receive message → load conversation → assemble prompt →
 * call LLM → handle tool calls (loop) → persist response → return.
 */
public class AgentRunner {

    private static final Gson gson = new Gson();
    private static final int DEFAULT_MAX_TOOL_ROUNDS = 10;

    private static int maxToolRounds() {
        try {
            return Integer.parseInt(services.ConfigService.get("agent.maxToolRounds", "10"));
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_TOOL_ROUNDS;
        }
    }

    public record RunResult(String response, Conversation conversation) {}

    private record PreparedData(
        List<ChatMessage> messages,
        LlmProvider primary,
        LlmProvider secondary,
        List<ToolDef> tools
    ) {}

    /**
     * Run the agent synchronously. Returns the final assistant response.
     * JPA transactions are scoped to short Tx.run() blocks — no JDBC connection
     * is held during LLM HTTP calls or tool execution.
     */
    public static RunResult run(Agent agent, Conversation conversation, String userMessage) {
        // Acquire conversation queue — prevents concurrent message corruption
        var queueMsg = new services.ConversationQueue.QueuedMessage(
                userMessage, conversation.channelType, conversation.peerId, agent);
        if (!services.ConversationQueue.tryAcquire(conversation.id, queueMsg)) {
            return new RunResult("Your message has been queued and will be processed shortly.", conversation);
        }

        final Long conversationId = conversation.id;

        try {
            // Short setup transaction: persist user message, assemble prompt, resolve provider
            var prepared = services.Tx.run(() -> {
                ConversationService.appendUserMessage(conversation, userMessage);

                var assembled = SystemPromptAssembler.assemble(agent, userMessage);
                var messages = buildMessages(assembled.systemPrompt(), conversation);

                var agentProvider = ProviderRegistry.get(agent.modelProvider);
                var primary = agentProvider != null ? agentProvider : ProviderRegistry.getPrimary();
                if (primary == null) {
                    var error = "No LLM provider configured. Add provider config via Settings.";
                    EventLogger.error("llm", agent.name, null, error);
                    ConversationService.appendAssistantMessage(conversation, error, null);
                    return null;
                }
                var secondary = ProviderRegistry.getSecondary();

                var trimmed = trimToContextWindow(messages, agent, primary);
                var tools = ToolRegistry.getToolDefsForAgent(agent);

                EventLogger.info("llm", agent.name, conversation.channelType,
                        "Calling %s / %s".formatted(primary.config().name(), agent.modelId));

                return new PreparedData(trimmed, primary, secondary, tools);
            });

            if (prepared == null) {
                var error = "No LLM provider configured. Add provider config via Settings.";
                return new RunResult(error,
                        services.Tx.run(() -> ConversationService.findById(conversationId)));
            }

            // LLM call loop — no transaction open, JDBC connection back in pool
            var response = callWithToolLoop(agent, conversationId,
                    prepared.messages(), prepared.tools(), prepared.primary(), prepared.secondary());

            // Short persistence transaction: final assistant message
            services.Tx.run(() -> {
                var conv = ConversationService.findById(conversationId);
                ConversationService.appendAssistantMessage(conv, response, null);
            });

            EventLogger.info("llm", agent.name, conversation.channelType,
                    "Response generated (%d chars)".formatted(response.length()));

            var updatedConversation = services.Tx.run(() -> ConversationService.findById(conversationId));
            return new RunResult(response, updatedConversation);
        } finally {
            // Always release the queue to prevent deadlock
            services.ConversationQueue.drain(conversationId);
        }
    }

    /**
     * Run the agent with streaming. Resolves the conversation inside the virtual thread
     * so it commits in its own transaction before inserting messages.
     */
    public static void runStreaming(Agent agent, Long conversationId, String channelType, String peerId,
                                    String userMessage,
                                    AtomicBoolean isCancelled,
                                    Consumer<Conversation> onInit,
                                    Consumer<String> onToken,
                                    Consumer<String> onReasoning,
                                    Consumer<String> onStatus,
                                    Consumer<String> onComplete,
                                    Consumer<Exception> onError) {
        Thread.ofVirtual().start(() -> {
            final Long[] conversationIdRef = {null};
            try {
                // 1. Resolve conversation and persist user message in one transaction
                Conversation conversation = services.Tx.run(() -> {
                    Conversation convo;
                    if (conversationId != null) {
                        convo = ConversationService.findById(conversationId);
                    } else if ("web".equals(channelType)) {
                        convo = ConversationService.create(agent, channelType, peerId);
                    } else {
                        convo = ConversationService.findOrCreate(agent, channelType, peerId);
                    }
                    if (convo != null) {
                        ConversationService.appendUserMessage(convo, userMessage);
                    }
                    return convo;
                });

                if (conversation == null) {
                    onError.accept(new RuntimeException("Conversation not found"));
                    return;
                }
                conversationIdRef[0] = conversation.id;

                // Acquire conversation queue — prevents concurrent message corruption
                var queueMsg = new services.ConversationQueue.QueuedMessage(
                        userMessage, channelType, peerId, agent);
                if (!services.ConversationQueue.tryAcquire(conversation.id, queueMsg)) {
                    onInit.accept(conversation);
                    onComplete.accept("Your message has been queued and will be processed shortly.");
                    return;
                }

                // Notify caller (e.g., send SSE init event with conversation ID)
                onInit.accept(conversation);

                if (isCancelled.get()) {
                    EventLogger.info("llm", agent.name, channelType, "Stream cancelled by client disconnect");
                    return;
                }

                EventLogger.info("llm", agent.name, channelType,
                        "Streaming: assembling prompt for conversation %d".formatted(conversation.id));

                var assembled = services.Tx.run(() ->
                        SystemPromptAssembler.assemble(agent, userMessage));

                if (isCancelled.get()) {
                    EventLogger.info("llm", agent.name, channelType, "Stream cancelled by client disconnect");
                    return;
                }

                var messages = services.Tx.run(() ->
                        buildMessages(assembled.systemPrompt(), ConversationService.findById(conversation.id)));

                var agentProvider = services.Tx.run(() -> ProviderRegistry.get(agent.modelProvider));
                var primary = agentProvider != null ? agentProvider : services.Tx.run(ProviderRegistry::getPrimary);
                if (primary == null) {
                    EventLogger.error("llm", agent.name, channelType, "No LLM provider configured");
                    onError.accept(new RuntimeException("No LLM provider configured"));
                    return;
                }

                if (isCancelled.get()) {
                    EventLogger.info("llm", agent.name, channelType, "Stream cancelled by client disconnect");
                    return;
                }

                messages = trimToContextWindow(messages, agent, primary);

                var tools = services.Tx.run(() -> ToolRegistry.getToolDefsForAgent(agent));
                EventLogger.info("llm", agent.name, channelType,
                        "Streaming: calling %s / %s (%d messages, %d tools, %d skills%s)"
                                .formatted(primary.config().name(), agent.modelId,
                                        messages.size(), tools.size(), assembled.skills().size(),
                                        agent.thinkingMode != null ? ", thinking=" + agent.thinkingMode : ""));
                var modelInfo = primary.config().models().stream()
                        .filter(m -> m.id().equals(agent.modelId))
                        .findFirst().orElse(null);
                var maxTokens = modelInfo != null && modelInfo.maxTokens() > 0 ? modelInfo.maxTokens() : null;

                // Stream with tool call handling (HTTP, no JPA)
                var accumulator = primary.chatStreamAccumulate(
                        agent.modelId, messages, tools, onToken, onReasoning, maxTokens, agent.thinkingMode);

                while (!accumulator.awaitCompletion(5000)) {
                    if (isCancelled.get()) {
                        EventLogger.info("llm", agent.name, channelType, "Stream cancelled by client disconnect");
                        return;
                    }
                }

                // Retry once on transient 5xx errors
                if (accumulator.error != null && accumulator.error.getMessage() != null
                        && accumulator.error.getMessage().contains("HTTP 5")) {
                    EventLogger.warn("llm", agent.name, null, "Retrying streaming after transient error");
                    accumulator = primary.chatStreamAccumulate(
                            agent.modelId, messages, tools, onToken, onReasoning, maxTokens, agent.thinkingMode);
                    while (!accumulator.awaitCompletion(5000)) {
                        if (isCancelled.get()) {
                            EventLogger.info("llm", agent.name, channelType, "Stream cancelled by client disconnect");
                            return;
                        }
                    }
                }

                if (isCancelled.get()) {
                    EventLogger.info("llm", agent.name, channelType, "Stream cancelled by client disconnect");
                    return;
                }

                if (accumulator.error != null) {
                    onError.accept(accumulator.error);
                    return;
                }

                var content = accumulator.content;

                // Check for truncated response (max tokens hit mid-tool-call)
                if ("length".equals(accumulator.finishReason) && !accumulator.toolCalls.isEmpty()) {
                    EventLogger.warn("llm", agent.name, channelType,
                            "Response truncated (finish_reason=length) with pending tool calls — skipping execution of incomplete tool arguments");
                    var truncMsg = content.isEmpty()
                            ? "I tried to use a tool but my response was too long and got cut off. Let me try a more concise approach."
                            : content;
                    onToken.accept(truncMsg.equals(content) ? "" : "\n\n*[Response was truncated — retrying with a simpler approach]*");
                    // Persist and complete with the truncation notice
                    var finalContent = truncMsg;
                    services.Tx.run(() -> {
                        var conv = ConversationService.findById(conversation.id);
                        ConversationService.appendAssistantMessage(conv, finalContent, null);
                    });
                    onComplete.accept(finalContent);
                    return;
                }

                // Handle tool calls if present
                if (!accumulator.toolCalls.isEmpty()) {
                    content = handleToolCallsStreaming(agent, conversation.id, messages, tools,
                            accumulator.toolCalls, content, primary, onToken, onReasoning, onStatus, maxTokens, 0, isCancelled);
                }

                if (isCancelled.get()) {
                    EventLogger.info("llm", agent.name, channelType, "Stream cancelled by client disconnect");
                    return;
                }

                // Persist and complete
                var finalContent = content;
                services.Tx.run(() -> {
                    var conv = ConversationService.findById(conversation.id);
                    ConversationService.appendAssistantMessage(conv, finalContent, null);
                });

                // Log and emit usage including reasoning tokens if available
                if (accumulator.usage != null) {
                    var u = accumulator.usage;
                    var usageSummary = " [%d prompt, %d completion, %d total tokens%s]".formatted(
                            u.promptTokens(), u.completionTokens(), u.totalTokens(),
                            u.reasoningTokens() > 0 ? ", %d reasoning".formatted(u.reasoningTokens()) : "");
                    EventLogger.info("llm", agent.name, channelType,
                            "Streaming complete (%d chars)%s".formatted(content.length(), usageSummary));
                    // Emit usage as a JSON status so the frontend can display token counts
                    var usageJson = "{\"usage\":{\"prompt\":%d,\"completion\":%d,\"total\":%d,\"reasoning\":%d}}"
                            .formatted(u.promptTokens(), u.completionTokens(), u.totalTokens(), u.reasoningTokens());
                    onStatus.accept(usageJson);
                } else {
                    EventLogger.info("llm", agent.name, channelType,
                            "Streaming complete (%d chars)".formatted(content.length()));
                }
                onComplete.accept(content);

            } catch (Exception e) {
                EventLogger.error("llm", agent.name, channelType,
                        "Streaming error: %s".formatted(e.getMessage()));
                onError.accept(e);
            } finally {
                // Always release the queue to prevent deadlock
                if (conversationIdRef[0] != null) {
                    services.ConversationQueue.drain(conversationIdRef[0]);
                }
            }
        });
    }

    // --- Internal ---

    private static String callWithToolLoop(Agent agent, Long conversationId,
                                            List<ChatMessage> messages, List<ToolDef> tools,
                                            LlmProvider primary, LlmProvider secondary) {
        var currentMessages = new ArrayList<>(messages);
        var modelInfo = primary.config().models().stream()
                .filter(m -> m.id().equals(agent.modelId))
                .findFirst().orElse(null);
        var maxTokens = modelInfo != null && modelInfo.maxTokens() > 0 ? modelInfo.maxTokens() : null;

        for (int round = 0; round < maxToolRounds(); round++) {
            ChatResponse response;
            try {
                response = (secondary != null)
                        ? LlmProvider.chatWithFailover(primary, secondary, agent.modelId, currentMessages, tools, maxTokens, agent.thinkingMode)
                        : primary.chat(agent.modelId, currentMessages, tools, maxTokens, agent.thinkingMode);
            } catch (Exception e) {
                EventLogger.error("llm", agent.name, null, "LLM call failed: %s".formatted(e.getMessage()));
                return "I'm sorry, I encountered an error communicating with the AI provider. Please try again.";
            }

            if (response.choices() == null || response.choices().isEmpty()) {
                return "No response received from the AI provider.";
            }

            var choice = response.choices().getFirst();
            var assistantMsg = choice.message();

            // No tool calls — return the content
            if (assistantMsg.toolCalls() == null || assistantMsg.toolCalls().isEmpty()) {
                return assistantMsg.content() != null ? (String) assistantMsg.content() : "";
            }

            // Check for truncated response (max tokens hit mid-tool-call)
            if ("length".equals(choice.finishReason())) {
                EventLogger.warn("llm", agent.name, null,
                        "Response truncated (finish_reason=length) with pending tool calls — skipping execution of incomplete tool arguments");
                return assistantMsg.content() != null ? (String) assistantMsg.content()
                        : "I tried to use a tool but my response was too long and got cut off. Let me try a more concise approach.";
            }

            // Tool calls — execute and continue
            currentMessages.add(assistantMsg);
            EventLogger.info("tool", agent.name, null,
                    "Round %d: executing %d tool call(s)".formatted(round + 1, assistantMsg.toolCalls().size()));

            for (var toolCall : assistantMsg.toolCalls()) {
                EventLogger.info("tool", agent.name, null,
                        "Executing tool '%s' (id: %s, args: %s)"
                                .formatted(toolCall.function().name(), toolCall.id(),
                                        toolCall.function().arguments().length() > 200
                                                ? toolCall.function().arguments().substring(0, 200) + "..."
                                                : toolCall.function().arguments()));
                var result = ToolRegistry.execute(toolCall.function().name(),
                        toolCall.function().arguments(), agent);
                var resultPreview = result.length() > 200 ? result.substring(0, 200) + "... (%d chars)".formatted(result.length()) : result;
                EventLogger.info("tool", agent.name, null,
                        "Tool '%s' returned: %s".formatted(toolCall.function().name(), resultPreview));
                currentMessages.add(ChatMessage.toolResult(toolCall.id(), result));

                // Persist tool interaction in a short transaction
                services.Tx.run(() -> {
                    var conv = ConversationService.findById(conversationId);
                    ConversationService.appendAssistantMessage(conv,
                            null, gson.toJson(toolCall));
                    ConversationService.appendToolResult(conv,
                            toolCall.id(), result);
                });
            }
        }

        return "I reached the maximum number of tool execution rounds. Please try a simpler request.";
    }

    private static String handleToolCallsStreaming(Agent agent, Long conversationId,
                                                    List<ChatMessage> messages, List<ToolDef> tools,
                                                    List<ToolCall> toolCalls, String priorContent,
                                                    LlmProvider provider,
                                                    Consumer<String> onToken, Consumer<String> onReasoning,
                                                    Consumer<String> onStatus,
                                                    Integer maxTokens,
                                                    int round, AtomicBoolean isCancelled) {
        if (round >= maxToolRounds()) {
            return "I reached the maximum number of tool execution rounds. Please try a simpler request.";
        }
        if (isCancelled.get()) {
            return priorContent;
        }
        EventLogger.info("tool", agent.name, null,
                "Streaming round %d: executing %d tool call(s)".formatted(round + 1, toolCalls.size()));

        var currentMessages = new ArrayList<>(messages);
        currentMessages.add(ChatMessage.assistant(priorContent, toolCalls));

        for (var toolCall : toolCalls) {
            if (isCancelled.get()) return priorContent;
            onStatus.accept("Using tool: " + toolCall.function().name());
            EventLogger.info("tool", agent.name, null,
                    "Executing tool '%s' (id: %s, args: %s)"
                            .formatted(toolCall.function().name(), toolCall.id(),
                                    toolCall.function().arguments().length() > 200
                                            ? toolCall.function().arguments().substring(0, 200) + "..."
                                            : toolCall.function().arguments()));
            var result = ToolRegistry.execute(toolCall.function().name(),
                    toolCall.function().arguments(), agent);
            var resultPreview = result.length() > 200 ? result.substring(0, 200) + "... (%d chars)".formatted(result.length()) : result;
            EventLogger.info("tool", agent.name, null,
                    "Tool '%s' returned: %s".formatted(toolCall.function().name(), resultPreview));
            currentMessages.add(ChatMessage.toolResult(toolCall.id(), result));

            services.Tx.run(() -> {
                var conv = ConversationService.findById(conversationId);
                ConversationService.appendAssistantMessage(conv, null, gson.toJson(toolCall));
                ConversationService.appendToolResult(conv, toolCall.id(), result);
            });
        }

        if (isCancelled.get()) return priorContent;

        onStatus.accept("Processing results (round %d)...".formatted(round + 1));
        EventLogger.info("llm", agent.name, null,
                "Streaming round %d: continuing LLM call after tool results".formatted(round + 1));

        // Continue with streaming after tool results
        var accumulator = provider.chatStreamAccumulate(
                agent.modelId, currentMessages, tools, onToken, onReasoning, maxTokens, agent.thinkingMode);

        try {
            // Poll with timeout so we can detect client disconnect
            while (!accumulator.awaitCompletion(5000)) {
                if (isCancelled.get()) {
                    EventLogger.info("llm", agent.name, null,
                            "Aborting streaming tool round — client disconnected");
                    return priorContent;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return priorContent;
        }

        if (isCancelled.get()) return priorContent;

        // Recursively handle if more tool calls
        if (!accumulator.toolCalls.isEmpty()) {
            return handleToolCallsStreaming(agent, conversationId, currentMessages, tools,
                    accumulator.toolCalls, accumulator.content, provider, onToken, onReasoning, onStatus, maxTokens, round + 1, isCancelled);
        }

        // If LLM returned empty content after tool calls, emit "Done." so the user isn't left
        // with a blank response. Some models return empty content when tool results are self-explanatory.
        if (accumulator.content == null || accumulator.content.isBlank()) {
            var fallback = "Done.";
            onToken.accept(fallback);
            return fallback;
        }

        return accumulator.content;
    }

    private static List<ChatMessage> buildMessages(String systemPrompt, Conversation conversation) {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system(systemPrompt));

        var history = ConversationService.loadRecentMessages(conversation);
        for (var msg : history) {
            messages.add(switch (msg.role) {
                case "user" -> ChatMessage.user(msg.content);
                case "assistant" -> {
                    if (msg.toolCalls != null && !msg.toolCalls.isBlank()) {
                        var toolCalls = parseToolCalls(msg.toolCalls);
                        yield ChatMessage.assistant(msg.content, toolCalls);
                    }
                    yield ChatMessage.assistant(msg.content != null ? msg.content : "");
                }
                case "tool" -> ChatMessage.toolResult(msg.toolResults, msg.content);
                default -> ChatMessage.user(msg.content);
            });
        }

        return messages;
    }

    private static List<ChatMessage> trimToContextWindow(List<ChatMessage> messages, Agent agent, LlmProvider provider) {
        // Find the model's context window
        var modelInfo = provider.config().models().stream()
                .filter(m -> m.id().equals(agent.modelId))
                .findFirst().orElse(null);
        if (modelInfo == null || modelInfo.contextWindow() <= 0) return messages;

        int maxTokens = modelInfo.contextWindow();
        int estimatedTokens = estimateTokens(messages);

        if (estimatedTokens <= maxTokens) return messages;

        // Trim oldest non-system messages until we fit
        var trimmed = new ArrayList<>(messages);
        int removed = 0;
        while (estimateTokens(trimmed) > maxTokens && trimmed.size() > 2) {
            // Remove the second message (first after system prompt) — oldest history
            trimmed.remove(1);
            removed++;
        }
        if (removed > 0) {
            EventLogger.warn("llm", agent.name, null,
                    "Trimmed %d messages to fit context window (%d tokens max, estimated %d)"
                            .formatted(removed, maxTokens, estimatedTokens));
        }
        return trimmed;
    }

    private static int estimateTokens(List<ChatMessage> messages) {
        int chars = 0;
        for (var msg : messages) {
            if (msg.content() instanceof String s) chars += s.length();
        }
        return chars / 4; // rough approximation: ~4 chars per token
    }

    private static List<ToolCall> parseToolCalls(String json) {
        try {
            var tc = gson.fromJson(json, ToolCall.class);
            return tc != null ? List.of(tc) : List.of();
        } catch (Exception _) {
            return List.of();
        }
    }
}
