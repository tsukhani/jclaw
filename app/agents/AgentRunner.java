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

        EventLogger.info("llm", agent.name, conversation.channelType,
                "Calling %s / %s".formatted(primary.name(), agent.modelId));

        // 5. Get registered tools
        var tools = ToolRegistry.getToolDefs();

        // 6. LLM call loop (handles tool calls)
        var response = callWithToolLoop(agent, conversation, messages, tools, primary, secondary);

        // 7. Persist final assistant response
        ConversationService.appendAssistantMessage(conversation, response, null);

        EventLogger.info("llm", agent.name, conversation.channelType,
                "Response generated (%d chars)".formatted(response.length()));

        return new RunResult(response, conversation);
    }

    /**
     * Run the agent with streaming. Calls onToken for each token, returns final response.
     */
    public static void runStreaming(Agent agent, Conversation conversation, String userMessage,
                                    Consumer<String> onToken,
                                    Consumer<String> onComplete,
                                    Consumer<Exception> onError) {
        Thread.ofVirtual().start(() -> {
            try {
                // 1. Persist user message (JPA via Tx for virtual thread)
                services.Tx.run(() ->
                        ConversationService.appendUserMessage(conversation, userMessage));

                // 2. Assemble system prompt (reads JPA + filesystem)
                var assembled = services.Tx.run(() ->
                        SystemPromptAssembler.assemble(agent, userMessage));

                // 3. Build messages from conversation history
                var messages = services.Tx.run(() ->
                        buildMessages(assembled.systemPrompt(), conversation));

                // 4. Get provider for this agent (no JPA needed)
                var agentProvider = ProviderRegistry.get(agent.modelProvider);
                var primary = agentProvider != null ? agentProvider : ProviderRegistry.getPrimary();
                if (primary == null) {
                    onError.accept(new RuntimeException("No LLM provider configured"));
                    return;
                }

                var tools = ToolRegistry.getToolDefs();

                // 5. Stream with tool call handling (HTTP, no JPA)
                var accumulator = OpenAiCompatibleClient.chatStreamAccumulate(
                        primary, agent.modelId, messages, tools, onToken);

                accumulator.awaitCompletion();

                if (accumulator.error != null) {
                    onError.accept(accumulator.error);
                    return;
                }

                var content = accumulator.content;

                // Handle tool calls if present
                if (!accumulator.toolCalls.isEmpty()) {
                    content = handleToolCallsStreaming(agent, conversation, messages, tools,
                            accumulator.toolCalls, content, primary, onToken);
                }

                // Persist and complete
                var finalContent = content;
                services.Tx.run(() ->
                        ConversationService.appendAssistantMessage(conversation, finalContent, null));
                onComplete.accept(content);

            } catch (Exception e) {
                onError.accept(e);
            }
        });
    }

    // --- Internal ---

    private static String callWithToolLoop(Agent agent, Conversation conversation,
                                            List<ChatMessage> messages, List<ToolDef> tools,
                                            ProviderConfig primary, ProviderConfig secondary) {
        var currentMessages = new ArrayList<>(messages);

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            ChatResponse response;
            try {
                response = (secondary != null)
                        ? OpenAiCompatibleClient.chatWithFailover(primary, secondary, agent.modelId, currentMessages, tools)
                        : OpenAiCompatibleClient.chat(primary, agent.modelId, currentMessages, tools);
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
                    "Executing %d tool call(s)".formatted(assistantMsg.toolCalls().size()));

            for (var toolCall : assistantMsg.toolCalls()) {
                var result = ToolRegistry.execute(toolCall.function().name(),
                        toolCall.function().arguments(), agent);
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
                                                    Consumer<String> onToken) {
        var currentMessages = new ArrayList<>(messages);
        currentMessages.add(ChatMessage.assistant(priorContent, toolCalls));

        for (var toolCall : toolCalls) {
            var result = ToolRegistry.execute(toolCall.function().name(),
                    toolCall.function().arguments(), agent);
            currentMessages.add(ChatMessage.toolResult(toolCall.id(), result));

            ConversationService.appendAssistantMessage(conversation, null, gson.toJson(toolCall));
            ConversationService.appendToolResult(conversation, toolCall.id(), result);
        }

        // Continue with streaming after tool results
        var accumulator = OpenAiCompatibleClient.chatStreamAccumulate(
                provider, agent.modelId, currentMessages, tools, onToken);

        try {
            accumulator.awaitCompletion();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return priorContent;
        }

        // Recursively handle if more tool calls
        if (!accumulator.toolCalls.isEmpty()) {
            return handleToolCallsStreaming(agent, conversation, currentMessages, tools,
                    accumulator.toolCalls, accumulator.content, provider, onToken);
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

    private static List<ToolCall> parseToolCalls(String json) {
        try {
            var tc = gson.fromJson(json, ToolCall.class);
            return tc != null ? List.of(tc) : List.of();
        } catch (Exception _) {
            return List.of();
        }
    }
}
