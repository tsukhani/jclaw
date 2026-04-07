package agents;

import com.google.gson.Gson;
import llm.LlmTypes.*;
import llm.OpenAiCompatibleClient;
import llm.ProviderRegistry;
import models.Agent;
import models.Conversation;
import services.ConversationService;
import services.EventLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Core agent pipeline: receive message → load conversation → assemble prompt →
 * call LLM → handle tool calls (loop) → persist response → return.
 */
public class AgentRunner {

    private static final Gson gson = new Gson();
    private static final int MAX_TOOL_ROUNDS = 10;

    public record RunResult(String response, Conversation conversation) {}

    /**
     * Run the agent synchronously. Returns the final assistant response.
     */
    public static RunResult run(Agent agent, Conversation conversation, String userMessage) {
        // Acquire conversation queue — prevents concurrent message corruption
        var queueMsg = new services.ConversationQueue.QueuedMessage(
                userMessage, conversation.channelType, conversation.peerId, agent);
        if (!services.ConversationQueue.tryAcquire(conversation.id, queueMsg)) {
            return new RunResult("Your message has been queued and will be processed shortly.", conversation);
        }

        try {
            // 1. Persist user message
            ConversationService.appendUserMessage(conversation, userMessage);

            // 2. Assemble system prompt
            var assembled = SystemPromptAssembler.assemble(agent, userMessage);

            // 3. Build messages list
            var messages = buildMessages(assembled.systemPrompt(), conversation);

            // 4. Get provider config for this agent
            var agentProvider = ProviderRegistry.get(agent.modelProvider);
            var primary = agentProvider != null ? agentProvider : ProviderRegistry.getPrimary();
            var secondary = ProviderRegistry.listAll().stream()
                    .filter(p -> !p.name().equals(primary != null ? primary.name() : ""))
                    .findFirst().orElse(null);
            if (primary == null) {
                var error = "No LLM provider configured. Add provider config via Settings.";
                EventLogger.error("llm", agent.name, null, error);
                ConversationService.appendAssistantMessage(conversation, error, null);
                return new RunResult(error, conversation);
            }

            messages = trimToContextWindow(messages, agent, primary);

            EventLogger.info("llm", agent.name, conversation.channelType,
                    "Calling %s / %s".formatted(primary.name(), agent.modelId));

            // 5. Get registered tools
            var tools = ToolRegistry.getToolDefsForAgent(agent);

            // 6. LLM call loop (handles tool calls)
            var response = callWithToolLoop(agent, conversation, messages, tools, primary, secondary);

            // 7. Persist final assistant response
            ConversationService.appendAssistantMessage(conversation, response, null);

            EventLogger.info("llm", agent.name, conversation.channelType,
                    "Response generated (%d chars)".formatted(response.length()));

            return new RunResult(response, conversation);
        } finally {
            // Always release the queue to prevent deadlock
            services.ConversationQueue.drain(conversation.id);
        }
    }

    /**
     * Run the agent with streaming. Resolves the conversation inside the virtual thread
     * so it commits in its own transaction before inserting messages.
     */
    public static void runStreaming(Agent agent, Long conversationId, String channelType, String peerId,
                                    String userMessage,
                                    Consumer<Conversation> onInit,
                                    Consumer<String> onToken,
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
                    // Message was queued — notify caller and return
                    onInit.accept(conversation);
                    onComplete.accept("Your message has been queued and will be processed shortly.");
                    return;
                }

                // Notify caller (e.g., send SSE init event with conversation ID)
                onInit.accept(conversation);

                EventLogger.info("llm", agent.name, channelType,
                        "Streaming: assembling prompt for conversation %d".formatted(conversation.id));

                // 3. Assemble system prompt (reads JPA + filesystem)
                var assembled = services.Tx.run(() ->
                        SystemPromptAssembler.assemble(agent, userMessage));

                // 4. Build messages from conversation history
                var messages = services.Tx.run(() ->
                        buildMessages(assembled.systemPrompt(), conversation));

                // 5. Get provider for this agent (may trigger JPA via ProviderRegistry refresh)
                var agentProvider = services.Tx.run(() -> ProviderRegistry.get(agent.modelProvider));
                var primary = agentProvider != null ? agentProvider : services.Tx.run(ProviderRegistry::getPrimary);
                if (primary == null) {
                    EventLogger.error("llm", agent.name, channelType, "No LLM provider configured");
                    onError.accept(new RuntimeException("No LLM provider configured"));
                    return;
                }

                messages = trimToContextWindow(messages, agent, primary);

                var tools = services.Tx.run(() -> ToolRegistry.getToolDefsForAgent(agent));
                EventLogger.info("llm", agent.name, channelType,
                        "Streaming: calling %s / %s (%d messages, %d tools, %d skills)"
                                .formatted(primary.name(), agent.modelId,
                                        messages.size(), tools.size(), assembled.skills().size()));
                var modelInfo = primary.models().stream()
                        .filter(m -> m.id().equals(agent.modelId))
                        .findFirst().orElse(null);
                var maxTokens = modelInfo != null && modelInfo.maxTokens() > 0 ? modelInfo.maxTokens() : null;

                // 6. Stream with tool call handling (HTTP, no JPA)
                var accumulator = OpenAiCompatibleClient.chatStreamAccumulate(
                        primary, agent.modelId, messages, tools, onToken, maxTokens);

                accumulator.awaitCompletion();

                // Retry once on transient 5xx errors
                if (accumulator.error != null && accumulator.error.getMessage() != null
                        && accumulator.error.getMessage().contains("HTTP 5")) {
                    EventLogger.warn("llm", agent.name, null, "Retrying streaming after transient error");
                    accumulator = OpenAiCompatibleClient.chatStreamAccumulate(
                            primary, agent.modelId, messages, tools, onToken, maxTokens);
                    accumulator.awaitCompletion();
                }

                if (accumulator.error != null) {
                    onError.accept(accumulator.error);
                    return;
                }

                var content = accumulator.content;

                // Handle tool calls if present
                if (!accumulator.toolCalls.isEmpty()) {
                    content = handleToolCallsStreaming(agent, conversation, messages, tools,
                            accumulator.toolCalls, content, primary, onToken, maxTokens, 0);
                }

                // Persist and complete
                var finalContent = content;
                services.Tx.run(() ->
                        ConversationService.appendAssistantMessage(conversation, finalContent, null));

                EventLogger.info("llm", agent.name, channelType,
                        "Streaming complete (%d chars)".formatted(content.length()));
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

    private static String callWithToolLoop(Agent agent, Conversation conversation,
                                            List<ChatMessage> messages, List<ToolDef> tools,
                                            ProviderConfig primary, ProviderConfig secondary) {
        var currentMessages = new ArrayList<>(messages);
        var modelInfo = primary.models().stream()
                .filter(m -> m.id().equals(agent.modelId))
                .findFirst().orElse(null);
        var maxTokens = modelInfo != null && modelInfo.maxTokens() > 0 ? modelInfo.maxTokens() : null;

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            ChatResponse response;
            try {
                response = (secondary != null)
                        ? OpenAiCompatibleClient.chatWithFailover(primary, secondary, agent.modelId, currentMessages, tools, maxTokens)
                        : OpenAiCompatibleClient.chat(primary, agent.modelId, currentMessages, tools, maxTokens);
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

                // Persist tool interaction
                ConversationService.appendAssistantMessage(conversation,
                        null, gson.toJson(toolCall));
                ConversationService.appendToolResult(conversation,
                        toolCall.id(), result);
            }
        }

        return "I reached the maximum number of tool execution rounds. Please try a simpler request.";
    }

    private static String handleToolCallsStreaming(Agent agent, Conversation conversation,
                                                    List<ChatMessage> messages, List<ToolDef> tools,
                                                    List<ToolCall> toolCalls, String priorContent,
                                                    ProviderConfig provider,
                                                    Consumer<String> onToken, Integer maxTokens,
                                                    int round) {
        if (round >= MAX_TOOL_ROUNDS) {
            return "I reached the maximum number of tool execution rounds. Please try a simpler request.";
        }
        EventLogger.info("tool", agent.name, null,
                "Streaming round %d: executing %d tool call(s)".formatted(round + 1, toolCalls.size()));

        var currentMessages = new ArrayList<>(messages);
        currentMessages.add(ChatMessage.assistant(priorContent, toolCalls));

        for (var toolCall : toolCalls) {
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
                ConversationService.appendAssistantMessage(conversation, null, gson.toJson(toolCall));
                ConversationService.appendToolResult(conversation, toolCall.id(), result);
            });
        }

        EventLogger.info("llm", agent.name, null,
                "Streaming round %d: continuing LLM call after tool results".formatted(round + 1));

        // Continue with streaming after tool results
        var accumulator = OpenAiCompatibleClient.chatStreamAccumulate(
                provider, agent.modelId, currentMessages, tools, onToken, maxTokens);

        try {
            accumulator.awaitCompletion();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return priorContent;
        }

        // Recursively handle if more tool calls
        if (!accumulator.toolCalls.isEmpty()) {
            return handleToolCallsStreaming(agent, conversation, currentMessages, tools,
                    accumulator.toolCalls, accumulator.content, provider, onToken, maxTokens, round + 1);
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

    private static List<ChatMessage> trimToContextWindow(List<ChatMessage> messages, Agent agent, ProviderConfig provider) {
        // Find the model's context window
        var modelInfo = provider.models().stream()
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
