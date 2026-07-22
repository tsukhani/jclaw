import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.tts.TtsText;

/**
 * Markdown-to-speakable reduction for TTS (JCLAW-791): tables, code, links,
 * bullets, and emphasis markers must not reach the engine as literal syntax.
 */
class TtsTextTest extends UnitTest {

    @Test
    void nullAndBlankYieldEmpty() {
        assertEquals("", TtsText.toSpeakable(null));
        assertEquals("", TtsText.toSpeakable(""));
        assertEquals("", TtsText.toSpeakable("   "));
    }

    @Test
    void plainProseIsUnchanged() {
        assertEquals("Hello there, how are you?", TtsText.toSpeakable("Hello there, how are you?"));
    }

    @Test
    void stripsEmphasisAndHeadings() {
        assertEquals("Weather today", TtsText.toSpeakable("## **Weather** _today_"));
    }

    @Test
    void linksBecomeTheirText() {
        assertEquals("see the docs here", TtsText.toSpeakable("see the [docs](https://x.y/z) here"));
    }

    @Test
    void dropsInlineCodeAndFences() {
        assertEquals("run x now", TtsText.toSpeakable("run `x` now"));
        var fenced = TtsText.toSpeakable("before ```\ncode\n``` after");
        assertTrue(fenced.contains("before"), fenced);
        assertFalse(fenced.contains("code"), fenced);
    }

    @Test
    void tableBecomesSpeakableWithoutSyntax() {
        var md = "Forecast:\n| | |\n|:---|:---|\n| **High** | 32C |\n| **Low** | 24C |\nStay dry!";
        var out = TtsText.toSpeakable(md);
        assertFalse(out.contains("|"), out);
        assertFalse(out.contains("---"), out);
        assertTrue(out.contains("High"), out);
        assertTrue(out.contains("32C"), out);
        assertTrue(out.contains("Stay dry!"), out);
    }

    @Test
    void stripsEmojiSoTheyAreNotPronounced() {
        // JCLAW-791 follow-up: emoji reached the engine and got pronounced
        // ("lotus", "waving hand"). They must be removed, leaving only words.
        assertEquals("Yes, I can hear you loud and clear lah. What can I help with?",
                TtsText.toSpeakable("Yes, I can hear you loud and clear lah. 🦩 What can I help with?"));
        assertEquals("Hey Tarun! How are you?",
                TtsText.toSpeakable("Hey Tarun! 👋 How are you?"));
        // checkmark, star, heart-with-variation-selector, flag, ZWJ family sequence
        assertEquals("done star heart flag family",
                TtsText.toSpeakable("done ✅ star ⭐ heart ❤️ flag 🇺🇸 family 👨‍👩‍👧"));
    }

    @Test
    void keepsNonEmojiPunctuationAndSymbols() {
        // The emoji strip must not eat prose, currency, math, or accents.
        assertEquals("Plain prose, 100% fine keep $5 and 3+4=7 café résumé",
                TtsText.toSpeakable("Plain prose, 100% fine keep $5 and 3+4=7 café résumé"));
    }

    @Test
    void dropsListBullets() {
        var out = TtsText.toSpeakable("- first\n- second\n* third");
        assertFalse(out.contains("- "), out);
        assertTrue(out.contains("first"), out);
        assertTrue(out.contains("third"), out);
    }
}
