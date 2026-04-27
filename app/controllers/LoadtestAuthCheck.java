package controllers;

import play.Play;
import play.mvc.Before;
import play.mvc.Controller;
import play.mvc.Http;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

/**
 * Auth interceptor for the loadtest endpoints under
 * /api/metrics/loadtest. Parallel to {@link AuthCheck} — NOT a bypass.
 *
 * <p>The loadtest endpoint exists to drive the in-process mock LLM
 * provider against the running JVM. After commit caf9422 moved the
 * admin password to a PBKDF2 hash in the Config DB, neither the
 * jclaw.sh shell driver nor LoadTestRunner has plaintext admin
 * credentials to log in with through /api/auth/login. JCLAW-181
 * replaces that flow with this two-factor guard:
 *
 * <ul>
 *   <li>The request must originate from a loopback address
 *       (127.0.0.1 or the IPv6 loopback). This binds loadtest to
 *       same-host operation, matching its design intent.</li>
 *   <li>The request must carry an {@code X-Loadtest-Auth} header whose
 *       value equals {@code application.secret}, compared in constant
 *       time. Reusing application.secret avoids introducing a separate
 *       operator-managed credential — same trust boundary as today
 *       (read access to {@code .env} already grants session forgery).</li>
 * </ul>
 *
 * <p>On any failure the response is 403; the body deliberately does not
 * distinguish missing-header from wrong-value to avoid feeding probe
 * loops on a misconfigured deployment.
 */
public class LoadtestAuthCheck extends Controller {

    /**
     * Loopback addresses Netty / Play 1.x can produce for local
     * connections. IPv4 loopback is always {@code 127.0.0.1}; IPv6
     * loopback can render as either {@code ::1} or the long
     * {@code 0:0:0:0:0:0:0:1} form depending on JDK version and
     * socket configuration. Accept both.
     */
    private static final Set<String> LOOPBACK_ADDRESSES = Set.of(
            "127.0.0.1",
            "::1",
            "0:0:0:0:0:0:0:1"
    );

    private static final String AUTH_HEADER = "x-loadtest-auth";

    @Before
    static void checkLoadtestAuth() {
        var req = Http.Request.current();

        if (!LOOPBACK_ADDRESSES.contains(req.remoteAddress)) {
            denied();
        }

        var headerVal = req.headers.containsKey(AUTH_HEADER)
                ? req.headers.get(AUTH_HEADER).value()
                : null;
        if (headerVal == null) {
            denied();
        }

        var expected = Play.configuration.getProperty("application.secret", "");
        if (expected.isEmpty()) {
            // Should never happen in practice — require_application_secret
            // in jclaw.sh blocks startup when APPLICATION_SECRET is unset.
            // Fail closed if it ever does, rather than treat empty == empty
            // as a successful match.
            denied();
        }

        var presented = headerVal.getBytes(StandardCharsets.UTF_8);
        var canonical = expected.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(presented, canonical)) {
            denied();
        }
    }

    private static void denied() {
        response.status = 403;
        renderJSON("{\"error\":\"Forbidden\"}");
    }
}
