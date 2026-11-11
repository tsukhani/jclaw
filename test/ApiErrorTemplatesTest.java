import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.ApiErrorTemplates;
import utils.ApiResponses;
import utils.ErrorTemplates;

import java.util.List;

/**
 * Wording defects the JCLAW-60 rubric scoring found in the API templates: a remedy that promised
 * UI behaviour nothing implements, and limits the operator had to go and look up.
 */
class ApiErrorTemplatesTest extends UnitTest {

    @Test
    void anInvalidRequestPointsAtTheMessageRatherThanAtHighlightingNoPageDoes() {
        var template = ErrorTemplates.forCode(ApiResponses.INVALID_REQUEST);

        assertFalse(template.howToRetry().contains("highlighted"), template.howToRetry());
        assertTrue(template.whatToCheck().contains("message above"), template.whatToCheck());
    }

    @Test
    void anAgentScopeRefusalCoversAnotherAgentsRowAndARouteThatIsTheOperatorsAndMains() {
        // The code answers both since JF-292, and a custom agent holding jclaw_api reads it for both (JF-293).
        var template = ErrorTemplates.forCode(ApiResponses.AGENT_SCOPE);

        assertEquals(List.of(
                "An agent tried to reach another agent's row, or a route that answers only the operator and main.",
                "An agent other than main reaches only its own rows, such as its conversations, tasks and settings. "
                        + "Every other agent's rows, and the instance's setup, status and the calls that spend, are "
                        + "the operator's and main's.",
                "Do it yourself in the admin UI, or ask main."),
                List.of(template.whatBroke(), template.whatToCheck(), String.valueOf(template.howToRetry())));
    }

    @Test
    void aPasswordLengthRefusalNamesTheLimit() {
        var tooShort = ApiErrorTemplates.passwordTooShort(12);
        assertEquals(ApiResponses.PASSWORD_TOO_SHORT, tooShort.code());
        assertTrue(tooShort.whatBroke().contains("12 characters"), tooShort.whatBroke());
        assertTrue(tooShort.howToRetry().contains("at least 12"), tooShort.howToRetry());

        var tooLong = ApiErrorTemplates.passwordTooLong(128);
        assertEquals(ApiResponses.PASSWORD_TOO_LONG, tooLong.code());
        assertTrue(tooLong.whatBroke().contains("128 characters"), tooLong.whatBroke());
        assertTrue(tooLong.howToRetry().contains("at most 128"), tooLong.howToRetry());
    }

    @Test
    void anOversizedBodyNamesItsSizeTheLimitAndTheKeyThatSetsIt() {
        var known = ApiErrorTemplates.payloadTooLarge(2_000_000L, 1_048_576L, "slack.webhook.max-body-bytes");
        assertTrue(known.whatBroke().contains("2,000,000 bytes"), known.whatBroke());
        assertTrue(known.whatBroke().contains("1,048,576-byte limit"), known.whatBroke());
        assertTrue(known.howToRetry().contains("slack.webhook.max-body-bytes"), known.howToRetry());

        // A chunked request declares no length; the limit alone still has to read as a sentence.
        var undeclared = ApiErrorTemplates.payloadTooLarge(null, 1_048_576L, "slack.webhook.max-body-bytes");
        assertFalse(undeclared.whatBroke().contains("null"), undeclared.whatBroke());
        assertTrue(undeclared.whatBroke().contains("1,048,576-byte limit"), undeclared.whatBroke());
    }
}
