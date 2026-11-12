import channels.TelegramOutboundPlanner;
import models.Agent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.AgentService;
import tools.DocumentsTool;
import tools.FileSystemTools;
import utils.ToolErrorTemplates;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JF-322: the file and documents tools, and a Telegram reply that links a file, never open, list or send what a
 * skill keeps in its {@code credentials/} folder, whatever route reaches it, while a whole-file write still stores
 * a value there and the rest of the skill folder reads as before.
 */
class SkillCredentialsFenceTest extends UnitTest {

    private static final String SECRET = "sk-test-fence-0123456789";
    private static final String KEY = "skills/demo/credentials/key.json";

    private final FileSystemTools files = new FileSystemTools();
    private final DocumentsTool documents = new DocumentsTool();
    private Agent agent;
    private Path workspace;

    @BeforeEach
    void seedASkillWithASecret() throws Exception {
        agent = AgentService.create("cred-fence-" + UUID.randomUUID().toString().substring(0, 8), "openrouter", "gpt-4.1");
        workspace = AgentService.workspacePath(agent.name);
        Files.createDirectories(workspace.resolve("skills/demo/credentials"));
        Files.createDirectories(workspace.resolve("skills/demo/scripts"));
        Files.writeString(workspace.resolve("skills/demo/SKILL.md"), "# Demo\n");
        Files.writeString(workspace.resolve("skills/demo/scripts/run.sh"), "echo run\n");
        Files.writeString(workspace.resolve(KEY), "{\"apiKey\":\"" + SECRET + "\"}\n");
    }

    @AfterEach
    void deleteTheAgent() {
        AgentService.delete(agent);
    }

    private String file(String argsJson) {
        return files.execute(argsJson, agent);
    }

    private static String refusal(String path) {
        return ToolErrorTemplates.render(ToolErrorTemplates.fsCredentials(path));
    }

    private String keyOnDisk() throws Exception {
        return Files.readString(workspace.resolve(KEY));
    }

    @Test
    void readingACredentialsFileIsRefusedInPlainWords() {
        assertEquals("Error: 'skills/demo/credentials/key.json' holds a skill's credentials or what a skill saved for"
                        + " its next run, which no agent tool opens.\n\nWhat to check: A skill's own scripts read these"
                        + " files: run the skill's script instead of reading or sending the file. The operator manages a"
                        + " skill's secrets and settings on the Skills page.",
                file("{\"action\":\"readFile\",\"path\":\"" + KEY + "\"}"));
    }

    /** JF-357: what a skill saves for its next run sits outside its folder, fenced the same way. */
    @Test
    void theSkillStateFolderIsFencedLikeACredentialsFolder() throws Exception {
        Files.createDirectories(workspace.resolve("skill-state/demo"));
        Files.writeString(workspace.resolve("skill-state/demo/session.txt"), SECRET + "\n");
        assertEquals(refusal("skill-state/demo/session.txt"),
                file("{\"action\":\"readFile\",\"path\":\"skill-state/demo/session.txt\"}"));
        assertEquals(refusal("skill-state"), file("{\"action\":\"listFiles\",\"path\":\"skill-state\"}"));
        assertEquals(refusal("Skill-State/demo/session.txt"),
                file("{\"action\":\"readFile\",\"path\":\"Skill-State/demo/session.txt\"}"));
        assertEquals(refusal("skill-state/demo/session.txt"),
                documents.execute("{\"action\":\"read\",\"path\":\"skill-state/demo/session.txt\"}", agent));
        assertEquals(SECRET + "\n", Files.readString(workspace.resolve("skill-state/demo/session.txt")));
    }

    @Test
    void listingTheCredentialsFolderIsRefused() {
        assertEquals(refusal("skills/demo/credentials"),
                file("{\"action\":\"listFiles\",\"path\":\"skills/demo/credentials\"}"));
        assertEquals(refusal("skills/demo/credentials/"),
                file("{\"action\":\"listFiles\",\"path\":\"skills/demo/credentials/\"}"));
    }

    @Test
    void appendingAndEditingAreRefusedAndLeaveTheFileAsItWas() throws Exception {
        var before = keyOnDisk();
        assertEquals(refusal(KEY), file("{\"action\":\"appendFile\",\"path\":\"" + KEY + "\",\"content\":\"x\"}"));
        assertEquals(refusal(KEY), file("{\"action\":\"editFile\",\"path\":\"" + KEY
                + "\",\"edits\":[{\"oldText\":\"apiKey\",\"newText\":\"k\"}]}"));
        assertEquals(refusal(KEY), file("{\"action\":\"editLines\",\"path\":\"" + KEY
                + "\",\"operations\":[{\"op\":\"replace\",\"startLine\":1,\"endLine\":1,\"content\":\"{}\"}]}"));
        assertEquals(before, keyOnDisk());
    }

    @Test
    void aWholeFileWriteStillStoresAValueThere() throws Exception {
        assertEquals("File written successfully: token.txt",
                file("{\"action\":\"writeFile\",\"path\":\"skills/demo/credentials/token.txt\",\"content\":\"t-1\"}"));
        assertEquals("t-1", Files.readString(workspace.resolve("skills/demo/credentials/token.txt")));

        assertEquals("File written successfully: key.json",
                file("{\"action\":\"writeFile\",\"path\":\"" + KEY + "\",\"content\":\"{}\"}"));
        assertEquals("{}", keyOnDisk());
    }

    @Test
    void aPatchThatReadsWritesOrMovesIntoTheFolderIsRefused() throws Exception {
        var before = keyOnDisk();
        assertEquals(refusal(KEY), patch("""
                *** Begin Patch
                *** Update File: skills/demo/credentials/key.json
                -{"apiKey":"%s"}
                +{}
                *** End of File
                *** End Patch
                """.formatted(SECRET)));
        assertEquals(refusal(KEY), patch("""
                *** Begin Patch
                *** Delete File: skills/demo/credentials/key.json
                *** End Patch
                """));
        assertEquals(refusal("skills/demo/credentials/new.txt"), patch("""
                *** Begin Patch
                *** Add File: skills/demo/credentials/new.txt
                +x
                *** End of File
                *** End Patch
                """));
        Files.writeString(workspace.resolve("notes.txt"), "a\n");
        assertEquals(refusal("skills/demo/credentials/notes.txt"), patch("""
                *** Begin Patch
                *** Update File: notes.txt
                *** Move to: skills/demo/credentials/notes.txt
                -a
                +b
                *** End of File
                *** End Patch
                """));
        assertEquals(before, keyOnDisk());
        assertEquals("a\n", Files.readString(workspace.resolve("notes.txt")));
        assertFalse(Files.exists(workspace.resolve("skills/demo/credentials/new.txt")));
    }

    private String patch(String body) {
        var escaped = body.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        return file("{\"action\":\"applyPatch\",\"patch\":\"" + escaped + "\"}");
    }

    @Test
    void anotherLetterCaseADotDotRouteOrALinkIsRefusedTheSameWay() throws Exception {
        var upper = "SKILLS/demo/Credentials/key.json";
        assertEquals(refusal(upper), file("{\"action\":\"readFile\",\"path\":\"" + upper + "\"}"));

        var dotDot = "skills/demo/scripts/../credentials/key.json";
        assertEquals(refusal(dotDot), file("{\"action\":\"readFile\",\"path\":\"" + dotDot + "\"}"));

        Files.createDirectories(workspace.resolve("notes"));
        Files.createSymbolicLink(workspace.resolve("notes/key.json"), workspace.resolve(KEY));
        Files.createSymbolicLink(workspace.resolve("notes/creds"), workspace.resolve("skills/demo/credentials"));
        assertEquals(refusal("notes/key.json"), file("{\"action\":\"readFile\",\"path\":\"notes/key.json\"}"));
        assertEquals(refusal("notes/creds/key.json"), file("{\"action\":\"readFile\",\"path\":\"notes/creds/key.json\"}"));
        assertEquals(refusal("notes/creds"), file("{\"action\":\"listFiles\",\"path\":\"notes/creds\"}"));
    }

    @Test
    void theRestOfTheSkillAndOtherCredentialsNamedFoldersReadAsBefore() throws Exception {
        assertEquals("# Demo\n", file("{\"action\":\"readFile\",\"path\":\"skills/demo/SKILL.md\"}"));
        assertEquals("echo run\n", file("{\"action\":\"readFile\",\"path\":\"skills/demo/scripts/run.sh\"}"));
        assertEquals("SKILL.md\ncredentials/\nscripts/", file("{\"action\":\"listFiles\",\"path\":\"skills/demo\"}"));

        Files.createDirectories(workspace.resolve("projects/site/credentials"));
        Files.writeString(workspace.resolve("projects/site/credentials/notes.txt"), "not a skill's\n");
        assertEquals("not a skill's\n",
                file("{\"action\":\"readFile\",\"path\":\"projects/site/credentials/notes.txt\"}"));
    }

    @Test
    void theDocumentsToolRefusesEveryActionThere() throws Exception {
        assertEquals(refusal(KEY), documents.execute("{\"action\":\"readDocument\",\"path\":\"" + KEY + "\"}", agent));
        assertEquals(refusal(KEY),
                documents.execute("{\"action\":\"appendDocument\",\"path\":\"" + KEY + "\",\"content\":\"x\"}", agent));
        assertEquals(refusal("skills/demo/credentials/out.md"), documents.execute(
                "{\"action\":\"writeDocument\",\"path\":\"skills/demo/credentials/out.md\",\"content\":\"x\"}", agent));
        Files.writeString(workspace.resolve("skills/demo/credentials/notes.md"), "# " + SECRET + "\n");
        assertEquals(refusal("skills/demo/credentials/notes.md"), documents.execute(
                "{\"action\":\"renderDocument\",\"path\":\"out.pdf\",\"sourcePath\":\"skills/demo/credentials/notes.md\"}",
                agent));
        assertFalse(Files.exists(workspace.resolve("out.pdf")));
        assertFalse(Files.exists(workspace.resolve("skills/demo/credentials/out.md")));
    }

    @Test
    void theDocumentsToolStillReadsAnOrdinarySkillFile() {
        assertEquals("# Demo\n\n",
                documents.execute("{\"action\":\"readDocument\",\"path\":\"skills/demo/SKILL.md\"}", agent));
    }

    @Test
    void aTelegramReplyLinkingACredentialsFileSendsTheTextAndNotTheFile() throws Exception {
        Files.writeString(workspace.resolve("skills/demo/credentials/backup.pdf"), "fake-pdf");
        var md = "Here: [backup.pdf](skills/demo/credentials/backup.pdf)";
        var segments = TelegramOutboundPlanner.plan(md, agent.name);
        assertEquals(1, segments.size(), () -> "one text segment: " + segments);
        assertTrue(segments.getFirst() instanceof TelegramOutboundPlanner.TextSegment text && text.markdown().equals(md),
                () -> "the link stays text: " + segments);

        Files.writeString(workspace.resolve("report.pdf"), "fake-pdf");
        var sent = TelegramOutboundPlanner.plan("[report.pdf](report.pdf)", agent.name);
        assertTrue(sent.getFirst() instanceof TelegramOutboundPlanner.FileSegment, () -> "file segment: " + sent);
    }
}
