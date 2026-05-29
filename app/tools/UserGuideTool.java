package tools;

import agents.ToolRegistry;
import com.google.gson.JsonParser;
import models.Agent;
import play.Play;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Answers chat questions about JClaw's own functionality by searching the
 * bundled user guide (the markdown under {@code docs/user-guide/}).
 *
 * <p>The user guide ships inside the dist/bundle (it is intentionally NOT
 * excluded by {@code .distignore}, unlike {@code docs/architecture/}), so at
 * runtime the pages live at {@code Play.applicationPath/docs/user-guide/} in
 * both dev and a {@code play dist} deployment.
 *
 * <p>Retrieval is deliberately in-memory keyword scoring rather than Lucene:
 * the corpus is a handful of small static files, so the indexing/scale value
 * of the Lucene backend doesn't apply. Each call reads the pages fresh (cheap,
 * and always reflects edits in dev), splits them into heading-delimited
 * sections, ranks sections by how many distinct query terms they cover
 * (heading matches weighted higher), and returns the top few with a citation.
 */
public class UserGuideTool implements ToolRegistry.Tool {

    private static final String ARG_QUERY = "query";

    /** How many sections to return, and how much of each (token budget guard). */
    private static final int MAX_SECTIONS = 3;
    private static final int MAX_SECTION_CHARS = 1800;

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");
    private static final Pattern WORD = Pattern.compile("[a-z0-9]+");

    /** Non-discriminating tokens dropped before scoring. "jclaw" is included
     *  because it appears in nearly every question about JClaw and so carries
     *  no signal for ranking guide sections against each other. */
    private static final Set<String> STOPWORDS = Set.of(
            "the", "a", "an", "is", "are", "do", "does", "how", "what", "why", "when",
            "where", "which", "who", "to", "of", "in", "on", "for", "and", "or", "i",
            "can", "my", "me", "with", "it", "this", "that", "you", "your", "be", "as",
            "if", "from", "jclaw");

    /** Non-null only in tests/overrides; production reads the bundled guide. */
    private final Path docsDirOverride;

    /** Production constructor — reads {@code Play.applicationPath/docs/user-guide}. */
    public UserGuideTool() {
        this.docsDirOverride = null;
    }

    /** Override constructor (used by tests) pointing at an explicit guide directory. */
    public UserGuideTool(Path docsDir) {
        this.docsDirOverride = docsDir;
    }

    private Path docsDir() {
        if (docsDirOverride != null) {
            return docsDirOverride;
        }
        return Path.of(Play.applicationPath.getAbsolutePath(), "docs", "user-guide");
    }

    @Override
    public String name() {
        return "jclaw_docs";
    }

    @Override
    public String category() {
        return "Utilities";
    }

    @Override
    public String icon() {
        return "book";
    }

    /** Read-only retrieval, no shared mutable state — safe to run concurrently. */
    @Override
    public boolean parallelSafe() {
        return true;
    }

    @Override
    public String shortDescription() {
        return "Answer questions about JClaw's features and usage from the official user guide.";
    }

    @Override
    public String description() {
        return """
                Search the official JClaw user guide to answer questions about how JClaw works — \
                its features, settings, agents, chat, tasks, subagents, conversations and channels, \
                skills/tools/MCP, reminders, and the logs/dashboard. Pass the user's question as \
                'query'; the tool returns the most relevant user-guide sections, each with its \
                source page and an in-app deep link. Use this whenever the user asks how to do \
                something in JClaw or how a JClaw feature behaves, then answer concisely from the \
                returned sections and cite each source as a Markdown link using the "Link" path the \
                tool returns (e.g. /guide#tasks) — never invent a citation URL, and do not paste \
                whole sections verbatim.""";
    }

    @Override
    public String summary() {
        return "Search the JClaw user guide to answer questions about JClaw's features and usage. Arg: query (the question).";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                SchemaKeys.TYPE, SchemaKeys.OBJECT,
                SchemaKeys.PROPERTIES, Map.of(
                        ARG_QUERY, Map.of(
                                SchemaKeys.TYPE, SchemaKeys.STRING,
                                SchemaKeys.DESCRIPTION,
                                "The user's question about JClaw functionality, in natural language.")),
                SchemaKeys.REQUIRED, List.of(ARG_QUERY));
    }

    @Override
    public String execute(String argsJson, Agent agent) {
        var args = JsonParser.parseString(argsJson).getAsJsonObject();
        if (!args.has(ARG_QUERY) || args.get(ARG_QUERY).isJsonNull()) {
            return "Error: 'query' is required.";
        }
        var query = args.get(ARG_QUERY).getAsString();
        if (query.isBlank()) {
            return "Error: 'query' must not be blank.";
        }

        List<Section> sections;
        try {
            sections = loadSections();
        } catch (IOException e) {
            return "Error: could not read the JClaw user guide: " + e.getMessage();
        }
        if (sections.isEmpty()) {
            return "The JClaw user guide is not available (no pages found under docs/user-guide).";
        }

        var terms = new ArrayList<>(new HashSet<>(tokenize(query)));
        if (terms.isEmpty()) {
            return "Could not extract searchable terms from the query. Try rephrasing the question.";
        }

        var ranked = sections.stream()
                .map(s -> Map.entry(s, score(s, terms)))
                .filter(e -> e.getValue() > 0)
                .sorted((x, y) -> Double.compare(y.getValue(), x.getValue()))
                .limit(MAX_SECTIONS)
                .map(Map.Entry::getKey)
                .toList();

        if (ranked.isEmpty()) {
            return "No relevant section found in the JClaw user guide for: \"%s\". The guide may not cover this topic."
                    .formatted(query);
        }

        var sb = new StringBuilder();
        sb.append("Top JClaw user-guide sections for \"").append(query).append("\":\n");
        for (var s : ranked) {
            sb.append("\n--- User Guide → ").append(s.pageTitle());
            if (!s.heading().equals(s.pageTitle()) && !"(intro)".equals(s.heading())) {
                sb.append(" → ").append(s.heading());
            }
            sb.append(" ---\n");
            sb.append("Link (cite this exact path): ").append(s.link()).append('\n');
            sb.append(truncate(s.text(), MAX_SECTION_CHARS)).append('\n');
        }
        sb.append("\n(Answer the user's question concisely from these sections. When you cite a source, "
                + "use the exact \"Link\" path shown for that section as a Markdown link — e.g. "
                + "[Tasks](/guide#tasks) — never invent a URL or link to an external host. "
                + "Do not paste sections verbatim.)");
        return sb.toString();
    }

    // ---------------------------------------------------------------- retrieval

    /**
     * A heading-delimited slice of a user-guide page.
     *
     * @param page      source filename, e.g. {@code tasks.md}
     * @param pageTitle the page's display title (its leading heading)
     * @param link      in-app deep link to the page's guide section, e.g.
     *                  {@code /guide#tasks} — cite this, never invent one
     * @param heading   the section's own heading within the page
     * @param text      the section body
     */
    private record Section(String page, String pageTitle, String link, String heading, String text) {}

    private List<Section> loadSections() throws IOException {
        var dir = docsDir();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        var out = new ArrayList<Section>();
        try (var stream = Files.list(dir)) {
            var pages = stream
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .toList();
            for (var page : pages) {
                out.addAll(splitIntoSections(page.getFileName().toString(), Files.readString(page)));
            }
        }
        return out;
    }

    private List<Section> splitIntoSections(String page, String content) {
        var sections = new ArrayList<Section>();
        var link = pageLink(page);
        var pageTitle = pageTitle(page, content);
        String heading = null;
        var body = new StringBuilder();
        for (var line : content.split("\n", -1)) {
            var m = HEADING.matcher(line);
            if (m.matches()) {
                flush(sections, page, pageTitle, link, heading, body);
                heading = m.group(2).trim();
                body.setLength(0);
            } else {
                body.append(line).append('\n');
            }
        }
        flush(sections, page, pageTitle, link, heading, body);
        return sections;
    }

    private void flush(List<Section> out, String page, String pageTitle, String link,
                       String heading, StringBuilder body) {
        var text = body.toString().strip();
        if (heading == null && text.isEmpty()) {
            return;
        }
        out.add(new Section(page, pageTitle, link, heading == null ? "(intro)" : heading, text));
    }

    /**
     * In-app guide deep link for a page. The frontend registers each guide
     * section under an {@code id} equal to its source filename stem (see
     * {@code frontend/components/guide/sections.ts}), and {@code /guide#<id>}
     * scrolls straight to that section. Returning this exact path in the tool
     * output stops the model from inventing citation URLs — it previously
     * emitted external links like {@code https://jclaw-docs}.
     */
    private static String pageLink(String page) {
        return "/guide#" + stem(page);
    }

    private static String stem(String page) {
        return page.endsWith(".md") ? page.substring(0, page.length() - 3) : page;
    }

    /** Display title for a page: its leading heading, or the humanized stem. */
    private static String pageTitle(String page, String content) {
        for (var line : content.split("\n", -1)) {
            var m = HEADING.matcher(line);
            if (m.matches()) {
                return m.group(2).trim();
            }
        }
        return stem(page).replace('-', ' ');
    }

    private static List<String> tokenize(String s) {
        var out = new ArrayList<String>();
        var m = WORD.matcher(s.toLowerCase());
        while (m.find()) {
            var t = m.group();
            if (t.length() >= 2 && !STOPWORDS.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * Coverage-weighted keyword score: a section that contains more of the
     * query's distinct terms outranks one that merely repeats a single term,
     * and a term appearing in the heading counts for more than in the body.
     */
    private double score(Section s, List<String> distinctTerms) {
        var headingLower = s.heading().toLowerCase();
        var bodyLower = s.text().toLowerCase();
        double score = 0;
        int covered = 0;
        for (var term : distinctTerms) {
            int bodyHits = countOccurrences(bodyLower, term);
            boolean inHeading = headingLower.contains(term);
            if (bodyHits > 0 || inHeading) {
                covered++;
            }
            score += Math.min(bodyHits, 5);   // cap a single term's body contribution
            if (inHeading) {
                score += 5;                    // heading match weighted higher
            }
        }
        return score + covered * 10.0;         // prefer broader query coverage
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "\n… (section truncated)";
    }
}
