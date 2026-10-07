import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.grapheval.CertificateDocument;
import services.grapheval.CertificationSplit;
import services.grapheval.Certifier;
import services.grapheval.Configuration;
import services.grapheval.Configuration.ClassSetting;
import services.grapheval.Fingerprints;
import services.grapheval.ResolutionCalibration;
import services.grapheval.ResolutionCalibration.Counts;
import services.grapheval.SequenceScorer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** JCLAW-1368, JCLAW-1369: the certificate as canonical JSON, its stable id, its configuration and its reader. */
class CertificateDocumentTest extends UnitTest {

    private static final String SCHEMA = "v3@aaaaaaaaaaaa";
    private static final String EXTRACTION = "x@bbbbbbbbbbbb";
    private static final String DIGEST = "sha256:cccc";
    private Path root;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createTempDirectory("certificates");
    }

    @AfterEach
    void tearDown() throws IOException {
        try (var walk = Files.walk(root)) {
            for (var p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    private static final Certifier.Power POWER = new Certifier.Power(0.5, 0.2, 0.1);

    private static Certifier.Gate gate(String name, String state, Double threshold, int n, int k, double bound,
                                       double recall) {
        return new Certifier.Gate(name, state, threshold, n, k, bound, recall, POWER, List.of(), null);
    }

    private static CertificateDocument certificate(String model, int termsK) {
        return certificate(model, termsK, null);
    }

    private static CertificateDocument certificate(String model, int termsK, ResolutionCalibration.Walk shortlist) {
        var split = new CertificationSplit("cert-2026-10", "cases", 1356, List.of("c003"), Map.of("c003", "9f1c2a7d4e05"),
                "cases@111111111111", "sequences@222222222222", "guide@333333333333", SCHEMA, 0.95,
                List.of("works_at", "kind_of"));
        var classes = new TreeMap<String, ClassSetting>();
        classes.put("status", new ClassSetting(Certifier.CLASS_CERTIFIED, 0.85));
        classes.put("time", new ClassSetting(Certifier.PROVISIONAL, 0.90));
        classes.put("negation", new ClassSetting(Certifier.DISABLED, null));
        var reached = new Configuration(0.85, new TreeMap<>(Map.of("works_at", 0.90)), classes);
        var sequencing = new Certifier.Sequencing(0.95, gate("terms", Certifier.ON, 0.85, 410, termsK, 0.022, 0.71),
                List.of(gate("works_at", Certifier.ON, 0.90, 71, 0, 0.041, 0.58),
                        gate("kind_of", Certifier.OFF, null, 6, 0, 0.393, 0.12)),
                List.of(new Certifier.ClassGate("status", Certifier.CLASS_CERTIFIED, 0.85, 262, 5, 0.040, POWER, List.of()),
                        new Certifier.ClassGate("time", Certifier.PROVISIONAL, 0.90, 84, 2, 0.073, POWER, List.of()),
                        new Certifier.ClassGate("negation", Certifier.DISABLED, null, 10, 0, 0.3, POWER, List.of())),
                new Certifier.Pooled(Certifier.G_WRITTEN, 1650, 41, 0.032, true, POWER),
                new Certifier.Pooled(Certifier.G_TRAP, 228, 4, 0.040, true, POWER), List.of(), reached, true, List.of(),
                List.of());
        var lineage = new Certifier.ClassWalk("lineage", 0.90, Certifier.PROVISIONAL, 80, 1, 0.058, List.of());
        var timeline = SequenceScorer.timeline(240, 205, 3, 10, 2, 200);
        return CertificateDocument.of(model, DIGEST, Certifier.CERTIFIED, split, "guide@333333333333", SCHEMA, EXTRACTION,
                sequencing, lineage, timeline, SequenceScorer.REPORTED, 0.50, shortlist);
    }

    @Test
    void theCertificateIsCanonicalJsonOneFilePerModelAndRoundTrips() throws IOException {
        var cert = certificate("tev1:latest", 4);
        cert.write(root);
        var file = root.resolve("certificates/tev1_latest.json");
        assertTrue(Files.exists(file));
        var text = Files.readString(file);
        assertEquals(Fingerprints.canonical(JsonParser.parseString(text)).toString(), text, "keys sorted, compact");
        assertEquals(cert, CertificateDocument.parse(text));
        var json = cert.json();
        assertTrue(json.getAsJsonObject("classes").has("lineage"), "the certificate holds the lineage class");
        assertEquals("reported", json.getAsJsonObject("timeline").get("result").getAsString());
        assertEquals(205, json.getAsJsonObject("timeline").get("definite").getAsInt());
        assertTrue(json.getAsJsonObject("relations").getAsJsonObject("kind_of").get("threshold").isJsonNull());
        assertEquals(Map.of("state", "disabled").keySet(),
                json.getAsJsonObject("classes").getAsJsonObject("negation").keySet());
    }

    @Test
    void theCertificateStatesWhichSetCertifiedEachPartAndRefusesAnUnknownOne() throws IOException {
        var cert = certificate("tev1", 4);
        var sets = cert.json().getAsJsonObject("sets");
        assertEquals(Map.of("terms", "heldout", "relations", "heldout", "G_written", "heldout", "recall", "heldout",
                "classes", "cases", "G_trap", "cases", "lineage", "sequences", "timeline", "sequences"),
                sets.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                        e -> e.getValue().getAsString())));
        assertEquals(cert, CertificateDocument.parse(cert.text()));
        var file = CertificateDocument.path(root, "tev1");
        Files.createDirectories(file.getParent());
        var extra = cert.json().deepCopy();
        extra.getAsJsonObject("sets").addProperty("valence", "cases");
        Files.writeString(file, extra.toString());
        assertEquals("certificate sets: unknown key 'valence'",
                CertificateDocument.read(root, "tev1", SCHEMA, EXTRACTION, DIGEST).reason());
        var missing = cert.json().deepCopy();
        missing.remove("sets");
        Files.writeString(file, missing.toString());
        assertEquals("certificate: 'sets' is required",
                CertificateDocument.read(root, "tev1", SCHEMA, EXTRACTION, DIGEST).reason());
    }

    @Test
    void theIdIsStableForTheSameContentAndCoversAllOfIt() {
        var a = certificate("tev1", 4);
        assertEquals(a.id(), certificate("tev1", 4).id());
        assertTrue(a.id().matches("cert@[0-9a-f]{12}"), a.id());
        var content = a.json().deepCopy();
        content.remove("id");
        assertEquals(Fingerprints.hex12("cert", content), a.id());
        assertNotEquals(a.id(), certificate("tev1", 5).id());
    }

    @Test
    void itsConfigurationEnablesWhatPassedAndADisabledClassWritesNull() {
        var c = certificate("tev1", 4).toConfiguration();
        assertEquals(0.85, c.terms());
        assertEquals(Map.of("works_at", 0.90), c.relations());
        assertEquals(new ClassSetting(Certifier.DISABLED, null), c.classes().get("negation"));
        assertEquals(new ClassSetting(Certifier.CLASS_CERTIFIED, 0.85), c.classes().get("status"));
        assertFalse(c.classes().containsKey("lineage"), "lineage is the run's own walk, not configured");
        assertNull(c.statementClasses().negation());
    }

    @Test
    void theReaderReturnsItOnlyWhenEveryStampMatchesAndNamesWhichDiffers() throws IOException {
        var cert = certificate("tev1", 4);
        cert.write(root);
        assertEquals(cert, CertificateDocument.read(root, "tev1", SCHEMA, EXTRACTION, DIGEST).certificate());
        assertEquals("schema stamp v3@aaaaaaaaaaaa differs from running v3@999999999999",
                CertificateDocument.read(root, "tev1", "v3@999999999999", EXTRACTION, DIGEST).reason());
        assertEquals("extraction stamp x@bbbbbbbbbbbb differs from running x@999999999999",
                CertificateDocument.read(root, "tev1", SCHEMA, "x@999999999999", DIGEST).reason());
        assertEquals("digest sha256:cccc differs from running sha256:dddd",
                CertificateDocument.read(root, "tev1", SCHEMA, EXTRACTION, "sha256:dddd").reason());
        assertNull(CertificateDocument.read(root, "tev1", SCHEMA, "x@999999999999", DIGEST).certificate());
        assertEquals("no certificate for nimble", CertificateDocument.read(root, "nimble", SCHEMA, EXTRACTION, DIGEST)
                .reason());
    }

    @Test
    void theReaderRefusesAnUnknownKeyAndATamperedId() throws IOException {
        var cert = certificate("tev1", 4);
        var file = CertificateDocument.path(root, "tev1");
        Files.createDirectories(file.getParent());
        var extra = cert.json().deepCopy();
        extra.addProperty("allowList", "everything");
        Files.writeString(file, extra.toString());
        assertEquals("certificate: unknown key 'allowList'",
                CertificateDocument.read(root, "tev1", SCHEMA, EXTRACTION, DIGEST).reason());
        var nested = cert.json().deepCopy();
        nested.getAsJsonObject("terms").addProperty("weight", 1);
        Files.writeString(file, nested.toString());
        assertEquals("certificate terms: unknown key 'weight'",
                CertificateDocument.read(root, "tev1", SCHEMA, EXTRACTION, DIGEST).reason());
        var tampered = cert.json().deepCopy();
        tampered.getAsJsonObject("terms").addProperty("k", 0);
        Files.writeString(file, tampered.toString());
        assertTrue(CertificateDocument.read(root, "tev1", SCHEMA, EXTRACTION, DIGEST).reason().contains("is not its content's"));
    }

    private static ResolutionCalibration.Walk walk() {
        var counts = Map.of(0.95, new Counts(60, 0), 0.90, new Counts(74, 0), 0.85, new Counts(100, 9));
        return ResolutionCalibration.walk(List.of(0.95, 0.90, 0.85), counts::get);
    }

    @Test
    void theOptionalResolutionSectionIsReadAndItsKeysAreStillStrict() throws IOException {
        assertNull(certificate("tev1", 4).shortlistThreshold());
        assertFalse(certificate("tev1", 4).json().has("resolution"), "written only with a threshold");
        assertFalse(certificate("tev1", 4, ResolutionCalibration.walk(List.of(0.9), _ -> new Counts(10, 0))).json()
                .has("resolution"));
        var cert = certificate("tev1", 4, walk());
        assertNotEquals(certificate("tev1", 4).id(), cert.id(), "the id covers the section");
        var shortlist = cert.json().getAsJsonObject("resolution").getAsJsonObject("shortlist");
        assertEquals(74, shortlist.get("n").getAsInt());
        assertEquals(0, shortlist.get("k").getAsInt());
        assertEquals(Certifier.upperBound(0, 74), shortlist.get("bound").getAsDouble());
        cert.write(root);
        var read = CertificateDocument.read(root, "tev1", SCHEMA, EXTRACTION, DIGEST).certificate();
        assertNotNull(read);
        assertEquals(0.90, read.shortlistThreshold());

        var file = CertificateDocument.path(root, "tev1");
        var extra = cert.json().deepCopy();
        extra.getAsJsonObject("resolution").addProperty("fuzzy", 0.9);
        Files.writeString(file, extra.toString());
        assertEquals("certificate resolution: unknown key 'fuzzy'",
                CertificateDocument.read(root, "tev1", SCHEMA, EXTRACTION, DIGEST).reason());
        var nested = cert.json().deepCopy();
        nested.getAsJsonObject("resolution").getAsJsonObject("shortlist").addProperty("weight", 1);
        Files.writeString(file, nested.toString());
        assertEquals("certificate resolution shortlist: unknown key 'weight'",
                CertificateDocument.read(root, "tev1", SCHEMA, EXTRACTION, DIGEST).reason());
        var rootKey = cert.json().deepCopy();
        rootKey.addProperty("resolutions", 1);
        Files.writeString(file, rootKey.toString());
        assertEquals("certificate: unknown key 'resolutions'",
                CertificateDocument.read(root, "tev1", SCHEMA, EXTRACTION, DIGEST).reason());
    }
}
