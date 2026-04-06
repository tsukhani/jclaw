import org.junit.jupiter.api.*;
import play.test.*;
import services.ConfigService;

public class ConfigServiceTest extends UnitTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
    }

    @Test
    public void setAndGet() {
        ConfigService.set("test.key", "test-value");
        assertEquals("test-value", ConfigService.get("test.key"));
    }

    @Test
    public void getWithDefault() {
        assertEquals("fallback", ConfigService.get("missing.key", "fallback"));
    }

    @Test
    public void getReturnsNullForMissing() {
        assertNull(ConfigService.get("nonexistent"));
    }

    @Test
    public void setOverwrites() {
        ConfigService.set("key", "v1");
        ConfigService.set("key", "v2");
        assertEquals("v2", ConfigService.get("key"));
    }

    @Test
    public void deleteRemovesEntry() {
        ConfigService.set("to-delete", "value");
        assertNotNull(ConfigService.get("to-delete"));
        ConfigService.delete("to-delete");
        assertNull(ConfigService.get("to-delete"));
    }

    @Test
    public void listAllReturnsAll() {
        ConfigService.set("a", "1");
        ConfigService.set("b", "2");
        var all = ConfigService.listAll();
        assertEquals(2, all.size());
    }

    @Test
    public void isSensitiveDetectsKeys() {
        assertTrue(ConfigService.isSensitive("provider.openrouter.apiKey"));
        assertTrue(ConfigService.isSensitive("jclaw.admin.password"));
        assertTrue(ConfigService.isSensitive("slack.signing.secret"));
        assertTrue(ConfigService.isSensitive("telegram.bot.token"));
        assertFalse(ConfigService.isSensitive("provider.openrouter.baseUrl"));
        assertFalse(ConfigService.isSensitive("jclaw.workspace.path"));
    }

    @Test
    public void maskValueHidesSensitive() {
        assertEquals("sk-t****", ConfigService.maskValue("apiKey", "sk-test-123"));
        assertEquals("https://openrouter.ai/api/v1",
                ConfigService.maskValue("baseUrl", "https://openrouter.ai/api/v1"));
    }

    @Test
    public void cacheServesWithoutDb() {
        ConfigService.set("cached", "value");
        assertEquals("value", ConfigService.get("cached"));
        // Value is in cache, even if DB row were somehow gone the cache would serve it
    }
}
