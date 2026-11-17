package jobs;

import play.jobs.Every;
import play.jobs.OnApplicationStart;
import services.TimezoneResolver;
import services.Tx;
import services.database.DatabaseGrowth;
import utils.AppClock;

import java.time.LocalDate;

/**
 * JF-496: one sample a day of each table's size and the data file's, for the database card's growth table. The hourly
 * check writes only when the app-zone day has none, so a restart or a missed hour never skips a day.
 */
// @Every alone first fires a full interval after boot (JCLAW-1067); the start run takes the day's sample at once.
@OnApplicationStart(async = true)
@Every("1h")
public class DatabaseSizeSampleJob extends PeriodicJob {

    @Override
    public void doJob() {
        var today = LocalDate.ofInstant(AppClock.now(), TimezoneResolver.appZone());
        Tx.run(() -> DatabaseGrowth.sampleIfDue(today));
    }
}
