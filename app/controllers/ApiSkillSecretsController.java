package controllers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import models.Agent;
import org.jspecify.annotations.Nullable;
import play.mvc.Controller;
import play.mvc.With;
import services.AgentService;
import services.SkillSecrets;
import services.SkillSettings;
import services.WorkspaceFiles;
import utils.ApiResponses;
import utils.WorkspacePathGuard;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static controllers.AgentAccess.Level.OPERATOR_ONLY;
import static utils.GsonHolder.GSON;

/**
 * The Secrets panel of an agent's skill (JF-323): list, create or replace, and delete. A secret's value goes in and
 * never comes back out, to the operator included. A setting (JF-357) is not secret, so its value is listed. Every
 * route is the operator's alone.
 */
@With(AuthCheck.class)
public class ApiSkillSecretsController extends Controller {

    public record SecretView(String name, List<String> hosts, String setAt) {}

    /**
     * A secret the skill's SKILL.md declares in its {@code secrets} block (JF-348). {@code setIn} is the skill of the agent that
     * holds it, since a name is used once on an agent; {@code set} says this skill can be given it, which it cannot
     * when the holder is another skill that is switched off.
     */
    public record ExpectedView(String name, String description, List<String> exampleHosts, boolean optional,
                               boolean set, @Nullable String setIn) {}

    public record SettingView(String name, String value, String setAt) {}

    /** A setting the skill's block declares (JF-357), with the same {@code set} and {@code setIn} as a secret. */
    public record ExpectedSettingView(String name, String description, String example, boolean optional, boolean set,
                                      @Nullable String setIn) {}

    /**
     * {@code missing} names the required declared secrets and settings not yet provided; the skill runs only when it
     * is empty.
     */
    public record SecretsResponse(List<SecretView> secrets, List<ExpectedView> expected, List<SettingView> settings,
                                  List<ExpectedSettingView> expectedSettings, List<String> missing) {}

    public record SettingWriteRequest(String value) {}

    /** {@code value} may be left out to keep the stored one and change only {@code hosts}. */
    public record SecretWriteRequest(@Nullable String value, List<String> hosts) {}

    public record SecretDeletedResponse(boolean deleted) {}

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = SecretsResponse.class)))
    @Operation(summary = "List a skill's secrets and settings on an agent, and the ones its SKILL.md declares: a secret's name, hosts and when it was set, never its value; a setting's value")
    @AgentAccess(value = OPERATOR_ONLY, reason = "names the secrets a skill holds and the hosts they reach")
    public static void list(Long id, String name) {
        var owner = requireOwner(id);
        var dir = requireSkill(owner, name);
        var views = new ArrayList<SecretView>();
        for (var listing : SkillSecrets.list(owner, name)) views.add(view(listing));
        var provided = SkillSecrets.provided(owner);
        var expected = new ArrayList<ExpectedView>();
        for (var d : SkillSecrets.declared(dir)) {
            expected.add(new ExpectedView(d.name(), d.description(), d.exampleHosts(), d.optional(),
                    provided.to(name, d.name()), provided.holder(d.name())));
        }
        var settings = new ArrayList<SettingView>();
        for (var listing : SkillSettings.list(owner, name)) {
            settings.add(new SettingView(listing.name(), listing.value(), listing.setAt().toString()));
        }
        var expectedSettings = new ArrayList<ExpectedSettingView>();
        for (var d : SkillSecrets.declaredSettings(dir)) {
            expectedSettings.add(new ExpectedSettingView(d.name(), d.description(), d.example(), d.optional(),
                    provided.to(name, d.name()), provided.holder(d.name())));
        }
        renderJSON(GSON.toJson(new SecretsResponse(views, expected, settings, expectedSettings, provided.missing(dir))));
    }

    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = SecretWriteRequest.class)))
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = SecretView.class)))
    @Operation(summary = "Create or replace a skill's secret on an agent, with the exact hosts it may be sent to")
    @AgentAccess(value = OPERATOR_ONLY, reason = "stores a credential a skill sends to the hosts named with it")
    public static void put(Long id, String name, String secret) {
        var owner = requireOwner(id);
        requireSkill(owner, name);
        var body = JsonBodyReader.readJsonBody();
        if (body == null) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST,
                    "The body must be a JSON object with 'hosts' and, for a new secret, 'value'.");
            throw ApiResponses.unreachable();
        }
        var value = stringOrNull(body, "value");
        var hosts = hosts(body);
        SkillSecrets.Listing saved;
        try {
            saved = SkillSecrets.put(owner, name, secret, value, hosts);
        } catch (SkillSecrets.Refused e) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, e.reason());
            throw ApiResponses.unreachable();
        }
        renderJSON(GSON.toJson(view(saved)));
    }

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = SecretDeletedResponse.class)))
    @Operation(summary = "Delete a skill's secret on an agent")
    @AgentAccess(value = OPERATOR_ONLY, reason = "deletes a credential a skill relies on")
    public static void delete(Long id, String name, String secret) {
        var owner = requireOwner(id);
        requireSkill(owner, name);
        if (!SkillSecrets.delete(owner, name, secret)) {
            ApiResponses.error(404, ApiResponses.NOT_FOUND, "No secret '%s' on skill '%s'.".formatted(secret, name));
            throw ApiResponses.unreachable();
        }
        renderJSON(GSON.toJson(new SecretDeletedResponse(true)));
    }

    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = SettingWriteRequest.class)))
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = SettingView.class)))
    @Operation(summary = "Create or replace a skill's setting on an agent: a value that is not secret, such as an address")
    @AgentAccess(value = OPERATOR_ONLY, reason = "sets where a skill's commands connect and as whom")
    public static void putSetting(Long id, String name, String setting) {
        var owner = requireOwner(id);
        requireSkill(owner, name);
        var body = JsonBodyReader.readJsonBody();
        var value = body == null ? null : stringOrNull(body, "value");
        if (value == null) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "The body must be a JSON object with 'value'.");
            throw ApiResponses.unreachable();
        }
        SkillSettings.Listing saved;
        try {
            saved = SkillSettings.put(owner, name, setting, value);
        } catch (SkillSecrets.Refused e) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, e.reason());
            throw ApiResponses.unreachable();
        }
        renderJSON(GSON.toJson(new SettingView(saved.name(), saved.value(), saved.setAt().toString())));
    }

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = SecretDeletedResponse.class)))
    @Operation(summary = "Delete a skill's setting on an agent")
    @AgentAccess(value = OPERATOR_ONLY, reason = "deletes a value a skill relies on")
    public static void deleteSetting(Long id, String name, String setting) {
        var owner = requireOwner(id);
        requireSkill(owner, name);
        if (!SkillSettings.delete(owner, name, setting)) {
            ApiResponses.error(404, ApiResponses.NOT_FOUND, "No setting '%s' on skill '%s'.".formatted(setting, name));
            throw ApiResponses.unreachable();
        }
        renderJSON(GSON.toJson(new SecretDeletedResponse(true)));
    }

    private static SecretView view(SkillSecrets.Listing listing) {
        return new SecretView(listing.name(), listing.hosts(), listing.setAt().toString());
    }

    private static Agent requireOwner(Long id) {
        var agent = AgentService.findById(id);
        if (agent == null) {
            ApiResponses.error(404, ApiResponses.NOT_FOUND, "No agent " + id + ".");
            throw ApiResponses.unreachable();
        }
        return WorkspaceFiles.workspaceOwner(agent);
    }

    private static Path requireSkill(Agent owner, String name) {
        var dir = WorkspacePathGuard.resolveChild(AgentService.workspacePath(owner.name).resolve("skills"), name);
        if (dir == null) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST,
                    "'%s' is not a skill name: pass the skill's folder name, not a path.".formatted(name));
            throw ApiResponses.unreachable();
        }
        if (!Files.isDirectory(dir)) {
            ApiResponses.error(404, ApiResponses.NOT_FOUND,
                    "Agent '%s' has no skill '%s'.".formatted(owner.name, name));
            throw ApiResponses.unreachable();
        }
        return dir;
    }

    private static @Nullable String stringOrNull(JsonObject body, String key) {
        var element = body.get(key);
        if (element == null || element.isJsonNull()) return null;
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "'%s' must be a string.".formatted(key));
            throw ApiResponses.unreachable();
        }
        return element.getAsString();
    }

    private static List<String> hosts(JsonObject body) {
        var element = body.get("hosts");
        if (!(element instanceof JsonArray array)) {
            ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "'hosts' must be a list of host names.");
            throw ApiResponses.unreachable();
        }
        var out = new ArrayList<String>();
        for (JsonElement host : array) {
            if (!host.isJsonPrimitive() || !host.getAsJsonPrimitive().isString()) {
                ApiResponses.error(400, ApiResponses.INVALID_REQUEST, "'hosts' must be a list of host names.");
                throw ApiResponses.unreachable();
            }
            out.add(host.getAsString());
        }
        return out;
    }
}
