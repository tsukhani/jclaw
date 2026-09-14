import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The video sidecar's {@code POST /pull} contract, asked of its own interpreter over a real
 * {@code ThreadingHTTPServer}. Only WAN specs carry the {@code repo} that {@code _pull_stream}
 * downloads, so every other engine must be refused before the ndjson headers go out.
 */
class VideoSidecarPullContractTest extends UnitTest {

    private static final String MARKER = "PROBE:";

    /** Serves {@code serve.py}'s Handler for one engine of the chosen registry and reports
     *  status, content type and body of one {@code POST /pull}. {@code _pull_stream} is
     *  stubbed, so a WAN pull downloads nothing. */
    private static final String SCRIPT = """
            import sys, json, tempfile, threading, urllib.request, urllib.error
            sys.path.insert(0, sys.argv[1])
            import serve
            from http.server import ThreadingHTTPServer

            def fake_pull(state, wfile):
                wfile.write((json.dumps({"bytesDownloaded": 1, "totalBytes": 0}) + "\\n").encode())
                wfile.write((json.dumps({"done": True, "error": None}) + "\\n").encode())
                wfile.flush()

            serve._pull_stream = fake_pull
            serve.Handler.token = None

            def pull(apple_silicon, model_id):
                serve.IS_APPLE_SILICON = apple_silicon
                serve.MODELS = serve._build_models()
                state = serve.State(model_id, tempfile.mkdtemp(), 0)
                srv = ThreadingHTTPServer(("127.0.0.1", 0),
                                          lambda *a, **k: serve.Handler(*a, state=state, **k))
                threading.Thread(target=srv.serve_forever, daemon=True).start()
                req = urllib.request.Request("http://127.0.0.1:%d/pull" % srv.server_address[1],
                                             data=b"", method="POST")
                try:
                    try:
                        with urllib.request.urlopen(req, timeout=10) as r:
                            status, headers, body = r.status, r.headers, r.read()
                    except urllib.error.HTTPError as e:
                        status, headers, body = e.code, e.headers, e.read()
                finally:
                    srv.shutdown()
                    srv.server_close()
                return {"status": status, "type": headers.get("Content-Type"),
                        "body": body.decode()}

            print("PROBE:" + json.dumps({
                "wan": pull(False, "wan-5b"),
                "cudaLtx": pull(False, "ltx"),
                "mlxLtx": pull(True, "ltx"),
            }))
            """;

    private static JsonObject probe() throws Exception {
        var dir = new File(Play.applicationPath, "sidecar/video");
        assertTrue(new File(dir, "serve.py").isFile(), "sidecar/video has moved or gone");

        var proc = new ProcessBuilder(List.of("python3", "-c", SCRIPT, dir.getAbsolutePath()))
                .redirectErrorStream(true).start();
        assertTrue(proc.waitFor(90, TimeUnit.SECONDS), "video sidecar probe timed out");
        var stdout = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertEquals(0, proc.exitValue(), "video sidecar probe failed: " + stdout);

        var answer = stdout.lines().filter(l -> l.startsWith(MARKER)).reduce((a, b) -> b);
        assertTrue(answer.isPresent(), "video sidecar probe printed no answer: " + stdout);
        return JsonParser.parseString(answer.get().substring(MARKER.length())).getAsJsonObject();
    }

    @Test
    void aWanPullStreamsNdjsonProgress() throws Exception {
        var wan = probe().getAsJsonObject("wan");
        assertEquals(200, wan.get("status").getAsInt(), wan.toString());
        assertEquals("application/x-ndjson", wan.get("type").getAsString());
        var lines = wan.get("body").getAsString().strip().lines().toList();
        var last = JsonParser.parseString(lines.getLast()).getAsJsonObject();
        assertTrue(last.get("done").getAsBoolean(), "the stream ends on its done line: " + lines);
    }

    @Test
    void anLtxPullIsRefusedBeforeAnyNdjsonHeader() throws Exception {
        var out = probe();
        for (var engine : List.of("cudaLtx", "mlxLtx")) {
            var ltx = out.getAsJsonObject(engine);
            assertEquals(400, ltx.get("status").getAsInt(), engine + ": " + ltx);
            assertEquals("application/json", ltx.get("type").getAsString(),
                    engine + " must not have started an ndjson stream");
            var body = JsonParser.parseString(ltx.get("body").getAsString()).getAsJsonObject();
            assertEquals("pull_unsupported", body.get("error").getAsString(), engine + ": " + body);
        }
    }
}
