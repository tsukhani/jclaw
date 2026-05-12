package jclaw.mcp.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConfigTest {

    @Test
    void parsesMinimalArgs() {
        var cfg = Config.parse(new String[] {
                "--base-url=http://localhost:9000",
                "--token=jcl_abcdef",
        });
        assertEquals("http://localhost:9000", cfg.baseUrl().toString());
        assertEquals("jcl_abcdef", cfg.bearerToken());
        // Default is READ_ONLY — least privilege. The README leans on
        // this so operators who copy the example without --scope can't
        // accidentally hand an agent write access.
        assertEquals(Config.Scope.READ_ONLY, cfg.scope());
        assertTrue(cfg.excludes().isEmpty());
    }

    @Test
    void parsesFullScopeAndMultipleExcludes() {
        var cfg = Config.parse(new String[] {
                "--base-url=http://jclaw.local:9000",
                "--token=jcl_x",
                "--scope=full",
                "--exclude=ApiAuthController",
                "--exclude=/api/metrics/loadtest",
        });
        assertEquals(Config.Scope.FULL, cfg.scope());
        assertEquals(2, cfg.excludes().size());
        assertTrue(cfg.excludes().contains("ApiAuthController"));
        assertTrue(cfg.excludes().contains("/api/metrics/loadtest"));
    }

    @Test
    void acceptsReadOnlyWithDashOrUnderscore() {
        // Operators typing read-only on the CLI and READ_ONLY in the
        // JClaw settings UI should both map to the same scope.
        var dashed = Config.parse(new String[] {
                "--base-url=http://localhost:9000", "--token=x", "--scope=read-only",
        });
        var underscored = Config.parse(new String[] {
                "--base-url=http://localhost:9000", "--token=x", "--scope=READ_ONLY",
        });
        assertEquals(Config.Scope.READ_ONLY, dashed.scope());
        assertEquals(Config.Scope.READ_ONLY, underscored.scope());
    }

    @Test
    void rejectsUnknownScope() {
        var thrown = assertThrows(IllegalArgumentException.class, () -> Config.parse(new String[] {
                "--base-url=http://localhost:9000", "--token=x", "--scope=admin",
        }));
        assertTrue(thrown.getMessage().contains("admin"),
                "error should name the offending value; got: " + thrown.getMessage());
    }

    @Test
    void rejectsMissingBaseUrl() {
        var thrown = assertThrows(IllegalArgumentException.class, () -> Config.parse(new String[] {
                "--token=x",
        }));
        assertTrue(thrown.getMessage().contains("base-url"),
                "error should mention base-url; got: " + thrown.getMessage());
    }

    @Test
    void rejectsMissingToken() {
        var thrown = assertThrows(IllegalArgumentException.class, () -> Config.parse(new String[] {
                "--base-url=http://localhost:9000",
        }));
        assertTrue(thrown.getMessage().contains("token"),
                "error should mention token; got: " + thrown.getMessage());
    }

    @Test
    void rejectsBaseUrlWithoutScheme() {
        var thrown = assertThrows(IllegalArgumentException.class, () -> Config.parse(new String[] {
                "--base-url=localhost:9000", "--token=x",
        }));
        // The early-exit guard against schemeless URLs prevents a
        // confusing "Connection refused" at runtime — fail fast at
        // config parse instead.
        assertTrue(thrown.getMessage().contains("scheme"),
                "error should mention scheme; got: " + thrown.getMessage());
    }
}
