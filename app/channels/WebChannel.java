package channels;

import java.io.File;

/**
 * Web (in-app SPA) channel. The web UI has no outbound push: an agent's reply
 * is persisted to the DB by {@link agents.AgentRunner#run} and the browser
 * picks it up on the next conversation load / refresh. There is no socket to
 * deliver through, so the {@link Channel} send methods here are intentional
 * no-ops that report success — the message has already been "delivered" (to
 * the DB) by the time dispatch would run.
 *
 * <p>JCLAW-141: existed implicitly as a {@code null} from the old
 * {@code ChannelType.resolve()} switch, which forced every dispatch site to
 * special-case WEB. Making it a real {@link Channel} lets
 * {@link ChannelRegistry#forConversation} hand back a uniform instance so
 * dispatch needs no type branch.
 */
public class WebChannel implements Channel {

    private static final String WEB = "web";

    @Override
    public String channelName() { return WEB; }

    /**
     * No outbound transport for web — the reply is already in the DB. Reporting
     * OK keeps generic dispatch ({@link #sendText}) from logging a spurious
     * failure for a channel that, by design, never pushes.
     */
    @Override
    public SendResult trySend(String peerId, String text) {
        return SendResult.OK;
    }

    /** No-op (DB-backed delivery); reports success. See {@link #trySend}. */
    @Override
    public SendResult sendText(String peerId, String text) {
        return SendResult.OK;
    }

    /** Web has no native file-push path; reports success (DB-backed). */
    @Override
    public SendResult sendPhoto(String peerId, File file, String caption) {
        return SendResult.OK;
    }

    /** Web has no native file-push path; reports success (DB-backed). */
    @Override
    public SendResult sendDocument(String peerId, File file, String caption) {
        return SendResult.OK;
    }
}
