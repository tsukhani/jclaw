package utils;

import org.jspecify.annotations.Nullable;
import play.Play;
import services.database.DatabaseService;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * JCLAW-1452: the directories a sandboxed process may neither read nor write — JClaw's own
 * state, where the H2 file keeps third-party credentials in plaintext, its lock file the key
 * a client must present to the {@code AUTO_SERVER} TCP port, and {@code certs/.env} the
 * instance secret. {@code tools.HarnessSandbox} hides each one, then reopens a write root or
 * allowance that lies beneath it.
 */
public final class SandboxHiddenRoots {

    private static final List<String> STATE_DIRS = List.of("data", "certs", "conf");

    private SandboxHiddenRoots() {}

    /**
     * {@code data/}, {@code certs/} and {@code conf/} under the application root and under the
     * working directory, which H2's {@code ./data/jclaw} resolves against, plus the backup
     * directory, which an operator may move out of {@code data/}. Canonical, because Seatbelt
     * matches after symlink resolution; a root nested inside another is dropped.
     */
    public static List<Path> current() {
        var bases = new ArrayList<Path>();
        bases.add(Play.applicationPath.toPath());
        var cwd = System.getProperty("user.dir", "");
        if (!cwd.isBlank()) bases.add(Path.of(cwd));
        var roots = new LinkedHashSet<Path>();
        for (var base : bases) {
            for (var dir : STATE_DIRS) roots.add(canonical(base.resolve(dir)));
        }
        roots.add(canonical(DatabaseService.backupsDir()));
        return outermost(roots);
    }

    /** {@code path} in canonical form when it lies at or beneath one of the canonical {@code hidden} roots, else null. */
    public static @Nullable Path beneath(String path, List<Path> hidden) {
        var c = canonical(Path.of(path));
        return hidden.stream().anyMatch(c::startsWith) ? c : null;
    }

    /** {@code path} made absolute with the symlinks in its existing prefix resolved; a missing tail is kept as written. */
    public static Path canonical(Path path) {
        var abs = path.toAbsolutePath().normalize();
        try {
            var c = WorkspacePathGuard.canonicalize(abs);
            return c != null ? c : abs;
        } catch (IOException _) {
            return abs;
        }
    }

    private static List<Path> outermost(Collection<Path> roots) {
        return roots.stream()
                .filter(r -> roots.stream().noneMatch(o -> !o.equals(r) && r.startsWith(o)))
                .toList();
    }
}
