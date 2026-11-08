import channels.SlackStreamingSink;
import channels.SlackStreamingSink.Slacker;
import llm.LlmProvider;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.AgentService;
import services.AttachmentService;
import services.ConversationService;
import services.Tx;
import utils.LlmErrorTemplates;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * JCLAW-341/346: the sink's two live-reply modes, exercised with an injected
 * {@link Slacker} (no Slack API) and throttle 0 so every update flushes
 * deterministically. Native (assistant thread + recipient) does
 * start → append → stop; off-thread does the chat.update draft preview
 * (post → edit → final formatted edit), with a single-post fallback when no
 * token ever arrives.
 */
class SlackStreamingSinkTest extends UnitTest {

    static final class FakeSlacker implements Slacker {
        final List<String> appended = new ArrayList<>();
        final List<String> fallbackPosts = new ArrayList<>();
        final List<String> fallbackThreads = new ArrayList<>();
        int stopStreams = 0;
        /** Every stream, post and edit call, in order; written from the reader and the turn thread alike. */
        final List<String> calls = new CopyOnWriteArrayList<>();
        /** When set, startStream opens {@code startEntered} and returns once {@code startRelease} opens. */
        volatile CountDownLatch startEntered;
        volatile CountDownLatch startRelease;
        final List<String> statuses = new ArrayList<>();
        final List<String> posted = new ArrayList<>();   // JCLAW-346 draft placeholder posts
        final List<String> edited = new ArrayList<>();    // JCLAW-346 draft edits
        final List<String> uploaded = new ArrayList<>();  // generated-media uploads (display names)
        boolean stopped = false;
        String startResult = "1700000000.0001";
        String postResult = "1700000099.0001";
        boolean editResult = true;
        String startedThread;
        String startedUser;
        String startedInitial;

        @Override public String startStream(String channelId, String threadTs, String recipientUserId, String initialMarkdown) {
            startedThread = threadTs;
            startedUser = recipientUserId;
            startedInitial = initialMarkdown;
            calls.add("startStream " + initialMarkdown);
            var release = startRelease;
            if (release != null) {
                startEntered.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return startResult;
        }

        @Override public boolean appendStream(String channelId, String ts, String markdownDelta) {
            appended.add(markdownDelta);
            calls.add("appendStream " + markdownDelta);
            return true;
        }

        @Override public boolean stopStream(String channelId, String ts) {
            stopped = true;
            stopStreams++;
            calls.add("stopStream " + ts);
            return true;
        }

        @Override public void setStatus(String channelId, String threadTs, String status) {
            statuses.add(status);
        }

        @Override public void postFallback(String channelId, String text, String threadTs) {
            fallbackPosts.add(text);
            fallbackThreads.add(threadTs);
            calls.add("postFallback " + text + " in " + threadTs);
        }

        @Override public String postMessage(String channelId, String text, String threadTs) {
            posted.add(text);
            calls.add("postMessage " + text);
            return postResult;
        }

        @Override public boolean editMessage(String channelId, String ts, String text) {
            edited.add(text);
            calls.add("editMessage " + text);
            return editResult;
        }

        @Override public boolean uploadFile(String peerId, String threadTs, File file, String displayName, String caption) {
            uploaded.add(displayName);
            return true;
        }
    }

    @Test
    void nativeStreamsWhenThreadAndUserPresent() {
        var f = new FakeSlacker();
        var sink = new SlackStreamingSink("C1", "1700.0", "U1", f, 0L);
        sink.begin();
        sink.update("Hel");
        sink.update("lo");
        sink.seal("Hello");
        assertEquals("U1", f.startedUser);
        assertEquals("1700.0", f.startedThread);
        // Lazy start: the first token seeds the stream (never an empty message);
        // subsequent deltas append (throttle 0 → each flushes).
        assertEquals("Hel", f.startedInitial);
        assertEquals(List.of("lo"), f.appended);
        assertTrue(f.stopped, "stream must be finalized");
        assertTrue(f.fallbackPosts.isEmpty(), "native path must not post a fallback");
        assertTrue(f.posted.isEmpty(), "native path must not use the draft preview");
        // "is typing…" set at begin, cleared at seal.
        assertEquals(List.of("is typing...", ""), f.statuses);
    }

    @Test
    void noStatusWhenOffThread() {
        var f = new FakeSlacker();
        var sink = new SlackStreamingSink("C1", null, "U1", f, 0L);
        sink.begin();
        sink.seal("hi");
        assertTrue(f.statuses.isEmpty(), "no thread → no assistant status");
    }

    @Test
    void fallbackWhenStartStreamFails() {
        var f = new FakeSlacker();
        f.startResult = null; // startStream failed (e.g. app not an AI Assistant)
        var sink = new SlackStreamingSink("C1", "1700.0", "U1", f, 0L);
        sink.begin();
        sink.update("x");
        sink.seal("done");
        assertTrue(f.appended.isEmpty());
        // canStream was true, so the draft preview never engaged; seal posts once.
        assertEquals(List.of("done"), f.fallbackPosts);
        assertTrue(f.posted.isEmpty());
    }

    @Test
    void errorPostsNoticeWhenNotStreaming() {
        var f = new FakeSlacker();
        var sink = new SlackStreamingSink("C1", null, "U1", f, 0L);
        sink.begin();
        sink.errorFallback(new RuntimeException("boom"));
        assertEquals(1, f.fallbackPosts.size());
        // JCLAW-1133: the notice is the 3-part template, converted to Slack mrkdwn — CommonMark
        // ** would arrive as literal asterisks, which is what SlackMarkdownFormatter prevents.
        var posted = f.fallbackPosts.get(0);
        assertTrue(posted.contains("What broke"), posted);
        assertTrue(posted.contains("How to retry"), posted);
        assertFalse(posted.contains("**"), "mrkdwn, not raw CommonMark: " + posted);
    }

    /** Everyone in the channel reads the notice, so the classified remedy stays in the log. */
    @Test
    void errorNoticeNeverShowsTheChannelTheProviderOrItsKey() {
        var f = new FakeSlacker();
        var sink = new SlackStreamingSink("C1", null, "U1", f, 0L);
        sink.begin();
        var failure = new LlmErrorTemplates.Failure(
                LlmErrorTemplates.Remedy.INVALID_KEY, "openrouter", "gpt-4.1", null, null);
        sink.errorFallback(new LlmProvider.LlmException.ClientError("HTTP 401 from openrouter", failure));
        var posted = f.fallbackPosts.get(0);
        assertFalse(posted.contains("openrouter"), posted);
        assertFalse(posted.contains("gpt-4.1"), posted);
        assertFalse(posted.contains("API key"), posted);
    }

    // ── JCLAW-346: off-thread chat.update draft preview ──

    @Test
    void draftPreviewPostsThenFinalEditsOffThread() {
        var f = new FakeSlacker();
        var sink = new SlackStreamingSink("C1", null, "U1", f, 0L); // off-thread → draft preview
        sink.begin();
        sink.update("Hi");          // first token posts the placeholder
        sink.update(" there");      // throttle 0 → edit with the accumulated raw text
        sink.seal("**Hi there**");  // final edit → mrkdwn-formatted
        assertEquals(List.of("Hi"), f.posted, "first token posts the draft placeholder");
        assertEquals(2, f.edited.size(), "a live edit + the final formatted edit");
        assertEquals("Hi there", f.edited.get(0), "live edit shows the raw accumulated text");
        assertFalse(f.edited.get(1).contains("**"), "final edit is mrkdwn-formatted, not raw CommonMark");
        assertTrue(f.edited.get(1).contains("Hi there"));
        assertTrue(f.fallbackPosts.isEmpty(), "draft path must not post a fallback");
        assertTrue(f.appended.isEmpty(), "draft path must not use the native append");
    }

    @Test
    void draftFallsBackToSinglePostWhenNoTokens() {
        var f = new FakeSlacker();
        var sink = new SlackStreamingSink("C1", null, "U1", f, 0L);
        sink.begin();
        sink.seal("only at the end"); // no tokens → no draft posted → single fallback post
        assertTrue(f.posted.isEmpty());
        assertTrue(f.edited.isEmpty());
        assertEquals(List.of("only at the end"), f.fallbackPosts);
    }

    @Test
    void draftToolProgressShownThenReplacedByReply() {
        var f = new FakeSlacker();
        var sink = new SlackStreamingSink("C1", null, "U1", f, 0L);
        sink.begin();
        sink.toolProgress("search_web"); // posts the "Working…" placeholder
        sink.toolProgress("read_file");  // edits to add the second line
        sink.update("the answer");       // real token → edits to the reply, replacing progress
        assertEquals(List.of("Working…\n• search_web"), f.posted);
        assertEquals(List.of("Working…\n• search_web\n• read_file", "the answer"), f.edited);
    }

    @Test
    void theCompactionNoticeShowsInTheDraftUntilTheReplyReplacesIt() {
        var f = new FakeSlacker();
        var sink = new SlackStreamingSink("C1", null, "U1", f, 0L);
        sink.begin();
        sink.compacting("Compacting this conversation (~182k tokens)…");
        sink.update("the answer");
        assertEquals(List.of("Working…\n• Compacting this conversation (~182k tokens)…"), f.posted);
        assertEquals(List.of("the answer"), f.edited);
    }

    @Test
    void inAnAssistantThreadTheCompactionNoticeReplacesTheTypingStatus() {
        var f = new FakeSlacker();
        var sink = new SlackStreamingSink("C1", "1700.0", "U1", f, 0L);
        sink.begin();
        sink.compacting("Compacting this conversation (~182k tokens)…");
        assertEquals(List.of("is typing...", "is compacting this conversation (~182k tokens)..."), f.statuses);
        assertTrue(f.posted.isEmpty(), "the status line carries it, not a draft");
    }

    // ── Out-of-band generated-media delivery (generate_image et al.) ──
    //
    // A tool-generated attachment is persisted out-of-band and the generating tool
    // tells the model NOT to link it in prose, so SlackOutboundPlanner.dispatchFiles
    // (text-links only) never sends it — the image was silently dropped on Slack while
    // the web UI rendered it from the DB/SSE. The runner now feeds the sink the uuids;
    // seal must resolve each persisted file and upload it via the Slacker seam.

    @Test
    void generatedAttachmentUploadedToSlackAtSeal() throws Exception {
        var agent = AgentService.create("slack-genmedia-agent", "openrouter", "gpt-4.1");
        var uuid = Tx.run(() -> {
            var conv = ConversationService.findOrCreate(agent, "slack", "C-genmedia");
            var msg = ConversationService.appendAssistantMessage(conv, null, "[]");
            return AttachmentService.persistGeneratedAttachment(
                    agent, msg, new byte[]{(byte) 0x89, 'P', 'N', 'G'}, "image/png", "{}", "chart.png").uuid;
        });

        var f = new FakeSlacker();
        var sink = new SlackStreamingSink("C-genmedia", null, "U1", f, 0L);
        sink.collectGeneratedAttachments(List.of(uuid));
        sink.seal("Here's the chart.");

        // The upload runs on a spawned virtual thread — poll for it.
        long deadline = System.currentTimeMillis() + 3000;
        while (f.uploaded.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(List.of("chart.png"), f.uploaded,
                "the out-of-band generated image must be uploaded to Slack at seal");
    }

    @Test
    void noUploadWhenNoGeneratedMediaCollected() throws Exception {
        var f = new FakeSlacker();
        var sink = new SlackStreamingSink("C1", null, "U1", f, 0L);
        sink.seal("just text");
        // Nothing was collected → deliverGeneratedAttachments is a no-op; give any stray
        // background thread a brief window to (not) fire.
        Thread.sleep(50);
        assertTrue(f.uploaded.isEmpty(), "a turn with no generated attachment must upload nothing");
    }

    @Test
    void nativeIgnoresToolProgress() {
        var f = new FakeSlacker();
        var sink = new SlackStreamingSink("C1", "1700.0", "U1", f, 0L); // thread+user → native
        sink.begin();
        sink.toolProgress("search_web");
        assertTrue(f.posted.isEmpty(), "native mode shows no draft tool progress");
        assertTrue(f.edited.isEmpty());
    }

    @Test
    void draftStopsLiveEditsPastLengthCap() {
        var f = new FakeSlacker();
        var sink = new SlackStreamingSink("C1", null, "U1", f, 0L);
        sink.begin();
        sink.update("x".repeat(4000)); // > DRAFT_PREVIEW_MAX → no live post, draft stopped
        sink.seal("x".repeat(4000));
        assertTrue(f.posted.isEmpty(), "oversize live preview must not post");
        assertTrue(f.edited.isEmpty());
        assertEquals(1, f.fallbackPosts.size(), "seal posts the full reply once");
    }

    @Test
    void draftErrorEditsInPlaceAfterDraftPosted() {
        var f = new FakeSlacker();
        var sink = new SlackStreamingSink("C1", null, "U1", f, 0L);
        sink.begin();
        sink.update("partial");      // posts the draft placeholder
        sink.errorFallback(new RuntimeException("boom"));
        assertEquals(List.of("partial"), f.posted);
        assertEquals(1, f.edited.size(), "error edits the draft in place");
        var edited = f.edited.get(0);
        assertTrue(edited.contains("What broke"), edited);
        assertTrue(edited.contains("How to retry"), edited);
        assertFalse(edited.contains("**"), "mrkdwn, not raw CommonMark: " + edited);
        assertTrue(f.fallbackPosts.isEmpty());
    }

    // --- JF-197b: a stopped turn ---

    private static final String NOTICE = "This reply was stopped.";

    @Test
    void aStopClosesTheStreamWithWhatItStreamedAndPostsTheNoticeInTheThreadOnce() {
        var f = new FakeSlacker();
        var sink = new SlackStreamingSink("C1", "1700.0", "U1", f, 0L);
        sink.begin();
        sink.update("Hel");
        sink.update("lo");

        sink.stop(NOTICE);
        sink.stop(NOTICE);
        sink.update(" world");
        sink.seal("Hello world");
        sink.errorFallback(new RuntimeException("late"));

        assertEquals("Hel", f.startedInitial);
        assertEquals(List.of("lo"), f.appended, "nothing is appended after the stop");
        assertEquals(1, f.stopStreams, "the stream is closed once, by the stop");
        assertEquals(List.of(NOTICE), f.fallbackPosts);
        assertEquals(List.of("1700.0"), f.fallbackThreads, "the notice goes in the reply's thread");
        assertEquals(List.of("is typing...", ""), f.statuses);
        assertEquals(List.of(), f.edited);
    }

    @Test
    void aStopOffThreadLeavesTheDraftAsItStoodAndPostsTheNotice() {
        var f = new FakeSlacker();
        var sink = new SlackStreamingSink("C1", null, null, f, 0L);
        sink.begin();
        sink.update("Hel");
        sink.update("lo");

        sink.stop(NOTICE);
        sink.seal("Hello");

        assertEquals(List.of("Hel"), f.posted);
        assertEquals(List.of("Hello"), f.edited, "the draft keeps its last live edit, with no final one");
        assertEquals(List.of(NOTICE), f.fallbackPosts);
        assertEquals(0, f.stopStreams);
    }

    @Test
    void aStopTheTurnsOwnStopAnsweredClosesTheStreamAndPostsNothing() {
        var f = new FakeSlacker();
        var sink = new SlackStreamingSink("C1", "1700.0", "U1", f, 0L);
        sink.begin();
        sink.update("Hel");

        sink.stop(null);
        assertEquals(1, f.stopStreams, "the stop closed the stream");
        sink.seal("Hello");

        assertEquals(1, f.stopStreams);
        assertEquals(List.of(), f.fallbackPosts);
        assertEquals(List.of(), f.appended);
    }

    @Test
    void aStopAfterTheReplyWasSealedPostsNothing() {
        var f = new FakeSlacker();
        var sink = new SlackStreamingSink("C1", "1700.0", "U1", f, 0L);
        sink.begin();
        sink.update("Hello");
        sink.seal("Hello");

        sink.stop(NOTICE);

        assertEquals(1, f.stopStreams, "the seal closed the stream");
        assertEquals(List.of(), f.fallbackPosts);
    }

    @Test
    void aStopThatArrivesWhileTheStreamIsStartingClosesThatStreamAndNothingIsAppendedAfter() throws Exception {
        var f = new FakeSlacker();
        f.startEntered = new CountDownLatch(1);
        f.startRelease = new CountDownLatch(1);
        var sink = new SlackStreamingSink("C1", "1700.0", "U1", f, 0L);
        sink.begin();
        // The first token batch starts the stream on the model stream's reader thread.
        var reader = Thread.ofPlatform().start(() -> sink.update("Hel"));
        assertTrue(f.startEntered.await(10, TimeUnit.SECONDS), "test precondition: the stream is starting");
        var stopper = Thread.ofPlatform().start(() -> sink.stop(NOTICE));
        for (int i = 0; i < 200 && stopper.isAlive() && stopper.getState() != Thread.State.WAITING; i++) {
            Thread.sleep(5);
        }
        assertEquals(Thread.State.WAITING, stopper.getState(),
                "test precondition: the stop waits on the stream that is starting");
        f.startRelease.countDown();
        reader.join(10_000);
        stopper.join(10_000);

        sink.update("lo");
        sink.seal("Hello");

        assertEquals(List.of("startStream Hel", "stopStream 1700000000.0001",
                "postFallback " + NOTICE + " in 1700.0"), f.calls);
    }

    @Test
    void aStopOfAnOffThreadTurnShowingOnlyToolProgressEditsThatDraftToTheNotice() {
        var f = new FakeSlacker();
        var sink = new SlackStreamingSink("C1", null, null, f, 0L);
        sink.begin();
        sink.toolProgress("web_search");

        sink.stop(NOTICE);
        sink.seal("late");

        assertEquals(List.of("postMessage Working…\n• web_search", "editMessage " + NOTICE), f.calls);
    }

    @Test
    void aStopTheTurnsOwnStopAnsweredEditsAToolProgressDraftToStopped() {
        var f = new FakeSlacker();
        var sink = new SlackStreamingSink("C1", null, null, f, 0L);
        sink.begin();
        sink.toolProgress("web_search");

        sink.stop(null);
        sink.seal("late");

        assertEquals(List.of("postMessage Working…\n• web_search", "editMessage Stopped."), f.calls);
    }

    @Test
    void aStopWhoseEditOfAToolProgressDraftFailsPostsTheNoticeInTheThread() {
        var f = new FakeSlacker();
        f.editResult = false; // Slack rate-limits chat.update
        var sink = new SlackStreamingSink("C1", null, null, f, 0L);
        sink.begin();
        sink.toolProgress("web_search");

        sink.stop(NOTICE);

        assertEquals(List.of("postMessage Working…\n• web_search", "editMessage " + NOTICE,
                "postFallback " + NOTICE + " in null"), f.calls);
    }

    @Test
    void aStopWithTokensNotYetShownOnAToolProgressDraftEditsThatDraftToTheNotice() {
        var f = new FakeSlacker();
        // The draft's throttle keeps the reply's first tokens back.
        var sink = new SlackStreamingSink("C1", null, null, f, 60_000L);
        sink.begin();
        sink.toolProgress("web_search");
        sink.update("Hel");

        sink.stop(NOTICE);

        assertEquals(List.of("postMessage Working…\n• web_search", "editMessage " + NOTICE), f.calls);
    }

    @Test
    void aStopAfterAToolProgressEditFailedEditsTheDraftAsItStillReads() {
        var f = new FakeSlacker();
        var sink = new SlackStreamingSink("C1", null, null, f, 0L);
        sink.begin();
        sink.toolProgress("web_search");
        f.editResult = false; // the next edit is rate-limited, so the draft keeps reading "• web_search"
        sink.toolProgress("read_file");
        f.editResult = true;

        sink.stop(NOTICE);

        assertEquals(List.of("postMessage Working…\n• web_search", "editMessage Working…\n• web_search\n• read_file",
                "editMessage " + NOTICE), f.calls);
    }
}
