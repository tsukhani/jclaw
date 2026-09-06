package services.catalog;

import org.jspecify.annotations.Nullable;

/**
 * One catalog entry, normalized across catalog sources so the UI renders any
 * source uniformly. {@code provider} is the originating catalog's id (for the
 * source badge + import routing); {@code url} is the canonical web page for the
 * skill (a GitHub URL for the static dump, a clawhub page for the dynamic
 * registry). {@code owner}/{@code repo} are GitHub-specific and may be blank for
 * non-GitHub sources. {@code category} is the derived topical bucket
 * ({@link services.SkillCategoryClassifier}).
 */
public record CatalogSkill(@Nullable String skillId, @Nullable String displayName, @Nullable String source,
                           @Nullable String owner, @Nullable String repo, @Nullable String url, long installs,
                           String category, String provider) {}
