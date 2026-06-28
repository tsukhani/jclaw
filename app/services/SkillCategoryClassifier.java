package services;

import java.util.List;

/**
 * Assigns each importable-catalog skill to ONE topical category for the Browse
 * Catalog facets. The upstream dump carries no category field, and skills.sh's
 * own topic taxonomy is behind its gated API — so categories are <em>derived</em>
 * here by keyword over the skill's name/repo signals.
 *
 * <h2>How the taxonomy was chosen</h2>
 * The buckets and keyword sets were calibrated against the full ~34k-skill
 * snapshot (token-frequency analysis + iterating on the distribution). Two design
 * choices keep the facets useful:
 * <ul>
 *   <li><b>Domain over meta-label.</b> Almost everything in an agent-skills
 *       catalog "is an agent skill" (the bare word {@code claude} alone matched
 *       ~8k rows — the runtime, not a topic), so that axis doesn't discriminate.
 *       {@code AI & Agents} is therefore placed near the END and {@code claude}
 *       is intentionally NOT a keyword: a "data-analysis agent" lands in Research,
 *       a "react agent" in Web; only domain-less skills fall to AI & Agents.</li>
 *   <li><b>First-match-wins, ordered specific → general.</b> Order is significant
 *       (e.g. a "react testing" skill lands in Testing, listed before Web).</li>
 * </ul>
 * The residual lands in {@link #OTHER} (~23% — expected for a heuristic over
 * free-form names with no description text). 17 buckets + Other; fixed, bounded.
 *
 * <p>Pure + deterministic — classification runs once per skill at catalog load.
 */
public final class SkillCategoryClassifier {

    /** Residual bucket for skills no keyword matches. */
    public static final String OTHER = "Other";
    /** Icon shown for the residual bucket. */
    private static final String OTHER_ICON = "📦";

    public record Category(String name, String icon, List<String> keywords) {}

    // First-match-wins, ordered specific → general (domains first; the AI/agent
    // meta-label and generic tooling mop-up last). Keywords are lowercase
    // substrings matched against "skillId displayName repo".
    private static final List<Category> TAXONOMY = List.of(
            new Category("Git & VCS", "🔀", List.of(
                    "git", "github", "gitlab", "commit", "rebase", "pull request", "pull-request",
                    "changelog", "monorepo")),
            new Category("Testing & QA", "🧪", List.of(
                    "test", "qa", "e2e", "playwright", "vitest", "jest", "cypress", "pytest",
                    "coverage", "tdd", "mocha", "selenium")),
            new Category("Security", "🔒", List.of(
                    "security", "secure", "pentest", "vulnerab", "owasp", "exploit", "oauth",
                    "auth", "encrypt", "cve", "secret", "threat", "malware")),
            new Category("DevOps & Cloud", "☁️", List.of(
                    "docker", "kubernetes", "k8s", "terraform", "ansible", "helm", "aws", "azure",
                    "gcp", "cloud", "devops", "ci/cd", "cicd", "deploy", "infra", "dotfiles",
                    "nginx", "serverless", "lambda", "pipeline")),
            new Category("Data & Databases", "🗄️", List.of(
                    "database", "postgres", "mysql", "mongo", "redis", "sqlite", "sql", "etl",
                    "analytics", "warehouse", "dataset", "dbt", "snowflake", "bigquery", "duckdb",
                    "pandas")),
            new Category("Media & Creative", "🎬", List.of(
                    "video", "image", "audio", "ffmpeg", "remotion", "photo", "render", "animation",
                    "music", "media", "thumbnail", "gif", "svg", "comic", "infographic", "art ")),
            new Category("Documents", "📄", List.of(
                    "pdf", "docx", "xlsx", "pptx", "spreadsheet", "powerpoint", "excel", "invoice",
                    "resume", "slide", "epub", "markdown")),
            new Category("Web & Frontend", "🌐", List.of(
                    "react", "vue", "angular", "svelte", "next", "nuxt", "frontend", "front-end",
                    "web", "css", "tailwind", "html", "component", "astro", "remix", "spa", "expo",
                    "react native", "react-native", "ios", "android", "flutter", "vite", "pnpm",
                    "turborepo", "webpack", "npm", "browser", "dom")),
            new Category("Design & UX", "🎨", List.of(
                    "design", "ux", "figma", "visual", "brand", "typography", "palette", "wireframe",
                    "theme", "icon")),
            new Category("Backend & Languages", "🔌", List.of(
                    "api", "backend", "server", "rest", "graphql", "microservice", "laravel", "rails",
                    "django", "flask", "express", "sdk", "python", "rust", "typescript", "javascript",
                    "golang", "java", "dotnet", ".net", "php", "ruby", "kotlin", "swift", "node",
                    "spring")),
            new Category("Research & Analysis", "🔬", List.of(
                    "research", "analysis", "analyst", "analyze", "analyzer", "insight", "summari",
                    "report", "investigat", "scrape", "scraper", "extract")),
            new Category("Engineering & Architecture", "🏗️", List.of(
                    "pattern", "architecture", "refactor", "code review", "best practice", "clean code",
                    "debug", "lint", "solid", "development", "engineer", "spec", "review",
                    "optimization", "performance", "developer", "architect")),
            new Category("Content & Marketing", "✍️", List.of(
                    "writing", "writer", "content", "blog", "copywrit", "seo", "marketing", "social",
                    "campaign", "newsletter", "article", "documentation", "docs", "translat", "comms")),
            new Category("Business & Product", "💼", List.of(
                    "product", "management", "manager", "strategy", "business", "roadmap", "okr",
                    "sales", "crm", "finance", "invoic", "startup", "pitch", "plan")),
            new Category("Productivity", "✅", List.of(
                    "workflow", "productiv", "task", "notes", "automation", "calendar", "planner",
                    "todo", "obsidian", "notion", "life", "email", "slack")),
            new Category("AI & Agents", "🤖", List.of(
                    "mcp", "agent", "llm", "prompt", "rag", "gemini", "openai", "gpt", "anthropic",
                    "chatbot", "embedding", "fine-tune", "langchain", "ollama", "brainstorm",
                    "browser use")),
            new Category("Developer Tools", "🧰", List.of(
                    "cli", "command line", "terminal", "tool", "toolkit", "generator", "builder",
                    "scaffold", "boilerplate", "starter", "template", "snippet", "vscode", "editor",
                    "plugin", "extension", "shell", "script", "setup", "config")));

    private SkillCategoryClassifier() {}

    /** The ordered taxonomy, excluding the implicit {@link #OTHER} bucket. */
    public static List<Category> taxonomy() {
        return TAXONOMY;
    }

    /** Icon for a category name; the Other icon for {@link #OTHER} or unknowns. */
    public static String iconFor(String category) {
        for (var c : TAXONOMY) {
            if (c.name().equals(category)) return c.icon();
        }
        return OTHER_ICON;
    }

    /**
     * Classify a skill by its name/repo signals. Returns the first matching
     * category (taxonomy order) or {@link #OTHER} when nothing matches.
     */
    public static String classify(String skillId, String displayName, String repo) {
        return classifyText(nz(skillId) + " " + nz(displayName) + " " + nz(repo));
    }

    /**
     * Classify by an arbitrary text signal — first matching category (taxonomy
     * order) or {@link #OTHER}. Dynamic sources pass richer text (description +
     * topics) than the static dump's name-only signal, which classifies better.
     */
    public static String classifyText(String text) {
        var t = nz(text).toLowerCase();
        for (var c : TAXONOMY) {
            for (var k : c.keywords()) {
                if (t.contains(k)) return c.name();
            }
        }
        return OTHER;
    }

    private static String nz(String s) {
        return s != null ? s : "";
    }
}
