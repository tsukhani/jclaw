import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.PageData;
import utils.WebExtraction;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Structured extraction (JCLAW-1271): CSS-selector fields and the metadata a page states about itself. */
class PageDataTest extends UnitTest {

    private static final String PAGE = """
            <html lang="en-GB"><head>
              <title>Blue Kettle</title>
              <meta name="description" content="A kettle that is blue.">
              <link rel="canonical" href="/products/kettle">
              <meta property="og:title" content="Blue Kettle (OG)">
              <meta name="twitter:card" content="summary">
              <script type="application/ld+json">{"@type": "Product", "name": "Blue Kettle", "offers": {"price": "29.99"}}</script>
              <script type="application/ld+json">{ this is not json </script>
            </head><body>
              <h1>Blue Kettle</h1>
              <span class="price"> £29.99 </span>
              <span class="price">£24.99</span>
              <a class="next" href="page-2">Next</a>
              <a data-x="a@b" href="/x">odd</a>
            </body></html>""";

    private static WebExtraction.FetchResult html(String body) {
        return new WebExtraction.FetchResult(body.getBytes(StandardCharsets.UTF_8),
                "text/html; charset=utf-8", "https://shop.test/products/list");
    }

    private static Map<String, PageData.Field> fields(String... nameThenSpec) {
        var out = new LinkedHashMap<String, PageData.Field>();
        for (int i = 0; i < nameThenSpec.length; i += 2) {
            out.put(nameThenSpec[i], PageData.Field.parse(nameThenSpec[i + 1]));
        }
        return out;
    }

    @Test
    void eachFieldReturnsItsMatchesInDocumentOrder() {
        var out = PageData.extract(html(PAGE), fields("price", ".price", "heading", "h1")).json();

        assertEquals("[\"£29.99\",\"£24.99\"]", out.get("price").toString());
        assertEquals("[\"Blue Kettle\"]", out.get("heading").toString());
    }

    @Test
    void anAttributeIsReadAndAUrlComesBackAbsolute() {
        var out = PageData.extract(html(PAGE), fields("next", "a.next@href")).json();

        assertEquals("https://shop.test/products/page-2", out.getAsJsonArray("next").get(0).getAsString());
    }

    @Test
    void anAtSignInsideTheSelectorIsNotAnAttribute() {
        var field = PageData.Field.parse("a[data-x='a@b']");

        assertEquals("a[data-x='a@b']", field.selector());
        assertNull(field.attribute());
        assertEquals("[\"odd\"]", PageData.extract(html(PAGE), Map.of("odd", field)).json().get("odd").toString());
    }

    @Test
    void aSelectorThatMatchesNothingIsAnEmptyFieldNotAnError() {
        var extracted = PageData.extract(html(PAGE), fields("missing", ".no-such-class"));

        assertEquals("[]", extracted.json().get("missing").toString());
        assertFalse(extracted.truncated());
    }

    @Test
    void aSelectorThatDoesNotParseIsNamedWithItsField() {
        var message = PageData.invalidSelector(fields("ok", "h1", "broken", "div[["));

        assertNotNull(message);
        assertTrue(message.contains("broken"), message);
        assertNotNull(PageData.invalidSelector(fields("blank", "  ")));
        assertNull(PageData.invalidSelector(fields("ok", "h1", "also", "a.next@href")));
    }

    @Test
    void matchesPastTheCapAreDroppedAndReported() {
        var many = new StringBuilder("<html><body>");
        for (int i = 0; i < PageData.MAX_MATCHES_PER_FIELD + 50; i++) many.append("<i>").append(i).append("</i>");
        var extracted = PageData.extract(html(many + "</body></html>"), fields("n", "i"));

        assertEquals(PageData.MAX_MATCHES_PER_FIELD, extracted.json().getAsJsonArray("n").size());
        assertTrue(extracted.truncated());
    }

    @Test
    void aLongValueIsClippedAndReported() {
        var extracted = PageData.extract(html("<p>" + "x".repeat(3_000) + "</p>"), fields("p", "p"));

        var value = extracted.json().getAsJsonArray("p").get(0).getAsString();
        assertEquals(PageData.MAX_VALUE_CHARS + 1, value.length());
        assertTrue(extracted.truncated());
    }

    @Test
    void metadataCarriesWhatThePageStatesAboutItself() {
        var meta = PageData.metadata(html(PAGE)).json();

        assertEquals("Blue Kettle", meta.get("title").getAsString());
        assertEquals("A kettle that is blue.", meta.get("description").getAsString());
        assertEquals("https://shop.test/products/kettle", meta.get("canonical").getAsString());
        assertEquals("en-GB", meta.get("language").getAsString());
        assertEquals("Blue Kettle (OG)", meta.getAsJsonObject("openGraph").get("og:title").getAsString());
        assertEquals("summary", meta.getAsJsonObject("twitter").get("twitter:card").getAsString());
        // The malformed block is skipped rather than failing the page.
        var jsonLd = meta.getAsJsonArray("jsonLd");
        assertEquals(1, jsonLd.size());
        assertEquals("29.99", jsonLd.get(0).getAsJsonObject().getAsJsonObject("offers").get("price").getAsString());
    }

    @Test
    void aDocumentWithNoDomYieldsEmptyFieldsAndNoMetadata() {
        var pdf = new WebExtraction.FetchResult("%PDF-1.7 ...".getBytes(StandardCharsets.UTF_8),
                "application/pdf", "https://shop.test/manual.pdf");

        assertEquals("[]", PageData.extract(pdf, fields("price", ".price")).json().get("price").toString());
        assertEquals("{}", PageData.metadata(pdf).json().toString());
    }
}
