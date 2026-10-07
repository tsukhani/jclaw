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
import java.util.function.Predicate;
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
    public static class GraphRefusedException extends RuntimeException {
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

    /** Thrown when a write would store Evidence from a run the agent's ledger marks retracted; nothing changes. */
    public static final class RunRetractedException extends GraphRefusedException {
        private final String runId;

        RunRetractedException(long agentId, String runId) {
            super("run retracted: " + runId + " in the graph of agent " + agentId, List.of(), List.of());
            this.runId = runId;
        }

        public String runId() {
            return runId;
        }
    }

    /**
     * What a retraction removed: {@code runs} newly marked retracted, {@code evidence} Evidence removed (cascade
     * included), {@code records} other records removed.
     */
    public record Retraction(int runs, int evidence, int records) {
        public static final Retraction NONE = new Retraction(0, 0, 0);

        public Retraction plus(Retraction other) {
            return new Retraction(runs + other.runs, evidence + other.evidence, records + other.records);
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

    // ---- the run ledger ----

    /**
     * Apply one extraction run's {@code change} and upsert its ledger {@code entry}, both in one swap. Every Evidence
     * the change adds must carry the entry's run id; a run that wrote nothing must leave the records as they were.
     *
     * @throws RunRetractedException    when the ledger already marks the run retracted
     * @throws IllegalArgumentException when the change breaks either rule, or the entry is for another agent
     * @throws GraphRefusedException    when the resulting set fails validation
     */
    public void recordRun(long agentId, RunLedger.Entry entry, UnaryOperator<List<OntologyRecord>> change)
            throws IOException {
        if (entry.agentId() != agentId) {
            throw new IllegalArgumentException("run " + entry.runId() + " belongs to agent " + entry.agentId()
                    + ", not " + agentId);
        }
        if (entry.retracted()) throw new IllegalArgumentException("run " + entry.runId() + " is recorded unretracted");
        locked(agentId, () -> {
            var ledger = ledgerLocked(agentId);
            var existing = ledger.get(entry.runId());
            if (existing != null && existing.retracted()) throw new RunRetractedException(agentId, entry.runId());
            var before = readLocked(agentId);
            var next = List.copyOf(change.apply(new ArrayList<>(before)));
            var beforeIds = before.stream().map(OntologyRecord::id).collect(Collectors.toSet());
            for (var r : next) {
                if (r instanceof Evidence e && !beforeIds.contains(e.id()) && !entry.runId().equals(e.runId())) {
                    throw new IllegalArgumentException("evidence " + e.id() + " carries run " + e.runId()
                            + ", not " + entry.runId());
                }
            }
            if (entry.outcome() != RunLedger.Outcome.WRITTEN && !Set.copyOf(next).equals(Set.copyOf(before))) {
                throw new IllegalArgumentException("run " + entry.runId() + " is " + entry.outcome().wire()
                        + " and may not change the graph");
            }
            ledger.put(entry.runId(), entry);
            writeLocked(agentId, next, RunLedger.document(ledger.values()));
            return true;
        });
    }

    /** Retract the named runs from the agent's graph; a run the ledger does not hold, or holds retracted, adds nothing. */
    public Retraction retract(long agentId, Set<String> runIds) throws IOException {
        return locked(agentId, () -> retractLocked(agentId, e -> runIds.contains(e.runId())));
    }

    /** Retract every run the selector matches in every agent's ledger, summing the counts. */
    public Retraction retract(RunLedger.Selector selector) throws IOException {
        var total = Retraction.NONE;
        for (var agentId : agentIds()) {
            total = total.plus(locked(agentId, () -> retractLocked(agentId, selector::matches)));
        }
        return total;
    }

    /** The agent's ledger, sorted by run id; empty when it has none. */
    public List<RunLedger.Entry> ledger(long agentId) throws IOException {
        return locked(agentId, () -> List.copyOf(ledgerLocked(agentId).values()));
    }

    /** The run ids in the agent's ledger the selector matches, retracted or not. */
    public SortedSet<String> runs(long agentId, RunLedger.Selector selector) throws IOException {
        var out = new TreeSet<String>();
        for (var e : ledger(agentId)) {
            if (selector.matches(e)) out.add(e.runId());
        }
        return out;
    }

    private Retraction retractLocked(long agentId, Predicate<RunLedger.Entry> filter) throws IOException {
        if (!hasGraph(agentId)) return Retraction.NONE;
        var ledger = ledgerLocked(agentId);
        var targets = new TreeMap<String, RunLedger.Entry>();
        for (var e : ledger.values()) {
            if (!e.retracted() && filter.test(e)) targets.put(e.runId(), e);
        }
        if (targets.isEmpty()) return Retraction.NONE;
        var records = readLocked(agentId);
        var evidenceIds = new TreeSet<String>();
        for (var r : records) {
            if (r instanceof Evidence e) {
                var runId = e.runId();
                if (runId != null && targets.containsKey(runId)) evidenceIds.add(e.id());
            }
        }
        var withdrawn = GraphWithdrawal.withdrawEvidence(records, evidenceIds);
        var wereEvidence = records.stream().filter(r -> r instanceof Evidence).map(OntologyRecord::id)
                .collect(Collectors.toSet());
        int evidence = (int) withdrawn.removedIds().stream().filter(wereEvidence::contains).count();
        int other = withdrawn.removedIds().size() - evidence;
        targets.values().forEach(e -> ledger.put(e.runId(), e.withRetracted(true)));
        // A predecessor's lineage stamp names no run, only its successor's source, which a standing run may still hold.
        var standing = new TreeSet<String>();
        for (var e : ledger.values()) {
            if (!e.retracted()) standing.add(e.source());
        }
        var successors = new TreeSet<String>();
        for (var e : targets.values()) {
            if (!standing.contains(e.source())) successors.add(e.source());
        }
        var cleared = GraphWithdrawal.clearLineage(withdrawn.survivors(), successors);
        writeLocked(agentId, cleared.records(), RunLedger.document(ledger.values()));
        return new Retraction(targets.size(), evidence, other);
    }

    /** The ledger by run id, read under the agent's lock. */
    private TreeMap<String, RunLedger.Entry> ledgerLocked(long agentId) throws IOException {
        var out = new TreeMap<String, RunLedger.Entry>();
        var file = agentDir(agentId).resolve(RunLedger.FILE_NAME);
        if (!Files.isRegularFile(file)) return out;
        for (var e : RunLedger.parse(Files.readString(file, StandardCharsets.UTF_8), agentId + "/" + RunLedger.FILE_NAME)) {
            out.put(e.runId(), e);
        }
        return out;
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

    /** Retire each memory's Evidence as given; writes only when a stamp changes something. */
    public GraphWithdrawal.Stamped retire(long agentId, Map<Long, GraphWithdrawal.Retirement> byMemoryId)
            throws IOException {
        var bySource = new TreeMap<String, GraphWithdrawal.Retirement>();
        byMemoryId.forEach((id, r) -> bySource.put(memorySource(id), r));
        return locked(agentId, () -> {
            if (!hasGraph(agentId) || bySource.isEmpty()) return new GraphWithdrawal.Stamped(List.of(), 0, 0);
            var stamped = GraphWithdrawal.retire(readLocked(agentId), bySource);
            if (stamped.evidenceRetired() > 0) writeLocked(agentId, stamped.records());
            return stamped;
        });
    }

    /** Retire each predecessor and stamp its lineage; returns true when anything was written. */
    public boolean recordLineage(long agentId, Map<Long, GraphWithdrawal.LineageDecision> byPredecessorMemoryId)
            throws IOException {
        var bySource = new TreeMap<String, GraphWithdrawal.LineageDecision>();
        byPredecessorMemoryId.forEach((id, d) -> bySource.put(memorySource(id), d));
        return locked(agentId, () -> {
            if (!hasGraph(agentId) || bySource.isEmpty()) return false;
            var lineaged = GraphWithdrawal.recordLineage(readLocked(agentId), bySource);
            if (lineaged.changed()) writeLocked(agentId, lineaged.records());
            return lineaged.changed();
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
        writeLocked(agentId, records, null);
    }

    /**
     * Validate and swap in {@code records}; a non-null {@code ledgerDocument} replaces the ledger in the same swap,
     * a null one carries the current ledger over unchanged.
     */
    private void writeLocked(long agentId, Collection<? extends OntologyRecord> records,
            @Nullable String ledgerDocument) throws IOException {
        var retracted = new TreeSet<String>();
        var ledger = ledgerDocument == null ? ledgerLocked(agentId).values()
                : RunLedger.parse(ledgerDocument, agentId + "/" + RunLedger.FILE_NAME);
        for (var e : ledger) {
            if (e.retracted()) retracted.add(e.runId());
        }
        for (var r : records) {
            if (r instanceof Evidence e) {
                var runId = e.runId();
                if (runId != null && retracted.contains(runId)) throw new RunRetractedException(agentId, runId);
            }
        }
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
                        .filter(p -> ledgerDocument == null || !p.getFileName().toString().equals(RunLedger.FILE_NAME))
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
            if (ledgerDocument != null) {
                Files.writeString(dir.resolve(RunLedger.FILE_NAME), ledgerDocument, StandardCharsets.UTF_8);
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
