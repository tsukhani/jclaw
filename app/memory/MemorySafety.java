package memory;

import java.util.regex.Pattern;

/**
 * Deterministic secret detection for the memory capture path (JCLAW-535). The
 * extraction prompt already asks the model not to capture secrets, but a prompt
 * is guidance, not a guarantee — this is the fail-safe deterministic guard. A
 * candidate memory that trips any high-confidence secret pattern is dropped
 * wholesale: losing one memory is far cheaper than persisting a live credential
 * to the long-term store.
 *
 * <p>Patterns are deliberately narrow (unambiguous key prefixes, bearer/JWT
 * tokens, PEM private-key blocks, explicit {@code key=value} assignments, and
 * Luhn-valid card numbers) so ordinary facts aren't dropped. Free-form mentions
 * of the word "password" are intentionally NOT matched — only an explicit
 * assignment is.
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
            Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{20,}\\b"),
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
}
