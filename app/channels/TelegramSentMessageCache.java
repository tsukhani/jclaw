package channels;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/**
 * Bounded, thread-safe cache of the message ids this bot sent, keyed by chat
 * (JCLAW-383; extracted from {@code TelegramSender} in JCLAW-702).
 *
 * <p>To make {@code telegram.reactions.notify=own} work in groups (where the
 * reacted message's author is NOT carried on the {@code message_reaction}
 * update), we remember the ids of messages THIS bot sent, per chat, so the
 * reaction gate can ask "was this message one of ours?". The cache is bounded
 * two ways so it can't grow without limit on a busy multi-chat bot:
 * <ul>
 *   <li>at most {@link TelegramChannel#SENT_CHATS_CAP} chats are tracked
 *       (access-ordered LRU: the least-recently-touched chat is evicted first);</li>
 *   <li>within each chat, at most {@link TelegramChannel#SENT_IDS_PER_CHAT_CAP}
 *       message ids are retained (insertion-ordered FIFO: the oldest id is
 *       evicted).</li>
 * </ul>
 * It populates only from sends this process made, so it is cold after a restart
 * — acceptable: a cold miss under-notifies (conservative), it never
 * over-notifies. See {@link #wasSent}.
 */
final class TelegramSentMessageCache {

    /**
     * chatId → ring of recently bot-sent message ids in that chat. Outer map is
     * access-ordered (LRU on the chat key); each inner set is a bounded
     * insertion-ordered FIFO. All access is guarded by synchronizing on
     * {@code sentByChat} itself — sends and the reaction-gate read happen on
     * different threads.
     */
    private final LinkedHashMap<String, LinkedHashSet<Integer>> sentByChat =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<String, LinkedHashSet<Integer>> eldest) {
                    return size() > TelegramChannel.SENT_CHATS_CAP;
                }
            };

    /**
     * JCLAW-383: record that this bot sent {@code messageId} into {@code chatId},
     * so a later reaction on it can be recognized as a reaction on a bot message
     * (the signal {@code notify=own} needs in a group). No-op on null/blank chat
     * id or null message id. The per-chat ring is bounded to
     * {@link TelegramChannel#SENT_IDS_PER_CHAT_CAP} (oldest id evicted) and the
     * number of chats to {@link TelegramChannel#SENT_CHATS_CAP} (coldest chat
     * evicted).
     */
    void remember(String chatId, Integer messageId) {
        if (chatId == null || chatId.isBlank() || messageId == null) return;
        synchronized (sentByChat) {
            var ring = sentByChat.computeIfAbsent(chatId, _ ->
                    new LinkedHashSet<>() {
                        @Override
                        public boolean add(Integer id) {
                            boolean added = super.add(id);
                            // Insertion-ordered FIFO: drop the oldest id once over cap.
                            if (size() > TelegramChannel.SENT_IDS_PER_CHAT_CAP) {
                                var it = iterator();
                                it.next();
                                it.remove();
                            }
                            return added;
                        }
                    });
            ring.add(messageId);
        }
    }

    /**
     * JCLAW-383: true when {@code messageId} in {@code chatId} is a message this
     * bot sent (and is still in the bounded cache). False on any null arg, a
     * never-seen chat, an evicted id, or a cold cache after a restart — a false
     * here makes the reaction gate under-notify (conservative), never
     * over-notify.
     */
    boolean wasSent(String chatId, Integer messageId) {
        if (chatId == null || messageId == null) return false;
        synchronized (sentByChat) {
            var ring = sentByChat.get(chatId);
            return ring != null && ring.contains(messageId);
        }
    }
}
