package memory;

import models.MemoryAuthorType;
import org.jspecify.annotations.Nullable;

import java.util.regex.Pattern;

/** Who the user turn of a captured memory speaks for, named for the extractor in place of "The user". */
public final class MemorySubject {

    private MemorySubject() {}

    // "[Ada Lovelace (id 42)]: hello", the attribution channels prefix onto a group message (JCLAW-367).
    private static final Pattern ATTRIBUTION = Pattern.compile("\\A\\[(.+?) \\(id [^)\\]]*\\)\\]:");
    // A guest chooses their display name and the extractor answers ownerName freely; both reach USER.md or the
    // instructions, so only a name-shaped value is used.
    private static final Pattern NAME_SHAPED = Pattern.compile("[\\p{L}\\p{M}\\p{N}][\\p{L}\\p{M}\\p{N} .'’-]{0,63}");
    private static final String NAME_STATEMENT = "(?:['’]s (?:full |first |last |preferred )?name\\b"
            + "| (?:goes by|is called|prefers to be called|wants to be called|asks to be called)\\b)";
    private static final Pattern THE_USER_STATES_NAME = Pattern.compile("(?i)\\bthe user" + NAME_STATEMENT);

    static final String OWNER_NAME_FIELD = "If the person speaking says what their own name is, or what to call them, "
            + "put that name in a top-level \"ownerName\" field beside \"memories\", and do not also write it as a memory.";

    /** Whether {@code value} looks like a person's name rather than arbitrary text. */
    public static boolean isNameShaped(@Nullable String value) {
        return value != null && NAME_SHAPED.matcher(value.strip()).matches();
    }

    /** The display name a group message is attributed to, or null for a message carrying none or no name-shaped one. */
    public static @Nullable String attributedName(@Nullable String userMessage) {
        if (userMessage == null) return null;
        var m = ATTRIBUTION.matcher(userMessage.strip());
        if (!m.find()) return null;
        var name = m.group(1).strip();
        return isNameShaped(name) ? name : null;
    }

    /** Whether {@code text} is a memory stating the owner's own name, which belongs in USER.md rather than in memory. */
    public static boolean statesOwnerName(String text, @Nullable String ownerName) {
        if (THE_USER_STATES_NAME.matcher(text).find()) return true;
        return ownerName != null
                && Pattern.compile("(?i)\\b" + Pattern.quote(ownerName) + NAME_STATEMENT).matcher(text).find();
    }

    /**
     * The directive appended to the extraction instructions. An owner's turn always asks for a stated name in
     * {@code ownerName}, and names the owner once USER.md does; a guest's turn names the guest and never asks.
     *
     * @param ownerName  the Name line of the agent's USER.md, or null
     * @param authorType the turn's author: a guest's turn is never the owner's
     */
    public static String directive(@Nullable String ownerName, @Nullable MemoryAuthorType authorType,
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
        if (ownerName == null) return OWNER_NAME_FIELD;
        return ("The person speaking in the user turn is %1$s, the owner of this agent. Wherever these instructions "
                + "say \"The user\", write \"%1$s\" instead: \"%1$s's son Theo goes by Bo\", \"%1$s prefers metric units\". ")
                .formatted(ownerName) + OWNER_NAME_FIELD;
    }
}
