package utils;

import org.jspecify.annotations.NonNull;

import java.util.Map;

/**
 * The error code to {@link ErrorTemplate} registry (JCLAW-1130).
 *
 * <p>Keyed off the {@link ApiResponses} constants (JCLAW-1138), so a code has one spelling
 * and renaming its value cannot leave a template stranded under the old key. This costs
 * nothing at runtime: javac inlines a {@code static final String} into the caller, so the
 * reference compiles to the same literal and adds no dependency on {@code ApiResponses}.
 * Registering by code is what makes the surface stories cheap:
 * the ~214 API call sites already funnel through {@code ApiResponses}, so they gain a remedy
 * from this table without being edited. Only a site whose default is too generic to help needs
 * to supply its own.
 *
 * <p><b>An unregistered code is not an error.</b> {@link #forCode} falls back to a generic
 * template rather than returning null or throwing: this table is consulted on the failure path,
 * and a lookup that fails there turns a handled error into an unhandled one. The fallback is
 * deliberately vague — that is the signal to add a row, not a reason to break the request.
 */
public final class ErrorTemplates {

    private ErrorTemplates() {}

    private static final String CHECK_LOGS = "Open Logs and find the matching entry — it carries "
            + "the technical detail this message deliberately omits.";

    private static final Map<String, ErrorTemplate> BY_CODE = Map.ofEntries(
            // --- ApiResponses constants ---
            e(ApiResponses.INVALID_REQUEST, "The request was not in a form the server could accept.",
                    "Check the fields you submitted for missing or malformed values.",
                    "Correct the highlighted fields and submit again."),
            e(ApiResponses.INTERNAL_ERROR, "Something failed on the server while handling the request.",
                    CHECK_LOGS,
                    "Retry the operation. If it fails the same way twice, the log entry is the thing to report."),
            e(ApiResponses.CONFLICT, "The change clashes with something that already exists.",
                    "Check whether the name, binding or record you are creating is already in use.",
                    "Choose a different name, or edit the existing record instead of creating one."),
            e(ApiResponses.NOT_FOUND, "The thing you addressed does not exist.",
                    "Check the id or name in the address bar — it may have been deleted, or renamed.",
                    "Go back to the list and pick the record from there."),

            // --- authentication and session ---
            e(ApiResponses.AUTHENTICATION_REQUIRED, "You are not signed in.", "Nothing to check — the session is absent or expired.",
                    "Sign in and try again."),
            e(ApiResponses.CREDENTIALS_CHANGED, "Your session was ended because the admin password changed.",
                    "This is expected after a password change or reset; every other session is signed out too.",
                    "Sign in with the new password."),
            e(ApiResponses.INVALID_CREDENTIALS, "That username and password were not accepted.",
                    "Check for caps lock and stray whitespace. Repeated failures are throttled per source.",
                    "Try again, or reset the password if you no longer have it."),
            e(ApiResponses.INVALID_TOKEN, "The API token was not accepted.",
                    "Check the token has not been revoked and was copied whole.",
                    "Issue a fresh token and retry with it."),
            e(ApiResponses.PASSWORD_UNSET, "This instance has no admin password yet.",
                    "A fresh install, or one whose password row was cleared.",
                    "Complete the setup flow to choose a password."),
            e(ApiResponses.TOO_MANY_ATTEMPTS, "Too many attempts from this source in a short window.",
                    "The throttle is per source address and clears on its own.",
                    "Wait for the window to pass, then try again."),
            e(ApiResponses.ALREADY_SET, "The admin password is already configured.",
                    "Setup only applies to an instance that has none — this one is past that point.",
                    "Sign in instead, or reset the password if you no longer have it."),
            e(ApiResponses.PASSWORD_TOO_SHORT, "That password is shorter than the minimum length.",
                    "Length is the only rule — there are no composition requirements.",
                    "Choose a longer password and submit again."),
            e(ApiResponses.PASSWORD_TOO_LONG, "That password is longer than the maximum length.",
                    "The cap bounds per-attempt hashing cost; it is not a strength judgement.",
                    "Shorten it and submit again."),
            e(ApiResponses.PASSWORD_BREACHED, "That password appears in a known breach corpus.",
                    "The check is against published breach data, not a judgement of its strength.",
                    "Choose a different password. This one cannot be used even if retried."),

            // --- authorization ---
            e(ApiResponses.FORBIDDEN, "You are signed in, but not allowed to do this.",
                    "Check whether the operation is operator-only.", null),
            e(ApiResponses.OPERATOR_ONLY, "Only the operator can make this change — an agent cannot.",
                    "This is a deliberate boundary: an agent must not widen its own configuration.",
                    "Make the change yourself in the admin UI."),
            e(ApiResponses.APP_SCOPE, "A hosted app tried to reach outside its own agent.",
                    "An app may only invoke the agent it was installed for.",
                    "If the app genuinely needs this, it needs installing against that agent."),

            // --- agents and apps ---
            e(ApiResponses.NO_AGENT, "No agent was specified.", "Check the request names an agent.",
                    "Pick an agent and retry."),
            e(ApiResponses.UNKNOWN_AGENT, "No agent by that name or id exists.",
                    "Check the name — an agent may have been renamed or deleted.",
                    "Pick an agent from the list and retry."),
            e(ApiResponses.BAD_AGENT, "That agent cannot serve this request.",
                    "Check the agent is enabled and has a model configured.",
                    "Fix the agent's configuration, then retry."),
            e(ApiResponses.NO_SUCH_APP, "No installed app by that slug.",
                    "Check the slug against the installed apps list.",
                    "Install the app, or correct the slug."),

            // --- channel bindings ---
            e(ApiResponses.BOT_TOKEN_CONFLICT, "That bot token is already bound to another agent.",
                    "A token drives exactly one binding; check which agent already holds it.",
                    "Release the existing binding, or use a different bot token."),
            e(ApiResponses.PHONE_NUMBER_CONFLICT, "That phone number is already bound to another agent.",
                    "A number drives exactly one binding; check which agent already holds it.",
                    "Release the existing binding, or use a different number."),
            e(ApiResponses.CLOUD_API_VERIFICATION_FAILED, "The channel provider rejected the credentials.",
                    "Check the phone number id, access token and app secret against the provider's console.",
                    "Correct the credentials and save again to re-verify."),

            // --- request shape and limits ---
            e(ApiResponses.NO_INPUT, "The request carried no content to act on.",
                    "Check that a message, file or field was actually included.",
                    "Add the content and submit again."),
            e(ApiResponses.PAYLOAD_TOO_LARGE, "The upload is larger than the configured limit.",
                    "The limit is configurable in Settings.",
                    "Send a smaller file, or raise the limit and retry."),
            e(ApiResponses.RESERVED_KEY, "That configuration key is reserved and cannot be set here.",
                    "Reserved namespaces are enforced by the server; a stored row could not take effect anyway.",
                    null),

            // --- upstream and capacity ---
            e(ApiResponses.RATE_LIMITED, "An upstream service is rate-limiting these requests.",
                    "This is the provider throttling us, not a fault in the request.",
                    "Wait and retry. If it persists, check the provider's quota."),
            e(ApiResponses.UPSTREAM_ERROR, "An upstream service returned an error.",
                    CHECK_LOGS + " It records what the provider actually returned.",
                    "Retry. If it repeats, check the provider's status page."),
            e(ApiResponses.UNAVAILABLE, "The feature is not available on this instance.",
                    "Check whether the provider or sidecar it needs is configured and running.",
                    "Configure the missing dependency, then retry."),
            e(ApiResponses.TTS_UNAVAILABLE, "Speech synthesis is not available.",
                    "Check the configured voice provider, and that its sidecar is running.",
                    "Fix the voice configuration in Settings, then retry."),
            e(ApiResponses.POOL_UNAVAILABLE, "No capacity was free to serve the request.",
                    "Usually transient — a burst rather than a misconfiguration.",
                    "Retry shortly."),
            e(ApiResponses.IO_ERROR, "A file or network operation failed.",
                    CHECK_LOGS + " It names the path or host involved.",
                    "Retry. If it repeats, check disk space and permissions."),
            e(ApiResponses.SEARCH_FAILED, "The search could not be completed.",
                    "Usually the full-text index rather than the query — check Logs for an index error.",
                    "Retry the search. A persistent failure needs the index rebuilding."));

    /** Terser construction for the table above, which is otherwise 33 near-identical calls. */
    private static Map.Entry<String, ErrorTemplate> e(String code, String broke, String check,
                                                      String retry) {
        return Map.entry(code, new ErrorTemplate(code, broke, check, retry));
    }

    /**
     * The template for {@code code}, or a generic one when the code has no row yet.
     *
     * <p>Never null and never throws — see the class note on why the failure path must not have
     * its own failure mode.
     */
    public static ErrorTemplate forCode(@NonNull String code) {
        var known = BY_CODE.get(code);
        return known != null ? known : fallback(code);
    }

    /** Whether {@code code} has a specific template, as opposed to falling back. */
    public static boolean isRegistered(@NonNull String code) {
        return BY_CODE.containsKey(code);
    }

    private static ErrorTemplate fallback(String code) {
        return new ErrorTemplate(code, "The operation did not complete.", CHECK_LOGS,
                "Retry the operation.");
    }
}
