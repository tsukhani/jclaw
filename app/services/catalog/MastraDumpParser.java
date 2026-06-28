package services.catalog;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import services.SkillCategoryClassifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the skills.sh / mastra-ai snapshot (`scraped-skills.json`): a top-level
 * {@code {scrapedAt, skills:[{slug-ish fields}]}} with each skill carrying
 * {@code skillId, displayName, source(owner/repo), owner, repo, githubUrl,
 * installs}. Maps to {@link CatalogSkill} with the GitHub URL as the canonical
 * {@code url} and a category derived from the name/repo signals.
 */
public final class MastraDumpParser implements DumpParser {

    @Override
    public ParsedDump parse(String raw, String provider) {
        var root = JsonParser.parseString(raw).getAsJsonObject();
        var when = str(root, "scrapedAt");
        var arr = root.getAsJsonArray("skills");
        var list = new ArrayList<CatalogSkill>(arr != null ? arr.size() : 0);
        if (arr != null) {
            for (var el : arr) {
                var o = el.getAsJsonObject();
                var skillId = str(o, "skillId");
                var displayName = str(o, "displayName");
                var repo = str(o, "repo");
                list.add(new CatalogSkill(
                        skillId, displayName, str(o, "source"),
                        str(o, "owner"), repo, str(o, "githubUrl"),
                        asLong(o, "installs"),
                        SkillCategoryClassifier.classify(skillId, displayName, repo),
                        provider));
            }
        }
        return new ParsedDump(List.copyOf(list), when);
    }

    private static String str(JsonObject o, String key) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    private static long asLong(JsonObject o, String key) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsLong() : 0L;
    }
}
