package memory;

import org.jspecify.annotations.Nullable;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import java.util.regex.Pattern;

/**
 * Deterministic content guards for the memory capture path. The extraction
 * prompt already asks the model not to capture secrets or instructions, but a
 * prompt is guidance, not a guarantee — these are the fail-safe deterministic
 * guards. A candidate memory that trips any high-confidence pattern is dropped
 * wholesale: losing one memory is far cheaper than persisting a live credential
 * or a hostile directive to the long-term store.
 *
 * <p>Six independent guards. Four read the candidate alone; the last two read it
 * against the turn that produced it, because what they reject is indistinguishable from an
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

    /** Hyphenated names, so text quoting one (a prompt marker, an alert) does not match its own pattern. */
    private record InjectionRule(String name, Pattern pattern, String hint, @Nullable Pattern after) {
        InjectionRule(String name, Pattern pattern, String hint) {
            this(name, pattern, hint, null);
        }
    }

    /**
     * Injection/exfiltration shapes, adapted from the Hermes memory content
     * scanner. Each pattern targets phrasing that has no business in a stored
     * fact about the user; ordinary preferences and biography never match.
     */
    private static final InjectionRule[] INJECTION_RULES = {
            // prompt-injection phrasing ("ignore all previous instructions", "ignore prior instructions")
            new InjectionRule("ignore-instructions",
                    Pattern.compile("(?i)\\bignore\\s+(?:previous|all|above|prior|any)(?:\\s+\\w+)?\\s+instructions\\b"),
                    "Say what the agent should do, rather than telling it to ignore other instructions."),
            new InjectionRule("disregard-instructions",
                    Pattern.compile("(?i)\\bdisregard\\s+(?:your|all|any)\\s+(?:instructions|rules|guidelines)\\b"),
                    "Say what the agent should do, rather than telling it to disregard its rules."),
            new InjectionRule("you-are-now", Pattern.compile("(?i)\\byou\\s+are\\s+now\\s"),
                    "Write \"You are …\" or \"Your name is …\", without \"now\"."),
            new InjectionRule("system-prompt-override", Pattern.compile("(?i)\\bsystem\\s+prompt\\s+override\\b"),
                    "Describe the behavior you want, rather than asking to override the system prompt."),
            new InjectionRule("hide-from-user", Pattern.compile("(?i)\\bdo\\s+not\\s+tell\\s+the\\s+user\\b"),
                    "Write \"Keep … private\", rather than telling the agent to hide something from the user."),
            new InjectionRule("no-restrictions",
                    Pattern.compile("(?i)\\bact\\s+as\\s+(?:if|though)\\s+you\\s+(?:have\\s+no|don'?t\\s+have)\\s+(?:restrictions|limits|rules)\\b"),
                    "Name the limits the agent keeps, rather than saying it has none."),
            // exfiltration: fetch tools shipping a credential variable off-box
            new InjectionRule("credential-exfiltration", Pattern.compile("(?i)\\b(?:curl|wget)\\s"),
                    "Leave out commands that send a key or token with curl or wget.",
                    Pattern.compile("(?i)\\$\\{?\\w*(?:KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL)")),
            // reading credential files
            new InjectionRule("credential-file-read", Pattern.compile("(?i)\\bcat\\s"),
                    "Name the file in words, such as \"the .netrc file\", without the cat command.",
                    Pattern.compile("(?i)(?:\\.env\\b|credentials|\\.netrc\\b|\\.pgpass\\b|\\.npmrc\\b|\\.pypirc\\b)")),
            // SSH persistence / key access
            new InjectionRule("ssh-authorized-keys", Pattern.compile("(?i)authorized_keys"),
                    "Write \"the SSH keys file\" in place of the file's name."),
            new InjectionRule("ssh-directory", Pattern.compile("(?i)(?:\\$HOME|~)/\\.ssh\\b"),
                    "Write \"the SSH folder\" in place of its path."),
    };

    /**
     * Zero-width and other default-ignorable characters, bidi override/isolate
     * controls, tag characters and variation selectors — invisible in rendered
     * text, so their only plausible role in a memory is smuggling content past a
     * human reviewer (Trojan-Source, and since 2025 tag- and selector-encoded
     * instructions). {@link #isVisibleUse} lets through the uses that do show something.
     */
    // Unicode's Default_Ignorable_Code_Point set, written out: java.util.regex has no property for it.
    private static final Pattern INVISIBLE_CHARS = Pattern.compile(
            "[\\u00AD\\u034F\\u061C\\u115F\\u1160\\u17B4\\u17B5\\u180B-\\u180F\\u200B-\\u200F\\u202A-\\u202E"
                    + "\\u2060-\\u206F\\u3164\\uFE00-\\uFE0F\\uFEFF\\uFFA0\\uFFF0-\\uFFF8\\x{1BCA0}-\\x{1BCA3}"
                    + "\\x{1D173}-\\x{1D17A}\\x{E0000}-\\x{E0FFF}]");
    private static final int BLACK_FLAG = 0x1F3F4;
    private static final int CANCEL_TAG = 0xE007F;
    // The subdivision flags Unicode recommends: England, Scotland, Wales. Any other tag run could carry text.
    private static final Set<String> SUBDIVISION_FLAGS = Set.of("gbeng", "gbsct", "gbwls");
    // Each ideographic variant can carry a byte, so a text with more than a name's worth is smuggling.
    private static final int MAX_IDEOGRAPHIC_VARIANTS = 8;

    private static final String INVISIBLE_RULE = "invisible-character";
    private static final String INVISIBLE_HINT = "It shows as nothing on screen. Delete it.";

    /** One place a rule matched: {@code [start, end)} in UTF-16 units of the text scanned. */
    public record InjectionMatch(String rule, int start, int end) {}

    // A joiner between two emoji is how a multi-part emoji such as a woman technologist is spelled.
    private static boolean isEmojiJoiner(String text, int index) {
        if (text.charAt(index) != '\u200D' || index == 0 || index + 1 >= text.length()) return false;
        int before = text.codePointBefore(index);
        boolean joinsAfter = Character.isExtendedPictographic(before) || before == 0xFE0F
                || (before >= 0x1F3FB && before <= 0x1F3FF);
        return joinsAfter && Character.isExtendedPictographic(text.codePointAt(index + 1));
    }

    // A pictograph takes either presentation; a digit, # or * only the emoji one, as the start of a keycap.
    private static boolean isPresentationBase(String text, int index, int selector) {
        int base = text.codePointBefore(index);
        if (Character.isExtendedPictographic(base)) return true;
        boolean keycapBase = (base >= '0' && base <= '9') || base == '#' || base == '*';
        return keycapBase && selector == 0xFE0F && index + 1 < text.length() && text.charAt(index + 1) == 0x20E3;
    }

    private static boolean isIdeographicVariant(int cp) {
        return cp >= 0xE0100 && cp <= 0xE01EF;
    }

    // Scripts whose spelling needs a zero-width joiner or non-joiner between its letters.
    private static final Set<Character.UnicodeScript> JOINING_SCRIPTS = EnumSet.of(
            Character.UnicodeScript.ARABIC, Character.UnicodeScript.SYRIAC, Character.UnicodeScript.NKO,
            Character.UnicodeScript.DEVANAGARI, Character.UnicodeScript.BENGALI, Character.UnicodeScript.GURMUKHI,
            Character.UnicodeScript.GUJARATI, Character.UnicodeScript.ORIYA, Character.UnicodeScript.TAMIL,
            Character.UnicodeScript.TELUGU, Character.UnicodeScript.KANNADA, Character.UnicodeScript.MALAYALAM,
            Character.UnicodeScript.SINHALA, Character.UnicodeScript.KHMER, Character.UnicodeScript.MYANMAR,
            Character.UnicodeScript.TIBETAN, Character.UnicodeScript.MONGOLIAN);

    /**
     * What the allowances need to know about the whole text, counted in one pass. Each allowed invisible
     * character stays fewer than the letters it serves, so a run of them cannot become a channel.
     */
    private record TextFacts(int ideographicVariants, int letters, int joiningLetters, int rightToLeftLetters,
                             int mongolianLetters, int softHyphens, int scriptJoiners, int bidiMarks,
                             int mongolianSelectors) {
        static TextFacts of(String text) {
            int variants = 0;
            int letters = 0;
            int joining = 0;
            int rightToLeft = 0;
            int mongolian = 0;
            int softHyphens = 0;
            int joiners = 0;
            int marks = 0;
            int selectors = 0;
            for (int i = 0; i < text.length(); ) {
                int cp = text.codePointAt(i);
                if (Character.isLetter(cp)) {
                    letters++;
                    var script = Character.UnicodeScript.of(cp);
                    if (JOINING_SCRIPTS.contains(script)) joining++;
                    if (script == Character.UnicodeScript.MONGOLIAN) mongolian++;
                    if (isRightToLeft(cp)) rightToLeft++;
                } else if (isIdeographicVariant(cp)) {
                    variants++;
                } else if (cp == 0x00AD) {
                    softHyphens++;
                } else if ((cp == 0x200C || cp == 0x200D) && !isEmojiJoiner(text, i)) {
                    joiners++;
                } else if (isBidiMark(cp)) {
                    marks++;
                } else if (cp >= 0x180B && cp <= 0x180F) {
                    selectors++;
                }
                i += Character.charCount(cp);
            }
            return new TextFacts(variants, letters, joining, rightToLeft, mongolian, softHyphens, joiners, marks,
                    selectors);
        }
    }

    // The nearest strongly directional character within 16 code points on the line, skipping neutrals, or -1.
    private static int nearestStrong(String text, int index, boolean forward) {
        int at = index;
        for (int steps = 0; steps < 16; steps++) {
            if (forward) {
                at += Character.charCount(text.codePointAt(at));
                if (at >= text.length()) return -1;
            } else {
                if (at == 0) return -1;
                at -= Character.charCount(text.codePointBefore(at));
            }
            int cp = text.codePointAt(at);
            if (cp == '\n') return -1;
            byte direction = Character.getDirectionality(cp);
            if (direction == Character.DIRECTIONALITY_LEFT_TO_RIGHT || isRightToLeft(cp)) return cp;
        }
        return -1;
    }

    private static boolean touchesRightToLeft(String text, int index) {
        int before = nearestStrong(text, index, false);
        int after = nearestStrong(text, index, true);
        return (before >= 0 && isRightToLeft(before)) || (after >= 0 && isRightToLeft(after));
    }

    private static boolean isRightToLeft(int cp) {
        byte direction = Character.getDirectionality(cp);
        return direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT
                || direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC;
    }

    private static boolean isBidiMark(int cp) {
        return cp == 0x200E || cp == 0x200F || cp == 0x061C;
    }

    private static boolean betweenLettersOfAJoiningScript(String text, int index) {
        if (index == 0 || index + 1 >= text.length()) return false;
        var before = Character.UnicodeScript.of(text.codePointBefore(index));
        return JOINING_SCRIPTS.contains(before) && before == Character.UnicodeScript.of(text.codePointAt(index + 1));
    }

    private static boolean betweenLetters(String text, int index) {
        return index > 0 && index + 1 < text.length()
                && Character.isLetter(text.codePointBefore(index)) && Character.isLetter(text.codePointAt(index + 1));
    }

    private static boolean isTag(int cp) {
        return cp >= 0xE0000 && cp <= CANCEL_TAG;
    }

    // A subdivision flag, England's for one, is a black flag, its code in tag letters, and the cancel tag.
    private static boolean inSubdivisionFlag(String text, int index) {
        int start = index;
        // Bounded, so a long tag run costs each of its characters a few steps, not a walk to its start.
        for (int walked = 0; start > 0; walked++) {
            int before = text.codePointBefore(start);
            if (!isTag(before) || before == CANCEL_TAG) break;
            if (walked == 6) return false;
            start -= Character.charCount(before);
        }
        if (start == 0 || text.codePointBefore(start) != BLACK_FLAG) return false;
        var code = new StringBuilder();
        for (int i = start; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (cp == CANCEL_TAG) return SUBDIVISION_FLAGS.contains(code.toString());
            if (!isTag(cp) || code.length() == 6) return false;
            code.appendCodePoint(cp - 0xE0000);
            i += Character.charCount(cp);
        }
        return false;
    }

    /**
     * Whether the invisible character at {@code index} is a use of it that writing needs: a joiner inside an emoji
     * or between letters of a script that spells with one, a bidi mark in right-to-left text, a soft hyphen inside a
     * word, a selector on the emoji, ideograph or Mongolian letter it styles, or a recommended flag.
     */
    private static boolean isVisibleUse(String text, int index, TextFacts facts) {
        int cp = text.codePointAt(index);
        if (cp == 0x200C || cp == 0x200D) {
            if (cp == 0x200D && isEmojiJoiner(text, index)) return true;
            return betweenLettersOfAJoiningScript(text, index) && facts.scriptJoiners() * 2 <= facts.joiningLetters();
        }
        // One mark at a time, beside right-to-left text: a run of them carries bits, and no ordering problem needs two.
        if (isBidiMark(cp)) {
            return (index == 0 || !isBidiMark(text.codePointBefore(index))) && touchesRightToLeft(text, index)
                    && facts.bidiMarks() * 2 <= facts.rightToLeftLetters();
        }
        if (cp == 0x00AD) return betweenLetters(text, index) && facts.softHyphens() * 3 <= facts.letters();
        if (cp >= 0x180B && cp <= 0x180F) {
            if (index == 0) return false;
            int before = text.codePointBefore(index);
            return Character.isLetter(before) && Character.UnicodeScript.of(before) == Character.UnicodeScript.MONGOLIAN
                    && facts.mongolianSelectors() <= facts.mongolianLetters();
        }
        if (cp == 0xFE0E || cp == 0xFE0F) return index > 0 && isPresentationBase(text, index, cp);
        if (isIdeographicVariant(cp)) {
            return facts.ideographicVariants() <= MAX_IDEOGRAPHIC_VARIANTS && index > 0
                    && Character.isIdeographic(text.codePointBefore(index));
        }
        return isTag(cp) && inSubdivisionFlag(text, index);
    }

    private static boolean hasInvisibleChars(String text) {
        var matcher = INVISIBLE_CHARS.matcher(text);
        TextFacts facts = null;
        while (matcher.find()) {
            if (facts == null) facts = TextFacts.of(text);
            if (!isVisibleUse(text, matcher.start(), facts)) return true;
        }
        return false;
    }

    /**
     * Each match of {@code rule}, as {@code [start, end)}. A rule with an {@code after} pattern matches from the
     * first command word on a line to the last credential after it on that line, as a greedy {@code [^\n]*} gap
     * would, but in two linear passes: a gap pattern backtracks from every command word to the end of its line.
     */
    private static List<int[]> matchesOf(InjectionRule rule, String text) {
        var found = new ArrayList<int[]>();
        var matcher = rule.pattern().matcher(text).useTransparentBounds(true);
        var afterPattern = rule.after();
        if (afterPattern == null) {
            while (matcher.find()) found.add(new int[]{matcher.start(), matcher.end()});
            return found;
        }
        var after = afterPattern.matcher(text).useTransparentBounds(true);
        for (int lineStart = 0; lineStart < text.length(); ) {
            int newline = text.indexOf('\n', lineStart);
            int lineEnd = newline < 0 ? text.length() : newline;
            if (matcher.region(lineStart, lineEnd).find()) {
                int lastEnd = -1;
                after.region(matcher.end(), lineEnd);
                while (after.find()) lastEnd = after.end();
                if (lastEnd >= 0) found.add(new int[]{matcher.start(), lastEnd});
            }
            lineStart = lineEnd + 1;
        }
        return found;
    }

    /** The text the phrase rules read, and where each of its chars came from in the text. */
    private record Folded(String text, IntUnaryOperator start, IntUnaryOperator end) {}

    // Fullwidth and other compatibility forms read as the letters they show, and the invisible characters writing
    // may hold are dropped, so neither a fullwidth "cat" nor a soft hyphen inside "ignore" dodges a rule. Folding
    // code point by code point keeps a map back to the original, for a match's position.
    private static Folded fold(String text) {
        if (Normalizer.isNormalized(text, Normalizer.Form.NFKC) && !INVISIBLE_CHARS.matcher(text).find()) {
            return new Folded(text, i -> i, i -> i);
        }
        var invisible = INVISIBLE_CHARS.matcher("");
        var folded = new StringBuilder(text.length());
        int[] starts = new int[text.length() + 16];
        int[] ends = new int[text.length() + 16];
        for (int i = 0; i < text.length(); ) {
            int next = i + Character.charCount(text.codePointAt(i));
            var original = text.substring(i, next);
            var piece = invisible.reset(original).matches() ? "" : Normalizer.normalize(original, Normalizer.Form.NFKC);
            for (int k = 0; k < piece.length(); k++) {
                if (folded.length() == starts.length) {
                    starts = Arrays.copyOf(starts, starts.length * 2);
                    ends = Arrays.copyOf(ends, ends.length * 2);
                }
                starts[folded.length()] = i;
                ends[folded.length()] = next;
                folded.append(piece.charAt(k));
            }
            i = next;
        }
        int[] startOf = starts;
        int[] endOf = ends;
        return new Folded(folded.toString(), i -> startOf[i], i -> endOf[i - 1]);
    }

    /**
     * @return {@code true} when the text looks like a prompt-injection or
     *         exfiltration payload (hostile directive phrasing, credential
     *         exfil/read commands, SSH persistence, or invisible unicode) and
     *         must not be persisted to memory.
     */
    public static boolean looksLikeInjection(String text) {
        return injectionRule(text) != null;
    }

    /**
     * The name of the rule behind {@link #looksLikeInjection}: {@code invisible-character} when one is
     * present, otherwise the first matching pattern in declaration order, so equal text always names
     * the same rule. Null when nothing matches.
     */
    public static @Nullable String injectionRule(String text) {
        if (text == null || text.isBlank()) return null;
        if (hasInvisibleChars(text)) return INVISIBLE_RULE;
        var folded = fold(text).text();
        for (var rule : INJECTION_RULES) {
            if (!matchesOf(rule, folded).isEmpty()) return rule.name();
        }
        return null;
    }

    /**
     * Every match of every rule behind {@link #injectionRule}, in text order: each invisible character
     * on its own, and each pattern each time it matches. Empty exactly when {@code injectionRule} is null.
     */
    public static List<InjectionMatch> injectionMatches(@Nullable String text) {
        if (text == null || text.isBlank()) return List.of();
        var found = new ArrayList<InjectionMatch>();
        var invisible = INVISIBLE_CHARS.matcher(text);
        TextFacts facts = null;
        while (invisible.find()) {
            if (facts == null) facts = TextFacts.of(text);
            if (!isVisibleUse(text, invisible.start(), facts)) {
                found.add(new InjectionMatch(INVISIBLE_RULE, invisible.start(), invisible.end()));
            }
        }
        var folded = fold(text);
        for (var rule : INJECTION_RULES) {
            for (var span : matchesOf(rule, folded.text())) {
                found.add(new InjectionMatch(rule.name(), folded.start().applyAsInt(span[0]),
                        folded.end().applyAsInt(span[1])));
            }
        }
        found.sort(Comparator.comparingInt(InjectionMatch::start).thenComparingInt(InjectionMatch::end));
        return found;
    }

    /** How to reword text so {@code rule} no longer matches, for the operator to read. */
    public static String rewordingHint(String rule) {
        if (INVISIBLE_RULE.equals(rule)) return INVISIBLE_HINT;
        for (var candidate : INJECTION_RULES) {
            if (candidate.name().equals(rule)) return candidate.hint();
        }
        throw new IllegalArgumentException("no injection rule is named " + rule);
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
     * <p>Unlike the guards above this one cannot work on the candidate alone: "The user
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
