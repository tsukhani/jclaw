import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A published image carries the newest Zulu and OS packages of its build day only if the build re-runs the
 * Dockerfile's apt step. A reused layer shipped v1.0.24 with a Zulu older than the Java4Shell fix (JF-533).
 */
class ReleaseImageRebuildConformanceTest extends UnitTest {
    private static final Pattern FROM_LINE = Pattern.compile("(?mi)^FROM\\s.*$");
    private static final Pattern STAGE_NAME = Pattern.compile("(?i)\\sAS\\s+(\\S+)\\s*$");
    private static final Pattern PLATFORM_LOOP = Pattern.compile("for platform in ([^;\\n]+);");

    @Test
    void theCandidateImageRebuildsTheDockerfilesFinalStageFromAFreshlyPulledBase() throws IOException {
        var options = buildOptions(stage("Candidate Image"));
        assertTrue(options.contains("--pull"),
                () -> "the candidate image builds on whatever base the builder cached: add --pull to its buildx build, "
                        + "which has " + options);
        assertEquals(List.of(finalStage(read("Dockerfile"))), values(options, "--no-cache-filter"),
                () -> "the candidate image must rebuild the Dockerfile's final stage uncached, by its name, or a release "
                        + "ships the apt layer an earlier build cached; its buildx build has " + options);
    }

    @Test
    void theDevContainerPublishRebuildsEveryLayer() throws IOException {
        var options = buildOptions(stage("Publish Dev Container"));
        assertTrue(options.contains("--no-cache"),
                () -> "the dev container publish reuses cached layers, so it ships an old zulu25-jdk: add --no-cache to "
                        + "its buildx build, which has " + options);
    }

    @Test
    void theCandidateLogsTheJavaOfEachPlatformItBuilds() throws IOException {
        var body = stage("Candidate Image");
        var loop = PLATFORM_LOOP.matcher(body);
        assertTrue(loop.find(), "the Candidate Image stage no longer prints the image's java -version per platform");
        assertEquals(List.of("linux/amd64", "linux/arm64"), List.of(loop.group(1).trim().split("\\s+")));
        var after = body.substring(loop.end());
        assertTrue(after.substring(0, after.indexOf("done")).contains(
                        "\"${image%@*}@$digest\" -c 'exec \"$JAVA_HOME/bin/java\" -version'"),
                () -> "the platform loop no longer runs java -version in each platform's own image: " + after);
        assertEquals(buildPlatforms(buildOptions(body)), List.of(loop.group(1).trim().split("\\s+")),
                "the stage must print the Java of every platform it builds, and only those");
    }

    @Test
    void eachPlatformsJavaIsReadFromItsOwnManifestNeverFromTheImagesIndexDigest() throws IOException {
        var body = stage("Candidate Image");
        var loop = PLATFORM_LOOP.matcher(body);
        assertTrue(loop.find(), "the Candidate Image stage no longer prints the image's java -version per platform");
        assertTrue(body.substring(0, loop.start()).contains("index=$(docker buildx imagetools inspect --raw \"$image\")"),
                () -> "the platform loop must pick each platform's digest from the image's index: " + body);
        var inLoop = body.substring(loop.end(), body.indexOf("done", loop.end()));
        assertFalse(inLoop.contains("\"$image\""),
                () -> "the platform loop pulls or runs the index digest itself, which Docker stores for one platform "
                        + "only, so the second platform fails with 'cannot overwrite digest': " + inLoop);
        assertTrue(inLoop.contains("docker pull -q --platform \"$platform\" \"${image%@*}@$digest\""),
                () -> "the platform loop no longer pulls each platform by its own digest: " + inLoop);
        assertTrue(inLoop.contains("[ -n \"$digest\" ] || {"),
                () -> "the platform loop must stop when the index lists no single manifest for a platform: " + inLoop);
    }

    private static String read(String file) throws IOException {
        return Files.readString(Path.of(Play.applicationPath.getAbsolutePath()).resolve(file));
    }

    /** The Jenkinsfile's text from {@code stage('<name>')} to the next top-level stage. */
    private static String stage(String name) throws IOException {
        var jenkinsfile = read("Jenkinsfile");
        int start = jenkinsfile.indexOf("stage('" + name + "')");
        assertTrue(start >= 0, () -> "the Jenkinsfile has no stage('" + name + "')");
        int end = jenkinsfile.indexOf("\n        stage('", start + 1);
        return jenkinsfile.substring(start, end < 0 ? jenkinsfile.length() : end);
    }

    /**
     * The words of the stage's {@code docker buildx build} command, read across its backslash-continued lines. Only a
     * line that starts with it counts: the Publish Dev Container stage also quotes one in a comment.
     */
    private static List<String> buildOptions(String body) {
        var lines = body.split("\n");
        int first = 0;
        while (first < lines.length && !lines[first].strip().startsWith("docker buildx build")) first++;
        assertTrue(first < lines.length, () -> "no docker buildx build command in: " + body);
        var words = new ArrayList<String>();
        for (int i = first; i < lines.length; i++) {
            var text = i == first ? lines[i].strip().substring("docker buildx build".length()) : lines[i].strip();
            boolean continues = text.endsWith("\\\\");
            if (continues) text = text.substring(0, text.length() - 2).strip();
            for (var word : text.strip().split("\\s+")) if (!word.isEmpty()) words.add(word);
            if (!continues) break;
        }
        return words;
    }

    /** Every value {@code flag} takes, as {@code --flag value} or {@code --flag=value}. */
    private static List<String> values(List<String> options, String flag) {
        var found = new ArrayList<String>();
        for (int i = 0; i < options.size(); i++) {
            var option = options.get(i);
            if (option.equals(flag) && i + 1 < options.size()) found.add(options.get(i + 1));
            else if (option.startsWith(flag + "=")) found.add(option.substring(flag.length() + 1));
        }
        return found;
    }

    private static List<String> buildPlatforms(List<String> options) {
        var platforms = values(options, "--platform");
        assertEquals(1, platforms.size(), () -> "expected one --platform list in " + options);
        return List.of(platforms.getFirst().split(","));
    }

    /** The name of the Dockerfile's last stage, the one {@code --no-cache-filter} has to name. */
    private static String finalStage(String dockerfile) {
        var from = FROM_LINE.matcher(dockerfile);
        String last = null;
        while (from.find()) last = from.group();
        assertNotNull(last, "the Dockerfile has no FROM line");
        var name = STAGE_NAME.matcher(last);
        assertTrue(name.find(), "the Dockerfile's final stage is unnamed, so no --no-cache-filter can name it: " + last);
        return name.group(1);
    }
}
