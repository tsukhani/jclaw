package controllers;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import models.ApiToken;
import play.mvc.Controller;
import play.mvc.With;
import services.EventLogger;
import utils.TokenHasher;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

import static utils.GsonHolder.INSTANCE;

/**
 * CRUD for {@link ApiToken} rows (JCLAW-282).
 *
 * <ul>
 *   <li>{@code GET /api/api-tokens} — list the current admin's tokens
 *       (active + revoked, in that order, newest first)</li>
 *   <li>{@code POST /api/api-tokens} — mint a new token. The response
 *       carries the plaintext exactly once; subsequent listings only
 *       expose {@link ApiToken#displayPrefix}.</li>
 *   <li>{@code DELETE /api/api-tokens/&#123;id&#125;} — soft-delete via
 *       {@link ApiToken#revoke()}. Idempotent.</li>
 * </ul>
 *
 * <p><b>Auth: session cookie only.</b> Token CRUD is a privilege-
 * escalation surface — a stolen {@code full} token must not be usable
 * to mint a fresh one before the operator revokes it. {@link #requireSessionAuth}
 * rejects requests admitted via the bearer path with a clear 403.
 */
@With(AuthCheck.class)
public class ApiTokensController extends Controller {

    private static final Gson gson = INSTANCE;

    /** Response shape for {@link #list} / {@link #mint}. The plaintext
     *  is populated only by mint (and only on the response that creates
     *  the row); listing always returns {@code plaintext=null}. */
    public record ApiTokenView(
            Long id,
            String name,
            String displayPrefix,
            String scope,
            String ownerUsername,
            Instant createdAt,
            Instant lastUsedAt,
            Instant revokedAt,
            String plaintext) {

        static ApiTokenView of(ApiToken token) {
            return new ApiTokenView(
                    token.id,
                    token.name,
                    token.displayPrefix,
                    token.scope.name(),
                    token.ownerUsername,
                    token.createdAt,
                    token.lastUsedAt,
                    token.revokedAt,
                    null);
        }

        static ApiTokenView ofMint(ApiToken token, String plaintext) {
            return new ApiTokenView(
                    token.id,
                    token.name,
                    token.displayPrefix,
                    token.scope.name(),
                    token.ownerUsername,
                    token.createdAt,
                    token.lastUsedAt,
                    token.revokedAt,
                    plaintext);
        }
    }

    public record MintRequest(String name, String scope) {}

    public record RevokeResponse(boolean revoked) {}

    @ApiResponse(responseCode = "200", content = @Content(array = @ArraySchema(schema = @Schema(implementation = ApiTokenView.class))))
    public static void list() {
        requireSessionAuth();
        var owner = AuthCheck.configuredAdminUsername();
        List<ApiTokenView> rows = ApiToken.listForOwner(owner).stream()
                .map(ApiTokenView::of)
                .collect(Collectors.toList());
        renderJSON(gson.toJson(rows));
    }

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = ApiTokenView.class)))
    @RequestBody(required = true, content = @Content(schema = @Schema(implementation = MintRequest.class)))
    public static void mint() {
        requireSessionAuth();
        var body = JsonBodyReader.readJsonBody();
        if (body == null) { badRequest(); return; }

        var name = body.has("name") && !body.get("name").isJsonNull()
                ? body.get("name").getAsString().trim() : "";
        if (name.isEmpty()) {
            error(400, "Field 'name' is required");
        }
        if (name.length() > 100) {
            error(400, "Field 'name' must be 100 characters or fewer");
        }

        ApiToken.Scope scope = ApiToken.Scope.READ_ONLY;
        if (body.has("scope") && !body.get("scope").isJsonNull()) {
            var raw = body.get("scope").getAsString();
            try {
                scope = ApiToken.Scope.valueOf(raw.toUpperCase().replace('-', '_'));
            } catch (IllegalArgumentException e) {
                error(400, "Unknown scope '%s' (expected READ_ONLY or FULL)".formatted(raw));
            }
        }

        var plaintext = TokenHasher.mint();
        var row = new ApiToken();
        row.name = name;
        row.scope = scope;
        row.ownerUsername = AuthCheck.configuredAdminUsername();
        row.secretHash = TokenHasher.hash(plaintext);
        row.displayPrefix = TokenHasher.prefix(plaintext);
        row.save();

        EventLogger.info("auth",
                "API token minted: name=%s scope=%s prefix=%s".formatted(
                        name, scope.name(), row.displayPrefix));

        renderJSON(gson.toJson(ApiTokenView.ofMint(row, plaintext)));
    }

    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = RevokeResponse.class)))
    public static void revoke(Long id) {
        requireSessionAuth();
        var row = (ApiToken) ApiToken.findById(id);
        if (row == null) { notFound(); return; }

        var owner = AuthCheck.configuredAdminUsername();
        if (!owner.equals(row.ownerUsername)) {
            // The single-admin model means this branch is unreachable
            // today (all tokens belong to the same admin). It exists so
            // that when JClaw grows real multi-tenancy, the controller
            // already enforces ownership instead of leaking other users'
            // tokens through this endpoint.
            notFound();
            return;
        }

        if (row.isRevoked()) {
            // Idempotent: re-revoking a revoked token is a no-op rather
            // than a 409. The operator's intent is "this token must
            // stop working" and that's already true.
            renderJSON(gson.toJson(new RevokeResponse(true)));
            return;
        }

        row.revoke();
        row.save();
        EventLogger.info("auth",
                "API token revoked: id=%d name=%s prefix=%s".formatted(
                        row.id, row.name, row.displayPrefix));
        renderJSON(gson.toJson(new RevokeResponse(true)));
    }

    /** Token CRUD is session-only — see class Javadoc. Bearer-admitted
     *  requests 403 with a code that hints at the actual remediation
     *  (log in via the SPA), rather than letting an MCP-client agent
     *  loop until it figures out the call won't work. */
    private static void requireSessionAuth() {
        if (AuthCheck.isBearerRequest()) {
            response.status = 403;
            renderJSON("{\"error\":\"Token CRUD requires session login\",\"code\":\"session_required\"}");
        }
    }
}
