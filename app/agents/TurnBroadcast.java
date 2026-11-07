package agents;

import models.Agent;
import models.Conversation;
import org.jspecify.annotations.Nullable;
import services.ConversationTurnStream;
import services.EventLogger;
import utils.AppClock;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One turn's frames on its conversation's {@link ConversationTurnStream}, each built by {@link TurnFrames}. A frame
 * that cannot be built or published is logged and dropped: watching a turn must never break it.
 *
 * <p>An owning broadcast is the turn's own: it begins the turn when the turn acquires its conversation and publishes
 * its one terminal frame, and its {@code init} carries when it began, for a view that joins the turn late (JF-202). A
 * joining one adds frames to the turn open in the conversation when it was made, an inline subagent's in its parent's
 * or a coding run's in the turn that started it, and never ends it; once that turn ends it publishes nothing, so a
 * later turn never gets its frames. Once the turn's cancel flag is set, its next frame of any kind ends it as stopped
 * instead: what it had not committed is gone, and a reply after the flag is one the fenced sink dropped.
 */
public final class TurnBroadcast {

    private final boolean owns;
    private final @Nullable AtomicBoolean cancelled;
    private volatile ConversationTurnStream.@Nullable Turn turn;
    private volatile @Nullable Instant startedAt;

    private TurnBroadcast(boolean owns, @Nullable AtomicBoolean cancelled) {
        this.owns = owns;
        this.cancelled = cancelled;
    }

    /** A streamed turn's, cancelled by {@code isCancelled}; it publishes nothing until {@link #acquired}. */
    static TurnBroadcast forStreamedTurn(AtomicBoolean isCancelled) {
        return new TurnBroadcast(true, isCancelled);
    }

    /** A turn that already owns {@code conversationId} at {@code generation}, with no cancel flag: it begins now. */
    public static TurnBroadcast owning(Long conversationId, long generation) {
        return owning(conversationId, generation, null);
    }

    /** {@link #owning(Long, long)} for a turn cancelled by {@code cancelled}, its owner's flag (JF-197). */
    public static TurnBroadcast owning(Long conversationId, long generation, @Nullable AtomicBoolean cancelled) {
        var broadcast = new TurnBroadcast(true, cancelled);
        broadcast.acquired(conversationId, generation);
        return broadcast;
    }

    /** Frames for the turn open in {@code conversationId} now, if any; never begins or ends one. */
    public static TurnBroadcast joining(@Nullable Long conversationId) {
        var broadcast = new TurnBroadcast(false, null);
        if (conversationId != null) broadcast.turn = ConversationTurnStream.current(conversationId);
        return broadcast;
    }

    /**
     * {@code cb}, with each token, reasoning, status and tool call it gets also published to the turn open in
     * {@code conversationId}: for frames that reach a chat stream outside the turn's own callbacks, a coding run's
     * harness events and the subagent wait relay. Its init and terminal callbacks are {@code cb}'s own.
     */
    public static AgentRunner.StreamingCallbacks alsoPublishing(Long conversationId, AgentRunner.StreamingCallbacks cb) {
        var broadcast = joining(conversationId);
        return new AgentRunner.StreamingCallbacks(
                cb.onInit(),
                token -> {
                    broadcast.token(token);
                    cb.onToken().accept(token);
                },
                reasoning -> {
                    broadcast.reasoning(reasoning);
                    cb.onReasoning().accept(reasoning);
                },
                status -> {
                    broadcast.status(status);
                    cb.onStatus().accept(status);
                },
                toolCall -> {
                    broadcast.toolCall(toolCall);
                    cb.onToolCall().accept(toolCall);
                },
                cb.onComplete(),
                cb.onError(),
                cb.onCancel());
    }

    /** Begin the turn as it acquires {@code conversationId} at {@code generation}, before it writes anything. */
    void acquired(Long conversationId, long generation) {
        startedAt = AppClock.now();
        try {
            turn = ConversationTurnStream.begin(conversationId, generation);
        } catch (RuntimeException e) {
            EventLogger.warn("turn-stream", "A turn could not begin on its conversation's stream: %s"
                    .formatted(e.getMessage()));
        }
    }

    public void init(Agent agent, Conversation conversation) {
        // Read once the turn is open, which only acquired opens after setting it; a queued turn's init reaches here too.
        if (owns) publish(open -> open.publishInit(TurnFrames.json(
                TurnFrames.init(agent, conversation, Objects.requireNonNull(startedAt)))));
    }

    public void token(String content) {
        publish(open -> {
            if (open.claimFirstToken()) open.publish(TurnFrames.json(TurnFrames.firstToken(content)));
            else open.publishDelta(TurnFrames.Chunk.TOKEN, content);
        });
    }

    public void reasoning(String content) {
        publish(open -> open.publishDelta(TurnFrames.Chunk.REASONING, content));
    }

    public void status(String content) {
        publish(open -> open.publish(TurnFrames.json(TurnFrames.status(content))));
    }

    public void toolCall(AgentRunner.ToolCallEvent event) {
        publish(open -> open.publish(TurnFrames.json(TurnFrames.toolCall(event))));
    }

    public void complete(String content) {
        end(() -> TurnFrames.json(TurnFrames.complete(content)));
    }

    public void error(Throwable error) {
        end(() -> TurnFrames.json(TurnFrames.error(error)));
    }

    public void stopped() {
        var open = owns ? openTurn() : null;
        if (open == null) return;
        try {
            open.stop();
        } catch (RuntimeException e) {
            EventLogger.warn("turn-stream", "A turn could not be ended as stopped: %s".formatted(e.getMessage()));
        }
    }

    /**
     * End a sync turn by how it ended: its reply, empty for a yield as a streamed yield's is; {@code stopped} for a
     * subagent kill; an {@code error} for any other failure, or for none recorded, an {@link Error} escaping.
     */
    public void endWith(AgentRunner.@Nullable RunResult result, @Nullable RuntimeException failure) {
        if (failure instanceof RunCancelledException) stopped();
        else if (failure != null) error(failure);
        else if (result == null) error(new IllegalStateException("the turn ended without a reply"));
        else complete(result.yielded() ? "" : result.response());
    }

    /** Whether the turn this publishes to is still open; a joining broadcast made with none open never is. */
    public boolean isOpen() {
        return openTurn() != null;
    }

    /** A streamed turn that reached no terminal callback still ends, so no view waits on it. */
    void endIfOpen() {
        if (owns && openTurn() != null) error(new IllegalStateException("the turn ended without a reply"));
    }

    private boolean cancelled() {
        return cancelled != null && cancelled.get();
    }

    private void publish(Consumer<ConversationTurnStream.Turn> frame) {
        var open = openTurn();
        if (open == null) return;
        if (cancelled()) {
            stopped();
            return;
        }
        try {
            frame.accept(open);
        } catch (RuntimeException e) {
            EventLogger.warn("turn-stream", "A frame of a turn could not be published: %s".formatted(e.getMessage()));
        }
    }

    private void end(Supplier<byte[]> terminal) {
        var open = owns ? openTurn() : null;
        if (open == null) return;
        if (cancelled()) {
            stopped();
            return;
        }
        try {
            open.end(terminal.get());
        } catch (RuntimeException e) {
            EventLogger.warn("turn-stream", "A turn's terminal frame could not be published: %s"
                    .formatted(e.getMessage()));
            stopped();
        }
    }

    private ConversationTurnStream.@Nullable Turn openTurn() {
        var known = turn;
        return known != null && known.isOpen() ? known : null;
    }
}
