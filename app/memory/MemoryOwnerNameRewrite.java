package memory;

import models.Agent;
import models.Memory;
import models.MemoryAuthorType;
import models.Message;
import services.EventLogger;
import services.Tx;
import services.WorkspaceFiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes the owner's name from USER.md in place of "the user" in an agent's active memories, once the name
 * is known, so memories captured before it and after it read alike. A guest's memory names the guest: the
 * display name its source message was attributed to, else "a guest". Superseded rows keep their text, except
 * that every memory merely stating the owner's name is removed: USER.md holds the name.
 */
public final class MemoryOwnerNameRewrite {

    private MemoryOwnerNameRewrite() {}

    private static final String EVENT_CATEGORY = "memory";
    static final String GUEST = "a guest";
    // "the user" not run into a longer word: "the username" and "the user-facing" are left alone.
    private static final Pattern POSSESSIVE = Pattern.compile("(?i)\\bthe user['’]s\\b");
    private static final Pattern PLAIN = Pattern.compile("(?i)\\bthe user\\b(?![-\\w'’])");

    /** {@code text} with "the user" written as {@code name}; a lower-case name takes a capital at a sentence start. */
    public static String named(String text, String name) {
        return replace(PLAIN, replace(POSSESSIVE, text, name + "'s"), name);
    }

    private static String replace(Pattern pattern, String text, String replacement) {
        var m = pattern.matcher(text);
        var out = new StringBuilder();
        while (m.find()) {
            var r = Character.isUpperCase(m.group().charAt(0)) && Character.isLowerCase(replacement.charAt(0))
                    ? Character.toUpperCase(replacement.charAt(0)) + replacement.substring(1)
                    : replacement;
            m.appendReplacement(out, Matcher.quoteReplacement(r));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * Removes the agent's memories that only state the owner's name, renames its active memories that say
     * "the user" and re-embeds each renamed one; returns how many were renamed. Does nothing while USER.md
     * names no owner, or while a capture holds the agent's lock.
     */
    public static int run(Agent agent) {
        var owner = WorkspaceFiles.ownerName(agent.name);
        if (owner == null) return 0;
        var lock = MemoryAutoCapture.captureLock(String.valueOf(agent.id));
        if (!lock.tryLock()) return 0;
        List<Long> changed;
        var removed = new int[1];
        try {
            changed = Tx.run(() -> {
                var ids = new ArrayList<Long>();
                // The name lives on USER.md's Name line now: a memory that only states it goes, superseded copies too.
                List<Memory> mentions = Memory.find("agent.id = ?1 AND (LOWER(text) LIKE ?2 OR LOWER(text) LIKE ?3)",
                        agent.id, "%the user%", "%" + owner.toLowerCase(Locale.ROOT) + "%").fetch();
                for (var memory : mentions) {
                    if (memory.authorType != MemoryAuthorType.GUEST_TURN && MemorySubject.statesOwnerName(memory.text, owner)) {
                        MemoryStoreFactory.get().delete(String.valueOf(memory.id));
                        removed[0]++;
                    }
                }
                List<Memory> rows = Memory.find("agent.id = ?1 AND supersededAt IS NULL AND LOWER(text) LIKE ?2",
                        agent.id, "%the user%").fetch();
                for (var memory : rows) {
                    var text = named(memory.text, memory.authorType == MemoryAuthorType.GUEST_TURN ? guestName(memory) : owner);
                    if (text.equals(memory.text)) continue;
                    memory.text = text;
                    memory.save();
                    ids.add(memory.id);
                }
                return ids;
            });
        } finally {
            lock.unlock();
        }
        // A text change replaces the index document without its vector; the embedding call runs outside any Tx.
        for (var id : changed) MemoryStoreFactory.get().embedStored(String.valueOf(id));
        if (!changed.isEmpty() || removed[0] > 0) {
            EventLogger.info(EVENT_CATEGORY, agent.name, null,
                    "Wrote the owner's name, %s, in place of \"the user\" in %d memories, and removed %d that only stated the name"
                            .formatted(owner, changed.size(), removed[0]));
        }
        return changed.size();
    }

    private static String guestName(Memory memory) {
        if (memory.sourceMessageId == null) return GUEST;
        Message source = Message.findById(memory.sourceMessageId);
        var shown = source == null ? null : MemorySubject.attributedName(source.content);
        return shown == null ? GUEST : shown;
    }
}
