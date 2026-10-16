import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.BflModelCatalog;
import services.BflModelCatalog.BflModel;
import services.BflModelCatalog.RequestShape;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Model discovery from Black Forest Labs' OpenAPI spec. The fixture keeps the real spec's shapes: a
 * plain prompt endpoint, endpoints that require an image or a finetune, a tool under
 * {@code flux-tools/}, utility routes, and the mode-switched {@code flux-3-video} union.
 */
class BflModelCatalogTest extends UnitTest {

    private static final String SPEC = """
            {"paths": {
              "/v1/flux-2-pro": {"post": {"tags": ["Models"], "summary": "Generate or edit an image with FLUX.2 [pro]",
                "requestBody": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/Flux2"}}}}}},
              "/v1/flux-pro-1.1": {"post": {"tags": ["Models"], "summary": "Generate an image with FLUX1.1 [pro]",
                "requestBody": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/Flux11"}}}}}},
              "/v1/flux-kontext-pro": {"post": {"tags": ["Models"], "summary": "Edit or create an image",
                "requestBody": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/Kontext"}}}}}},
              "/v1/flux-3-image": {"post": {"tags": ["Models"], "summary": "Generate an image with FLUX 3.",
                "requestBody": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/Flux3"}}}}}},
              "/v1/flux-moded": {"post": {"tags": ["Models"], "summary": "An image model that needs a mode",
                "requestBody": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/T2V"}}}}}},
              "/v1/flux-pro-1.0-fill": {"post": {"tags": ["Models"], "summary": "Inpaint an image",
                "requestBody": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/Fill"}}}}}},
              "/v1/flux-pro-1.1-ultra-finetuned": {"post": {"tags": ["Models"], "summary": "Finetuned",
                "requestBody": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/Finetuned"}}}}}},
              "/v1/flux-3-video": {"post": {"tags": ["Models"], "summary": "Generate a video with FLUX 3.",
                "requestBody": {"content": {"application/json": {"schema": {"anyOf": [
                  {"$ref": "#/components/schemas/T2V"}, {"$ref": "#/components/schemas/I2V"}]}}}}}},
              "/v1/flux-tools/video-edit-v1": {"post": {"tags": ["Models"], "summary": "Edit a video",
                "requestBody": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/Flux2"}}}}}},
              "/v1/delete_finetune": {"post": {"tags": ["Utility"], "summary": "Delete Finetune",
                "requestBody": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/Flux2"}}}}}},
              "/v1/get_result": {"get": {"tags": ["Utility"], "summary": "Get Result"}}
            },
            "components": {"schemas": {
              "Flux2": {"properties": {"prompt": {}, "input_image": {}}, "required": ["prompt"]},
              "Flux11": {"properties": {"prompt": {}, "width": {}}},
              "Kontext": {"properties": {"prompt": {}, "input_image": {}, "aspect_ratio": {"type": "string"}}, "required": ["prompt"]},
              "Flux3": {"additionalProperties": false, "required": ["prompt"], "properties": {"prompt": {}, "images": {},
                "aspect_ratio": {"anyOf": [{"type": "string", "enum": ["16:9", "1:1"]}, {"type": "string", "const": "auto"}]}}},
              "Fill": {"properties": {"image": {}, "prompt": {}}, "required": ["image"]},
              "Finetuned": {"properties": {"finetune_id": {}, "prompt": {}}, "required": ["finetune_id"]},
              "T2V": {"properties": {"prompt": {}, "mode": {}}, "required": ["prompt", "mode"]},
              "I2V": {"properties": {"prompt": {}, "mode": {}, "keyframes": {}}, "required": ["prompt", "mode", "keyframes"]}
            }}}
            """;

    private final List<String> requested = new ArrayList<>();

    private OkHttpClient canned(int code, String body) {
        Interceptor interceptor = chain -> {
            requested.add(chain.request().url().toString());
            return new Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("canned")
                    .body(ResponseBody.create(body, null))
                    .build();
        };
        return new OkHttpClient.Builder().addInterceptor(interceptor).build();
    }

    @Test
    void promptOnlyModelsAreSplitIntoImageAndVideo() {
        var catalog = new BflModelCatalog(canned(200, SPEC)).fetch("http://127.0.0.1:1/v1");

        assertEquals(List.of("flux-2-pro", "flux-3-image", "flux-kontext-pro", "flux-pro-1.1"),
                catalog.image().stream().map(BflModel::slug).toList(),
                "fill, the finetune and a mode-switched image model need more than a prompt; tools and "
                        + "utility routes are not models");
        assertEquals(List.of("flux-3-video"), catalog.video().stream().map(BflModel::slug).toList(),
                "the union qualifies through its text-to-video variant");
        assertEquals("Generate or edit an image with FLUX.2 [pro]", catalog.image().getFirst().description());
    }

    @Test
    void eachImageModelKeepsTheFieldsItAccepts() {
        var shapes = new BflModelCatalog(canned(200, SPEC)).fetch("http://127.0.0.1:1/v1").shapes();

        assertEquals(new RequestShape(Set.of("prompt", "images", "aspect_ratio"), List.of("16:9", "1:1")),
                shapes.get("flux-3-image"));
        assertEquals(new RequestShape(Set.of("prompt", "input_image", "aspect_ratio"), List.of()),
                shapes.get("flux-kontext-pro"), "a free-form ratio has no enum to carry");
        assertFalse(shapes.containsKey("flux-3-video"), "only image models are shaped");
    }

    @Test
    void theSpecIsReadFromTheRootOfTheConfiguredHost() {
        new BflModelCatalog(canned(200, SPEC)).fetch("http://127.0.0.1:1/v1/");

        assertEquals(List.of("http://127.0.0.1:1/openapi.json"), requested);
    }

    @Test
    void anUnreadableSpecYieldsNoModels() {
        var failed = new BflModelCatalog(canned(503, "unavailable")).fetch("http://127.0.0.1:1/v1");
        var garbled = new BflModelCatalog(canned(200, "<html>")).fetch("http://127.0.0.1:1/v1");

        assertTrue(failed.image().isEmpty() && failed.video().isEmpty(), failed.toString());
        assertTrue(garbled.image().isEmpty() && garbled.video().isEmpty(), garbled.toString());
    }
}
