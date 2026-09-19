import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * No agent-reachable read hands back a credential (JCLAW-1268).
 *
 * <p>The JCLAW-1253/1266 stance rule covers mutating routes and says so deliberately: a GET that
 * leaks is a masking problem at the seam. The seam is the right place — what was missing is any
 * check that it was used. Masking is applied correctly in eight files today, arrived at over four
 * tickets, and entirely by memory; a new read that returns a secret gets none unless its author
 * happens to remember, and the omission looks exactly like a decision.
 *
 * <p><b>Why a source scan rather than an ArchUnit rule.</b> A render goes through
 * {@code gson.toJson} on an arbitrary object graph, so no bytecode rule can follow a field to the
 * response without dataflow ArchUnit does not do. The hook used instead already exists: 79 of the
 * 113 {@code GET /api} routes declare their response type in the Swagger
 * {@code @ApiResponse(... implementation = X.class)}. That declaration is the render contract, so
 * the check reads it rather than inferring it.
 *
 * <p><b>Why a type check and not a masking check.</b> Proving a value was masked needs the same
 * dataflow. Proving the response type cannot carry one needs no dataflow at all, and it is what
 * the codebase already does: every channel binding view exposes {@code hasAccessToken} and
 * {@code hasSigningSecret} booleans rather than the strings. The safe pattern is not "mask the
 * credential on the way out", it is "the response type has no field to leak".
 *
 * <p><b>What the predicate matches, and why it is narrow.</b> String-typed components whose name
 * contains secret / password / apiKey / authKey, or ends in Token. Type is load-bearing: the
 * obvious name-only predicate matches 40 files, almost all of them token <em>counts</em>
 * ({@code maxTokens}, {@code promptTokens}, {@code tokensPerSecond}) and map keys. Requiring
 * {@code String} drops those and leaves 24 members, nearly all real credentials. A rule that cried
 * wolf at that rate would be suppressed and then trusted, which is worse than the convention.
 *
 * <p><b>Known over-approximation.</b> Types are matched by simple name, so a credential on one
 * controller's {@code BindingView} is reported against every route returning a
 * {@code BindingView} — four controllers declare one. Deliberate: over-reporting costs a moment
 * finding which class carries the field, under-reporting ships the leak, and the remedy is the
 * same either way.
 */
class CredentialReadGateConformanceTest extends UnitTest {

    /** A credential is a String. The counts that share these names are not (see class Javadoc). */
    private static final Pattern CREDENTIAL_NAME =
            Pattern.compile("(secret|password|apikey|authkey)|token$", Pattern.CASE_INSENSITIVE);

    private static final Pattern GET_ROUTE =
            Pattern.compile("^GET\\s+(/api/\\S*)\\s+(\\S+)", Pattern.MULTILINE);

    /** {@code @ApiResponse(... implementation = Some.Type.class)} on the action above. */
    private static final Pattern DECLARED_RESPONSE =
            Pattern.compile("implementation\\s*=\\s*([\\w.]+)\\.class");

    /**
     * GET routes that do not declare a response type, and so cannot be checked. Ratcheted rather
     * than swept: each one closed is a line off this number, and a new undeclared route fails
     * below. Lowering it is the point; raising it needs a reason in the commit.
     */
    private static final int UNDECLARED_GET_ROUTES = 34;

    private static Path repo(String relative) {
        return Path.of(Play.applicationPath.getAbsolutePath()).resolve(relative);
    }

    @Test
    void noDeclaredApiResponseTypeCarriesACredential() throws IOException {
        var index = recordComponentsByType();
        var offenders = new TreeMap<String, String>();

        for (var route : declaredResponseTypes().entrySet()) {
            for (var offending : credentialComponentsOf(route.getValue(), index, new HashSet<>())) {
                offenders.put(route.getKey(), route.getValue() + " -> " + offending);
            }
        }

        assertTrue(offenders.isEmpty(),
                "these agent-reachable reads declare a response type that carries a credential-shaped "
                        + "String. Expose a boolean saying whether it is set — the channel binding views "
                        + "are the worked example (hasAccessToken, hasSigningSecret) — or refuse the agent "
                        + "principal on the route: " + offenders);
    }

    /**
     * The gate is only as wide as the declarations it can read, so the gap is a number the build
     * watches rather than a silence. Without this, closing the check's own coverage would be
     * invisible and a new undeclared route would simply not be looked at.
     */
    @Test
    void theUncheckableGetSurfaceDoesNotGrow() throws IOException {
        var undeclared = new TreeSet<String>();
        for (var route : getRoutes().entrySet()) {
            if (declaredResponseTypes().containsKey(route.getKey())) continue;
            undeclared.add(route.getKey());
        }
        assertTrue(undeclared.size() <= UNDECLARED_GET_ROUTES,
                "a GET route with no @ApiResponse(implementation = ...) cannot be checked by the gate "
                        + "above, and the number of them grew from " + UNDECLARED_GET_ROUTES + " to "
                        + undeclared.size() + ". Declare the new route's response type: " + undeclared);
    }

    /** Guards the two assertions above against a parser that quietly stopped matching. */
    @Test
    void theScanActuallyReadsTheRouteTableAndTheAnnotations() throws IOException {
        assertTrue(getRoutes().size() >= 100,
                "expected 100+ GET /api routes, found " + getRoutes().size()
                        + " — the routes parser stopped matching and both checks above would pass vacuously");
        assertTrue(declaredResponseTypes().size() >= 70,
                "expected 70+ declared response types, found " + declaredResponseTypes().size()
                        + " — the annotation parser stopped matching");
        assertFalse(recordComponentsByType().isEmpty(), "the record index is empty");

        // A known credential-shaped member must be recognised, or the predicate proves nothing.
        assertTrue(CREDENTIAL_NAME.matcher("botToken").find());
        assertTrue(CREDENTIAL_NAME.matcher("signingSecret").find());
        // ...and the counts that share those names must not be.
        assertFalse(CREDENTIAL_NAME.matcher("maxTokens").find());
        assertFalse(CREDENTIAL_NAME.matcher("promptTokens").find());
    }

    // --- scanning -----------------------------------------------------------------------------

    private static Map<String, String> getRoutes() throws IOException {
        var found = new TreeMap<String, String>();
        var matcher = GET_ROUTE.matcher(Files.readString(repo("conf/routes")));
        while (matcher.find()) found.put("GET " + matcher.group(1), matcher.group(2));
        return found;
    }

    /** Route to the simple name of the type its action declares as the 200 body. */
    private static Map<String, String> declaredResponseTypes() throws IOException {
        var out = new TreeMap<String, String>();
        for (var route : getRoutes().entrySet()) {
            var target = route.getValue();
            var controller = target.substring(0, target.lastIndexOf('.'));
            controller = controller.substring(controller.lastIndexOf('.') + 1);
            var action = target.substring(target.lastIndexOf('.') + 1);
            var file = repo("app/controllers/" + controller + ".java");
            if (!Files.exists(file)) continue;
            var source = Files.readString(file);
            var at = source.indexOf("public static void " + action + "(");
            if (at < 0) continue;
            var window = source.substring(Math.max(0, at - 700), at);
            var declared = DECLARED_RESPONSE.matcher(window);
            String last = null;
            while (declared.find()) last = declared.group(1);
            if (last != null) out.put(route.getKey(), last.substring(last.lastIndexOf('.') + 1));
        }
        return out;
    }

    /**
     * Every {@code record Name(components)} under {@code app/}, simple name to <em>every</em> body
     * declaring it.
     *
     * <p>A list, not one entry, and that is load-bearing: four controllers each declare a record
     * called {@code BindingView}. Keyed by simple name alone, the last file scanned silently wins
     * and the other three go unchecked — which is exactly how the first version of this gate passed
     * a deliberately-planted credential on the Slack view.
     */
    private static Map<String, List<String>> recordComponentsByType() throws IOException {
        var index = new HashMap<String, List<String>>();
        var pattern = Pattern.compile("record\\s+(\\w+)\\s*\\(([^)]*)\\)", Pattern.DOTALL);
        try (Stream<Path> files = Files.walk(repo("app"))) {
            for (var file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                var matcher = pattern.matcher(Files.readString(file));
                while (matcher.find()) {
                    index.computeIfAbsent(matcher.group(1), _ -> new ArrayList<>()).add(matcher.group(2));
                }
            }
        }
        return index;
    }

    /** Credential-shaped components of {@code type}, following nested record types it declares.
     *  Every declaration sharing the name is checked — see {@link #recordComponentsByType}. */
    private static List<String> credentialComponentsOf(String type, Map<String, List<String>> index,
                                                       Set<String> seen) {
        if (!seen.add(type) || !index.containsKey(type)) return List.of();
        var offending = new ArrayList<String>();
        for (var body : index.get(type)) {
            offending.addAll(componentsOfBody(type, body, index, seen));
        }
        return offending;
    }

    private static List<String> componentsOfBody(String type, String body,
                                                 Map<String, List<String>> index, Set<String> seen) {
        var offending = new ArrayList<String>();
        for (var component : splitComponents(body)) {
            var parts = component.split("\\s+");
            if (parts.length < 2) continue;
            var name = parts[parts.length - 1];
            var declaredType = String.join(" ", List.of(parts).subList(0, parts.length - 1));
            if (declaredType.contains("String") && CREDENTIAL_NAME.matcher(name).find()) {
                offending.add(type + "." + name);
            }
            for (var nested : index.keySet()) {
                if (declaredType.contains(nested)) {
                    offending.addAll(credentialComponentsOf(nested, index, seen));
                }
            }
        }
        return offending;
    }

    /** Split a record's component list on top-level commas, so {@code Map<String, String>} survives. */
    private static List<String> splitComponents(String signature) {
        var out = new ArrayList<String>();
        var current = new StringBuilder();
        int depth = 0;
        for (var ch : signature.toCharArray()) {
            if (ch == '<') depth++;
            if (ch == '>') depth--;
            if (ch == ',' && depth == 0) {
                out.add(current.toString().strip());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        out.add(current.toString().strip());
        return out.stream().filter(s -> !s.isEmpty()).toList();
    }
}
