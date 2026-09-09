package services.database;

import org.jspecify.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * The database verdict (JCLAW-1165): what the H2 trace file has been saying, plus a live probe.
 * Pure over its inputs so the thresholds are testable against a trace snippet and a clock.
 */
public final class DatabaseHealth {

    /** The MVStore messages that preceded the 2026-09-09 loss, in the order they escalated. */
    static final List<String> CORRUPTION_PHRASES = List.of(
            "File corrupted", "Unable to read the page", "has been closed");

    // The trace file stamps each entry "2026-09-09 10:30:49.557839+08:00 jdbc[3]: ..."; the
    // exception text follows on later lines, so a phrase is dated by the last stamp seen.
    private static final DateTimeFormatter TRACE_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSxxx");
    private static final int STAMP_LENGTH = "2026-09-09 10:30:49.557839+08:00".length();

    public enum Verdict { HEALTHY, ATTENTION, CRITICAL }

    public record CorruptionEvent(Instant at, String phrase) {}

    /**
     * @param last24h events since {@code now - 24h}
     * @param last7d  events since {@code now - 7d}
     * @param firstAt the earliest event in the 7-day window, or null
     */
    public record TraceSummary(int last24h, int last7d, @Nullable Instant firstAt) {}

    public record Assessment(Verdict verdict, String reason, TraceSummary trace, @Nullable Long probeMs,
                             @Nullable String probeError) {}

    private DatabaseHealth() {}

    /** Every corruption phrase in the trace file, dated by the stamp that preceded it. */
    public static List<CorruptionEvent> corruptionEvents(Path traceFile) throws IOException {
        if (!Files.isRegularFile(traceFile)) {
            return List.of();
        }
        var events = new ArrayList<CorruptionEvent>();
        try (BufferedReader reader = Files.newBufferedReader(traceFile, StandardCharsets.UTF_8)) {
            Instant current = null;
            String line;
            while ((line = reader.readLine()) != null) {
                var stamp = stampOf(line);
                if (stamp != null) {
                    current = stamp;
                }
                if (current == null) {
                    continue;
                }
                for (var phrase : CORRUPTION_PHRASES) {
                    if (line.contains(phrase)) {
                        events.add(new CorruptionEvent(current, phrase));
                        break;
                    }
                }
            }
        }
        return events;
    }

    static @Nullable Instant stampOf(String line) {
        if (line.length() < STAMP_LENGTH || !Character.isDigit(line.charAt(0))) {
            return null;
        }
        try {
            return OffsetDateTime.parse(line.substring(0, STAMP_LENGTH), TRACE_STAMP).toInstant();
        } catch (DateTimeParseException _) {
            return null;
        }
    }

    public static TraceSummary summarize(List<CorruptionEvent> events, Instant now) {
        var day = now.minus(Duration.ofHours(24));
        var week = now.minus(Duration.ofDays(7));
        var last24h = 0;
        var last7d = 0;
        Instant first = null;
        for (var e : events) {
            if (e.at().isBefore(week)) {
                continue;
            }
            last7d++;
            if (!e.at().isBefore(day)) {
                last24h++;
            }
            if (first == null || e.at().isBefore(first)) {
                first = e.at();
            }
        }
        return new TraceSummary(last24h, last7d, first);
    }

    /** True when the trace recorded a corruption message at or after {@code since}. */
    public static boolean anyEventSince(List<CorruptionEvent> events, Instant since) {
        return events.stream().anyMatch(e -> !e.at().isBefore(since));
    }

    /**
     * Critical when the probe failed — the database is closed or unreadable and every request
     * is failing with it; Attention when the trace shows read failures in the last day, which
     * is how the July damage announced itself for two months before it closed the database.
     */
    public static Assessment assess(TraceSummary trace, @Nullable Long probeMs, @Nullable String probeError) {
        if (probeError != null) {
            return new Assessment(Verdict.CRITICAL,
                    "The database is not answering queries (" + probeError + "). "
                            + "Repair rebuilds it from what is still readable; Restore replaces it with a backup.",
                    trace, probeMs, probeError);
        }
        if (trace.last24h() > 0) {
            return new Assessment(Verdict.ATTENTION,
                    trace.last24h() + " read failure" + (trace.last24h() == 1 ? "" : "s")
                            + " logged in the last 24 hours (" + trace.last7d() + " in 7 days). Pages are going bad; "
                            + "back up now, then Repair before a read of a damaged page closes the database.",
                    trace, probeMs, null);
        }
        if (trace.last7d() > 0) {
            return new Assessment(Verdict.ATTENTION,
                    trace.last7d() + " read failure" + (trace.last7d() == 1 ? "" : "s")
                            + " logged in the last 7 days. Back up now and consider Repair.",
                    trace, probeMs, null);
        }
        return new Assessment(Verdict.HEALTHY, "Queries answer and the trace file shows no read failures in 7 days.",
                trace, probeMs, null);
    }
}
