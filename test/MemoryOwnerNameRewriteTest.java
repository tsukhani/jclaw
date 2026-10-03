import memory.MemoryOwnerNameRewrite;
import memory.MemoryStoreFactory;
import models.Agent;
import models.Memory;
import models.MemoryAuthorType;
import models.Message;
import models.MessageRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.ConversationService;
import services.WorkspaceFiles;

import java.time.Instant;

class MemoryOwnerNameRewriteTest extends UnitTest {

    @BeforeEach
    void setup() {
        // Rows are stored through the store, whose @PostPersist hook writes the Lucene index.
        LuceneTestSync.closedForTest();
        Fixtures.deleteDatabase();
        MemoryStoreFactory.reset();
    }

    @AfterEach
    void luceneRelease() {
        LuceneTestSync.release();
    }

    @Test
    void theUserBecomesTheNameInEveryGrammaticalPosition() {
        assertEquals("Ada prefers tea.", MemoryOwnerNameRewrite.named("The user prefers tea.", "Ada"));
        assertEquals("Ada's son Theo goes by Bo.", MemoryOwnerNameRewrite.named("The user's son Theo goes by Bo.", "Ada"));
        assertEquals("Theo is Ada's son, and Ada calls him Bo.",
                MemoryOwnerNameRewrite.named("Theo is the user's son, and the user calls him Bo.", "Ada"));
        assertEquals("Ada's car is blue.", MemoryOwnerNameRewrite.named("The user’s car is blue.", "Ada"));
    }

    @Test
    void wordsThatOnlyStartWithUserAreLeftAlone() {
        var text = "The username is ada and the user-facing page lists the users.";
        assertEquals(text, MemoryOwnerNameRewrite.named(text, "Ada"));
    }

    @Test
    void aGuestTakesACapitalOnlyAtTheStartOfASentence() {
        assertEquals("A guest is vegetarian; a guest's dog is old.",
                MemoryOwnerNameRewrite.named("The user is vegetarian; the user's dog is old.", "a guest"));
    }

    @Test
    void runNamesTheOwnerInActiveMemoriesOnlyOnceUserMdNamesThem() {
        var agent = agent("rewrite-owner");
        var active = store(agent, "The user prefers metric units.");
        var superseded = store(agent, "The user lives in Leeds.");
        Memory old = Memory.findById(superseded);
        old.supersededAt = Instant.now();
        old.save();

        assertEquals(0, MemoryOwnerNameRewrite.run(agent), "nothing changes while USER.md names no owner");
        assertEquals("The user prefers metric units.", textOf(active));

        var statesTheName = store(agent, "The user's name is Ada Lovelace.");
        var oldCopy = store(agent, "The user goes by the name Ada.");
        Memory superseded2 = Memory.findById(oldCopy);
        superseded2.supersededAt = Instant.now();
        superseded2.save();
        WorkspaceFiles.writeWorkspaceFile(agent.name, "USER.md", "# User Information\n\nName: Ada Lovelace\n");
        assertEquals(1, MemoryOwnerNameRewrite.run(agent));
        assertEquals("Ada Lovelace prefers metric units.", textOf(active));
        assertNull(Memory.findById(statesTheName), "USER.md holds the name, so a memory stating it goes");
        assertNull(Memory.findById(oldCopy), "and so do its superseded copies");
        assertEquals("The user lives in Leeds.", textOf(superseded), "a superseded row keeps its text");
        assertEquals(0, MemoryOwnerNameRewrite.run(agent), "a second run finds nothing to rewrite");
    }

    @Test
    void aGuestsMemoryNamesTheGuestNeverTheOwner() {
        var agent = agent("rewrite-guest");
        WorkspaceFiles.writeWorkspaceFile(agent.name, "USER.md", "# User Information\n\nName: Ada\n");
        var conversation = ConversationService.create(agent, "telegram", "group-1", "group");
        Message source = ConversationService.appendMessage(conversation, MessageRole.USER,
                "[Priya (id 3)]: I'm vegetarian", null, null, null);
        var attributed = store(agent, "The user is vegetarian.");
        var anonymous = store(agent, "The user's dog is old.");
        Memory a = Memory.findById(attributed);
        a.authorType = MemoryAuthorType.GUEST_TURN;
        a.sourceMessageId = source.id;
        a.save();
        Memory b = Memory.findById(anonymous);
        b.authorType = MemoryAuthorType.GUEST_TURN;
        b.save();

        assertEquals(2, MemoryOwnerNameRewrite.run(agent));
        assertEquals("Priya is vegetarian.", textOf(attributed));
        assertEquals("A guest's dog is old.", textOf(anonymous));
    }

    private static Agent agent(String name) {
        WorkspaceFiles.resetWorkspace(name);
        var a = new Agent();
        a.name = name;
        a.modelProvider = "openrouter";
        a.modelId = "gpt-4.1";
        a.save();
        return a;
    }

    private static Long store(Agent agent, String text) {
        return Long.valueOf(MemoryStoreFactory.get().store(String.valueOf(agent.id), text, "fact", 0.5));
    }

    private static String textOf(Long id) {
        Memory m = Memory.findById(id);
        m.refresh();
        return m.text;
    }
}
