package services.video;

import java.util.Locale;

/**
 * Format a number of seconds as a {@code hh:mm:ss} timecode for the Tier-2 frame
 * timestamp list (JCLAW-221) and the Tier-3 temporal summary lines (JCLAW-222).
 */
final class VideoTimecode {

    private VideoTimecode() {}

    static String format(double seconds) {
        long total = (long) Math.floor(Math.max(0, seconds));
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", h, m, s);
    }
}
