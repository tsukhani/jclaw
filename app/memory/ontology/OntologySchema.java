package memory.ontology;

import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import play.Play;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * The parsed seed ontology document ({@code conf/ontology/seed-schema.yaml}): record families,
 * structural references, term types and relation types, each with its standard identifier.
 * Types are data — adding one is an edit to the document, never to this class.
 */
public record OntologySchema(
        int version,
        SortedMap<String, Family> families,
        List<String> references,
        SortedMap<String, TermType> termTypes,
        SortedMap<String, RelationType> relations) {

    public static final String SEED_PATH = "conf/ontology/seed-schema.yaml";
    public static final String NONE = "none";
    public static final String SAME = "same";
    public static final String ANY = "Any";
    private static final String ARROW = "->";

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

    public record TermType(String covers, Identifier identifier) {}

    public record RelationType(String kind, Identifier identifier, List<String> endpoints) {
        public RelationType {
            endpoints = List.copyOf(endpoints);
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
        if (!(doc.get("version") instanceof Integer version)) {
            throw new IllegalArgumentException("ontology schema: version must be an integer");
        }

        var families = new TreeMap<String, Family>();
        map(doc.get("families"), "families").forEach((name, value) -> {
            var where = "family " + name;
            var entry = map(value, where);
            families.put(
                    String.valueOf(name),
                    new Family(text(entry, "meaning", where), text(entry, "must_link", where), identifier(entry, where)));
        });

        var references = new ArrayList<String>();
        for (var value : list(doc.get("references"), "references")) {
            var reference = String.valueOf(value);
            var sides = sides(reference, "reference '" + reference + "'");
            for (var name : splitNames(sides[0])) requireDeclared(families, name, reference);
            for (var name : splitNames(sides[1])) {
                if (!name.equals(ANY)) requireDeclared(families, name, reference);
            }
            references.add(reference);
        }

        var termTypes = new TreeMap<String, TermType>();
        map(doc.get("term_types"), "term_types").forEach((name, value) -> {
            var where = "term type " + name;
            var entry = map(value, where);
            termTypes.put(String.valueOf(name), new TermType(text(entry, "covers", where), identifier(entry, where)));
        });

        var relations = new TreeMap<String, RelationType>();
        map(doc.get("relations"), "relations").forEach((name, value) -> {
            var where = "relation " + name;
            var entry = map(value, where);
            var endpoints = new ArrayList<String>();
            for (var endpointValue : list(entry.get("endpoints"), where + " endpoints")) {
                var endpoint = String.valueOf(endpointValue).trim();
                if (!endpoint.equals(SAME)) {
                    var sides = sides(endpoint, where + " endpoint '" + endpoint + "'");
                    for (var side : sides) {
                        for (var type : splitNames(side)) {
                            if (!termTypes.containsKey(type)) {
                                throw new IllegalArgumentException(
                                        where + ": endpoint '" + endpoint + "' names unknown term type '" + type + "'");
                            }
                        }
                    }
                }
                endpoints.add(endpoint);
            }
            if (endpoints.isEmpty()) throw new IllegalArgumentException(where + ": endpoints is empty");
            relations.put(
                    String.valueOf(name), new RelationType(text(entry, "kind", where), identifier(entry, where), endpoints));
        });

        return new OntologySchema(version, families, references, termTypes, relations);
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
        diffMap(lines, "term type", a.termTypes, b.termTypes, (x, y) -> {
            var changes = new ArrayList<String>();
            change(changes, "covers", x.covers(), y.covers());
            changeIdentifier(changes, x.identifier(), y.identifier());
            return changes;
        }, t -> t.identifier().toString());
        diffMap(lines, "relation", a.relations, b.relations, (x, y) -> {
            var changes = new ArrayList<String>();
            change(changes, "kind", x.kind(), y.kind());
            changeIdentifier(changes, x.identifier(), y.identifier());
            change(changes, "endpoints", x.endpoints().toString(), y.endpoints().toString());
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
