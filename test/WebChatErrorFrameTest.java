import controllers.ApiChatController;
import llm.LlmProvider;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.LlmErrorTemplates;

/**
 * JCLAW-1133, the Web half: the SSE frame a web reader sees for a failed turn.
 *
 * <p>It used to be {@code "An error occurred: " + error.getMessage()} — the raw throwable text sent
 * straight to the person, which is the one thing JCLAW-60 rules out outright. Nothing caught it:
 * no test exercised the web error event at all, because ApiChatControllerTest deliberately avoids
 * real LLM dispatch. These tests guard the choice directly instead of through a live stream.
 */
class WebChatErrorFrameTest extends UnitTest {

    @Test
    void theFrameNeverCarriesTheThrowablesOwnMessage() {
        var frame = ApiChatController.webErrorFrame(
                new IllegalStateException("NullPointerException at com.acme.Foo.bar(Foo.java:42)"));
        var content = String.valueOf(frame.get("content"));
        assertFalse(content.contains("Foo.java"), "stack detail must not reach the reader: " + content);
        assertFalse(content.contains("NullPointerException"), content);
        assertFalse(content.startsWith("An error occurred:"), "the old raw-message copy: " + content);
    }

    @Test
    void theFrameIsTheErrorTypeTheChatClientDispatchesOn() {
        var frame = ApiChatController.webErrorFrame(new RuntimeException("x"));
        assertEquals("error", frame.get("type"), "useChatStream switches on type === 'error'");
    }

    @Test
    void theFrameCarriesThreePartsRichForTheMarkdownChat() {
        var content = String.valueOf(ApiChatController.webErrorFrame(new RuntimeException("x"))
                .get("content"));
        assertTrue(content.contains("What broke"), content);
        assertTrue(content.contains("How to retry"), content);
        assertTrue(content.contains("**"), "rich: the web chat renders Markdown emphasis: " + content);
    }

    /** A classified provider failure keeps its remedy on the web path too, not just the sinks. */
    @Test
    void aClassifiedProviderFailureNamesTheProviderInTheBubble() {
        var failure = new LlmErrorTemplates.Failure(
                LlmErrorTemplates.Remedy.INVALID_KEY, "openrouter", "gpt-4.1", null, null);
        var content = String.valueOf(ApiChatController.webErrorFrame(
                new LlmProvider.LlmException.ClientError("HTTP 401 from openrouter", failure))
                .get("content"));
        assertTrue(content.contains("openrouter"), content);
        assertFalse(content.contains("HTTP 401"), "the raw status stays in the log: " + content);
    }
}
