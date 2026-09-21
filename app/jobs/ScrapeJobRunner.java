package jobs;

import play.Play;
import play.jobs.Every;
import play.jobs.Job;
import services.scrape.ScrapeJobService;

/**
 * JCLAW-1272: every 5s, marks a scrape job an earlier process left running as interrupted and
 * starts waiting jobs into free slots.
 *
 * <p>A submitted job starts at once when a slot is free, and a finishing job starts the next, so
 * this is the backstop: it catches the first pass after a restart and anything a failed hand-off
 * left waiting. Inert in test mode so tests drive {@link ScrapeJobService#tickOnce} themselves.
 */
@Every("5s")
public class ScrapeJobRunner extends Job<Void> {

    @Override
    public void doJob() {
        if (Play.runningInTestMode()) return;
        ScrapeJobService.tickOnce();
    }
}
