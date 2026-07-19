package controllers;

import agents.AgentRunner;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.Agent;
import models.MessageAttachment;
import play.Play;
import play.data.Upload;
import play.mvc.Controller;
import play.mvc.With;
import services.AgentService;
import services.AppInvokeLimits;
import services.AttachmentService;
import services.ConversationService;
import services.Tx;
import services.UploadStaging;
import utils.ApiResponses;
import utils.GsonHolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * JCLAW-764 / AD-2: the single-purpose App → Agent invoke endpoint. A hosted app
 * POSTs to {@code POST /api/apps/<slug>/invoke} and its one operator-designated
 * agent — resolved fail-closed from {@code app.json.agent} (AD-3) — runs on the
 * text input, returning the agent's response. There is <em>no</em> target-agent /
 * endpoint / verb parameter: the agent is resolved solely from {@code <slug>},
 * never from the request body. {@link AppOriginGate} (in {@link AuthCheck})
 * guarantees an app-originated caller can reach only this route for its own slug.
 *
 * <p>Each invoke runs in a fresh app-owned {@code Conversation}
 * ({@code channelType="app"}, {@code peerId=slug}), so it is viewable and
 * filterable in the operator's Conversations page under the {@code app} channel
 * (AD-4). Being a non-{@code web} origin, an app turn is untrusted, so a dangerous
 * tool such as {@code exec} fails closed for it (see {@code utils.ChannelOriginTrust}
 * / JCLAW-777) — apps may feed the agent untrusted content (e.g. an uploaded RFP).
 */
@With(AuthCheck.class)
public class ApiAppInvokeController extends Controller {

    /** Same slug shape as {@code ApiAppsController} — a single lowercase path segment. */
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]*$");
    private static final String APP_JSON = "app.json";

    /** Channel type stamped on every app-owned conversation, so they group under one
     *  "app" channel in the operator's Conversations page. */
    private static final String APP_CHANNEL = "app";

    public static void invoke(String slug, Upload[] files) {
        var agent = resolveDesignatedAgent(slug); // AD-3 — fail-closed 4xx on any miss
        // AD-2: input is the multipart "message" field plus optional file uploads of
        // any type. No target-agent / verb parameter is honored — the agent is resolved
        // solely from the slug. Uploads go through the SHARED UploadStaging path, so an
        // app upload is bound by the same size/type limits as chat (no new bypass).
        var message = params.get("message");
        var hasFiles = files != null && files.length > 0;
        if ((message == null || message.isBlank()) && !hasFiles) {
            ApiResponses.error(400, "no_input", "invoke requires a 'message' and/or file uploads");
        }
        // AD-5 / JCLAW-766: server-side per-app rate limit, checked here — after input
        // validation but before any real work (staging, the agent run) — so a runaway app
        // can't drain cost. The effective cap is the app.json override tightened into the
        // authoritative ceiling; over-limit calls are rejected until the window rolls.
        if (!AppInvokeLimits.tryAcquire(slug, AppInvokeLimits.effectiveLimit(readLimitOverride(slug)))) {
            ApiResponses.error(429, "rate_limited",
                    "This app has reached its invoke limit; try again shortly");
        }
        var attachments = hasFiles ? UploadStaging.stage(agent, files) : List.<AttachmentService.Input>of();
        // AD-4: a fresh app-owned conversation (channelType="app", peerId=slug), viewable
        // under the app channel. create() (not findOrCreate) so each invoke is its own row.
        var conversation = ConversationService.create(agent, APP_CHANNEL, slug);
        // AD-6: reuse the existing agent-run pipeline — no parallel execution stack.
        var result = AgentRunner.run(agent, conversation, message == null ? "" : message, attachments);
        var resp = new HashMap<String, Object>();
        resp.put("conversationId", conversation.id);
        resp.put("response", result.response());
        // AD-5 / JCLAW-765: any file the run produced (e.g. a documents-tool PDF) comes
        // back as a slug-scoped download URL the app is permitted to fetch (see file()).
        resp.put("files", producedFiles(slug, conversation.id));
        renderJSON(GsonHolder.INSTANCE.toJson(resp));
    }

    /**
     * JCLAW-765 / AD-5: serve ONE agent-produced file for THIS app. Two independent
     * scopes guard it: {@link AppOriginGate} lets an app-originated caller reach only
     * its own {@code /api/apps/<slug>/files/...} route, and this method additionally
     * requires the attachment's conversation to be this app's own ({@code channelType="app"},
     * {@code peerId=slug}) — so one app can never fetch another app's (or a chat's)
     * attachment by guessing a uuid. Only finalized, {@code generated}, non-deleted rows
     * are served.
     */
    public static void file(String slug, String uuid) {
        if (slug == null || !SLUG.matcher(slug).matches()) {
            notFound();
        }
        var att = MessageAttachment.findByUuid(uuid);
        if (att == null || att.deleted || !att.generated) {
            notFound();
        }
        var conv = att.message.conversation;
        if (!APP_CHANNEL.equals(conv.channelType) || !slug.equals(conv.peerId)) {
            notFound(); // not this app's file — indistinguishable from a missing one
        }

        // storagePath is workspace-relative "<agentName>/attachments/<convId>/<uuid>.<ext>";
        // strip the agent-name prefix so acquireWorkspacePath does its lexical + canonical
        // containment check inside the workspace root (mirrors ApiAttachmentsController.download).
        var agentName = conv.agent.name;
        var prefix = agentName + "/";
        if (!att.storagePath.startsWith(prefix)) {
            notFound();
        }
        var relPath = att.storagePath.substring(prefix.length());
        Path path;
        try {
            path = AgentService.acquireWorkspacePath(agentName, relPath);
        } catch (SecurityException _) {
            forbidden();
            return; // javac definite-assignment: path is unassigned on this catch path
        }
        var f = path.toFile();
        if (!f.exists() || !f.isFile()) {
            notFound();
        }
        response.setHeader("Content-Type", att.mimeType);
        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + safeHeaderFilename(att.originalFilename) + "\"");
        response.setHeader("Cache-Control", "private, max-age=300");
        renderBinary(f);
    }

    /**
     * AD-5 / JCLAW-765: the agent-produced downloadable files for this invoke's
     * conversation, each as {@code {filename, mimeType, sizeBytes, url}}. The {@code url}
     * is the slug-scoped {@code GET /api/apps/<slug>/files/<uuid>} the app may fetch; the
     * bytes were persisted as {@code generated} attachments by the run pipeline (e.g.
     * {@code DocumentsTool.executeRich}). Empty when the run produced nothing.
     */
    private static List<Map<String, Object>> producedFiles(String slug, long conversationId) {
        List<MessageAttachment> rows = MessageAttachment.find(
                "message.conversation.id = ?1 and generated = true and deleted = false ORDER BY id ASC",
                conversationId).fetch();
        var files = new ArrayList<Map<String, Object>>(rows.size());
        for (var att : rows) {
            var f = new HashMap<String, Object>();
            f.put("filename", att.originalFilename);
            f.put("mimeType", att.mimeType);
            f.put("sizeBytes", att.sizeBytes);
            f.put("url", "/api/apps/" + slug + "/files/" + att.uuid);
            files.add(f);
        }
        return files;
    }

    /** Strip characters that could break the {@code Content-Disposition} header (quotes,
     *  backslash, CR/LF). Generated filenames are already sanitized upstream; this is
     *  defense in depth at the header boundary. */
    private static String safeHeaderFilename(String name) {
        if (name == null || name.isBlank()) {
            return "download";
        }
        return name.replaceAll("[\"\\\\\\r\\n]", "_");
    }

    /**
     * AD-3: resolve the app's designated agent from {@code app.json.agent} (the
     * agent's id, stored as a string — see {@code apps.vue}). A missing manifest,
     * missing/blank field, non-numeric value, or unknown/deleted agent fails closed
     * ({@code 4xx}) — never a default agent.
     */
    private static Agent resolveDesignatedAgent(String slug) {
        var manifest = manifestPath(slug);
        if (manifest == null || !Files.isRegularFile(manifest)) {
            ApiResponses.error(404, "no_such_app", "No such app: " + slug);
        }
        var agentIdStr = readAgentId(manifest, slug); // throws 4xx if manifest unreadable / no agent
        var agentId = parseAgentId(agentIdStr, slug);  // throws 400 if non-numeric
        var agent = Tx.run(() -> AgentService.findById(agentId));
        if (agent == null) {
            ApiResponses.error(400, "unknown_agent",
                    "App '" + slug + "' designates an agent that no longer exists");
        }
        return agent;
    }

    /**
     * The validated {@code app.json} path for a slug, or {@code null} when the slug is
     * malformed or the resolved path would escape the apps directory. Does not require
     * the file to exist. Single source of the traversal-safe path shared by
     * {@link #resolveDesignatedAgent} and {@link #readLimitOverride}.
     */
    private static Path manifestPath(String slug) {
        if (slug == null || !SLUG.matcher(slug).matches()) {
            return null;
        }
        var appsDir = Play.getFile("public/apps").toPath().toAbsolutePath().normalize();
        var manifest = appsDir.resolve(slug).resolve(APP_JSON).normalize();
        return manifest.startsWith(appsDir) ? manifest : null;
    }

    /**
     * AD-5 / JCLAW-766: the optional per-app invoke-limit override from
     * {@code app.json.limit}, or {@code null} when absent, unreadable, or non-numeric —
     * {@link AppInvokeLimits} then applies the global default. The value only ever
     * tightens the effective limit; it can never raise it above the ceiling.
     */
    private static Integer readLimitOverride(String slug) {
        var manifest = manifestPath(slug);
        if (manifest == null || !Files.isRegularFile(manifest)) {
            return null;
        }
        try {
            var m = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
            if (m.has("limit") && !m.get("limit").isJsonNull()) {
                return m.get("limit").getAsInt();
            }
        } catch (RuntimeException | java.io.IOException _) {
            return null; // malformed manifest / non-numeric limit → fall back to the default
        }
        return null;
    }

    /** The raw {@code agent} field of the manifest, or fail-closed 4xx when absent. */
    private static String readAgentId(Path manifest, String slug) {
        JsonObject m;
        try {
            m = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
        } catch (Exception e) {
            ApiResponses.error(404, "no_such_app", "App manifest unreadable: " + slug);
            return null; // unreachable — error() threw
        }
        var v = (m.has("agent") && !m.get("agent").isJsonNull()) ? m.get("agent").getAsString() : null;
        if (v == null || v.isBlank()) {
            ApiResponses.error(400, "no_agent", "App '" + slug + "' has no designated agent");
        }
        return v;
    }

    private static long parseAgentId(String agentIdStr, String slug) {
        try {
            return Long.parseLong(agentIdStr.trim());
        } catch (NumberFormatException e) {
            ApiResponses.error(400, "bad_agent", "App '" + slug + "' has an invalid agent designation");
            return -1; // unreachable — error() threw
        }
    }
}
