package utils;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;

/**
 * General filesystem-boundary guard: resolve a caller-supplied relative path
 * inside a "must stay inside" root and reject anything that would escape it.
 *
 * <p>Extracted from {@code services.AgentService} (JCLAW-703) because this is
 * cross-cutting filesystem security, not agent-domain logic — a GRASP Pure
 * Fabrication. Every fs sink that writes/reads/execs a caller-supplied path
 * (agent workspaces, upload staging, skill fetch/import, shell workdir) routes
 * through here so containment is enforced in exactly one place.
 *
 * <p>Defends against four escape vectors:
 * <ul>
 *   <li>lexical {@code ..} traversal (normalize + startsWith);</li>
 *   <li>symlink escape — an in-root symlink pointing outside is realpath-caught,
 *       including for not-yet-created write targets via the missing-suffix walk-up;</li>
 *   <li>hardlink side-door — a regular file with {@code nlink > 1} aliasing an
 *       inode across the sandbox boundary;</li>
 *   <li>TOCTOU — the validate&rarr;use window is shrunk via a double-resolve.</li>
 * </ul>
 *
 * <p>Sibling of {@link SsrfGuard} (network-boundary guard); both are static
 * security utilities with no per-request state.
 */
public final class WorkspacePathGuard {

    private WorkspacePathGuard() {}

    /**
     * Resolve {@code relativePath} inside {@code root}, rejecting any target
     * that would escape the root. Two-layer validation:
     *
     * <ol>
     *   <li><b>Lexical</b>: collapse {@code ..} via {@code normalize()} and
     *       verify the result starts with {@code root}.</li>
     *   <li><b>Canonical</b>: realpath the deepest existing ancestor of both
     *       the root and the target (handles a not-yet-materialised root and
     *       writes whose target doesn't exist yet), append the missing suffix,
     *       and verify the resulting absolute path is still inside the
     *       canonical root. Catches symlink escapes — a symlink inside the root
     *       that points to {@code /etc} would pass step 1 but fail step 2.</li>
     * </ol>
     *
     * Returns the canonical absolute path on success, or {@code null} on any
     * escape or I/O error. This is a pure <b>read-only probe</b>: it never
     * creates the root or any directory. Prefer {@link #acquireContained} when
     * the result is about to be opened or executed against — it additionally
     * double-resolves to shrink the validate→use TOCTOU window.
     *
     * @param root         the workspace root (or any other "must stay inside"
     *                     boundary)
     * @param relativePath path relative to {@code root}, possibly containing
     *                     {@code ..} segments
     * @return the canonical absolute path inside {@code root}, or
     *         {@code null} on escape / missing root / I/O error
     */
    public static @Nullable Path resolveContained(@NonNull Path root, @NonNull String relativePath) {
        try {
            // Layer 1: lexical
            var rootAbs = root.toAbsolutePath().normalize();
            var target = rootAbs.resolve(relativePath).normalize();
            if (!target.startsWith(rootAbs)) return null;

            // Layer 2: canonical with missing-suffix walk-up. Read-only — never
            // creates the root or any parent. Both the root and the target are
            // canonicalized by realpath'ing their deepest existing ancestor
            // (which resolves symlinks in the existing prefix) and re-attaching
            // the not-yet-created tail; a symlink inside the root that points to
            // /etc passes step 1 but fails the containment check here.
            var rootReal = canonicalize(rootAbs);
            var canonical = canonicalize(target);
            if (rootReal == null || canonical == null) return null;
            return canonical.startsWith(rootReal) ? canonical : null;
        } catch (IOException _) {
            return null;
        }
    }

    /**
     * Canonicalize {@code path} without creating anything: walk up to the
     * deepest existing ancestor, {@code toRealPath()} it (resolving symlinks in
     * the existing prefix), then re-attach the not-yet-created suffix verbatim.
     * A read-only equivalent of {@link Path#toRealPath} that tolerates a path
     * whose tail doesn't exist yet — the common case for write targets, and for
     * a workspace root that hasn't been materialised on disk.
     *
     * @param path the absolute path to canonicalize
     * @return the canonicalized absolute path, or {@code null} if no ancestor
     *         exists (impossible for an absolute path, whose filesystem root
     *         always exists)
     * @throws IOException if realpath'ing the deepest existing ancestor fails
     */
    private static Path canonicalize(Path path) throws IOException {
        var existing = path;
        var missingSuffix = new ArrayDeque<Path>();
        while (existing != null && !Files.exists(existing)) {
            missingSuffix.push(existing.getFileName());
            existing = existing.getParent();
        }
        if (existing == null) return null;
        var canonical = existing.toRealPath();
        for (var seg : missingSuffix) canonical = canonical.resolve(seg);
        return canonical.normalize();
    }

    /**
     * Resolve, validate, then re-resolve immediately to confirm the canonical
     * target hasn't changed between the two resolutions. Additionally rejects
     * regular files whose inode has more than one hardlink. Returns the
     * canonical absolute path. Throws {@link SecurityException} on any escape,
     * mid-resolution divergence, or hardlink violation.
     *
     * <p><b>Three layers of defense</b>:
     * <ol>
     *   <li><b>Lexical + canonical</b> (via {@link #resolveContained}): rejects
     *       textual {@code ..} traversal and symlinks whose realpath escapes
     *       the root.</li>
     *   <li><b>Double-resolve</b>: re-resolves immediately and asserts the
     *       canonical target is unchanged. Achievable Java equivalent of
     *       OpenClaw's post-open re-check (Java NIO can't fstat an open
     *       {@code InputStream}, so we can't truly hold-then-validate; the
     *       double-resolve shrinks the validate→use TOCTOU window from
     *       "unbounded" to "microseconds").</li>
     *   <li><b>Hardlink rejection</b>: a regular file inside a workspace
     *       should never have {@code nlink > 1}. jclaw never creates hardlinks
     *       itself, the default shell allowlist doesn't include {@code ln},
     *       and pnpm-style hardlink dedup happens in dev trees outside any
     *       agent workspace. If we see {@code nlink > 1} here, treat it as an
     *       attempt to read across the sandbox boundary via the inode side
     *       door — hardlinks bypass the symlink check because there's no
     *       "link" to follow; both names point to the same inode. Skipped for
     *       directories (their nlink encodes subdirectory count) and on
     *       non-POSIX filesystems where {@code unix:nlink} isn't supported.</li>
     * </ol>
     *
     * <p>Callers should pass the returned path directly to the file operation
     * with no further work in between. Use from {@code FileSystemTools},
     * {@code DocumentsTool}, {@code ShellExecTool} (workdir), upload handlers,
     * and {@code serveWorkspaceFile}. Use {@link #resolveContained} only when
     * you need a non-throwing yes/no check.
     *
     * @param root         the workspace root the target must stay inside
     * @param relativePath path relative to {@code root}
     * @return the canonical absolute path inside {@code root}, double-resolved
     * @throws SecurityException on escape, mid-resolution divergence, or
     *                           hardlink violation
     */
    public static @NonNull Path acquireContained(@NonNull Path root, @NonNull String relativePath) {
        var first = resolveContained(root, relativePath);
        if (first == null) {
            throw new SecurityException("Path '%s' escapes the workspace.".formatted(relativePath));
        }
        var second = resolveContained(root, relativePath);
        if (second == null || !second.equals(first)) {
            throw new SecurityException(
                    "Path '%s' resolved to a different target between validations (possible TOCTOU)."
                            .formatted(relativePath));
        }
        // Hardlink check: only meaningful for existing regular files. Directories
        // legitimately have nlink > 1 (each subdir contributes a `..` entry), and
        // not-yet-created targets have no inode to inspect.
        try {
            if (Files.exists(second) && Files.isRegularFile(second)) {
                var nlink = Files.getAttribute(second, "unix:nlink");
                if (nlink instanceof Number n && n.intValue() > 1) {
                    throw new SecurityException(
                            "Path '%s' is a hardlink (nlink=%d); rejected to prevent cross-sandbox inode aliasing."
                                    .formatted(relativePath, n.intValue()));
                }
            }
        } catch (UnsupportedOperationException _) {
            // Non-POSIX filesystem (e.g. Windows / FAT). Lexical and canonical
            // layers still apply; just degrade the hardlink check.
        } catch (IOException e) {
            throw new SecurityException(
                    "Failed to inspect '%s': %s".formatted(relativePath, e.getMessage()));
        }
        return second;
    }
}
