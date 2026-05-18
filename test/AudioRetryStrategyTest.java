import agents.AudioRetryStrategy;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.lang.reflect.Method;

/**
 * JCLAW-302: pin the timeout branch of
 * {@link AudioRetryStrategy#shortErrorTag} that the JCLAW-299 AgentRunner
 * extraction left uncovered.
 *
 * <p>The strategy tags audio-passthrough error outcomes with one of four
 * suffixes — {@code :4xx}, {@code :5xx}, {@code :timeout}, or none — so
 * the {@code AUDIO_PASSTHROUGH_OUTCOME} field-data set can be sliced by
 * failure category without grepping prose. This test pins the timeout
 * branch specifically (two trigger keywords: "timeout" and "timed out")
 * plus the defensive guarantee that an HTTP 4xx wrapping a timeout
 * substring still tags as {@code :4xx} — the if-else order in the
 * production method matters because some providers wrap a timeout
 * inside an HTTP envelope.
 *
 * <p>Also pins that {@link AudioRetryStrategy#isAudioFormatRejection}
 * returns false for a bare socket timeout — that's the contract the
 * AgentRunner audio-retry flow depends on so a timeout falls through to
 * the generic error path rather than the format-rejection retry.
 *
 * <p>{@code shortErrorTag} is package-private. Tests in this codebase
 * live in the default package per Play 1.x's test-runner convention
 * (see build.gradle.kts S1220 suppression); reflection is the
 * lightest-weight seam that respects that convention.
 */
class AudioRetryStrategyTest extends UnitTest {

    private static Method shortErrorTag() throws Exception {
        var m = AudioRetryStrategy.class.getDeclaredMethod("shortErrorTag", Throwable.class);
        m.setAccessible(true);
        return m;
    }

    @Test
    void timedOutPhraseProducesTimeoutTag() throws Exception {
        var tag = (String) shortErrorTag().invoke(
                null, new java.net.SocketTimeoutException("Read timed out"));
        assertEquals("SocketTimeoutException:timeout", tag);
    }

    @Test
    void timeoutKeywordProducesTimeoutTag() throws Exception {
        var tag = (String) shortErrorTag().invoke(
                null, new RuntimeException("Request timeout after 30s"));
        assertEquals("RuntimeException:timeout", tag);
    }

    @Test
    void http4xxWrappingATimeoutMessageStillTagsAs4xx() throws Exception {
        // The if-else order in shortErrorTag puts the HTTP-class check
        // before the timeout substring check on purpose: a provider that
        // wraps a timeout inside an HTTP 408 ("Request Timeout") response
        // is still semantically an HTTP failure for the field-data set,
        // not a transport-level timeout. Pin that ordering so a future
        // reorder doesn't silently change AUDIO_PASSTHROUGH_OUTCOME tags.
        var tag = (String) shortErrorTag().invoke(
                null, new RuntimeException("HTTP 408 Request Timeout"));
        assertEquals("RuntimeException:4xx", tag);
    }

    @Test
    void timeoutIsNotMisclassifiedAsAudioFormatRejection() {
        // The AgentRunner audio-retry flow keys off isAudioFormatRejection
        // to decide whether to retry with text. A socket timeout must NOT
        // hit that branch — it falls through to the generic error path
        // instead. Confirms the "http 4" prefix-gate inside
        // isAudioFormatRejection works as advertised.
        var timeout = new java.net.SocketTimeoutException("connect timed out");
        assertFalse(AudioRetryStrategy.isAudioFormatRejection(timeout),
                "socket timeout must not be classified as an audio-format rejection");
    }
}
