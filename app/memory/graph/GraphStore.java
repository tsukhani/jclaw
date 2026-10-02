package memory.graph;

import memory.graph.GraphCodec.Family;
import memory.ontology.OntologyRecord;
import memory.ontology.OntologyRecord.Evidence;
import memory.ontology.OntologyRecord.Mapping;
import memory.ontology.OntologySchema;
import memory.ontology.OntologyValidator;
import memory.ontology.OntologyValidator.Violation;
import org.jspecify.annotations.Nullable;
import play.Play;
import services.AtomicDirSwap;
import services.database.H2Maintenance;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Each agent's knowledge graph as one directory of canonical JSONL documents, one per record
 * family, under {@code <root>/<agentId>/}. A write validates the whole set against the seed
 * ontology and swaps a freshly-built directory into place, so a reader never sees two
 * generations mixed. Ids are the caller's; the store never mints or rewrites one.
 */
public final class GraphStore {

    /** The source string an Evidence or Mapping uses to name a memory row. */
    public static final String MEMORY_SOURCE_PREFIX = "memory:";
    public static final String DIR_NAME = H2Maintenance.GRAPH_DIR;
    static final String TEST_DIR_NAME = "memory-graph-test";
    static final String STAGING_SUFFIX = ".staging";
    static final String PREVIOUS_SUFFIX = ".previous";

    private static final Pattern AGENT_ENTRY = Pattern.compile("(\\d+)(?:\\.staging|\\.previous)?");

    /** Writers share the read side; a backup takes the write side so it never sees a half-swapped directory. */
    private static final ReentrantReadWriteLock WRITERS = new ReentrantReadWriteLock();

    /** Thrown when a write would store a set the ontology refuses; nothing on disk changes. */
    public static final class GraphRefusedException extends RuntimeException {
        private final List<Violation> violations;
        private final List<String> foreignRecordIds;

        GraphRefusedException(String message, List<Violation> violations, List<String> foreignRecordIds) {
            super(message);
            this.violations = List.copyOf(violations);
            this.foreignRecordIds = List.copyOf(foreignRecordIds);
        }

        public List<Violation> violations() {
            return violations;
        }

        /** Records whose {@code agentId} is not the store's agent. */
        public List<String> foreignRecordIds() {
            return foreignRecordIds;
        }
    }

    /** The derived lookup an agent's graph answers from: records by id, Evidence ids by source. */
    public record Index(Map<String, OntologyRecord> byId, Map<String, Set<String>> evidenceIdsBySource) {
        static Index of(Collection<? extends OntologyRecord> records) {
            var byId = new TreeMap<String, OntologyRecord>();
            var bySource = new TreeMap<String, Set<String>>();
            for (var r : records) {
                byId.put(r.id(), r);
                if (r instanceof Evidence e) bySource.computeIfAbsent(e.source(), _ -> new TreeSet<>()).add(e.id());
            }
            bySource.replaceAll((_, ids) -> Collections.unmodifiableSet(ids));
            return new Index(Collections.unmodifiableMap(byId), Collections.unmodifiableMap(bySource));
        }
    }

    private static final class Default {
        static final GraphStore INSTANCE = create();

        private static GraphStore create() {
            var data = Play.applicationPath.toPath().resolve("data");
            if (!Play.runningInTestMode()) return new GraphStore(data.resolve(DIR_NAME));
            var root = data.resolve(TEST_DIR_NAME);
            // H2's test ids restart every run, so a previous run's graphs would be read as this run's.
            try {
                AtomicDirSwap.deleteRecursive(root);
            } catch (IOException e) {
                throw new IllegalStateException("cannot clear " + root, e);
            }
            return new GraphStore(root);
        }
    }

    private static final class Schema {
        static final OntologySchema SEED = OntologySchema.seed();
    }

    private final Path root;
    private final ConcurrentMap<Long, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Index> indexes = new ConcurrentHashMap<>();
    private volatile int failAfterFiles = -1;

    public GraphStore(Path root) {
        this.root = root;
    }

    /** The application's store under {@code data/}, or {@code data/memory-graph-test} under {@code %test}. */
    public static GraphStore get() {
        return Default.INSTANCE;
    }

    public Path root() {
        return root;
    }

    public Path agentDir(long agentId) {
        return root.resolve(String.valueOf(agentId));
    }

    /** Make the next writes' staging step fail after {@code files} family files; negative disarms it. */
    public void failAfterFilesForTest(int files) {
        failAfterFiles = files;
    }

    /** The derived index as of the last read or write, or null when neither has happened since load or delete. */
    public @Nullable Index index(long agentId) {
        return indexes.get(agentId);
    }

    // ---- reads and writes ----

    public List<OntologyRecord> read(long agentId) throws IOException {
        return locked(agentId, () -> readLocked(agentId));
    }

    /**
     * Replace the agent's graph with {@code records}.
     *
     * @throws GraphRefusedException when the set fails validation or names another agent
     */
    public void write(long agentId, Collection<? extends OntologyRecord> records) throws IOException {
        locked(agentId, () -> {
            writeLocked(agentId, records);
            return true;
        });
    }

    /** Read, change and write under the agent's lock; returns what was written. */
    public List<OntologyRecord> update(long agentId, UnaryOperator<List<OntologyRecord>> change) throws IOException {
        return locked(agentId, () -> {
            var next = List.copyOf(change.apply(new ArrayList<>(readLocked(agentId))));
            writeLocked(agentId, next);
            return next;
        });
    }

    /** Withdraw {@code memory:<id>} for each id, cascading; returns the removed record ids. */
    public Set<String> withdraw(long agentId, Set<Long> memoryIds) throws IOException {
        var sources = memoryIds.stream().map(GraphStore::memorySource).collect(Collectors.toSet());
        return withdrawSources(agentId, _ -> sources);
    }

    /** Withdraw every {@code memory:} source the agent's graph names; returns the removed record ids. */
    public Set<String> withdrawAllMemoryEvidence(long agentId) throws IOException {
        return withdrawSources(agentId, records -> {
            var sources = new TreeSet<String>();
            for (var r : records) {
                var source = sourceOf(r);
                if (source != null && source.startsWith(MEMORY_SOURCE_PREFIX)) sources.add(source);
            }
            return sources;
        });
    }

    public static String memorySource(long memoryId) {
        return MEMORY_SOURCE_PREFIX + memoryId;
    }

    /** The source an Evidence or Mapping names; null for the other families. */
    public static @Nullable String sourceOf(OntologyRecord record) {
        return switch (record) {
            case Evidence e -> e.source();
            case Mapping m -> m.source();
            default -> null;
        };
    }

    private interface SourceSelector {
        Set<String> select(List<OntologyRecord> records);
    }

    private Set<String> withdrawSources(long agentId, SourceSelector selector) throws IOException {
        return locked(agentId, () -> {
            if (!hasGraph(agentId)) return Set.<String>of();
            var records = readLocked(agentId);
            var sources = selector.select(records);
            if (sources.isEmpty()) return Set.<String>of();
            var result = GraphWithdrawal.withdraw(records, sources);
            if (!result.removedIds().isEmpty()) writeLocked(agentId, result.survivors());
            return result.removedIds();
        });
    }

    /** Remove the agent's directory, its staging and backup siblings, and its index. */
    public void deleteAgent(long agentId) throws IOException {
        locked(agentId, () -> {
            AtomicDirSwap.deleteRecursive(agentDir(agentId));
            AtomicDirSwap.deleteRecursive(staging(agentId));
            AtomicDirSwap.deleteRecursive(previous(agentId));
            indexes.remove(agentId);
            return true;
        });
    }

    /** Every agent with a graph directory, or the remains of an interrupted swap. */
    public SortedSet<Long> agentIds() throws IOException {
        var ids = new TreeSet<Long>();
        if (!Files.isDirectory(root)) return ids;
        try (var entries = Files.list(root)) {
            entries.forEach(p -> {
                var m = AGENT_ENTRY.matcher(p.getFileName().toString());
                if (m.matches() && Files.isDirectory(p)) {
                    try {
                        ids.add(Long.parseLong(m.group(1)));
                    } catch (NumberFormatException _) {
                        // too many digits to be an agent id
                    }
                }
            });
        }
        return ids;
    }

    /** Run {@code body} with every writer held off, so a copy of {@link #root} is one consistent generation. */
    public <T> T pauseWriters(Callable<T> body) throws Exception {
        WRITERS.writeLock().lock();
        try {
            return body.call();
        } finally {
            WRITERS.writeLock().unlock();
        }
    }

    /** Repair what a crashed swap left; returns true when anything was moved or deleted. */
    public boolean recover(long agentId) throws IOException {
        return locked(agentId, false, () -> recoverLocked(agentId));
    }

    // ---- internals ----

    @FunctionalInterface
    private interface LockedBody<T> {
        T run() throws IOException;
    }

    private <T> T locked(long agentId, LockedBody<T> body) throws IOException {
        return locked(agentId, true, body);
    }

    private <T> T locked(long agentId, boolean recoverFirst, LockedBody<T> body) throws IOException {
        WRITERS.readLock().lock();
        try {
            var lock = locks.computeIfAbsent(agentId, _ -> new ReentrantLock());
            lock.lock();
            try {
                if (recoverFirst) recoverLocked(agentId);
                return body.run();
            } finally {
                lock.unlock();
            }
        } finally {
            WRITERS.readLock().unlock();
        }
    }

    private boolean hasGraph(long agentId) {
        return Files.isDirectory(agentDir(agentId)) || Files.isDirectory(previous(agentId));
    }

    private Path staging(long agentId) {
        return root.resolve(agentId + STAGING_SUFFIX);
    }

    private Path previous(long agentId) {
        return root.resolve(agentId + PREVIOUS_SUFFIX);
    }

    private boolean recoverLocked(long agentId) throws IOException {
        var target = agentDir(agentId);
        var previous = previous(agentId);
        var staging = staging(agentId);
        var changed = false;
        if (Files.isDirectory(previous)) {
            if (Files.exists(target)) {
                // The swap finished but its backup was never deleted; the next swap would collide with it.
                AtomicDirSwap.deleteRecursive(previous);
            } else {
                Files.move(previous, target);
            }
            changed = true;
        }
        if (Files.exists(staging)) {
            AtomicDirSwap.deleteRecursive(staging);
            changed = true;
        }
        if (changed) indexes.remove(agentId);
        return changed;
    }

    private List<OntologyRecord> readLocked(long agentId) throws IOException {
        var dir = agentDir(agentId);
        var records = new ArrayList<OntologyRecord>();
        if (Files.isDirectory(dir)) {
            for (var family : Family.values()) {
                var file = dir.resolve(family.fileName());
                if (!Files.isRegularFile(file)) continue;
                records.addAll(GraphCodec.parseDocument(family, Files.readString(file, StandardCharsets.UTF_8),
                        agentId + "/" + family.fileName()));
            }
        }
        indexes.put(agentId, Index.of(records));
        return List.copyOf(records);
    }

    private void writeLocked(long agentId, Collection<? extends OntologyRecord> records) throws IOException {
        var foreign = records.stream().filter(r -> r.meta().agentId() != agentId).map(OntologyRecord::id)
                .sorted().toList();
        if (!foreign.isEmpty()) {
            throw new GraphRefusedException("records belong to another agent than " + agentId + ": "
                    + String.join(", ", foreign), List.of(), foreign);
        }
        var violations = OntologyValidator.validate(Schema.SEED, records);
        if (!violations.isEmpty()) {
            throw new GraphRefusedException("graph for agent " + agentId + " refused: "
                    + violations.stream().map(Violation::message).collect(Collectors.joining("; ")),
                    violations, List.of());
        }
        var documents = new EnumMap<Family, String>(Family.class);
        for (var family : Family.values()) documents.put(family, GraphCodec.document(family, records));

        var target = agentDir(agentId);
        Files.createDirectories(root);
        var replacing = Files.isDirectory(target);
        var unknown = new ArrayList<Path>();
        if (replacing) {
            try (var entries = Files.list(target)) {
                entries.filter(p -> Files.isRegularFile(p) && Family.ofFileName(p.getFileName().toString()) == null)
                        .sorted()
                        .forEach(unknown::add);
            }
        }
        var failAfter = failAfterFiles;
        AtomicDirSwap.stageAndSwap(target, staging(agentId), previous(agentId), replacing, dir -> {
            var written = 0;
            for (var entry : documents.entrySet()) {
                Files.writeString(dir.resolve(entry.getKey().fileName()), entry.getValue(), StandardCharsets.UTF_8);
                if (++written == failAfter) throw new IOException("staging failed after " + written + " files");
            }
            for (var file : unknown) {
                Files.copy(file, dir.resolve(file.getFileName()), StandardCopyOption.COPY_ATTRIBUTES);
            }
            // The swap deletes the previous generation, so the new one must be on disk before it moves in.
            try (var staged = Files.list(dir)) {
                for (var file : staged.toList()) {
                    try (var channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
                        channel.force(true);
                    }
                }
            }
        });
        indexes.put(agentId, Index.of(records));
    }
}
