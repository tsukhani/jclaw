package services;

import models.Agent;
import models.Message;
import models.MessageAttachment;
import play.Logger;
import utils.TikaHolder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Vision/multimodal attachment lifecycle (JCLAW-25). Staged uploads land in
 * {@code workspace/{agent.name}/attachments/staging/{uuid}.{ext}} via
 * {@code ApiChatController.uploadChatFiles}; this service moves them to the
 * conversation-keyed final directory and writes the accompanying
 * {@link MessageAttachment} row when the send lands.
 */
public final class AttachmentService {

    /** Workspace-relative prefix for a conversation's attachment directory ({@code attachments/{conversationId}}). */
    private static final String ATTACHMENTS_DIR = "attachments/";

    private AttachmentService() {}

    /**
     * Everything {@code ApiChatController.uploadChatFiles} returned to the
     * frontend, roundtripped verbatim on the send call. The last three
     * fields are client-supplied metadata we re-sniff on finalize to keep
     * authority on the server.
     *
     * @param attachmentId     server-issued id for the upload
     * @param originalFilename filename the user picked
     * @param mimeType         client-reported content type (re-sniffed on
     *                         finalize)
     * @param sizeBytes        client-reported size (re-verified on finalize)
     * @param kind             discriminator for special-rendered kinds
     *                         ({@code image}, {@code audio}, generic file);
     *                         re-sniffed on finalize
     */
    public record Input(
            String attachmentId,
            String originalFilename,
            String mimeType,
            long sizeBytes,
            String kind) {}

    /**
     * Move a staged upload to its conversation-keyed final path and persist
     * the {@link MessageAttachment} row. Re-sniffs MIME on finalize so
     * client-declared metadata never outranks filesystem truth. Caller must
     * be inside a JPA transaction — this method only calls {@code save()}
     * on the attachment entity.
     */
    public static MessageAttachment finalizeAttachment(Agent agent, Message message, Input input) {
        if (input.attachmentId() == null || input.attachmentId().isBlank()) {
            throw new IllegalArgumentException("attachmentId is required");
        }
        var stagingDir = AgentService.acquireWorkspacePath(agent.name, "attachments/staging");
        var stagedFile = findStagedFile(stagingDir, input.attachmentId());
        if (stagedFile == null) {
            throw new IllegalStateException(
                    "No staged attachment found for id " + input.attachmentId()
                            + " under " + stagingDir);
        }

        String sniffedMime;
        long sizeBytes;
        try {
            sniffedMime = TikaHolder.TIKA.detect(stagedFile);
            sizeBytes = Files.size(stagedFile);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to inspect staged attachment: " + e.getMessage(), e);
        }
        // WebM/Matroska disambiguation, shared with ApiChatController.sniffMime
        // (JCLAW-165 follow-up + JCLAW-560): Tika sniffs every such container
        // as video/* regardless of track content. The upload's classification
        // (carried in input.mimeType) is the fast-path hint; without it the
        // container's track codecs decide, so an audio-only .webm re-sniffed
        // here keeps (or gains) its KIND_AUDIO classification either way.
        sniffedMime = MatroskaTracks.disambiguate(sniffedMime, input.mimeType(), stagedFile);
        var kind = MessageAttachment.kindForMime(sniffedMime);

        var leaf = stagedFile.getFileName().toString();
        var conversationDir = AgentService.acquireWorkspacePath(
                agent.name, ATTACHMENTS_DIR + message.conversation.id);
        try {
            Files.createDirectories(conversationDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create attachments directory: " + e.getMessage(), e);
        }
        var finalPath = AgentService.acquireContained(conversationDir, leaf);
        try {
            Files.move(stagedFile, finalPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to finalize staged attachment: " + e.getMessage(), e);
        }

        var att = new MessageAttachment();
        att.message = message;
        att.uuid = input.attachmentId();
        // Original filename is sanitized at upload time and passed through the
        // frontend unchanged; re-sanitizing here would be defensive but empty
        // (uploadChatFiles already rejects unsafe leaves).
        att.originalFilename = input.originalFilename() != null ? input.originalFilename() : leaf;
        att.storagePath = toStoragePath(agent.name, message.conversation.id, leaf);
        att.mimeType = sniffedMime;
        att.sizeBytes = sizeBytes;
        att.kind = kind;
        att.save();
        return att;
    }

    private static final DateTimeFormatter GENERATED_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * Persist a tool-generated image (JCLAW-227). Unlike {@link #finalizeAttachment}, the bytes come
     * straight from {@code services.imagegen.ImageGenerationService} (not a staged upload), so they're
     * written directly to the conversation-keyed final directory and the {@link MessageAttachment} row
     * is flagged {@code generated = true} with the supplied {@code generationMetadata} JSON (prompt,
     * model, provider). Caller must be inside a JPA transaction — this only calls {@code save()}.
     */
    public static MessageAttachment persistGeneratedImage(Agent agent, Message message, byte[] bytes,
            String mimeType, String generationMetadata) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("generated image bytes are required");
        }
        var mime = (mimeType == null || mimeType.isBlank()) ? "image/png" : mimeType;
        var uuid = UUID.randomUUID().toString();
        var ext = extensionForMime(mime);
        var leaf = uuid + "." + ext;
        var conversationDir = AgentService.acquireWorkspacePath(
                agent.name, ATTACHMENTS_DIR + message.conversation.id);
        try {
            Files.createDirectories(conversationDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create attachments directory: " + e.getMessage(), e);
        }
        var finalPath = AgentService.acquireContained(conversationDir, leaf);
        try {
            Files.write(finalPath, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write generated image: " + e.getMessage(), e);
        }

        var att = new MessageAttachment();
        att.message = message;
        att.uuid = uuid;
        att.originalFilename = "generated-" + GENERATED_TS.format(LocalDateTime.now()) + "." + ext;
        att.storagePath = toStoragePath(agent.name, message.conversation.id, leaf);
        att.mimeType = mime;
        att.sizeBytes = bytes.length;
        att.kind = MessageAttachment.kindForMime(mime);
        att.generated = true;
        att.generationMetadata = generationMetadata;
        att.save();
        return att;
    }

    /**
     * JCLAW-234: create a zero-length placeholder row for an async video generation. The
     * {@code generate_video} tool (JCLAW-235) calls this at submit time so the chat can show a
     * "generating" card immediately; {@link #fillGeneratedVideo} writes the real bytes once the job
     * succeeds. No file is written yet — the {@code storagePath} is reserved and the byte file appears on
     * fill. Caller must be inside a JPA transaction.
     */
    public static MessageAttachment createGeneratedVideoPlaceholder(Agent agent, Message message,
            Long generationJobId, String generationMetadata) {
        var uuid = UUID.randomUUID().toString();
        var leaf = uuid + ".mp4";
        var att = new MessageAttachment();
        att.message = message;
        att.uuid = uuid;
        att.originalFilename = "generated-" + GENERATED_TS.format(LocalDateTime.now()) + ".mp4";
        att.storagePath = toStoragePath(agent.name, message.conversation.id, leaf);
        att.mimeType = "video/mp4";
        att.sizeBytes = 0; // filled on job success
        att.kind = MessageAttachment.KIND_VIDEO;
        att.generated = true;
        att.generationMetadata = generationMetadata;
        att.generationJobId = generationJobId;
        att.save();
        return att;
    }

    /**
     * JCLAW-234: write the produced video bytes into a placeholder created by
     * {@link #createGeneratedVideoPlaceholder} and update its size / MIME. Called by the job runner on
     * success. Caller must be inside a JPA transaction.
     */
    public static void fillGeneratedVideo(MessageAttachment att, byte[] bytes, String mimeType) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("generated video bytes are required");
        }
        var path = resolveOnDisk(att);
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write generated video: " + e.getMessage(), e);
        }
        att.sizeBytes = bytes.length;
        if (mimeType != null && !mimeType.isBlank()) att.mimeType = mimeType;
        att.save();
    }

    private static String extensionForMime(String mime) {
        return switch (mime) {
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            // JCLAW-562: the diarize_audio voice-lineup result rides the same
            // generated-attachment path as images.
            case "audio/wav" -> "wav";
            default -> "png";
        };
    }

    /**
     * Read a finalized attachment's bytes and encode them as a
     * {@code data:<mime>;base64,<bytes>} URL suitable for the OpenAI
     * {@code image_url.url} content part. Used by {@code AgentRunner} when
     * assembling the LLM request for a vision-capable model.
     */
    public static String readAsDataUrl(MessageAttachment att) {
        return "data:" + att.mimeType + ";base64," + readAsBase64(att);
    }

    /**
     * Read a finalized attachment's bytes and encode them as bare base64.
     * Used for the OpenAI {@code input_audio.data} content part (JCLAW-132),
     * which takes raw base64 without the {@code data:...} URL prefix.
     */
    public static String readAsBase64(MessageAttachment att) {
        return Base64.getEncoder().encodeToString(readBytes(att));
    }

    /**
     * Read a finalized attachment's raw bytes from disk. Used by the image
     * captioner (JCLAW-213), which decodes them into a {@code BufferedImage}.
     */
    public static byte[] readBytes(MessageAttachment att) {
        var path = resolveOnDisk(att);
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read attachment bytes: " + e.getMessage(), e);
        }
    }

    /**
     * JCLAW-209: remove an attachment's bytes from the workspace. Best-effort —
     * an already-missing file is treated as success so the caller's {@code deleted}
     * flag still flips. The DB row is intentionally retained by the caller; only the
     * on-disk bytes are freed (the chip keeps the prompt/provenance as a record).
     */
    public static void deleteImageFile(MessageAttachment att) {
        try {
            Files.deleteIfExists(resolveOnDisk(att));
        } catch (IOException | SecurityException e) {
            Logger.warn("Failed to delete attachment file %s: %s", att.uuid, e.getMessage());
        }
    }

    /**
     * JCLAW-209: reclaim a conversation's attachment bytes from the workspace. Every attachment in a
     * conversation is stored under one directory ({@code {agent}/attachments/{conversationId}/}), so a
     * recursive sweep of that directory frees them all at once. Called by {@code ConversationService}
     * before the row cascade so deleting a conversation doesn't orphan its files on disk. Best-effort —
     * a missing directory (no attachments were ever written) is a quiet no-op.
     */
    public static void deleteConversationAttachments(String agentName, long conversationId) {
        if (agentName == null) return;
        Path dir;
        try {
            dir = AgentService.acquireWorkspacePath(agentName, ATTACHMENTS_DIR + conversationId);
        } catch (SecurityException e) {
            Logger.warn("Refused attachment-dir resolution for conversation %d: %s", conversationId, e.getMessage());
            return;
        }
        if (!Files.isDirectory(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException _) { /* best-effort per-entry */ }
                    });
        } catch (IOException e) {
            Logger.warn("Failed to delete attachment dir for conversation %d: %s", conversationId, e.getMessage());
        }
    }

    /**
     * Canonical {@code storagePath} layout for a finalized attachment:
     * {@code {agentName}/attachments/{conversationId}/{leaf}}. The
     * {@code agentName} prefix is what {@link #resolveOnDisk} strips back off
     * to recover the workspace-relative path — defining the format here once
     * keeps the writer and reader from drifting apart on the prefix shape.
     */
    static String toStoragePath(String agentName, long conversationId, String leaf) {
        return agentName + "/attachments/" + conversationId + "/" + leaf;
    }

    /**
     * Resolve a finalized attachment's {@code storagePath} to its on-disk
     * location. The {@code storagePath} is {@code {agentName}/...}; strip the
     * agent-name prefix (the workspace root already keys on the agent) and
     * re-validate the remainder through the workspace path guard.
     */
    static Path resolveOnDisk(MessageAttachment att) {
        var agentName = att.message.conversation.agent.name;
        return AgentService.acquireWorkspacePath(
                agentName, att.storagePath.substring(agentName.length() + 1));
    }

    /**
     * The attachment's path <b>relative to the agent's workspace root</b> — exactly the form the
     * documents / filesystem tools expect (they resolve via
     * {@code AgentService.acquireWorkspacePath(agent.name, path)}). Strips the {@code {agentName}/}
     * prefix from {@link #toStoragePath storagePath}, mirroring {@link #resolveOnDisk}'s prefix
     * handling. Surfaced into the caption block (JCLAW-215 follow-up) so a non-vision agent can
     * move / copy / forward the image file with the tools even though it can't read the bytes.
     */
    public static String workspaceRelativePath(MessageAttachment att) {
        var agentName = att.message.conversation.agent.name;
        return att.storagePath.substring(agentName.length() + 1);
    }

    /**
     * Locate the staged file for a given uuid. Scans the staging directory
     * for the first entry whose filename starts with {@code uuid + "."} —
     * the on-disk layout written by {@code ApiChatController.uploadChatFiles}.
     */
    private static Path findStagedFile(Path stagingDir, String uuid) {
        if (!Files.isDirectory(stagingDir)) return null;
        try (var stream = Files.list(stagingDir)) {
            return stream
                    .filter(p -> {
                        var name = p.getFileName().toString();
                        return name.equals(uuid)
                                || name.startsWith(uuid + ".");
                    })
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list staging dir: " + e.getMessage(), e);
        }
    }

    /**
     * Assert every input has an IMAGE kind. Used by the controller vision
     * gate — rejects a send that carries any image attachments when the
     * selected model does not declare {@code supportsVision}.
     */
    public static boolean anyImage(List<Input> inputs) {
        if (inputs == null) return false;
        return inputs.stream().anyMatch(i -> MessageAttachment.KIND_IMAGE.equalsIgnoreCase(i.kind()));
    }

    /** Mirror of {@link #anyImage} for the JCLAW-131 audio gate. */
    public static boolean anyAudio(List<Input> inputs) {
        if (inputs == null) return false;
        return inputs.stream().anyMatch(i -> MessageAttachment.KIND_AUDIO.equalsIgnoreCase(i.kind()));
    }

    /** Mirror of {@link #anyImage} for the JCLAW-217 video gate. */
    public static boolean anyVideo(List<Input> inputs) {
        if (inputs == null) return false;
        return inputs.stream().anyMatch(i -> MessageAttachment.KIND_VIDEO.equalsIgnoreCase(i.kind()));
    }
}
