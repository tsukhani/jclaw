package services;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * Atomic directory staging/swap filesystem operations (JCLAW-727), extracted from
 * {@link SkillPromotionService}. Pure fabrication: a staging directory is populated by a caller
 * callback, then swapped into the target location with a backup of any existing target, so a
 * partially-written directory never becomes visible and a mid-write failure rolls back cleanly.
 *
 * <p>Deliberately knows only about directories. Domain policy that used to ride inside the swap
 * (skill-cache invalidation, registry sync, notifications) belongs at the call site, which runs it
 * only after {@link #stageAndSwap} returns — by then staging no longer exists, so a later failure
 * has nothing to clean up.
 */
public final class AtomicDirSwap {

    private AtomicDirSwap() {}

    /** A {@link Path}-consuming step that fills a freshly-created staging directory. */
    @FunctionalInterface
    public interface StagingPopulator {
        void populate(Path stagingDir) throws IOException;
    }

    /**
     * Shared staging spine: create the staging directory, let {@code populate} fill it, then
     * atomically swap it into {@code targetDir}, backing up any existing target. On any failure the
     * partially-built staging directory is removed and the cause is rethrown as an
     * {@link IOException}; an {@link UncheckedIOException} from {@code populate} is unwrapped to its
     * cause so callers see the underlying I/O error.
     */
    public static void stageAndSwap(Path targetDir, Path stagingDir, Path backupDir,
                                    boolean replacing, StagingPopulator populate) throws IOException {
        try {
            Files.createDirectories(stagingDir);
            populate.populate(stagingDir);
            atomicSwap(targetDir, stagingDir, backupDir, replacing);
        } catch (IOException | RuntimeException e) {
            if (Files.exists(stagingDir)) {
                try { deleteRecursive(stagingDir); } catch (IOException _) {}
            }
            if (e instanceof UncheckedIOException uioe) throw uioe.getCause();
            throw e instanceof IOException ioe ? ioe : new IOException(e.getMessage(), e);
        }
    }

    /**
     * Atomically swap a staging directory into the target location, backing up any existing target
     * first. On failure, the backup is restored.
     */
    public static void atomicSwap(Path targetDir, Path stagingDir, Path backupDir,
                                  boolean replacing) throws IOException {
        if (replacing) {
            Files.move(targetDir, backupDir);
        }
        try {
            Files.move(stagingDir, targetDir);
        } catch (IOException swapEx) {
            if (replacing && Files.isDirectory(backupDir)) {
                try { Files.move(backupDir, targetDir); } catch (IOException _) {}
            }
            throw swapEx;
        }
        if (replacing && Files.isDirectory(backupDir)) {
            deleteRecursive(backupDir);
        }
    }

    /** Recursively delete {@code dir}, best-effort per entry; a no-op when it does not exist. */
    public static void deleteRecursive(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException _) {}
            });
        }
    }
}
