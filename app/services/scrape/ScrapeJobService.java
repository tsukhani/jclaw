package services.scrape;

import agents.AgentRunner;
import agents.DangerousActionGate;
import models.Agent;
import models.Conversation;
import models.MessageRole;
import models.ScrapeJob;
import models.ScrapeJobPage;
import org.jspecify.annotations.Nullable;
import play.db.jpa.JPA;
import services.ConversationService;
import services.EventLogger;
import services.Tx;
import tools.WebScrapeTool;
import tools.scrape.CrawlListener;
import tools.scrape.ScrapeJobRequest;
import tools.scrape.ScrapeOutput;
import tools.scrape.WebScrapeSettings;
import utils.AppClock;
import utils.GsonHolder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs {@link ScrapeJob}s: a crawl that keeps going after the turn that asked for it (JCLAW-1272).
 *
 * <p>The video job is the model, with one difference that shapes everything here: a video renders at
 * the provider and JClaw only polls it, while a scrape job's work runs in this process. So the job
 * owns a thread, and a restart ends it. {@link #ACTIVE} is this process's own record of which jobs
 * it is running, which is what lets {@link #reconcile} tell a live RUNNING row from one an earlier
 * process left behind without guessing from its age.
 *
 * <p>A job is stopped with a flag its crawl reads before each fetch, never by interrupting its
 * thread: the thread writes to H2, and an interrupt closes the database file's channel.
 */
public final class ScrapeJobService {

    /** {@code Message.messageKind} of the message that tells a conversation its job ended. */
    public static final String MESSAGE_KIND_COMPLETE = "scrape_job_complete";

    private static final String EVENT_CATEGORY = "scrape";
    private static final String STOPPED = "stopped on request";

    /** Runs a job's crawl. */
    @FunctionalInterface
    public interface Crawler {
        WebScrapeTool.JobCrawl crawl(ScrapeJobRequest request, CrawlListener listener);
    }

    /** What happens after the completion message is posted: a turn that reads it and answers. */
    @FunctionalInterface
    public interface CompletionTurn {
        void run(Agent agent, Conversation conversation, boolean ownerInitiated);
    }

    private record Collaborators(Crawler crawler, CompletionTurn turn) {}

    private static final Collaborators PRODUCTION = new Collaborators(
            (request, listener) -> new WebScrapeTool().crawlForJob(request, listener),
            ScrapeJobService::runCompletionTurn);

    /**
     * Chosen when a job is submitted and read when it starts, so whichever thread dispatches it runs
     * it with the same ones. That is what lets a test drive its own jobs while another test class
     * submits real ones in the same JVM.
     */
    private static final Map<Long, Collaborators> COLLABORATORS = new ConcurrentHashMap<>();

    private static final Map<Long, Running> ACTIVE = new ConcurrentHashMap<>();

    /** Held while jobs are started, stopped, reconciled or deleted, so none of those interleave. */
    private static final Object LOCK = new Object();

    private static final class Running {
        final Long jobId;
        volatile boolean stopRequested;

        Running(Long jobId) {
            this.jobId = jobId;
        }
    }

    private ScrapeJobService() {}

    public static String folder(Long jobId) {
        return ScrapeJobFiles.folder(jobId);
    }

    public static String combinedFile(Long jobId, ScrapeOutput.Format format) {
        return ScrapeJobFiles.combinedFile(jobId, format);
    }

    /**
     * Queue a job and start it once the row is committed, if a slot is free.
     *
     * @param conversationId told when the job ends; null for one started from the form or a task
     * @param ownerInitiated whether the asking turn was proven to come from the binding owner, which
     *                       the completion turn keeps rather than widens
     */
    public static ScrapeJob submit(Agent agent, @Nullable Long conversationId, ScrapeJobRequest request,
                                  boolean ownerInitiated) {
        return submit(agent, conversationId, request, ownerInitiated, PRODUCTION);
    }

    /** {@link #submit} with the crawl and the completion turn replaced, for this job only. */
    // Public because Play's tests live in the default package.
    public static ScrapeJob submitForTest(Agent agent, @Nullable Long conversationId, ScrapeJobRequest request,
                                          boolean ownerInitiated, Crawler crawler, CompletionTurn turn) {
        return submit(agent, conversationId, request, ownerInitiated, new Collaborators(crawler, turn));
    }

    private static ScrapeJob submit(Agent agent, @Nullable Long conversationId, ScrapeJobRequest request,
                                    boolean ownerInitiated, Collaborators collaborators) {
        var job = Tx.run(() -> {
            var created = new ScrapeJob();
            created.agent = Agent.findById(agent.id);
            created.conversation = conversationId == null ? null : Conversation.findById(conversationId);
            created.url = request.url().toString();
            created.options = GsonHolder.GSON.toJson(request.toJson());
            created.ownerInitiated = ownerInitiated;
            created.save();
            COLLABORATORS.put(created.id, collaborators);
            // After the commit, or the thread that starts it could look for a row it cannot see yet.
            Tx.afterCommit(ScrapeJobService::dispatchSoon);
            return created;
        });
        EventLogger.info(EVENT_CATEGORY, agent.name, null,
                "Scrape job %d queued for %s".formatted(job.id, job.url));
        return job;
    }

    /** Off the committing thread: a commit callback is no place to open the next transaction. */
    private static void dispatchSoon() {
        Thread.ofVirtual().name("scrape-dispatch").inheritInheritableThreadLocals(false)
                .start(ScrapeJobService::dispatch);
    }

    /** One pass of the scheduler: settle what an earlier process left running, then start what waits. */
    public static void tickOnce() {
        reconcile();
        dispatch();
    }

    /** Start waiting jobs, oldest first, until every slot is taken. */
    public static void dispatch() {
        synchronized (LOCK) {
            int room = WebScrapeSettings.jobMaxConcurrent() - ACTIVE.size();
            if (room <= 0) return;
            var pending = Tx.run(() -> idsIn(ScrapeJob.State.PENDING));
            for (var id : pending) {
                if (room <= 0) break;
                if (ACTIVE.containsKey(id)) continue;
                var running = new Running(id);
                ACTIVE.put(id, running);
                // Not inheriting: the job outlives the turn that queued it, and must not carry that
                // turn's owner or task-fire origin into work it no longer supervises.
                Thread.ofVirtual().name("scrape-job-" + id).inheritInheritableThreadLocals(false)
                        .start(() -> run(running));
                room--;
            }
        }
    }

    /**
     * Mark every RUNNING job this process is not running as INTERRUPTED. Only an earlier process
     * can have left one: a job is in {@link #ACTIVE} before its row says RUNNING, and leaves it only
     * after its row says how it ended.
     *
     * @return how many were interrupted
     */
    public static int reconcile() {
        synchronized (LOCK) {
            var running = Tx.run(() -> idsIn(ScrapeJob.State.RUNNING));
            int interrupted = 0;
            for (var id : running) {
                if (ACTIVE.containsKey(id)) continue;
                if (Boolean.TRUE.equals(Tx.run(() -> interrupt(id)))) interrupted++;
            }
            return interrupted;
        }
    }

    private static List<Long> idsIn(ScrapeJob.State state) {
        List<ScrapeJob> jobs = ScrapeJob.find("state = ?1 ORDER BY id", state).fetch();
        var ids = new ArrayList<Long>(jobs.size());
        for (var job : jobs) ids.add(job.id);
        return ids;
    }

    private static boolean interrupt(Long id) {
        ScrapeJob job = ScrapeJob.findById(id);
        if (job == null || job.state != ScrapeJob.State.RUNNING) return false;
        var header = "Read %d page%s from %s before the app stopped.\n"
                .formatted(job.pagesRead, job.pagesRead == 1 ? "" : "s", job.url);
        writeCombinedQuietly(job, header);
        job.state = ScrapeJob.State.INTERRUPTED;
        job.stopReason = "the app stopped while it ran";
        job.completedAt = AppClock.now();
        job.save();
        EventLogger.warn(EVENT_CATEGORY, "Scrape job %d was running when the app stopped; marked interrupted".formatted(id));
        return true;
    }

    public enum CancelResult { STOPPING, CANCELLED, ALREADY_FINISHED, NOT_FOUND }

    /**
     * Stop a job. A running one finishes the fetches already in flight and starts no more; a waiting
     * one is cancelled without running.
     */
    public static CancelResult cancel(Long id) {
        synchronized (LOCK) {
            var running = ACTIVE.get(id);
            if (running != null) {
                running.stopRequested = true;
                return CancelResult.STOPPING;
            }
            return Tx.run(() -> {
                ScrapeJob job = ScrapeJob.findById(id);
                if (job == null) return CancelResult.NOT_FOUND;
                if (job.state.terminal()) return CancelResult.ALREADY_FINISHED;
                // PENDING, or a RUNNING row no process is running: neither has a crawl to wind down.
                job.state = ScrapeJob.State.CANCELLED;
                job.stopReason = STOPPED;
                job.completedAt = AppClock.now();
                job.save();
                COLLABORATORS.remove(id);
                return CancelResult.CANCELLED;
            });
        }
    }

    public enum DeleteResult { DELETED, NOT_FINISHED, NOT_FOUND }

    private record Deletion(DeleteResult result, @Nullable String agentName) {}

    /** Delete a finished job, its page rows and its folder. One still waiting or running is refused. */
    public static DeleteResult delete(Long id) {
        Deletion deletion;
        synchronized (LOCK) {
            if (ACTIVE.containsKey(id)) return DeleteResult.NOT_FINISHED;
            deletion = Tx.run(() -> {
                ScrapeJob job = ScrapeJob.findById(id);
                if (job == null) return new Deletion(DeleteResult.NOT_FOUND, null);
                if (!job.state.terminal()) return new Deletion(DeleteResult.NOT_FINISHED, null);
                var agentName = job.agent.name;
                ScrapeJobPage.delete("job = ?1", job);
                job.delete();
                return new Deletion(DeleteResult.DELETED, agentName);
            });
        }
        if (deletion.agentName() != null) ScrapeJobFiles.deleteFolder(deletion.agentName(), id);
        return deletion.result();
    }

    /** How the job ended, and what to tell its conversation. */
    private record Finished(Long jobId, @Nullable Long conversationId, boolean ownerInitiated, String message,
                            Map<String, Object> metadata) {}

    private record Start(ScrapeJobRequest request, String agentName) {}

    private static void run(Running running) {
        var id = running.jobId;
        var collaborators = COLLABORATORS.getOrDefault(id, PRODUCTION);
        Finished finished = null;
        try {
            var start = Tx.run(() -> markRunning(id));
            if (start != null) {
                var listener = new JobListener(id, start.agentName(), start.request().output().format(), running);
                WebScrapeTool.JobCrawl crawl = null;
                String crash = null;
                try {
                    crawl = collaborators.crawler().crawl(start.request(), listener);
                } catch (RuntimeException e) {
                    crash = "the crawl failed: " + reason(e);
                    EventLogger.warn(EVENT_CATEGORY, "Scrape job %d: %s".formatted(id, crash));
                }
                var crawled = crawl;
                var crashed = crash;
                finished = Tx.run(() -> finish(id, running, crawled, crashed));
            }
        } catch (RuntimeException e) {
            // The database went away beneath it; the next reconcile marks the row interrupted.
            EventLogger.warn(EVENT_CATEGORY, "Scrape job %d ended without its outcome recorded: %s"
                    .formatted(id, reason(e)));
        } finally {
            COLLABORATORS.remove(id);
            ACTIVE.remove(id);
            dispatch();
        }
        // After its slot is free: the completion turn is a model call, not part of the crawl.
        if (finished != null && finished.conversationId() != null) {
            complete(finished, collaborators.turn());
        }
    }

    /**
     * PENDING to RUNNING as one conditional update. A cancel made through the API commits with its
     * request, after {@link #LOCK} is released, so a read-then-write here could start a job that is
     * already cancelled; the update waits on that row instead and then matches nothing.
     */
    private static @Nullable Start markRunning(Long id) {
        int started = JPA.em().createQuery("UPDATE ScrapeJob j SET j.state = :running, j.startedAt = :now "
                        + "WHERE j.id = :id AND j.state = :pending")
                .setParameter("running", ScrapeJob.State.RUNNING)
                .setParameter("now", AppClock.now())
                .setParameter("id", id)
                .setParameter("pending", ScrapeJob.State.PENDING)
                .executeUpdate();
        if (started == 0) return null;
        ScrapeJob job = ScrapeJob.findById(id);
        if (job == null) return null;
        EventLogger.info(EVENT_CATEGORY, job.agent.name, null, "Scrape job %d started".formatted(id));
        return new Start(ScrapeJobRequest.fromJson(job.options), job.agent.name);
    }

    private static @Nullable Finished finish(Long id, Running running, WebScrapeTool.@Nullable JobCrawl crawl,
                                             @Nullable String crash) {
        ScrapeJob job = ScrapeJob.findById(id);
        if (job == null) return null;
        if (crawl != null) job.summary = crawl.summary();
        if (crash != null) {
            job.state = ScrapeJob.State.FAILED;
            job.errorMessage = crash;
        } else if (running.stopRequested) {
            job.state = ScrapeJob.State.CANCELLED;
            job.stopReason = STOPPED;
        } else if (job.pagesFetched > 0) {
            job.state = ScrapeJob.State.SUCCEEDED;
            job.stopReason = crawl == null ? null : crawl.stoppedBecause();
        } else {
            job.state = ScrapeJob.State.FAILED;
            job.errorMessage = nothingRead(job, crawl);
        }
        // Before the state is committed, so a reader who sees the job end can download the file.
        writeCombinedQuietly(job, job.summary != null ? job.summary
                : "Read %d pages from %s.\n".formatted(job.pagesRead, job.url));
        job.completedAt = AppClock.now();
        job.save();
        EventLogger.info(EVENT_CATEGORY, job.agent.name, null, "Scrape job %d %s: %d of %d pages read"
                .formatted(id, job.state.name().toLowerCase(Locale.ROOT), job.pagesFetched, job.pagesRead));
        return new Finished(id, job.conversation == null ? null : job.conversation.id, job.ownerInitiated,
                completionMessage(job), completionMetadata(job));
    }

    /** Why a job that retrieved nothing failed, told from its first page or the refusal of its seed. */
    private static String nothingRead(ScrapeJob job, WebScrapeTool.@Nullable JobCrawl crawl) {
        ScrapeJobPage first = ScrapeJobPage.find("job = ?1 ORDER BY pageIndex", job).first();
        if (first != null) {
            return "No page could be read: the starting page was %s (%s)."
                    .formatted(first.outcome.name().toLowerCase(Locale.ROOT), first.reason);
        }
        if (crawl != null && crawl.firstRefusal() != null) {
            return "The starting URL was refused: " + crawl.firstRefusal() + ".";
        }
        return "No page was read.";
    }

    private static void writeCombinedQuietly(ScrapeJob job, String header) {
        var format = ScrapeJobRequest.fromJson(job.options).output().format();
        List<ScrapeJobPage> pages = ScrapeJobPage.find("job = ?1 ORDER BY pageIndex", job).fetch();
        try {
            ScrapeJobFiles.writeCombined(job, new ArrayList<>(pages), format, header);
        } catch (IOException | SecurityException e) {
            EventLogger.warn(EVENT_CATEGORY, "Scrape job %d: could not write its combined file: %s"
                    .formatted(job.id, e.getMessage()));
        }
    }

    private static String completionMessage(ScrapeJob job) {
        var format = ScrapeJobRequest.fromJson(job.options).output().format();
        var where = "Its pages are in the workspace folder '%s', combined in '%s'."
                .formatted(folder(job.id), combinedFile(job.id, format));
        var head = switch (job.state) {
            case SUCCEEDED -> "Background scrape job %d finished: %d of %d pages retrieved from %s. %s"
                    .formatted(job.id, job.pagesFetched, job.pagesRead, job.url, where);
            case CANCELLED -> "Background scrape job %d was stopped after %d of %d pages were retrieved from %s. %s"
                    .formatted(job.id, job.pagesFetched, job.pagesRead, job.url, where);
            default -> "Background scrape job %d for %s failed: %s"
                    .formatted(job.id, job.url, job.errorMessage);
        };
        return job.summary == null ? head : head + "\n\n" + job.summary.strip();
    }

    private static Map<String, Object> completionMetadata(ScrapeJob job) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("jobId", job.id);
        metadata.put("state", job.state.name());
        metadata.put("url", job.url);
        metadata.put("pagesRead", job.pagesRead);
        metadata.put("pagesFetched", job.pagesFetched);
        metadata.put("folder", folder(job.id));
        return metadata;
    }

    /** Post the completion message into the job's conversation and hand the conversation a turn. */
    private static void complete(Finished finished, CompletionTurn turn) {
        record Posted(Agent agent, Conversation conversation) {}
        Posted posted;
        try {
            posted = Tx.run(() -> {
                Conversation conversation = Conversation.findById(finished.conversationId());
                if (conversation == null) return null;
                // USER-role, as a yielded subagent's announce is: loadRecentMessages hands the model
                // USER rows that carry a messageKind, so the turn below reads it.
                var message = ConversationService.appendMessage(conversation, MessageRole.USER,
                        finished.message(), null, null, null);
                message.messageKind = MESSAGE_KIND_COMPLETE;
                message.metadata = GsonHolder.GSON.toJson(finished.metadata());
                message.save();
                return new Posted(conversation.agent, conversation);
            });
        } catch (RuntimeException e) {
            EventLogger.warn(EVENT_CATEGORY, "Scrape job %d: could not post its completion message: %s"
                    .formatted(finished.jobId(), reason(e)));
            return;
        }
        if (posted == null) return;
        try {
            turn.run(posted.agent(), posted.conversation(), finished.ownerInitiated());
        } catch (RuntimeException e) {
            EventLogger.warn(EVENT_CATEGORY, "Scrape job %d: the completion turn failed: %s"
                    .formatted(finished.jobId(), reason(e)));
        }
    }

    private static void runCompletionTurn(Agent agent, Conversation conversation, boolean ownerInitiated) {
        DangerousActionGate.withOwnerInitiated(ownerInitiated, () -> {
            AgentRunner.resumeAndDeliver(agent, conversation);
            return null;
        });
    }

    private static String reason(Exception e) {
        var message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    /** Writes each page to disk and records it as it lands. Called on the crawl's own thread. */
    private static final class JobListener implements CrawlListener {
        private final Long jobId;
        private final String agentName;
        private final ScrapeOutput.Format format;
        private final Running running;
        private int pageIndex;

        JobListener(Long jobId, String agentName, ScrapeOutput.Format format, Running running) {
            this.jobId = jobId;
            this.agentName = agentName;
            this.format = format;
            this.running = running;
        }

        @Override
        public void page(Page page) {
            int index = ++pageIndex;
            String file = null;
            var content = page.content();
            if (content != null) {
                file = ScrapeJobFiles.pageFile(jobId, index, format);
                ScrapeJobFiles.writePage(agentName, file, content);
            }
            var path = file;
            var recorded = Tx.run(() -> {
                ScrapeJob job = ScrapeJob.findById(jobId);
                if (job == null) return false;
                var row = new ScrapeJobPage();
                row.job = job;
                row.pageIndex = index;
                row.url = page.url();
                row.crawlDepth = page.depth();
                row.servedBy = page.servedBy();
                row.outcome = ScrapeJobPage.Outcome.of(page.reason());
                row.reason = page.reason() == ScrapeReason.OK ? null : page.reason().name();
                row.chars = content == null ? 0 : content.length();
                row.file = path;
                row.fetchedAt = AppClock.now();
                row.save();
                job.pagesRead++;
                if (row.outcome == ScrapeJobPage.Outcome.FETCHED) job.pagesFetched++;
                job.save();
                return true;
            });
            // Gone with its agent: nothing left to write to.
            if (!Boolean.TRUE.equals(recorded)) running.stopRequested = true;
        }

        @Override
        public void discovered(int urls) {
            Tx.run(() -> {
                ScrapeJob job = ScrapeJob.findById(jobId);
                if (job != null) {
                    job.pagesDiscovered = urls;
                    job.save();
                }
            });
        }

        @Override
        public boolean stopRequested() {
            return running.stopRequested;
        }
    }
}
