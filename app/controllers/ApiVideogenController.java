package controllers;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.Operation;
import models.MessageAttachment;
import models.VideoGenerationJob;
import play.mvc.Controller;
import play.mvc.With;
import services.AttachmentService;
import services.videogen.ReplicateVideoModelCatalog;
import services.videogen.VideoCapabilityProbe;
import services.videogen.VideoGenerationJobService;
import utils.ApiResponses;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static utils.GsonHolder.INSTANCE;

/**
 * Video-generation job status for the chat UI (JCLAW-234). The chat polls
 * {@code GET /api/videogen/jobs?ids=1,2,3} (~2s) while any pending video placeholder is non-terminal —
 * the SV-4 / JCLAW-513 transport decision was polling, not SSE, because a minutes-long job outlives the
 * per-turn chat stream. The UI swaps the generating-card for an inline player once a job reports
 * {@code SUCCEEDED} with a {@code resultAttachmentUuid}, and shows an error card on {@code FAILED}.
 */
@With(AuthCheck.class)
public class ApiVideogenController extends Controller {

    private static final String FIELD_STATE = "state";

    private static final Gson gson = INSTANCE;

    /** Per-request id cap — the chat only ever polls a handful of visible placeholders; this just bounds
     *  the work a single request can ask for (defense-in-depth), not an authorization control. */
    private static final int MAX_IDS = 50;

    /** GET /api/videogen/jobs?ids=1,2,3 — lightweight status for the chat poll loop.
     *
     *  <p>No owner-scoping: JClaw is single-admin Personal Edition — there is no {@code User} entity or
     *  owner FK (see {@code ApiToken}/{@code AuthCheck}), so the {@code AuthCheck}-gated operator owns
     *  every job. This mirrors {@code ApiConversationsController} / {@code ApiAttachmentsController},
     *  which likewise serve rows by id/uuid to the one authenticated operator. */
    @Operation(summary = "Status of video-generation jobs by id (chat progress polling)")
    public static void jobs(String ids) {
        var out = new ArrayList<LinkedHashMap<String, Object>>();
        if (ids != null && !ids.isBlank()) {
            var parts = ids.split(",");
            int limit = Math.min(parts.length, MAX_IDS);
            for (int i = 0; i < limit; i++) {
                var id = parseId(parts[i]);
                if (id == null) continue;
                VideoGenerationJob job = VideoGenerationJobService.findById(id);
                if (job != null) out.add(toStatusRow(job));
            }
        }
        renderJSON(gson.toJson(out));
    }

    /** Status row for one job — id, state, live progress, and the result uuid/size for the chat chip. */
    private static LinkedHashMap<String, Object> toStatusRow(VideoGenerationJob job) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", job.id);
        m.put(FIELD_STATE, job.state.name());
        m.put("percent", job.percent); // real 0..100 for the local engine (JCLAW-232); null for cloud (SV-1)
        m.put("errorMessage", job.errorMessage);
        // The placeholder is filled in-place, so until a reload the chat still holds sizeBytes=0;
        // hand it the result's uuid + size so the video chip can show the size live (JCLAW-234).
        MessageAttachment result = job.resultAttachmentId != null
                ? AttachmentService.findById(job.resultAttachmentId) : null;
        m.put("resultAttachmentUuid", result == null ? null : result.uuid);
        m.put("resultSizeBytes", result == null ? null : result.sizeBytes);
        return m;
    }

    /** GET /api/videogen/models — Replicate text-to-video models for the Settings model dropdown. */
    @Operation(summary = "Discover Replicate text-to-video models (Settings model dropdown)")
    public static void models() {
        renderJSON(gson.toJson(ReplicateVideoModelCatalog.textToVideoModels()));
    }

    /** GET /api/videogen/capability — adaptive local-engine capability snapshot for the Settings dropdown
     *  (SV-2 / JCLAW-232/233). The page polls this while the probe is PROBING, then renders the per-host
     *  tiered engine list (and greys out what won't run). */
    @Operation(summary = "Local video-gen host capability (GPU, free VRAM, per-engine runnable tiers)")
    public static void capability() {
        renderJSON(gson.toJson(VideoCapabilityProbe.snapshot()));
    }

    /** POST /api/videogen/capability/probe — kick off a background GPU/VRAM probe (one-shot
     *  {@code uv run serve.py --probe}). Returns immediately; progress is observed via {@link #capability}. */
    @Operation(summary = "Start a background local video-gen capability probe")
    @ChatHidden("runs a GPU capability subprocess -- resource action")
    public static void probeCapability() {
        VideoCapabilityProbe.probe();
        ApiResponses.ok(FIELD_STATE, "probing");
    }

    /** GET /api/videogen/jobs/recent — most-recent jobs for the dashboard Recent Activity (video view). */
    @Operation(summary = "Most-recent video-generation jobs (dashboard Recent Activity — video view)")
    public static void recent() {
        List<VideoGenerationJob> jobs = VideoGenerationJob.find("order by createdAt desc").fetch(20);
        var out = new ArrayList<LinkedHashMap<String, Object>>();
        for (var job : jobs) {
            var m = new LinkedHashMap<String, Object>();
            m.put("id", job.id);
            m.put(FIELD_STATE, job.state.name());
            m.put("prompt", job.prompt);
            m.put("percent", job.percent); // real 0..100 for the local engine (JCLAW-232); null for cloud (SV-1)
            m.put("errorMessage", job.errorMessage);
            m.put("conversationId", job.conversationId);
            m.put("createdAt", job.createdAt != null ? job.createdAt.toString() : null);
            out.add(m);
        }
        renderJSON(gson.toJson(out));
    }

    private static Long parseId(String s) {
        try {
            return Long.valueOf(s.trim());
        } catch (NumberFormatException _) {
            return null;
        }
    }

}
