import llm.LlmProvider;
import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.ProviderConfig;
import llm.OpenAiProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import play.Play;
import play.test.UnitTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * JCLAW-185 AC3 perf baseline. Drives the configured cloud provider through
 * both the {@code jdk} and {@code okhttp} drivers for {@code N} turns and
 * emits a markdown table of p50/p95/p99 across three metrics: total turn
 * time, time-to-first-token, and inter-token gap.
 *
 * <p>Auto-skipped unless {@code OKHTTP_BENCH_BASE_URL} is set in the
 * environment, so it never fires under {@code play autotest} on a fresh
 * checkout. To run:
 *
 * <pre>{@code
 *   export OKHTTP_BENCH_BASE_URL=https://openrouter.ai/api/v1
 *   export OKHTTP_BENCH_API_KEY=sk-or-...
 *   export OKHTTP_BENCH_MODEL=openai/gpt-oss-120b   # optional, default below
 *   export OKHTTP_BENCH_PROVIDER=openrouter         # optional, default below
 *   play autotest
 * }</pre>
 *
 * <p>Output goes to stdout and to {@code tmp/okhttp-bench.md} (gitignored
 * with the rest of {@code tmp/}); paste it into the JCLAW-185 ticket as
 * the perf baseline that JCLAW-187 will compare against before flipping
 * the default driver.
 *
 * <p>The deterministic-shape prompt asks for 20 short outputs so the
 * completion length, and therefore the inter-token-gap sample count, is
 * stable across runs. Sampling temperature is the provider default —
 * latency is dominated by network and tokenization, not by sampling
 * variance, at this n.
 */
@EnabledIfEnvironmentVariable(named = "OKHTTP_BENCH_BASE_URL", matches = ".+",
        disabledReason = "set OKHTTP_BENCH_BASE_URL + OKHTTP_BENCH_API_KEY to run")
public class OkHttpLlmDriverBenchmark extends UnitTest {

    private static final int N = 50;
    private static final String PROMPT =
            "List the names of 20 famous physicists, one per line, no numbering or commentary.";

    @Test
    public void benchmark_jdkVsOkhttp() throws Exception {
        var baseUrl = System.getenv("OKHTTP_BENCH_BASE_URL");
        var apiKey  = System.getenv("OKHTTP_BENCH_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OKHTTP_BENCH_API_KEY must be set when OKHTTP_BENCH_BASE_URL is");
        }
        var model = System.getenv().getOrDefault("OKHTTP_BENCH_MODEL", "openai/gpt-oss-120b");
        var providerName = System.getenv().getOrDefault("OKHTTP_BENCH_PROVIDER", "openrouter");

        var config = new ProviderConfig(providerName, baseUrl, apiKey, List.of());
        var provider = new OpenAiProvider(config);

        System.out.println("[bench] " + providerName + " " + model + ", " + N + " turns/driver");
        var jdk = drive(provider, model, "jdk");
        var okhttp = drive(provider, model, "okhttp");

        var md = report(providerName, model, jdk, okhttp);
        System.out.println(md);
        Files.createDirectories(Path.of("tmp"));
        Files.writeString(Path.of("tmp/okhttp-bench.md"), md);
        System.out.println("[bench] wrote tmp/okhttp-bench.md");
    }

    private static Stats drive(LlmProvider provider, String model, String mode) throws Exception {
        Play.configuration.setProperty("play.llm.client", mode);
        var totals = new ArrayList<Long>(N);
        var ttfts  = new ArrayList<Long>(N);
        var gaps   = new ArrayList<Long>(N * 64);
        for (int i = 0; i < N; i++) {
            var latch = new CountDownLatch(1);
            var startNs = System.nanoTime();
            var firstNs = new long[]{-1L};
            var lastNs  = new long[]{-1L};
            provider.chatStream(model, List.of(ChatMessage.user(PROMPT)), null,
                    chunk -> {
                        var now = System.nanoTime();
                        if (firstNs[0] == -1L) firstNs[0] = now;
                        if (lastNs[0] != -1L) gaps.add(now - lastNs[0]);
                        lastNs[0] = now;
                    },
                    latch::countDown,
                    e -> { e.printStackTrace(); latch.countDown(); },
                    null, null);
            latch.await();
            var endNs = System.nanoTime();
            totals.add(endNs - startNs);
            if (firstNs[0] != -1L) ttfts.add(firstNs[0] - startNs);
            if ((i + 1) % 10 == 0) System.out.printf("[bench] %s %d/%d%n", mode, i + 1, N);
        }
        return new Stats(totals, ttfts, gaps);
    }

    private record Stats(List<Long> totals, List<Long> ttfts, List<Long> gaps) { }

    private static String report(String providerName, String model, Stats jdk, Stats okhttp) {
        var sb = new StringBuilder();
        sb.append("# JCLAW-185 OkHttp benchmark\n\n");
        sb.append("- provider: `").append(providerName).append("`\n");
        sb.append("- model: `").append(model).append("`\n");
        sb.append("- turns per driver: ").append(N).append("\n");
        sb.append("- latencies in milliseconds\n\n");
        sb.append("| metric              | driver |   p50 |   p95 |   p99 |\n");
        sb.append("|---------------------|--------|------:|------:|------:|\n");
        appendRow(sb, "total turn time",     jdk.totals, okhttp.totals);
        appendRow(sb, "time to first token", jdk.ttfts,  okhttp.ttfts);
        appendRow(sb, "inter-token gap",     jdk.gaps,   okhttp.gaps);
        sb.append("\nAC3 gate: each metric's p50/p95/p99 must agree to within plus/minus 5 percent across drivers.\n");
        return sb.toString();
    }

    private static void appendRow(StringBuilder sb, String label, List<Long> jdk, List<Long> okhttp) {
        sb.append("| ").append(pad(label, 19)).append(" | jdk    | ")
                .append(fmt(ms(jdk, 0.50))).append(" | ")
                .append(fmt(ms(jdk, 0.95))).append(" | ")
                .append(fmt(ms(jdk, 0.99))).append(" |\n");
        sb.append("| ").append(pad("", 19)).append(" | okhttp | ")
                .append(fmt(ms(okhttp, 0.50))).append(" | ")
                .append(fmt(ms(okhttp, 0.95))).append(" | ")
                .append(fmt(ms(okhttp, 0.99))).append(" |\n");
    }

    private static long ms(List<Long> ns, double q) {
        if (ns.isEmpty()) return 0L;
        var sorted = ns.stream().sorted().toList();
        return sorted.get(Math.min((int) (sorted.size() * q), sorted.size() - 1)) / 1_000_000L;
    }

    private static String fmt(long v) { return "%5d".formatted(v); }
    private static String pad(String s, int w) { return ("%-" + w + "s").formatted(s); }
}
