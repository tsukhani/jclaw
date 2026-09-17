package utils;

import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.Map;

import static utils.ErrorTemplateSupport.CHECK_LOGS;
import static utils.ErrorTemplateSupport.e;

/**
 * Templates for the {@link ApiResponses} error codes (JCLAW-1130).
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
 * <p>Public only for those sites' factories, such as {@link #configValueRejected}.
 */
public final class ApiErrorTemplates {

    private ApiErrorTemplates() {}

    /** A config write {@code ConfigService.setWithSideEffects} refused on its value. */
    public static final String CONFIG_VALUE_REJECTED = "config_value_rejected";

    /**
     * Sent under the wire code {@code forbidden}, whose own row reads as a missing permission and
     * would send the operator looking for one; the rejection names a value to correct.
     */
    public static ErrorTemplate configValueRejected() {
        return new ErrorTemplate(CONFIG_VALUE_REJECTED,
                "The value was refused, so nothing was saved.",
                "The reason above names the rule the value broke. The previous value is still in effect.",
                "Correct the value and save again.");
    }

    /** An {@code /api} path no route matches (JCLAW-1218). */
    public static final String API_PATH_NOT_FOUND = "api_path_not_found";

    /**
     * Sent under the wire code {@code not_found}, whose own row assumes a deleted record and says to
     * pick one from the list; here nothing was ever at the path.
     */
    public static ErrorTemplate apiPathNotFound() {
        return new ErrorTemplate(API_PATH_NOT_FOUND,
                "No API endpoint matches that path.",
                "Check the path and the HTTP method: the route may have been renamed, or the request "
                        + "may carry a typo. This is a missing endpoint, not a missing record.",
                "Correct the path and send the request again.");
    }

    /** A request the load-test gate refused (JCLAW-1218). */
    public static final String LOADTEST_ACCESS_DENIED = "loadtest_access_denied";

    /**
     * Sent under the wire code {@code forbidden}, whose own row assumes a signed-in operator lacking a
     * permission. Names the gate's requirements but never the value its header must carry: this
     * reaches whoever sent the refused request.
     */
    public static ErrorTemplate loadtestAccessDenied() {
        return new ErrorTemplate(LOADTEST_ACCESS_DENIED,
                "The load-test endpoints refused this request.",
                "They accept only a request from this machine that carries the X-Loadtest-Auth header. "
                        + "A request from another host, or one without the right header, is refused.",
                "Run the load test from this machine through ./jclaw.sh loadtest, which supplies the header.");
    }

    /** Enabling a skill the agent's workspace has no copy of (JCLAW-1221). */
    public static final String SKILL_NOT_INSTALLED = "skill_not_installed";

    /**
     * Sent under the wire code {@code invalid_request}, whose own row says to fix malformed fields; this
     * request is well formed and names a skill the agent does not have.
     */
    public static ErrorTemplate skillNotInstalled() {
        return new ErrorTemplate(SKILL_NOT_INSTALLED,
                "The skill is not installed on this agent, so it cannot be enabled.",
                "Only a skill copied into the agent's workspace can be switched on. On the Skills page, "
                        + "drag the skill from the global list onto the agent to install it.",
                "Install the skill on the agent, then switch it on again.");
    }

    private static final String LENGTH_RULE_CHECK =
            "Length is the only rule — there are no composition requirements.";

    private static final String LENGTH_CAP_CHECK =
            "The cap bounds how long each sign-in takes to check; it is not a strength judgement.";

    /** A setup or reset password under the minimum, naming the minimum rather than only its existence. */
    public static ErrorTemplate passwordTooShort(int minLength) {
        return new ErrorTemplate(ApiResponses.PASSWORD_TOO_SHORT,
                "That password is shorter than %d characters, the minimum.".formatted(minLength),
                LENGTH_RULE_CHECK,
                "Choose a password of at least %d characters and submit again.".formatted(minLength));
    }

    /** A setup or reset password over the cap, naming the cap. */
    public static ErrorTemplate passwordTooLong(int maxLength) {
        return new ErrorTemplate(ApiResponses.PASSWORD_TOO_LONG,
                "That password is longer than %d characters, the maximum.".formatted(maxLength),
                LENGTH_CAP_CHECK,
                "Choose a password of at most %d characters and submit again.".formatted(maxLength));
    }

    /**
     * A webhook body over its endpoint's cap, naming the size when it is known and the cap with the
     * key that sets it — the only sites that send {@code payload_too_large} are the webhook gates.
     *
     * @param bodyBytes the declared or read size, or null when the request did not declare one
     */
    public static ErrorTemplate payloadTooLarge(@Nullable Long bodyBytes, long limitBytes,
                                                String limitKey) {
        var broke = bodyBytes == null
                ? String.format(Locale.ROOT,
                        "The request body is over the %,d-byte limit for this endpoint.", limitBytes)
                : String.format(Locale.ROOT,
                        "The request body is %,d bytes, over the %,d-byte limit for this endpoint.",
                        bodyBytes, limitBytes);
        return new ErrorTemplate(ApiResponses.PAYLOAD_TOO_LARGE, broke,
                ("The endpoint refuses a body over the limit %s sets before handling it, so nothing "
                        + "in this request was processed.").formatted(limitKey),
                "Send a smaller body, or raise %s in application.conf and restart JClaw.".formatted(limitKey));
    }

    private static final Map<String, ErrorTemplate> TEMPLATES = Map.ofEntries(
            e(ApiResponses.INVALID_REQUEST, "The request was not in a form the server could accept.",
                    "The message above names the field or value that was refused.",
                    "Correct that value and submit again."),
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
            e(ApiResponses.SESSION_REVOKED, "This session was signed out.",
                    "Expected if you signed out in another tab or window. Only that session is ended; "
                            + "any other stays signed in.",
                    "Sign in again."),
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
                    LENGTH_RULE_CHECK, "Choose a longer password and submit again."),
            e(ApiResponses.PASSWORD_TOO_LONG, "That password is longer than the maximum length.",
                    LENGTH_CAP_CHECK, "Shorten it and submit again."),
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
            e(ApiResponses.PAYLOAD_TOO_LARGE, "The request body is larger than this endpoint accepts.",
                    "The endpoint refuses a body over its limit before handling it, so nothing in this "
                            + "request was processed.",
                    "Send a smaller body, or raise the endpoint's max-body-bytes setting in application.conf "
                            + "and restart JClaw."),
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

    static Map<String, ErrorTemplate> templates() {
        return TEMPLATES;
    }
}
