package services.scrape;

import models.ScrapeJob;
import models.ScrapeJobPage;
import org.jspecify.annotations.Nullable;
import services.AgentService;
import services.EventLogger;
import tools.WebScrapeTool;
import tools.scrape.ScrapeOutput;
import utils.GsonHolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * Where a scrape job keeps its pages in its agent's workspace, and the file that combines them
 * (JCLAW-1272). Everything sits under {@code scrapes/JOBID/}, so deleting a job is deleting one
 * folder.
 */
public final class ScrapeJobFiles {

    private static final String ROOT = "scrapes";

    private ScrapeJobFiles() {}

    public static String folder(Long jobId) {
        return ROOT + "/" + jobId;
    }

    public static String pageFile(Long jobId, int pageIndex, ScrapeOutput.Format format) {
        return "%s/%04d.%s".formatted(folder(jobId), pageIndex, switch (format) {
            case JSON -> "json";
            case TEXT -> "txt";
            case MARKDOWN, HTML -> "md";
        });
    }

    public static String combinedFile(Long jobId, ScrapeOutput.Format format) {
        return folder(jobId) + "/combined." + switch (format) {
            case JSON -> "jsonl";
            case TEXT -> "txt";
            case MARKDOWN, HTML -> "md";
        };
    }

    static void writePage(String agentName, String path, String content) {
        AgentService.writeWorkspaceFile(agentName, path, content);
    }

    /** The file's absolute path when it exists; null when it does not or would leave the workspace. */
    public static @Nullable Path existing(String agentName, String path) {
        var resolved = AgentService.resolveWorkspacePath(agentName, path);
        return resolved != null && Files.isRegularFile(resolved) ? resolved : null;
    }

    /**
     * Write the file that combines every page, in the shape {@code web_scrape} with {@code save}
     * writes: the summary, then each page under its URL, or one JSON record per line.
     *
     * <p>Streamed a page at a time, so a large job is never held in memory.
     */
    static void writeCombined(ScrapeJob job, List<ScrapeJobPage> pages, ScrapeOutput.Format format,
                              String header) throws IOException {
        var agentName = job.agent.name;
        var target = AgentService.acquireWorkspacePath(agentName, combinedFile(job.id, format));
        Files.createDirectories(target.getParent());
        try (var out = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            if (format != ScrapeOutput.Format.JSON) out.write(header);
            for (var page : pages) {
                var content = page.file == null ? null : read(agentName, page.file);
                if (format == ScrapeOutput.Format.JSON) {
                    out.write(content != null ? content.strip()
                            : GsonHolder.GSON.toJson(ScrapeOutput.failedRecord(page.url, page.servedBy,
                                    String.valueOf(page.reason))));
                    out.write('\n');
                } else {
                    out.write(WebScrapeTool.sectionHeading(format, page.url, page.servedBy));
                    out.write("\n\n");
                    out.write(content != null ? content : "[Not retrieved — %s]".formatted(page.reason));
                }
            }
        }
    }

    private static @Nullable String read(String agentName, String path) throws IOException {
        var file = existing(agentName, path);
        return file == null ? null : Files.readString(file, StandardCharsets.UTF_8);
    }

    /** Best-effort: the job's rows are already gone, and a folder left behind is only disk. */
    static void deleteFolder(String agentName, Long jobId) {
        var dir = AgentService.resolveWorkspacePath(agentName, folder(jobId));
        if (dir == null || !Files.isDirectory(dir)) return;
        try (var walk = Files.walk(dir)) {
            for (var path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException e) {
            EventLogger.warn("scrape", "Could not delete %s for scrape job %d: %s"
                    .formatted(dir, jobId, e.getMessage()));
        }
    }
}
