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
import tools.scrape.PageHarvest;
import tools.scrape.ScrapeJobRequest;
import tools.scrape.ScrapeOutput;
import tools.scrape.WebScrapeSettings;
import utils.AppClock;
import utils.GsonHolder;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
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
 * owns a thread, and a restart stops it. {@link #ACTIVE} is this process's own record of which jobs
 * it is running, which is what lets {@link #reconcile} tell a live RUNNING row from one an earlier
 * process left behind without guessing from its age.
 *
 * <p>A job is stopped, to cancel or to pause it, with a flag its crawl reads before each fetch, never
 * by interrupting its thread: the thread writes to H2, and an interrupt closes the database file's
 * channel. Every page it reads is kept with what the crawl learned from it, so a job that stopped
 * short continues from the pages it has rather than from its seed.
 */
public final class ScrapeJobService {

    /** {@code Message.messageKind} of the message that tells a conversation its job ended. */
    public static final String MESSAGE_KIND_COMPLETE = "scrape_job_complete";

    /**
     * A job the app leaves running is continued on its own until this many interruptions, then waits to
     * be resumed, so a job that takes the app down with it cannot do so for ever.
     */
    public static final int MAX_INTERRUPTIONS = 3;

    private static final String EVENT_CATEGORY = "scrape";
    private static final String STOPPED = "stopped on request";
    private static final String PAUSED = "paused";

    /** Runs a job's crawl, from the start or from where an earlier run of it stopped. */
    @FunctionalInterface
    public interface Crawler {
        WebScrapeTool.JobCrawl crawl(ScrapeJobRequest request, CrawlListener listener, CrawlListener.Resume resume);
    }

    /** What happens after the completion message is posted: a turn that reads it and answers. */
    @FunctionalInterface
    public interface CompletionTurn {
        void run(Agent agent, Conversation conversation, boolean ownerInitiated);
    }

    private record Collaborators(Crawler crawler, CompletionTurn turn) {}

    private static final Collaborators PRODUCTION = new Collaborators(
            (request, listener, resume) -> new WebScrapeTool().crawlForJob(request, listener, resume),
            ScrapeJobService::runCompletionTurn);

    /**
     * Chosen when a job is submitted and kept until it ends, so however often it is paused and resumed
     * it runs with the same ones. That is what lets a test drive its own jobs while another test class
     * submits real ones in the same JVM. A process that restarts has none, and uses {@link #PRODUCTION}.
     */
    private static final Map<Long, Collaborators> COLLABORATORS = new ConcurrentHashMap<>();

    private static final Map<Long, Running> ACTIVE = new ConcurrentHashMap<>();

    /** Held while jobs are started, stopped, paused, reconciled or deleted, so none of those interleave. */
    private static final Object LOCK = new Object();

    private enum Stop { NONE, PAUSE, CANCEL }

    private static final class Running {
        final Long jobId;
        volatile Stop stop = Stop.NONE;

        Running(Long jobId) {
            this.jobId = jobId;
        }

        /** A cancel outranks a pause asked for before it. */
        void request(Stop requested) {
            if (stop != Stop.CANCEL) stop = requested;
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

    /** One pass of the scheduler: continue what an earlier process left running, then start what waits. */
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
     * Continue every RUNNING job this process is not running. Only an earlier process, or a run whose
     * outcome could not be recorded, leaves one: a job is in {@link #ACTIVE} before its row says
     * RUNNING, and leaves it only after its row says how it stopped. It goes back to the queue and
     * resumes from its pages, until {@link #MAX_INTERRUPTIONS}, when it is INTERRUPTED instead.
     *
     * @return how many were found
     */
    public static int reconcile() {
        synchronized (LOCK) {
            var running = Tx.run(() -> idsIn(ScrapeJob.State.RUNNING));
            int found = 0;
            for (var id : running) {
                if (ACTIVE.containsKey(id)) continue;
                if (Boolean.TRUE.equals(Tx.run(() -> requeue(id)))) found++;
            }
            return found;
        }
    }

    private static List<Long> idsIn(ScrapeJob.State state) {
        List<ScrapeJob> jobs = ScrapeJob.find("state = ?1 ORDER BY id", state).fetch();
        var ids = new ArrayList<Long>(jobs.size());
        for (var job : jobs) ids.add(job.id);
        return ids;
    }

    private static boolean requeue(Long id) {
        ScrapeJob job = ScrapeJob.findById(id);
        if (job == null || job.state != ScrapeJob.State.RUNNING) return false;
        job.interruptions++;
        if (job.interruptions >= MAX_INTERRUPTIONS) {
            writeCombinedQuietly(job, "Read %d page%s from %s before it was interrupted.\n"
                    .formatted(job.pagesRead, job.pagesRead == 1 ? "" : "s", job.url));
            job.state = ScrapeJob.State.INTERRUPTED;
            job.stopReason = "interrupted %d times while running; resume it to continue".formatted(job.interruptions);
            EventLogger.warn(EVENT_CATEGORY, "Scrape job %d was left running %d times; it waits to be resumed"
                    .formatted(id, job.interruptions));
        } else {
            job.state = ScrapeJob.State.PENDING;
            job.stopReason = null;
            EventLogger.info(EVENT_CATEGORY, "Scrape job %d was left running; it continues from its %d pages"
                    .formatted(id, job.pagesRead));
        }
        job.save();
        return true;
    }

    public enum PauseResult { PAUSING, PAUSED, NOT_ACTIVE, NOT_FOUND }

    /**
     * Pause a job. A running one finishes the fetches already in flight, starts no more, and keeps its
     * place; a waiting one leaves the queue until it is resumed.
     */
    public static PauseResult pause(Long id) {
        synchronized (LOCK) {
            var running = ACTIVE.get(id);
            if (running != null) {
                running.request(Stop.PAUSE);
                return PauseResult.PAUSING;
            }
            return Tx.run(() -> {
                ScrapeJob job = ScrapeJob.findById(id);
                if (job == null) return PauseResult.NOT_FOUND;
                // PENDING, or a RUNNING row no process is running: neither has a crawl to wind down.
                if (!job.state.active()) return PauseResult.NOT_ACTIVE;
                job.state = ScrapeJob.State.PAUSED;
                job.stopReason = PAUSED;
                job.save();
                return PauseResult.PAUSED;
            });
        }
    }

    public enum ResumeResult { RESUMED, NOT_PAUSED, NOT_FOUND }

    /** Put a paused or interrupted job back in the queue; it continues from the pages it has. */
    public static ResumeResult resume(Long id) {
        synchronized (LOCK) {
            return Tx.run(() -> {
                ScrapeJob job = ScrapeJob.findById(id);
                if (job == null) return ResumeResult.NOT_FOUND;
                if (job.state != ScrapeJob.State.PAUSED && job.state != ScrapeJob.State.INTERRUPTED) {
                    return ResumeResult.NOT_PAUSED;
                }
                job.state = ScrapeJob.State.PENDING;
                job.stopReason = null;
                job.interruptions = 0;
                job.save();
                Tx.afterCommit(ScrapeJobService::dispatchSoon);
                return ResumeResult.RESUMED;
            });
        }
    }

    public enum CancelResult { STOPPING, CANCELLED, ALREADY_FINISHED, NOT_FOUND }

    /**
     * End a job for good. A running one finishes the fetches already in flight and starts no more; a
     * waiting, paused or interrupted one ends where it is.
     */
    public static CancelResult cancel(Long id) {
        synchronized (LOCK) {
            var running = ACTIVE.get(id);
            if (running != null) {
                running.request(Stop.CANCEL);
                return CancelResult.STOPPING;
            }
            return Tx.run(() -> {
                ScrapeJob job = ScrapeJob.findById(id);
                if (job == null) return CancelResult.NOT_FOUND;
                if (job.state.terminal()) return CancelResult.ALREADY_FINISHED;
                if (job.pagesRead > 0) writeCombinedQuietly(job, header(job));
                job.state = ScrapeJob.State.CANCELLED;
                job.stopReason = STOPPED;
                job.completedAt = AppClock.now();
                release(job);
                job.save();
                return CancelResult.CANCELLED;
            });
        }
    }

    public enum DeleteResult { DELETED, NOT_FINISHED, NOT_FOUND }

    private record Deletion(DeleteResult result, @Nullable String agentName) {}

    /** Delete a job that is not queued or running, with its page rows and its folder. */
    public static DeleteResult delete(Long id) {
        Deletion deletion;
        synchronized (LOCK) {
            if (ACTIVE.containsKey(id)) return DeleteResult.NOT_FINISHED;
            deletion = Tx.run(() -> {
                ScrapeJob job = ScrapeJob.findById(id);
                if (job == null) return new Deletion(DeleteResult.NOT_FOUND, null);
                if (job.state.active()) return new Deletion(DeleteResult.NOT_FINISHED, null);
                var agentName = job.agent.name;
                ScrapeJobPage.delete("job = ?1", job);
                job.delete();
                COLLABORATORS.remove(id);
                return new Deletion(DeleteResult.DELETED, agentName);
            });
        }
        if (deletion.agentName() != null) ScrapeJobFiles.deleteFolder(deletion.agentName(), id);
        return deletion.result();
    }

    /** How the job ended, and what to tell its conversation. */
    private record Finished(Long jobId, @Nullable Long conversationId, boolean ownerInitiated, String message,
                            Map<String, Object> metadata) {}

    /**
     * @param lastIndex the highest page index an earlier run recorded, which this run continues after
     */
    private record Start(ScrapeJobRequest request, String agentName, CrawlListener.Resume resume, int lastIndex,
                         long runtimeMillis) {}

    private static void run(Running running) {
        var id = running.jobId;
        var collaborators = COLLABORATORS.getOrDefault(id, PRODUCTION);
        Finished finished = null;
        try {
            var start = Tx.run(() -> markRunning(id));
            if (start != null) {
                var listener = new JobListener(id, start, running);
                WebScrapeTool.JobCrawl crawl = null;
                String crash = null;
                try {
                    crawl = collaborators.crawler().crawl(start.request(), listener, start.resume());
                } catch (RuntimeException e) {
                    crash = "the crawl failed: " + reason(e);
                    EventLogger.warn(EVENT_CATEGORY, "Scrape job %d: %s".formatted(id, crash));
                }
                var crawled = crawl;
                var crashed = crash;
                finished = Tx.run(() -> finish(id, running, listener, crawled, crashed));
            }
        } catch (RuntimeException e) {
            // The database went away beneath it; the next reconcile puts the row back in the queue.
            EventLogger.warn(EVENT_CATEGORY, "Scrape job %d stopped without its outcome recorded: %s"
                    .formatted(id, reason(e)));
        } finally {
            ACTIVE.remove(id);
            dispatch();
        }
        // After its slot is free: the completion turn is a model call, not part of the crawl.
        if (finished != null && finished.conversationId() != null) {
            complete(finished, collaborators.turn());
        }
    }

    /**
     * PENDING to RUNNING as one conditional update, then what the crawl needs to pick up where an
     * earlier run stopped. A cancel or pause made through the API commits with its request, after
     * {@link #LOCK} is released, so a read-then-write here could start a job that is no longer
     * waiting; the update waits on that row instead and then matches nothing.
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
        var request = ScrapeJobRequest.fromJson(job.options);
        List<ScrapeJobPage> pages = ScrapeJobPage.find("job = ?1 ORDER BY pageIndex", job).fetch();
        var recorded = new HashMap<String, CrawlListener.Recorded>();
        int lastIndex = 0;
        for (var page : pages) {
            lastIndex = Math.max(lastIndex, page.pageIndex);
            if (page.requestedUrl == null) continue;
            recorded.put(page.requestedUrl, new CrawlListener.Recorded(page.servedBy,
                    page.harvest == null ? null : PageHarvest.fromJson(page.harvest)));
        }
        var timeLeft = Duration.ofMinutes(request.maxMinutes()).minusMillis(job.runtimeMillis);
        EventLogger.info(EVENT_CATEGORY, job.agent.name, null, pages.isEmpty()
                ? "Scrape job %d started".formatted(id)
                : "Scrape job %d resumed after %d pages".formatted(id, pages.size()));
        return new Start(request, job.agent.name,
                new CrawlListener.Resume(recorded, timeLeft.isNegative() ? Duration.ZERO : timeLeft),
                lastIndex, job.runtimeMillis);
    }

    /** Record how the run stopped. Null when there is nothing to tell a conversation: paused, or gone. */
    private static @Nullable Finished finish(Long id, Running running, JobListener listener,
                                             WebScrapeTool.@Nullable JobCrawl crawl, @Nullable String crash) {
        ScrapeJob job = ScrapeJob.findById(id);
        if (job == null) {
            COLLABORATORS.remove(id);
            return null;
        }
        job.runtimeMillis = listener.runtimeMillis();
        if (crawl != null) job.summary = crawl.summary();
        if (crash == null && running.stop == Stop.PAUSE) {
            job.state = ScrapeJob.State.PAUSED;
            job.stopReason = PAUSED;
            writeCombinedQuietly(job, header(job));
            job.save();
            EventLogger.info(EVENT_CATEGORY, job.agent.name, null,
                    "Scrape job %d paused after %d pages".formatted(id, job.pagesRead));
            return null;
        }
        if (crash != null) {
            job.state = ScrapeJob.State.FAILED;
            job.errorMessage = crash;
        } else if (running.stop == Stop.CANCEL) {
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
        writeCombinedQuietly(job, header(job));
        job.completedAt = AppClock.now();
        release(job);
        job.save();
        EventLogger.info(EVENT_CATEGORY, job.agent.name, null, "Scrape job %d %s: %d of %d pages read"
                .formatted(id, job.state.name().toLowerCase(Locale.ROOT), job.pagesFetched, job.pagesRead));
        return new Finished(id, job.conversation == null ? null : job.conversation.id, job.ownerInitiated,
                completionMessage(job), completionMetadata(job));
    }

    /** A job that has ended keeps its pages but no longer needs what a resume would replay. */
    private static void release(ScrapeJob job) {
        JPA.em().createQuery("UPDATE ScrapeJobPage p SET p.harvest = NULL WHERE p.job = :job")
                .setParameter("job", job)
                .executeUpdate();
        COLLABORATORS.remove(job.id);
    }

    private static String header(ScrapeJob job) {
        return job.summary != null ? job.summary : "Read %d pages from %s.\n".formatted(job.pagesRead, job.url);
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
        private final long runtimeBefore;
        private final long startedNanos = System.nanoTime();
        private int pageIndex;

        JobListener(Long jobId, Start start, Running running) {
            this.jobId = jobId;
            this.agentName = start.agentName();
            this.format = start.request().output().format();
            this.running = running;
            this.runtimeBefore = start.runtimeMillis();
            this.pageIndex = start.lastIndex();
        }

        long runtimeMillis() {
            return runtimeBefore + Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
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
            var harvest = page.harvest();
            var recorded = Tx.run(() -> {
                ScrapeJob job = ScrapeJob.findById(jobId);
                if (job == null) return false;
                var row = new ScrapeJobPage();
                row.job = job;
                row.pageIndex = index;
                row.url = page.url();
                row.requestedUrl = page.requested();
                row.crawlDepth = page.depth();
                row.servedBy = page.servedBy();
                row.outcome = ScrapeJobPage.Outcome.of(page.reason());
                row.reason = page.reason() == ScrapeReason.OK ? null : page.reason().name();
                row.chars = content == null ? 0 : content.length();
                row.file = path;
                row.harvest = harvest == null ? null : harvest.toJson();
                row.fetchedAt = AppClock.now();
                row.save();
                job.pagesRead++;
                if (row.outcome == ScrapeJobPage.Outcome.FETCHED) job.pagesFetched++;
                job.runtimeMillis = runtimeMillis();
                job.save();
                return true;
            });
            // Gone with its agent: nothing left to write to.
            if (!Boolean.TRUE.equals(recorded)) running.request(Stop.CANCEL);
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
            return running.stop != Stop.NONE;
        }
    }
}
