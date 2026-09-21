import agents.AgentRunner;
import channels.ChannelTransport;
import channels.TelegramChannel;
import com.google.gson.JsonParser;
import models.Agent;
import models.Conversation;
import models.Message;
import models.ScrapeJob;
import models.ScrapeJobPage;
import models.TelegramBinding;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.AgentService;
import services.ConversationService;
import services.Tx;
import services.scrape.ScrapeJobFiles;
import services.scrape.ScrapeJobService;
import services.scrape.ScrapeReason;
import services.scrape.ScrapeRung;
import tools.WebScrapeTool;
import tools.scrape.CrawlListener;
import tools.scrape.ScrapeJobRequest;
import tools.scrape.ScrapeOutput;
import tools.scrape.WebScrapeSettings;
import utils.AppClock;
import utils.GsonHolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Background scrape jobs (JCLAW-1272), driven through {@link ScrapeJobService} with a crawler each
 * test supplies, so no page is fetched and no model is called.
 *
 * <p>No {@code Fixtures.deleteDatabase()}: play1 runs test classes concurrently, and every row here
 * belongs to an agent this class creates. Setup and reads run on a fresh thread and transaction,
 * because a job runs on its own thread and sees only what has been committed.
 */
class ScrapeJobServiceTest extends UnitTest {

    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final ScrapeJobService.CompletionTurn NO_TURN = (agent, conversation, owner) -> {};

    private final List<Agent> agents = new ArrayList<>();
    private final List<CountDownLatch> releases = new ArrayList<>();

    @AfterEach
    void cleanUp() throws Exception {
        releases.forEach(CountDownLatch::countDown);
        for (var agent : agents) {
            await(() -> fresh(() -> ScrapeJob.count("agent.id = ?1 AND state IN (?2, ?3)", agent.id,
                    ScrapeJob.State.PENDING, ScrapeJob.State.RUNNING)) == 0, "the agent's jobs end");
            fresh(() -> {
                Agent row = Agent.findById(agent.id);
                if (row != null) AgentService.delete(row);
                return null;
            });
            deleteTree(AgentService.workspacePath(agent.name));
        }
    }

    // ==================== pages and outcome ====================

    @Test
    void aJobRecordsEachPageAsItLandsAndWritesItsFiles() throws Exception {
        var agent = agent("sj-pages");
        var id = submit(agent, null, crawler("page budget (10) reached",
                fetched("https://jobs.test/a", "# Alpha"),
                blocked("https://jobs.test/b"),
                fetched("https://jobs.test/c", "# Gamma")), NO_TURN);

        var end = awaitEnd(id);
        assertEquals(ScrapeJob.State.SUCCEEDED, end.state());
        assertEquals(3, end.pagesRead());
        assertEquals(2, end.pagesFetched());
        assertEquals(3, end.pagesDiscovered());
        assertEquals("page budget (10) reached", end.stopReason(), "the crawl's own stop reason is kept");

        var rows = pages(id);
        assertEquals(List.of(1, 2, 3), rows.stream().map(PageRow::index).toList());
        assertEquals(List.of(ScrapeJobPage.Outcome.FETCHED, ScrapeJobPage.Outcome.BLOCKED, ScrapeJobPage.Outcome.FETCHED),
                rows.stream().map(PageRow::outcome).toList());
        assertEquals("TURNSTILE", rows.get(1).reason());
        assertNull(rows.get(1).file(), "a page that was not retrieved has no file");
        assertEquals("scrapes/%d/0001.md".formatted(id), rows.get(0).file());
        assertEquals("# Alpha", read(agent, rows.get(0).file()));

        var combined = read(agent, "scrapes/%d/combined.md".formatted(id));
        assertTrue(combined.startsWith("Scraped 3 pages"), combined);
        assertTrue(combined.contains("## https://jobs.test/a\n\n# Alpha"), combined);
        assertTrue(combined.contains("[Not retrieved — TURNSTILE]"), combined);
        assertTrue(combined.contains("# Gamma"), combined);
    }

    @Test
    void aJsonJobWritesOneRecordPerPageAndALineEachInTheCombinedFile() throws Exception {
        var agent = agent("sj-json");
        var id = fresh(() -> ScrapeJobService.submitForTest(agent, null,
                ScrapeJobRequest.forOperator(JsonParser.parseString(
                        "{\"url\":\"https://jobs.test/\",\"format\":\"json\"}").getAsJsonObject()),
                false, crawler(null, fetched("https://jobs.test/a", "{\"url\":\"https://jobs.test/a\"}"),
                        blocked("https://jobs.test/b")), NO_TURN).id);

        assertEquals(ScrapeJob.State.SUCCEEDED, awaitEnd(id).state());
        assertEquals("scrapes/%d/0001.json".formatted(id), pages(id).getFirst().file());
        var lines = read(agent, "scrapes/%d/combined.jsonl".formatted(id)).lines().toList();
        assertEquals(2, lines.size(), lines.toString());
        assertEquals("https://jobs.test/b", JsonParser.parseString(lines.get(1)).getAsJsonObject().get("url").getAsString());
        assertEquals("TURNSTILE", JsonParser.parseString(lines.get(1)).getAsJsonObject().get("error").getAsString());
    }

    @Test
    void aJobThatRetrievesNoPageFailsWithTheSeedsReason() {
        var agent = agent("sj-fail");
        var blockedSeed = submit(agent, null, crawler(null, blocked("https://jobs.test/")), NO_TURN);
        var refusedSeed = submit(agent, null, (request, listener) ->
                new WebScrapeTool.JobCrawl("Scraped 0 pages\n", null, "disallowed by robots.txt"), NO_TURN);

        var blockedEnd = awaitEnd(blockedSeed);
        assertEquals(ScrapeJob.State.FAILED, blockedEnd.state());
        assertTrue(blockedEnd.errorMessage().contains("blocked (TURNSTILE)"), blockedEnd.errorMessage());

        var refusedEnd = awaitEnd(refusedSeed);
        assertEquals(ScrapeJob.State.FAILED, refusedEnd.state());
        assertEquals("The starting URL was refused: disallowed by robots.txt.", refusedEnd.errorMessage());
    }

    @Test
    void aCrawlThatThrowsFailsTheJobRatherThanLeavingItRunning() {
        var agent = agent("sj-crash");
        var id = submit(agent, null, (request, listener) -> {
            throw new IllegalStateException("boom");
        }, NO_TURN);

        var end = awaitEnd(id);
        assertEquals(ScrapeJob.State.FAILED, end.state());
        assertEquals("the crawl failed: boom", end.errorMessage());
    }

    // ==================== scheduling ====================

    @Test
    void jobsBeyondTheLimitWaitAndStartOldestFirst() throws Exception {
        var agent = agent("sj-order");
        int limit = fresh(WebScrapeSettings::jobMaxConcurrent);
        var blockers = new ArrayList<Blocker>();
        var ids = new ArrayList<Long>();
        for (int i = 0; i < limit + 2; i++) {
            var blocker = new Blocker();
            blockers.add(blocker);
            ids.add(submit(agent, null, "https://jobs.test/order/%d/".formatted(i), blocker, NO_TURN));
        }
        for (int i = 0; i < limit; i++) {
            assertTrue(blockers.get(i).started.await(WAIT.toSeconds(), TimeUnit.SECONDS), "job " + i + " starts");
        }
        assertEquals(ScrapeJob.State.PENDING, state(ids.get(limit)).state(), "every slot is taken");
        assertEquals(ScrapeJob.State.PENDING, state(ids.get(limit + 1)).state());

        // One slot frees: the older of the two waiting jobs takes it, and the newer keeps waiting.
        blockers.getFirst().release.countDown();
        assertTrue(blockers.get(limit).started.await(WAIT.toSeconds(), TimeUnit.SECONDS), "the older waiting job starts");
        assertEquals(ScrapeJob.State.PENDING, state(ids.get(limit + 1)).state(), "the newer one still waits");

        blockers.forEach(b -> b.release.countDown());
        ids.forEach(ScrapeJobServiceTest::awaitEnd);
        assertEquals(0, blockers.getLast().started.getCount(), "the newest job ran once a slot freed");
    }

    // ==================== stopping ====================

    @Test
    void stoppingARunningJobStartsNoFurtherFetchAndKeepsWhatItRead() throws Exception {
        var agent = agent("sj-stop");
        var blocker = new Blocker();
        var id = submit(agent, null, blocker, NO_TURN);
        assertTrue(blocker.started.await(WAIT.toSeconds(), TimeUnit.SECONDS));

        var running = state(id);
        assertEquals(ScrapeJob.State.RUNNING, running.state());
        assertEquals(1, running.pagesRead(), "a page is recorded while the job still runs");
        assertEquals(1, pages(id).size());

        assertEquals(ScrapeJobService.CancelResult.STOPPING, fresh(() -> ScrapeJobService.cancel(id)));
        var end = awaitEnd(id);
        assertEquals(ScrapeJob.State.CANCELLED, end.state());
        assertEquals("stopped on request", end.stopReason());
        assertTrue(blocker.stoppedByFlag, "the crawl was stopped by the flag it reads");
        assertFalse(blocker.sawInterrupt, "the job's thread is never interrupted");
        assertEquals(1, pages(id).size());
        assertTrue(read(agent, "scrapes/%d/combined.md".formatted(id)).contains("# One"));
    }

    @Test
    void cancellingAWaitingJobEndsItWithoutRunningIt() throws Exception {
        var agent = agent("sj-cancel-waiting");
        int limit = fresh(WebScrapeSettings::jobMaxConcurrent);
        var blockers = new ArrayList<Blocker>();
        var blockerIds = new ArrayList<Long>();
        for (int i = 0; i < limit; i++) {
            var blocker = new Blocker();
            blockers.add(blocker);
            blockerIds.add(submit(agent, null, blocker, NO_TURN));
        }
        for (var blocker : blockers) {
            assertTrue(blocker.started.await(WAIT.toSeconds(), TimeUnit.SECONDS));
        }
        var ran = new AtomicBoolean();
        var waiting = submit(agent, null, (request, listener) -> {
            ran.set(true);
            return new WebScrapeTool.JobCrawl("", null, null);
        }, NO_TURN);
        assertEquals(ScrapeJob.State.PENDING, state(waiting).state());

        assertEquals(ScrapeJobService.CancelResult.CANCELLED, fresh(() -> ScrapeJobService.cancel(waiting)));
        blockers.forEach(b -> b.release.countDown());
        blockerIds.forEach(ScrapeJobServiceTest::awaitEnd);
        fresh(() -> {
            ScrapeJobService.dispatch();
            return null;
        });

        assertEquals(ScrapeJob.State.CANCELLED, state(waiting).state());
        assertFalse(ran.get(), "a cancelled job never runs");
        assertEquals(ScrapeJobService.CancelResult.ALREADY_FINISHED, fresh(() -> ScrapeJobService.cancel(waiting)));
    }

    // ==================== restart ====================

    @Test
    void aJobLeftRunningByAnEarlierProcessIsInterruptedWithItsPagesKept() throws Exception {
        var agent = agent("sj-orphan");
        var live = new Blocker();
        var liveId = submit(agent, null, live, NO_TURN);
        assertTrue(live.started.await(WAIT.toSeconds(), TimeUnit.SECONDS));

        // What an earlier process leaves: a RUNNING row, a page it read, and nothing here running it.
        var orphanId = fresh(() -> {
            var job = new ScrapeJob();
            job.agent = Agent.findById(agent.id);
            job.url = "https://jobs.test/orphan/";
            job.options = GsonHolder.GSON.toJson(request(job.url).toJson());
            job.state = ScrapeJob.State.RUNNING;
            job.startedAt = AppClock.now();
            job.pagesRead = 1;
            job.pagesFetched = 1;
            job.save();
            var file = ScrapeJobFiles.pageFile(job.id, 1, ScrapeOutput.Format.MARKDOWN);
            AgentService.writeWorkspaceFile(agent.name, file, "# Before the restart");
            var page = new ScrapeJobPage();
            page.job = job;
            page.pageIndex = 1;
            page.url = job.url;
            page.servedBy = ScrapeRung.PLAIN;
            page.outcome = ScrapeJobPage.Outcome.FETCHED;
            page.chars = 20;
            page.file = file;
            page.fetchedAt = AppClock.now();
            page.save();
            return job.id;
        });

        assertTrue(fresh(ScrapeJobService::reconcile) >= 1);

        var orphan = state(orphanId);
        assertEquals(ScrapeJob.State.INTERRUPTED, orphan.state());
        assertEquals("the app stopped while it ran", orphan.stopReason());
        assertEquals(1, pages(orphanId).size(), "the page it read is kept");
        assertTrue(read(agent, "scrapes/%d/combined.md".formatted(orphanId)).contains("# Before the restart"));
        assertEquals(ScrapeJob.State.RUNNING, state(liveId).state(), "a job this process is running is left alone");
    }

    // ==================== deletion ====================

    @Test
    void deletingAFinishedJobRemovesItsRowsAndItsFolder() {
        var agent = agent("sj-delete");
        var id = submit(agent, null, crawler(null, fetched("https://jobs.test/a", "# Alpha")), NO_TURN);
        awaitEnd(id);
        var folder = AgentService.workspacePath(agent.name).resolve("scrapes/" + id);
        assertTrue(Files.isDirectory(folder));

        assertEquals(ScrapeJobService.DeleteResult.DELETED, fresh(() -> ScrapeJobService.delete(id)));
        assertNull(state(id));
        assertEquals(0L, (long) fresh(() -> ScrapeJobPage.count("job.id = ?1", id)));
        assertFalse(Files.exists(folder));
    }

    @Test
    void deletingAJobThatHasNotEndedIsRefused() throws Exception {
        var agent = agent("sj-delete-live");
        var blocker = new Blocker();
        var id = submit(agent, null, blocker, NO_TURN);
        assertTrue(blocker.started.await(WAIT.toSeconds(), TimeUnit.SECONDS));

        assertEquals(ScrapeJobService.DeleteResult.NOT_FINISHED, fresh(() -> ScrapeJobService.delete(id)));
        assertEquals(ScrapeJob.State.RUNNING, state(id).state());
    }

    @Test
    void deletingTheConversationKeepsTheJobAndDeletingTheAgentRemovesIt() {
        var agent = agent("sj-cascade");
        var conversationId = conversation(agent, "web", "u-sj-cascade");
        var id = submit(agent, conversationId, crawler(null, fetched("https://jobs.test/a", "# Alpha")), NO_TURN);
        awaitEnd(id);

        fresh(() -> {
            Message.delete("conversation.id = ?1", conversationId);
            Conversation conversation = Conversation.findById(conversationId);
            conversation.delete();
            return null;
        });
        var kept = state(id);
        assertNotNull(kept, "the results are the agent's, not the chat's");
        assertNull(kept.conversationId());

        fresh(() -> {
            AgentService.delete(Agent.findById(agent.id));
            return null;
        });
        assertNull(state(id));
        assertEquals(0L, (long) fresh(() -> ScrapeJobPage.count("job.id = ?1", id)));
    }

    // ==================== completion ====================

    @Test
    void aJobFromAConversationPostsItsCompletionAndHandsTheConversationATurn() {
        var agent = agent("sj-complete");
        var conversationId = conversation(agent, "web", "u-sj-complete");
        var turns = new CopyOnWriteArrayList<List<Object>>();
        var id = fresh(() -> ScrapeJobService.submitForTest(agent, conversationId, request("https://jobs.test/"),
                true, crawler(null, fetched("https://jobs.test/a", "# Alpha")),
                (a, c, owner) -> turns.add(List.of(a.id, c.id, owner))).id);

        awaitEnd(id);
        await(() -> !turns.isEmpty(), "the completion turn runs");
        assertEquals(List.of(agent.id, conversationId, true), turns.getFirst(),
                "the turn runs in the job's conversation and keeps the asking turn's owner answer");

        var message = fresh(() -> {
            Message m = Message.find("conversation.id = ?1 AND messageKind = ?2", conversationId,
                    ScrapeJobService.MESSAGE_KIND_COMPLETE).first();
            return m == null ? null : List.of(m.role, m.content, m.metadata);
        });
        assertNotNull(message);
        assertEquals("user", message.get(0), "USER-role, so the model reads it on the turn that follows");
        assertTrue(message.get(1).startsWith("Background scrape job %d finished".formatted(id)), message.get(1));
        assertEquals(id, JsonParser.parseString(message.get(2)).getAsJsonObject().get("jobId").getAsLong());
    }

    @Test
    void aJobWithNoConversationPostsNothing() throws Exception {
        var agent = agent("sj-silent");
        var turned = new AtomicBoolean();
        var id = submit(agent, null, crawler(null, fetched("https://jobs.test/a", "# Alpha")),
                (a, c, owner) -> turned.set(true));

        awaitEnd(id);
        Thread.sleep(300);
        assertFalse(turned.get());
    }

    @Test
    void theCompletionReplyGoesOutOnATelegramConversationAndWaitsWhenItIsQueued() throws Exception {
        var agent = agent("sj-telegram");
        var token = "sj-tg-" + System.nanoTime();
        var peer = "4242";
        fresh(() -> {
            var binding = new TelegramBinding();
            binding.agent = Agent.findById(agent.id);
            binding.botToken = token;
            binding.telegramUserId = peer;
            binding.transport = ChannelTransport.POLLING;
            binding.enabled = true;
            binding.save();
            return null;
        });
        var telegramId = conversation(agent, "telegram", peer);
        var webId = conversation(agent, "web", "u-sj-telegram");
        var deliver = AgentRunner.class.getDeclaredMethod("deliverResumed",
                Agent.class, Conversation.class, AgentRunner.RunResult.class);
        deliver.setAccessible(true);

        try (var mock = new MockTelegramServer()) {
            mock.start();
            TelegramChannel.installForTest(token, mock.telegramUrl());
            try {
                var telegram = fresh(() -> (Conversation) Conversation.findById(telegramId));
                var web = fresh(() -> (Conversation) Conversation.findById(webId));

                deliver.invoke(null, agent, telegram, new AgentRunner.RunResult("Here is the summary.", telegram, false));
                assertEquals(1, mock.countRequests("sendMessage"), "the reply goes out on the Telegram chat");
                assertTrue(mock.requests().getLast().body().contains("Here is the summary"));

                deliver.invoke(null, agent, telegram, new AgentRunner.RunResult(
                        "Your message has been queued and will be processed shortly.", telegram, false));
                assertEquals(1, mock.countRequests("sendMessage"),
                        "a queued turn is sent by the queue's drain when it runs, not here");

                deliver.invoke(null, agent, web, new AgentRunner.RunResult("Here is the summary.", web, false));
                assertEquals(1, mock.countRequests("sendMessage"), "web chat reads the persisted reply instead");
            } finally {
                TelegramChannel.clearForTest(token);
            }
        }
    }

    // ==================== helpers ====================

    /** Reads one page, then holds its job RUNNING until the job is stopped or the test releases it. */
    private final class Blocker implements ScrapeJobService.Crawler {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile boolean stoppedByFlag;
        volatile boolean sawInterrupt;

        Blocker() {
            releases.add(release);
        }

        @Override
        public WebScrapeTool.JobCrawl crawl(ScrapeJobRequest request, CrawlListener listener) {
            listener.discovered(2);
            listener.page(fetched(request.url() + "one", "# One"));
            started.countDown();
            try {
                while (!listener.stopRequested() && !release.await(20, TimeUnit.MILLISECONDS)) {
                    // held until stopped or released
                }
            } catch (InterruptedException e) {
                sawInterrupt = true;
                Thread.currentThread().interrupt();
            }
            stoppedByFlag = listener.stopRequested();
            if (Thread.currentThread().isInterrupted()) sawInterrupt = true;
            return new WebScrapeTool.JobCrawl("Scraped 1 page\n", null, null);
        }
    }

    private static ScrapeJobService.Crawler crawler(String stoppedBecause, CrawlListener.Page... pages) {
        return (request, listener) -> {
            listener.discovered(pages.length);
            for (var page : pages) listener.page(page);
            return new WebScrapeTool.JobCrawl("Scraped %d pages from %s\n".formatted(pages.length, request.url()),
                    stoppedBecause, null);
        };
    }

    private static CrawlListener.Page fetched(String url, String content) {
        return new CrawlListener.Page(url, 0, ScrapeRung.PLAIN, ScrapeReason.OK, content);
    }

    private static CrawlListener.Page blocked(String url) {
        return new CrawlListener.Page(url, 1, ScrapeRung.PLAIN, ScrapeReason.TURNSTILE, null);
    }

    private static ScrapeJobRequest request(String url) {
        return ScrapeJobRequest.forOperator(JsonParser.parseString(
                "{\"url\":\"%s\",\"maxPages\":10,\"maxDepth\":1}".formatted(url)).getAsJsonObject());
    }

    private Agent agent(String prefix) {
        var name = prefix + "-" + (System.nanoTime() % 1_000_000_000L);
        var agent = fresh(() -> AgentService.create(name, "openrouter", "gpt-4.1"));
        agents.add(agent);
        return agent;
    }

    private static Long conversation(Agent agent, String channel, String peer) {
        return fresh(() -> ConversationService.create(Agent.findById(agent.id), channel, peer).id);
    }

    private static Long submit(Agent agent, Long conversationId, ScrapeJobService.Crawler crawler,
                               ScrapeJobService.CompletionTurn turn) {
        return submit(agent, conversationId, "https://jobs.test/", crawler, turn);
    }

    private static Long submit(Agent agent, Long conversationId, String url, ScrapeJobService.Crawler crawler,
                               ScrapeJobService.CompletionTurn turn) {
        return fresh(() -> ScrapeJobService.submitForTest(agent, conversationId, request(url), false, crawler, turn).id);
    }

    private record JobState(ScrapeJob.State state, int pagesRead, int pagesFetched, int pagesDiscovered,
                            String stopReason, String errorMessage, Long conversationId) {}

    private record PageRow(int index, ScrapeJobPage.Outcome outcome, String reason, String file) {}

    private static JobState state(Long id) {
        return fresh(() -> {
            ScrapeJob job = ScrapeJob.findById(id);
            return job == null ? null : new JobState(job.state, job.pagesRead, job.pagesFetched, job.pagesDiscovered,
                    job.stopReason, job.errorMessage, job.conversation == null ? null : job.conversation.id);
        });
    }

    private static List<PageRow> pages(Long id) {
        return fresh(() -> {
            List<ScrapeJobPage> rows = ScrapeJobPage.find("job.id = ?1 ORDER BY pageIndex", id).fetch();
            return rows.stream().map(p -> new PageRow(p.pageIndex, p.outcome, p.reason, p.file)).toList();
        });
    }

    private static JobState awaitEnd(Long id) {
        var end = new AtomicReference<JobState>();
        await(() -> {
            var now = state(id);
            end.set(now);
            return now != null && now.state().terminal();
        }, "job " + id + " ends");
        return end.get();
    }

    private static void await(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("timed out waiting until " + what);
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    private static String read(Agent agent, String path) throws IOException {
        return Files.readString(AgentService.workspacePath(agent.name).resolve(path));
    }

    /** Commit {@code block} on its own thread, as the job thread will read it. */
    private static <T> T fresh(Supplier<T> block) {
        var ref = new AtomicReference<T>();
        var err = new AtomicReference<Throwable>();
        var t = Thread.ofPlatform().start(() -> {
            try {
                ref.set(Tx.run(block::get));
            } catch (Throwable ex) {
                err.set(ex);
            }
        });
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        if (err.get() != null) throw new IllegalStateException(err.get());
        return ref.get();
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            for (var path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
