package controllers;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller action as safe to surface to chat via the jclaw_api
 * tool's {@code discover} action (JCLAW-329).
 *
 * <p>The marker is the curation source of truth — co-located with the endpoint
 * so it cannot drift from the route — and replaces the hand-maintained endpoint
 * catalog that used to live in the jclaw-api skill's SKILL.md. Adding a new
 * endpoint and annotating it makes it discoverable at runtime with no skill edit
 * and no skill version bump.
 *
 * <p>{@link tools.JClawApiTool}'s {@code discover} reads {@link play.mvc.Router#routes},
 * resolves each route's action to its {@link java.lang.reflect.Method}, and includes
 * the ones annotated here. The {@code PATH_BLOCKLIST} in {@code JClawApiTool} stays an
 * unconditional deny-floor: a path that is both annotated and blocklisted is still
 * excluded (defense in depth — the marker is an allowlist, not an override).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ChatSafe {

    /** One-line description of the operation, shown in the discover catalog. */
    String summary();

    /**
     * Optional hint of the request-body fields for mutating verbs
     * (POST/PUT/PATCH), e.g. "name, modelProvider, modelId". Empty for
     * read-only operations.
     */
    String body() default "";
}
