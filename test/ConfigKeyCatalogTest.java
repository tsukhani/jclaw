import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.ConfigKeyCatalog;

class ConfigKeyCatalogTest extends UnitTest {

    @Test
    void anExactLineReadsItsKeyAndNoOther() {
        assertTrue(ConfigKeyCatalog.isRead("chat.maxToolRounds"));
        assertFalse(ConfigKeyCatalog.isRead("chat.maxToolRoundsX"));
        assertFalse(ConfigKeyCatalog.isRead("xchat.maxToolRounds"));
    }

    @Test
    void aPatternReadsANameHoldingDotsSlashesAndColons() {
        assertTrue(ConfigKeyCatalog.isRead("jtokkit.safetyMultiplier.openrouter.deepseek/deepseek-v3.2"));
        assertTrue(ConfigKeyCatalog.isRead("jtokkit.safetyMultiplier.ollama-cloud.gpt-oss:120b"));
        assertTrue(ConfigKeyCatalog.isRead("provider.ollama-cloud.apiKey"));
        assertTrue(ConfigKeyCatalog.isRead("agent.main.chromeBridge.allowScript"));
    }

    @Test
    void aPatternNeedsANameWhereItsStarIs() {
        assertFalse(ConfigKeyCatalog.isRead("provider.apiKey"));
        assertFalse(ConfigKeyCatalog.isRead("provider.openrouter.apiKeyX"));
        assertFalse(ConfigKeyCatalog.isRead("jtokkit.safetyMultiplier."));
    }

    @Test
    void aRetiredOrUnknownKeyIsNotRead() {
        assertFalse(ConfigKeyCatalog.isRead("chat.compaction.onToolRound"));
        assertTrue(ConfigKeyCatalog.retired().contains("chat.compaction.onToolRound"));
        assertFalse(ConfigKeyCatalog.isRead("nothing.reads.this"));
        assertFalse(ConfigKeyCatalog.isRead(""));
    }

    @Test
    void aStarIsAnyNonEmptyRunAndEverythingElseIsLiteral() {
        assertEquals("\\Qprovider.\\E.+\\Q.apiKey\\E", ConfigKeyCatalog.regexOf("provider.*.apiKey"));
        assertEquals("\\Qtts.local.\\E.+", ConfigKeyCatalog.regexOf("tts.local.*"));
        assertEquals(".+\\Q.port\\E", ConfigKeyCatalog.regexOf("*.port"));
        assertEquals("\\Qchat.maxToolRounds\\E", ConfigKeyCatalog.regexOf("chat.maxToolRounds"));
    }
}
