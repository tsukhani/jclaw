package models;

import org.jspecify.annotations.Nullable;

/**
 * Which guests' memories a turn may read (JF-147). A {@link MemoryAuthorType#GUEST_TURN} row is
 * visible only in a chat with the channel and peer it came from; every other row, a null author
 * included, is visible everywhere. The peer is the chat, so a group's guest rows are the group's,
 * and {@code /new} keeps them because it keeps the peer.
 */
public record MemoryScope(@Nullable String channel, @Nullable String peer) {

    /** No chat behind the read — a task fire, an introspection: no guest row is visible. */
    public static final MemoryScope NONE = new MemoryScope(null, null);

    public static MemoryScope of(@Nullable Conversation conversation) {
        return conversation == null ? NONE : new MemoryScope(conversation.channelType, conversation.peerId);
    }

    /**
     * What a new memory is compared against for dedup and supersession. A guest's write is
     * compared against what its chat sees; anyone else's against no guest row, so the operator's
     * fact is stored as the operator's even where a guest already said it.
     */
    public static MemoryScope forWrite(@Nullable MemoryAuthorType author, @Nullable Conversation source) {
        return author == MemoryAuthorType.GUEST_TURN ? of(source) : NONE;
    }

    /** Whether any guest row can be visible here: only a chat with a known channel and peer admits one. */
    public boolean admitsGuestRows() {
        return channel != null && peer != null;
    }
}
