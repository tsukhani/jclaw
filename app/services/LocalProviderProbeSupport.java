package services;

import com.google.gson.JsonParser;
import llm.LlmOkHttpClient;
import okhttp3.OkHttpClient;
import okhttp3.Request;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;

final class LocalProviderProbeSupport {

    record Result(boolean available, int modelCount, String reason, boolean connectionRefused) { }

    /**
     * Short-timeout client for boot probes — 2s connect, 5s read, no SSE so
     * no extended call timeout. Derived from {@link LlmOkHttpClient#SINGLE_SHOT}
     * via {@link OkHttpClient#newBuilder()} so it shares the same shared
     * connection pool and dispatcher; the only overrides are the tighter
     * timeouts that "is the local provider alive at boot" deserves.
     */
    private static final OkHttpClient PROBE_CLIENT = LlmOkHttpClient.SINGLE_SHOT.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .readTimeout(Duration.ofSeconds(5))
            .callTimeout(Duration.ofSeconds(7))
            .build();

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
        try (var resp = PROBE_CLIENT.newCall(req).execute()) {
            if (resp.code() != 200) {
                return new Result(false, 0,
                        "GET %s/models returned HTTP %d".formatted(baseUrl, resp.code()),
                        false);
            }
            var bodyStr = resp.body() != null ? resp.body().string() : "";
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
