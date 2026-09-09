import jakarta.persistence.EntityManager;
import models.Agent;
import org.hibernate.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.ConfigService;

class ConfigServiceTest extends UnitTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
    }

    @Test
    void acpModelProviderMustNameAConfiguredProvider() {
        // POST /api/config reaches this key directly; a typo would otherwise surface only as a
        // refused acp spawn much later.
        var key = tools.SubagentSpawnTool.ACP_MODEL_PROVIDER_KEY;
        var rejected = ConfigService.setWithSideEffects(key, "no-such-provider");
        assertNotNull(rejected, "an unconfigured provider name must be refused at the write");
        assertTrue(rejected.contains("no-such-provider") && rejected.contains(key), rejected);

        // The registry lists a provider only once both its baseUrl and apiKey are set.
        ConfigService.set("provider.acp-cfg-prov.baseUrl", "http://127.0.0.1:1/v1");
        ConfigService.set("provider.acp-cfg-prov.apiKey", "k");
        llm.ProviderRegistry.refresh();
        assertNull(ConfigService.setWithSideEffects(key, "acp-cfg-prov"),
                "a configured provider name is accepted");
        assertNull(ConfigService.setWithSideEffects(key, ""), "clearing the key is always accepted");
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

    // --- getDouble: rejects the non-finite values parseDouble accepts (JCLAW-1016) ---

    @Test
    void parseDoubleAcceptsTheNonFiniteLiterals() {
        // Pinned so the next reader does not re-derive why getDouble's catch was insufficient:
        // these three parse successfully, so a NumberFormatException arm can never reject them.
        assertEquals(Double.NaN, Double.parseDouble("NaN"));
        assertEquals(Double.POSITIVE_INFINITY, Double.parseDouble("Infinity"));
        assertEquals(Double.NEGATIVE_INFINITY, Double.parseDouble("-Infinity"));
    }

    @Test
    void getDoubleReturnsDefaultForNonFiniteValues() {
        for (var stored : new String[] {"NaN", "Infinity", "-Infinity", "  NaN  "}) {
            ConfigService.set("some.double", stored);
            assertEquals(0.25, ConfigService.getDouble("some.double", 0.25),
                    "a stored '" + stored + "' must not reach the caller as a non-finite double");
        }
    }

    @Test
    void getDoubleStillParsesFiniteValues() {
        // The guard must reject non-finite values, not every value.
        ConfigService.set("some.double", "0.62");
        assertEquals(0.62, ConfigService.getDouble("some.double", -1.0));
        ConfigService.set("some.double", "-1.0");
        assertEquals(-1.0, ConfigService.getDouble("some.double", 99.0));
    }

    @Test
    void getDoubleReturnsDefaultForNonNumeric() {
        ConfigService.set("bad.double", "not-a-number");
        assertEquals(0.5, ConfigService.getDouble("bad.double", 0.5));
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

    // --- setWithSideEffects: memory providers must stay on this machine ---

    @Test
    void setWithSideEffectsRejectsANonLocalRerankProvider() {
        // Reranking renders the candidate memories into its prompt, so whatever serves it
        // sees memory text — the same exposure that restricts embeddings (JCLAW-939).
        // Enforced here and not only in the Settings picker, because the key is reachable
        // through POST /api/config directly.
        var error = ConfigService.setWithSideEffects(memory.MemoryReranker.KEY_PROVIDER, "openrouter");
        assertNotNull(error, "a non-local rerank provider must be rejected");
        assertTrue(error.contains("reranking"),
                "the error must name the feature it is protecting: " + error);
        assertNull(ConfigService.get(memory.MemoryReranker.KEY_PROVIDER),
                "the rejected value must not be persisted");
    }

    @Test
    void setWithSideEffectsAcceptsBlankRerankProviderAsTheOffSwitch() {
        // Clearing the provider is how an operator turns the reranker back off without
        // hunting for the enabled flag; a blank must not trip the locality guard.
        assertNull(ConfigService.setWithSideEffects(memory.MemoryReranker.KEY_PROVIDER, ""));
    }

    // --- setWithSideEffects: recall knobs that poison ranking (JCLAW-970) ---

    @Test
    void setWithSideEffectsRejectsNegativeRrfK() {
        // k = -1 makes RRF's first term 1.0/0 = Infinity; top-normalization then hands the
        // best hit NaN and everything else 0.0, pinning one memory above every other.
        var error = ConfigService.setWithSideEffects(memory.JpaMemoryStore.KEY_RRF_K, "-1");
        assertNotNull(error, "a negative rrfK must be rejected");
        assertNull(ConfigService.get(memory.JpaMemoryStore.KEY_RRF_K),
                "the rejected value must not be persisted");
    }

    @Test
    void setWithSideEffectsAcceptsAValidRrfK() {
        assertNull(ConfigService.setWithSideEffects(memory.JpaMemoryStore.KEY_RRF_K, "5"));
        assertEquals("5", ConfigService.get(memory.JpaMemoryStore.KEY_RRF_K));
    }

    @Test
    void setWithSideEffectsRejectsNonFiniteMinCosine() {
        // getDouble refuses non-finite values since JCLAW-1016, so this guard is not the last
        // line of defence — it is what turns a silent fallback into an error the operator sees.
        var error = ConfigService.setWithSideEffects(
                memory.JpaMemoryStore.KEY_RECALL_MIN_COSINE, "NaN");
        assertNotNull(error, "a NaN minCosine must be rejected");
        assertNull(ConfigService.get(memory.JpaMemoryStore.KEY_RECALL_MIN_COSINE),
                "the rejected value must not be persisted");
    }

    @Test
    void setWithSideEffectsRejectsAnOutOfRangeMinCosine() {
        // It is a cosine, so anything outside [-1, 1] can never be met by a real hit —
        // 1.5 would disable the vector leg just as silently as NaN.
        assertNotNull(ConfigService.setWithSideEffects(
                memory.JpaMemoryStore.KEY_RECALL_MIN_COSINE, "1.5"));
    }

    @Test
    void setWithSideEffectsAcceptsAValidMinCosine() {
        assertNull(ConfigService.setWithSideEffects(
                memory.JpaMemoryStore.KEY_RECALL_MIN_COSINE, "0.6"));
        assertEquals("0.6", ConfigService.get(memory.JpaMemoryStore.KEY_RECALL_MIN_COSINE));
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

    // --- JCLAW-1042 / VULN-100: a delete must not leave a window for a racing reader to
    // re-cache the row the commit is about to remove. ---

    /** Insert {@code key} on its own connection so the row is committed and visible to every
     *  other connection — the state a racing reader would actually observe. */
    private void seedCommittedRow(String key, String value) {
        EntityManager em = JPA.newEntityManager("default");
        try {
            em.getTransaction().begin();
            var row = new models.Config();
            row.key = key;
            row.value = value;
            em.persist(row);
            em.getTransaction().commit();
        } finally {
            if (em.isOpen()) em.close();
        }
    }

    @Test
    void deleteDoesNotLetAReaderOnAnotherConnectionRecacheTheUncommittedRow() throws Exception {
        // The delete below runs in this test's ambient transaction and is NOT committed, so a
        // reader on its own connection still sees the row. Before the fix, delete() invalidated
        // the cache inline: that reader missed, loaded the still-present row and re-cached it
        // for the full 60s TTL, so a deleted password hash outlived its own deletion.
        var key = "jclaw1042.vuln100.recache";
        seedCommittedRow(key, "old-password-hash");
        assertEquals("old-password-hash", ConfigService.get(key),
                "precondition: the cache is warm from the committed row");

        ConfigService.delete(key);

        var seen = new java.util.concurrent.atomic.AtomicReference<String>("<unset>");
        var reader = new Thread(() -> seen.set(ConfigService.get(key)));
        reader.start();
        reader.join(10_000);
        assertFalse(reader.isAlive(),
                "the reader must not block on the uncommitted delete — it reads its own connection");
        assertNull(seen.get(),
                "a reader on another connection must be served the absence, not the value the "
                        + "delete is removing; serving the value is what let it survive the commit");
    }
}
