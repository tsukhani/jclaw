import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import tools.ChromeFinder;

import java.util.List;

class ChromeFinderTest extends UnitTest {

    private static final String LISTING = """
            e1 a Home -> https://example.com/
            e2 input[email] (label: Email)
            e3 input[email] Enter your email
            e10 button Subscribe""";

    @Test
    void theRefsTheModelNamesComeBackBestFirst() {
        assertEquals(List.of("e2", "e3"), ChromeFinder.pick(_ -> "e2 e3", "Checkout", "my email", LISTING));
    }

    @Test
    void aRefTheListDoesNotHoldIsDropped() {
        assertEquals(List.of("e2"), ChromeFinder.pick(_ -> "e99 e2 e7", "Checkout", "my email", LISTING));
    }

    @Test
    void aRefIsMatchedWholeSoE1DoesNotPickE10() {
        assertEquals(List.of("e10"), ChromeFinder.pick(_ -> "e10", "Checkout", "subscribe", LISTING));
        assertEquals(List.of(), ChromeFinder.pick(_ -> "e100", "Checkout", "subscribe", LISTING));
    }

    @Test
    void aRefRepeatedIsReturnedOnce() {
        assertEquals(List.of("e2", "e3"), ChromeFinder.pick(_ -> "e2, e2, e3", "Checkout", "my email", LISTING));
    }

    @Test
    void atMostThreeRefsAreReturned() {
        assertEquals(List.of("e1", "e2", "e3"), ChromeFinder.pick(_ -> "e1 e2 e3 e10", "Checkout", "anything", LISTING));
    }

    @Test
    void noneMeansNothingFits() {
        assertEquals(List.of(), ChromeFinder.pick(_ -> "NONE", "Checkout", "a teleporter", LISTING));
    }

    @Test
    void aReplyAroundTheRefsStillYieldsThem() {
        assertEquals(List.of("e2"), ChromeFinder.pick(_ -> "The best match is **e2**.", "Checkout", "my email", LISTING));
    }

    @Test
    void aFailedCallReturnsNullSoTheWordMatchAnswers() {
        assertNull(ChromeFinder.pick(_ -> {
            throw new IllegalStateException("provider down");
        }, "Checkout", "my email", LISTING));
        assertNull(ChromeFinder.pick(_ -> null, "Checkout", "my email", LISTING));
    }

    @Test
    void thePromptCarriesTheRequestAndTheListing() {
        var prompt = ChromeFinder.prompt("Checkout", "my email", LISTING);
        assertEquals(true, prompt.startsWith("Web page: Checkout\nRequest: my email\n\nElements:\ne1 a Home"));
    }
}
