package jobs;

import models.Prompt;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.EventLogger;

/**
 * Seeds a handful of starter prompts into the Prompts Library (JCLAW-813) so the
 * page isn't empty on first visit. Idempotent "seed if empty": runs at
 * {@code @OnApplicationStart} and no-ops once the {@code prompt} table has any
 * row (including after the operator has added or deleted their own), so it never
 * fights the operator's curation — deleting all sample prompts keeps them gone.
 *
 * <p>Categories are the fixed {@link Prompt.Category} enum, so nothing needs
 * seeding for them; only the sample prompts are inserted here.
 */
@OnApplicationStart
public class PromptLibrarySeedJob extends Job<Void> {

    @Override
    public void doJob() {
        try {
            if (Prompt.count() > 0) return;
            seedSamples();
        } catch (Exception e) {
            EventLogger.warn("prompts", "Failed to seed sample prompts: " + e.getMessage());
        }
    }

    private void seedSamples() {
        seed("Code Review Checklist", Prompt.Category.CODING, "review, quality, checklist",
                """
                Review the following code and report issues grouped by severity (blocker, \
                major, minor). For each: the file/line, what's wrong, why it matters, and a \
                concrete fix. Cover correctness, edge cases, security, performance, and \
                readability. End with the single highest-leverage change.

                <paste code here>""");
        seed("Explain This Code", Prompt.Category.CODING, "explain, learning",
                """
                Explain what the following code does, step by step, for a competent engineer \
                new to this codebase. Call out any non-obvious control flow, side effects, or \
                assumptions. Then give a one-sentence summary of its purpose.

                <paste code here>""");
        seed("Write Unit Tests", Prompt.Category.CODING, "testing, tdd",
                """
                Write thorough unit tests for the code below. Cover the happy path, boundary \
                values, and error/edge cases. Use the project's existing test framework and \
                conventions. Name each test after the behavior it pins.

                <paste code here>""");
        seed("Blog Post Outline", Prompt.Category.WRITING, "content, outline",
                "Create a detailed outline for a blog post titled \"<title>\" aimed at "
                        + "<audience>. Include a hook, 4-6 section headings each with 2-3 bullet points, "
                        + "and a closing call to action. Keep the tone <tone>.");
        seed("Rewrite More Professionally", Prompt.Category.WRITING, "email, tone, editing",
                """
                Rewrite the following text to be clear, concise, and professional while \
                preserving my intent and key details. Keep it warm, not stiff. Return only the \
                rewritten version.

                <paste text here>""");
        seed("SWOT Analysis", Prompt.Category.ANALYSIS, "strategy, framework",
                """
                Produce a SWOT analysis (Strengths, Weaknesses, Opportunities, Threats) for \
                the subject below. Give 3-5 specific, evidence-based points per quadrant, then a \
                short paragraph on the most important strategic implication.

                <subject>""");
        seed("Summarize & Extract Action Items", Prompt.Category.ANALYSIS, "summary, meetings",
                """
                Summarize the text below in 5 bullet points, then list every action item as \
                \"[owner] - [task] - [due]\" (use TBD when unknown). Flag any open questions \
                separately.

                <paste notes/transcript here>""");
        seed("Brainstorm Ideas", Prompt.Category.CREATIVE, "ideation, divergent",
                "Brainstorm 15 distinct, non-obvious ideas for <goal>. Range from safe to bold. "
                        + "For the 3 strongest, add a one-line reason they could work. Avoid repeating the "
                        + "same idea in different words.");
        seed("Meeting Agenda", Prompt.Category.BUSINESS, "meetings, planning",
                "Draft a focused agenda for a <length>-minute meeting about <topic> with "
                        + "<attendees>. Include the objective, timed agenda items, the decision(s) to be "
                        + "made, and pre-reads. Keep it tight enough to actually finish on time.");
        seed("Product One-Pager", Prompt.Category.BUSINESS, "product, spec",
                "Write a one-page brief for <product/feature>: the problem, the target user, "
                        + "the proposed solution, why now, success metrics, and the top 3 risks. Be "
                        + "specific and avoid buzzwords.");
        seed("Daily Standup Notes", Prompt.Category.CUSTOM, "standup, routine",
                """
                Turn my rough notes into a clean standup update with three sections - \
                Yesterday, Today, Blockers - as short bullet points.

                <paste rough notes here>""");
    }

    private void seed(String title, Prompt.Category category, String tags, String content) {
        var p = new Prompt();
        p.title = title;
        p.category = category;
        p.tags = tags;
        p.content = content;
        p.save();
    }
}
