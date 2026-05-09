package services;

import models.Agent;
import models.Message;
import models.MessageAttachment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.List;

/**
 * Vision/multimodal attachment lifecycle (JCLAW-25). Staged uploads land in
 * {@code workspace/{agent.name}/attachments/staging/{uuid}.{ext}} via
 * {@code ApiChatController.uploadChatFiles}; this service moves them to the
 * conversation-keyed final directory and writes the accompanying
 * {@link MessageAttachment} row when the send lands.
 */
public final class AttachmentService {

    private AttachmentService() {}

    /**
     * Everything {@code ApiChatController.uploadChatFiles} returned to the
     * frontend, roundtripped verbatim on the send call. {@code mimeType},
     * {@code sizeBytes}, and {@code kind} are client-supplied metadata we
     * re-sniff on finalize to keep authority on the server.
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
            sniffedMime = new org.apache.tika.Tika().detect(stagedFile);
            sizeBytes = Files.size(stagedFile);
        } catch (IOException e) {
            throw new RuntimeException("Failed to inspect staged attachment: " + e.getMessage(), e);
        }
        // Mirror the WebM disambiguation in ApiChatController.uploadChatFiles
        // (JCLAW-165 follow-up): Tika sniffs every WebM container as video/webm
        // regardless of whether it has video tracks, so browser-recorded voice
        // notes were getting reclassified to KIND_FILE here even after the
        // upload endpoint correctly classified them as KIND_AUDIO. Use the
        // upload's classification (carried in input.mimeType) as a hint to
        // override the re-sniff when it's the ambiguous video/webm case.
        if ("video/webm".equals(sniffedMime)
                && input.mimeType() != null && input.mimeType().startsWith("audio/")) {
            sniffedMime = "audio/webm";
        }
        var kind = MessageAttachment.kindForMime(sniffedMime);

        var leaf = stagedFile.getFileName().toString();
        var conversationDir = AgentService.acquireWorkspacePath(
                agent.name, "attachments/" + message.conversation.id);
        try {
            Files.createDirectories(conversationDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create attachments directory: " + e.getMessage(), e);
        }
        var finalPath = AgentService.acquireContained(conversationDir, leaf);
        try {
            Files.move(stagedFile, finalPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("Failed to finalize staged attachment: " + e.getMessage(), e);
        }

        var att = new MessageAttachment();
        att.message = message;
        att.uuid = input.attachmentId();
        // Original filename is sanitized at upload time and passed through the
        // frontend unchanged; re-sanitizing here would be defensive but empty
        // (uploadChatFiles already rejects unsafe leaves).
        att.originalFilename = input.originalFilename() != null ? input.originalFilename() : leaf;
        att.storagePath = agent.name + "/attachments/" + message.conversation.id + "/" + leaf;
        att.mimeType = sniffedMime;
        att.sizeBytes = sizeBytes;
        att.kind = kind;
        att.save();
        return att;
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
        var path = AgentService.acquireWorkspacePath(
                att.message.conversation.agent.name,
                att.storagePath.substring(att.message.conversation.agent.name.length() + 1));
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read attachment bytes: " + e.getMessage(), e);
        }
        return Base64.getEncoder().encodeToString(bytes);
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
            throw new RuntimeException("Failed to list staging dir: " + e.getMessage(), e);
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
}
