package tools;

import com.google.gson.JsonObject;
import models.Agent;
import org.jspecify.annotations.Nullable;
import services.AgentService;
import services.ChromeBridge;
import services.ChromeBridgePolicy;
import utils.AnimatedImages;
import utils.AppClock;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records what an agent does in a tab as an animated image. A frame is a small screenshot taken after each
 * action that changes the page, so the recording shows the session step by step. Everything about its size is
 * capped: the frame count, the width of a frame, the bytes held, how long a recording may sit unfinished, and how
 * many run at once. A frame from a page the agent may not read is dropped, as a screenshot would be refused.
 */
final class ChromeRecorder {

    static final int DEFAULT_FRAMES = 30;
    static final int MAX_FRAMES = 100;
    static final int FRAME_WIDTH = 800;
    static final int WEBP_QUALITY = 60;
    static final int DEFAULT_FRAME_MS = 700;
    static final int MIN_FRAME_MS = 100;
    static final int MAX_FRAME_MS = 5000;
    static final int MAX_RECORDINGS = 4;
    static final long MAX_BYTES = 25L * 1024 * 1024;
    static final long EXPIRY_MS = 20L * 60 * 1000;

    private static final String ERROR = ChromeControlTool.ERROR;

    private static final ConcurrentHashMap<String, Recording> RECORDINGS = new ConcurrentHashMap<>();

    private ChromeRecorder() {}

    /** One recording in progress; its fields are touched only under its own lock. */
    private static final class Recording {
        final boolean webp;
        final boolean gif;
        final int maxFrames;
        final int frameMs;
        final long startedAt = AppClock.now().toEpochMilli();
        final List<byte[]> webpFrames = new ArrayList<>();
        final List<byte[]> pngFrames = new ArrayList<>();
        long bytes;
        int dropped;

        Recording(boolean webp, boolean gif, int maxFrames, int frameMs) {
            this.webp = webp;
            this.gif = gif;
            this.maxFrames = maxFrames;
            this.frameMs = frameMs;
        }

        int frames() {
            return webp ? webpFrames.size() : pngFrames.size();
        }

        boolean full() {
            return frames() >= maxFrames || bytes >= MAX_BYTES;
        }
    }

    private static String key(Agent agent, long tab) {
        return agent.name + ":" + tab;
    }

    /** Begins recording {@code tab}, with its first frame. */
    static String start(Agent agent, long tab, JsonObject args) {
        prune();
        var format = ChromeControlTool.string(args, "format");
        if (format == null) format = "webp";
        if (!List.of("webp", "gif", "both").contains(format)) return ERROR + "format must be webp, gif or both.";
        var asked = ChromeControlTool.longOf(args, "maxFrames");
        var frames = asked == null ? DEFAULT_FRAMES : (int) Math.max(2, Math.min(MAX_FRAMES, asked));
        var pace = ChromeControlTool.longOf(args, "frameMs");
        var frameMs = pace == null ? DEFAULT_FRAME_MS : (int) Math.max(MIN_FRAME_MS, Math.min(MAX_FRAME_MS, pace));
        var recording = new Recording(!format.equals("gif"), !format.equals("webp"), frames, frameMs);
        var key = key(agent, tab);
        if (RECORDINGS.size() >= MAX_RECORDINGS && !RECORDINGS.containsKey(key)) {
            return ERROR + "too many recordings are running; stop one first.";
        }
        if (RECORDINGS.putIfAbsent(key, recording) != null) return ERROR + "that tab is already being recorded.";
        try {
            if (!capture(agent, tab, recording)) {
                RECORDINGS.remove(key);
                return ERROR + "the first frame could not be captured.";
            }
        } catch (RuntimeException e) {
            RECORDINGS.remove(key);
            throw e;
        }
        return "Recording tab " + tab + " as " + format + ": up to " + frames + " frames, " + frameMs + " ms each, "
                + FRAME_WIDTH + " px wide at most. A frame is added after each click, key, typing, scroll, form change,"
                + " drag and navigation. Stop it with record_stop.";
    }

    /** Adds a frame after an action, if this tab is being recorded. Never fails the action it follows. */
    static void afterAction(Agent agent, long tab) {
        var recording = RECORDINGS.get(key(agent, tab));
        if (recording == null) return;
        try {
            if (!capture(agent, tab, recording)) count(recording);
        } catch (ChromeBridge.BridgeException _) {
            count(recording);
        }
    }

    /** Ends a recording and saves it. */
    static String stop(Agent agent, long tab) {
        var recording = RECORDINGS.remove(key(agent, tab));
        if (recording == null) return ERROR + "no recording is running on that tab.";
        try {
            synchronized (recording) {
                if (!recording.full()) capture(agent, tab, recording);
            }
        } catch (ChromeBridge.BridgeException _) {
            count(recording);
        }
        return save(agent, recording);
    }

    /** Drops a recording without saving it, for a tab that was closed. */
    static void discard(Agent agent, long tab) {
        RECORDINGS.remove(key(agent, tab));
    }

    private static void count(Recording recording) {
        synchronized (recording) {
            recording.dropped++;
        }
    }

    private static void prune() {
        var cutoff = AppClock.now().toEpochMilli() - EXPIRY_MS;
        RECORDINGS.values().removeIf(r -> r.startedAt < cutoff);
    }

    /** One frame in each format the recording wants, kept only if every one was captured. */
    private static boolean capture(Agent agent, long tab, Recording recording) {
        synchronized (recording) {
            if (recording.full()) return true;
            byte[] webp = null;
            byte[] png = null;
            if (recording.webp) {
                webp = grab(agent, tab, "webp");
                if (webp == null) return false;
            }
            if (recording.gif) {
                png = grab(agent, tab, "png");
                if (png == null) return false;
            }
            if (webp != null) {
                recording.webpFrames.add(webp);
                recording.bytes += webp.length;
            }
            if (png != null) {
                recording.pngFrames.add(png);
                recording.bytes += png.length;
            }
            return true;
        }
    }

    private static byte @Nullable [] grab(Agent agent, long tab, String format) {
        var call = ChromeControlTool.withTab(tab);
        call.addProperty("format", format);
        call.addProperty("maxWidth", FRAME_WIDTH);
        call.addProperty("quality", WEBP_QUALITY);
        var shot = ChromeBridge.call("screenshot", call).getAsJsonObject();
        var url = ChromeControlTool.string(shot, "url");
        if (url == null || ChromeBridgePolicy.visitRefusal(agent, url) != null) return null;
        var data = ChromeControlTool.string(shot, "data");
        if (data == null || data.isEmpty()) return null;
        try {
            return Base64.getDecoder().decode(data);
        } catch (IllegalArgumentException _) {
            return null;
        }
    }

    private static String save(Agent agent, Recording recording) {
        var webp = recording.webp ? sameSize(recording.webpFrames, true) : List.<byte[]>of();
        var png = recording.gif ? sameSize(recording.pngFrames, false) : List.<byte[]>of();
        var shown = recording.webp ? webp.size() : png.size();
        if (shown < 2) {
            return ERROR + "recorded only " + shown + " frame" + (shown == 1 ? "" : "s") + "; there is nothing to animate.";
        }
        var stamp = AppClock.now().toEpochMilli();
        var out = new StringBuilder();
        try {
            if (recording.webp) {
                out.append(write(agent, "recording-" + stamp + ".webp", AnimatedImages.webp(webp, recording.frameMs), "Recording"));
            }
            if (recording.gif) {
                out.append(write(agent, "recording-" + stamp + ".gif", AnimatedImages.gif(png, recording.frameMs),
                        recording.webp ? "Recording (GIF)" : "Recording"));
            }
        } catch (IOException _) {
            return ERROR + "the recording could not be saved.";
        } catch (IllegalArgumentException e) {
            return ERROR + "the recording could not be assembled: " + e.getMessage() + ".";
        }
        var skipped = recording.dropped + (recording.webp ? recording.webpFrames.size() - webp.size() : recording.pngFrames.size() - png.size());
        out.append("[Recording saved: ").append(shown).append(" frames, ").append(recording.frameMs).append(" ms each, up to ")
                .append(FRAME_WIDTH).append(" px wide.");
        if (recording.full()) {
            out.append(" The limit was reached, so later steps are not shown.");
        }
        if (skipped > 0) {
            out.append(' ').append(skipped).append(skipped == 1 ? " frame was" : " frames were")
                    .append(" left out: the page was off the allowed list, a capture failed, or the window changed size.");
        }
        return out.append(" Don't quote the file paths in your reply — the user already sees the images.]").toString();
    }

    /** The frames that match the first one in size; a window resized mid-recording leaves the rest out. */
    private static List<byte[]> sameSize(List<byte[]> frames, boolean webp) {
        var kept = new ArrayList<byte[]>();
        AnimatedImages.Size canvas = null;
        for (var frame : frames) {
            try {
                var size = webp ? AnimatedImages.webpSize(frame) : AnimatedImages.imageSize(frame);
                if (canvas == null) canvas = size;
                if (canvas.equals(size)) kept.add(frame);
            } catch (IllegalArgumentException | IOException _) {
                // A frame that cannot be read is left out rather than spoiling the recording.
            }
        }
        return kept;
    }

    private static String write(Agent agent, String filename, byte[] bytes, String label) throws IOException {
        Files.write(AgentService.workspacePath(agent.name).resolve(filename), bytes);
        return "![" + label + "](/api/agents/%d/files/%s)\n".formatted(agent.id, filename);
    }
}
