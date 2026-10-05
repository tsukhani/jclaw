package memory.ontology;

import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import play.Play;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The parsed seed ontology document ({@code conf/ontology/seed-schema.yaml}): record families,
 * structural references, the system-time, date and claim glosses, term types and relation types,
 * each with its standard identifier. Types are data — adding one is an edit to the document, never
 * to this class. The three gloss sections are null below version 3.
 */
public record OntologySchema(
        int version,
        SortedMap<String, Family> families,
        List<String> references,
        @Nullable SystemTime systemTime,
        @Nullable Gloss dates,
        @Nullable Claims claims,
        SortedMap<String, TermType> termTypes,
        SortedMap<String, RelationType> relations) {

    public static final String SEED_PATH = "conf/ontology/seed-schema.yaml";
    public static final String NONE = "none";
    public static final String SAME = "same";
    public static final String ANY = "Any";
    private static final String ARROW = "->";
    // ASCII unit separator: no name, covers text or endpoint holds it, so two schemas cannot render alike.
    private static final char FIELD_SEP = (char) 0x1F;
    private static final String ENDED = "ended";
    private static final int CLAIMS_VERSION = 3;
    private static final Pattern X = Pattern.compile("\\bX\\b");
    private static final Pattern Y = Pattern.compile("\\bY\\b");
    private static final Set<String> TOP_KEYS = Set.of(
            "version", "families", "references", "system_time", "dates", "claims", "term_types", "relations");
    private static final Set<String> FAMILY_KEYS = Set.of("meaning", "must_link", "standard", "match");
    private static final Set<String> TERM_TYPE_KEYS = Set.of("covers", "standard", "match", "dated");
    private static final Set<String> RELATION_KEYS = Set.of("kind", "standard", "match", "endpoints", "reads", "status",
            "symmetric", "irreflexive", "valid", "valence");
    private static final Set<String> GLOSS_KEYS = Set.of("standard", "match", "covers");
    private static final List<String> SYSTEM_TIME_KEYS = List.of("recordedAt", "retiredAt", "lineage");
    private static final List<String> LINEAGE_KEYS = List.of("update", "restatement", "correction");
    private static final List<String> CLAIMS_KEYS = List.of("status", "valid", "occurs", "valence");
    private static final List<String> STATUS_KEYS = List.of("holds", ENDED, "denied");
    private static final List<String> VALENCE_KEYS = List.of("favorable", "unfavorable");

    public enum Match {
        EXACT,
        CLOSE
    }

    /** A standard identifier such as {@code schema:Person}, or {@code none} with a null match. */
    public record Identifier(String standard, @Nullable Match match) {
        @Override
        public String toString() {
            return match == null ? standard : standard + ", " + match.name().toLowerCase();
        }
    }

    public record Family(String meaning, String mustLink, Identifier identifier) {}

    /** A documented entry's covers text; the identifier is null only for {@code dates}, which has no standard. */
    public record Gloss(String covers, @Nullable Identifier identifier) {}

    public record SystemTime(Gloss recordedAt, Gloss retiredAt, Map<String, Gloss> lineage) {
        public SystemTime {
            lineage = Collections.unmodifiableMap(new LinkedHashMap<>(lineage));
        }
    }

    public record Claims(Map<String, Gloss> status, Gloss valid, Gloss occurs, Map<String, Gloss> valence) {
        public Claims {
            status = Collections.unmodifiableMap(new LinkedHashMap<>(status));
            valence = Collections.unmodifiableMap(new LinkedHashMap<>(valence));
        }
    }

    public record TermType(String covers, Identifier identifier, boolean dated) {}

    /** {@code reads} is null and {@code statuses} empty below version 3. */
    public record RelationType(
            String kind,
            Identifier identifier,
            List<String> endpoints,
            @Nullable String reads,
            boolean symmetric,
            boolean irreflexive,
            List<String> statuses,
            boolean valid,
            boolean valence) {
        public RelationType {
            endpoints = List.copyOf(endpoints);
            statuses = List.copyOf(statuses);
        }

        public boolean allows(String fromType, String toType) {
            for (var endpoint : endpoints) {
                if (endpoint.equals(SAME)) {
                    if (fromType.equals(toType)) return true;
                    continue;
                }
                var sides = endpoint.split(ARROW, -1);
                if (splitNames(sides[0]).contains(fromType) && splitNames(sides[1]).contains(toType)) {
                    return true;
                }
            }
            return false;
        }
    }

    public OntologySchema {
        families = Collections.unmodifiableSortedMap(new TreeMap<>(families));
        references = List.copyOf(references);
        termTypes = Collections.unmodifiableSortedMap(new TreeMap<>(termTypes));
        relations = Collections.unmodifiableSortedMap(new TreeMap<>(relations));
    }

    /** True when the relation type is declared and one of its endpoints admits the pair. */
    public boolean allows(String relationType, String fromType, String toType) {
        var relation = relations.get(relationType);
        return relation != null
                && termTypes.containsKey(fromType)
                && termTypes.containsKey(toType)
                && relation.allows(fromType, toType);
    }

    /** The relation's statuses, without ended when {@code fromType} is dated: an occurrence does not stop. */
    public List<String> effectiveStatuses(String relationType, String fromType) {
        var statuses = relation(relationType).statuses();
        if (!termType(fromType).dated()) return statuses;
        return statuses.stream().filter(s -> !s.equals(ENDED)).toList();
    }

    /** True when the relation takes a valid time from {@code fromType}; a dated From is timed by its occurrence. */
    public boolean validAllowed(String relationType, String fromType) {
        return relation(relationType).valid() && !termType(fromType).dated();
    }

    public boolean timeable(String relationType, String fromType) {
        return effectiveStatuses(relationType, fromType).contains(ENDED) || termType(fromType).dated();
    }

    public boolean symmetric(String relationType) {
        return relation(relationType).symmetric();
    }

    public boolean irreflexive(String relationType) {
        return relation(relationType).irreflexive();
    }

    public SortedSet<String> symmetricSet() {
        var names = new TreeSet<String>();
        relations.forEach((name, r) -> {
            if (r.symmetric()) names.add(name);
        });
        return Collections.unmodifiableSortedSet(names);
    }

    private RelationType relation(String relationType) {
        var relation = relations.get(relationType);
        if (relation == null) throw new IllegalArgumentException("relation type '" + relationType + "' is not declared");
        return relation;
    }

    private TermType termType(String name) {
        var termType = termTypes.get(name);
        if (termType == null) throw new IllegalArgumentException("term type '" + name + "' is not declared");
        return termType;
    }

    /**
     * {@code v<version>@<12 hex>}: the version and a SHA-256 prefix over what extraction asks with — the version, each
     * term type's name, covers text and dated flag, and each relation's name, endpoints, reads, symmetric flag, status
     * list, valid and valence. A graph-eval certificate holds only under the fingerprint it was measured with.
     */
    public String fingerprint() {
        var canonical = new StringBuilder().append(version).append(FIELD_SEP);
        termTypes.forEach((name, t) -> canonical.append(name).append(FIELD_SEP).append(t.covers()).append(FIELD_SEP)
                .append(t.dated()).append(FIELD_SEP));
        relations.forEach((name, r) -> {
            canonical.append(name);
            r.endpoints().forEach(e -> canonical.append(FIELD_SEP).append(e));
            canonical.append(FIELD_SEP).append(Objects.requireNonNullElse(r.reads(), NONE))
                    .append(FIELD_SEP).append(r.symmetric())
                    .append(FIELD_SEP).append(r.statuses())
                    .append(FIELD_SEP).append(r.valid())
                    .append(FIELD_SEP).append(r.valence())
                    .append(FIELD_SEP);
        });
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return "v" + version + "@" + HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public static OntologySchema seed() {
        return load(Play.applicationPath.toPath().resolve(SEED_PATH));
    }

    public static OntologySchema load(Path path) {
        try {
            return parse(Files.readString(path));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read ontology schema " + path, e);
        }
    }

    /** Parses the document, failing with {@link IllegalArgumentException} naming the bad entry. */
    public static OntologySchema parse(String yaml) {
        Object root;
        try {
            var options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            root = new Yaml(new SafeConstructor(options)).load(yaml);
        } catch (YAMLException e) {
            throw new IllegalArgumentException("ontology schema is not valid YAML: " + e.getMessage(), e);
        }
        var doc = map(root, "document");
        knownKeys(doc, TOP_KEYS, "ontology schema");
        if (!(doc.get("version") instanceof Integer version)) {
            throw new IllegalArgumentException("ontology schema: version must be an integer");
        }
        var v3 = version >= CLAIMS_VERSION;

        var families = new TreeMap<String, Family>();
        map(doc.get("families"), "families").forEach((name, value) -> {
            var where = "family " + name;
            var entry = map(value, where);
            knownKeys(entry, FAMILY_KEYS, where);
            families.put(
                    String.valueOf(name),
                    new Family(text(entry, "meaning", where), text(entry, "must_link", where), identifier(entry, where)));
        });

        var references = parseReferences(doc.get("references"), families);

        var systemTime = section(doc, "system_time", v3) ? parseSystemTime(doc.get("system_time")) : null;
        var dates = section(doc, "dates", v3) ? parseDates(doc.get("dates")) : null;
        var claims = section(doc, "claims", v3) ? parseClaims(doc.get("claims")) : null;

        var termTypes = new TreeMap<String, TermType>();
        map(doc.get("term_types"), "term_types").forEach((name, value) -> {
            var where = "term type " + name;
            var entry = map(value, where);
            knownKeys(entry, TERM_TYPE_KEYS, where);
            termTypes.put(String.valueOf(name),
                    new TermType(text(entry, "covers", where), identifier(entry, where), flag(entry, "dated", where)));
        });

        var relations = new TreeMap<String, RelationType>();
        map(doc.get("relations"), "relations").forEach((name, value) -> {
            var where = "relation " + name;
            var entry = map(value, where);
            knownKeys(entry, RELATION_KEYS, where);
            var endpoints = parseEndpoints(entry, termTypes.keySet(), where);
            relations.put(String.valueOf(name), new RelationType(
                    text(entry, "kind", where),
                    identifier(entry, where),
                    endpoints,
                    reads(entry, where, v3),
                    symmetricFlag(entry, endpoints, termTypes.keySet(), where),
                    flag(entry, "irreflexive", where),
                    statuses(entry, where, v3, claims),
                    flag(entry, "valid", where),
                    valenceFlag(entry, endpoints, where)));
        });

        return new OntologySchema(version, families, references, systemTime, dates, claims, termTypes, relations);
    }

    private static List<String> parseReferences(@Nullable Object value, Map<String, Family> families) {
        var references = new ArrayList<String>();
        for (var item : list(value, "references")) {
            var reference = String.valueOf(item);
            var sides = sides(reference, "reference '" + reference + "'");
            for (var name : splitNames(sides[0])) requireDeclared(families, name, reference);
            for (var name : splitNames(sides[1])) {
                if (!name.equals(ANY)) requireDeclared(families, name, reference);
            }
            references.add(reference);
        }
        return references;
    }

    private static List<String> parseEndpoints(Map<?, ?> entry, Set<String> termTypes, String where) {
        var endpoints = new ArrayList<String>();
        for (var endpointValue : list(entry.get("endpoints"), where + " endpoints")) {
            var endpoint = String.valueOf(endpointValue).trim();
            if (!endpoint.equals(SAME)) requireTermTypes(endpoint, termTypes, where);
            endpoints.add(endpoint);
        }
        if (endpoints.isEmpty()) throw new IllegalArgumentException(where + ": endpoints is empty");
        return endpoints;
    }

    private static void requireTermTypes(String endpoint, Set<String> termTypes, String where) {
        for (var side : sides(endpoint, where + " endpoint '" + endpoint + "'")) {
            for (var type : splitNames(side)) {
                if (!termTypes.contains(type)) {
                    throw new IllegalArgumentException(
                            where + ": endpoint '" + endpoint + "' names unknown term type '" + type + "'");
                }
            }
        }
    }

    private static boolean section(Map<?, ?> doc, String key, boolean required) {
        if (doc.get(key) != null) return true;
        if (required) throw new IllegalArgumentException("ontology schema: missing " + key);
        return false;
    }

    private static SystemTime parseSystemTime(@Nullable Object value) {
        var where = "system_time";
        var entry = required(map(value, where), SYSTEM_TIME_KEYS, where);
        return new SystemTime(
                gloss(entry.get("recordedAt"), where + " recordedAt"),
                gloss(entry.get("retiredAt"), where + " retiredAt"),
                glosses(entry.get("lineage"), LINEAGE_KEYS, where + " lineage"));
    }

    private static Gloss parseDates(@Nullable Object value) {
        var where = "dates";
        var entry = map(value, where);
        knownKeys(entry, Set.of("covers"), where);
        return new Gloss(text(entry, "covers", where), null);
    }

    private static Claims parseClaims(@Nullable Object value) {
        var where = "claims";
        var entry = required(map(value, where), CLAIMS_KEYS, where);
        return new Claims(
                glosses(entry.get("status"), STATUS_KEYS, where + " status"),
                gloss(entry.get("valid"), where + " valid"),
                gloss(entry.get("occurs"), where + " occurs"),
                glosses(entry.get("valence"), VALENCE_KEYS, where + " valence"));
    }

    /** The named glosses in document order, each of {@code keys} required and no other admitted. */
    private static Map<String, Gloss> glosses(@Nullable Object value, List<String> keys, String where) {
        var entry = required(map(value, where), keys, where);
        var glosses = new LinkedHashMap<String, Gloss>();
        entry.forEach((name, gloss) -> glosses.put(String.valueOf(name), gloss(gloss, where + " " + name)));
        return glosses;
    }

    private static Gloss gloss(@Nullable Object value, String where) {
        var entry = map(value, where);
        knownKeys(entry, GLOSS_KEYS, where);
        return new Gloss(text(entry, "covers", where), identifier(entry, where));
    }

    private static Map<?, ?> required(Map<?, ?> entry, List<String> keys, String where) {
        knownKeys(entry, Set.copyOf(keys), where);
        for (var key : keys) {
            if (entry.get(key) == null) throw new IllegalArgumentException(where + ": missing " + key);
        }
        return entry;
    }

    private static @Nullable String reads(Map<?, ?> entry, String where, boolean required) {
        if (entry.get("reads") == null && !required) return null;
        var reads = text(entry, "reads", where);
        if (count(X, reads) != 1 || count(Y, reads) != 1) {
            throw new IllegalArgumentException(
                    where + ": reads must hold exactly one whole-word X and one whole-word Y, was '" + reads + "'");
        }
        return reads;
    }

    private static long count(Pattern pattern, String text) {
        return pattern.matcher(text).results().count();
    }

    private static List<String> statuses(Map<?, ?> entry, String where, boolean required, @Nullable Claims claims) {
        var value = entry.get("status");
        if (value == null) {
            if (required) throw new IllegalArgumentException(where + ": missing status");
            return List.of();
        }
        var list = list(value, where + " status");
        if (list.isEmpty()) throw new IllegalArgumentException(where + ": status is empty");
        var known = claims == null ? Set.<String>of() : claims.status().keySet();
        var statuses = new ArrayList<String>();
        for (var status : list) {
            if (!(status instanceof String name)) {
                throw new IllegalArgumentException(where + ": status must list strings, was '" + status + "'");
            }
            if (!known.contains(name)) {
                throw new IllegalArgumentException(where + ": status " + name + " is not a key of claims.status");
            }
            statuses.add(name);
        }
        if (statuses.contains(ENDED) && !flag(entry, "valid", where)) {
            throw new IllegalArgumentException(where + ": status " + ENDED + " needs valid: true");
        }
        return statuses;
    }

    private static boolean symmetricFlag(Map<?, ?> entry, List<String> endpoints, Set<String> termTypes, String where) {
        var symmetric = flag(entry, "symmetric", where);
        if (!symmetric) return false;
        var from = new TreeSet<String>();
        var to = new TreeSet<String>();
        for (var endpoint : endpoints) {
            if (endpoint.equals(SAME)) {
                from.addAll(termTypes);
                to.addAll(termTypes);
            } else {
                var sides = endpoint.split(ARROW, -1);
                from.addAll(splitNames(sides[0]));
                to.addAll(splitNames(sides[1]));
            }
        }
        from.removeAll(to);
        if (!from.isEmpty()) {
            throw new IllegalArgumentException(
                    where + ": symmetric needs every From type to be a To type, but " + from.first() + " is not");
        }
        return true;
    }

    private static boolean valenceFlag(Map<?, ?> entry, List<String> endpoints, String where) {
        var valence = flag(entry, "valence", where);
        if (valence && !(endpoints.size() == 1 && isPersonToTopic(endpoints.get(0)))) {
            throw new IllegalArgumentException(where + ": valence needs the only endpoint to be Person -> Topic");
        }
        return valence;
    }

    private static boolean isPersonToTopic(String endpoint) {
        if (endpoint.equals(SAME)) return false;
        var sides = endpoint.split(ARROW, -1);
        return splitNames(sides[0]).equals(List.of("Person")) && splitNames(sides[1]).equals(List.of("Topic"));
    }

    private static boolean flag(Map<?, ?> entry, String key, String where) {
        var value = entry.get(key);
        if (value == null) return false;
        if (value instanceof Boolean flag) return flag;
        throw new IllegalArgumentException(where + ": " + key + " must be a boolean, was '" + value + "'");
    }

    /** Reports the first unknown key in sorted order, so the message does not depend on document order. */
    private static void knownKeys(Map<?, ?> entry, Set<String> known, String where) {
        var unknown = new TreeSet<String>();
        for (var key : entry.keySet()) {
            if (!known.contains(String.valueOf(key))) unknown.add(String.valueOf(key));
        }
        if (!unknown.isEmpty()) throw new IllegalArgumentException(where + ": unknown key '" + unknown.first() + "'");
    }

    /** The readable view of two versions: one sorted line per added, removed or changed entry. */
    public static List<String> diff(OntologySchema a, OntologySchema b) {
        var lines = new ArrayList<String>();
        if (a.version != b.version) lines.add("~ version: " + a.version + " -> " + b.version);
        diffMap(lines, "family", a.families, b.families, (x, y) -> {
            var changes = new ArrayList<String>();
            change(changes, "meaning", x.meaning(), y.meaning());
            change(changes, "must_link", x.mustLink(), y.mustLink());
            changeIdentifier(changes, x.identifier(), y.identifier());
            return changes;
        }, f -> f.identifier().toString());
        for (var reference : a.references) {
            if (!b.references.contains(reference)) lines.add("- reference " + reference);
        }
        for (var reference : b.references) {
            if (!a.references.contains(reference)) lines.add("+ reference " + reference);
        }
        diffMap(lines, "system_time", systemTimeEntries(a.systemTime), systemTimeEntries(b.systemTime),
                OntologySchema::glossChanges, OntologySchema::glossSummary);
        diffDates(lines, a.dates, b.dates);
        diffMap(lines, "claims", claimsEntries(a.claims), claimsEntries(b.claims),
                OntologySchema::glossChanges, OntologySchema::glossSummary);
        diffMap(lines, "term type", a.termTypes, b.termTypes, (x, y) -> {
            var changes = new ArrayList<String>();
            change(changes, "covers", x.covers(), y.covers());
            changeIdentifier(changes, x.identifier(), y.identifier());
            change(changes, "dated", x.dated(), y.dated());
            return changes;
        }, t -> t.identifier().toString());
        diffMap(lines, "relation", a.relations, b.relations, (x, y) -> {
            var changes = new ArrayList<String>();
            change(changes, "kind", x.kind(), y.kind());
            changeIdentifier(changes, x.identifier(), y.identifier());
            change(changes, "endpoints", x.endpoints().toString(), y.endpoints().toString());
            change(changes, "reads", Objects.requireNonNullElse(x.reads(), NONE),
                    Objects.requireNonNullElse(y.reads(), NONE));
            change(changes, "symmetric", x.symmetric(), y.symmetric());
            change(changes, "irreflexive", x.irreflexive(), y.irreflexive());
            change(changes, "status", x.statuses().toString(), y.statuses().toString());
            change(changes, "valid", x.valid(), y.valid());
            change(changes, "valence", x.valence(), y.valence());
            return changes;
        }, r -> r.identifier().toString());
        Collections.sort(lines);
        return List.copyOf(lines);
    }

    private static <T> void diffMap(
            List<String> lines,
            String label,
            SortedMap<String, T> a,
            SortedMap<String, T> b,
            BiFunction<T, T, List<String>> changes,
            Function<T, String> summary) {
        a.forEach((name, x) -> {
            var y = b.get(name);
            if (y == null) {
                lines.add("- " + label + " " + name);
            } else {
                for (var change : changes.apply(x, y)) lines.add("~ " + label + " " + name + " " + change);
            }
        });
        b.forEach((name, y) -> {
            if (!a.containsKey(name)) lines.add("+ " + label + " " + name + " (" + summary.apply(y) + ")");
        });
    }

    private static void change(List<String> changes, String field, String x, String y) {
        if (!x.equals(y)) changes.add(field + ": " + x + " -> " + y);
    }

    private static void change(List<String> changes, String field, boolean x, boolean y) {
        change(changes, field, String.valueOf(x), String.valueOf(y));
    }

    private static SortedMap<String, Gloss> systemTimeEntries(@Nullable SystemTime systemTime) {
        var entries = new TreeMap<String, Gloss>();
        if (systemTime == null) return entries;
        entries.put("recordedAt", systemTime.recordedAt());
        entries.put("retiredAt", systemTime.retiredAt());
        systemTime.lineage().forEach((name, gloss) -> entries.put("lineage " + name, gloss));
        return entries;
    }

    private static SortedMap<String, Gloss> claimsEntries(@Nullable Claims claims) {
        var entries = new TreeMap<String, Gloss>();
        if (claims == null) return entries;
        claims.status().forEach((name, gloss) -> entries.put("status " + name, gloss));
        entries.put("valid", claims.valid());
        entries.put("occurs", claims.occurs());
        claims.valence().forEach((name, gloss) -> entries.put("valence " + name, gloss));
        return entries;
    }

    private static void diffDates(List<String> lines, @Nullable Gloss a, @Nullable Gloss b) {
        if (a == null && b != null) lines.add("+ dates (" + glossSummary(b) + ")");
        if (a != null && b == null) lines.add("- dates");
        if (a != null && b != null) {
            for (var change : glossChanges(a, b)) lines.add("~ dates " + change);
        }
    }

    private static List<String> glossChanges(Gloss x, Gloss y) {
        var changes = new ArrayList<String>();
        change(changes, "covers", x.covers(), y.covers());
        var xId = x.identifier();
        var yId = y.identifier();
        if (xId != null && yId != null) changeIdentifier(changes, xId, yId);
        return changes;
    }

    private static String glossSummary(Gloss gloss) {
        var identifier = gloss.identifier();
        return identifier == null ? "no standard" : identifier.toString();
    }

    private static void changeIdentifier(List<String> changes, Identifier x, Identifier y) {
        change(changes, "standard", x.standard(), y.standard());
        if (!Objects.equals(x.match(), y.match())) changes.add("match: " + matchName(x) + " -> " + matchName(y));
    }

    private static String matchName(Identifier identifier) {
        var match = identifier.match();
        return match == null ? NONE : match.name().toLowerCase();
    }

    private static Identifier identifier(Map<?, ?> entry, String where) {
        var standard = text(entry, "standard", where);
        var match = entry.get("match");
        if (standard.equals(NONE)) {
            if (match != null) throw new IllegalArgumentException(where + ": standard none takes no match");
            return new Identifier(NONE, null);
        }
        if (match == null) throw new IllegalArgumentException(where + ": standard " + standard + " needs a match");
        return switch (String.valueOf(match)) {
            case "exact" -> new Identifier(standard, Match.EXACT);
            case "close" -> new Identifier(standard, Match.CLOSE);
            default -> throw new IllegalArgumentException(
                    where + ": match must be exact or close, was '" + match + "'");
        };
    }

    private static String[] sides(String arrowed, String where) {
        var sides = arrowed.split(ARROW, -1);
        if (sides.length != 2 || splitNames(sides[0]).isEmpty() || splitNames(sides[1]).isEmpty()) {
            throw new IllegalArgumentException(where + " must read 'From[, From] -> To[, To]'");
        }
        return sides;
    }

    private static List<String> splitNames(String side) {
        var names = new ArrayList<String>();
        for (var name : side.split(",", -1)) {
            if (!name.isBlank()) names.add(name.trim());
        }
        return names;
    }

    private static void requireDeclared(Map<String, Family> families, String name, String reference) {
        if (!families.containsKey(name)) {
            throw new IllegalArgumentException("reference '" + reference + "' names unknown family '" + name + "'");
        }
    }

    private static String text(Map<?, ?> entry, String key, String where) {
        var value = entry.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException(where + ": missing " + key);
        }
        return String.valueOf(value).trim();
    }

    private static Map<?, ?> map(@Nullable Object value, String where) {
        if (value instanceof Map<?, ?> map) return map;
        throw new IllegalArgumentException("ontology schema: " + where + " must be a mapping");
    }

    private static List<?> list(@Nullable Object value, String where) {
        if (value instanceof List<?> list) return list;
        throw new IllegalArgumentException("ontology schema: " + where + " must be a list");
    }
}
