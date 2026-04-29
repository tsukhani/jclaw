import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.EmbeddingModelFilter;

/**
 * Coverage for the JCLAW-183 Tier 3 ID heuristic. Pins the exact id
 * fixtures named in the ticket's acceptance criteria so a future regex
 * tweak can't silently regress them.
 */
public class EmbeddingModelFilterTest extends UnitTest {

    @Test
    public void filtersOpenAiTextEmbeddingPrefix() {
        assertTrue(EmbeddingModelFilter.isLikelyNonChat("text-embedding-nomic-embed-text-v1.5"));
        assertTrue(EmbeddingModelFilter.isLikelyNonChat("openai/text-embedding-3-large"));
        assertTrue(EmbeddingModelFilter.isLikelyNonChat("openai/text-embedding-ada-002"));
    }

    @Test
    public void filtersHuggingFaceEmbeddingFamilies() {
        assertTrue(EmbeddingModelFilter.isLikelyNonChat("BAAI/bge-m3"));
        assertTrue(EmbeddingModelFilter.isLikelyNonChat("BAAI/bge-large-en-v1.5"));
        assertTrue(EmbeddingModelFilter.isLikelyNonChat("intfloat/e5-large-v2"));
        assertTrue(EmbeddingModelFilter.isLikelyNonChat("intfloat/e5-mistral-7b-instruct"));
        assertTrue(EmbeddingModelFilter.isLikelyNonChat("jinaai/jina-embeddings-v3"));
        assertTrue(EmbeddingModelFilter.isLikelyNonChat("nomic-ai/nomic-embed-text-v1.5"));
    }

    @Test
    public void filtersAudioAndImageGenerationModels() {
        // OpenAI hosts these alongside chat models in /v1/models — they would
        // otherwise show up in the discover panel as bind-able for chat
        // and fail at the first request.
        assertTrue(EmbeddingModelFilter.isLikelyNonChat("whisper-1"));
        assertTrue(EmbeddingModelFilter.isLikelyNonChat("dall-e-3"));
        assertTrue(EmbeddingModelFilter.isLikelyNonChat("tts-1"));
        assertTrue(EmbeddingModelFilter.isLikelyNonChat("tts-1-hd"));
    }

    @Test
    public void keepsChatModels() {
        assertFalse(EmbeddingModelFilter.isLikelyNonChat("openai/gpt-oss-20b"));
        assertFalse(EmbeddingModelFilter.isLikelyNonChat("kimi-k2.5"));
        assertFalse(EmbeddingModelFilter.isLikelyNonChat("google/gemma-4-e4b"));
        assertFalse(EmbeddingModelFilter.isLikelyNonChat("zai-org/glm-4.7-flash"));
        assertFalse(EmbeddingModelFilter.isLikelyNonChat("llama3.1"));
        assertFalse(EmbeddingModelFilter.isLikelyNonChat("anthropic/claude-sonnet-4-6"));
        assertFalse(EmbeddingModelFilter.isLikelyNonChat("openai/gpt-4.1"));
        assertFalse(EmbeddingModelFilter.isLikelyNonChat("deepseek/deepseek-v3.2"));
    }

    @Test
    public void caseInsensitiveMatching() {
        // Provider casing is inconsistent; the filter must not be fooled by
        // upper-case prefixes or mixed casing on family names.
        assertTrue(EmbeddingModelFilter.isLikelyNonChat("OpenAI/Text-Embedding-3-Large"));
        assertTrue(EmbeddingModelFilter.isLikelyNonChat("BAAI/BGE-M3"));
        assertTrue(EmbeddingModelFilter.isLikelyNonChat("WHISPER-1"));
    }

    @Test
    public void handlesNullAndBlankInputs() {
        assertFalse(EmbeddingModelFilter.isLikelyNonChat(null));
        assertFalse(EmbeddingModelFilter.isLikelyNonChat(""));
        assertFalse(EmbeddingModelFilter.isLikelyNonChat("   "));
    }

    @Test
    public void doesNotFalsePositiveOnEmbeddedSubstring() {
        // A chat model id that contains the substring "embed" but not at a
        // word boundary should NOT be filtered. Crafted to verify the
        // boundary anchoring in the regex.
        assertFalse(EmbeddingModelFilter.isLikelyNonChat("acme/membership-router-7b"));
    }
}
