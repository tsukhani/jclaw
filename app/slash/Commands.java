package slash;

import com.google.gson.JsonParser;
import llm.LlmProvider;
import llm.LlmTypes.ModelInfo;
import llm.ProviderRegistry;
import models.Agent;
import models.Conversation;
import models.Message;
import services.ConversationService;
import services.EventLogger;
import services.Tx;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Chat slash-command registry and dispatcher (JCLAW-26).
 *
 * <p>Commands are parsed at the channel entry points (web SSE, web sync,
 * Telegram streaming, generic channel) BEFORE the LLM round fires. A
 * recognized command short-circuits the turn: the caller persists a canned
 * assistant response, optionally mutates conversation state, and returns
 * without invoking the model. Unknown slash-prefixed input falls through
 * as normal user text — no hard rejection.
 *
 * <p>The "do nothing if not mine" contract is important: slash literals
 * like {@code /start} (Telegram bot-open auto-message) MUST pass through
 * to the LLM unless a future ticket handles them here explicitly.
 *
 * <h2>Persistence model</h2>
 * The user's slash text is <b>not</b> persisted as a message — it's a
 * control signal, not conversation content. The bot's canned response IS
 * persisted as an assistant message in the target conversation so history
 * reload shows the acknowledgement.
 *
 * <h2>/reset ordering</h2>
 * {@link Conversation#contextSince} is written <i>after</i> the assistant
 * response is appended, so the acknowledgement stays out of the next LLM
 * context window (its {@code createdAt} will be less than the watermark).
 */
public final class Commands {

    private Commands() {}

    /**
     * Recognized commands. Unknown slash-prefixed input returns empty
     * Optional from {@link #parse}.
     *
     * <p>The {@code shortDescription} is the string shown in Telegram's
     * native autocomplete dropdown when the user types {@code /}
     * (JCLAW-99). Keep them short — Telegram truncates long descriptions.
     */
    public enum Command {
        NEW("/new", "Start a fresh conversation"),
        RESET("/reset", "Clear the LLM's memory for this conversation"),
        COMPACT("/compact", "Summarize older turns to free context"),
        HELP("/help", "Show available commands"),
        MODEL("/model", "Show current model and its capabilities"),
        USAGE("/usage", "Show context usage for this conversation"),
        STOP("/stop", "Interrupt the current generation"),
        SUBAGENT("/subagent", "Inspect or kill subagent runs");

        public final String literal;
        public final String shortDescription;
        Command(String literal, String shortDescription) {
            this.literal = literal;
            this.shortDescription = shortDescription;
        }

        /** Name without the leading slash — the form Telegram's BotCommand expects. */
        public String bareName() { return literal.substring(1); }
    }

    /** Canned response text for {@link Command#HELP}. */
    public static final String HELP_TEXT = """
            Available commands:
            • /new — start a fresh conversation (creates a new thread)
            • /reset — clear the LLM's memory for this conversation (keeps the thread)
            • /compact — summarize older turns to free context (optional: /compact focus-hint)
            • /help — show this message
            • /model — show current model and its capabilities
            • /usage — show context usage for this conversation
            • /stop — interrupt the current generation
            • /subagent — inspect or kill subagent runs (list, info ID, log ID, kill ID)""";

    /**
     * Canned response for {@link Command#NEW}. The leading {@code >} line
     * becomes an HTML {@code <blockquote>} via {@code TelegramMarkdownFormatter},
     * which Telegram renders with a colored vertical bar on the left edge —
     * a native, unmistakable session-boundary marker. Web chat renders the
     * same markdown with its own blockquote styling, so the visual cue
     * works across both channels without channel-specific content.
     */
    public static final String NEW_TEXT = """
            > New Conversation

            What would you like to discuss?""";

    /** Canned response for {@link Command#RESET}. See {@link #NEW_TEXT} for the blockquote rationale. */
    public static final String RESET_TEXT = """
            > Context Cleared

            I no longer remember our earlier exchange in this conversation.""";

    /** Outcome of handling a slash command. */
    public record Result(Conversation conversation, String responseText, Command command) {}

    /**
     * Parse the first token of {@code text} as a recognized command.
     * Case-insensitive. Leading/trailing whitespace ignored. Anything
     * after the command (e.g. {@code "/help foo bar"}) is discarded —
     * commands don't currently take arguments.
     */
    public static Optional<Command> parse(String text) {
        if (text == null) return Optional.empty();
        var trimmed = text.strip();
        if (!trimmed.startsWith("/")) return Optional.empty();
        var firstSpace = indexOfWhitespace(trimmed);
        var head = firstSpace < 0 ? trimmed : trimmed.substring(0, firstSpace);
        for (var cmd : Command.values()) {
            if (cmd.literal.equalsIgnoreCase(head)) return Optional.of(cmd);
        }
        return Optional.empty();
    }

    private static int indexOfWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) return i;
        }
        return -1;
    }

    /**
     * Convenience: {@link #parse} + {@link #execute}. Returns empty when
     * the text is not a recognized slash command (including unknown
     * slash-prefixed input) — caller should proceed with the normal LLM
     * flow in that case.
     *
     * @param current may be null only for channels that haven't resolved a
     *                conversation yet; {@code /reset} and {@code /help}
     *                will be treated as no-ops in that case.
     */
    public static Optional<Result> handle(String text, Agent agent, String channelType,
                                           String peerId, Conversation current) {
        return parse(text).map(cmd -> execute(cmd, agent, channelType, peerId, current, extractArgs(text)));
    }

    /**
     * Extract the argument portion of a slash-command text — everything after
     * the first whitespace. Returns null when the command has no arguments.
     * {@code /model openrouter/gpt-5} yields {@code "openrouter/gpt-5"};
     * {@code /model} yields null.
     *
     * <p>Public so callers that already {@link #parse} can thread the raw
     * user text through the args-carrying {@link #execute} overload
     * without re-running the parser.
     */
    public static String extractArgs(String text) {
        if (text == null) return null;
        var trimmed = text.strip();
        var firstSpace = indexOfWhitespace(trimmed);
        if (firstSpace < 0) return null;
        var rest = trimmed.substring(firstSpace + 1).strip();
        return rest.isEmpty() ? null : rest;
    }

    /** Execute a previously-parsed command. See class javadoc for side effects. */
    public static Result execute(Command cmd, Agent agent, String channelType,
                                  String peerId, Conversation current) {
        return execute(cmd, agent, channelType, peerId, current, null);
    }

    /**
     * Argument-aware execute overload (JCLAW-108). {@code args} is the text
     * following the command literal with leading/trailing whitespace stripped;
     * null or empty when none. Only {@code /model} currently consumes args:
     * {@code /model NAME} writes the conversation-scoped override,
     * {@code /model reset} clears it.
     */
    public static Result execute(Command cmd, Agent agent, String channelType,
                                  String peerId, Conversation current, String args) {
        return switch (cmd) {
            case NEW -> executeNew(agent, channelType, peerId);
            case RESET -> executeReset(agent, channelType, current);
            case COMPACT -> executeCompact(agent, channelType, current, args);
            case HELP -> executeHelp(agent, channelType, current);
            case MODEL -> executeModel(agent, channelType, current, args);
            case USAGE -> executeUsage(agent, channelType, current);
            case STOP -> executeStop(agent, channelType, current);
            case SUBAGENT -> executeSubagent(agent, channelType, current, args);
        };
    }

    private static Result executeNew(Agent agent, String channelType, String peerId) {
        var newConv = Tx.run(() -> {
            var conv = ConversationService.create(agent, channelType, peerId);
            ConversationService.appendAssistantMessage(conv, NEW_TEXT, null);
            return conv;
        });
        EventLogger.info("SLASH_COMMAND", agent != null ? agent.name : null, channelType,
                "/new → new conversation %d for peer=%s".formatted(newConv.id, peerId));
        return new Result(newConv, NEW_TEXT, Command.NEW);
    }

    private static Result executeReset(Agent agent, String channelType, Conversation current) {
        if (current == null) {
            // Nothing to reset. Treat as help-like: tell the user we can't
            // reset because there's no current conversation.
            var fallback = "No active conversation to reset.";
            EventLogger.warn("SLASH_COMMAND", agent != null ? agent.name : null, channelType,
                    "/reset with no current conversation");
            return new Result(null, fallback, Command.RESET);
        }
        final Long convId = current.id;
        var updated = Tx.run(() -> {
            var conv = (Conversation) Conversation.findById(convId);
            if (conv == null) return null;
            // The ack text is delivered to the user by the caller (via SSE
            // complete frame on web, sink.seal on Telegram) but NOT persisted
            // as a Message row. /reset is a control signal; storing it as
            // content created a timing-sensitive filter rule (the ack's
            // createdAt had to align with contextSince across JDBC driver
            // rounding) that flaked on lower-resolution Linux clocks.
            // Keeping it transient sidesteps the problem entirely — the
            // event_log entry below is the durable record of the reset.
            conv.contextSince = Instant.now();
            conv.save();
            return conv;
        });
        EventLogger.info("SLASH_COMMAND", agent != null ? agent.name : null, channelType,
                "/reset for conversation %d".formatted(convId));
        return new Result(updated != null ? updated : current, RESET_TEXT, Command.RESET);
    }

    /**
     * {@code /stop} — interrupt an in-flight assistant turn for this conversation.
     *
     * <p>Sets {@link services.ConversationQueue#cancellationFlag} so
     * {@link agents.AgentRunner}'s {@code checkCancelled} polls short-circuit
     * the streaming loop on the next checkpoint. The shared
     * {@code AtomicBoolean} works because {@code tryAcquire} (which any active
     * stream has already passed through) creates the {@code QueueState} via
     * {@code computeIfAbsent} — so the streaming thread and this dispatcher
     * thread receive the same object reference, and the volatile write is
     * visible immediately.
     *
     * <p>On Telegram this fully interrupts in-flight processing. On web,
     * {@code AgentRunner.runStreaming} polls a per-request token created in
     * {@code ApiChatController}, not {@code ConversationQueue.cancellationFlag},
     * so this command is largely a no-op there — the in-product stop button
     * remains the primary mechanism for the web channel. Worth keeping in
     * the shared registry anyway: typed {@code /stop} surfaces uniformly
     * across channels and the web reply is informative either way.
     *
     * <p>Like {@code /reset}, the ack is delivered via the channel transport
     * (SSE complete frame on web, sink.seal on Telegram) but NOT persisted
     * as a Message row — {@code /stop} is a control signal, not conversation
     * content, and persisting "Stopped." next to whatever partial assistant
     * content the cancelled stream left behind would clutter history and
     * skew {@code /usage} accounting.
     */
    private static Result executeStop(Agent agent, String channelType, Conversation current) {
        if (current == null) {
            EventLogger.info("SLASH_COMMAND", agent != null ? agent.name : null, channelType,
                    "/stop with no current conversation");
            return new Result(null, "No active conversation. Nothing to stop.", Command.STOP);
        }
        if (!services.ConversationQueue.isBusy(current.id)) {
            EventLogger.info("SLASH_COMMAND", agent != null ? agent.name : null, channelType,
                    "/stop for conversation %d — nothing in flight".formatted(current.id));
            return new Result(current, "Nothing to stop.", Command.STOP);
        }
        services.ConversationQueue.cancellationFlag(current.id).set(true);
        EventLogger.info("SLASH_COMMAND", agent != null ? agent.name : null, channelType,
                "/stop signalled cancellation for conversation %d".formatted(current.id));
        return new Result(current, "Stopped.", Command.STOP);
    }

    /**
     * {@code /compact [hint]} — manually trigger session compaction
     * (JCLAW-38). Bypasses the auto-trigger's too-few-turns guard so
     * users can summarize on demand even on smaller conversations, and
     * accepts an optional guidance hint that's appended to the
     * summarization prompt (e.g. {@code /compact focus on the SQL
     * migration work}).
     *
     * <p>Cycle-safety is preserved — boundaries still anchor at user
     * messages, so tool_call/tool_result pairs are never split. The
     * response is a canned acknowledgement with turn-count and rough
     * token size of the summary; the summary itself is in the DB
     * ({@link models.SessionCompaction}) and re-injected into the next
     * turn's system prompt by {@link services.SessionCompactor#appendSummaryToPrompt}.
     *
     * <p>Runs the summarization LLM call synchronously on the caller's
     * thread — the channel's response shows up only after the
     * summarizer returns. This matches {@code /model NAME}'s validation
     * latency and is acceptable for an explicit user-requested action.
     */
    private static Result executeCompact(Agent agent, String channelType, Conversation current, String args) {
        if (current == null) {
            var fallback = "No active conversation to compact.";
            EventLogger.warn("SLASH_COMMAND", agent != null ? agent.name : null, channelType,
                    "/compact with no current conversation");
            return new Result(null, fallback, Command.COMPACT);
        }

        // Resolve provider: prefer the conversation override, fall back to
        // the agent default, then to the registry's primary. Mirrors
        // AgentRunner.run's provider-selection flow.
        var resolved = services.ModelOverrideResolver.resolve(current, agent);
        var providerName = resolved.provider();
        var primary = ProviderRegistry.get(providerName);
        if (primary == null) primary = ProviderRegistry.getPrimary();
        if (primary == null) {
            var msg = "> Compaction failed\n\nNo LLM provider is configured. Add one in Settings.";
            persistCompactAckAndLog(agent, channelType, current, msg, "no-provider");
            return new Result(current, msg, Command.COMPACT);
        }

        var modelId = resolved.modelId();
        var modelLabel = primary.config().name() + "/" + modelId;
        var maxOutput = services.ConfigService.getInt("chat.compactionMaxTokens", 8192);

        final var capturedPrimary = primary;
        final var capturedModelId = modelId;
        services.SessionCompactor.Summarizer summarizer = sumMsgs -> {
            // Slash-command-triggered compaction has no inbound chat-channel
            // context (it runs on a programmatic invocation), so dispatcher_wait
            // for this call records under "unknown".
            var resp = capturedPrimary.chat(capturedModelId, sumMsgs, java.util.List.of(), maxOutput, null, null);
            return services.SessionCompactor.firstChoiceText(resp);
        };

        var result = services.SessionCompactor.compact(current.id, modelLabel, summarizer,
                /*force*/ true, args);
        var responseText = buildCompactResponseText(result, args);
        persistCompactAckAndLog(agent, channelType, current, responseText,
                result.compacted() ? "compacted %d turns".formatted(result.turnsCompacted())
                        : "skipped: " + result.skipReason());

        return new Result(current, responseText, Command.COMPACT);
    }

    /**
     * Format the canned ack for {@link #executeCompact} — Telegram and
     * web both render the leading {@code >} as a blockquote for a clear
     * visual boundary.
     */
    private static String buildCompactResponseText(services.SessionCompactor.CompactionResult result, String args) {
        if (!result.compacted()) {
            var reason = result.skipReason();
            if ("no safe boundary or below min-turns".equals(reason)) {
                return "> Nothing to compact\n\nThis conversation doesn't have enough earlier turns to summarize yet.";
            }
            if ("llm error".equals(reason)) {
                return "> Compaction failed\n\nThe summarization LLM call errored out. Older turns remain in the active context.";
            }
            if ("empty summary".equals(reason)) {
                return "> Compaction failed\n\nThe summarization model returned an empty response. Older turns remain in the active context.";
            }
            return "> Compaction skipped\n\n" + (reason != null ? reason : "unknown reason");
        }
        var approxTokens = Math.max(1, result.summaryChars() / 4);
        var hintNote = (args != null && !args.isBlank()) ? " (guidance: " + args.strip() + ")" : "";
        return """
                > Conversation Compacted%s

                Summarized %d earlier turns into ~%d tokens. Older messages remain in history and will stop being shipped to the model starting next turn."""
                .formatted(hintNote, result.turnsCompacted(), approxTokens);
    }

    private static void persistCompactAckAndLog(Agent agent, String channelType,
                                                 Conversation current, String text, String outcome) {
        final Long convId = current.id;
        final String responseFinal = text;
        Tx.run(() -> {
            var conv = (Conversation) Conversation.findById(convId);
            if (conv != null) {
                ConversationService.appendAssistantMessage(conv, responseFinal, null);
            }
        });
        EventLogger.info("SLASH_COMMAND", agent != null ? agent.name : null, channelType,
                "/compact for conversation %d: %s".formatted(convId, outcome));
    }

    private static Result executeHelp(Agent agent, String channelType, Conversation current) {
        if (current != null) {
            final Long convId = current.id;
            Tx.run(() -> {
                var conv = (Conversation) Conversation.findById(convId);
                if (conv != null) {
                    ConversationService.appendAssistantMessage(conv, HELP_TEXT, null);
                }
            });
        }
        EventLogger.info("SLASH_COMMAND", agent != null ? agent.name : null, channelType,
                "/help" + (current != null ? " for conversation " + current.id : ""));
        return new Result(current, HELP_TEXT, Command.HELP);
    }

    /**
     * {@code /model} — three argument forms (JCLAW-107 for the first, JCLAW-108
     * for the other two):
     * <ul>
     *   <li>{@code /model} — show the current model's identity, capabilities,
     *       context window, and pricing. Honors any conversation-scoped
     *       override when displaying.</li>
     *   <li>{@code /model NAME} — set the conversation-scoped override to
     *       {@code NAME} (parsed as {@code provider/model-id}). Validates
     *       the pair exists in the provider registry; writes both override
     *       columns atomically; includes a shrinkage warning when the new
     *       model's context window is smaller than the current input-token
     *       estimate.</li>
     *   <li>{@code /model reset} — clear the override, reverting to the
     *       agent's default.</li>
     * </ul>
     * Validation failures render a helpful response; no state is mutated.
     */
    private static Result executeModel(Agent agent, String channelType, Conversation current, String args) {
        if (args == null || args.isBlank()) {
            // JCLAW-109: on Telegram, render the short summary with an
            // inline-keyboard selector. The handler sends the keyboard
            // message itself via TelegramChannel.sendMessageWithKeyboard,
            // so we return an empty responseText — processInboundForAgentStreaming
            // skips the default sink.seal when the text is empty.
            if ("telegram".equals(channelType) && current != null && agent != null) {
                var delivered = channels.TelegramModelSelector.sendSummary(agent, current);
                EventLogger.info("SLASH_COMMAND", agent.name, channelType,
                        "/model (summary+keyboard) for conversation " + current.id
                                + (delivered ? "" : " — delivery failed"));
                return new Result(current, delivered ? "" : buildModelResponse(agent, current), Command.MODEL);
            }
            // Web, tests, or no-conversation fallback: full detail text as before.
            var responseText = Tx.run(() -> {
                var text = buildModelResponse(agent, current);
                persistCannedResponseInTx(current, text);
                return text;
            });
            EventLogger.info("SLASH_COMMAND", agent != null ? agent.name : null, channelType,
                    "/model" + (current != null ? " for conversation " + current.id : ""));
            return new Result(current, responseText, Command.MODEL);
        }
        // /model status — the full detail view, callable explicitly on any channel.
        if (args.equalsIgnoreCase("status")) {
            var responseText = Tx.run(() -> {
                var text = buildModelResponse(agent, current);
                persistCannedResponseInTx(current, text);
                return text;
            });
            EventLogger.info("SLASH_COMMAND", agent != null ? agent.name : null, channelType,
                    "/model status" + (current != null ? " for conversation " + current.id : ""));
            return new Result(current, responseText, Command.MODEL);
        }
        if (args.equalsIgnoreCase("reset")) {
            var responseText = Tx.run(() -> {
                var text = performModelReset(agent, current);
                persistCannedResponseInTx(current, text);
                return text;
            });
            EventLogger.info("SLASH_COMMAND", agent != null ? agent.name : null, channelType,
                    "/model reset" + (current != null ? " for conversation " + current.id : ""));
            return new Result(current, responseText, Command.MODEL);
        }
        // Write path: /model provider/model-id
        var responseText = Tx.run(() -> {
            var text = performModelSwitch(agent, current, args);
            persistCannedResponseInTx(current, text);
            return text;
        });
        EventLogger.info("SLASH_COMMAND", agent != null ? agent.name : null, channelType,
                "/model " + args + (current != null ? " for conversation " + current.id : ""));
        return new Result(current, responseText, Command.MODEL);
    }

    /**
     * {@code /usage} — render the current conversation's context usage. Sums
     * the latest assistant turn's {@code prompt} and {@code completion} tokens
     * (current context size, not lifetime — lifetime cost is JCLAW-108's
     * aggregator) and expresses the prompt as a percentage of the resolved
     * model's {@code contextWindow}. Divide-by-zero is guarded: if the
     * discovered model has no context window, the percentage is rendered as
     * "unknown (model metadata incomplete)".
     */
    private static Result executeUsage(Agent agent, String channelType, Conversation current) {
        // buildUsageResponse calls ConversationService.loadRecentMessages,
        // which issues a JPQL query and needs an active EntityManager. Wrap
        // the full handler in Tx.run so the polling-thread entry point
        // (Telegram) gets a transaction — the web SSE path already runs
        // inside a request-scoped tx, and Tx.run joins that instead of
        // opening a new one.
        var responseText = Tx.run(() -> {
            var text = buildUsageResponse(agent, current);
            persistCannedResponseInTx(current, text);
            return text;
        });
        EventLogger.info("SLASH_COMMAND", agent != null ? agent.name : null, channelType,
                "/usage" + (current != null ? " for conversation " + current.id : ""));
        return new Result(current, responseText, Command.USAGE);
    }

    /**
     * Persist a canned (slash-command) assistant reply when the caller has
     * already opened a transaction. Avoids a redundant Tx.run() nesting when
     * the handler body is itself wrapped in one (the {@code /usage} case,
     * which reads message history).
     */
    private static void persistCannedResponseInTx(Conversation current, String responseText) {
        if (current == null) return;
        var conv = (Conversation) Conversation.findById(current.id);
        if (conv != null) {
            ConversationService.appendAssistantMessage(conv, responseText, null);
        }
    }

    /**
     * Lookup the effective model in its provider's configured model list.
     * Honors the conversation-scoped override (JCLAW-108) when present;
     * otherwise falls back to the agent's default model.
     */
    private static Optional<ModelInfo> resolveModel(Agent agent, Conversation current) {
        var providerName = effectiveProviderName(agent, current);
        var modelId = effectiveModelIdFor(agent, current);
        if (providerName == null || modelId == null) return Optional.empty();
        LlmProvider provider = ProviderRegistry.get(providerName);
        if (provider == null) return Optional.empty();
        return provider.config().models().stream()
                .filter(m -> modelId.equals(m.id()))
                .findFirst();
    }

    /** Resolve the effective provider name — override when present, else agent default. */
    private static String effectiveProviderName(Agent agent, Conversation current) {
        return services.ModelOverrideResolver.provider(current, agent);
    }

    /** Resolve the effective model id — override when present, else agent default. */
    private static String effectiveModelIdFor(Agent agent, Conversation current) {
        return services.ModelOverrideResolver.modelId(current, agent);
    }

    public static String buildModelResponse(Agent agent, Conversation current) {
        if (agent == null) return "No agent bound to this conversation.";
        var providerName = effectiveProviderName(agent, current);
        var modelId = effectiveModelIdFor(agent, current);
        var overrideActive = services.ModelOverrideResolver.hasOverride(current);
        var model = resolveModel(agent, current);
        if (model.isEmpty()) {
            return "Model not found in provider config (%s/%s). Re-assign the agent's model or restore the provider entry."
                    .formatted(providerName, modelId);
        }
        var m = model.get();
        var thinkingLine = m.supportsThinking()
                ? "supported" + renderThinkingSelection(agent, m)
                : "not supported";
        var sb = new StringBuilder();
        sb.append("Model: ").append(providerName).append('/').append(modelId).append('\n');
        if (overrideActive) {
            // Make the scope explicit — users coming from OpenClaw expect
            // session-scoped switches; JClaw's override is conversation-scoped
            // and a /model reset returns to the agent default.
            sb.append("(Conversation override active — agent default is ")
                    .append(agent.modelProvider).append('/').append(agent.modelId)
                    .append(")\n");
        }
        if (m.name() != null && !m.name().isBlank() && !m.name().equals(modelId)) {
            sb.append("Display name: ").append(m.name()).append('\n');
        }
        sb.append("Provider: ").append(providerName).append('\n');
        sb.append("Context window: ").append(formatTokenCapacity(m.contextWindow())).append('\n');
        sb.append("Max output: ").append(formatTokenCapacity(m.maxTokens())).append('\n');
        sb.append("Thinking: ").append(thinkingLine).append('\n');
        sb.append("Vision: ").append(m.supportsVision() ? "supported" : "not supported").append('\n');
        sb.append("Audio: ").append(m.supportsAudio() ? "supported" : "not supported").append('\n');
        sb.append("Pricing (per 1M tokens): ").append(formatPricing(m));
        return sb.toString();
    }

    /**
     * Execute {@code /model NAME} — parse NAME as {@code provider/model-id},
     * validate, write the override, and return a confirmation (possibly with
     * a shrinkage warning). Validation failures return an explanatory message
     * without mutating state. Caller is responsible for opening the transaction.
     */
    public static String performModelSwitch(Agent agent, Conversation current, String args) {
        if (current == null) {
            return "No active conversation — cannot switch models without a target.";
        }
        if (agent == null) {
            return "No agent bound to this conversation — cannot switch models.";
        }
        int slash = args.indexOf('/');
        if (slash <= 0 || slash == args.length() - 1) {
            return "Unrecognized model format. Use `/model provider/model-id` "
                    + "(for example `/model openrouter/google-flash-preview`) "
                    + "or `/model reset` to revert to the agent default.";
        }
        var newProvider = args.substring(0, slash).strip();
        var newModelId = args.substring(slash + 1).strip();
        if (newProvider.isEmpty() || newModelId.isEmpty()) {
            return "Unrecognized model format. Use `/model provider/model-id` "
                    + "(for example `/model openrouter/google-flash-preview`).";
        }
        var provider = ProviderRegistry.get(newProvider);
        if (provider == null) {
            return "Provider `%s` is not configured. Available providers appear under Settings → Providers; "
                    .formatted(newProvider)
                    + "add one there before switching to it.";
        }
        var resolved = provider.config().models().stream()
                .filter(m -> newModelId.equals(m.id()))
                .findFirst();
        if (resolved.isEmpty()) {
            return "Provider `%s` has no model with id `%s`. Run `/model` to see the current model "
                    .formatted(newProvider, newModelId)
                    + "or check Settings → Providers for the available list.";
        }

        // Warn if the new model's context window is smaller than the estimated
        // current context size — the next turn's trim will drop oldest messages.
        var shrinkage = computeShrinkageWarning(resolved.get(), current);

        // Reload the persistence-context entity so the save() below writes to
        // the managed instance, not a detached copy.
        var managed = (Conversation) Conversation.findById(current.id);
        if (managed == null) {
            return "Conversation disappeared mid-switch — try again.";
        }
        ConversationService.setModelOverride(managed, newProvider, newModelId);

        var sb = new StringBuilder();
        sb.append("Switched this conversation to `").append(newProvider).append('/').append(newModelId).append("`.\n");
        sb.append("Agent default (").append(agent.modelProvider).append('/').append(agent.modelId)
                .append(") is unchanged. Use `/model reset` to revert this conversation.");
        if (shrinkage != null) sb.append('\n').append(shrinkage);
        return sb.toString();
    }

    /**
     * Execute {@code /model reset} — clear both override columns, revert to the
     * agent default, and return a confirmation. Caller owns the transaction.
     */
    static String performModelReset(Agent agent, Conversation current) {
        if (current == null) {
            return "No active conversation — nothing to reset.";
        }
        if (agent == null) {
            return "No agent bound to this conversation — cannot reset.";
        }
        var managed = (Conversation) Conversation.findById(current.id);
        if (managed == null) {
            return "Conversation disappeared mid-reset — try again.";
        }
        boolean hadOverride = managed.modelProviderOverride != null && managed.modelIdOverride != null;
        ConversationService.clearModelOverride(managed);
        if (!hadOverride) {
            return "This conversation had no override. The agent default (" + agent.modelProvider
                    + "/" + agent.modelId + ") remains in effect.";
        }
        return "Cleared the conversation's model override. Reverted to agent default "
                + agent.modelProvider + "/" + agent.modelId + ".";
    }

    /**
     * Compute a human-readable shrinkage warning when switching to a model
     * whose context window is smaller than the estimated current context size.
     * The size estimate is the latest assistant turn's {@code prompt} — i.e.
     * what was actually in the model's view on the last turn, same signal
     * JCLAW-107's {@code /usage} uses. Returns null when no warning applies.
     */
    private static String computeShrinkageWarning(ModelInfo newModel, Conversation current) {
        int newWindow = newModel.contextWindow();
        if (newWindow <= 0) return null;
        var messages = ConversationService.loadRecentMessages(current);
        var latest = findLatestAssistantUsage(messages);
        if (latest == null) return null;
        int currentInput = latest[0];
        if (currentInput <= newWindow) return null;
        return "Warning: %s's %s context window is smaller than the current context (%s estimated). "
                .formatted(newModel.id(), formatTokenCapacity(newWindow), formatTokens(currentInput))
                + "Older messages will be trimmed on the next turn.";
    }

    private static String renderThinkingSelection(Agent agent, ModelInfo m) {
        if (agent.thinkingMode == null || agent.thinkingMode.isBlank()) return " (not currently enabled)";
        var levels = m.thinkingLevels();
        if (levels != null && !levels.isEmpty() && !levels.contains(agent.thinkingMode)) {
            return " (current setting %s is not advertised by this model — effectively off)"
                    .formatted(agent.thinkingMode);
        }
        return " (effort: %s)".formatted(agent.thinkingMode);
    }

    private static String formatPricing(ModelInfo m) {
        if (m.promptPrice() < 0 && m.completionPrice() < 0
                && m.cachedReadPrice() < 0 && m.cacheWritePrice() < 0) {
            return "unknown";
        }
        var parts = new java.util.ArrayList<String>(4);
        parts.add("input " + formatPrice(m.promptPrice()));
        parts.add("output " + formatPrice(m.completionPrice()));
        parts.add("cache read " + formatPrice(m.cachedReadPrice()));
        parts.add("cache write " + formatPrice(m.cacheWritePrice()));
        return String.join(", ", parts);
    }

    private static String formatPrice(double perMillion) {
        if (perMillion < 0) return "n/a";
        if (perMillion == 0) return "free";
        // Keep two decimals up to $9.99, three for sub-dollar granularity.
        return perMillion < 1.0
                ? "$%.3f".formatted(perMillion)
                : "$%.2f".formatted(perMillion);
    }

    static String buildUsageResponse(Agent agent, Conversation current) {
        if (current == null) return "No active conversation — no usage to report.";
        // JCLAW-108: resolveModel honors the conversation override; the Model
        // line below also reflects the effective id so switching mid-chat
        // shows the new model in subsequent /usage output.
        var model = resolveModel(agent, current);
        var effectiveProvider = effectiveProviderName(agent, current);
        var effectiveModel = effectiveModelIdFor(agent, current);
        var messages = ConversationService.loadRecentMessages(current);
        var latest = findLatestAssistantUsage(messages);
        int prompt = latest != null ? latest[0] : 0;
        int completion = latest != null ? latest[1] : 0;
        int total = prompt + completion;

        var sb = new StringBuilder();
        sb.append("Input: ").append(formatTokens(prompt)).append(" tokens\n");
        sb.append("Output: ").append(formatTokens(completion)).append(" tokens\n");
        sb.append("Total: ").append(formatTokens(total)).append(" tokens\n");
        sb.append("Context: ").append(renderContextLine(prompt, model)).append('\n');
        sb.append("Model: ")
                .append(effectiveProvider != null ? effectiveProvider : "?")
                .append('/')
                .append(effectiveModel != null ? effectiveModel : "?");
        return sb.toString();
    }

    /**
     * Walk messages newest-first, returning {@code [prompt, completion]} from the
     * most recent assistant turn that has usage data. Skips user, tool, and
     * assistant-without-usage rows (fresh conversations, turns without provider
     * usage, slash-command acks). Returns {@code null} when no such turn
     * exists — callers render zeros in that case.
     */
    @SuppressWarnings("java:S1168") // null is a documented sentinel meaning "no usage data found"; callers render zeros
    private static int[] findLatestAssistantUsage(List<Message> messages) {
        if (messages == null || messages.isEmpty()) return null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            var m = messages.get(i);
            if (!"assistant".equals(m.role)) continue;
            if (m.usageJson == null || m.usageJson.isBlank()) continue;
            try {
                var obj = JsonParser.parseString(m.usageJson).getAsJsonObject();
                // Skip durationMs-only rows (cancelled turns, turns where the
                // provider didn't return usage). Those carry no "prompt" field
                // and reporting them as "current context = 0" would mislead
                // the user when the actual last successful turn had real
                // context. Keep scanning back for a turn with real tokens.
                if (!obj.has("prompt")) continue;
                int prompt = obj.get("prompt").getAsInt();
                int completion = obj.has("completion") ? obj.get("completion").getAsInt() : 0;
                return new int[]{prompt, completion};
            } catch (Exception _) {
                // Malformed usageJson — skip and keep scanning.
            }
        }
        return null;
    }

    private static String renderContextLine(int prompt, Optional<ModelInfo> model) {
        if (model.isEmpty() || model.get().contextWindow() <= 0) {
            return "unknown (model metadata incomplete)";
        }
        int cw = model.get().contextWindow();
        int pct = (int) Math.round(prompt * 100.0 / cw);
        return "%d%% of %s tokens used".formatted(pct, formatTokenCapacity(cw));
    }

    /**
     * Render a token count as a human-friendly capacity string: {@code 200000} →
     * {@code "200K"}, {@code 1000000} → {@code "1M"}, {@code 2048000} →
     * {@code "2.0M"}. Uses decimal (1K = 1000) matching provider-published
     * context-window values like Anthropic's "200K" and Google's "2M". Returns
     * {@code "?"} when the value is non-positive.
     */
    private static String formatTokenCapacity(int tokens) {
        if (tokens <= 0) return "?";
        if (tokens >= 1_000_000) {
            double m = tokens / 1_000_000.0;
            return m == Math.floor(m) ? "%.0fM".formatted(m) : "%.1fM".formatted(m);
        }
        if (tokens >= 1_000) return "%dK".formatted(tokens / 1_000);
        return Integer.toString(tokens);
    }

    private static String formatTokens(int tokens) {
        // Thousands separator for readability at larger counts.
        return String.format("%,d", tokens);
    }

    // ── /subagent ─────────────────────────────────────────────────────────
    //
    // JCLAW-271: operator-facing introspection + kill surface for subagent
    // runs spawned by the current parent conversation. The four subcommands
    // map 1:1 to the AC:
    //   list           — RUNNING + recently-terminal rows scoped to current
    //   info <id>      — full metadata for a single run
    //   log <id>       — chronological EventLog rows matching run id
    //   kill <id>      — flip RUNNING → KILLED, interrupt the VT, emit event
    // Output is plain text suitable for a chat bubble; the SubagentRuns
    // admin page (separate Vue route) gets the same data via the new
    // /api/subagent-runs REST endpoint.

    /** Max EventLog rows returned by {@code /subagent log}. Chosen so the
     *  output stays readable in a chat bubble while still covering a
     *  multi-turn child run's typical event-rate. */
    private static final int SUBAGENT_LOG_LIMIT = 50;

    private static Result executeSubagent(Agent agent, String channelType,
                                           Conversation current, String args) {
        var sub = parseSubagentArgs(args);
        var responseText = Tx.run(() -> {
            var text = buildSubagentResponse(agent, current, sub);
            persistCannedResponseInTx(current, text);
            return text;
        });
        EventLogger.info("SLASH_COMMAND", agent != null ? agent.name : null, channelType,
                "/subagent " + (sub.kind() != null ? sub.kind() : "(no-args)")
                        + (sub.id() != null ? " " + sub.id() : "")
                        + (current != null ? " for conversation " + current.id : ""));
        return new Result(current, responseText, Command.SUBAGENT);
    }

    /** Outcome of parsing the subcommand portion of {@code /subagent ARGS}. */
    private record SubagentArgs(String kind, Long id, String error) {}

    private static SubagentArgs parseSubagentArgs(String args) {
        if (args == null || args.isBlank()) {
            return new SubagentArgs("list", null, null);
        }
        var trimmed = args.strip();
        var space = indexOfWhitespace(trimmed);
        var head = (space < 0 ? trimmed : trimmed.substring(0, space)).toLowerCase();
        var rest = space < 0 ? "" : trimmed.substring(space + 1).strip();
        switch (head) {
            case "list" -> {
                return new SubagentArgs("list", null, null);
            }
            case "info", "log", "kill" -> {
                if (rest.isEmpty()) {
                    return new SubagentArgs(head, null,
                            "Missing run id. Usage: /subagent " + head + " <run-id>");
                }
                try {
                    return new SubagentArgs(head, Long.parseLong(rest), null);
                } catch (NumberFormatException _) {
                    return new SubagentArgs(head, null,
                            "Invalid run id '" + rest + "' — expected a numeric SubagentRun id.");
                }
            }
            default -> {
                return new SubagentArgs(null, null,
                        "Unknown subcommand '" + head + "'. "
                                + "Available: list, info <id>, log <id>, kill <id>.");
            }
        }
    }

    /** Build the response text for a parsed {@code /subagent} call. Must run
     *  inside a Tx so the DB lookups have an active EntityManager. */
    private static String buildSubagentResponse(Agent agent, Conversation current, SubagentArgs sub) {
        if (sub.error() != null) {
            return sub.error();
        }
        return switch (sub.kind()) {
            case "list" -> renderSubagentList(current);
            case "info" -> renderSubagentInfo(sub.id());
            case "log" -> renderSubagentLog(sub.id());
            case "kill" -> renderSubagentKill(agent, sub.id());
            default -> "Unknown /subagent subcommand.";
        };
    }

    private static String renderSubagentList(Conversation current) {
        if (current == null) {
            return "No active conversation — /subagent list requires a parent conversation.";
        }
        // AC: scoped to the current parent conversation. RUNNING first, then
        // most recent. JPQL doesn't allow enum literals in ORDER BY, so we
        // fetch with a startedAt-DESC sort and re-sort in Java to lift the
        // RUNNING rows to the top (stable, small N — limit 50). Limit a
        // generous 50 so a long-running parent's history still fits;
        // anything more goes to the admin page.
        List<models.SubagentRun> runs = models.SubagentRun.find(
                "parentConversation = ?1 ORDER BY startedAt DESC",
                current).fetch(50);
        if (runs.isEmpty()) {
            return "No subagent runs in this conversation.";
        }
        runs = new java.util.ArrayList<>(runs);
        runs.sort((a, b) -> {
            int aRunning = a.status == models.SubagentRun.Status.RUNNING ? 0 : 1;
            int bRunning = b.status == models.SubagentRun.Status.RUNNING ? 0 : 1;
            if (aRunning != bRunning) return Integer.compare(aRunning, bRunning);
            // Tie-break: newer first. startedAt is non-null per @PrePersist.
            return b.startedAt.compareTo(a.startedAt);
        });
        var sb = new StringBuilder();
        sb.append("Subagent runs for this conversation:\n");
        for (var run : runs) {
            sb.append('\n').append(formatRunSummary(run));
        }
        return sb.toString();
    }

    private static String renderSubagentInfo(Long runId) {
        if (runId == null) return "Missing run id.";
        var run = (models.SubagentRun) models.SubagentRun.findById(runId);
        if (run == null) return "Run " + runId + " not found.";
        // Pull mode/context from the most recent SUBAGENT_SPAWN event for this
        // run id. Those fields aren't on SubagentRun directly — the typed
        // EventLogger helpers stash them in the JSON details payload.
        var spawnEvent = findSpawnEventDetails(runId);
        String mode = spawnEvent != null ? extractJsonField(spawnEvent, "mode") : null;
        String context = spawnEvent != null ? extractJsonField(spawnEvent, "context") : null;

        var sb = new StringBuilder();
        sb.append("Subagent run #").append(run.id).append('\n');
        sb.append("Parent agent: ").append(run.parentAgent != null ? run.parentAgent.name : "?").append('\n');
        sb.append("Child agent: ").append(run.childAgent != null ? run.childAgent.name : "?").append('\n');
        sb.append("Parent conversation: ").append(run.parentConversation != null ? run.parentConversation.id : "?").append('\n');
        sb.append("Child conversation: ").append(run.childConversation != null ? run.childConversation.id : "?").append('\n');
        sb.append("Mode: ").append(mode != null ? mode : "?").append('\n');
        sb.append("Context: ").append(context != null ? context : "?").append('\n');
        sb.append("Status: ").append(run.status.name()).append('\n');
        sb.append("Started: ").append(run.startedAt != null ? run.startedAt.toString() : "?").append('\n');
        sb.append("Ended: ").append(run.endedAt != null ? run.endedAt.toString() : "—").append('\n');
        if (run.outcome != null && !run.outcome.isBlank()) {
            // Truncate long outcomes so an LLM-essay reply doesn't blow up
            // the chat bubble; the full text is on the child Conversation
            // page anyway.
            var outcome = run.outcome.length() > 500
                    ? run.outcome.substring(0, 497) + "..."
                    : run.outcome;
            sb.append("Outcome: ").append(outcome);
        }
        else {
            sb.append("Outcome: —");
        }
        return sb.toString();
    }

    private static String renderSubagentLog(Long runId) {
        if (runId == null) return "Missing run id.";
        // The run must exist before we promise log rows for it; surface a
        // clear 404 rather than an empty list (which an operator would
        // misread as "no events" when the id is actually a typo).
        var run = (models.SubagentRun) models.SubagentRun.findById(runId);
        if (run == null) return "Run " + runId + " not found.";

        // EventLog.details is a JSON blob containing run_id. H2's LIKE on a
        // small set of rows is cheap; we filter further in Java to extract
        // the canonical run_id field rather than matching string prefixes
        // that could collide with a substring elsewhere in the payload.
        var marker = "\"run_id\":\"" + runId + "\"";
        List<models.EventLog> rows = models.EventLog.<models.EventLog>find(
                "details LIKE ?1 AND category LIKE 'SUBAGENT_%' ORDER BY timestamp ASC",
                "%" + marker + "%").fetch(SUBAGENT_LOG_LIMIT);
        if (rows.isEmpty()) {
            return "No events for run " + runId + ".";
        }
        var sb = new StringBuilder();
        sb.append("Events for subagent run #").append(runId).append(":\n");
        for (var row : rows) {
            sb.append('\n')
                    .append(row.timestamp != null ? row.timestamp.toString() : "?")
                    .append(' ').append(row.level)
                    .append(' ').append(row.category)
                    .append(" — ").append(row.message != null ? row.message : "");
        }
        return sb.toString();
    }

    private static String renderSubagentKill(Agent agent, Long runId) {
        if (runId == null) return "Missing run id.";
        var reason = agent != null
                ? "Killed by operator via /subagent kill (agent " + agent.name + ")"
                : "Killed by operator via /subagent kill";
        var result = services.SubagentRegistry.kill(runId, reason);
        return result.message();
    }

    /** Compact one-line summary of a SubagentRun for the {@code list} view. */
    private static String formatRunSummary(models.SubagentRun run) {
        var label = run.childAgent != null ? run.childAgent.name : "(unnamed)";
        var sb = new StringBuilder();
        sb.append("#").append(run.id)
                .append(' ').append(run.status.name())
                .append(' ').append(label)
                .append(" started=").append(run.startedAt != null ? run.startedAt.toString() : "?");
        if (run.endedAt != null) {
            var durSec = java.time.Duration.between(run.startedAt, run.endedAt).toSeconds();
            sb.append(" duration=").append(durSec).append('s');
        }
        return sb.toString();
    }

    /** Find the most recent SUBAGENT_SPAWN event for a given run id and
     *  return its {@code details} JSON, or null when no event exists yet. */
    private static String findSpawnEventDetails(Long runId) {
        var marker = "\"run_id\":\"" + runId + "\"";
        List<models.EventLog> rows = models.EventLog.<models.EventLog>find(
                "category = ?1 AND details LIKE ?2 ORDER BY timestamp DESC",
                "SUBAGENT_SPAWN", "%" + marker + "%").fetch(1);
        return rows.isEmpty() ? null : rows.getFirst().details;
    }

    /** Tiny JSON-field extractor — Gson is heavy for a single-key lookup
     *  in a known-shape payload. Returns null on missing key or unparseable
     *  input (the SUBAGENT_* details payload is always a flat object). */
    private static String extractJsonField(String json, String key) {
        if (json == null || json.isBlank()) return null;
        try {
            var obj = JsonParser.parseString(json).getAsJsonObject();
            if (!obj.has(key) || obj.get(key).isJsonNull()) return null;
            return obj.get(key).getAsString();
        } catch (Exception _) {
            return null;
        }
    }
}
