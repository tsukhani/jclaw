package memory;

import models.MemoryAuthorType;
import org.jspecify.annotations.Nullable;

import java.util.regex.Pattern;

/** Who the user turn of a captured memory speaks for, named for the extractor in place of "The user". */
public final class MemorySubject {

    private MemorySubject() {}

    // "[Ada Lovelace (id 42)]: hello", the attribution channels prefix onto a group message (JCLAW-367).
    private static final Pattern ATTRIBUTION = Pattern.compile("\\A\\[(.+?) \\(id [^)\\]]*\\)\\]:");
    // The guest chooses their display name and it reaches the extractor's instructions, so only a name-shaped one is used.
    private static final Pattern NAME_SHAPED = Pattern.compile("[\\p{L}\\p{M}\\p{N}][\\p{L}\\p{M}\\p{N} .'’-]{0,63}");

    /** The display name a group message is attributed to, or null for a message carrying none or no name-shaped one. */
    public static @Nullable String attributedName(@Nullable String userMessage) {
        if (userMessage == null) return null;
        var m = ATTRIBUTION.matcher(userMessage.strip());
        if (!m.find()) return null;
        var name = m.group(1).strip();
        return NAME_SHAPED.matcher(name).matches() ? name : null;
    }

    /**
     * The directive appended to the extraction instructions, or null to leave their "The user" as it is:
     * an owner's turn while USER.md names no owner.
     *
     * @param ownerName  the Name line of the agent's USER.md, or null
     * @param authorType the turn's author: a guest's turn is never the owner's
     */
    public static @Nullable String directive(@Nullable String ownerName, @Nullable MemoryAuthorType authorType,
                                             @Nullable String userMessage) {
        if (authorType == MemoryAuthorType.GUEST_TURN) {
            var shown = attributedName(userMessage);
            var fallback = shown == null
                    ? "\"a guest\" (\"A guest\" at the start of a sentence)"
                    : "\"%s\", the name their message is attributed to".formatted(shown);
            return ("The person speaking in the user turn is a guest, not the owner of this agent. Wherever these "
                    + "instructions say \"The user\", name the guest instead: by the name they give in this turn if "
                    + "they give one, otherwise as %s. Never write a guest's words as a fact about the owner.")
                    .formatted(fallback);
        }
        if (ownerName == null) return null;
        return ("The person speaking in the user turn is %1$s, the owner of this agent. Wherever these instructions "
                + "say \"The user\", write \"%1$s\" instead: \"%1$s's son Theo goes by Bo\", \"%1$s prefers metric units\".")
                .formatted(ownerName);
    }
}
