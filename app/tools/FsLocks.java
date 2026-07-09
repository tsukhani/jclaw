package tools;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * JVM-global per-file locking for {@link FileSystemTools}. Single-target mutations go
 * through {@link #withLock}; multi-file patches acquire an ordered lock set through
 * {@link #runUnderFileLocks} so concurrent applies on overlapping file sets can't deadlock.
 */
final class FsLocks {

    private FsLocks() {}

    /**
     * Per-file reentrant locks keyed on the canonical absolute-normalized path. Ensures
     * that two concurrent tool calls on the same file serialize instead of clobbering
     * each other. The map grows monotonically with unique files seen across the JVM
     * lifetime; documented and accepted — workspace file counts are small (hundreds,
     * maybe low thousands).
     */
    private static final ConcurrentMap<String, ReentrantLock> FILE_LOCKS = new ConcurrentHashMap<>();

    private static String lockKey(Path target) {
        return target.toAbsolutePath().normalize().toString();
    }

    static String withLock(Path target, Supplier<String> block) {
        var lock = FILE_LOCKS.computeIfAbsent(lockKey(target), k -> new ReentrantLock());
        lock.lock();
        try {
            return block.get();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Acquire the file locks for every path in {@code targets} in a global lexicographic
     * order (deadlock-free across concurrent applyPatch calls on overlapping file sets) and
     * run {@code block} inside that critical section. Duplicate paths collapse to one lock.
     */
    static String runUnderFileLocks(List<Path> targets, Supplier<String> block) {
        var lockKeys = new LinkedHashSet<String>();
        for (var t : targets) {
            lockKeys.add(lockKey(t));
        }
        var sortedKeys = new ArrayList<>(lockKeys);
        Collections.sort(sortedKeys);

        // Resolve all locks first (allocation only, nothing acquired yet) so a
        // late OOM during ArrayList growth can't leave a held lock outside the
        // finally's reach. Then acquire in order, tracking how many we grabbed.
        // The finally only releases the prefix we actually own — addresses
        // SonarQube S2222 (lock-not-unlocked-on-all-paths) by removing the
        // window between lock() and locks.add() the previous shape had.
        var locks = new ArrayList<ReentrantLock>(sortedKeys.size());
        for (var key : sortedKeys) {
            locks.add(FILE_LOCKS.computeIfAbsent(key, k -> new ReentrantLock()));
        }
        int acquired = 0;
        try {
            while (acquired < locks.size()) {
                locks.get(acquired).lock();
                acquired++;
            }
            return block.get();
        } finally {
            for (int i = acquired - 1; i >= 0; i--) {
                locks.get(i).unlock();
            }
        }
    }
}
