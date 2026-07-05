package tools;

import agents.GeneratedAttachment;
import agents.ToolAction;
import agents.ToolContext;
import agents.ToolRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.Agent;
import models.MessageAttachment;
import services.AgentService;
import services.Tx;
import services.transcription.DiarizationPipeline;
import services.transcription.DiarizationRouter;
import services.transcription.DiarizedTranscript;
import services.transcription.OverlapReattributor;
import services.transcription.SpeakerClipExtractor;
import services.transcription.SpeakerNamer;
import services.transcription.TranscriptionException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code diarize_audio} (JCLAW-559): on-demand speaker identification for an
 * audio attachment in the current conversation. The ordinary inbound-audio
 * path stays on the fast single-speaker transcription service; this tool is
 * how "who said what in this recording?" gets answered — the model invokes
 * it, the full pipeline runs (whisper segments + pyannote-sidecar
 * diarization + enrolled-speaker naming when {@code data/speaker-voices/}
 * has enrollment, plus the JCLAW-605..613 correction passes), and the
 * speaker-attributed transcript comes back as the tool result for the
 * model to reason over.
 *
 * <p>Attachment resolution: an explicit {@code attachment_uuid} wins;
 * otherwise the newest non-deleted audio attachment in the scoped
 * conversation ({@link ToolContext#conversationId()}). Task fires run with
 * no conversation scope and get a clear error instead.
 */
public class DiarizeAudioTool implements ToolRegistry.Tool {

    public static final String TOOL_NAME = "diarize_audio";

    private static final String ARG_ACTION = "action";
    private static final String ARG_UUID = "attachment_uuid";
    private static final String ARG_NUM_SPEAKERS = "num_speakers";
    private static final String ARG_LANGUAGE = "language";
    private static final String ARG_SPEAKER_NAME = "speaker_name";
    private static final String ARG_CLIP_LABEL = "clip_label";
    private static final String ARG_ALLOW_ANONYMOUS = "allow_anonymous";

    private static final String ACTION_DIARIZE = "diarize";
    private static final String ACTION_ENROLL = "enroll_speaker";
    private static final String ACTION_EXTRACT = "extract_speaker_clips";
    private static final Set<String> ACTIONS = Set.of(ACTION_DIARIZE, ACTION_ENROLL, ACTION_EXTRACT);

    /** Clip labels are tool-assigned ({@code voice-1}…) — anything else in
     *  {@code clip_label} is rejected before it can touch a path. */
    private static final java.util.regex.Pattern CLIP_LABEL_PATTERN =
            java.util.regex.Pattern.compile("^voice-\\d{1,3}$");

    private static final double CLIP_TARGET_SECONDS = 5.0;
    private static final double CLIP_MIN_SECONDS = 1.0;
    /** Total pure speech harvested per speaker for enrollment (JCLAW-609):
     *  the lineup clip plus hidden reference clips until this many seconds
     *  are on file (when the recording has that much pure speech) — matching
     *  the 20s weight of the matcher's in-recording centroid references. */
    private static final double ENROLLMENT_TARGET_SECONDS = 20.0;

    /** Display names become enrollment folder names — letters/digits/space
     *  and a few name punctuation marks, no separators, no leading dot. */
    private static final java.util.regex.Pattern SPEAKER_NAME_PATTERN =
            java.util.regex.Pattern.compile("^[\\p{L}\\p{N}][\\p{L}\\p{N} _'.-]{0,63}$");

    @Override public String name() { return TOOL_NAME; }
    @Override public String category() { return "Utilities"; }
    @Override public String icon() { return "mic"; }

    @Override
    public List<ToolAction> actions() {
        return List.of(
                new ToolAction(ACTION_DIARIZE,
                        "Identify who spoke when and how — speaker-attributed transcript with "
                                + "enrolled names and per-turn emotion labels"),
                new ToolAction(ACTION_ENROLL,
                        "Save a voice reference under a name (whole attachment or a staged clip_label)"),
                new ToolAction(ACTION_EXTRACT,
                        "Cut a short clip of every speaker and play the lineup back for identification")
        );
    }

    @Override
    public String description() {
        return """
                Speaker tools for an uploaded audio recording. Action 'diarize' (default) identifies \
                who spoke when — use it only when the user asks to identify, separate, or label the \
                speakers in a recording; ordinary voice notes are already transcribed automatically. \
                Identification-first: it matches the voices against enrolled speakers BEFORE \
                transcribing. If every voice is recognized (or 'allow_anonymous' is true) it \
                returns the full transcript — one line per turn, labeled with speaker names (or \
                SPEAKER_00-style tags), plus a per-turn emotion label in parentheses (happy, sad, \
                angry, disgust, fear, surprised, neutral) classified from the voice's acoustics — \
                tone, not word choice, so it works across languages. If some voices are NOT \
                recognized, it skips transcription and instead attaches a playable clip per unknown \
                voice with instructions: ask the user who each voice is, enroll the answers, then \
                call diarize again (fast — the diarization is cached) or pass allow_anonymous=true \
                if the user declines. allow_anonymous is only honored after that identification \
                step has run once for the attachment — never set it on a first call. Transcription \
                takes roughly a tenth of the recording's duration. \
                Action 'enroll_speaker' saves a voice reference for \
                'speaker_name', so future diarizations label that voice by name — use it when the \
                user asks to remember/enroll a voice or store an audio file under the speaker-voices \
                folder; it enrolls either the whole attachment or, with 'clip_label', one clip staged \
                by extract_speaker_clips. Action 'extract_speaker_clips' cuts a short sample of every \
                speaker in the recording and attaches each as its own playable file named after its \
                label (voice-1.wav, ...) — use it when the user wants to hear the speakers \
                individually or enroll the people in a recording without separate per-person \
                samples: ask the user who each voice is, then enroll each answer via enroll_speaker \
                with its clip_label. Attachment-based actions \
                default to the most recent audio attachment in this conversation; pass \
                'attachment_uuid' to pick a specific one. For diarize/extract, set 'num_speakers' \
                when the user states the exact count; for diarize, 'language' (ISO 639-1, e.g. 'ms') \
                forces the transcription language.""";
    }

    @Override
    public String summary() {
        return "Identify who spoke when in a recording, or enroll a voice for future identification.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                SchemaKeys.TYPE, SchemaKeys.OBJECT,
                SchemaKeys.PROPERTIES, Map.of(
                        ARG_ACTION, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.ENUM, List.of(ACTION_DIARIZE, ACTION_ENROLL, ACTION_EXTRACT),
                                SchemaKeys.DESCRIPTION,
                                "What to do with the recording. Defaults to 'diarize'."),
                        ARG_UUID, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.DESCRIPTION,
                                "UUID of the audio attachment to use. Omit to use the most recent "
                                        + "audio attachment in this conversation."),
                        ARG_ALLOW_ANONYMOUS, Map.of(SchemaKeys.TYPE, "boolean",
                                SchemaKeys.DESCRIPTION,
                                "diarize only: produce the transcript even when voices are not "
                                        + "enrolled, labeling them SPEAKER_00-style. Set it when the "
                                        + "user explicitly does not care about speaker names or has "
                                        + "declined to identify the voices."),
                        ARG_NUM_SPEAKERS, Map.of(SchemaKeys.TYPE, SchemaKeys.INTEGER,
                                SchemaKeys.DESCRIPTION,
                                "diarize only: exact number of speakers, when the user states it. "
                                        + "Omit when unknown."),
                        ARG_LANGUAGE, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.DESCRIPTION,
                                "diarize only: optional ISO 639-1 language code to force (e.g. 'en', "
                                        + "'ms'). Omit to follow the configured whisper model's default."),
                        ARG_SPEAKER_NAME, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.DESCRIPTION,
                                "enroll_speaker only: the person's display name — future diarized "
                                        + "transcripts label this voice with it."),
                        ARG_CLIP_LABEL, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.DESCRIPTION,
                                "enroll_speaker only: enroll a clip staged by extract_speaker_clips "
                                        + "(e.g. 'voice-2') instead of a whole attachment.")
                ),
                SchemaKeys.REQUIRED, List.of()
        );
    }

    /** Minutes of serialized native inference; never worth racing in a parallel round. */
    @Override public boolean parallelSafe() { return false; }

    @Override
    public String execute(String argsJson, Agent agent) {
        // Text-only fallback; the dispatcher uses executeRich (which also
        // carries the extract action's playback file). One code path.
        return executeRich(argsJson, agent).text();
    }

    @Override
    public ToolRegistry.ToolResult executeRich(String argsJson, Agent agent) {
        JsonObject args;
        try {
            args = JsonParser.parseString(argsJson == null || argsJson.isBlank() ? "{}" : argsJson)
                    .getAsJsonObject();
        } catch (RuntimeException _) {
            return ToolRegistry.ToolResult.text("Error: invalid arguments for " + TOOL_NAME + ".");
        }

        var action = optString(args, ARG_ACTION);
        if (action == null || action.isBlank()) action = ACTION_DIARIZE;
        if (!ACTIONS.contains(action)) {
            return ToolRegistry.ToolResult.text("Error: unknown action '%s' (expected %s).".formatted(
                    action, String.join(", ", ACTIONS)));
        }

        // Staged-clip enrollment references a clip by label, not an
        // attachment — no attachment resolution on that path.
        var clipLabel = optString(args, ARG_CLIP_LABEL);
        if (ACTION_ENROLL.equals(action) && clipLabel != null && !clipLabel.isBlank()) {
            return ToolRegistry.ToolResult.text(
                    enrollFromStaging(clipLabel, optString(args, ARG_SPEAKER_NAME)));
        }

        // Tool dispatch runs on a bare virtual thread with no JPA context
        // (JCLAW-559 UAT finding) — DB reads need their own transaction, the
        // same Tx.run discipline the other DB-touching tools use. Only the
        // lookup runs inside it; the minutes of native inference below must
        // never hold a transaction open.
        var attachment = Tx.run(() -> resolveAttachment(optString(args, ARG_UUID)));
        if (attachment.error() != null) return ToolRegistry.ToolResult.text(attachment.error());
        var att = attachment.value();

        var path = AgentService.workspaceRoot().resolve(att.storagePath);
        if (!Files.isRegularFile(path)) {
            return ToolRegistry.ToolResult.text(
                    "Error: the audio file for attachment %s is missing from storage.".formatted(att.uuid));
        }

        Integer numSpeakers = optInt(args, ARG_NUM_SPEAKERS);
        if (ACTION_ENROLL.equals(action)) {
            return ToolRegistry.ToolResult.text(
                    enrollSpeaker(path, att, optString(args, ARG_SPEAKER_NAME)));
        }
        if (ACTION_EXTRACT.equals(action)) {
            return extractSpeakerClips(path, att, numSpeakers);
        }
        boolean allowAnonymous = args.has(ARG_ALLOW_ANONYMOUS)
                && !args.get(ARG_ALLOW_ANONYMOUS).isJsonNull()
                && args.get(ARG_ALLOW_ANONYMOUS).getAsBoolean();
        return diarize(path, att, numSpeakers, optString(args, ARG_LANGUAGE), allowAnonymous);
    }

    private static ToolRegistry.ToolResult diarize(Path path, MessageAttachment att,
                                                   Integer numSpeakers, String language,
                                                   boolean allowAnonymous) {
        try {
            // The anonymous escape hatch is only honored AFTER identification
            // was actually attempted in this conversation (JCLAW-611 UAT: the
            // model set allow_anonymous on its very first call, skipping the
            // flow it was told to follow — determinism beats instructions).
            if (allowAnonymous && !identificationAttempted(att)) {
                play.Logger.info("DiarizeAudioTool: ignoring allow_anonymous before any "
                        + "identification attempt for %s", att.uuid);
                allowAnonymous = false;
            }
            // JCLAW-628: the stage sequence lives in DiarizationPipeline —
            // identification-first is this tool's mode (it can converse).
            var outcome = DiarizationPipeline.run(path, new DiarizationPipeline.Options(
                    numSpeakers, language, true, allowAnonymous));
            if (outcome instanceof DiarizationPipeline.IdentificationNeeded need) {
                return identificationRequest(path, att, need.diarization(),
                        need.names(), need.unmatched());
            }
            var entries = ((DiarizationPipeline.Transcript) outcome).entries();

            var labels = new LinkedHashSet<String>();
            entries.forEach(e -> labels.add(e.speaker()));
            var anonymous = labels.stream().filter(l -> l.startsWith("SPEAKER_")).toList();
            var note = anonymous.isEmpty() ? "" : ("%n%n(Anonymous transcript as requested — "
                    + "the user can enroll %s later via extract_speaker_clips + enroll_speaker.)")
                    .formatted(String.join(", ", anonymous));
            return ToolRegistry.ToolResult.text(
                    "Diarized transcript of %s — %d speaker(s) detected: %s%n%n%s%s".formatted(
                            att.originalFilename, labels.size(), String.join(", ", labels),
                            DiarizedTranscript.toText(entries), note));
        } catch (TranscriptionException e) {
            return ToolRegistry.ToolResult.text("Speaker diarization failed: " + e.getMessage());
        }
    }

    /**
     * JCLAW-611: some voices matched no enrolled speaker — stop before the
     * expensive transcription and hand the agent everything it needs to
     * resolve identities: purity-gated lineup clips for the UNKNOWN voices
     * only, plus the follow-up script. Recognized voices are reported so
     * partially-enrolled recordings only ask about the new people.
     */
    private static ToolRegistry.ToolResult identificationRequest(
            Path path, MessageAttachment att, DiarizationRouter.Result diarization,
            Map<Integer, String> names, List<Integer> unmatched) {
        var known = names.isEmpty() ? "none"
                : String.join(", ", new java.util.TreeMap<>(names).values());
        var preamble = ("Transcription NOT run yet (identification-first): %d voice(s) in %s "
                + "did not match any enrolled speaker (recognized: %s).")
                .formatted(unmatched.size(), att.originalFilename, known);
        var followUp = ("Ask the user to play each clip and say who the voice is, enroll every "
                + "answer via action=%s (clip_label + speaker_name), then call action=%s again "
                + "for the full named transcript (the diarization is cached — only transcription "
                + "remains). If the user declines to identify the voices, call action=%s with "
                + "%s=true for an anonymous transcript.")
                .formatted(ACTION_ENROLL, ACTION_DIARIZE, ACTION_DIARIZE, ARG_ALLOW_ANONYMOUS);
        var response = stageLineup(path, att, diarization, new java.util.HashSet<>(unmatched), preamble, followUp);
        markIdentificationAttempted(att);
        return response;
    }

    /** Marker: an identification lineup was produced for this attachment in
     *  this conversation — only then is {@code allow_anonymous} honored. */
    private static Path identificationMarker(MessageAttachment att) {
        var conversationId = ToolContext.conversationId();
        return SpeakerNamer.enrollmentRoot().resolve(".staging")
                .resolve(String.valueOf(conversationId)).resolve(".identified-" + att.uuid);
    }

    private static boolean identificationAttempted(MessageAttachment att) {
        return Files.isRegularFile(identificationMarker(att));
    }

    private static void markIdentificationAttempted(MessageAttachment att) {
        try {
            var marker = identificationMarker(att);
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, "");
        } catch (IOException e) {
            play.Logger.warn("DiarizeAudioTool: could not write identification marker (%s)", e.getMessage());
        }
    }

    /**
     * extract_speaker_clips (JCLAW-562): cut up to {@value #CLIP_TARGET_SECONDS}
     * seconds of every diarized speaker's clearest speech, stage the clips for
     * label-based enrollment, and attach each clip as its OWN playable audio
     * file ({@code voice-N.wav}) on the assistant turn — the operator plays
     * each one, names the voices, and the model enrolls each via
     * {@code enroll_speaker + clip_label}.
     */
    private static ToolRegistry.ToolResult extractSpeakerClips(
            Path path, MessageAttachment att, Integer numSpeakers) {
        try {
            var diarization = DiarizationPipeline.cachedDiarization(path, numSpeakers);
            var followUp = ("Ask the user to play each clip and say who the voice is. Then enroll "
                    + "every voice they identify by calling this tool with action=%s, "
                    + "clip_label=<label>, speaker_name=<their name>. Skip voices the user does "
                    + "not identify.").formatted(ACTION_ENROLL);
            return stageLineup(path, att, diarization, null, null, followUp);
        } catch (TranscriptionException e) {
            return ToolRegistry.ToolResult.text("Speaker clip extraction failed: " + e.getMessage());
        }
    }

    /**
     * Shared lineup machinery (JCLAW-562/609/611): purity-gated clip
     * extraction + staging + playable attachments, optionally restricted to
     * a subset of diarized speakers ({@code onlySpeakers == null} = all).
     */
    private static ToolRegistry.ToolResult stageLineup(
            Path path, MessageAttachment att, DiarizationRouter.Result diarization,
            Set<Integer> onlySpeakers, String preamble, String followUp) {
        var conversationId = ToolContext.conversationId(); // non-null: resolution required scope
        try {
            // JCLAW-609: cut clips only from PURE speech — exclusive-mode
            // segments absorb cross-talk, so overlap regions (padded) are
            // subtracted first. A shorter pure clip beats a contaminated 5s one.
            var speakers = SpeakerClipExtractor.purify(diarization.segments(), diarization.overlaps());
            play.Logger.info("DiarizeAudioTool: extracting from %d pure span(s) (%d segments, %d overlap region(s) excluded)",
                    speakers.size(), diarization.segments().size(), diarization.overlaps().size());
            var clips = SpeakerClipExtractor.extract(path, speakers, CLIP_TARGET_SECONDS, CLIP_MIN_SECONDS);
            if (onlySpeakers != null) {
                var filtered = new java.util.ArrayList<SpeakerClipExtractor.Clip>();
                int n = 1;
                for (var clip : clips) {
                    if (onlySpeakers.contains(clip.speaker())) {
                        filtered.add(new SpeakerClipExtractor.Clip(
                                "voice-" + n++, clip.speaker(), clip.start(), clip.samples()));
                    }
                }
                clips = filtered;
            }
            if (clips.isEmpty()) {
                return ToolRegistry.ToolResult.text(
                        "No speaker in %s has a continuous speech segment of at least %.0f second(s) — "
                                + "cannot extract usable voice clips.".formatted(att.originalFilename, CLIP_MIN_SECONDS));
            }

            // Stage per-clip files for the enrollment follow-up. One staging
            // dir per conversation, replaced wholesale on every extraction so
            // labels always refer to the latest lineup. Dot-prefixed → the
            // enrollment scanner never mistakes staged clips for enrollment.
            var staging = stagingDir(conversationId);
            deleteRecursive(staging);
            Files.createDirectories(staging);

            // JCLAW-606/609: stage hidden extra reference clips per speaker
            // so the enroll action stores a multi-clip, PURITY-GATED set —
            // overlap purification + anchor voiceprint gate + separation
            // stem gate (the last one only on the sidecar path). Only the
            // lineup clip is attached/playable — UX unchanged.
            var refsBySpeaker = services.transcription.EnrollmentHarvester.harvest(
                    path, speakers, clips.stream().map(SpeakerClipExtractor.Clip::speaker).toList(),
                    ENROLLMENT_TARGET_SECONDS, CLIP_TARGET_SECONDS, CLIP_MIN_SECONDS,
                    services.transcription.SidecarEmbedder.INSTANCE,
                    services.transcription.OverlapReattributor::separateViaSidecar);
            var lineup = new StringBuilder();
            var generated = new java.util.ArrayList<GeneratedAttachment>(clips.size());
            for (var clip : clips) {
                var refs = refsBySpeaker.getOrDefault(clip.speaker(), List.of());
                for (int r = 1; r < refs.size(); r++) {
                    Files.write(staging.resolve(clip.label() + "-ref" + (r + 1) + ".wav"),
                            SpeakerClipExtractor.toWavPcm16(refs.get(r)));
                }
                var wav = SpeakerClipExtractor.toWavPcm16(clip.samples());
                Files.write(staging.resolve(clip.label() + ".wav"), wav);
                var meta = new JsonObject();
                meta.addProperty("label", clip.label());
                meta.addProperty("source", att.originalFilename);
                meta.addProperty("sourceStartSeconds", clip.start());
                generated.add(new GeneratedAttachment(wav, "audio/wav", meta.toString(),
                        clip.label() + ".wav"));
                lineup.append("\n- %s: %.1fs of speech, taken from %.1fs into the recording"
                        .formatted(clip.label(), clip.duration(), clip.start()));
            }
            var text = ("%sExtracted %d voice clip(s) from %s, each attached as its own playable "
                    + "audio file named after its label (already shown to the user — do not "
                    + "re-embed or link them):%s\n%s")
                    .formatted(preamble == null ? "" : preamble + "\n\n",
                            clips.size(), att.originalFilename, lineup, followUp);
            return ToolRegistry.ToolResult.withAttachments(text, null, generated);
        } catch (TranscriptionException e) {
            return ToolRegistry.ToolResult.text("Speaker clip extraction failed: " + e.getMessage());
        } catch (IOException e) {
            return ToolRegistry.ToolResult.text("Error: failed to stage voice clips: " + e.getMessage());
        }
    }

    /** enroll_speaker with clip_label (JCLAW-562): file a clip staged by
     *  extract_speaker_clips under the given name. */
    private static String enrollFromStaging(String label, String name) {
        var conversationId = ToolContext.conversationId();
        if (conversationId == null) {
            return "Error: no conversation in scope — staged clips belong to a chat conversation.";
        }
        if (!CLIP_LABEL_PATTERN.matcher(label.strip()).matches()) {
            return "Error: '%s' is not a clip label (expected voice-N from extract_speaker_clips)."
                    .formatted(label);
        }
        var clip = stagingDir(conversationId).resolve(label.strip() + ".wav");
        if (!Files.isRegularFile(clip)) {
            return "Error: no staged clip '%s' in this conversation — run extract_speaker_clips first."
                    .formatted(label.strip());
        }
        var result = storeEnrollment(clip, conversationId + "-" + label.strip() + ".wav", name);
        if (result.startsWith("Error")) return result;
        // JCLAW-606/609: also file the hidden reference clips staged
        // alongside the lineup clip (variable count — the extractor harvests
        // until ~20s of pure speech). Sequential names, stop at first gap.
        for (int r = 2; ; r++) {
            var ref = stagingDir(conversationId).resolve(label.strip() + "-ref" + r + ".wav");
            if (!Files.isRegularFile(ref)) break;
            storeEnrollment(ref, conversationId + "-" + label.strip() + "-ref" + r + ".wav", name);
        }
        return result;
    }

    private static Path stagingDir(Long conversationId) {
        return SpeakerNamer.enrollmentRoot().resolve(".staging").resolve(String.valueOf(conversationId));
    }

    private static void deleteRecursive(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException _) {} });
        }
    }

    /**
     * enroll_speaker (JCLAW-561): copy the attachment into
     * {@code data/speaker-voices/{name}/} so {@link SpeakerNamer} labels this
     * voice by name in future diarizations. This is the sanctioned crossing
     * of the workspace → data boundary that the workspace-jailed filesystem
     * tools deliberately can't make: the destination is fixed to the
     * enrollment root, the folder name is validated against
     * {@link #SPEAKER_NAME_PATTERN}, and the file leaf is regenerated from
     * the attachment uuid (never from user-controlled names).
     */
    private static String enrollSpeaker(Path source, MessageAttachment att, String name) {
        var extension = att.originalFilename != null && att.originalFilename.lastIndexOf('.') > 0
                ? att.originalFilename.substring(att.originalFilename.lastIndexOf('.'))
                : ".wav";
        if (!extension.matches("\\.[A-Za-z0-9]{1,8}")) extension = ".wav";
        return storeEnrollment(source, att.uuid + extension, name);
    }

    /** Shared tail of both enrollment paths: name validation + the copy into
     *  {@code data/speaker-voices/{name}/{destLeaf}}. {@code destLeaf} is
     *  always tool-generated (attachment uuid / staged clip label), never a
     *  user-controlled string. */
    private static String storeEnrollment(Path source, String destLeaf, String name) {
        if (name == null || name.isBlank()) {
            return "Error: 'speaker_name' is required for enroll_speaker.";
        }
        var trimmed = name.strip();
        if (!SPEAKER_NAME_PATTERN.matcher(trimmed).matches()) {
            return ("Error: '%s' is not a valid speaker name — use letters, digits, spaces or "
                    + "-_'. (max 64 chars, must not start with a dot).").formatted(trimmed);
        }
        try {
            var personDir = SpeakerNamer.enrollmentRoot().resolve(trimmed);
            Files.createDirectories(personDir);
            Files.copy(source, personDir.resolve(destLeaf),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            long clips;
            try (var files = Files.list(personDir)) {
                clips = files.filter(Files::isRegularFile)
                        .filter(p -> !p.getFileName().toString().startsWith(".")).count();
            }
            return ("Enrolled voice reference for \"%s\" (%d clip(s) on file). Future speaker "
                    + "diarizations will label this voice as %s.").formatted(trimmed, clips, trimmed);
        } catch (IOException e) {
            return "Error: failed to store the enrollment clip: " + e.getMessage();
        }
    }

    private record Resolved(MessageAttachment value, String error) {
        static Resolved of(MessageAttachment value) { return new Resolved(value, null); }
        static Resolved fail(String error) { return new Resolved(null, error); }
    }

    private static Resolved resolveAttachment(String uuid) {
        // Both paths are scoped to the active conversation. The uuid argument
        // is model-controlled and agents can be group-reachable through
        // channel bindings, so an unscoped uuid lookup would let a crafted
        // prompt pull audio out of an unrelated conversation (IDOR). The
        // argument only exists to disambiguate among THIS conversation's
        // attachments; cross-conversation reach is deliberately impossible.
        var conversationId = ToolContext.conversationId();
        if (conversationId == null) {
            return Resolved.fail("Error: no conversation in scope — this tool works on audio "
                    + "uploaded to the current chat conversation.");
        }

        if (uuid != null && !uuid.isBlank()) {
            MessageAttachment att = MessageAttachment.find(
                    "uuid = ?1 and message.conversation.id = ?2 and deleted = false",
                    uuid, conversationId).first();
            if (att == null) {
                return Resolved.fail(
                        "Error: no attachment with uuid %s exists in this conversation.".formatted(uuid));
            }
            if (!MessageAttachment.KIND_AUDIO.equals(att.kind)) {
                return Resolved.fail("Error: attachment %s is %s, not audio.".formatted(uuid, att.kind));
            }
            return Resolved.of(att);
        }

        // generated = false: tool-produced audio (the extract action's own
        // voice-lineup file) must never become the implicit target — "most
        // recent audio" means the user's upload (JCLAW-562 UAT finding: the
        // lineup outranked the recording it was cut from). An explicit
        // attachment_uuid can still address generated audio deliberately.
        MessageAttachment att = MessageAttachment.find(
                "message.conversation.id = ?1 and kind = ?2 and deleted = false "
                        + "and generated = false order by id desc",
                conversationId, MessageAttachment.KIND_AUDIO).first();
        if (att == null) {
            return Resolved.fail("Error: this conversation has no audio attachments to diarize. "
                    + "Ask the user to upload the recording first.");
        }
        return Resolved.of(att);
    }

    private static String optString(JsonObject args, String key) {
        return args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsString() : null;
    }

    private static Integer optInt(JsonObject args, String key) {
        return args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsInt() : null;
    }
}
