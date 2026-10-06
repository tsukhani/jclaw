package services.grapheval;

import memory.LiteralSpans;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Clusters typed mentions across memories by type plus canonical key, or by identifier key for a URL, path, ticket
 * key or email (JCLAW-1356, JCLAW-1370). The baseline resolver: it merges only what reads the same, so every miss it
 * makes is a merge a smarter resolver would have to earn. Its keys are the one normalizer {@link EntityResolver}
 * shares.
 */
public final class ExactMatchResolver {

    public static final String TOPIC = "Topic";
    public static final Pattern EMAIL = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");

    private static final String OPERATOR_KEY = "\u0000operator";
    private static final String IDENTIFIER_KEY = "\u0000id\u0000";
    private static final Pattern DETERMINER = Pattern.compile("^(?:the|a|an) ");
    private static final Pattern POSSESSIVE = Pattern.compile("['’]s$");
    private static final Pattern PUNCTUATION = Pattern.compile("\\p{P}+");
    private static final Pattern EDGE_PUNCTUATION = Pattern.compile("^[\\p{P}\\s]+|[\\p{P}\\s]+$");
    private static final Pattern URL_PREFIX = Pattern.compile("^([A-Za-z][A-Za-z0-9+.\\-]*://)([^/?#]*)(.*)$");

    private ExactMatchResolver() {}

    /** One typed mention; {@code ref} identifies it to the caller, and every {@code operator} mention joins one cluster. */
    public record Mention(String ref, String surface, String type, boolean operator) {}

    /**
     * The canonical key: lower case, edge punctuation trimmed, a leading determiner (the, a, an) and a possessive
     * {@code 's} removed, punctuation removed, whitespace collapsed.
     */
    public static String normalize(String surface) {
        // Trimmed first, or a quoted "The Inn" keeps its determiner and "Inn's." its possessive.
        var s = collapse(EDGE_PUNCTUATION.matcher(surface.toLowerCase(Locale.ROOT)).replaceAll(""));
        s = DETERMINER.matcher(s).replaceFirst("");
        // Before punctuation goes, or "inn's" would read as "inns".
        s = POSSESSIVE.matcher(s).replaceFirst("");
        return collapse(PUNCTUATION.matcher(s).replaceAll(""));
    }

    /** {@link #normalize(String)}, with a Topic's last token reduced to its singular. */
    public static String normalize(String surface, String type) {
        var key = normalize(surface);
        if (!type.equals(TOPIC) || key.isEmpty()) return key;
        int cut = key.lastIndexOf(' ') + 1;
        return key.substring(0, cut) + singular(key.substring(cut));
    }

    /** Berries to berry, glasses to glass, oat milks to oat milk; bus, analysis and glass stay. No dictionary. */
    static String singular(String token) {
        if (token.length() > 3 && token.endsWith("ies")) return token.substring(0, token.length() - 3) + "y";
        for (var ending : List.of("sses", "xes", "ches", "shes")) {
            if (token.endsWith(ending)) return token.substring(0, token.length() - 2);
        }
        if (token.length() > 1 && token.endsWith("s") && !token.endsWith("ss") && !token.endsWith("us")
                && !token.endsWith("is")) {
            return token.substring(0, token.length() - 1);
        }
        return token;
    }

    /** Whether the trimmed surface is wholly a URL, path, ticket key or email. */
    public static boolean isIdentifier(String surface) {
        var s = surface.strip();
        return LiteralSpans.URL.matcher(s).matches() || LiteralSpans.PATH.matcher(s).matches()
                || LiteralSpans.TICKET.matcher(s).matches() || EMAIL.matcher(s).matches();
    }

    /** The identifier as found, trimmed; an email, and a URL's scheme and host, in lower case. A path keeps its case. */
    public static String identifierKey(String surface) {
        var s = surface.strip();
        // lookingAt: PATH's character class has no @, so /node_modules/@types/x.d.ts only starts like a path.
        if (EMAIL.matcher(s).matches() && !LiteralSpans.URL.matcher(s).matches()
                && !LiteralSpans.PATH.matcher(s).lookingAt()) {
            return s.toLowerCase(Locale.ROOT);
        }
        var url = URL_PREFIX.matcher(s);
        if (LiteralSpans.URL.matcher(s).matches() && url.matches()) {
            return url.group(1).toLowerCase(Locale.ROOT) + url.group(2).toLowerCase(Locale.ROOT) + url.group(3);
        }
        return s;
    }

    private static String collapse(String s) {
        return s.strip().replaceAll("\\s+", " ");
    }

    /**
     * The clusters, each a list of mention refs, in order of first appearance. A Person whose surface normalizes to
     * {@code ownerName}'s joins the operator's cluster.
     */
    public static List<List<String>> resolve(List<Mention> mentions, @Nullable String ownerName) {
        var owner = ownerName == null ? null : normalize(ownerName);
        var clusters = new LinkedHashMap<String, List<String>>();
        for (var m : mentions) {
            boolean named = owner != null && m.type().equals(ExtractionPipeline.OPERATOR_TYPE)
                    && normalize(m.surface()).equals(owner);
            String key;
            if (m.operator() || named) key = OPERATOR_KEY;
            else if (isIdentifier(m.surface())) key = IDENTIFIER_KEY + identifierKey(m.surface());
            else key = m.type() + "\u0000" + normalize(m.surface(), m.type());
            clusters.computeIfAbsent(key, _ -> new ArrayList<>()).add(m.ref());
        }
        return clusters.values().stream().map(List::copyOf).toList();
    }
}
