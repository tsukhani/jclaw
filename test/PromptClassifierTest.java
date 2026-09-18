import llm.routing.PromptClassifier;
import llm.routing.TaskClass;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

/** JCLAW-1222: which task class a prompt lands in, and that the decision always explains itself. */
class PromptClassifierTest extends UnitTest {

    private static TaskClass classOf(String message) {
        return PromptClassifier.classify(message, null, 0).taskClass();
    }

    @Test
    void everydayConversationIsChat() {
        assertEquals(TaskClass.CHAT, classOf("hi there"));
        assertEquals(TaskClass.CHAT, classOf("What is the capital of Portugal?"));
        assertEquals(TaskClass.CHAT, classOf("Recommend a good fantasy novel for a long flight"));
    }

    @Test
    void aCodeBlockOrStackTraceIsCodingWhateverElseTheMessageSays() {
        assertEquals(TaskClass.CODING, classOf("Why does this fail step by step?\n```java\nint x = null;\n```"));
        assertEquals(TaskClass.CODING, classOf("""
                Exception in thread "main" java.lang.NullPointerException: boom
                    at app.Main.run(Main.java:12)
                """));
        assertEquals(TaskClass.CODING, classOf("Traceback (most recent call last):\n  File \"x.py\", line 3"));
    }

    @Test
    void twoCodeTermsAreCodingButOneIsNot() {
        assertEquals(TaskClass.CODING, classOf("fix the bug in AgentRunner.java"));
        assertEquals(TaskClass.CODING, classOf("refactor this python function to be shorter"));
        assertEquals(TaskClass.CHAT, classOf("what is node.js?"), "one incidental term is not a coding task");
    }

    @Test
    void anAskToWorkSomethingOutIsReasoningEvenWithoutTheExactMarkerPhrase() {
        // Real prompts that the first cut left on the chat model.
        assertEquals(TaskClass.REASONING, classOf("Reason about why LLMs cannot reach ASI"));
        assertEquals(TaskClass.REASONING,
                classOf("Couldn't we create our own DSL for the policy? Why would we need to use JSON?"));
        assertEquals(TaskClass.REASONING, classOf("What is your opinion on these arguments?"));
        assertEquals(TaskClass.REASONING,
                classOf("How could this research paper be applied to JClaw? Does the approach only apply to the models?"));
    }

    @Test
    void oneWeakCueIsNotEnoughToLeaveTheCheapModel() {
        assertEquals(TaskClass.CHAT, classOf("Why is the sky blue?"));
        assertEquals(TaskClass.CHAT, classOf("What time is my next meeting?"));
        assertEquals(TaskClass.CHAT, classOf("How does the auto router choose a model?"));
        assertEquals(TaskClass.CHAT, classOf("Tell me a joke about cats"));
    }

    @Test
    void explicitReasoningAndMathAreReasoning() {
        assertEquals(TaskClass.REASONING, classOf("Prove that the square root of 2 is irrational"));
        assertEquals(TaskClass.REASONING, classOf("Walk me through the trade-offs of Postgres versus H2 here"));
        assertEquals(TaskClass.REASONING, classOf("Compute \\int_0^1 x^2 dx"));
        assertEquals(TaskClass.REASONING, classOf("What is the probability of rolling 7 with 2 dice?"));
        assertEquals(TaskClass.CHAT, classOf("What does probability mean in plain words?"),
                "a math word without a number or formula is a question about the word");
    }

    @Test
    void reasoningOutranksSoftCodeTermsButNotACodeBlock() {
        assertEquals(TaskClass.REASONING, classOf("Explain the root cause of this API bug"));
    }

    @Test
    void anInstructionToActIsAgentWorkEvenWithOneVerb() {
        // The first cut needed two verbs here, which left "Run daily briefing skill" on the chat model.
        assertEquals(TaskClass.AGENTIC, classOf("Run daily briefing skill"));
        assertEquals(TaskClass.AGENTIC, classOf("Can you check if radarr has the new documentary?"));
        assertEquals(TaskClass.AGENTIC, classOf("Spawn one subagent and instruct it to call the date_time tool"));
        assertEquals(TaskClass.AGENTIC,
                classOf("Research the three cheapest flights to Tokyo, then email me the list"));
        assertEquals(TaskClass.AGENTIC, classOf("""
                Please do the following:
                1. download the report
                2. update the spreadsheet
                """));
    }

    @Test
    void createStyleVerbsAreAgentWorkOnlyWhenTheObjectIsSomethingJClawManages() {
        assertEquals(TaskClass.AGENTIC, classOf("Create a prompt in the prompt library for image styles"));
        assertEquals(TaskClass.AGENTIC, classOf("generate a cover image for this article"));
        assertEquals(TaskClass.CHAT, classOf("Create the introductory blurb for this article"),
                "writing prose is not tool work, whatever the leading verb is");
        assertEquals(TaskClass.CHAT, classOf("Write a short poem about the sea"));
    }

    @Test
    void averbMerelyMentionedIsNotAnInstruction() {
        assertEquals(TaskClass.CHAT,
                classOf("Also, you may want to look at the neo4j plugin I wrote, and find out what it does"),
                "a verb mid-sentence is conversation, not a command");
    }

    @Test
    void aToolHeavyPreviousTurnKeepsAContinuationOnAgentWork() {
        var c = PromptClassifier.classify("now check the second server", null, 5);
        assertEquals(TaskClass.AGENTIC, c.taskClass());
        assertTrue(c.signals().contains("previous turn used 5 tool calls"), "signals: " + c.signals());
    }

    @Test
    void summaryRequestsAreSummarize() {
        assertEquals(TaskClass.SUMMARIZE, classOf("tl;dr of the thread above please"));
        assertEquals(TaskClass.SUMMARIZE, classOf("Give me the key takeaways from this article"));
    }

    @Test
    void aBareAcknowledgementInheritsThePreviousClass() {
        var c = PromptClassifier.classify("yes, go ahead!", TaskClass.REASONING, 0);
        assertEquals(TaskClass.REASONING, c.taskClass());
        assertEquals("follow-up inherits reasoning", c.signals().getFirst());
        assertEquals(TaskClass.AGENTIC, PromptClassifier.classify("continue", null, 4).taskClass());
    }

    @Test
    void aShortMessageWithANewAskIsNotAFollowUp() {
        assertEquals(TaskClass.CHAT, PromptClassifier.classify("ok what time is it", TaskClass.REASONING, 0).taskClass());
    }

    @Test
    void theWordBoundaryKeepsSubstringsFromMatching() {
        assertEquals(TaskClass.CHAT, classOf("How do I improve my waterproof jacket?"),
                "'prove' inside 'improve' and 'proof' inside 'waterproof' are not reasoning markers");
    }

    @Test
    void everyDecisionCarriesAtLeastOneSignal() {
        for (var message : new String[] {"hi", "", "summarize", "prove it", "```x```", "run and deploy"}) {
            var c = PromptClassifier.classify(message, null, 0);
            assertFalse(c.signals().isEmpty(), () -> "no signal for '" + message + "' → " + c.taskClass());
        }
    }

    @Test
    void aHugePasteIsClassifiedOnItsHead() {
        var body = "Summarize this:\n" + "lorem ipsum dolor sit amet ".repeat(20_000);
        assertEquals(TaskClass.SUMMARIZE, classOf(body));
    }
}
