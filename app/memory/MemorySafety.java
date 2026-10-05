package memory;

import java.util.Collections;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic content guards for the memory capture path. The extraction
 * prompt already asks the model not to capture secrets or instructions, but a
 * prompt is guidance, not a guarantee — these are the fail-safe deterministic
 * guards. A candidate memory that trips any high-confidence pattern is dropped
 * wholesale: losing one memory is far cheaper than persisting a live credential
 * or a hostile directive to the long-term store.
 *
 * <p>Four independent guards. The first three read the candidate alone; the fourth reads it
 * against the turn that produced it, because what it rejects is indistinguishable from an
 * ordinary fact in isolation.
 *
 * <ul>
 *   <li>{@link #looksLikeSecret} (JCLAW-535) — credentials must never reach the
 *       store. Patterns are deliberately narrow (unambiguous key prefixes,
 *       bearer/JWT tokens, PEM private-key blocks, explicit {@code key=value}
 *       assignments, and Luhn-valid card numbers) so ordinary facts aren't
 *       dropped. Free-form mentions of the word "password" are intentionally
 *       NOT matched — only an explicit assignment is.
 *   <li>{@link #looksLikeInjection} (JCLAW-553) — memory text is re-injected
 *       into every future system prompt, so a hostile instruction stored as a
 *       "fact" becomes persistent prompt injection. The recall-side framing
 *       ("stored reference data, not instructions") is the soft defense; this
 *       is the hard one: injection phrasing, exfiltration payloads, SSH
 *       persistence, and invisible-unicode smuggling are refused at write time.
 *   <li>{@link #looksLikeForgetRequest} (JCLAW-1048) — the turn that asks to
 *       forget something is itself extractable as "the user wants X forgotten",
 *       which stores as a durable, well-keyed memory that the model then reads
 *       as standing policy and refuses the whole subject on. The prompt already
 *       bars one-off task instructions; a live model ignored it.
 *   <li>{@link #assertsOnlyPresupposition} (JCLAW-1055) — a request or question
 *       presupposes its subject without stating it, and the extractor writes the
 *       presupposition down as a fact: "forget my dentist's name" produced "The
 *       user has a dentist." for an operator who never said they had one.
 *   <li>{@link #assertsOnlyAssistantContent} (JCLAW-1056) — the substance came from
 *       the assistant turn, which carries tool output: a question about an accountant
 *       stored a fact about an optometrist, every token of it lifted from what recall
 *       had just returned.
 * </ul>
 */
public final class MemorySafety {

    private MemorySafety() {}

    private static final Pattern[] SECRET_PATTERNS = {
            // OpenAI / Stripe / generic sk-/pk-/rk-/kk- style API keys
            Pattern.compile("\\b[sprk]k-[A-Za-z0-9_-]{16,}\\b"),
            // AWS access key id
            Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"),
            // GitHub tokens (ghp_/gho_/ghu_/ghs_/ghr_ and fine-grained github_pat_)
            Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{20,}\\b"),
            Pattern.compile("\\bgithub_pat_\\w{20,}\\b"),
            // Slack tokens
            Pattern.compile("\\bxox[baprs]-[A-Za-z0-9-]{10,}\\b"),
            // Google API key
            Pattern.compile("\\bAIza[0-9A-Za-z_-]{30,}\\b"),
            // JWT: three base64url segments
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b"),
            // PEM private-key block header
            Pattern.compile("-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----"),
            // explicit "<api key|secret|token|password|...> = <value>" assignment
            Pattern.compile("(?i)\\b(api[_-]?key|secret|token|password|passwd|pwd)\\b\\s*[:=]\\s*\\S{6,}"),
    };

    /** A 13-19 digit run, optionally separated by spaces or hyphens. */
    private static final Pattern DIGIT_RUN = Pattern.compile("\\b(?:\\d[ -]?){13,19}\\b");

    /**
     * @return {@code true} when the text looks like it contains a secret (API
     *         key, bearer/JWT token, PEM private key, an explicit key/secret
     *         assignment, or a Luhn-valid card number) and must not be persisted
     *         to memory.
     */
    public static boolean looksLikeSecret(String text) {
        if (text == null || text.isBlank()) return false;
        for (var p : SECRET_PATTERNS) {
            if (p.matcher(text).find()) return true;
        }
        return containsCardNumber(text);
    }

    /**
     * A 13-19 digit run that passes the Luhn check — a plausible payment-card
     * number. The Luhn gate keeps ordinary long identifiers (which rarely satisfy
     * Luhn) from false-positiving.
     */
    private static boolean containsCardNumber(String text) {
        var m = DIGIT_RUN.matcher(text);
        while (m.find()) {
            var digits = m.group().replaceAll("[ -]", "");
            if (digits.length() >= 13 && digits.length() <= 19 && luhnValid(digits)) return true;
        }
        return false;
    }

    private static boolean luhnValid(String digits) {
        int sum = 0;
        boolean dbl = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (dbl) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
            dbl = !dbl;
        }
        return sum % 10 == 0;
    }

    /**
     * Injection/exfiltration shapes, adapted from the Hermes memory content
     * scanner. Each pattern targets phrasing that has no business in a stored
     * fact about the user; ordinary preferences and biography never match.
     */
    private static final Pattern[] INJECTION_PATTERNS = {
            // prompt-injection phrasing ("ignore all previous instructions", "ignore prior instructions")
            Pattern.compile("(?i)\\bignore\\s+(?:previous|all|above|prior|any)(?:\\s+\\w+)?\\s+instructions\\b"),
            Pattern.compile("(?i)\\bdisregard\\s+(?:your|all|any)\\s+(?:instructions|rules|guidelines)\\b"),
            Pattern.compile("(?i)\\byou\\s+are\\s+now\\s"),
            Pattern.compile("(?i)\\bsystem\\s+prompt\\s+override\\b"),
            Pattern.compile("(?i)\\bdo\\s+not\\s+tell\\s+the\\s+user\\b"),
            Pattern.compile("(?i)\\bact\\s+as\\s+(?:if|though)\\s+you\\s+(?:have\\s+no|don'?t\\s+have)\\s+(?:restrictions|limits|rules)\\b"),
            // exfiltration: fetch tools shipping a credential variable off-box
            Pattern.compile("(?i)\\b(?:curl|wget)\\s[^\\n]*\\$\\{?\\w*(?:KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL)"),
            // reading credential files
            Pattern.compile("(?i)\\bcat\\s[^\\n]*(?:\\.env\\b|credentials|\\.netrc\\b|\\.pgpass\\b|\\.npmrc\\b|\\.pypirc\\b)"),
            // SSH persistence / key access
            Pattern.compile("(?i)authorized_keys"),
            Pattern.compile("(?i)(?:\\$HOME|~)/\\.ssh\\b"),
    };

    /**
     * Zero-width characters and bidi override/isolate controls — invisible in
     * rendered text, so their only plausible role in a memory is smuggling
     * content past a human reviewer (Trojan-Source-style).
     */
    private static final Pattern INVISIBLE_CHARS =
            Pattern.compile("[\\u200B\\u200C\\u200D\\u2060\\uFEFF\\u202A-\\u202E\\u2066-\\u2069]");

    /**
     * @return {@code true} when the text looks like a prompt-injection or
     *         exfiltration payload (hostile directive phrasing, credential
     *         exfil/read commands, SSH persistence, or invisible unicode) and
     *         must not be persisted to memory.
     */
    public static boolean looksLikeInjection(String text) {
        if (text == null || text.isBlank()) return false;
        if (INVISIBLE_CHARS.matcher(text).find()) return true;
        for (var p : INJECTION_PATTERNS) {
            if (p.matcher(text).find()) return true;
        }
        return false;
    }

    private static final String REMOVAL_VERB =
            "forget|forgets|forgetting|forgot|forgotten|delete|deleted|remove|removed|erase|erased";

    /** The assistant's own store, not any noun that merely contains the word — "memory foam". */
    private static final String STORED_MEMORY =
            "memories|memory\\s+(?:of|about|that|regarding)|stored\\s+(?:information|data|facts?)";

    /**
     * A request to forget, phrased as a fact about the user.
     *
     * <p>Every pattern needs a removal verb <em>and</em> the assistant's memory as its
     * object, in that order or the reverse. Both halves are required because either alone
     * is ordinary: "the user forgot his passport at home" and "the user has a good memory
     * for faces" must both survive.
     *
     * <p>Gaps are bounded rather than {@code .*} — an unbounded gap between two overlapping
     * classes is the polynomial shape JCLAW-1048's sibling regexes were just hardened
     * against, and nothing here needs to span a sentence.
     */
    private static final Pattern[] FORGET_REQUEST_PATTERNS = {
            // "…to forget everything it knows about X", "…delete what you know about X"
            Pattern.compile("(?i)\\b(?:" + REMOVAL_VERB + ")\\b[^.!?]{0,60}"
                    + "\\b(?:you|it|the assistant)\\s+knows?\\b"),
            // "…delete the memory about X", "…remove all stored information on X"
            Pattern.compile("(?i)\\b(?:" + REMOVAL_VERB + ")\\b[^.!?]{0,60}"
                    + "\\b(?:" + STORED_MEMORY + ")\\b"),
            // "…wants the memory about X deleted" — same shape, object first
            Pattern.compile("(?i)\\b(?:" + STORED_MEMORY + ")\\b[^.!?]{0,60}"
                    + "\\b(?:be\\s+)?(?:forgotten|deleted|removed|erased)\\b"),
    };

    /**
     * @return {@code true} when the text records a request to forget or delete stored
     *         memories rather than a durable fact, and so must not become one itself
     *         (JCLAW-1048). Deliberately not scoped to the forget window: the same note is
     *         junk whether or not a {@code forget} tool call actually ran on the turn.
     */
    public static boolean looksLikeForgetRequest(String text) {
        if (text == null || text.isBlank()) return false;
        for (var p : FORGET_REQUEST_PATTERNS) {
            if (p.matcher(text).find()) return true;
        }
        return false;
    }

    /**
     * Instructions about driving the memory tool itself.
     *
     * <p>Matches a reference to the tool or one of its actions by name, which a durable fact
     * about the user has no reason to contain. Deliberately narrow: a standing preference
     * that merely concerns what gets remembered ("the user does not want medical details
     * stored") names no tool and survives.
     */
    private static final Pattern[] TOOL_INSTRUCTION_PATTERNS = {
            Pattern.compile("(?i)\\bmemory\\s+tool\\b"),
            Pattern.compile("(?i)\\b(?:recall|store|forget)\\s+action\\b"),
            Pattern.compile("(?i)\\baction\\s*[:=]\\s*(?:recall|store|forget)\\b"),
    };

    /**
     * @return {@code true} when the text records an instruction to operate the memory tool
     *         rather than a durable fact, and so must not become a memory (JCLAW-1051).
     *
     * <p>Complements {@link #looksLikeForgetRequest} rather than replacing it, and UAT
     * showed neither subsumes the other: "the user wants the assistant to forget everything
     * it knows about what Marlow eats" names no tool, while "the user wants the assistant to
     * use the recall action of its memory tool" carries no removal verb. Both were stored,
     * and both then ranked in recall on the topic that produced them.
     */
    public static boolean looksLikeToolInstruction(String text) {
        if (text == null || text.isBlank()) return false;
        for (var p : TOOL_INSTRUCTION_PATTERNS) {
            if (p.matcher(text).find()) return true;
        }
        return false;
    }

    /**
     * Clause boundaries, delimiter kept on the clause it closes so a trailing {@code ?} is
     * still visible. A comma counts: a directive's object is a noun phrase, so in "forget my
     * dentist's name, it's Dr Vela" the dentist is presupposed and the name is asserted.
     */
    private static final Pattern CLAUSE_SPLIT = Pattern.compile("(?<=[.!?;,\\n])");

    /** Interrogative openers, for questions written without a question mark. */
    private static final Pattern QUESTION_OPENER = Pattern.compile(
            "(?i)^\\W*(?:what|which|who|whom|whose|where|when|why|how|do|does|did|is|are|was"
                    + "|were|can|could|will|would|have|has|had|should)\\b");

    private static final Pattern REMOVAL_VERB_ANYWHERE =
            Pattern.compile("(?i)\\b(?:" + REMOVAL_VERB + ")\\b");

    /**
     * Whether a candidate memory rests only on what the turn presupposed rather than on
     * anything the operator asserted (JCLAW-1055).
     *
     * <p>Unlike the three guards above this one cannot work on the candidate alone: "The user
     * has a dentist." is a perfectly ordinary memory, and the only thing wrong with it is that
     * the turn behind it was "forget my dentist's name" — a request whose object is taken for
     * granted, not claimed. So the turn is split into what it asserts and what it merely
     * presupposes (a question in full; a removal directive from its verb onward, leaving
     * "my dentist retired so delete his number" asserting the retirement), and a candidate is
     * refused only when it draws on the second and nothing from the first. That asymmetry is
     * what keeps a fact stated in the same breath as the request — "forget my dentist's name,
     * it's Dr Vela" — capturable.
     *
     * <p>Tokens come from {@link MemorySimilarity#contentTokens}, the normalization shared with
     * search and dedup (JCLAW-1054), which strips "The user has a" boilerplate and folds
     * "dentist's" onto "dentist" so the comparison is about substance rather than phrasing.
     *
     * <p>Bounded on purpose: an object continued past a comma ("delete what you know about my
     * dog, my cat and my dentist") reads as a second clause and only the first noun is covered.
     */
    public static boolean assertsOnlyPresupposition(String userTurn, String text) {
        if (userTurn == null || userTurn.isBlank() || text == null || text.isBlank()) return false;

        var asserted = new StringBuilder();
        var presupposed = new StringBuilder();
        for (var clause : CLAUSE_SPLIT.split(userTurn)) {
            var trimmed = clause.strip();
            if (trimmed.isEmpty()) continue;
            if (trimmed.endsWith("?") || QUESTION_OPENER.matcher(trimmed).find()) {
                presupposed.append(' ').append(trimmed);
                continue;
            }
            var m = REMOVAL_VERB_ANYWHERE.matcher(trimmed);
            if (m.find()) {
                asserted.append(' ').append(trimmed, 0, m.start());
                presupposed.append(' ').append(trimmed.substring(m.start()));
            } else {
                asserted.append(' ').append(trimmed);
            }
        }

        Set<String> candidate = MemorySimilarity.contentTokens(text);
        if (candidate.isEmpty()) return false;
        return Collections.disjoint(candidate, MemorySimilarity.contentTokens(asserted.toString()))
                && !Collections.disjoint(candidate, MemorySimilarity.contentTokens(presupposed.toString()));
    }

    /**
     * Whether a candidate memory's substance came from the assistant rather than the operator
     * (JCLAW-1056).
     *
     * <p>The extraction prompt already bars this — only the user turn is a source — but a live
     * model ignored it: asked "what did I tell you about my accountant", it stored "The user
     * sees Dr Kerans as their optometrist", every content token lifted from the recall output
     * the assistant had just surfaced. That matters beyond phrasing churn, because the
     * assistant turn carries tool results: whatever a fetch returns can otherwise become a
     * durable fact about the operator.
     *
     * <p>The prompt supplies the assistant turn on purpose, to resolve pronouns, so this
     * cannot demand that every token trace back to the user — "The user's son Arun goes by Bo"
     * legitimately takes <em>Arun</em> from the assistant. The two cases differ by degree
     * rather than kind: a resolution is still partly grounded in what the user said ("goes by
     * Bo"), while the defect is grounded in it not at all. So the refusal needs user-grounding
     * to be empty and assistant-grounding not to be, which leaves resolution untouched.
     *
     * <p>Complements {@link #assertsOnlyPresupposition} at its blind spot rather than
     * overlapping it: that guard requires the candidate to touch the turn somewhere, and this
     * one fires precisely when it touches nothing in it.
     */
    public static boolean assertsOnlyAssistantContent(String userTurn, String assistantTurn, String text) {
        if (userTurn == null || assistantTurn == null || text == null || text.isBlank()) return false;

        Set<String> candidate = MemorySimilarity.contentTokens(text);
        if (candidate.isEmpty()) return false;
        return Collections.disjoint(candidate, MemorySimilarity.contentTokens(userTurn))
                && !Collections.disjoint(candidate, MemorySimilarity.contentTokens(assistantTurn));
    }
}
