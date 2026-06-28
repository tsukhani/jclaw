package services.catalog;

/**
 * One catalog entry, normalized across catalog sources so the UI renders any
 * source uniformly. {@code provider} is the originating catalog's id (for the
 * source badge + import routing); {@code url} is the canonical web page for the
 * skill (a GitHub URL for the static dump, a clawhub page for the dynamic
 * registry). {@code owner}/{@code repo} are GitHub-specific and may be blank for
 * non-GitHub sources. {@code category} is the derived topical bucket
 * ({@link services.SkillCategoryClassifier}).
 */
public record CatalogSkill(String skillId, String displayName, String source,
                           String owner, String repo, String url, long installs,
                           String category, String provider) {}
