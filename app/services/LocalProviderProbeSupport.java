package services;

import com.google.gson.JsonParser;
import okhttp3.Request;
import utils.HttpFactories;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;

final class LocalProviderProbeSupport {

    /** Boot probes don't need the LLM single-shot's 180s default — 7s is plenty. */
    private static final long PROBE_TIMEOUT_SECONDS = 7;

    record Result(boolean available, int modelCount, String reason, boolean connectionRefused) { }

    private LocalProviderProbeSupport() {}

    /**
     * Probe a local LLM provider's {@code /models} endpoint. JCLAW-186: the
     * old {@code HttpClient.Version} parameter is gone — OkHttp does not
     * attempt h2c upgrade on plain HTTP, so the LM Studio Express/Node
     * upgrade-event hang that the JDK driver had to dodge is structurally
     * absent here. Both probes share one client, no special-casing.
     */
    static Result probeModels(String baseUrl, String notRunningLabel) {
        var req = new Request.Builder()
                .url(baseUrl + "/models")
                .get()
                .build();
        var call = HttpFactories.llmSingleShot().newCall(req);
        call.timeout().timeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        try (var resp = call.execute()) {
            if (resp.code() != 200) {
                return new Result(false, 0,
                        "GET %s/models returned HTTP %d".formatted(baseUrl, resp.code()),
                        false);
            }
            var bodyStr = resp.body().string();
            var json = JsonParser.parseString(bodyStr).getAsJsonObject();
            var data = json.has("data") ? json.getAsJsonArray("data") : null;
            return new Result(true, data == null ? 0 : data.size(), null, false);
        } catch (ConnectException e) {
            return new Result(false, 0,
                    "%s not reachable (%s not running)".formatted(baseUrl, notRunningLabel),
                    true);
        } catch (SocketTimeoutException e) {
            // Connect timeout vs read timeout — OkHttp surfaces both as
            // SocketTimeoutException. Connect timeout still means the
            // provider isn't running on that port; read timeout means the
            // server accepted the connection but didn't respond in time.
            // The connectionRefused-style hint is most useful for the
            // "not running" case, so default to that classification.
            return new Result(false, 0,
                    "%s not reachable (%s not running)".formatted(baseUrl, notRunningLabel),
                    true);
        } catch (IOException e) {
            return new Result(false, 0,
                    "%s probe failed: %s".formatted(baseUrl, e.getMessage()),
                    false);
        }
    }
}
