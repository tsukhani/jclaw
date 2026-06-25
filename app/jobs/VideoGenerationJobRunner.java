package jobs;

import play.Play;
import play.jobs.Every;
import play.jobs.Job;
import services.videogen.VideoGenerationJobService;

/**
 * JCLAW-230: singleton poll loop for asynchronous video-generation jobs. Every 5s it advances each
 * {@code RUNNING} {@link models.VideoGenerationJob} — polling its provider, transitioning to
 * SUCCEEDED/FAILED, or timing it out.
 *
 * <p>Inert in test mode so functional tests drive {@link VideoGenerationJobService#tickOnce}
 * deterministically without the scheduler racing them (and without the background loop hitting the real
 * provider API against test fixtures). Runs on Play's scheduled job pool (a platform-thread
 * {@code ScheduledThreadPoolExecutor}), not a swarm of sleeping virtual threads — deliberately, to
 * avoid the JDK-25 VT {@code Thread.sleep} starvation footgun.
 */
@Every("5s")
public class VideoGenerationJobRunner extends Job<Void> {

    @Override
    public void doJob() {
        if (Play.runningInTestMode()) return;
        VideoGenerationJobService.tickOnce();
    }
}
