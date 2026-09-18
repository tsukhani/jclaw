package llm;

import llm.LlmFailureClassifier.CallSite;
import llm.LlmTypes.ChatCompletionChunk;
import llm.LlmTypes.ChatRequest;
import llm.LlmTypes.ChatResponse;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.util.function.Consumer;

/**
 * One wire protocol a chat request can travel by (JCLAW-1158). {@link LlmProvider} speaks
 * OpenAI-compatible chat completions over SSE; a provider whose native API reports what that
 * schema cannot carry — Ollama's per-request timings — supplies a second implementation and
 * picks between them per request in {@link LlmProvider#wireFor}.
 *
 * <p>Everything past the wire is shared. A wire yields the same {@link ChatResponse} and
 * {@link ChatCompletionChunk} the OpenAI path does, so the tool-call loop, the stream
 * accumulator and the usage folding never learn which protocol a round used.
 */
public interface ChatWire {

    /** Short name for the event log: {@code openai-compat}, {@code ollama-native}. */
    String name();

    /** The absolute endpoint a request on this wire is posted to. */
    URI uri();

    String serialize(ChatRequest request);

    /**
     * Parse a non-streaming 200 body. {@code channel} is the turn's origin channel, for a
     * wire that records per-request histograms off the response.
     */
    ChatResponse parseResponse(String body, @Nullable String channel);

    /**
     * One streaming attempt, complete or failed by the time it returns. Every event reaches
     * {@code onChunk} as an OpenAI-shaped chunk; {@code publishCancel} receives the abort for
     * the in-flight call as soon as there is one (JCLAW-1183).
     */
    @SuppressWarnings("java:S107") // the streaming callback surface, the cancel handle and the call's identity
    void stream(String json, CallSite site,
                Consumer<ChatCompletionChunk> onChunk, Runnable onComplete, Consumer<Throwable> onError,
                Consumer<Runnable> publishCancel, @Nullable String channel);

    /**
     * Whether {@code failure} says this wire's endpoint is not there at all, so the request
     * should travel the OpenAI-compatible wire instead — as opposed to a failure the request
     * would meet on any wire. The OpenAI wire has nothing to fall back to and answers false.
     */
    default boolean endpointAbsent(Throwable failure) {
        return false;
    }
}
