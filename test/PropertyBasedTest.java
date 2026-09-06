import channels.TelegramOutboundPlanner;
import channels.TelegramOutboundPlanner.FileSegment;
import channels.TelegramOutboundPlanner.MediaGroupSegment;
import channels.TelegramOutboundPlanner.TextSegment;
import com.google.gson.JsonObject;
import models.Agent;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.constraints.StringLength;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.BeforeContainer;
import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;
import services.AgentService;
import services.printing.LpdClient;
import tools.FileSystemTools;
import utils.Filenames;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Every jqwik {@code @Property} in the backend suite (JCLAW-1154). Properties state an invariant
 * over generated input and let jqwik shrink any violation to a minimal counterexample; the
 * example-based classes next door (UnifiedPatchParserTest, TelegramOutboundPlannerTest,
 * UtilsFilenamesTest) still own the named edge cases and the exact error strings.
 *
 * <p><strong>Keep every property in this one class.</strong> The play1 fork runs pure unit-test
 * classes on a 16-way parallel lane, and each class gets its own {@code LauncherFactory.create()};
 * jqwik's engine keeps process-global mutable state ({@code StoreRepository.current} is a plain
 * static), so two property-bearing classes executing at the same time corrupt each other. Measured
 * on 1.10.1: four property classes together failed two runs in three, with
 * ConcurrentModificationException, a StoreRepository NPE, CannotFindArbitraryException and
 * "List length = -1" — all spurious. One class is the supported shape until the fork gains a
 * serial lane for property classes.
 *
 * <p>Every assertion carries its generated input in the failure message. The fork's test listener
 * records {@code Throwable.getMessage()} and drops JUnit Platform report entries, which is where
 * jqwik publishes its own sample report — so a message supplier is the only route a shrunk
 * counterexample has into {@code test-result}.
 */
class PropertyBasedTest extends UnitTest {

    private static final String PATCH_AGENT = "patch-property-agent";
    private static final String PLANNER_AGENT = "planner-chunk-property";

    /** Distinct file per try — an Add File op onto an existing path is a validation error, not a round trip. */
    private static final AtomicInteger FILE_SEQ = new AtomicInteger();

    /** The workspace outlives the JVM, so the per-try counter alone would collide with the last run's files. */
    private static final String RUN_TAG = Long.toHexString(System.nanoTime());

    /** Mirrors the planner's private MEDIA_GROUP_MAX — Telegram rejects an album above 10 items. */
    private static final int MEDIA_GROUP_MAX = 10;

    /** Two full albums plus a remainder, so the chunk arithmetic is exercised past its first wrap. */
    private static final int MAX_PHOTOS = 24;

    /** LPD control-file records are newline-delimited and capped at 131 chars; see LpdClient.safeToken. */
    private static final int SAFE_TOKEN_MAX = 96;

    // jqwik builds a fresh container instance per try, so the fixture has to be static to survive.
    private static Agent patchAgent;
    private static FileSystemTools fileSystemTools;

    @BeforeContainer
    static void seedWorkspaces() {
        patchAgent = AgentService.create(PATCH_AGENT, "openrouter", "gpt-4.1");
        fileSystemTools = new FileSystemTools();
        AgentService.create(PLANNER_AGENT, "openrouter", "gpt-4.1");
        for (int i = 0; i < MAX_PHOTOS; i++) {
            AgentService.writeWorkspaceFile(PLANNER_AGENT, photoName(i), "fake-png-bytes");
        }
    }

    @AfterContainer
    static void removeGeneratedFiles() {
        deleteRecursively(AgentService.workspacePath(PATCH_AGENT));
        deleteRecursively(AgentService.workspacePath(PLANNER_AGENT));
    }

    /**
     * Proves the fork's launcher discovers the jqwik engine at all — the JCLAW-1154 spike. Kept as
     * a live check rather than a note: a Jupiter {@code @Test} beside a {@code @Property} makes a
     * silently-undiscovered engine look like a green class instead of a missing one.
     */
    @Test
    void jupiterAndJqwikBothRunInThisClass() {
        assertEquals(2, 1 + 1);
    }

    /**
     * The one-class rule in the class Javadoc has teeth only if something checks it: a second
     * property-bearing class would race this one on the parallel lane and fail spuriously.
     */
    @Test
    void noOtherTestClassCarriesAProperty() throws IOException {
        var offenders = new ArrayList<String>();
        try (Stream<Path> tree = Files.list(Path.of(Play.applicationPath.getAbsolutePath(), "test"))) {
            for (var file : tree.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (file.getFileName().toString().equals("PropertyBasedTest.java")) continue;
                if (Files.readString(file).contains("net.jqwik")) offenders.add(file.getFileName().toString());
            }
        }
        assertEquals(List.of(), offenders, "jqwik properties live in PropertyBasedTest only — see the class Javadoc");
    }

    // tries=50: O(1) per try, single-digit milliseconds.
    @Property(tries = 50)
    void jqwikEngineIsDiscoveredAndRunsProperties(@ForAll @IntRange(min = -1000, max = 1000) int n) {
        assertTrue(Math.abs((long) n) >= 0, () -> "absolute value went negative for n=" + n);
    }

    // ── UnifiedPatchParser + FsPatchApplier round trips ──

    // tries=40: each try does three small filesystem round trips (~1 ms), so ~50 ms total.
    @Property(tries = 40)
    void addFileReproducesTheIntendedContent(
            @ForAll @Size(min = 1, max = 6) List<@AlphaChars @StringLength(max = 16) String> lines) {

        var name = nextFileName();
        var body = new StringBuilder("*** Begin Patch\n*** Add File: ").append(name).append('\n');
        for (var line : lines) body.append('+').append(line).append('\n');
        body.append("*** End of File\n*** End Patch\n");

        var result = applyPatch(body.toString());
        var expected = String.join("\n", lines);
        assertEquals(expected, readPatchAgentFile(name),
                () -> "Add File round trip lost content. lines=" + lines + " applyPatch said: " + result);
    }

    // tries=40: same budget and the same ~1 ms per try as the Add File property above.
    @Property(tries = 40)
    void updateChunkReplacesExactlyTheTargetedLine(
            @ForAll @Size(min = 2, max = 8) List<@AlphaChars @StringLength(min = 1, max = 16) String> lines,
            @ForAll @IntRange(min = 0, max = 7) int targetSeed,
            @ForAll @AlphaChars @StringLength(min = 1, max = 16) String replacement) {

        // An unanchored chunk must match exactly once, so the generated lines are made
        // mutually non-containing by an index prefix rather than by @UniqueElements —
        // "ab" and "xab" are unique as strings but "ab\n" still occurs twice on disk.
        var numbered = new ArrayList<String>(lines.size());
        for (int i = 0; i < lines.size(); i++) numbered.add("L" + i + "-" + lines.get(i));
        int target = targetSeed % numbered.size();

        var name = nextFileName();
        AgentService.writeWorkspaceFile(PATCH_AGENT, name, String.join("\n", numbered) + "\n");

        var body = "*** Begin Patch\n*** Update File: %s\n-%s\n+%s\n*** End of File\n*** End Patch\n"
                .formatted(name, numbered.get(target), replacement);
        var result = applyPatch(body);

        var expectedLines = new ArrayList<>(numbered);
        expectedLines.set(target, replacement);
        var expected = String.join("\n", expectedLines) + "\n";
        assertEquals(expected, readPatchAgentFile(name),
                () -> "Update chunk round trip diverged. lines=" + numbered + " target=" + target
                        + " replacement=" + replacement + " applyPatch said: " + result);
    }

    // ── Name sanitisation ──
    //
    // JCLAW-1154 named utils.Filenames as the home of filename sanitisation. It has none —
    // extensionOf only inspects. The repository's sanitisers are LpdClient.safeToken (public) and
    // UploadStaging.sanitizeFilename (private), so the idempotence / no-separator / bounded-length
    // trio is asserted against safeToken.

    /**
     * The default String arbitrary almost never emits {@code /} or a dot, which would leave the
     * interesting branches of both helpers unvisited while the properties still passed.
     */
    @Provide
    Arbitrary<String> hostileNames() {
        return Arbitraries.strings()
                .withCharRange('a', 'f')
                .withCharRange('0', '9')
                .withChars('/', '\\', '.', '-', '_', ' ', '\t', '\n', '\u0000', 'é', '中')
                .ofMaxLength(200);
    }

    // tries=300: pure string work, microseconds per try — under 20 ms.
    @Property(tries = 300)
    void safeTokenIsIdempotent(@ForAll("hostileNames") String raw) {
        var once = LpdClient.safeToken(raw);
        assertEquals(once, LpdClient.safeToken(once),
                () -> "safeToken is not a fixed point for " + quote(raw) + " -> " + quote(once));
    }

    // tries=300: same pure-string budget as above.
    @Property(tries = 300)
    void safeTokenNeverEmitsASeparatorOrAnOverLongName(@ForAll("hostileNames") String raw) {
        var token = LpdClient.safeToken(raw);
        assertFalse(token.isEmpty(), () -> "empty token for " + quote(raw));
        assertFalse(token.contains("/") || token.contains("\\"),
                () -> "path separator survived sanitisation of " + quote(raw) + " -> " + quote(token));
        assertTrue(token.length() <= SAFE_TOKEN_MAX,
                () -> "token exceeds the RFC 1179 budget for " + quote(raw) + " -> length " + token.length());
        assertTrue(token.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-'),
                () -> "unsanitised character survived for " + quote(raw) + " -> " + quote(token));
    }

    /** Directory prefixes that themselves carry dots — the case {@code extensionOf} has to ignore. */
    @Provide
    Arbitrary<String> dottedDirectoryPrefix() {
        return Arbitraries.of("", "dir/", "a.d/", "a.d/b.e/", "C:\\a.d\\", "./", "../x.y/");
    }

    // tries=200: pure string work; the generated candidate is at most ~40 chars.
    @Property(tries = 200)
    void extensionOfRecoversTheExtensionItWasGiven(
            @ForAll("dottedDirectoryPrefix") String prefix,
            @ForAll @AlphaChars @StringLength(min = 1, max = 10) String base,
            @ForAll @AlphaChars @StringLength(min = 1, max = 6) String extension) {

        var candidate = prefix + base + "." + extension;
        assertEquals("." + extension, Filenames.extensionOf(candidate),
                () -> "extensionOf lost the extension of " + quote(candidate));
    }

    // tries=200: same budget as above.
    @Property(tries = 200)
    void extensionOfNeverCrossesAPathSeparator(@ForAll("hostileNames") String candidate) {
        var ext = Filenames.extensionOf(candidate);
        if (ext.isEmpty()) return;
        assertTrue(candidate.endsWith(ext),
                () -> "extension is not a suffix of " + quote(candidate) + " -> " + quote(ext));
        assertEquals('.', ext.charAt(0),
                () -> "extension must keep its leading dot for " + quote(candidate) + " -> " + quote(ext));
        assertFalse(ext.contains("/") || ext.contains("\\"),
                () -> "extension crossed a path separator for " + quote(candidate) + " -> " + quote(ext));
    }

    // ── TelegramOutboundPlanner chunking ──

    // tries=40: plan() re-resolves every link against the workspace, so a 24-photo try costs about
    // a millisecond; the property lands around 40 ms.
    @Property(tries = 40)
    void everyPhotoSurvivesInOrderAndNoAlbumExceedsTheCap(@ForAll @IntRange(min = 1, max = MAX_PHOTOS) int photos) {
        var markdown = new StringBuilder("Here they are:");
        for (int i = 0; i < photos; i++) {
            markdown.append(" [").append(photoName(i)).append("](<").append(photoName(i)).append(">)");
        }

        var segments = TelegramOutboundPlanner.plan(markdown.toString(), PLANNER_AGENT);
        var delivered = new ArrayList<String>();
        for (var segment : segments) {
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
                () -> "planner reordered or dropped photos for a run of " + photos
                        + "; delivered " + delivered);
    }

    // tries=40: same budget as above.
    @Property(tries = 40)
    void textOnlyMarkdownIsReturnedVerbatim(@ForAll @IntRange(min = 1, max = 40) int words) {
        var markdown = String.join(" ", Collections.nCopies(words, "prose"));
        var segments = TelegramOutboundPlanner.plan(markdown, PLANNER_AGENT);
        assertEquals(List.of(new TextSegment(markdown)), segments,
                () -> "markdown with no workspace links must pass through untouched (" + words + " words)");
    }

    // ── Fixture helpers ──

    private static String photoName(int index) {
        return "chunk-p" + index + ".png";
    }

    private static String nextFileName() {
        return "prop-" + RUN_TAG + "-" + FILE_SEQ.incrementAndGet() + ".txt";
    }

    private static String applyPatch(String patchBody) {
        var args = new JsonObject();
        args.addProperty("action", "applyPatch");
        args.addProperty("patch", patchBody);
        return fileSystemTools.execute(args.toString(), patchAgent);
    }

    /**
     * Reads through the filesystem rather than {@code AgentService.readWorkspaceFile}: the patch
     * applier writes via {@code FsWriter}, which does not invalidate the workspace file cache.
     */
    private static String readPatchAgentFile(String name) {
        try {
            return Files.readString(AgentService.acquireWorkspacePath(PATCH_AGENT, name));
        } catch (IOException e) {
            throw new AssertionError("could not read back " + name + ": " + e.getMessage(), e);
        }
    }

    private static void deleteRecursively(java.nio.file.Path dir) {
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

    /** Renders control characters so a shrunk counterexample survives the XML report legibly. */
    private static String quote(String s) {
        var sb = new StringBuilder("\"");
        s.codePoints().forEach(cp -> {
            if (cp >= 0x20 && cp != '"' && cp != '\\') sb.appendCodePoint(cp);
            else sb.append("\\u").append(String.format("%04x", cp));
        });
        return sb.append('"').toString();
    }
}
