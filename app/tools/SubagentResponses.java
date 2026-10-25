package tools;

import agents.AgentRunner;
import agents.DangerousActionGate;
import agents.ToolContext;
import models.Agent;
import models.Conversation;
import models.Message;
import models.MessageRole;
import models.SubagentRun;
import org.jspecify.annotations.Nullable;
import services.ConversationQueue;
import services.ConversationService;
import services.EventLogger;
import services.Tx;
import tools.SubagentSpawnTool.SyncRunOutcome;
import utils.GsonHolder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JCLAW-677: response shaping into the parent transcript, extracted from
 * {@link SubagentSpawnTool}: inline start/end boundary markers, terminal
 * lifecycle events, the async announce card, and the yielded-parent resume.
 */
final class SubagentResponses {

    /** What a chat receives when a background run fails or times out, unless the owner reads it alone. */
    static final String FAILURE_NOTICE = "A background task did not finish. Ask me if you want the details.";

    private SubagentResponses() {}

    /**
     * JCLAW-267: inline-mode boundary-start marker. Written into the parent
     * Conversation BEFORE the child reasons so the chat UI's collapsible
     * block can fold from this marker forward. The marker is an
     * assistant-role row carrying the task instruction and stamped with the
     * SubagentRun id so it groups with the child's own messages. The start
     * marker's content becomes the header label on the collapsed block.
     */
    static boolean writeInlineStartMarker(Long parentConvId, Long runId,
                                          @Nullable String label, @Nullable String task) {
        return ConversationQueue.commitUnlessCancelled(parentConvId, ToolContext.turnCancel(), () -> Tx.run(() -> {
            var conv = Conversation.<Conversation>findById(parentConvId);
            ConversationService.withSubagentRunIdMarker(runId, () -> {
                var startContent = "Spawning subagent: "
                        + (label != null && !label.isBlank() ? label + " — " : "")
                        + task;
                ConversationService.appendAssistantMessage(conv, startContent, null);
                return null;
            });
        }));
    }

    /**
     * JCLAW-267: inline-mode boundary-end marker. Written into the parent
     * Conversation AFTER the child run terminates so the chat UI's
     * collapsible block has a clear end. The marker carries the terminal
     * status in its content so the collapsed header can render
     * "Completed / Failed / Timed out" without a separate join to
     * SubagentRun.
     *
     * <p>JCLAW-291: when killed by operator, the kill confirmation in the
     * operator's slash-command response is the user-facing signal; the
     * caller skips this writer so a kill doesn't double-render as both
     * "Killed by operator" and a synthesized "Subagent killed" line.
     */
    static void writeInlineEndMarker(Long parentConvId, Long runId,
                                     SubagentRun.Status status, String reply,
                                     boolean replyTruncated) {
        Tx.run(() -> {
            var conv = Conversation.<Conversation>findById(parentConvId);
            ConversationService.withSubagentRunIdMarker(runId, () -> {
                var endContent = "Subagent " + status.name().toLowerCase()
                        + (reply != null && !reply.isBlank() ? ": " + reply : "");
                // JCLAW-291: stamp truncated on the inline run's terminal
                // marker so the chat UI surfaces the marker on the inline
                // subagent block's last assistant row.
                ConversationService.appendAssistantMessage(conv, endContent, null, null, null,
                        replyTruncated);
                return null;
            });
        });
    }

    static void emitTerminalEvent(String parentAgentName, @Nullable String childName,
                                  String runIdStr, @Nullable String mode, @Nullable String context,
                                  SubagentRun.Status status, @Nullable String errorReason) {
        switch (status) {
            case COMPLETED -> EventLogger.recordSubagentComplete(
                    parentAgentName, childName, runIdStr, mode, context, "ok");
            case TIMEOUT -> EventLogger.recordSubagentTimeout(parentAgentName, runIdStr);
            default -> EventLogger.recordSubagentError(
                    parentAgentName, childName, runIdStr,
                    mode, context, errorReason);
        }
    }

    /**
     * JCLAW-273: read the yield flag inside the same Tx that persists the
     * announce so the role-decision and the message insert see a consistent
     * snapshot of {@link SubagentRun#yielded}. A parent that called
     * subagent_yield expects USER-role delivery (the announce IS its next
     * user message); a fire-and-forget async caller expects SYSTEM-role
     * (the JCLAW-270 card, which the model reads as its own earlier reply,
     * JCLAW-1602).
     *
     * <p>NB: the local {@code displayTruncatedBody} is the 4000-char display
     * cap on the announce body, NOT the JCLAW-291 model-output truncation
     * flag. The two flow through {@link #postAnnounceMessage} as separate
     * parameters.
     *
     * <p>A fire-and-forget announce is also sent, after the commit, as plain text to the parent
     * conversation's channel: nothing else carries it to Telegram, Slack or WhatsApp. Web chat
     * has no peer and stays card-only.
     * A yielded announce sends nothing here; the resumed reply is the message.
     *
     * <p>A failure's text is an exception message, so it is sent only when the owner started the
     * run in a chat that is not a group; any other chat gets {@link #FAILURE_NOTICE} (JCLAW-1603).
     * The saved row keeps the text, and the model reads it there. The async thread inherits the
     * spawning turn's owner flag.
     */
    @SuppressWarnings("java:S1181")
    static Boolean postAnnounceAndReadYieldFlag(Long runId, @Nullable Long childConvId,
                                                Long parentConvId, @Nullable String label,
                                                SyncRunOutcome outcome) {
        var status = outcome.terminalStatus();
        var failureBody = outcome.errorReason() != null ? outcome.errorReason() : "";
        // JCLAW-424 (AC5): on a timeout, append the child's partial output (carried
        // on outcome.reply()) to the timeout reason so the async parent salvages it
        // too — the reason itself is preserved for the announce's terminal semantics.
        var failureOrPartialBody = status == SubagentRun.Status.TIMEOUT && SubagentSpawnTool.notBlank(outcome.reply())
                ? failureBody + "\n\nPartial output before timeout:\n" + outcome.reply()
                : failureBody;
        var announceBody = status == SubagentRun.Status.COMPLETED
                ? outcome.reply()
                : failureOrPartialBody;
        var displayTruncatedBody = truncateForAnnounce(announceBody);
        boolean displayCut = announceBody != null
                && announceBody.length() > SubagentSpawnTool.ANNOUNCE_REPLY_MAX_CHARS;
        final var modelOutputTruncated = outcome.replyTruncated();
        try {
            var delivery = new AtomicReference<@Nullable PostedAnnounce>();
            boolean yielded = Tx.run(() -> {
                var run = (SubagentRun) SubagentRun.findById(runId);
                boolean isYielded = run != null && run.yielded;
                delivery.set(postAnnounceMessage(parentConvId, runId, label, status,
                        displayTruncatedBody, displayCut, childConvId, isYielded, modelOutputTruncated));
                return isYielded;
            });
            var posted = delivery.get();
            if (!yielded && posted != null) {
                var text = status == SubagentRun.Status.COMPLETED
                        || DangerousActionGate.ownerReadsAlone(parentConvId, posted.channelType(), posted.peerId())
                        ? posted.text()
                        : FAILURE_NOTICE;
                AgentRunner.dispatchToChannel(posted.agent(), posted.channelType(), posted.peerId(), text);
            }
            return yielded;
        } catch (Throwable t) {
            EventLogger.warn(SubagentSpawnTool.SUBAGENT_CHANNEL,
                    "Failed to post announce Message for run " + runId
                            + ": " + t.getMessage());
            return Boolean.FALSE;
        }
    }

    /** Where a saved announce row can be sent, read inside the transaction that saved it. */
    private record PostedAnnounce(Agent agent, String channelType, @Nullable String peerId, String text) {}

    /**
     * JCLAW-273: re-invoke {@link AgentRunner#resumeAndDeliver} on the parent
     * conversation after a yielded async run terminates. The announce
     * Message has already been persisted as a USER-role row (see
     * {@link #postAnnounceMessage}); the resume entrypoint runs the
     * standard prompt-assembly + LLM pipeline against the now-extended
     * conversation history WITHOUT re-appending a user message (which
     * would duplicate the announce). The LLM picks up the announce via
     * {@link services.ConversationService#loadRecentMessages}.
     *
     * <p>Runs in the announce VT (so the call is naturally outside any
     * other turn's queue acquisition). Failures are caught + logged at the
     * caller — losing the resume must not lose the audit row or the
     * announce.
     *
     * <p>{@code resumeAndDeliver} also sends the resumed reply to the parent's channel; a resume
     * queued behind a busy conversation is sent by the queue's drain instead.
     */
    static void resumeParentAfterYield(Long parentConvId, Long runId) {
        var conv = Tx.run(() -> (Conversation) Conversation.findById(parentConvId));
        if (conv == null) {
            EventLogger.warn(SubagentSpawnTool.SUBAGENT_CHANNEL,
                    "Yielded resume skipped: parent conversation " + parentConvId
                            + " not found for run " + runId);
            return;
        }
        var parentAgent = Tx.run(() -> {
            var c = (Conversation) Conversation.findById(parentConvId);
            return c != null ? c.agent : null;
        });
        if (parentAgent == null) {
            EventLogger.warn(SubagentSpawnTool.SUBAGENT_CHANNEL,
                    "Yielded resume skipped: no agent on parent conversation " + parentConvId
                            + " for run " + runId);
            return;
        }
        AgentRunner.resumeAndDeliver(parentAgent, conv);
    }

    /**
     * Hard-truncate to {@link SubagentSpawnTool#ANNOUNCE_REPLY_MAX_CHARS} with an
     * ellipsis marker so the reader can tell the preview is bounded. The full reply
     * stays accessible via the announce card's "View full" link to the
     * child Conversation.
     */
    private static String truncateForAnnounce(String s) {
        if (s == null) return "";
        if (s.length() <= SubagentSpawnTool.ANNOUNCE_REPLY_MAX_CHARS) return s;
        // Reserve 3 chars for the ellipsis so the visible char count
        // including the marker matches ANNOUNCE_REPLY_MAX_CHARS exactly.
        return s.substring(0, SubagentSpawnTool.ANNOUNCE_REPLY_MAX_CHARS - 3) + "...";
    }

    /**
     * Persist the announce Message into the parent Conversation. The role
     * depends on whether the parent yielded into this run (JCLAW-273): a
     * fire-and-forget async caller gets a {@link MessageRole#SYSTEM}-role
     * row, which the LLM reads as its own earlier reply, while a
     * yielded caller gets a {@link MessageRole#USER}-role row that the LLM
     * sees as its next user-role input on resume. Either way the row is
     * stamped with {@link SubagentSpawnTool#MESSAGE_KIND_ANNOUNCE} so the chat UI
     * renders the same structured card (run id, label, status, truncated reply,
     * "View full" link to the child Conversation).
     *
     * <p>{@code content} carries a plain-text fallback for transports that
     * don't understand the card. The plain text is what the LLM sees in
     * the rebuilt context — keep it the rich-prose form (status + reply)
     * so a model that doesn't understand the metadata still gets a
     * coherent turn. When the reply was cut for display it says so and
     * names the run, since a bare ellipsis reads as the whole reply.
     *
     * @return the parent's channel and the plain text, or null when the parent conversation is gone
     */
    private static @Nullable PostedAnnounce postAnnounceMessage(Long parentConvId, Long runId, @Nullable String label,
                                            SubagentRun.Status status, String truncatedReply,
                                            boolean replyCut, @Nullable Long childConvId, boolean yielded,
                                            boolean modelOutputTruncated) {
        var parentConv = (Conversation) Conversation.findById(parentConvId);
        if (parentConv == null) return null;

        var payload = new LinkedHashMap<String, Object>();
        payload.put(SubagentSpawnTool.BUS_RUN_ID, runId);
        payload.put(SubagentSpawnTool.FIELD_LABEL, label != null ? label : "");
        payload.put(SubagentSpawnTool.FIELD_STATUS, status.name());
        payload.put(SubagentSpawnTool.FIELD_REPLY, truncatedReply != null ? truncatedReply : "");
        payload.put("childConversationId", childConvId);
        payload.put("yielded", yielded);
        // JCLAW-291: separate from {@code truncatedReply} (which is the
        // 4000-char display cap on the announce body) — this flag means the
        // CHILD'S underlying reply was cut off by max_tokens. The chat-page
        // announce card reads this and renders a "Reply was truncated by
        // the model" marker.
        if (modelOutputTruncated) payload.put(SubagentSpawnTool.FIELD_TRUNCATED, Boolean.TRUE);

        var fallbackLabel = label != null && !label.isBlank() ? label : "subagent run";
        var fallback = "Subagent " + status.name().toLowerCase() + " (" + fallbackLabel + ")"
                + (truncatedReply != null && !truncatedReply.isBlank()
                        ? ": " + truncatedReply
                        : "")
                + (replyCut
                        ? " [cut at " + SubagentSpawnTool.ANNOUNCE_REPLY_MAX_CHARS + " characters; subagent run "
                                + runId + "'s conversation has the full text]"
                        : "");

        var msg = new Message();
        msg.conversation = parentConv;
        msg.role = yielded ? MessageRole.USER.value : MessageRole.SYSTEM.value;
        msg.content = fallback;
        msg.messageKind = SubagentSpawnTool.MESSAGE_KIND_ANNOUNCE;
        msg.metadata = GsonHolder.GSON.toJson(payload, Map.class);
        // JCLAW-291: also stamp the column on the announce row itself so
        // queries that count truncated messages see it without parsing JSON.
        msg.truncated = modelOutputTruncated;
        msg.save();

        parentConv.messageCount++;
        parentConv.save();
        return new PostedAnnounce(parentConv.agent, parentConv.channelType, parentConv.peerId, fallback);
    }
}
