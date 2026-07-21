package agents;

import llm.LlmProvider;
import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.ToolDef;
import llm.ProviderRegistry;
import models.Agent;
import models.Conversation;
import services.AttachmentService;
import services.ConversationService;
import services.EventLogger;
import services.SessionCompactor;
import services.Tx;
import utils.LatencyTrace;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * JCLAW-678: prologue preparation and media-capability orchestration for both
 * the synchronous and streaming agent paths. Runs the short JPA transaction that
 * assembles the system prompt, hydrates history into messages, resolves the
 * provider, and loads tool defs — then, outside any transaction, applies the
 * compression → compaction → context-window trim → audio/vision/video capability
 * rewrites before the LLM call.
 *
 * <p>Extracted from {@link AgentRunner}. The sync ({@link #prepareSyncData} +
 * {@link #rewriteSyncMedia}) and streaming ({@link #buildStreamingPrologue} +
 * {@link #applyMediaRewrite}) shapes are deliberately kept as separate methods:
 * the two paths pass different argument values (the sync path assembles with a
 * null {@code disabledTools} set and threads a once-resolved {@code modelInfo};
 * the streaming path loads {@code disabledTools} and recomputes capability
 * flags). Preserving each path's exact arguments is the responsibility-preserving
 * contract — see the JCLAW-678 blueprint risk note.
 *
 * <p>Tx-boundary invariant: {@link #prepareSyncData} / {@link #buildStreamingPrologue}
 * return fully-materialized, Tx-committed data. No lazily-loaded entity or open
 * transaction escapes the return, preserving the "no JDBC connection held during
 * the LLM/tool HTTP calls" design.
 */
final class AgentPromptPreparer {

    private AgentPromptPreparer() {}

    /**
     * Read-only prologue data computed for the synchronous path in a single
     * JPA transaction. Threads the resolved provider pair and the media bearers
     * through to the capability-rewrite step and the tool loop.
     */
    record PreparedData(
        List<ChatMessage> messages,
        LlmProvider primary,
        LlmProvider secondary,
        List<ToolDef> tools,
        List<VisionAudioAssembler.AudioBearer> audioBearers,
        List<VisionAudioAssembler.ImageBearer> imageBearers,
        List<VisionAudioAssembler.VideoBearer> videoBearers
    ) {}

    /**
     * Bundle of read-only prologue data computed in a single JPA transaction.
     * Used by the streaming path to fold what used to be 5+ separate
     * {@code Tx.run} blocks into one round-trip to the connection pool.
     */
    record PreparedPrologue(
        SystemPromptAssembler.AssembledPrompt assembled,
        List<ChatMessage> messages,
        List<ToolDef> tools,
        Set<String> disabledTools,
        List<VisionAudioAssembler.AudioBearer> audioBearers,
        List<VisionAudioAssembler.ImageBearer> imageBearers,
        List<VisionAudioAssembler.VideoBearer> videoBearers
    ) {}

    /**
     * Synchronous prologue Tx: persist the user message (unless
     * {@code skipUserAppend}), re-fetch the managed conversation, assemble the
     * system prompt + compaction summary + parent-context, hydrate messages and
     * tool definitions, and resolve the provider. Returns {@link Optional#empty()}
     * (after persisting the canned error via the sink) when no provider is configured.
     */
    static Optional<PreparedData> prepareSyncData(Agent agent, String userMessage,
                                        List<AttachmentService.Input> attachments,
                                        boolean skipUserAppend, Long conversationId,
                                        AgentExecutionSink sink, LatencyTrace trace) {
        return Tx.run(() -> {
            var conv = ConversationService.findById(conversationId);
            // JCLAW-273: skipUserAppend=true comes from runYieldResume — the
            // yield-resume announce was already persisted as a USER-role
            // Message before this call, so re-appending would duplicate the
            // row in both the chat scrollback and the LLM context.
            if (!skipUserAppend) {
                sink.appendUserMessage(userMessage, attachments);
            }
            trace.mark(LatencyTrace.PROLOGUE_CONV_RESOLVED);
            trace.agentId(AgentRunner.agentIdOf(agent));

            var assembled = SystemPromptAssembler.assemble(agent, userMessage, null, conv.channelType);
            // JCLAW-38: re-inject the latest compaction summary (if any)
            // into the system prompt so the LLM keeps continuity with
            // turns that have since been dropped from the raw history.
            var sysPrompt = SessionCompactor.appendSummaryToPrompt(assembled.systemPrompt(), conv);
            // JCLAW-268: re-inject the spawn-time parent-conversation context
            // for inherit-mode subagents. No-op for fresh-mode and non-subagent
            // conversations (parentContext is null).
            sysPrompt = SessionCompactor.appendParentContextToPrompt(sysPrompt, conv);
            var hydration = MessageHydrator.buildMessages(sysPrompt, conv);

            // JCLAW-108: resolve the provider from the effective provider name
            // (conversation override when set, agent default otherwise), not
            // from agent.modelProvider directly. Downstream helpers that take
            // (agent, conv, provider) compute their own effective model id.
            var agentProvider = ProviderRegistry.get(ModelResolver.effectiveModelProvider(agent, conv));
            var primary = agentProvider != null ? agentProvider : ProviderRegistry.getPrimary();
            if (primary == null) {
                var error = AgentRunner.NO_LLM_PROVIDER_ERROR;
                EventLogger.error("llm", agent.name, null, error);
                sink.appendAssistantMessage(error, null);
                return Optional.empty();
            }
            var secondary = ProviderRegistry.getSecondary();

            // Conversation-aware overload: lazy-load MCP tool schemas
            // based on which servers the model has discovered via
            // list_mcp_tools. Native tools always ship.
            var tools = ToolRegistry.getToolDefsForAgent(agent, conv);

            EventLogger.info("llm", agent.name, conv.channelType,
                    "Calling %s / %s".formatted(primary.config().name(), ModelResolver.effectiveModelId(agent, conv)));

            return Optional.of(new PreparedData(hydration.messages(), primary, secondary, tools,
                    hydration.audioBearers(), hydration.imageBearers(), hydration.videoBearers()));
        });
    }

    /**
     * Synchronous capability rewrite (outside any Tx): compress TOOL-role
     * outputs, compact older turns if over budget, trim to the context window,
     * then rewrite audio / image / video attachments for models that lack the
     * matching capability. Returns a new {@link PreparedData} with the rewritten
     * message list; every other field is threaded through unchanged.
     */
    static PreparedData rewriteSyncMedia(PreparedData prepared, Agent agent, Conversation conversation,
                                         Long conversationId, String userMessage) {
        // JCLAW-38: if the just-built context exceeds the compaction
        // budget, summarize older turns (LLM call, outside Tx) and
        // rebuild. trimToContextWindow below stays as a drop-oldest
        // fallback for when compaction is skipped (too few turns) or
        // fails.
        // JCLAW-465: content-aware compression of TOOL-role outputs, before
        // compaction so the budget check sees the smaller payload. No-op
        // unless chat.compression.enabled=true. When compaction fires it
        // rebuilds from originals; trimToContextWindow below stays the net.
        var compressedMessages = CompressionPipeline.compress(prepared.messages(), agent, conversation);
        var compactedMessages = CompactionGate.maybeCompactAndRebuild(
                agent, conversationId, userMessage, null,
                prepared.primary(), compressedMessages, prepared.tools());
        var finalMessages = ContextWindowManager.trimToContextWindow(compactedMessages, agent, conversation,
                prepared.primary(), prepared.tools());
        // JCLAW-165: when the active model lacks supportsAudio, await
        // any in-flight transcription futures and rewrite the user
        // messages as text-with-transcript before the LLM call. The
        // audio-capable happy path is a no-op and pays zero added latency.
        var modelInfoForAudio = ModelResolver.resolveModelInfo(agent, conversation, prepared.primary()).orElse(null);
        var supportsAudioForCall = modelInfoForAudio != null && modelInfoForAudio.supportsAudio();
        finalMessages = VisionAudioAssembler.applyTranscriptsForCapability(finalMessages, prepared.audioBearers(), supportsAudioForCall);
        // JCLAW-215: when the active model lacks supportsVision, caption any
        // image attachments (outside Tx) and rewrite the user messages as
        // text-with-caption. supportsAudioForCall is threaded so a turn with
        // both an image and a downgraded voice note rebuilds correctly.
        var supportsVisionForCall = modelInfoForAudio != null && modelInfoForAudio.supportsVision();
        finalMessages = VisionAudioAssembler.applyCaptionsForCapability(finalMessages, prepared.imageBearers(), supportsVisionForCall, supportsAudioForCall);
        // JCLAW-224: route any video attachments through the dispatcher (native-video /
        // multi-image / text-summary) and splice the content parts in. supportsVision/Audio
        // are threaded so a co-attached downgraded image / voice note survives the rebuild.
        finalMessages = VisionAudioAssembler.applyVideoForCapability(finalMessages, prepared.videoBearers(), agent, supportsAudioForCall, supportsVisionForCall);
        return new PreparedData(finalMessages, prepared.primary(), prepared.secondary(), prepared.tools(), prepared.audioBearers(), prepared.imageBearers(), prepared.videoBearers());
    }

    /**
     * Run the streaming prologue Tx: load disabled tools, fetch the managed conversation, assemble
     * the system prompt + compaction summary + parent-context, hydrate messages and
     * tool definitions. Fold everything into ONE transaction so nested Tx.run calls
     * inside helpers don't open additional connections.
     *
     * <p>{@code channelType} is the inbound surface of <em>this</em> turn, which
     * drives the assembler's channel guidance. It is normally identical to the
     * conversation's stored {@code channelType}; voice mode is the exception —
     * the turn is tagged {@code "voice"} while its history lives on the shared
     * {@code "web"} conversation, so the model gets spoken-conversation guidance.
     * Falls back to the stored channel when null.
     */
    static PreparedPrologue buildStreamingPrologue(Agent agent, Conversation conversation,
                                                   String channelType, String userMessage) {
        return Tx.run(() -> {
            var disabledTools = ToolRegistry.loadDisabledTools(agent);
            var convo = ConversationService.findById(conversation.id);
            var promptChannel = channelType != null ? channelType : convo.channelType;
            var assembled0 = SystemPromptAssembler.assemble(agent, userMessage, disabledTools, promptChannel);
            // JCLAW-38: re-inject latest compaction summary (if any)
            var sysPrompt = SessionCompactor.appendSummaryToPrompt(assembled0.systemPrompt(), convo);
            // JCLAW-268: re-inject spawn-time parent context for inherit-mode subagents.
            sysPrompt = SessionCompactor.appendParentContextToPrompt(sysPrompt, convo);
            var hydration = MessageHydrator.buildMessages(sysPrompt, convo);
            // Conversation-aware overload: applies the loadtest-agent
            // short-circuit AND the lazy MCP discovery gate (only ship
            // schemas for servers the model has called list_mcp_tools on).
            var toolDefs = ToolRegistry.getToolDefsForAgent(agent, convo);
            return new PreparedPrologue(assembled0, hydration.messages(), toolDefs, disabledTools,
                    hydration.audioBearers(), hydration.imageBearers(), hydration.videoBearers());
        });
    }

    /**
     * Streaming capability rewrite. Compaction + context-window trim + audio-capability rewrite.
     * JCLAW-165: when the active model lacks {@code supportsAudio}, rewrite audio messages to
     * text-with-transcript before the LLM call (no-op on audio-capable models).
     */
    static List<ChatMessage> applyMediaRewrite(Agent agent, Conversation conversation,
                                               String userMessage, LlmProvider primary,
                                               PreparedPrologue prepared,
                                               boolean supportsAudioForStream,
                                               boolean supportsVisionForStream) {
        // JCLAW-38: if the just-built context exceeds the compaction budget,
        // summarize older turns (LLM call, outside Tx) and rebuild.
        // trimToContextWindow below stays as a drop-oldest fallback for
        // when compaction is skipped or fails.
        // JCLAW-465: same content-aware compression hook on the streaming path.
        var compressedMessages = CompressionPipeline.compress(prepared.messages(), agent, conversation);
        var compactedMessages = CompactionGate.maybeCompactAndRebuild(
                agent, conversation.id, userMessage, prepared.disabledTools(),
                primary, compressedMessages, prepared.tools());
        var trimmedMessages = ContextWindowManager.trimToContextWindow(compactedMessages, agent, conversation,
                primary, prepared.tools());
        var rewritten = VisionAudioAssembler.applyTranscriptsForCapability(trimmedMessages, prepared.audioBearers(),
                supportsAudioForStream);
        // JCLAW-215: caption image attachments for non-vision models, mirroring
        // the audio downgrade above.
        var captioned = VisionAudioAssembler.applyCaptionsForCapability(rewritten, prepared.imageBearers(),
                supportsVisionForStream, supportsAudioForStream);
        // JCLAW-224: route video attachments through the dispatcher on the streaming path too.
        return VisionAudioAssembler.applyVideoForCapability(captioned, prepared.videoBearers(), agent,
                supportsAudioForStream, supportsVisionForStream);
    }
}
