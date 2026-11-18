import channels.TelegramOutboundPlanner;
import channels.TelegramOutboundPlanner.FileSegment;
import channels.TelegramOutboundPlanner.MediaGroupSegment;
import channels.TelegramOutboundPlanner.TextSegment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Album chunking and text pass-through over every input in a small domain; TelegramOutboundPlannerTest
 * owns the named cases. Each domain is checked whole: jetCheck never repeats an input, so it cannot
 * draw 40 from 24 photo counts.
 */
class TelegramOutboundPlannerPropertyTest extends UnitTest {

    private static final String AGENT_NAME = "planner-chunk-property";

    /** Mirrors the planner's private MEDIA_GROUP_MAX — Telegram rejects an album above 10 items. */
    private static final int MEDIA_GROUP_MAX = 10;

    /** Two full albums plus a remainder, so the chunk arithmetic is exercised past its first wrap. */
    private static final int MAX_PHOTOS = 24;

    private static final int MAX_WORDS = 40;

    @BeforeEach
    void seedWorkspace() {
        Fixtures.deleteDatabase();
        AgentService.create(AGENT_NAME, "openrouter", "gpt-4.1");
        for (int i = 0; i < MAX_PHOTOS; i++) AgentService.writeWorkspaceFile(AGENT_NAME, photoName(i), "fake-png-bytes");
    }

    @AfterAll
    static void removeGeneratedFiles() {
        deleteRecursively(AgentService.workspacePath(AGENT_NAME));
    }

    // 24 runs of about a millisecond: plan() re-resolves every link against the workspace.
    @Test
    void everyPhotoSurvivesInOrderAndNoAlbumExceedsTheCap() {
        for (int photos = 1; photos <= MAX_PHOTOS; photos++) deliversEveryPhotoInOrder(photos);
    }

    // 40 runs, the same cost each.
    @Test
    void textOnlyMarkdownIsReturnedVerbatim() {
        for (int words = 1; words <= MAX_WORDS; words++) {
            var markdown = String.join(" ", Collections.nCopies(words, "prose"));
            int count = words;
            assertEquals(List.of(new TextSegment(markdown)), TelegramOutboundPlanner.plan(markdown, AGENT_NAME),
                    () -> "markdown with no workspace links must pass through untouched (" + count + " words)");
        }
    }

    private static void deliversEveryPhotoInOrder(int photos) {
        var markdown = new StringBuilder("Here they are:");
        for (int i = 0; i < photos; i++) {
            markdown.append(" [").append(photoName(i)).append("](<").append(photoName(i)).append(">)");
        }
        var delivered = new ArrayList<String>();
        for (var segment : TelegramOutboundPlanner.plan(markdown.toString(), AGENT_NAME)) {
            switch (segment) {
                case MediaGroupSegment group -> {
                    assertTrue(group.items().size() >= 2 && group.items().size() <= MEDIA_GROUP_MAX,
                            () -> "album of " + group.items().size() + " items for " + photos
                                    + " photos — Telegram accepts 2..10");
                    group.items().forEach(item -> delivered.add(item.displayName()));
                }
                // The JCLAW-123 background original-quality re-upload is a second segment for the
                // same photo, so counting it here would double every image.
                case FileSegment file -> {
                    if (!file.isBackground()) delivered.add(file.displayName());
                }
                case TextSegment _ -> { }
            }
        }
        var expected = new ArrayList<String>(photos);
        for (int i = 0; i < photos; i++) expected.add(photoName(i));
        assertEquals(expected, delivered,
                () -> "planner reordered or dropped photos for a run of " + photos + "; delivered " + delivered);
    }

    private static String photoName(int index) {
        return "chunk-p" + index + ".png";
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.isDirectory(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException _) {
                    // best effort — a leftover file costs disk, never correctness
                }
            });
        } catch (IOException _) {
            // best effort
        }
    }
}
