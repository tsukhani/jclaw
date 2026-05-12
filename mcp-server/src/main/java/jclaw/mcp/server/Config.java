package jclaw.mcp.server;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * Operator-supplied configuration for one MCP server process.
 *
 * <p>Surfaced through CLI flags ({@link #parse(String[])}) with env-var
 * fall-throughs so the same launch line works in Claude Desktop's
 * declarative config and in a shell where exporting {@code JCLAW_*}
 * is more natural than embedding the token.
 *
 * <p>{@link #scope} is the operator's <i>declared</i> scope hint — it
 * mirrors the scope baked into {@link #bearerToken} on the JClaw
 * backend, and the tool generator uses it to refuse to <em>advertise</em>
 * mutating tools at all when read-only. Belt-and-suspenders: even if
 * an operator mis-declares the scope, JClaw's auth filter still 403s
 * any mutating call from a read-only-scoped token.
 */
public record Config(
        URI baseUrl,
        String bearerToken,
        Scope scope,
        List<String> excludes) {

    public enum Scope { READ_ONLY, FULL }

    public Config {
        if (baseUrl == null) throw new IllegalArgumentException("baseUrl required");
        if (bearerToken == null || bearerToken.isBlank()) {
            throw new IllegalArgumentException("bearerToken required");
        }
        if (scope == null) scope = Scope.READ_ONLY;
        excludes = excludes == null ? List.of() : List.copyOf(excludes);
    }

    /** Parse argv + env. Throws {@link IllegalArgumentException} with
     *  a usage-style message on invalid input so {@link Main} can
     *  print it and exit non-zero. */
    public static Config parse(String[] argv) {
        String baseUrl = env("JCLAW_BASE_URL");
        String token = env("JCLAW_API_TOKEN");
        Scope scope = parseScope(env("JCLAW_SCOPE"));
        var excludes = new java.util.ArrayList<String>();

        for (var arg : argv) {
            if (arg.startsWith("--base-url=")) baseUrl = arg.substring("--base-url=".length());
            else if (arg.startsWith("--token=")) token = arg.substring("--token=".length());
            else if (arg.startsWith("--scope=")) scope = parseScope(arg.substring("--scope=".length()));
            else if (arg.startsWith("--exclude=")) excludes.add(arg.substring("--exclude=".length()));
            else if (arg.equals("--help") || arg.equals("-h")) throw new IllegalArgumentException(usage());
            else throw new IllegalArgumentException("Unknown argument: " + arg + "\n\n" + usage());
        }

        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException(
                    "--base-url (or JCLAW_BASE_URL) is required\n\n" + usage());
        }
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException(
                    "--token (or JCLAW_API_TOKEN) is required\n\n" + usage());
        }

        URI uri;
        try {
            uri = URI.create(baseUrl);
        }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "--base-url is not a valid URI: " + baseUrl, e);
        }
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException(
                    "--base-url must include scheme and host (e.g. http://localhost:9000): " + baseUrl);
        }

        return new Config(uri, token, scope, excludes);
    }

    private static Scope parseScope(String raw) {
        if (raw == null || raw.isBlank()) return Scope.READ_ONLY;
        var normalized = raw.toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return Scope.valueOf(normalized);
        }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown --scope value: " + raw + " (expected read-only or full)");
        }
    }

    private static String env(String key) {
        return System.getenv(key);
    }

    public static String usage() {
        return """
                Usage: jclaw-mcp-server [options]

                Options:
                  --base-url=<url>      JClaw API base URL (env: JCLAW_BASE_URL)
                                        Example: http://localhost:9000
                  --token=<token>       Bearer API token minted from Settings (env: JCLAW_API_TOKEN)
                  --scope=<scope>       read-only (default) or full
                                        Determines which OpenAPI operations are
                                        advertised as MCP tools. Must match the
                                        scope of the token; the JClaw backend
                                        rejects mismatches at request time.
                  --exclude=<pattern>   Skip operations whose operationId or path
                                        contains this substring. Repeatable.
                  --help, -h            This help.

                Wire protocol: stdio JSON-RPC 2.0 per the MCP spec.
                Logs are emitted on stderr; stdout is reserved for the protocol.
                """.stripIndent();
    }
}
