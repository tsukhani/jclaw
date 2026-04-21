import org.junit.jupiter.api.*;
import agents.ToolRegistry;
import models.EventLog;
import play.test.*;
import services.ConfigService;
import services.EventLogger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Phase 5 of the backend test audit: cover Play Job {@code doJob()} invocations
 * that had no direct coverage. Each test drives a real job through its lifecycle
 * and asserts the observable effect, rather than mocking anything.
 */
public class JobLifecycleTest extends UnitTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
        EventLogger.clear();
    }

    // === EventLogCleanupJob ===

    @Test
    public void eventLogCleanupDeletesRowsOlderThanCutoff() {
        // Seed two rows: one well past retention, one recent. Run the job
        // and assert only the old one is deleted.
        services.Tx.run(() -> {
            var oldLog = new EventLog();
            oldLog.level = "INFO";
            oldLog.category = "cleanup-test";
            oldLog.message = "old";
            oldLog.timestamp = Instant.now().minus(60, ChronoUnit.DAYS);
            oldLog.save();

            var recent = new EventLog();
            recent.level = "INFO";
            recent.category = "cleanup-test";
            recent.message = "recent";
            recent.timestamp = Instant.now();
            recent.save();
        });

        new jobs.EventLogCleanupJob().doJob();

        // After cleanup (default retention 30 days), only the recent row remains.
        List<EventLog> remaining = services.Tx.run(() -> EventLog.<EventLog>find(
                "category = ?1", "cleanup-test").fetch());
        assertEquals(1, remaining.size(),
                "exactly one cleanup-test row must remain (the recent one)");
        assertEquals("recent", remaining.getFirst().message,
                "the recent row must be the survivor, not the old one");
    }

    @Test
    public void eventLogCleanupIsNoOpWhenAllRowsWithinRetention() {
        services.Tx.run(() -> {
            var log = new EventLog();
            log.level = "INFO";
            log.category = "fresh-only";
            log.message = "fresh";
            log.timestamp = Instant.now();
            log.save();
        });

        new jobs.EventLogCleanupJob().doJob();

        List<EventLog> remaining = services.Tx.run(() -> EventLog.<EventLog>find(
                "category = ?1", "fresh-only").fetch());
        assertEquals(1, remaining.size(),
                "a fresh row must survive a cleanup pass");
    }

    // === ToolRegistrationJob ===

    @Test
    public void toolRegistrationPublishesBaseTools() {
        // The always-on tools must land in the registry after registerAll.
        // This is the smoke test for the job's primary contract.
        ConfigService.set("playwright.enabled", "false");
        ConfigService.set("shell.enabled", "false");
        new jobs.ToolRegistrationJob().doJob();

        var names = ToolRegistry.listTools().stream()
                .map(ToolRegistry.Tool::name)
                .toList();
        assertTrue(names.contains("filesystem"), "filesystem must always be registered");
        assertTrue(names.contains("datetime"), "datetime must always be registered");
        assertTrue(names.contains("task_manager"), "task_manager tool must always be registered");
        assertTrue(names.contains("web_fetch"), "web_fetch must always be registered");
    }

    @Test
    public void toolRegistrationIncludesShellWhenEnabled() {
        // Flipping shell.enabled from false → true + re-registering must
        // add the exec tool. This is the path exercised by
        // ConfigService.setWithSideEffects for operator toggles.
        ConfigService.set("shell.enabled", "false");
        jobs.ToolRegistrationJob.registerAll();
        var before = ToolRegistry.listTools().stream()
                .map(ToolRegistry.Tool::name)
                .toList();
        assertFalse(before.contains("exec"),
                "exec must not be registered when shell.enabled=false");

        ConfigService.set("shell.enabled", "true");
        jobs.ToolRegistrationJob.registerAll();
        var after = ToolRegistry.listTools().stream()
                .map(ToolRegistry.Tool::name)
                .toList();
        assertTrue(after.contains("exec"),
                "exec must be registered when shell.enabled=true");
    }

    @Test
    public void toolRegistrationIncludesPlaywrightWhenEnabled() {
        ConfigService.set("playwright.enabled", "true");
        jobs.ToolRegistrationJob.registerAll();
        var names = ToolRegistry.listTools().stream()
                .map(ToolRegistry.Tool::name)
                .toList();
        assertTrue(names.contains("browser"),
                "browser tool must be registered when playwright.enabled=true");
    }

    // === BrowserCleanupJob ===

    @Test
    public void browserCleanupJobRunsWithoutError() {
        // With no open sessions, the cleanup pass must be a no-op that
        // doesn't throw — it runs every 60s and any exception would flood
        // the logs. The substantive cleanup logic is covered by
        // PlaywrightToolTest.idleSessionCleanupDoesNotThrow; this test
        // locks in that the job wiring itself is sound.
        new jobs.BrowserCleanupJob().doJob();
    }

    // === ShutdownJob ===

    @Test
    public void shutdownJobRunsWithoutError() {
        // The job chains three shutdown-style calls (task poller, browser
        // sessions, telegram poller). In a unit-test context with none of
        // those running, the job must still complete cleanly.
        new jobs.ShutdownJob().doJob();
    }

    // === TelegramStreamingRecoveryJob (JCLAW-95) ===

    @Test
    public void streamingRecoveryIsNoOpWithNoOrphans() {
        // Empty DB — nothing to recover. Must not throw; must not log any
        // "recovery: N orphaned..." line.
        new jobs.TelegramStreamingRecoveryJob().doJob();
        // If it threw, the test would already have failed.
    }

    @Test
    public void streamingRecoveryClearsCheckpointEvenWithoutEnabledBinding() {
        // When a conversation has a dangling checkpoint but no TelegramBinding
        // exists for the agent (e.g. binding was deleted between crash and
        // recovery), the job must still clear the checkpoint columns.
        // Otherwise the next boot re-tries an un-editable placeholder forever.
        var agent = services.AgentService.create(
                "recovery-agent", "openrouter", "gpt-4.1");
        var convId = services.Tx.run(() -> {
            var conv = services.ConversationService.create(
                    agent, "telegram", "999999");
            conv.activeStreamMessageId = 42;
            conv.activeStreamChatId = "999999";
            conv.save();
            return conv.id;
        });

        jobs.TelegramStreamingRecoveryJob.recoverAll();

        var after = services.Tx.run(() ->
                (models.Conversation) models.Conversation.findById(convId));
        assertNull(after.activeStreamMessageId,
                "checkpoint columns must be cleared even when no binding exists");
        assertNull(after.activeStreamChatId);
    }

    @Test
    public void streamingRecoveryEditsPlaceholderWhenBindingIsEnabled() throws Exception {
        // JCLAW-96 Gap 2: the interesting path — checkpoint + enabled binding
        // — was never wire-verified. This test stands up a MockTelegramServer,
        // routes TelegramChannel at it, seeds a Conversation with a checkpoint
        // and an enabled TelegramBinding, then asserts the recovery pass
        // issues exactly one editMessageText with INTERRUPT_NOTE and clears
        // the checkpoint columns.
        final String botToken = "recovery-bot-token";
        MockTelegramServer mockServer = new MockTelegramServer();
        try {
            mockServer.start();
            channels.TelegramChannel.installForTest(botToken, mockServer.telegramUrl());

            var agent = services.AgentService.create(
                    "recovery-binding-agent", "openrouter", "gpt-4.1");
            var convId = services.Tx.run(() -> {
                var conv = services.ConversationService.create(
                        agent, "telegram", "777");
                conv.activeStreamMessageId = 999;
                conv.activeStreamChatId = "777";
                conv.save();
                var binding = new models.TelegramBinding();
                binding.agent = agent;
                binding.botToken = botToken;
                binding.telegramUserId = "777";
                binding.transport = channels.ChannelTransport.POLLING;
                binding.enabled = true;
                binding.save();
                return conv.id;
            });

            jobs.TelegramStreamingRecoveryJob.recoverAll();

            assertEquals(1, mockServer.countRequests("editMessageText"),
                    "exactly one editMessageText should have landed for the orphan");
            var editRequests = mockServer.requests().stream()
                    .filter(r -> r.method().equalsIgnoreCase("editMessageText"))
                    .toList();
            assertFalse(editRequests.isEmpty(), "expected at least one editMessageText");
            var body = editRequests.get(0).body();
            assertTrue(body.contains("interrupted"),
                    "body should contain INTERRUPT_NOTE (\"...interrupted...\"); got: " + body);
            assertTrue(body.contains("\"message_id\":999"),
                    "body should target the seeded messageId; got: " + body);

            var after = services.Tx.run(() ->
                    (models.Conversation) models.Conversation.findById(convId));
            assertNull(after.activeStreamMessageId,
                    "checkpoint columns must be cleared after successful recovery");
            assertNull(after.activeStreamChatId);
        } finally {
            channels.TelegramChannel.clearForTest(botToken);
            mockServer.close();
        }
    }

    // The "DRAFT sinks produce no checkpoint" test was removed alongside
    // DRAFT disablement (JCLAW-121). DRAFT is never selected now, so the
    // recovery-immunity invariant the test enforced is vacuously true and
    // there is no path to exercise it. EDIT_IN_PLACE checkpoint behavior
    // is covered by streamingRecoveryFindsOrphanCheckpointAndEditsPlaceholder
    // elsewhere in this file.

    @Test
    public void streamingRecoverySkipsConversationsWithoutCheckpoint() {
        // Conversations without a checkpoint (the normal case) must not be
        // touched by the recovery pass — it's selected explicitly by the
        // activeStreamMessageId IS NOT NULL query.
        var agent = services.AgentService.create(
                "no-checkpoint-agent", "openrouter", "gpt-4.1");
        var convId = services.Tx.run(() -> {
            var conv = services.ConversationService.create(
                    agent, "web", "admin");
            return conv.id;
        });

        jobs.TelegramStreamingRecoveryJob.recoverAll();

        var after = services.Tx.run(() ->
                (models.Conversation) models.Conversation.findById(convId));
        assertNull(after.activeStreamMessageId);
        assertNull(after.activeStreamChatId);
    }

    // === TelegramCommandsRegistrationJob (JCLAW-99) ===

    @Test
    public void commandsRegistrationIsNoOpWithNoBindings() {
        // No bindings in DB → job must run cleanly without throwing or
        // attempting any network calls. Mirrors TelegramStreamingRecoveryJob's
        // empty-DB defensive posture.
        new jobs.TelegramCommandsRegistrationJob().doJob();
    }

    @Test
    public void commandsRegistrationMappingCoversEveryRegisteredCommand() {
        // The BotCommand list derived from slash.Commands.Command must
        // have one entry per enum value, with the leading "/" stripped
        // from each name and the description populated. This is the
        // guarantee callers need — adding a new command via the enum
        // automatically makes it show up in Telegram autocomplete on
        // the next restart, no separate registration to update.
        var botCommands = jobs.TelegramCommandsRegistrationJob.toBotCommands();
        assertEquals(slash.Commands.Command.values().length, botCommands.size(),
                "one BotCommand per enum value");

        for (var cmd : slash.Commands.Command.values()) {
            var match = botCommands.stream()
                    .filter(bc -> bc.getCommand().equals(cmd.bareName()))
                    .findFirst();
            assertTrue(match.isPresent(),
                    "missing BotCommand for enum " + cmd.name());
            assertEquals(cmd.shortDescription, match.get().getDescription());
            // Telegram's BotCommand.command field must NOT include the
            // leading slash — the client adds it at display time. Bugs
            // here would produce broken "//new" entries in autocomplete.
            assertFalse(match.get().getCommand().startsWith("/"),
                    "BotCommand.command must be bare, without leading /");
        }
    }
}
