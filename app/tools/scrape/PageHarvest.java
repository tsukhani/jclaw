package tools.scrape;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import utils.GsonHolder;
import utils.WebExtraction;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * What a crawl keeps of a page once it is read: where it leads, and which translations of itself it
 * declares. The frontier is built from these, not from the page body.
 *
 * <p>A background job stores one with each page it reads (JCLAW-1272), so a job resumed after a pause
 * or a restart rebuilds its frontier by replaying them rather than fetching those pages again.
 *
 * @param markdown whether the page came back as Markdown, which carries no declared translations
 */
public record PageHarvest(String finalUrl, List<URI> links, Map<String, URI> alternates, boolean markdown) {

    /** @param keep the links worth keeping — those outside the crawl's scope are dropped here, before storage */
    public static PageHarvest of(WebExtraction.FetchResult fetched, Predicate<URI> keep) {
        var links = WebExtraction.links(fetched).stream().filter(keep).toList();
        return new PageHarvest(fetched.finalUrl(), links, WebExtraction.alternates(fetched),
                WebExtraction.isMarkdown(fetched.contentType()));
    }

    public String toJson() {
        var json = new JsonObject();
        json.addProperty("finalUrl", finalUrl);
        var linkArray = new JsonArray();
        links.forEach(link -> linkArray.add(link.toString()));
        json.add("links", linkArray);
        var alternateObject = new JsonObject();
        alternates.forEach((language, uri) -> alternateObject.addProperty(language, uri.toString()));
        json.add("alternates", alternateObject);
        json.addProperty("markdown", markdown);
        return GsonHolder.GSON.toJson(json);
    }

    /** Reads {@link #toJson()} back. Document order is kept, since the crawl breaks ties by it. */
    public static PageHarvest fromJson(String stored) {
        var json = JsonParser.parseString(stored).getAsJsonObject();
        var links = new ArrayList<URI>();
        json.getAsJsonArray("links").forEach(link -> links.add(URI.create(link.getAsString())));
        var alternates = new LinkedHashMap<String, URI>();
        json.getAsJsonObject("alternates").entrySet()
                .forEach(entry -> alternates.put(entry.getKey(), URI.create(entry.getValue().getAsString())));
        return new PageHarvest(json.get("finalUrl").getAsString(), List.copyOf(links),
                Collections.unmodifiableMap(alternates), json.get("markdown").getAsBoolean());
    }
}
