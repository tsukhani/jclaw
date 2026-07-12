import controllers.PagedJpqlQuery;
import models.Agent;
import models.Conversation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.ConversationService;
import utils.JpqlFilter;

/**
 * JCLAW-722: contract tests for the shared paginated-query helper. Uses
 * {@link Conversation} (no Lucene persist hook) so seeding is deterministic.
 * Verifies the total counts the full match set while the page respects
 * limit/offset, that a WHERE + positional param narrows both the SELECT and
 * the COUNT off one binding source, that a SELECT-only {@code JOIN FETCH}
 * never leaks into the COUNT, and that the page cap is the one named constant.
 */
class PagedJpqlQueryTest extends UnitTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
    }

    private Agent agent(String name) {
        return AgentService.create(name, "openrouter", "gpt-4.1");
    }

    @Test
    void totalCountsAllWhilePageIsBounded() {
        var a = agent("paged-a");
        for (int i = 0; i < 5; i++) {
            ConversationService.create(a, "web", "peer-" + i);
        }

        var page = PagedJpqlQuery.of(Conversation.class, "Conversation c", "c")
                .orderBy("ORDER BY c.id ASC")
                .page(0, 2)
                .execute();

        assertEquals(5L, page.total(), "total reflects the full match set, not the page size");
        assertEquals(2, page.rows().size(), "the page is capped to the requested limit");
    }

    @Test
    void offsetAdvancesThePage() {
        var a = agent("paged-offset");
        for (int i = 0; i < 4; i++) {
            ConversationService.create(a, "web", "peer-" + i);
        }

        var first = PagedJpqlQuery.of(Conversation.class, "Conversation c", "c")
                .orderBy("ORDER BY c.id ASC").page(0, 2).execute();
        var second = PagedJpqlQuery.of(Conversation.class, "Conversation c", "c")
                .orderBy("ORDER BY c.id ASC").page(2, 2).execute();

        assertEquals(4L, first.total());
        assertEquals(4L, second.total());
        assertNotEquals(first.rows().get(0).id, second.rows().get(0).id,
                "offset must return a different window");
    }

    @Test
    void whereAndPositionalParamNarrowBothQueries() {
        var a = agent("paged-where");
        ConversationService.create(a, "web", "w1");
        ConversationService.create(a, "web", "w2");
        ConversationService.create(a, "telegram", "t1");

        var filter = new JpqlFilter().eq("channelType", "web");
        var page = PagedJpqlQuery.of(Conversation.class, "Conversation c", "c")
                .where(filter.toWhereClause())
                .positionalParams(filter.paramList())
                .orderBy("ORDER BY c.id ASC")
                .page(0, 50)
                .execute();

        assertEquals(2L, page.total(), "COUNT honours the same WHERE + param as the SELECT");
        assertEquals(2, page.rows().size(), "SELECT returns exactly the matching rows");
    }

    @Test
    void joinFetchDoesNotLeakIntoCount() {
        var a = agent("paged-fetch");
        ConversationService.create(a, "web", "f1");
        ConversationService.create(a, "web", "f2");

        // A JOIN FETCH on the SELECT must not break the COUNT (which omits it).
        var page = PagedJpqlQuery.of(Conversation.class, "Conversation c", "c")
                .joinFetch("JOIN FETCH c.agent")
                .orderBy("ORDER BY c.id ASC")
                .page(0, 50)
                .execute();

        assertEquals(2L, page.total());
        assertEquals(2, page.rows().size());
        assertNotNull(page.rows().get(0).agent, "JOIN FETCH eagerly loads the agent");
    }

    @Test
    void maxLimitIsTheOneNamedCap() {
        assertEquals(500, PagedJpqlQuery.MAX_LIMIT,
                "the single normalized page cap replaces the old 100/500 split");
    }
}
