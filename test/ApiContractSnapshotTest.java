import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.swagger.v3.core.util.Json;
import org.junit.jupiter.api.Test;
import play.Play;
import play.mvc.Router;
import play.plugins.openapi.OpenApiGenerator;
import play.test.UnitTest;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The backend half of the API contract (JF-17). The OpenAPI schemas of the response types the SPA reads most, with every
 * schema they reference, must equal {@code frontend/types/api-contract.openapi.json}. The frontend generates types from
 * that file and checks its hand-written ones against them ({@code frontend/types/api-contract.check.ts}), so renaming a
 * field of one of these Java types fails here, and once the file is regenerated, fails {@code pnpm typecheck} wherever
 * the SPA still reads the old name.
 */
class ApiContractSnapshotTest extends UnitTest {

    private static final Path SNAPSHOT = Path.of("frontend", "types", "api-contract.openapi.json");
    private static final Path RECEIVED = Path.of("frontend", "types", "api-contract.openapi.received.json");

    /** The response types behind the most-imported types in frontend/types/api.ts (counted 2026-10-22). */
    static final List<String> CONTRACT = List.of(
            "MessageView", "ConversationView", "MessageAttachmentView", "ScrapeJobView", "PromptView", "BreakerView", "AgentView", "TaskView");

    private static final Pattern REF = Pattern.compile("\"\\$ref\"\\s*:\\s*\"#/components/schemas/([^\"]+)\"");

    @Test
    void theContractSchemasMatchTheSnapshot() throws Exception {
        var spec = new OpenApiGenerator(Play.classloader, "jclaw", "contract").generate(Router.routes);
        var schemas = JsonParser.parseString(Json.mapper().writeValueAsString(spec)).getAsJsonObject()
                .getAsJsonObject("components").getAsJsonObject("schemas");
        var missing = CONTRACT.stream().filter(name -> !schemas.has(name)).toList();
        assertTrue(missing.isEmpty(), "not in the generated spec: " + missing + "; it has " + new TreeSet<>(schemas.keySet()));

        var picked = new JsonObject();
        var pending = new ArrayDeque<>(CONTRACT);
        while (!pending.isEmpty()) {
            var name = pending.pop();
            if (picked.has(name)) continue;
            JsonElement schema = schemas.get(name);
            picked.add(name, schema);
            REF.matcher(schema.toString()).results().forEach(ref -> pending.push(ref.group(1)));
        }

        var document = new JsonObject();
        document.addProperty("openapi", spec.getOpenapi());
        var info = new JsonObject();
        info.addProperty("title", "jclaw API contract");
        info.addProperty("version", "1");
        document.add("info", info);
        document.add("paths", new JsonObject());
        var components = new JsonObject();
        components.add("schemas", picked);
        document.add("components", components);
        ApprovedText.assertApproved(SNAPSHOT, RECEIVED, ApprovedText.canonicalJson(document),
                "run pnpm gen:api-contract in frontend/ and fix what pnpm typecheck reports");
    }
}
