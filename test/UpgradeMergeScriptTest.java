import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import play.Play;
import play.test.UnitTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Runs jclaw.sh's upgrade swap, merge and first-start cleanup against real trees on disk (JF-522), so what an
 * upgrade replaces, leaves behind, carries and names is tested as jclaw.sh writes it rather than restated.
 */
class UpgradeMergeScriptTest extends UnitTest {

    private static final List<String> FUNCTIONS = List.of("sha256_file", "release_path_is_yours", "listed_sha",
            "listed_under", "merge_absent", "_merge_absent", "retire_unlisted_release_files", "upgrade_current_version",
            "is_developer_clone", "is_container", "move_entries", "upgrade_swap_in", "upgrade_unswap");
    private static final List<String> SETTINGS = List.of("UPGRADE_NO_MERGE", "RELEASE_FILES", "RETIRED_RELEASE_FILES",
            "MERGE_SHIPPED", "MERGE_KEPT", "MERGE_LEFT");

    // What a shell in the install folder sees there: three files, the listing and the folder itself.
    private static final String LOOK = "cat jclaw.sh; printf '|'; cat .play-version; printf '|'; cat data/jclaw.mv.db; "
            + "printf '|'; LC_ALL=C ls -A | tr '\\n' ' '; printf '|'; /bin/pwd -P";

    @TempDir
    Path tmp;

    @Test
    void anUpgradeLeavesAShellInTheInstallFolderLookingAtTheNewRelease() throws Exception {
        var home = installed();
        var fresh = release();

        var seen = fromAShellInTheInstall(home, "upgrade_swap_in '" + fresh + "' \"$SCRIPT_DIR.previous\"\n"
                + "merge_absent \"$SCRIPT_DIR.previous\" \"$SCRIPT_DIR\" ''\n"
                + "rm -rf \"$SCRIPT_DIR.previous\"");

        assertEquals("the new script|1.13.90|the database|.jclaw-manifest .play-version CHANGELOG.md data jclaw.sh "
                + "release-files.tsv |"
                + home.toRealPath() + "\n", seen);
        assertFalse(Files.exists(previous(home)));
    }

    @Test
    void aRollbackPutsTheOldReleaseBackInTheFolderAShellSitsIn() throws Exception {
        var home = installed();
        var fresh = release();

        var seen = fromAShellInTheInstall(home, "upgrade_swap_in '" + fresh + "' \"$SCRIPT_DIR.previous\"\n"
                + "merge_absent \"$SCRIPT_DIR.previous\" \"$SCRIPT_DIR\" ''\n"
                + "upgrade_unswap \"$SCRIPT_DIR.previous\"");

        assertEquals("the old script|1.13.88|the database|.jclaw-manifest .play-version data jclaw.sh |"
                + home.toRealPath() + "\n", seen);
        assertFalse(Files.exists(previous(home)));
    }

    @Test
    void anInstallThatCannotBeMovedAsideIsLeftAsItWas() throws Exception {
        var home = installed();
        unmovable(home.resolve("zz-locked"));
        var fresh = release();

        var out = run(home, "rc=0; upgrade_swap_in '" + fresh + "' \"$SCRIPT_DIR.previous\" 2>/dev/null || rc=$?\n"
                + "echo \"rc=$rc\"; " + listing(home, fresh));

        assertEquals("rc=1\n.jclaw-manifest .play-version data jclaw.sh zz-locked \n"
                + ".play-version CHANGELOG.md jclaw.sh release-files.tsv \nno previous\n", out);
        assertEquals("the old script", read(home, "jclaw.sh"));
    }

    @Test
    void aReleaseThatCannotBeMovedInIsRolledBackToTheOldContents() throws Exception {
        var home = installed();
        var fresh = release();
        unmovable(fresh.resolve("zz-locked"));

        var out = run(home, "rc=0; upgrade_swap_in '" + fresh + "' \"$SCRIPT_DIR.previous\" 2>/dev/null || rc=$?\n"
                + "echo \"rc=$rc\"; upgrade_unswap \"$SCRIPT_DIR.previous\"; " + listing(home, fresh));

        assertEquals("rc=2\n.jclaw-manifest .play-version data jclaw.sh \n"
                + ".play-version CHANGELOG.md jclaw.sh release-files.tsv zz-locked \nno previous\n", out);
        assertEquals("the old script", read(home, "jclaw.sh"));
    }

    @Test
    void anUpgradeLeavesBehindWhatTheOldReleaseShippedAndTheNewOneDrops() throws Exception {
        var old = tree("old");
        write(old, "COMMERCIAL-LICENSE.md", "the old terms");
        write(old, "skills/radarr-delete/SKILL.md", "a dropped skill");
        listShipped(old, "COMMERCIAL-LICENSE.md", "skills/radarr-delete/SKILL.md");
        var fresh = tree("new");

        var out = merge(old, fresh);

        assertEquals("left=2\nkept=|\n", out);
        assertFalse(Files.exists(fresh.resolve("COMMERCIAL-LICENSE.md")));
        assertFalse(Files.exists(fresh.resolve("skills/radarr-delete")), "an emptied skill folder goes too");
        assertFalse(Files.exists(fresh.resolve("release-files.tsv")), "the old release's list stays with it");
    }

    @Test
    void anUpgradeKeepsAndNamesADroppedFileTheOperatorChanged() throws Exception {
        var old = tree("old");
        write(old, "skills/nas-filestation/SKILL.md", "as shipped");
        listShipped(old, "skills/nas-filestation/SKILL.md");
        write(old, "skills/nas-filestation/SKILL.md", "as the operator edited it");
        var fresh = tree("new");

        var out = merge(old, fresh);

        assertEquals("left=0\nkept=skills/nas-filestation/SKILL.md\n|\n", out);
        assertEquals("as the operator edited it", read(fresh, "skills/nas-filestation/SKILL.md"));
    }

    @Test
    void anUpgradeCarriesTheOperatorsFilesEvenWhereTheOldListNamesThem() throws Exception {
        var old = tree("old");
        write(old, "workspace/main/AGENT.md", "the main agent's persona");
        write(old, "skills/gamma-presenter/credentials/key.txt", "a secret");
        write(old, "skills/oreilly-books/SKILL.md", "a dropped skill");
        write(old, "skills/oreilly-books/credentials/cookies.json", "the operator's cookies");
        listShipped(old, "workspace/main/AGENT.md", "skills/gamma-presenter/credentials/key.txt",
                "skills/oreilly-books/SKILL.md", "skills/oreilly-books/credentials/cookies.json");
        write(old, "data/jclaw.mv.db", "the database");
        write(old, "sidecar/asr/.venv/lib/site.py", "a venv the release never listed");
        write(old, "skills/my-own/SKILL.md", "a skill the operator wrote");
        var fresh = tree("new");

        var out = merge(old, fresh);

        assertEquals("left=1\nkept=|\n", out);
        assertEquals("the main agent's persona", read(fresh, "workspace/main/AGENT.md"));
        assertEquals("a secret", read(fresh, "skills/gamma-presenter/credentials/key.txt"));
        assertEquals("the operator's cookies", read(fresh, "skills/oreilly-books/credentials/cookies.json"));
        assertFalse(Files.exists(fresh.resolve("skills/oreilly-books/SKILL.md")));
        assertEquals("the database", read(fresh, "data/jclaw.mv.db"));
        assertEquals("a venv the release never listed", read(fresh, "sidecar/asr/.venv/lib/site.py"));
        assertEquals("a skill the operator wrote", read(fresh, "skills/my-own/SKILL.md"));
    }

    @Test
    void aFileBothReleasesShipStaysTheNewReleasesCopy() throws Exception {
        var old = tree("old");
        write(old, "skills/git/SKILL.md", "the old version");
        listShipped(old, "skills/git/SKILL.md");
        var fresh = tree("new");
        write(fresh, "skills/git/SKILL.md", "the new version");

        var out = merge(old, fresh);

        assertEquals("left=0\nkept=|\n", out);
        assertEquals("the new version", read(fresh, "skills/git/SKILL.md"));
    }

    @Test
    void anUpgradeFromAReleaseWithNoListCarriesEverythingAsBefore() throws Exception {
        var old = tree("old");
        write(old, "COMMERCIAL-LICENSE.md", "the old terms");
        write(old, "data/jclaw.mv.db", "the database");
        var fresh = tree("new");

        var out = merge(old, fresh);

        assertEquals("left=0\nkept=|\n", out);
        assertEquals("the old terms", read(fresh, "COMMERCIAL-LICENSE.md"));
        assertEquals("the database", read(fresh, "data/jclaw.mv.db"));
    }

    @Test
    void theFirstStartAfterAnUpgradeFromAnUnlistedReleaseRemovesItsDroppedFilesOnce() throws Exception {
        var home = tree("home");
        write(home, "conf/application.conf", "application.version=1.0.24\n");
        write(home, "skills/git/SKILL.md", "this release's");
        listShipped(home, "conf/application.conf", "skills/git/SKILL.md");
        write(home, "COMMERCIAL-LICENSE.md", "the old terms");
        write(home, "skills/radarr-delete/SKILL.md", "a dropped skill");
        write(home, "DEVELOPMENT.md", "edited after install");
        write(home, "workspace/main/AGENT.md", "the persona");
        write(home, "skills/oreilly-books/credentials/cookies.json", "the operator's cookies");
        retiredTable(home, List.of(
                row("COMMERCIAL-LICENSE.md", "an older text of it"),
                row("COMMERCIAL-LICENSE.md", "the old terms"),
                row("skills/radarr-delete/SKILL.md", "a dropped skill"),
                row("DEVELOPMENT.md", "as shipped"),
                row("workspace/main/AGENT.md", "the persona"),
                row("skills/oreilly-books/credentials/cookies.json", "the operator's cookies"),
                row("skills/git/SKILL.md", "an old version")));

        var out = run(home, "retire_unlisted_release_files");

        assertEquals("""
                ==> Removed 2 file(s) an earlier release shipped and 1.0.24 does not.
                    Kept these, which 1.0.24 does not ship either, because they differ from what any release shipped:
                      DEVELOPMENT.md
                """, out);
        assertFalse(Files.exists(home.resolve("COMMERCIAL-LICENSE.md")));
        assertFalse(Files.exists(home.resolve("skills/radarr-delete")), "an emptied skill folder goes too");
        assertEquals("edited after install", read(home, "DEVELOPMENT.md"));
        assertEquals("the persona", read(home, "workspace/main/AGENT.md"));
        assertEquals("the operator's cookies", read(home, "skills/oreilly-books/credentials/cookies.json"));
        assertEquals("this release's", read(home, "skills/git/SKILL.md"), "a path this release ships is its own");

        write(home, "COMMERCIAL-LICENSE.md", "the old terms");
        assertEquals("", run(home, "retire_unlisted_release_files"), "it runs once per version");
        assertEquals("the old terms", read(home, "COMMERCIAL-LICENSE.md"));
    }

    @Test
    void theStartCleanupLeavesASourceCheckoutAndAnInstallWithoutAListAlone() throws Exception {
        for (var name : List.of("checkout", "unlisted")) {
            var home = tree(name);
            write(home, "conf/application.conf", "application.version=1.0.24\n");
            write(home, "CONTRIBUTING.md", "a tracked file");
            retiredTable(home, List.of(row("CONTRIBUTING.md", "a tracked file")));
            if (name.equals("checkout")) {
                write(home, "app/controllers/Application.java", "class Application {}");
                listShipped(home, "conf/application.conf");
            }

            assertEquals("", run(home, "retire_unlisted_release_files"), name);
            assertEquals("a tracked file", read(home, "CONTRIBUTING.md"), name);
        }
    }

    private Path installed() throws Exception {
        var home = tree("jclaw");
        write(home, "jclaw.sh", "the old script");
        write(home, ".play-version", "1.13.88");
        write(home, ".jclaw-manifest", "version=1.0.25");
        write(home, "data/jclaw.mv.db", "the database");
        return home;
    }

    private Path release() throws Exception {
        var fresh = tree("staging/jclaw");
        write(fresh, "jclaw.sh", "the new script");
        write(fresh, ".play-version", "1.13.90");
        write(fresh, "CHANGELOG.md", "the new release's notes");
        listShipped(fresh, "jclaw.sh", ".play-version", "CHANGELOG.md");
        return fresh;
    }

    private static Path previous(Path home) {
        return Path.of(home + ".previous");
    }

    // A directory cannot be renamed into another one without write permission on itself, to rewrite its "..".
    private static void unmovable(Path dir) throws Exception {
        Files.createDirectory(dir);
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"));
    }

    private static String listing(Path home, Path fresh) {
        return "LC_ALL=C ls -A '" + home + "' | tr '\\n' ' '; echo; LC_ALL=C ls -A '" + fresh + "' | tr '\\n' ' '; echo\n"
                + "[[ -e '" + previous(home) + "' ]] && echo previous || echo 'no previous'";
    }

    /** Runs {@code swap} while another shell sits in the install folder, and returns what that shell then sees. */
    private String fromAShellInTheInstall(Path home, String swap) throws Exception {
        var ready = tmp.resolve("ready");
        var go = tmp.resolve("go");
        var seen = tmp.resolve("seen");
        return run(home, "mkfifo '" + ready + "' '" + go + "'\n"
                + "( cd \"$SCRIPT_DIR\" && echo >'" + ready + "' && read -r _ <'" + go + "' && { " + LOOK + "; } ) >'"
                + seen + "' 2>&1 &\n"
                + "shell=$!\n"
                + "read -r _ <'" + ready + "'\n"
                + swap + "\n"
                + "echo >'" + go + "'\n"
                + "wait \"$shell\" || true\n"
                + "cat '" + seen + "'");
    }

    private String merge(Path old, Path fresh) throws Exception {
        return run(tmp, "merge_absent '" + old + "' '" + fresh + "' ''\n"
                + "printf 'left=%s\\nkept=%s|\\n' \"$MERGE_LEFT\" \"$MERGE_KEPT\"");
    }

    private Path tree(String name) throws Exception {
        return Files.createDirectories(tmp.resolve(name));
    }

    private static void write(Path root, String rel, String text) throws Exception {
        var file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private static String read(Path root, String rel) throws Exception {
        return Files.readString(root.resolve(rel));
    }

    // The list a release zip carries, as gradle/release-files.gradle.kts writes it, of the files' current content.
    private static void listShipped(Path root, String... rels) throws Exception {
        var sb = new StringBuilder();
        for (var rel : rels) sb.append(rel).append('\t').append(sha256(Files.readAllBytes(root.resolve(rel)))).append('\n');
        Files.writeString(root.resolve("release-files.tsv"), sb.toString());
    }

    private static String row(String rel, String content) throws Exception {
        return rel + "\t" + sha256(content.getBytes(StandardCharsets.UTF_8));
    }

    private static void retiredTable(Path root, List<String> rows) throws Exception {
        write(root, "conf/retired-release-files.tsv", "# generated\n" + String.join("\n", rows) + "\n");
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private String run(Path home, String commands) throws Exception {
        var script = Files.readString(Path.of(Play.applicationPath.getAbsolutePath()).resolve("jclaw.sh"));
        var harness = new StringBuilder("set -euo pipefail\nSCRIPT_DIR='" + home + "'\n");
        for (var name : SETTINGS) {
            var m = Pattern.compile("(?m)^" + Pattern.quote(name) + "=.*$").matcher(script);
            assertTrue(m.find(), "jclaw.sh no longer sets " + name);
            harness.append(m.group()).append('\n');
        }
        for (var name : FUNCTIONS) {
            var m = Pattern.compile("(?ms)^" + Pattern.quote(name) + "\\(\\) \\{\\n.*?^\\}\\n").matcher(script);
            assertTrue(m.find(), "jclaw.sh no longer defines " + name);
            harness.append(m.group());
        }
        harness.append(commands).append('\n');
        var pb = new ProcessBuilder("bash", "-c", harness.toString()).redirectErrorStream(true);
        pb.environment().remove("JCLAW_CONTAINER");
        var p = pb.start();
        var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(30, TimeUnit.SECONDS), "the harness hung");
        assertEquals(0, p.exitValue(), () -> "the harness exited non-zero:\n" + out);
        return out;
    }
}
