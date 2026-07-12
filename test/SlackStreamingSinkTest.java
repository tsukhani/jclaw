import channels.SlackStreamingSink;
import channels.SlackStreamingSink.Slacker;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.AgentService;
import services.AttachmentService;
import services.ConversationService;
import services.Tx;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

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
            return startResult;
        }

        @Override public boolean appendStream(String channelId, String ts, String markdownDelta) {
            appended.add(markdownDelta);
            return true;
        }

        @Override public boolean stopStream(String channelId, String ts) {
            stopped = true;
            return true;
        }

        @Override public void setStatus(String channelId, String threadTs, String status) {
            statuses.add(status);
        }

        @Override public void postFallback(String channelId, String text, String threadTs) {
            fallbackPosts.add(text);
        }

        @Override public String postMessage(String channelId, String text, String threadTs) {
            posted.add(text);
            return postResult;
        }

        @Override public boolean editMessage(String channelId, String ts, String text) {
            edited.add(text);
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
        assertTrue(f.fallbackPosts.get(0).contains("something went wrong"));
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
        assertTrue(f.edited.get(0).contains("something went wrong"));
        assertTrue(f.fallbackPosts.isEmpty());
    }
}
