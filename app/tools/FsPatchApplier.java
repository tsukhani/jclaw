package tools;

import models.Agent;
import services.AgentService;
import tools.FsSupport.EditResult;
import tools.UnifiedPatchParser.FileOp;
import tools.UnifiedPatchParser.PatchChunk;
import tools.UnifiedPatchParser.PatchLine;
import tools.UnifiedPatchParser.PatchParseException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The {@code applyPatch} family for {@link FileSystemTools}: parse a multi-file unified-diff
 * patch, resolve and guard every op's path (via {@link FsPaths}), acquire the ordered lock set
 * (via {@link FsLocks}), then validate and apply all ops transactionally with best-effort
 * rollback on IO failure.
 */
final class FsPatchApplier {

    private FsPatchApplier() {}

    /**
     * Apply a multi-file patch in the OpenClaw/unified-diff format:
     *
     * <pre>
     * *** Begin Patch
     * *** Add File: path/new.txt
     * +line 1
     * +line 2
     * *** End of File
     * *** Update File: path/existing.md
     * *** Move to: path/renamed.md
     * @@ optional anchor @@
     *  context
     * -old line
     * +new line
     * *** End of File
     * *** Delete File: path/gone.txt
     * *** End Patch
     * </pre>
     *
     * All file operations are validated atomically before any write hits disk. On
     * application IO error, best-effort rollback restores pre-edit content and removes
     * newly-created files.
     */
    static String applyPatch(Agent agent, String patchBody) {
        if (patchBody == null || patchBody.isBlank()) {
            return "Error: applyPatch requires a non-empty 'patch' field";
        }

        List<FileOp> ops;
        try {
            ops = UnifiedPatchParser.parse(patchBody);
        } catch (PatchParseException e) {
            return "Error: malformed patch at line %d: %s".formatted(e.line, e.getMessage());
        }

        if (ops.isEmpty()) {
            return "Error: patch contains no file operations";
        }

        var workspace = AgentService.workspacePath(agent.name);
        var resolution = resolvePatchOps(ops, agent, workspace);
        if (resolution.error != null) return resolution.error;

        var lockTargets = new ArrayList<Path>();
        for (var r : resolution.resolved) {
            lockTargets.add(r.target);
            if (r.moveTarget != null) lockTargets.add(r.moveTarget);
        }
        return FsLocks.runUnderFileLocks(lockTargets, () -> applyPatchLocked(resolution.resolved));
    }

    private record PatchResolution(List<ResolvedOp> resolved, String error) {
        static PatchResolution ok(List<ResolvedOp> resolved) { return new PatchResolution(resolved, null); }
        static PatchResolution err(String error) { return new PatchResolution(null, error); }
    }

    /**
     * Resolve + validate paths and enforce the skill-creator read-only guard for every op,
     * building the ResolvedOp list the application phase needs. Bails on the first error.
     */
    private static PatchResolution resolvePatchOps(List<FileOp> ops, Agent agent, Path workspace) {
        var resolved = new ArrayList<ResolvedOp>();
        for (var op : ops) {
            Path target;
            try {
                target = AgentService.acquireWorkspacePath(agent.name, op.path());
            } catch (SecurityException e) {
                return PatchResolution.err(FsSupport.ERROR_PREFIX_COLON + e.getMessage());
            }
            var guardError = FsPaths.checkSkillCreatorReadOnly(agent, workspace, target);
            if (guardError != null) return PatchResolution.err(guardError);

            // Capture the Optional once so the isPresent check and the get share
            // a single reference — addresses SonarQube S3655, which can't prove
            // u.newPath() is referentially transparent across two calls.
            var newPathOpt = (op instanceof FileOp.Update u) ? u.newPath() : Optional.<String>empty();
            Path moveTarget = null;
            if (newPathOpt.isPresent()) {
                try {
                    moveTarget = AgentService.acquireWorkspacePath(agent.name, newPathOpt.get());
                } catch (SecurityException e) {
                    return PatchResolution.err(FsSupport.ERROR_PREFIX_COLON + e.getMessage());
                }
                var moveGuard = FsPaths.checkSkillCreatorReadOnly(agent, workspace, moveTarget);
                if (moveGuard != null) return PatchResolution.err(moveGuard);
            }
            resolved.add(new ResolvedOp(op, target, moveTarget));
        }
        return PatchResolution.ok(resolved);
    }

    private static String applyPatchLocked(List<ResolvedOp> resolved) {
        // === Phase 1: validate every op and compute the post-patch content for Add/Update ops. ===
        var plans = new ArrayList<OpPlan>();
        for (int i = 0; i < resolved.size(); i++) {
            var planned = planOp(resolved.get(i), i + 1);
            if (planned.error != null) return planned.error;
            plans.add(planned.plan);
        }

        // === Phase 2: apply. On IO failure mid-application, roll back successful writes. ===
        var committed = new ArrayList<CommittedOp>();
        try {
            for (var plan : plans) {
                var err = applyPlannedOp(plan, committed);
                if (err != null) {
                    rollback(committed);
                    return err;
                }
            }
        } catch (RuntimeException rt) {
            rollback(committed);
            throw rt;
        }

        return summarizeCommittedOps(committed);
    }

    private record PlannedOp(OpPlan plan, String error) {
        static PlannedOp ok(OpPlan plan) { return new PlannedOp(plan, null); }
        static PlannedOp err(String error) { return new PlannedOp(null, error); }
    }

    /**
     * Validate a single resolved op and compute its post-patch content (for Add/Update) or
     * snapshot of pre-edit content (for Update/Delete, used by rollback on IO error).
     */
    private static PlannedOp planOp(ResolvedOp r, int opIndex) {
        return switch (r.op) {
            case FileOp.Add(var path, var content) -> {
                if (Files.exists(r.target)) {
                    yield PlannedOp.err("Error: op #%d Add File '%s' failed — file already exists".formatted(opIndex, path));
                }
                yield PlannedOp.ok(new OpPlan(r, content, null));
            }
            case FileOp.Delete(var path) -> {
                if (!Files.exists(r.target)) {
                    yield PlannedOp.err("Error: op #%d Delete File '%s' failed — file does not exist".formatted(opIndex, path));
                }
                try {
                    yield PlannedOp.ok(new OpPlan(r, null, Files.readString(r.target)));
                } catch (IOException e) {
                    yield PlannedOp.err("Error: op #%d Delete File '%s' snapshot failed — %s".formatted(opIndex, path, e.getMessage()));
                }
            }
            case FileOp.Update upd -> {
                if (!Files.exists(r.target)) {
                    yield PlannedOp.err("Error: op #%d Update File '%s' failed — file does not exist".formatted(opIndex, upd.path()));
                }
                String snapshot;
                try {
                    snapshot = Files.readString(r.target);
                } catch (IOException e) {
                    yield PlannedOp.err("Error: op #%d Update File '%s' read failed — %s".formatted(opIndex, upd.path(), e.getMessage()));
                }
                var applied = applyUpdateChunks(snapshot, upd.chunks(), upd.path(), opIndex);
                if (applied.error() != null) yield PlannedOp.err(applied.error());
                yield PlannedOp.ok(new OpPlan(r, applied.result(), snapshot));
            }
        };
    }

    /**
     * Apply one validated plan, appending CommittedOp markers used for rollback.
     * Returns a non-null error message if this op failed; callers must rollback before returning.
     */
    private static String applyPlannedOp(OpPlan plan, List<CommittedOp> committed) {
        var r = plan.resolved;
        return switch (r.op) {
            case FileOp.Add add -> applyAddOp(r, plan, committed, add.path());
            case FileOp.Delete(var path) -> applyDeleteOp(r, plan, committed, path);
            case FileOp.Update upd -> (r.moveTarget != null)
                    ? applyUpdateMoveOp(r, plan, committed, upd)
                    : applyUpdateInPlaceOp(r, plan, committed, upd);
        };
    }

    private static String applyAddOp(ResolvedOp r, OpPlan plan, List<CommittedOp> committed, String path) {
        var result = FsWriter.writeFile(r.target, plan.newContent);
        if (result.startsWith(FsSupport.ERROR_PREFIX)) {
            return "Error applying Add File '%s': %s".formatted(path, result);
        }
        committed.add(new CommittedOp.Added(r.target));
        return null;
    }

    private static String applyDeleteOp(ResolvedOp r, OpPlan plan, List<CommittedOp> committed, String path) {
        try {
            Files.deleteIfExists(r.target);
            committed.add(new CommittedOp.Deleted(r.target, plan.preSnapshot));
            return null;
        } catch (IOException e) {
            return "Error applying Delete File '%s': %s".formatted(path, e.getMessage());
        }
    }

    private static String applyUpdateInPlaceOp(ResolvedOp r, OpPlan plan, List<CommittedOp> committed, FileOp.Update upd) {
        var result = FsWriter.writeFile(r.target, plan.newContent);
        if (result.startsWith(FsSupport.ERROR_PREFIX)) {
            return "Error applying Update File '%s': %s".formatted(upd.path(), result);
        }
        committed.add(new CommittedOp.Updated(r.target, plan.preSnapshot));
        return null;
    }

    private static String applyUpdateMoveOp(ResolvedOp r, OpPlan plan, List<CommittedOp> committed, FileOp.Update upd) {
        // Write edited content to new path, then remove original.
        var writeResult = FsWriter.writeFile(r.moveTarget, plan.newContent);
        if (writeResult.startsWith(FsSupport.ERROR_PREFIX)) {
            return "Error applying Update+Move '%s'→'%s': %s"
                    .formatted(upd.path(), upd.newPath().orElse(""), writeResult);
        }
        committed.add(new CommittedOp.Added(r.moveTarget));
        try {
            Files.deleteIfExists(r.target);
            committed.add(new CommittedOp.Deleted(r.target, plan.preSnapshot));
            return null;
        } catch (IOException e) {
            return "Error applying Update+Move '%s'→'%s': %s"
                    .formatted(upd.path(), upd.newPath().orElse(""), e.getMessage());
        }
    }

    private static String summarizeCommittedOps(List<CommittedOp> committed) {
        int added = 0;
        int updated = 0;
        int deleted = 0;
        for (var c : committed) {
            switch (c) {
                case CommittedOp.Added _ -> added++;
                case CommittedOp.Updated _ -> updated++;
                case CommittedOp.Deleted _ -> deleted++;
            }
        }
        var paths = committed.stream().map(c -> c.path().getFileName().toString()).toList();
        return "Applied patch: %d added, %d updated, %d deleted (files: %s)"
                .formatted(added, updated, deleted, String.join(", ", paths));
    }

    private static void rollback(List<CommittedOp> committed) {
        // Replay in reverse, restoring pre-edit state best-effort. We don't surface rollback
        // errors to the caller — the primary failure already did — but we do try to avoid
        // leaving partial writes behind.
        for (int i = committed.size() - 1; i >= 0; i--) {
            var c = committed.get(i);
            try {
                switch (c) {
                    case CommittedOp.Added(var path) -> Files.deleteIfExists(path);
                    case CommittedOp.Updated(var path, var preSnapshot) -> Files.writeString(path, preSnapshot);
                    case CommittedOp.Deleted(var path, var preSnapshot) -> Files.writeString(path, preSnapshot);
                }
            } catch (IOException _) {
                // swallow — best effort.
            }
        }
    }

    /**
     * Apply a list of patch chunks to the current file content. Each chunk's non-`+` lines
     * (context + remove) form an oldText block that must appear at least once in the file;
     * if an @@ anchor is present, the search is restricted to the region after the anchor.
     * Returns the new file content or an error.
     */
    private static EditResult applyUpdateChunks(String original, List<PatchChunk> chunks, String path, int opIndex) {
        var working = original;
        for (int c = 0; c < chunks.size(); c++) {
            var applied = applySingleChunk(working, chunks.get(c), path, opIndex, c + 1);
            if (applied.error() != null) return applied;
            working = applied.result();
        }
        return EditResult.ok(working);
    }

    /** Apply one chunk to {@code working}, returning the new content or an error. */
    private static EditResult applySingleChunk(String working, PatchChunk chunk, String path, int opIndex, int chunkIndex) {
        var oldBlock = new StringBuilder();
        var newBlock = new StringBuilder();
        for (var line : chunk.lines()) {
            appendChunkLine(line, oldBlock, newBlock);
        }
        var oldText = oldBlock.toString();
        var newText = newBlock.toString();
        if (oldText.isEmpty()) {
            return EditResult.err(("Error: op #%d Update File '%s' chunk #%d has no removal or context lines — "
                    + "a chunk must include at least one '-' or ' ' line to anchor the edit.")
                    .formatted(opIndex, path, chunkIndex));
        }

        var anchorOpt = chunk.anchor();
        int searchStart = 0;
        if (anchorOpt.isPresent() && !anchorOpt.get().isEmpty()) {
            var anchor = anchorOpt.get();
            var anchorIdx = working.indexOf(anchor);
            if (anchorIdx < 0) {
                return EditResult.err(("Error: op #%d Update File '%s' chunk #%d anchor '%s' not found in file")
                        .formatted(opIndex, path, chunkIndex, anchor));
            }
            searchStart = anchorIdx;
        }

        var hit = working.indexOf(oldText, searchStart);
        if (hit < 0) {
            return EditResult.err(("Error: op #%d Update File '%s' chunk #%d context did not match the current file content. "
                    + "Regenerate the chunk against the latest file state.").formatted(opIndex, path, chunkIndex));
        }
        // For non-anchored chunks, require uniqueness to avoid accidental mis-apply.
        if (anchorOpt.isEmpty()) {
            var second = working.indexOf(oldText, hit + oldText.length());
            if (second >= 0) {
                return EditResult.err(("Error: op #%d Update File '%s' chunk #%d context is not unique. "
                        + "Add an @@ anchor @@ line or include more context.").formatted(opIndex, path, chunkIndex));
            }
        }
        return EditResult.ok(working.substring(0, hit) + newText + working.substring(hit + oldText.length()));
    }

    private static void appendChunkLine(PatchLine line, StringBuilder oldBlock, StringBuilder newBlock) {
        switch (line) {
            case PatchLine.ContextLine(var text) -> {
                oldBlock.append(text).append('\n');
                newBlock.append(text).append('\n');
            }
            case PatchLine.RemoveLine(var text) -> oldBlock.append(text).append('\n');
            case PatchLine.AddLine(var text) -> newBlock.append(text).append('\n');
        }
    }

    // === Patch apply support types ===
    // FileOp/PatchChunk/PatchLine (the pure parse output) and PatchParseException live in
    // UnifiedPatchParser. The types below are specific to the transactional apply phase and
    // stay here since they carry filesystem Paths.

    private record ResolvedOp(FileOp op, Path target, Path moveTarget) {}
    private record OpPlan(ResolvedOp resolved, String newContent, String preSnapshot) {}

    private sealed interface CommittedOp {
        Path path();
        record Added(Path path) implements CommittedOp {}
        record Updated(Path path, String preSnapshot) implements CommittedOp {}
        record Deleted(Path path, String preSnapshot) implements CommittedOp {}
    }
}
