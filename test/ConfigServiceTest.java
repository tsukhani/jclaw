import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import models.Agent;
import services.AgentService;
import services.ConfigService;
import play.db.jpa.JPA;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;

class ConfigServiceTest extends UnitTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
    }

    @Test
    void setAndGet() {
        ConfigService.set("test.key", "test-value");
        assertEquals("test-value", ConfigService.get("test.key"));
    }

    @Test
    void getWithDefault() {
        assertEquals("fallback", ConfigService.get("missing.key", "fallback"));
    }

    @Test
    void getReturnsNullForMissing() {
        assertNull(ConfigService.get("nonexistent"));
    }

    // --- getLong: parses / defaults with the same semantics as getInt ---

    @Test
    void getLongParsesStoredValue() {
        // A value beyond int range proves it's a genuine long parse, not getInt.
        ConfigService.set("some.long", "5000000000");
        assertEquals(5_000_000_000L, ConfigService.getLong("some.long", -1L));
    }

    @Test
    void getLongReturnsDefaultForMissing() {
        assertEquals(3_600_000L, ConfigService.getLong("no.such.long", 3_600_000L));
    }

    @Test
    void getLongReturnsDefaultForNonNumeric() {
        ConfigService.set("bad.long", "not-a-number");
        assertEquals(7L, ConfigService.getLong("bad.long", 7L));
    }

    @Test
    void setOverwrites() {
        ConfigService.set("key", "v1");
        ConfigService.set("key", "v2");
        assertEquals("v2", ConfigService.get("key"));
    }

    // --- JCLAW-782: setIfAbsent is the atomic first-writer-wins primitive behind
    // the unauthenticated /api/auth/setup bootstrap. Unlike set() (last-writer-wins
    // via upsert), it inserts only when the key is absent so a check-then-write race
    // can't land two credentials. ---

    @Test
    void setIfAbsentInsertsWhenAbsentThenReportsPresent() {
        assertTrue(ConfigService.setIfAbsent("boot.key", "first"),
                "first setIfAbsent on an absent key must insert and return true");
        assertEquals("first", ConfigService.get("boot.key"));
        // A second attempt must NOT overwrite (contrast with set()'s last-writer-wins).
        assertFalse(ConfigService.setIfAbsent("boot.key", "second"),
                "setIfAbsent on a present key must return false");
        assertEquals("first", ConfigService.get("boot.key"),
                "setIfAbsent must never overwrite an existing value");
    }

    @Test
    void setIfAbsentIsAtomicUnderConcurrentInserts() throws InterruptedException {
        // Two threads race to bootstrap the same key from fresh, each in its own
        // committed transaction (the seedPassword cross-thread pattern). The
        // config_key unique constraint must arbitrate so exactly one insert wins;
        // the loser trips the constraint and returns false, never overwriting.
        var key = "race.setifabsent";
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        var results = new java.util.concurrent.ConcurrentLinkedQueue<Boolean>();
        var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();

        Runnable attempt = () -> {
            try {
                barrier.await(); // align both threads on the starting line
                results.add(ConfigService.setIfAbsent(key, "v-" + Thread.currentThread().threadId()));
            } catch (Throwable ex) { err.set(ex); }
        };
        var t1 = Thread.ofVirtual().start(attempt);
        var t2 = Thread.ofVirtual().start(attempt);
        t1.join();
        t2.join();

        if (err.get() != null) throw new RuntimeException(err.get());
        assertEquals(2, results.size(), "both attempts must complete");
        long wins = results.stream().filter(Boolean::booleanValue).count();
        assertEquals(1, wins, "exactly one concurrent setIfAbsent must win");
        var stored = ConfigService.get(key);
        assertNotNull(stored, "the winning insert must persist");
        assertTrue(stored.startsWith("v-"), "the persisted value must be a winner's, not absent: " + stored);
    }

    @Test
    void deleteRemovesEntry() {
        ConfigService.set("to-delete", "value");
        assertNotNull(ConfigService.get("to-delete"));
        ConfigService.delete("to-delete");
        assertNull(ConfigService.get("to-delete"));
    }

    @Test
    void listAllReturnsAll() {
        ConfigService.set("a", "1");
        ConfigService.set("b", "2");
        var all = ConfigService.listAll();
        assertEquals(2, all.size());
    }

    @Test
    void isSensitiveDetectsKeys() {
        assertTrue(ConfigService.isSensitive("provider.openrouter.apiKey"));
        assertTrue(ConfigService.isSensitive("jclaw.admin.password"));
        assertTrue(ConfigService.isSensitive("slack.signing.secret"));
        assertTrue(ConfigService.isSensitive("telegram.bot.token"));
        assertFalse(ConfigService.isSensitive("provider.openrouter.baseUrl"));
        assertFalse(ConfigService.isSensitive("jclaw.workspace.path"));
    }

    @Test
    void maskValueHidesSensitive() {
        assertEquals("sk-t****", ConfigService.maskValue("apiKey", "sk-test-123"));
        assertEquals("https://openrouter.ai/api/v1",
                ConfigService.maskValue("baseUrl", "https://openrouter.ai/api/v1"));
    }

    @Test
    void cacheServesWithoutDb() {
        ConfigService.set("cached", "value");
        assertEquals("value", ConfigService.get("cached"));
        // Value is in cache, even if DB row were somehow gone the cache would serve it
    }

    // --- setWithSideEffects: app.timezone IANA validation ---

    @Test
    void setWithSideEffectsRejectsInvalidAppTimezone() {
        // A typo'd zone must be rejected at the write boundary so the system
        // prompt never injects a bad zone (the resolver would silently fall
        // back to the server default, hiding the operator's mistake).
        var error = ConfigService.setWithSideEffects("app.timezone", "Not/A/Zone");
        assertNotNull(error, "invalid IANA zone must be rejected");
        assertTrue(error.toLowerCase().contains("timezone"),
                "error must explain the timezone rejection: " + error);
        // The rejected value must NOT be persisted.
        assertNull(ConfigService.get("app.timezone"));
    }

    @Test
    void setWithSideEffectsAcceptsValidAppTimezone() {
        var error = ConfigService.setWithSideEffects("app.timezone", "Asia/Kuala_Lumpur");
        assertNull(error, "a valid IANA zone must be accepted");
        assertEquals("Asia/Kuala_Lumpur", ConfigService.get("app.timezone"));
    }

    // --- setWithSideEffects: the privilege-guard path ---

    @Test
    void setWithSideEffectsRejectsShellBypassForNonMainAgent() {
        // Security-critical: shell bypass / global path privileges are only
        // legal for the main agent. A custom agent attempting to flip them
        // must be rejected BEFORE the set lands in the DB.
        var custom = AgentService.create("not-main", "openrouter", "gpt-4.1");
        assertFalse(custom.isMain(), "precondition: custom agent is not main");

        var error = ConfigService.setWithSideEffects(
                "agent.not-main.shell.bypassAllowlist", "true");
        assertNotNull(error, "non-main agent must be rejected with an error string");
        assertTrue(error.toLowerCase().contains("main"),
                "error must mention the main-agent restriction");

        // Critical invariant: the rejected value must NOT be persisted.
        assertNull(ConfigService.get("agent.not-main.shell.bypassAllowlist"));
    }

    @Test
    void setWithSideEffectsRejectsAllowGlobalPathsForNonMainAgent() {
        AgentService.create("helper", "openrouter", "gpt-4.1");
        var error = ConfigService.setWithSideEffects(
                "agent.helper.shell.allowGlobalPaths", "true");
        assertNotNull(error);
        assertNull(ConfigService.get("agent.helper.shell.allowGlobalPaths"));
    }

    @Test
    void setWithSideEffectsAcceptsShellPrivilegeForMainAgent() {
        // Seed main agent via direct construction — AgentService.create rejects
        // "main" by convention, but the router identifies it by name so it
        // must exist under that name for the privilege check to pass.
        var main = new Agent();
        main.name = Agent.MAIN_AGENT_NAME;
        main.modelProvider = "openrouter";
        main.modelId = "gpt-4.1";
        main.save();

        var error = ConfigService.setWithSideEffects(
                "agent." + Agent.MAIN_AGENT_NAME + ".shell.bypassAllowlist", "true");
        assertNull(error, "main agent must be allowed");
        assertEquals("true", ConfigService.get(
                "agent." + Agent.MAIN_AGENT_NAME + ".shell.bypassAllowlist"));
    }

    @Test
    void deleteEvictsCacheSoSubsequentGetReturnsNull() {
        // After delete, the in-memory cache entry must be removed or invalidated
        // so the next get() either returns null (if the caller knows to avoid
        // defaults) or forces a DB round-trip that also returns null.
        ConfigService.set("evict.me", "present");
        assertEquals("present", ConfigService.get("evict.me"));

        ConfigService.delete("evict.me");
        assertNull(ConfigService.get("evict.me"),
                "cache must not serve a stale value after delete");
    }

    // --- setWithSideEffects: ollama-cloud LLM key → ollama search key linkage ---

    @Test
    void setWithSideEffectsMirrorsOllamaCloudKeyToSearchWhenSearchKeyEmpty() {
        // Fresh state: no search.ollama.apiKey set. Operator sets the LLM key
        // via Settings — both providers hit the same Ollama account, so the
        // search key should auto-populate AND the search provider should flip
        // enabled=true. Saves the operator a redundant paste.
        assertNull(ConfigService.get("search.ollama.apiKey"));

        var error = ConfigService.setWithSideEffects(
                "provider.ollama-cloud.apiKey", "sk-ollama-abc");
        assertNull(error);

        assertEquals("sk-ollama-abc", ConfigService.get("provider.ollama-cloud.apiKey"));
        assertEquals("sk-ollama-abc", ConfigService.get("search.ollama.apiKey"),
                "search key must be auto-populated from the LLM key");
        assertEquals("true", ConfigService.get("search.ollama.enabled"),
                "web search must be enabled when the search key is auto-populated");
    }

    @Test
    void setWithSideEffectsLeavesExistingSearchKeyAlone() {
        // The "set once, owned by you" rule: once the operator has explicitly
        // set search.ollama.apiKey, a later rotation of the LLM key must NOT
        // overwrite it. Search-key independence is preserved.
        ConfigService.set("search.ollama.apiKey", "operator-set-key");
        ConfigService.set("search.ollama.enabled", "false");

        var error = ConfigService.setWithSideEffects(
                "provider.ollama-cloud.apiKey", "sk-ollama-xyz");
        assertNull(error);

        assertEquals("operator-set-key", ConfigService.get("search.ollama.apiKey"),
                "operator-set search key must not be overwritten");
        assertEquals("false", ConfigService.get("search.ollama.enabled"),
                "operator-set enabled flag must not be flipped");
    }

    @Test
    void setWithSideEffectsDoesNotMirrorBlankLlmKey() {
        // Clearing the LLM key (operator pastes empty / removes credentials)
        // must not mirror the empty value into the search key — that would
        // silently break a working search-only setup. The mirror is a
        // strictly-additive convenience.
        var error = ConfigService.setWithSideEffects(
                "provider.ollama-cloud.apiKey", "");
        assertNull(error);

        assertNull(ConfigService.get("search.ollama.apiKey"),
                "blank LLM key must not propagate to the search key");
        assertNull(ConfigService.get("search.ollama.enabled"),
                "blank LLM key must not enable the search provider");
    }

    @Test
    void setWithSideEffectsIgnoresUnrelatedProviderApiKey() {
        // Sanity guard: the linkage is specific to provider.ollama-cloud.apiKey.
        // Setting a different provider's apiKey must not touch search.ollama.*.
        var error = ConfigService.setWithSideEffects(
                "provider.openrouter.apiKey", "sk-or-some-key");
        assertNull(error);

        assertNull(ConfigService.get("search.ollama.apiKey"));
        assertNull(ConfigService.get("search.ollama.enabled"));
    }

    @Test
    void setWithSideEffectsRejectsShellPrivilegeForNonMainAgent() {
        // The shell-exec privilege gate only allows the main agent to bypass
        // the allowlist. A non-main agent → rejection message returned.
        services.AgentService.create("non-main-shell", "openrouter", "gpt-4.1");
        var error = ConfigService.setWithSideEffects(
                "agent.non-main-shell.shell.bypassAllowlist", "true");
        assertNotNull(error);
        assertTrue(error.contains("main agent"), "rejection message: " + error);
    }

    @Test
    void setWithSideEffectsRejectsShellPrivilegeForUnknownAgent() {
        // Unknown-agent path of the gate — same rejection.
        var error = ConfigService.setWithSideEffects(
                "agent.does-not-exist.shell.allowGlobalPaths", "true");
        assertNotNull(error);
        assertTrue(error.contains("main agent"));
    }

    @Test
    void setWithSideEffectsAppliesDispatcherCapLive() {
        // dispatcher.* keys trigger HttpFactories.applyDispatcherConfig.
        // We verify no exception escapes — the apply branch must be hit.
        var error = ConfigService.setWithSideEffects(
                "dispatcher.llm.maxRequestsPerHost", "32");
        assertNull(error);
    }

    @Test
    void setWithSideEffectsAppliesDispatcherTotalCapLive() {
        var error = ConfigService.setWithSideEffects(
                "dispatcher.llm.maxRequests", "200");
        assertNull(error);
    }

    // --- JCLAW-832: set() caches eagerly for read-your-writes; if it joined an
    // ambient transaction that ROLLS BACK the entry must be evicted, but on COMMIT
    // it must stand. The eviction is driven by an afterCompletion synchronization
    // (registered by scheduleRollbackEviction). We exercise that against a FRESH,
    // thread-unbound EntityManager so the test never disturbs the harness's ambient
    // JPA context — driving startTx/withTransaction from a UnitTest body would. ---

    /** Cache a value the DB does NOT hold — the phantom state set() leaves when its
     *  surrounding transaction rolls back. Seeded via committed fresh-EM operations
     *  (NOT ConfigService.set): the UnitTest body runs inside an ambient tx, so set()
     *  would leave the row uncommitted (invisible to the fresh-EM delete) and register
     *  an extra synchronization on the harness transaction. */
    private void seedCacheOnlyPhantom(String key) {
        EntityManager ins = JPA.newEntityManager("default"); // durable insert (own connection commits)
        try {
            ins.getTransaction().begin();
            var row = new models.Config();
            row.key = key;
            row.value = "phantom-value"; // @PrePersist stamps updatedAt
            ins.persist(row);
            ins.getTransaction().commit();
        } finally {
            if (ins.isOpen()) ins.close();
        }
        assertEquals("phantom-value", ConfigService.get(key)); // warm the service cache from the row
        EntityManager del = JPA.newEntityManager("default"); // durable delete — cache still serves the value
        try {
            del.getTransaction().begin();
            del.createQuery("delete from Config c where c.key = :k")
                    .setParameter("k", key).executeUpdate();
            del.getTransaction().commit();
        } finally {
            if (del.isOpen()) del.close();
        }
        assertEquals("phantom-value", ConfigService.get(key),
                "precondition: the cache still serves the phantom (DB row gone)");
    }

    @Test
    void rollbackEvictsPhantomFromCache() {
        var key = "jclaw832.phantom.rollback";
        seedCacheOnlyPhantom(key);
        EntityManager em = JPA.newEntityManager("default");
        try {
            em.getTransaction().begin();
            ConfigService.scheduleRollbackEviction(em.unwrap(Session.class), key);
            em.getTransaction().rollback(); // fires afterCompletion(ROLLEDBACK) -> evict
        } finally {
            if (em.isOpen()) em.close();
        }
        assertNull(ConfigService.get(key),
                "rolled-back config write must be evicted from the cache, not linger");
    }

    @Test
    void commitKeepsPhantomInCache() {
        var key = "jclaw832.phantom.commit";
        seedCacheOnlyPhantom(key);
        EntityManager em = JPA.newEntityManager("default");
        try {
            em.getTransaction().begin();
            ConfigService.scheduleRollbackEviction(em.unwrap(Session.class), key);
            em.getTransaction().commit(); // fires afterCompletion(COMMITTED) -> keep
        } finally {
            if (em.isOpen()) em.close();
        }
        assertEquals("phantom-value", ConfigService.get(key),
                "a committed transaction must NOT evict the eagerly-cached value");
    }
}
