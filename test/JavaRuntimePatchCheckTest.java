import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import models.Notification;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import play.Play;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.ConfigService;
import services.EventLogger;
import services.JavaRuntimePatchCheck;
import services.JavaRuntimeService;
import services.JavaRuntimeService.Download;
import services.JavaRuntimeService.Environment;
import services.JavaRuntimeService.Vendor;
import services.Tx;
import utils.HttpFactories;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * The daily patch check below its scheduler. Azul's catalog and CDN are stubbed at the HTTP boundary, the
 * catalog with its real answer for Java 25 on Linux x64, and what the check leaves is read back as the next
 * start and the operator's notification bar read it: the staging, the notification, and the record that keeps
 * either from happening twice.
 */
class JavaRuntimePatchCheckTest extends UnitTest {

    private static final String VERSION = "25.0.5.1";
    private static final String ARCHIVE = "zulu25.38.201-ca-jre25.0.5.1-linux_x64.tar.gz";
    private static final String CDN_PATH = "cdn.azul.com/zulu/bin/" + ARCHIVE;
    /** A later Zulu build of the same Java 25.0.5.1, as Azul ships an out-of-cycle fix. */
    private static final String RESPIN = "zulu25.38.203-ca-jre25.0.5.1-linux_x64.tar.gz";
    /** An earlier patch, waiting in {@code jre.staged/} for the next restart. */
    private static final String OLDER = "zulu25.36.11-ca-jre25.0.5-linux_x64.tar.gz";
    private static final String CATALOG_PATH = "api.azul.com/metadata/v1/zulu/packages/";
    /** The catalog's own hash for {@link #ARCHIVE}, which the stub's bytes never match. */
    private static final String AZUL_SHA = "12277af4957cd249dc7aa50017ddde967a826fa48971b2411752fefe48e5ca8b";
    private static final byte[] JRE = "a Zulu JRE".getBytes(StandardCharsets.UTF_8);
    private static final String LINK = " | Azul's latest release notes https://docs.azul.com/core/release-notes";
    private static final String TEMURIN_ARCHIVE = "OpenJDK25U-jre_x64_linux_hotspot_25.0.5.1_2.tar.gz";
    private static final String ADOPTIUM_PATH = "api.adoptium.net/v3/assets/latest/25/hotspot";
    private static final String TEMURIN_DOWNLOAD_PATH = "github.com/adoptium/temurin25-binaries/releases/download/"
            + "jdk-25.0.5.1%2B2/" + TEMURIN_ARCHIVE;
    /** Adoptium's own hash for {@link #TEMURIN_ARCHIVE}, which the stub's bytes never match. */
    private static final String TEMURIN_SHA = "2edef9e4f81ec5ee426d023336d4cfbd7b4644e99884e7a73a6a867b70b17acf";
    private static final String TEMURIN_LINK = " | Release notes for Temurin 25.0.5.1 "
            + "https://adoptium.net/temurin/release-notes/?version=jdk-25.0.5.1%2B2";

    @TempDir Path tmp;

    private final String recipient = "jf536-operator";
    private final List<String> requests = new ArrayList<>();

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        Tx.run(() -> AgentService.create(recipient, "openrouter", "gpt-4.1"));
    }

    @AfterEach
    void teardown() {
        ConfigService.delete(JavaRuntimePatchCheck.AUTO_DOWNLOAD_KEY);
    }

    @Test
    void aNewerPatchIsStagedOnTheBuiltInJavaForTheNextStartAndAnnouncedOnce() throws IOException {
        var env = builtIn("25.0.4");
        var azul = azul(catalog(true, sha(JRE)), 200);

        check(env, azul, new Download());

        assertEquals(new JavaRuntimeService.Staged(VERSION, sha(JRE), ARCHIVE, "25.38.201", Vendor.ZULU), JavaRuntimeService.staged(tmp));
        assertEquals("a Zulu JRE", Files.readString(tmp.resolve("jre.staged/" + ARCHIVE)));
        assertEquals(List.of("Java runtime | Java 25.0.5.1, a security and bug-fix release, is downloaded and installs "
                + "the next time JClaw restarts. Restart from Settings > Maintenance > Java runtime when it suits you."
                + LINK), notices());
        assertEquals(List.of(ARCHIVE), JavaRuntimePatchCheck.handled(env.app()));
        assertEquals(List.of(CATALOG_PATH, CDN_PATH), requests);
        // All it leaves: no restart is asked for, and nothing beside the install changes.
        assertEquals(List.of("jclaw", "jre", "jre.staged"), listing(tmp));
        assertEquals(List.of("conf", "data"), listing(env.app()));
        assertEquals(List.of("java-runtime.notified"), listing(env.app().resolve("data")));

        check(env, azul, new Download());

        assertEquals(List.of(CATALOG_PATH, CDN_PATH, CATALOG_PATH), requests);
        assertEquals(1, notices().size());
    }

    @Test
    void withTheKeyOffTheBuiltInJavaIsOnlyToldOfThePatchOnce() throws IOException {
        ConfigService.set(JavaRuntimePatchCheck.AUTO_DOWNLOAD_KEY, "false");
        var env = builtIn("25.0.4");
        var azul = azul(catalog(true, sha(JRE)), 200);

        check(env, azul, new Download());
        check(env, azul, new Download());

        assertEquals(List.of("Java runtime | Java 25.0.5.1, a security and bug-fix release, is available. Download it "
                + "from Settings > Maintenance > Java runtime; it installs the next time JClaw restarts." + LINK), notices());
        assertEquals(List.of(CATALOG_PATH, CATALOG_PATH), requests);
        assertFalse(Files.exists(tmp.resolve("jre.staged")));
    }

    @Test
    void withTheKeyOnTheBuiltInJavaDownloads() throws IOException {
        ConfigService.set(JavaRuntimePatchCheck.AUTO_DOWNLOAD_KEY, "true");

        check(builtIn("25.0.4"), azul(catalog(true, sha(JRE)), 200), new Download());

        assertEquals(VERSION, JavaRuntimeService.staged(tmp).version());
    }

    @Test
    void aHostJavaIsToldOfThePatchOnceAndNothingIsDownloaded() throws IOException {
        var javaHome = Files.createDirectories(tmp.resolve("usr/lib/jvm/java-25"));
        var env = new Environment(app(), javaHome, "25.0.4", "Ubuntu", "Linux", "amd64", false);
        var azul = azul(catalog(true, sha(JRE)), 200);

        check(env, azul, new Download());
        check(env, azul, new Download());

        assertEquals(List.of("Java runtime | Java 25.0.5.1, a security and bug-fix release, is available for the Java "
                + "JClaw runs on, Java 25.0.4 at " + javaHome + ". Update that Java the way you installed it." + LINK),
                notices());
        assertEquals(List.of(CATALOG_PATH, CATALOG_PATH), requests);
        assertFalse(Files.exists(tmp.resolve("jre.staged")));
    }

    @Test
    void aSourceCheckoutAndAContainerAreNeitherAskedAboutNorTold() throws IOException {
        var azul = azul(catalog(true, sha(JRE)), 200);
        var container = builtIn("25.0.4");
        container = new Environment(container.app(), container.javaHome(), "25.0.4", "Azul Systems, Inc.", "Linux",
                "amd64", true);
        check(container, azul, new Download());

        var checkout = builtIn("25.0.4");
        Files.createDirectories(checkout.app().resolve("app"));
        check(checkout, azul, new Download());

        assertEquals(List.of(), requests);
        assertEquals(List.of(), notices());
        assertEquals(List.of(), JavaRuntimePatchCheck.handled(checkout.app()));
        assertFalse(Files.exists(tmp.resolve("jre.staged")));
    }

    @Test
    void aFailedDownloadStagesNothingIsLoggedAndIsTriedAgainAtTheNextRun() throws IOException {
        var env = builtIn("25.0.4");

        var down = azul(catalog(true, sha(JRE)), 503);

        var events = EventLogger.captureForTest(() -> check(env, down, new Download()));

        assertEquals(List.of("INFO Downloading Zulu 25.0.5.1 for the next start to install",
                "WARN Java runtime download failed: HTTP 503 fetching https://" + CDN_PATH), runtimeEvents(events));
        assertFalse(Files.exists(tmp.resolve("jre.staged")));
        assertEquals(List.of(), notices());
        assertEquals(List.of(), JavaRuntimePatchCheck.handled(env.app()));

        check(env, azul(catalog(true, sha(JRE)), 200), new Download());

        assertEquals(VERSION, JavaRuntimeService.staged(tmp).version());
        assertEquals(1, notices().size());
    }

    @Test
    void aDownloadThatFailsItsSha256StagesNothingIsLoggedAndIsTriedAgainAtTheNextRun() throws IOException {
        var env = builtIn("25.0.4");

        var mismatched = azul(catalog(true, AZUL_SHA), 200);

        var events = EventLogger.captureForTest(() -> check(env, mismatched, new Download()));

        assertEquals(List.of("INFO Downloading Zulu 25.0.5.1 for the next start to install",
                "WARN Java runtime download failed: checksum mismatch: Azul's catalog gives " + AZUL_SHA
                        + ", the download is " + sha(JRE)), runtimeEvents(events));
        assertFalse(Files.exists(tmp.resolve("jre.staged")));
        assertEquals(List.of(), notices());
        assertEquals(List.of(), JavaRuntimePatchCheck.handled(env.app()));

        check(env, azul(catalog(true, sha(JRE)), 200), new Download());

        assertEquals(new JavaRuntimeService.Staged(VERSION, sha(JRE), ARCHIVE, "25.38.201", Vendor.ZULU), JavaRuntimeService.staged(tmp));
    }

    @Test
    void aCatalogEntryWithNoReleaseTypeIsAnnouncedWithTheLinkAlone() throws IOException {
        check(builtIn("25.0.4"), azul(catalog(false, sha(JRE)), 200), new Download());

        assertEquals(List.of("Java runtime | Java 25.0.5.1 is downloaded and installs the next time JClaw restarts. "
                + "Restart from Settings > Maintenance > Java runtime when it suits you." + LINK), notices());
    }

    @Test
    void nothingHappensOnTheNewestPatchOrWhenTheCatalogCannotBeReached() throws IOException {
        check(builtIn(VERSION), azul(catalog(true, sha(JRE)), 200), new Download());
        assertEquals(List.of(CATALOG_PATH), requests);

        var env = builtIn("25.0.4");
        Interceptor down = chain -> {
            requests.add(path(chain.request()));
            return answer(chain.request(), 503, "unavailable".getBytes(StandardCharsets.UTF_8));
        };
        check(env, new OkHttpClient.Builder().addInterceptor(down).build(), new Download());

        assertEquals(List.of(CATALOG_PATH, CATALOG_PATH), requests);
        assertEquals(List.of(), notices());
        assertEquals(List.of(), JavaRuntimePatchCheck.handled(env.app()));
        assertFalse(Files.exists(tmp.resolve("jre.staged")));
    }

    @Test
    void aPatchStagedFromSettingsIsNotDownloadedAgainNorAnnouncedNorStagedAgainOnceCanceled() throws IOException {
        var env = builtIn("25.0.4");
        stageByHand(VERSION, ARCHIVE);
        var azul = azul(catalog(true, sha(JRE)), 200);

        check(env, azul, new Download());

        assertEquals(List.of(CATALOG_PATH), requests);
        assertEquals(List.of(), notices());
        assertEquals(List.of(ARCHIVE), JavaRuntimePatchCheck.handled(env.app()));

        assertTrue(JavaRuntimePatchCheck.cancel(env, new Download()));
        check(env, azul, new Download());

        assertEquals(List.of(CATALOG_PATH, CATALOG_PATH), requests);
        assertFalse(Files.exists(tmp.resolve("jre.staged")));
        assertEquals(List.of(ARCHIVE), JavaRuntimePatchCheck.handled(env.app()));
    }

    @Test
    void aPatchNewerThanTheOnesHandledIsStagedAndRecordedBesideThem() throws IOException {
        var env = builtIn("25.0.4");
        Files.createDirectories(env.app().resolve("data"));
        Files.writeString(env.app().resolve("data/java-runtime.notified"), OLDER + "\n");

        check(env, azul(catalog(true, sha(JRE)), 200), new Download());

        assertEquals(VERSION, JavaRuntimeService.staged(tmp).version());
        assertEquals(List.of(OLDER, ARCHIVE), JavaRuntimePatchCheck.handled(env.app()));
    }

    @Test
    void whileTheSettingsDownloadRunsTheCheckLeavesItAlone() throws IOException {
        var env = builtIn("25.0.4");
        var running = new Download();
        running.begin();

        check(env, azul(catalog(true, sha(JRE)), 200), running);

        assertEquals(List.of(CATALOG_PATH), requests);
        assertTrue(running.active());
        assertEquals(List.of(), notices());
        assertEquals(List.of(), JavaRuntimePatchCheck.handled(env.app()));
    }

    @Test
    void withNoOneToTellNothingIsDownloadedAndTheNextRunTriesAgain() throws IOException {
        var env = builtIn("25.0.4");
        var azul = azul(catalog(true, sha(JRE)), 200);
        Fixtures.deleteDatabase();

        var events = EventLogger.captureForTest(() -> check(env, azul, new Download()));

        assertEquals(List.of("WARN No agent 'jf536-operator' to tell about Java 25.0.5.1"), runtimeEvents(events));
        assertEquals(List.of(CATALOG_PATH), requests);
        assertEquals(List.of(), JavaRuntimePatchCheck.handled(env.app()));

        Tx.run(() -> AgentService.create(recipient, "openrouter", "gpt-4.1"));
        check(env, azul, new Download());

        assertEquals(VERSION, JavaRuntimeService.staged(tmp).version());
        assertEquals(1, notices().size());
    }

    @Test
    void aHomeThatIsNotWritableIsLoggedAndTriedAgainAtTheNextRun() throws IOException {
        Assumptions.assumeFalse("root".equals(System.getProperty("user.name")), "root writes past any permission");
        var env = builtIn("25.0.4");
        var azul = azul(catalog(true, sha(JRE)), 200);
        Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("r-xr-xr-x"));
        List<EventLogger.Captured> events;
        try {
            events = EventLogger.captureForTest(() -> check(env, azul, new Download()));
        } finally {
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rwxr-xr-x"));
        }

        assertEquals(List.of("WARN Java 25.0.5.1 was not downloaded on its own: " + tmp + " is not writable, so a new "
                + "Java cannot be staged beside " + tmp.resolve("jre") + "."), runtimeEvents(events));
        assertEquals(List.of(CATALOG_PATH), requests);
        assertEquals(List.of(), notices());
        assertEquals(List.of(), JavaRuntimePatchCheck.handled(env.app()));
    }

    @Test
    void aRespinOfAVersionAlreadyHandledIsABuildOfItsOwnAndIsStaged() throws IOException {
        var env = builtIn("25.0.4");
        check(env, azul(catalog(true, sha(JRE)), 200), new Download());
        assertTrue(JavaRuntimePatchCheck.cancel(env, new Download()));

        check(env, azul(respun(catalog(true, sha(JRE))), 200), new Download());

        assertEquals(new JavaRuntimeService.Staged(VERSION, sha(JRE), RESPIN, "25.38.203", Vendor.ZULU), JavaRuntimeService.staged(tmp));
        assertEquals(List.of(CATALOG_PATH, CDN_PATH, CATALOG_PATH, "cdn.azul.com/zulu/bin/" + RESPIN), requests);
        assertEquals(2, notices().size());
        assertEquals(List.of(ARCHIVE, RESPIN), JavaRuntimePatchCheck.handled(env.app()));
    }

    @Test
    void aRespinOfTheRunningJavaVersionIsStagedAndNamedByItsZuluBuild() throws IOException {
        // Azul's out-of-cycle fix: Zulu 25.38.203 keeps the Java 25.0.5.1 that Zulu 25.38.201 runs.
        var env = builtIn(VERSION, "Zulu25.38+201-CA");

        check(env, azul(respun(catalog(true, sha(JRE))), 200), new Download());

        assertEquals(new JavaRuntimeService.Staged(VERSION, sha(JRE), RESPIN, "25.38.203", Vendor.ZULU), JavaRuntimeService.staged(tmp));
        assertEquals(List.of("Java runtime | Zulu 25.38.203, a security and bug-fix release, is downloaded and installs "
                + "the next time JClaw restarts. Restart from Settings > Maintenance > Java runtime when it suits you."
                + LINK), notices());
        assertEquals(List.of(CATALOG_PATH, "cdn.azul.com/zulu/bin/" + RESPIN), requests);
        assertEquals(List.of(RESPIN), JavaRuntimePatchCheck.handled(env.app()));
    }

    @Test
    void theRunningZuluBuildAndALaterOneOfItsJavaVersionAreNeitherStagedNorAnnounced() throws IOException {
        check(builtIn(VERSION, "Zulu25.38+203-CA"), azul(respun(catalog(true, sha(JRE))), 200), new Download());
        check(builtIn(VERSION, "Zulu25.38+204-CA"), azul(respun(catalog(true, sha(JRE))), 200), new Download());

        assertEquals(List.of(CATALOG_PATH, CATALOG_PATH), requests);
        assertFalse(Files.exists(tmp.resolve("jre.staged")));
        assertEquals(List.of(), notices());
        assertEquals(List.of(), JavaRuntimePatchCheck.handled(tmp.resolve("jclaw")));
    }

    @Test
    void aPatchCanceledInSettingsBeforeTheCheckRanIsNotDownloadedAgain() throws IOException {
        var env = builtIn("25.0.4");
        stageByHand(VERSION, ARCHIVE);

        assertTrue(JavaRuntimePatchCheck.cancel(env, new Download()));
        check(env, azul(catalog(true, sha(JRE)), 200), new Download());

        assertEquals(List.of(CATALOG_PATH), requests);
        assertFalse(Files.exists(tmp.resolve("jre.staged")));
        assertEquals(List.of(), notices());
        assertEquals(List.of(ARCHIVE), JavaRuntimePatchCheck.handled(env.app()));
    }

    @Test
    void withTheKeyOffAPatchAlreadyDownloadedFromSettingsIsNotAnnounced() throws IOException {
        ConfigService.set(JavaRuntimePatchCheck.AUTO_DOWNLOAD_KEY, "false");
        var env = builtIn("25.0.4");
        stageByHand(VERSION, ARCHIVE);

        check(env, azul(catalog(true, sha(JRE)), 200), new Download());
        check(env, azul(catalog(true, sha(JRE)), 200), new Download());

        assertEquals(List.of(), notices());
        assertEquals(List.of(CATALOG_PATH, CATALOG_PATH), requests);
        assertEquals(new JavaRuntimeService.Staged(VERSION, sha(JRE), ARCHIVE), JavaRuntimeService.staged(tmp));
        assertEquals(List.of(ARCHIVE), JavaRuntimePatchCheck.handled(env.app()));
    }

    @Test
    void aNewerPatchReplacesAnOlderOneWaitingForTheRestartAndIsAnnouncedOnce() throws IOException {
        var env = builtIn("25.0.4");
        stageByHand("25.0.5", OLDER);
        var azul = azul(catalog(true, sha(JRE)), 200);

        check(env, azul, new Download());
        check(env, azul, new Download());

        assertEquals(new JavaRuntimeService.Staged(VERSION, sha(JRE), ARCHIVE, "25.38.201", Vendor.ZULU), JavaRuntimeService.staged(tmp));
        assertEquals(List.of("manifest", ARCHIVE), listing(tmp.resolve("jre.staged")));
        assertEquals(List.of("Java runtime | Java 25.0.5.1, a security and bug-fix release, is downloaded in place of "
                + "Java 25.0.5 and installs the next time JClaw restarts. Restart from Settings > Maintenance > Java "
                + "runtime when it suits you." + LINK), notices());
        assertEquals(List.of(CATALOG_PATH, CDN_PATH, CATALOG_PATH), requests);
        assertEquals(List.of(ARCHIVE), JavaRuntimePatchCheck.handled(env.app()));
    }

    @Test
    void withTheKeyOffANewerPatchThanTheOneWaitingIsAnnouncedOnceAndNothingIsDownloaded() throws IOException {
        ConfigService.set(JavaRuntimePatchCheck.AUTO_DOWNLOAD_KEY, "false");
        var env = builtIn("25.0.4");
        stageByHand("25.0.5", OLDER);
        var azul = azul(catalog(true, sha(JRE)), 200);

        check(env, azul, new Download());
        check(env, azul, new Download());

        assertEquals(List.of("Java runtime | Java 25.0.5.1, a security and bug-fix release, is available, newer than "
                + "the Java 25.0.5 waiting for the next restart. To install it instead, cancel Java 25.0.5 in Settings "
                + "> Maintenance > Java runtime and download it there." + LINK), notices());
        assertEquals(List.of(CATALOG_PATH, CATALOG_PATH), requests);
        assertEquals(new JavaRuntimeService.Staged("25.0.5", sha(JRE), OLDER), JavaRuntimeService.staged(tmp));
        assertEquals(List.of(ARCHIVE), JavaRuntimePatchCheck.handled(env.app()));
    }

    @Test
    void onTemurinTheCheckAsksAdoptiumStagesItsPatchAndLinksThatReleasesOwnNotes() throws IOException {
        var env = temurinBuiltIn("25.0.5");

        check(env, adoptium(temurinCatalog(sha(JRE))), new Download());

        assertEquals(new JavaRuntimeService.Staged(VERSION, sha(JRE), TEMURIN_ARCHIVE, null, Vendor.TEMURIN),
                JavaRuntimeService.staged(tmp));
        assertEquals("version=" + VERSION + "\nsha256=" + sha(JRE) + "\narchive=" + TEMURIN_ARCHIVE + "\nvendor=Temurin\n",
                Files.readString(tmp.resolve("jre.staged/manifest")));
        assertEquals(List.of("Java runtime | Java 25.0.5.1 is downloaded and installs the next time JClaw restarts. "
                + "Restart from Settings > Maintenance > Java runtime when it suits you." + TEMURIN_LINK), notices());
        assertEquals(List.of(ADOPTIUM_PATH, TEMURIN_DOWNLOAD_PATH), requests);
        assertEquals(List.of(TEMURIN_ARCHIVE), JavaRuntimePatchCheck.handled(env.app()));
    }

    @Test
    void aTemurinDownloadThatFailsItsSha256StagesNothing() throws IOException {
        var env = temurinBuiltIn("25.0.5");
        var mismatched = adoptium(temurinCatalog(TEMURIN_SHA));

        var events = EventLogger.captureForTest(() -> check(env, mismatched, new Download()));

        assertEquals(List.of("INFO Downloading Temurin 25.0.5.1 for the next start to install",
                "WARN Java runtime download failed: checksum mismatch: Adoptium's catalog gives " + TEMURIN_SHA
                        + ", the download is " + sha(JRE)), runtimeEvents(events));
        assertFalse(Files.exists(tmp.resolve("jre.staged")));
        assertEquals(List.of(), notices());
        assertEquals(List.of(), JavaRuntimePatchCheck.handled(env.app()));
    }

    @Test
    void aHostTemurinIsToldOfAdoptiumsPatch() throws IOException {
        var javaHome = Files.createDirectories(tmp.resolve("usr/lib/jvm/temurin-25-jdk"));
        var env = new Environment(app(), javaHome, "25.0.4", "Eclipse Adoptium", "Linux", "amd64", false);

        check(env, adoptium(temurinCatalog(sha(JRE))), new Download());

        assertEquals(List.of("Java runtime | Java 25.0.5.1 is available for the Java JClaw runs on, Java 25.0.4 at "
                + javaHome + ". Update that Java the way you installed it." + TEMURIN_LINK), notices());
        assertEquals(List.of(ADOPTIUM_PATH), requests);
        assertFalse(Files.exists(tmp.resolve("jre.staged")));
    }

    @Test
    void aSwitchWaitingForTheNextStartIsNeitherReplacedByAPatchOfTheJavaItLeavesNorAnnounced() throws IOException {
        var env = builtIn("25.0.4");
        stageByHand(VERSION, TEMURIN_ARCHIVE, "Temurin");

        check(env, azul(catalog(true, sha(JRE)), 200), new Download());

        assertEquals(new JavaRuntimeService.Staged(VERSION, sha(JRE), TEMURIN_ARCHIVE, null, Vendor.TEMURIN),
                JavaRuntimeService.staged(tmp));
        assertEquals(List.of(CATALOG_PATH), requests);
        assertEquals(List.of(), notices());
        assertEquals(List.of(), JavaRuntimePatchCheck.handled(env.app()));
    }

    @Test
    void theRecordKeepsTheNewest32BuildsAndDropsTheOldestFirst() throws IOException {
        var env = builtIn("25.0.4");
        var data = Files.createDirectories(env.app().resolve("data"));
        var earlier = new ArrayList<String>();
        for (int i = 0; i < 31; i++) earlier.add("zulu25.0." + i + "-ca-jre25.0.0-linux_x64.tar.gz");
        Files.write(data.resolve("java-runtime.notified"), earlier);

        check(env, azul(catalog(true, sha(JRE)), 200), new Download());

        var all32 = new ArrayList<>(earlier);
        all32.add(ARCHIVE);
        assertEquals(all32, JavaRuntimePatchCheck.handled(env.app()));

        assertTrue(JavaRuntimePatchCheck.cancel(env, new Download()));
        check(env, azul(respun(catalog(true, sha(JRE))), 200), new Download());

        var newest32 = new ArrayList<>(all32.subList(1, 32));
        newest32.add(RESPIN);
        assertEquals(newest32, JavaRuntimePatchCheck.handled(env.app()));
    }

    // --- helpers ---

    /** A finished download in {@code jre.staged/}, as the Settings button leaves it. */
    private void stageByHand(String version, String archive) throws IOException {
        stageByHand(version, archive, null);
    }

    /** As {@link #stageByHand(String, String)}, the manifest naming {@code vendor} unless it is null. */
    private void stageByHand(String version, String archive, @Nullable String vendor) throws IOException {
        var dir = Files.createDirectories(tmp.resolve("jre.staged"));
        Files.write(dir.resolve(archive), JRE);
        Files.writeString(dir.resolve("manifest"), "version=" + version + "\nsha256=" + sha(JRE) + "\narchive="
                + archive + "\n" + (vendor == null ? "" : "vendor=" + vendor + "\n"));
    }

    /** {@code payload} with {@link #ARCHIVE} respun as {@link #RESPIN}: a newer Zulu build of the same Java. */
    private static String respun(String payload) {
        var entries = JsonParser.parseString(payload).getAsJsonArray();
        for (var el : entries) {
            var o = el.getAsJsonObject();
            if (!ARCHIVE.equals(o.get("name").getAsString())) continue;
            o.addProperty("name", RESPIN);
            o.addProperty("download_url", "https://cdn.azul.com/zulu/bin/" + RESPIN);
            var distro = new JsonArray();
            for (int part : new int[] {25, 38, 203, 0}) distro.add(part);
            o.add("distro_version", distro);
        }
        return entries.toString();
    }


    private void check(Environment env, OkHttpClient azul, Download progress) {
        var lookup = new JavaRuntimeService.Lookup();
        HttpFactories.runWith(azul, () -> JavaRuntimePatchCheck.run(env,
                () -> lookup.latest(true, () -> JavaRuntimeService.fetchLatest(env)), progress,
                JavaRuntimeService::download, recipient));
    }

    /** {@code <tmp>/jclaw} on {@code <tmp>/jre}, the installer's layout, running {@code version}. */
    private Environment builtIn(String version) throws IOException {
        return builtIn(version, null);
    }

    /** As {@link #builtIn(String)}, the JVM reporting {@code vendorVersion} as its {@code java.vendor.version}. */
    private Environment builtIn(String version, @Nullable String vendorVersion) throws IOException {
        var javaHome = Files.createDirectories(tmp.resolve("jre/zulu25-linux_x64"));
        Files.writeString(javaHome.resolve("release"), "JAVA_VERSION=\"" + version + "\"\nLIBC=\"default\"\n");
        return new Environment(app(), javaHome, version, "Azul Systems, Inc.", vendorVersion, "Linux", "amd64", false);
    }

    /** {@code <tmp>/jclaw} on a Temurin JRE at {@code <tmp>/jre}, as a switch from Settings leaves it. */
    private Environment temurinBuiltIn(String version) throws IOException {
        var javaHome = Files.createDirectories(tmp.resolve("jre/jdk-" + version + "+8-jre"));
        Files.writeString(javaHome.resolve("release"), "IMPLEMENTOR=\"Eclipse Adoptium\"\nJAVA_VERSION=\"" + version
                + "\"\nLIBC=\"default\"\n");
        return new Environment(app(), javaHome, version, "Eclipse Adoptium", "Temurin-" + version + "+8", "Linux", "amd64",
                false);
    }

    /** Adoptium's recorded answer for Java 25 on Linux x64, with its JRE's hash set to {@code sha}. */
    private static String temurinCatalog(String sha) throws IOException {
        return Files.readString(Play.applicationPath.toPath().resolve("test/adoptium-catalog-jre25-linux-x64.json"))
                .replace(TEMURIN_SHA, sha);
    }

    /**
     * Adoptium's API answering {@code payload}, and GitHub redirecting its binary to the asset host that serves
     * {@link #JRE}, as it does for every Adoptium download. Any other host fails.
     */
    private OkHttpClient adoptium(String payload) {
        Interceptor canned = chain -> {
            var request = chain.request();
            requests.add(path(request));
            return switch (request.url().host()) {
                case "api.adoptium.net" -> answer(request, 200, payload.getBytes(StandardCharsets.UTF_8));
                case "github.com" -> answer(request.newBuilder().url("https://release-assets.githubusercontent.com/"
                        + "github-production-release-asset/901810329/19a11c1b").build(), 200, JRE);
                default -> answer(request, 500, "unexpected".getBytes(StandardCharsets.UTF_8));
            };
        };
        return new OkHttpClient.Builder().addInterceptor(canned).build();
    }

    private Path app() throws IOException {
        return Files.createDirectories(tmp.resolve("jclaw/conf")).getParent();
    }

    /**
     * The catalog's recorded answer, with {@link #ARCHIVE}'s hash set to {@code sha} and, unless
     * {@code withType}, every {@code release_type} removed.
     */
    private static String catalog(boolean withType, String sha) throws IOException {
        var entries = JsonParser.parseString(Files.readString(
                Play.applicationPath.toPath().resolve("test/azul-catalog-jre25-linux-x64.json"))).getAsJsonArray();
        var out = new JsonArray();
        for (var el : entries) {
            var o = el.getAsJsonObject();
            if (ARCHIVE.equals(o.get("name").getAsString())) o.addProperty("sha256_hash", sha);
            if (!withType) o.remove("release_type");
            out.add(o);
        }
        return out.toString();
    }

    /** Azul's catalog answering {@code payload}, and its CDN answering {@code downloadStatus} with {@link #JRE}. */
    private OkHttpClient azul(String payload, int downloadStatus) {
        Interceptor canned = chain -> {
            var request = chain.request();
            requests.add(path(request));
            if ("api.azul.com".equals(request.url().host())) {
                return answer(request, 200, payload.getBytes(StandardCharsets.UTF_8));
            }
            return downloadStatus == 200 ? answer(request, 200, JRE)
                    : answer(request, downloadStatus, "unavailable".getBytes(StandardCharsets.UTF_8));
        };
        return new OkHttpClient.Builder().addInterceptor(canned).build();
    }

    private static String path(Request request) {
        return request.url().host() + request.url().encodedPath();
    }

    private static Response answer(Request request, int code, byte[] body) {
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("canned")
                .body(ResponseBody.create(body, null)).build();
    }

    /** The recipient's notifications as the bar shows them: heading, text, then the link's label and address. */
    private List<String> notices() {
        return Tx.run(() -> {
            List<Notification> rows = Notification.find("agent.name = ?1 ORDER BY id", recipient).fetch();
            var out = new ArrayList<String>();
            for (var n : rows) out.add(n.title + " | " + n.content + " | " + n.linkLabel + " " + n.linkUrl);
            return out;
        });
    }

    private static List<String> runtimeEvents(List<EventLogger.Captured> events) {
        return events.stream().filter(e -> "java-runtime".equals(e.category()))
                .map(e -> e.level() + " " + e.message()).toList();
    }

    private static List<String> listing(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private static String sha(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
