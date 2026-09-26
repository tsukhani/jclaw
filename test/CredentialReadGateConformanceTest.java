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
 * <p>The stance rule ({@code CapabilityRulesTest}) decides which routes an agent may call, reads
 * included, but not what a read returns: a GET that leaks is a masking problem at the seam. The seam is the right place — what was missing is any
 * check that it was used. Masking is applied correctly in eight files today, arrived at over four
 * tickets, and entirely by memory; a new read that returns a secret gets none unless its author
 * happens to remember, and the omission looks exactly like a decision.
 *
 * <p><b>Why a source scan rather than an ArchUnit rule.</b> A render goes through
 * {@code gson.toJson} on an arbitrary object graph, so no bytecode rule can follow a field to the
 * response without dataflow ArchUnit does not do. The hook used instead already exists: most
 * {@code GET /api} routes declare their response type in the Swagger
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
     * The GET routes the gate cannot see, named one by one (JCLAW-1268).
     *
     * <p>A list rather than a count, and the difference is the whole point: a count stays flat
     * when someone declares one route and adds another undeclared one, so the gap would look
     * closed while a fresh blind spot opened. Naming them makes every change to the set a diff.
     *
     * <p>Shrinking this is the goal — delete a line once its route declares
     * {@code @ApiResponse(implementation = ...)}. Thirty-two of these render a typed value and
     * could be declared today; they are left because the credential-bearing surfaces (bindings,
     * providers, config, MCP) all declare already and are checked, so annotating the rest is
     * mostly OpenAPI hygiene and a mis-typed annotation would point the gate at the wrong class.
     * The two that cannot be declared at all render an anonymous {@code Map.of}.
     */
    private static final Set<String> UNCHECKABLE_GET_ROUTES = Set.of(
            "GET /api/agents/{agentId}/core-migration",
            "GET /api/agents/{id}/files/{<.+>filePath}",
            "GET /api/agents/{id}/prompt-text",
            "GET /api/agents/{id}/shell/effective-allowlist",
            "GET /api/agents/{id}/tool-approvals",
            "GET /api/agents/{id}/workspace-backup",
            "GET /api/agents/{id}/workspace-download/{<.+>path}",
            "GET /api/agents/{id}/workspace-tree",
            "GET /api/agents/{id}/workspace/{filename}",
            "GET /api/apps/{slug}/files/{uuid}",
            "GET /api/attachments/{uuid}",
            "GET /api/channels/whatsapp/bindings/{id}/qr",
            "GET /api/conversations/channels",
            "GET /api/events",
            "GET /api/imagegen/capability",
            "GET /api/imagegen/models",
            "GET /api/imagegen/progress",
            "GET /api/memories/reembed",
            "GET /api/metrics/latency",
            "GET /api/metrics/latency/rows",
            "GET /api/notifications",
            "GET /api/prompts/export",
            "GET /api/skills/catalogs",
            "GET /api/system/database/backups/{id}/download",
            "GET /api/tailscale",
            "GET /api/tasks/{id}/delivery-advisory",
            "GET /api/timezones",
            "GET /api/tool-approvals/summary",
            "GET /api/videogen/capability",
            "GET /api/videogen/jobs",
            "GET /api/videogen/jobs/recent",
            "GET /api/videogen/models",
            "GET /api/videogen/state",
            "GET /api/webhooks/whatsapp"
    );

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
    void theUncheckableGetSurfaceIsExactlyTheseRoutes() throws IOException {
        var undeclared = new TreeSet<String>();
        for (var route : getRoutes().keySet()) {
            if (!declaredResponseTypes().containsKey(route)) undeclared.add(route);
        }

        var appeared = new TreeSet<>(undeclared);
        appeared.removeAll(UNCHECKABLE_GET_ROUTES);
        var closed = new TreeSet<>(UNCHECKABLE_GET_ROUTES);
        closed.removeAll(undeclared);

        assertTrue(appeared.isEmpty(),
                "these GET routes declare no @ApiResponse(implementation = ...), so the gate above "
                        + "cannot see what they return. Declare the response type: " + appeared);
        assertTrue(closed.isEmpty(),
                "these routes now declare a response type, so the gate checks them — delete them "
                        + "from UNCHECKABLE_GET_ROUTES: " + closed);
    }

    /**
     * {@code ApiProvidersController.discoverModels} carries an {@code OPEN}
     * {@link controllers.AgentAccess} whose reason names SsrfGuard as one of the two seams keeping
     * that route safely open. Nothing
     * verified it, so the annotation asserted a property the build did not check — and an
     * annotation trusted for a guarantee it does not carry is worse than no annotation
     * (JCLAW-1268).
     *
     * <p>Checked as a reachability claim across two files rather than by running the route: the
     * assertion is that the seam is still on the path, which is what the reason says. Whether
     * SsrfGuard itself refuses the right ranges is {@code SsrfGuardTest}'s job.
     *
     * <p>Read through {@code withoutComments} because the first version of this check did not, and
     * commenting the seam out left it green — the call survived as text inside the comment that
     * disabled it.
     */
    @Test
    void theProvidersAnnotationsSsrfClaimIsTrue() throws IOException {
        var controller = ResourceLeakGateConformanceTest.withoutComments(
                Files.readString(repo("app/controllers/ApiProvidersController.java")));
        var at = controller.indexOf("public static void discoverModels(");
        assertTrue(at > 0, "ApiProvidersController.discoverModels not found — this check is stale");
        assertTrue(controller.substring(Math.max(0, at - 700), at).contains("SsrfGuard"),
                "the @AgentAccess reason on discoverModels no longer names SsrfGuard; either it stopped "
                        + "claiming the seam, or this check is pinned to the wrong action");

        var body = controller.substring(at, Math.min(controller.length(), at + 1200));
        assertTrue(body.contains("ModelDiscoveryService.discover"),
                "discoverModels no longer routes through ModelDiscoveryService, so the claimed seam is "
                        + "no longer on its path: " + body.lines().limit(12).toList());

        var service = ResourceLeakGateConformanceTest.withoutComments(
                Files.readString(repo("app/services/ModelDiscoveryService.java")));
        assertTrue(service.contains("SsrfGuard.assertProviderUrlSafe"),
                "ModelDiscoveryService no longer screens the provider URL, so the annotation on "
                        + "ApiProvidersController.discoverModels is now claiming a seam that is gone");
        assertTrue(service.contains("llmSingleShotGuarded"),
                "ModelDiscoveryService no longer uses the SSRF-guarded transport, which is the half "
                        + "of the seam that closes the DNS-rebinding window");
    }

    /** Guards the two assertions above against a parser that quietly stopped matching. */
    @Test
    void theScanActuallyReadsTheRouteTableAndTheAnnotations() throws IOException {
        assertTrue(getRoutes().size() >= 100,
                "expected 100+ GET /api routes, found " + getRoutes().size()
                        + " — the routes parser stopped matching and both checks above would pass vacuously");
        assertFalse(UNCHECKABLE_GET_ROUTES.isEmpty(), "the uncheckable list is empty");
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
