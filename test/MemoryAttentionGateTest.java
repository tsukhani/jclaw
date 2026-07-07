import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import memory.MemoryAttentionGate;

class MemoryAttentionGateTest extends UnitTest {

    @Test
    void skipsEmpty() {
        assertFalse(MemoryAttentionGate.evaluate(null).proceed());
        assertFalse(MemoryAttentionGate.evaluate("   ").proceed());
        assertEquals("empty_user_message", MemoryAttentionGate.evaluate("").reason());
    }

    @Test
    void skipsShortGreetingsAndAcks() {
        assertFalse(MemoryAttentionGate.evaluate("hi").proceed());
        assertFalse(MemoryAttentionGate.evaluate("thanks!").proceed());
        assertFalse(MemoryAttentionGate.evaluate("ok").proceed());
        assertEquals("trivial", MemoryAttentionGate.evaluate("hey").reason());
    }

    @Test
    void proceedsOnSubstantiveTurn() {
        assertTrue(MemoryAttentionGate.evaluate("I prefer dark mode in all my editors").proceed());
        assertTrue(MemoryAttentionGate.evaluate("My name is Tarun and I work at Abundent").proceed());
    }

    @Test
    void proceedsWhenLongEvenIfItStartsWithAGreeting() {
        assertTrue(MemoryAttentionGate.evaluate(
                "hello, please remember that my timezone is Asia/Kuala_Lumpur").proceed());
    }
}
