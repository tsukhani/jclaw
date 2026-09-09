package tools;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Translates a {@link HarnessModel} override into what each coding harness accepts at launch.
 * Every harness has its own model flag and its own notion of a provider, so the table is
 * per harness id — what each installed CLI documents, and for claude and codex what was
 * verified against a local Ollama endpoint:
 *
 * <ul>
 *   <li>{@code claude} — {@code --model} on the {@code claude -p} wrapper, plus the
 *       {@code ANTHROPIC_MODEL} / {@code ANTHROPIC_BASE_URL} / {@code ANTHROPIC_AUTH_TOKEN}
 *       environment, which also reaches the {@code claude-agent-acp} adapter. The endpoint
 *       must speak the Anthropic Messages API (Ollama and OpenRouter do); Claude Code appends
 *       {@code /v1/messages} itself, so the provider's trailing {@code /v1} is dropped.</li>
 *   <li>{@code codex} — {@code -m}, plus an inline {@code model_providers.jclaw} block via
 *       {@code -c} naming the endpoint; the key travels in {@value #CODEX_KEY_ENV}, which the
 *       block references by name. Codex accepts only the Responses wire API for a custom
 *       provider ({@code wire_api = "chat"} is refused since 0.5x).</li>
 *   <li>{@code pi}, {@code gemini} — model flag only; both resolve providers from their own
 *       registry, so an endpoint override is refused up front rather than mistranslated.</li>
 *   <li>{@code opencode}, {@code antigravity}, {@code generic} (custom commands) — no model
 *       surface JClaw can drive from a provider/model pair; any override is refused.</li>
 * </ul>
 */
public final class HarnessModelBinding {

    /** Codex provider id declared by the inline config block. */
    public static final String CODEX_PROVIDER_ID = "jclaw";
    /** Environment variable carrying the provider key into codex (its {@code env_key}). */
    public static final String CODEX_KEY_ENV = "JCLAW_CODEX_API_KEY";
    /** Claude Code needs a non-empty token to prefer the configured endpoint over its own login. */
    public static final String PLACEHOLDER_TOKEN = "jclaw";

    private static final String HARNESS_CLAUDE = "claude";
    private static final String HARNESS_CODEX = "codex";
    private static final String HARNESS_PI = "pi";
    private static final String HARNESS_GEMINI = "gemini";
    private static final String FLAG_CONFIG = "-c";

    private HarnessModelBinding() {}

    /** Why {@code harnessId} cannot take {@code model}, or {@code null} when it can. */
    public static @Nullable String rejection(String harnessId, HarnessModel model) {
        return switch (harnessId) {
            case HARNESS_CLAUDE, HARNESS_CODEX -> null;
            case HARNESS_PI, HARNESS_GEMINI -> model.hasEndpoint()
                    ? "harness '" + harnessId + "' resolves providers from its own registry, so JClaw "
                            + "cannot point it at provider '" + model.providerName() + "'. Override the "
                            + "model only, or use the claude or codex harness."
                    : null;
            default -> "harness '" + harnessId + "' takes no model override from JClaw; set its model "
                    + "in the harness's own configuration.";
        };
    }

    /** Flags appended to the stdin/stdout wrapper argv (batch, json and rpc modes). */
    public static List<String> wrapperArgs(String harnessId, HarnessModel model) {
        return switch (harnessId) {
            case HARNESS_CLAUDE, HARNESS_PI -> List.of("--model", model.modelId());
            case HARNESS_CODEX -> codexArgs(model);
            case HARNESS_GEMINI -> List.of("-m", model.modelId());
            default -> List.of();
        };
    }

    /** Flags appended to the harness's ACP launch command (the acp-core path). */
    public static List<String> acpArgs(String harnessId, HarnessModel model) {
        return switch (harnessId) {
            case HARNESS_CODEX -> codexArgs(model);
            case HARNESS_GEMINI -> List.of("-m", model.modelId());
            // claude-agent-acp exposes no model flag; the ANTHROPIC_* env carries the override.
            default -> List.of();
        };
    }

    /** Environment entries layered over the secret-filtered child env (they win). */
    public static Map<String, String> env(String harnessId, HarnessModel model) {
        var env = new LinkedHashMap<String, String>();
        switch (harnessId) {
            case HARNESS_CLAUDE -> {
                env.put("ANTHROPIC_MODEL", model.modelId());
                if (model.hasEndpoint()) {
                    env.put("ANTHROPIC_BASE_URL", anthropicRoot(model.resolvedBaseUrl()));
                    var key = model.apiKey();
                    env.put("ANTHROPIC_AUTH_TOKEN", key == null || key.isBlank() ? PLACEHOLDER_TOKEN : key);
                }
            }
            case HARNESS_CODEX -> {
                var key = model.apiKey();
                if (model.hasEndpoint() && key != null && !key.isBlank()) env.put(CODEX_KEY_ENV, key);
            }
            default -> { /* no environment surface */ }
        }
        return env;
    }

    /** One transcript line: the override and how it reaches the harness — env var names, never values. */
    public static String describe(String harnessId, HarnessModel model, boolean acpLaunch) {
        var args = acpLaunch ? acpArgs(harnessId, model) : wrapperArgs(harnessId, model);
        var envNames = env(harnessId, model).keySet();
        var sb = new StringBuilder("model override ").append(model.label()).append(" for harness ")
                .append(harnessId).append(':');
        if (!args.isEmpty()) sb.append(' ').append(String.join(" ", args));
        if (!envNames.isEmpty()) sb.append(args.isEmpty() ? " env " : "; env ").append(String.join(", ", envNames));
        return sb.toString();
    }

    private static List<String> codexArgs(HarnessModel model) {
        var args = new ArrayList<String>();
        args.add("-m");
        args.add(model.modelId());
        if (!model.hasEndpoint()) return List.copyOf(args);
        var prefix = "model_providers." + CODEX_PROVIDER_ID + ".";
        args.add(FLAG_CONFIG);
        args.add("model_provider=" + CODEX_PROVIDER_ID);
        args.add(FLAG_CONFIG);
        args.add(prefix + "name=" + toml(String.valueOf(model.providerName())));
        args.add(FLAG_CONFIG);
        args.add(prefix + "base_url=" + toml(stripTrailingSlashes(model.resolvedBaseUrl())));
        args.add(FLAG_CONFIG);
        args.add(prefix + "wire_api=" + toml("responses"));
        var key = model.apiKey();
        if (key != null && !key.isBlank()) {
            args.add(FLAG_CONFIG);
            args.add(prefix + "env_key=" + toml(CODEX_KEY_ENV));
        }
        return List.copyOf(args);
    }

    /** A TOML basic string: backslashes and double quotes escaped, wrapped in quotes. */
    public static String toml(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** Claude Code appends {@code /v1/messages} to its base URL, so hand it the provider's root. */
    public static String anthropicRoot(String baseUrl) {
        var root = stripTrailingSlashes(baseUrl);
        if (root.endsWith("/v1")) root = root.substring(0, root.length() - 3);
        return stripTrailingSlashes(root);
    }

    private static String stripTrailingSlashes(String url) {
        var s = url.strip();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
