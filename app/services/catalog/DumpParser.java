package services.catalog;

import java.util.List;

/**
 * Parses a static dump's raw JSON into normalized {@link CatalogSkill} rows for
 * a {@link StaticDumpCatalog}. One parser per dump format — the format-specific
 * knowledge a static catalog needs to ingest its source (e.g.
 * {@link MastraDumpParser} for the skills.sh / mastra-ai snapshot). Stamps each
 * row with {@code provider} and the derived category.
 */
public interface DumpParser {
    ParsedDump parse(String raw, String provider);

    /** The parsed dump: its skills plus an optional snapshot timestamp. */
    record ParsedDump(List<CatalogSkill> skills, String scrapedAt) {}
}
