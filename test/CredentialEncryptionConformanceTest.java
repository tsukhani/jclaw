import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.lang.ArchRule;
import models.Config;
import org.hibernate.Session;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.UnitTest;
import services.ConfigService;
import services.Tx;
import services.decision.DecisionSettings;
import services.decision.LayaDecision;
import services.telemetry.OtelConfig;
import tools.scrape.DataImpulsePlans;
import tools.scrape.WebScrapeSettings;
import utils.CredentialCipher;

import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

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
    static final List<Store> STORES = List.of(
            configKey("jclaw1455.provider.apiKey"),
            configKey("jclaw1455.stealth.hfToken"),
            configKey("jclaw1455.proxy.password"),
            configKey("jclaw1455.exporter.secretHeaders"));

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
