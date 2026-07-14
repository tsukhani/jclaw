package services.transcription;

import models.MessageAttachment;
import services.AgentService;
import services.ConfigService;

/**
 * Adapts the static {@link WhisperTranscriber} engine to the
 * {@link TranscriptionService} contract. Resolves the configured
 * {@code transcription.localModel} on every call (so model swaps
 * via Settings take effect immediately) and routes the on-disk
 * attachment path through the JNI engine.
 *
 * <p>Fail-fast: if the configured model isn't downloaded, the
 * underlying engine throws {@link TranscriptionException} with a clear
 * message — the orchestrator above catches and treats as silent
 * failure (transcript stays NULL on the row, callers fall back to the
 * "could not be transcribed" note when the text-only branch needs it).
 */
public final class WhisperLocalTranscriptionService implements TranscriptionService {

    @Override
    public String transcribe(MessageAttachment attachment) {
        if (attachment == null) throw new TranscriptionException("attachment is null");
        var modelId = ConfigService.get("transcription.localModel");
        var model = AsrModel.byId(modelId).orElse(AsrModel.DEFAULT);
        var path = AgentService.workspaceRoot().resolve(attachment.storagePath);
        // Language follows the model selection (JCLAW-556): multilingual
        // models auto-detect per clip, .en models decode English. Before,
        // multilingual models were silently forced to "en".
        return WhisperTranscriber.transcribe(path, model);
    }
}
