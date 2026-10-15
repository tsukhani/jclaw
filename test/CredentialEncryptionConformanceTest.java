import channels.ChannelTransport;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.lang.ArchRule;
import controllers.ApiChannelsController;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Table;
import models.AgentBoundBinding;
import models.BindingCredential;
import models.BindingCredentialConverter;
import models.ChannelConfig;
import models.Config;
import models.JsonCredential;
import models.McpServer;
import models.SlackBinding;
import models.TelegramBinding;
import models.WhatsAppBinding;
import org.hibernate.Session;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.UnitTest;
import services.AgentService;
import services.ConfigService;
import services.McpServerService;
import services.Tx;
import services.decision.DecisionSettings;
import services.decision.LayaDecision;
import services.telemetry.OtelConfig;
import tools.scrape.DataImpulsePlans;
import tools.scrape.WebScrapeSettings;
import utils.CredentialCipher;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * JCLAW-1455: a third-party credential is stored encrypted wherever JClaw keeps one. Each {@link Store} writes a fake
 * credential through the application's own write path and reads the raw column back with SQL; a store whose column
 * holds the plaintext, or no {@code enc:v1:} ciphertext, fails. A new store of credentials adds itself to
 * {@link #STORES}.
 */
class CredentialEncryptionConformanceTest extends UnitTest {

    /** One place a credential is persisted. */
    interface Store {

        /** Names the store in a failure. */
        String name();

        /** Stores {@code secret} through the application's own write path and returns its row's id. */
        long write(String secret);

        /** Selects the row's raw column; the row's id is the one parameter. */
        String rawColumnSql();

        /** The credential's stored form within the raw column, for a column that holds more than the credential. */
        default String storedValue(String rawColumn) {
            return rawColumn;
        }

        /** What the application reads back for the row. */
        @Nullable String read(long id);

        void delete(long id);
    }

    /** Every store a credential is kept in. Stand-in Config keys: the cipher follows a key's name, not its namespace. */
    static final List<Store> STORES = Stream.of(
            Stream.of(
                    configKey("jclaw1455.provider.apiKey"),
                    configKey("jclaw1455.stealth.hfToken"),
                    configKey("jclaw1455.proxy.password"),
                    configKey("jclaw1455.exporter.secretHeaders")),
            Arrays.stream(BindingCredential.values()).map(CredentialEncryptionConformanceTest::bindingColumn),
            Stream.of(
                    mcpServerValue(McpServer.Transport.STDIO, "env", "GITHUB_TOKEN"),
                    mcpServerValue(McpServer.Transport.HTTP, "headers", "Authorization"),
                    channelConfigValue("accessToken")))
            .flatMap(stores -> stores)
            .toList();

    /** The entity field each binding credential column maps. */
    private static final Map<BindingCredential, String> BINDING_FIELDS = Map.of(
            BindingCredential.SLACK_BOT_TOKEN, "botToken",
            BindingCredential.SLACK_SIGNING_SECRET, "signingSecret",
            BindingCredential.SLACK_APP_TOKEN, "appToken",
            BindingCredential.TELEGRAM_BOT_TOKEN, "botToken",
            BindingCredential.TELEGRAM_WEBHOOK_SECRET, "webhookSecret",
            BindingCredential.WHATSAPP_ACCESS_TOKEN, "accessToken",
            BindingCredential.WHATSAPP_APP_SECRET, "appSecret",
            BindingCredential.WHATSAPP_VERIFY_TOKEN, "verifyToken");

    /** Persistent attributes whose names read as credentials but hold none: a hash, a config key's name, a search key. */
    private static final Set<String> NOT_CREDENTIALS = Set.of(
            "ApiToken.secretHash", "Config.key", "Memory.retrievalKey");

    /** The Config keys that hold third-party credentials today. Only asked, never written: other classes read them. */
    private static final List<String> CREDENTIAL_KEYS = List.of(
            "provider.openai.apiKey", "search.exa.apiKey", "scanner.virustotal.apiKey", "scanner.malwarebazaar.authKey",
            "imagegen.local.hfToken", "web_scrape.stealth.hfToken", WebScrapeSettings.PROXY_PASSWORD,
            DataImpulsePlans.passwordKey("residential"), OtelConfig.KEY_SECRET_HEADERS, DecisionSettings.API_KEY,
            LayaDecision.API_KEY);

    @Test
    void everyStoreKeepsItsCredentialEncrypted() {
        for (var store : STORES) {
            var secret = "fake-credential-" + HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextLong());
            long id = store.write(secret);
            try {
                var column = rawColumn(store.rawColumnSql(), id);
                assertNotNull(column, store.name() + ": the written row is missing");
                assertFalse(column.contains(secret), store.name() + " stores the credential in plaintext");
                assertTrue(store.storedValue(column).startsWith(CredentialCipher.PREFIX),
                        store.name() + " stores no ciphertext: " + store.storedValue(column));
                assertEquals(secret, store.read(id), store.name() + ": the application reads back the original value");
            } finally {
                store.delete(id);
            }
        }
    }

    @Test
    void everyBindingCredentialColumnHoldsAnEncrypted512CharacterToken() {
        // Meta sets its access tokens no maximum length; a 255-character column refused 313 characters (JCLAW-1455).
        var token = "T".repeat(512);
        for (var credential : BindingCredential.values()) {
            var store = bindingColumn(credential);
            long id = store.write(token);
            try {
                var column = rawColumn(store.rawColumnSql(), id);
                assertNotNull(column, store.name());
                assertTrue(column.startsWith(CredentialCipher.PREFIX), store.name() + " stores no ciphertext");
                assertEquals(token, store.read(id), store.name());
            } finally {
                store.delete(id);
            }
        }
    }

    @Test
    void everyPersistentAttributeNamedLikeACredentialIsSealedByItsColumnsConverter() {
        var sealed = new ArrayList<String>();
        for (var entity : JPA.em().getMetamodel().getEntities()) {
            for (var attribute : entity.getAttributes()) {
                if (attribute.getJavaType() != String.class || !ConfigService.isSensitive(attribute.getName())) continue;
                var name = entity.getJavaType().getSimpleName() + "." + attribute.getName();
                if (NOT_CREDENTIALS.contains(name)) continue;
                var field = (Field) attribute.getJavaMember();
                var convert = field.getAnnotation(Convert.class);
                assertNotNull(convert, name + " holds a credential with no BindingCredentialConverter: it is stored in "
                        + "plaintext. Add a BindingCredential column and its converter, or name it in NOT_CREDENTIALS.");
                var converter = assertDoesNotThrow(() -> convert.converter().getConstructor().newInstance(), name);
                var credential = assertInstanceOf(BindingCredentialConverter.class, converter, name).column();
                assertEquals(credential.table, entity.getJavaType().getAnnotation(Table.class).name(), name);
                assertEquals(credential.column, field.getAnnotation(Column.class).name(),
                        name + " is sealed under another column's name, which a value read raw will not open under");
                assertEquals(BINDING_FIELDS.get(credential), attribute.getName(), name);
                sealed.add(name);
            }
        }
        assertEquals(BindingCredential.values().length, sealed.size(),
                "each binding credential column is one attribute the scan found: " + sealed);
    }

    @Test
    void everyConfigKeyThatHoldsACredentialIsEncrypted() {
        for (var key : CREDENTIAL_KEYS) {
            assertTrue(ConfigService.encryptsAtRest(key), key);
        }
    }

    @Test
    void onlyConfigServiceWritesAConfigRow() {
        // A row written anywhere else skips the cipher, and a key moved anywhere else strands its ciphertext under the old
        // name, which is its additional authenticated data.
        ArchRule rule = noClasses()
                .that().doNotHaveFullyQualifiedName("services.ConfigService")
                .and().doNotHaveFullyQualifiedName("models.Config")
                .should().setField("models.Config", "value")
                .orShould().setField("models.Config", "key")
                .orShould().callMethod("models.Config", "upsert", "java.lang.String", "java.lang.String")
                .because("ConfigService is the Config seam of the credential cipher (JCLAW-1455)");
        rule.check(ArchitectureTest.APP_CLASSES);

        var writes = ArchitectureTest.APP_CLASSES.get("services.ConfigService").getFieldAccessesFromSelf().stream()
                .filter(access -> access.getAccessType() == JavaFieldAccess.AccessType.SET)
                .filter(access -> access.getTarget().getFullName().equals("models.Config.value"))
                .count();
        assertTrue(writes > 0, "the rule must still match the writes it guards");
    }

    /** A Config key, written through {@link ConfigService#set}. */
    static Store configKey(String key) {
        return new Store() {
            @Override
            public String name() {
                return "config key " + key;
            }

            @Override
            public long write(String secret) {
                ConfigService.set(key, secret);
                return Tx.run(() -> Config.findByKey(key).id);
            }

            @Override
            public String rawColumnSql() {
                return "SELECT config_value FROM config WHERE id = ?";
            }

            @Override
            public @Nullable String read(long id) {
                var entry = ConfigService.entry(key);
                return entry == null ? null : entry.value();
            }

            @Override
            public void delete(long id) {
                ConfigService.delete(key);
            }
        };
    }

    /** A binding credential column, written by saving a binding through JPA, with an agent of its own. */
    static Store bindingColumn(BindingCredential credential) {
        return new Store() {
            @Override
            public String name() {
                return "binding column " + credential.field();
            }

            @Override
            public long write(String secret) {
                return Tx.run(() -> {
                    var suffix = HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextLong());
                    var agent = AgentService.create("jclaw1455-" + suffix, "openrouter", "gpt-4.1", null, null, false);
                    AgentBoundBinding binding = switch (credential.table) {
                        case "slack_binding" -> {
                            var b = new SlackBinding();
                            b.botToken = "xoxb-fake-" + suffix;
                            b.signingSecret = "fake-signing-secret";
                            b.transport = ChannelTransport.HTTP;
                            yield b;
                        }
                        case "telegram_binding" -> {
                            var b = new TelegramBinding();
                            b.botToken = "1455:fake-" + suffix;
                            b.telegramUserId = "1455";
                            yield b;
                        }
                        default -> new WhatsAppBinding();
                    };
                    binding.agent = agent;
                    binding.getClass().getField(BINDING_FIELDS.get(credential)).set(binding, secret);
                    binding.save();
                    return binding.id;
                });
            }

            @Override
            public String rawColumnSql() {
                return "SELECT " + credential.column + " FROM " + credential.table + " WHERE id = ?";
            }

            @Override
            public @Nullable String read(long id) {
                return Tx.run(() -> (String) entityClass().getField(BINDING_FIELDS.get(credential)).get(load(id)));
            }

            @Override
            public void delete(long id) {
                Tx.run(() -> AgentService.delete(load(id).agent));
            }

            private Class<? extends AgentBoundBinding> entityClass() {
                return switch (credential.table) {
                    case "slack_binding" -> SlackBinding.class;
                    case "telegram_binding" -> TelegramBinding.class;
                    default -> WhatsAppBinding.class;
                };
            }

            // From the row, not the second-level cache, so the read runs the converter.
            private AgentBoundBinding load(long id) {
                var em = JPA.em();
                em.getEntityManagerFactory().getCache().evict(entityClass(), id);
                em.clear();
                return em.find(entityClass(), id);
            }
        };
    }

    @Test
    void everyJsonValueItsApiMasksIsSealedAndNoOther() throws Exception {
        var cipher = CredentialCipher.current();
        var stdio = new McpServer();
        stdio.name = "jclaw1455-mask-stdio";
        stdio.transport = McpServer.Transport.STDIO;
        stdio.configJson = """
                {"command":"npx","env":{"GITHUB_TOKEN":"ghp-fake-0001","DB_PASSWORD":"fake-password-0001",
                "NODE_ENV":"production","LOG_LEVEL":"debug-level"}}""";
        var http = new McpServer();
        http.name = "jclaw1455-mask-http";
        http.transport = McpServer.Transport.HTTP;
        http.configJson = """
                {"url":"http://127.0.0.1:1/mcp","headers":{"Authorization":"Bearer fake-0001","X-Region":"eu-west-1"}}""";
        var channel = """
                {"phoneNumberId":"1455000","accessToken":"EAA-fake-0001","appSecret":"fake-app-secret-0001",
                "verifyToken":"fake-verify-0001","displayName":"fake-display"}""";

        for (var row : List.of(stdio, http)) {
            var view = McpServerService.View.of(row);
            var masked = new LinkedHashMap<String, String>();
            view.env().forEach((name, value) -> masked.put("env." + name, value));
            view.headers().forEach((name, value) -> masked.put("headers." + name, value));
            var stored = JsonParser.parseString(JsonCredential.MCP_SERVER.sealed(row.configJson, cipher)).getAsJsonObject();
            masked.forEach((path, value) -> {
                var section = path.substring(0, path.indexOf('.'));
                var storedValue = stored.getAsJsonObject(section).get(path.substring(section.length() + 1)).getAsString();
                assertEquals(value.endsWith("****"), CredentialCipher.isEncrypted(storedValue),
                        "mcp_server " + path + ": the API masks exactly the values stored encrypted");
            });
        }

        var maskConfig = ApiChannelsController.class.getDeclaredMethod("maskConfig", String.class);
        maskConfig.setAccessible(true);
        var masked = ((JsonElement) maskConfig.invoke(null, channel)).getAsJsonObject();
        var stored = JsonParser.parseString(JsonCredential.CHANNEL_CONFIG.sealed(channel, cipher)).getAsJsonObject();
        for (var name : masked.keySet()) {
            assertEquals(masked.get(name).getAsString().endsWith("****"),
                    CredentialCipher.isEncrypted(stored.get(name).getAsString()),
                    "channel_config " + name + ": the API masks exactly the values stored encrypted");
        }
    }

    /** A secret value in an MCP server's JSON, written by saving the row as the admin API does. */
    static Store mcpServerValue(McpServer.Transport transport, String section, String name) {
        return new Store() {
            @Override
            public String name() {
                return JsonCredential.MCP_SERVER.field(section + "." + name);
            }

            @Override
            public long write(String secret) {
                return Tx.run(() -> {
                    var body = new JsonObject();
                    body.addProperty(transport == McpServer.Transport.HTTP ? "url" : "command",
                            transport == McpServer.Transport.HTTP ? "http://127.0.0.1:1/mcp" : "npx");
                    var values = new JsonObject();
                    values.addProperty(name, secret);
                    body.add(section, values);
                    var row = new McpServer();
                    row.name = "jclaw1455-" + HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextInt());
                    row.transport = transport;
                    row.configJson = McpServerService.composeConfigJson(transport, body);
                    row.save();
                    return row.id;
                });
            }

            @Override
            public String rawColumnSql() {
                return "SELECT config_json FROM mcp_server WHERE id = ?";
            }

            @Override
            public String storedValue(String rawColumn) {
                return JsonParser.parseString(rawColumn).getAsJsonObject().getAsJsonObject(section).get(name).getAsString();
            }

            @Override
            public @Nullable String read(long id) {
                return Tx.run(() -> {
                    var row = fresh(McpServer.class, id);
                    var config = McpServerService.explodeConfigJson(row.transport, row.configJson);
                    return (section.equals("env") ? config.env() : config.headers()).get(name);
                });
            }

            @Override
            public void delete(long id) {
                Tx.run(() -> fresh(McpServer.class, id).delete());
            }
        };
    }

    /** A secret value in a channel's app-wide JSON config, written by saving the row as the admin API does. */
    static Store channelConfigValue(String name) {
        return new Store() {
            @Override
            public String name() {
                return JsonCredential.CHANNEL_CONFIG.field(name);
            }

            @Override
            public long write(String secret) {
                return Tx.run(() -> {
                    var config = new JsonObject();
                    config.addProperty("phoneNumberId", "1455");
                    config.addProperty(name, secret);
                    var row = new ChannelConfig();
                    row.channelType = "jclaw1455-" + HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextInt());
                    row.configJson = config.toString();
                    row.save();
                    return row.id;
                });
            }

            @Override
            public String rawColumnSql() {
                return "SELECT config_json FROM channel_config WHERE id = ?";
            }

            @Override
            public String storedValue(String rawColumn) {
                return JsonParser.parseString(rawColumn).getAsJsonObject().get(name).getAsString();
            }

            @Override
            public @Nullable String read(long id) {
                return Tx.run(() -> JsonParser.parseString(fresh(ChannelConfig.class, id).configJson)
                        .getAsJsonObject().get(name).getAsString());
            }

            @Override
            public void delete(long id) {
                Tx.run(() -> fresh(ChannelConfig.class, id).delete());
            }
        };
    }

    // From the row, not the second-level cache, so the read runs the converter.
    private static <T> T fresh(Class<T> type, long id) {
        var em = JPA.em();
        em.getEntityManagerFactory().getCache().evict(type, id);
        em.clear();
        return em.find(type, id);
    }

    private static @Nullable String rawColumn(String sql, long id) {
        return Tx.run(() -> JPA.em().unwrap(Session.class).doReturningWork(connection -> {
            try (var statement = connection.prepareStatement(sql)) {
                statement.setLong(1, id);
                try (var rows = statement.executeQuery()) {
                    return rows.next() ? rows.getString(1) : null;
                }
            }
        }));
    }
}
