package controllers;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller action as <em>not</em> callable through the {@code jclaw_api}
 * tool's {@code discover}/{@code call} surface (JCLAW-XXX).
 *
 * <p>This is the blacklist marker for the {@code jclaw_api} discovery surface.
 * As of the blacklist migration, {@code jclaw_api} discovers and invokes
 * <em>every</em> {@code /api/} route that resolves to a controller action by
 * default — so new endpoints are reachable with no annotation — <b>unless</b>
 * they are caught by {@code JClawApiTool}'s {@code PATH_BLOCKLIST} deny-floor
 * (coarse, whole-subsystem categories) or carry this marker (a precise,
 * per-action opt-out within an otherwise-discoverable controller).
 *
 * <p>Use it for actions that exist as real features but must never be driven by
 * an LLM agent: destructive bulk operations, secret-bearing config, or the 404
 * catch-all. Prefer the deny-floor for an entire subsystem; reach for this
 * annotation when only specific actions of a controller are off-limits.
 *
 * <p>Catalog metadata (summary + body hint) comes from the Swagger
 * {@code @Operation} and {@code @RequestBody} annotations, not from this marker.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ChatHidden {

    /** Optional human-readable reason this action is hidden, for maintainers. */
    String value() default "";
}
