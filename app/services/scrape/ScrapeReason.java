package services.scrape;

/**
 * Why one corpus fetch succeeded or failed, at the granularity the escalation
 * ladder needs (JCLAW-1081).
 *
 * <p>Shared between the offline harness and — from JCLAW-1086 — the runtime
 * escalation decision in {@code web_scrape}. Two implementations would drift, and
 * the benchmark would quietly stop describing what agents experience.
 */
public enum ScrapeReason {
    /** Content retrieved and it passed the corpus entry's ground-truth assertions. */
    OK,
    /** Refused before any content: the fingerprint was rejected, not the request. */
    TLS_BLOCKED,
    /** A Cloudflare interstitial — valid HTML, no article behind it. */
    JS_CHALLENGE,
    /** Interactive challenge. Rung 3 clears one only on Cloudflare's own gate page, and only
     *  with {@code web_scrape.stealth.solve-turnstile} on. */
    TURNSTILE,
    /** The origin declares it blocks agents — a door honest identification could open. */
    POLICY_BLOCK,
    /** Scored too low to be served, without a named policy. Identification would not help. */
    TRUST_BLOCK,
    /** Refused with DataDome's device check or captcha, which scores the client and climbs as its status would. */
    DATADOME,
    /** Cloudflare Error 1009, a country ban every rung shares; a {@code web_scrape.proxy.*} egress in another country lifts it. */
    GEO_BLOCK,
    /** Cloudflare Errors 1005–1008, a ban on this host's ASN or IP every rung shares; a {@code web_scrape.proxy.*} egress elsewhere lifts it. */
    IP_BLOCK,
    /** Blocked by a non-Cloudflare WAF. Excluded from the epic's numerator and denominator. */
    OTHER_WAF,
    /** We were not blocked — the origin served a page with no server-rendered text.
     *  A rendering gap that rung 3 closes, not an anti-bot one, so it is kept distinct
     *  from {@link #TRUST_BLOCK} rather than inflating the blocked count. */
    THIN_CONTENT,
    /** We declined, the origin did not. Distinct from a block because no rung climbs
     *  it — the operator can lift it, or not. */
    ROBOTS_DISALLOWED,
    TIMEOUT,
    /** The origin answered that the page is not there. No rung climbs it — a browser
     *  renders a 404 as faithfully as a plain fetch reads one. */
    NOT_FOUND,
    /** Transport or extraction failure that is ours, not the origin's. */
    ERROR
}
