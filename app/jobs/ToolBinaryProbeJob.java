package jobs;

import play.db.jpa.NoTransaction;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.UvProbe;
import services.transcription.FfmpegProbe;

/**
 * Primes the ffmpeg and uv caches so the Settings pages can show a "missing" banner without paying the probe on the
 * first load (JCLAW-163, JCLAW-226). Off the boot thread (JF-270): the two probes held the port closed for about
 * 130 ms, and every reader that comes first probes through {@code isAvailable} or {@code probe} itself.
 */
@OnApplicationStart(async = true)
@NoTransaction
public class ToolBinaryProbeJob extends Job<Void> {

    @Override
    public void doJob() {
        FfmpegProbe.probe();
        UvProbe.probe();
    }
}
