#!/usr/bin/env python3
"""Stealth rendering sidecar for jclaw — escalation rung 3 (JCLAW-1088).

Two failure modes need a real browser, and they are independent:

  THIN_CONTENT  a client-rendered page, at any protection tier including none
  JS_CHALLENGE  a browser-fingerprint gate that must execute its own JavaScript

Patchright drives the browser here rather than the JVM driving it over CDP.
That is not a preference. Patchright's patches are DRIVER-side — it avoids
issuing Runtime.enable (using isolated ExecutionContexts instead) and disables
Console.enable — so attaching a stock Playwright client over connectOverCDP
re-introduces exactly the leaks the patches remove. To get the stealth,
Patchright has to launch AND drive.

Protocol (--host defaults to 127.0.0.1; the server binds what it is given):
  GET  /health   -> 200 {status, model, patchright, channel, browser_ready}
  GET  /capability -> 200 {kind, runnable, channel, reason}
  (CLI) --probe  -> the same capability JSON on stdout, one-shot, no browser
  (CLI) --self-check [--language L] -> renders a loopback fixture with the render's own launch and
        prints {language, channel, observed, problems}; exit 1 on a problem, 2 when it cannot run
  POST /render {url, pins?, language?, timeoutMs?, settleMs?, challengeMs?, solveTurnstile?,
                waitUntil?, maxBytes?, proxy?}
        -> 200  rendered HTML; X-Upstream-Status / X-Settled-Status / X-Upstream-Url /
                X-Challenge / X-Blocked-Hosts / X-Blocked-Hosts-Count / X-Upstream-Truncated
                carry the outcome
        -> 400  {error}  malformed request
        -> 502  {error}  navigation failed
  POST /shutdown -> exits, so a restarted JVM can evict an orphan.

Every request must carry `X-Sidecar-Token: $SIDECAR_TOKEN`, the secret the JVM
derives from its own install secret; without it in the environment the sidecar
refuses to start.
`--no-auth` drops both requirements for hand-running (see README).

SSRF containment mirrors what PlaywrightBrowserTool does in-JVM (JCLAW-731),
because moving the launch out of the JVM moves the pinning with it:

  1. --host-resolver-rules MAP clauses, supplied by the JVM from its own
     SsrfGuard resolution, pin the entry host to the address the guard actually
     validated. Behind the screen (4) the browser sends names, so the screen's own
     lookup, not this pin, decides where a connection goes.
  2. Route interceptors re-check every request the page makes — redirects and
     subresources included, which the launch pin alone does not cover — and
     abort any host that resolves to a non-public address. Registered on the
     CONTEXT and paired with a WebSocket interceptor: page-level routing does not
     cover popups, service workers or ws:// at all, and a page that could open a
     socket to loopback could read it and write the reply into the DOM we return.
  3. WebRTC's UDP reaches neither interceptor, so the launch refuses UDP that does not go
     through a proxy (JCLAW-1305).
  4. Every TCP connection the browser opens goes through the render's `proxy`, which the JVM
     sets to a SOCKS5 screen of its own (JCLAW-1315): SsrfGuard checks each destination there,
     and the screen carries it through the operator's scrape proxy when one is set. It is the
     layer nothing on the page steps around — a Worker's WebSocket and TURN over TCP pass
     neither interceptor and are refused there — and nothing bypasses it: Patchright adds
     <-loopback> to the bypass list, subtracting Chromium's implicit loopback and link-local
     bypass. Layers 1-3 sit in front of it.

Both interceptors fail CLOSED. A URL whose host cannot be parsed is aborted rather
than allowed, and a scheme that is not http/https/data/blob/about is aborted too.

Guard (2) is a SECOND implementation of the JVM's IP-range check, which is a real
duplication and is treated as one: it lives in ssrf.py, stdlib-only, and
StealthBrowserTest runs that file against the same address table the Java guard is
fed. The asserted invariant is that this side is never MORE PERMISSIVE than the
JVM's — it may be stricter. Exact parity was the earlier claim and it was not true:
ipaddress admitted fec0::/10, which Java's isSiteLocalAddress() rejects.
"""

import argparse
import concurrent.futures
import hmac
import json
import os
import random
import re
import socket
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

DEFAULT_TIMEOUT_MS = 35_000
# Waiting for "networkidle" hangs on a Cloudflare challenge — one that keeps polling never
# goes idle — so the sidecar waits for domcontentloaded, then either settles for a fixed
# interval or, when a challenge stands, polls until it clears (JCLAW-1306).
DEFAULT_SETTLE_MS = 4_000
DEFAULT_CHALLENGE_MS = 30_000
# Every wait holds a render permit for its whole duration and the JVM abandons the call
# at 120s, so an unbounded caller-supplied one parks a browser nobody is waiting for.
MAX_TIMEOUT_MS = 60_000
MAX_SETTLE_MS = 15_000
MAX_CHALLENGE_MS = 45_000
# Ceiling on what this process relays for one render. The origin decides the DOM size and
# four renders can be in flight; the driver's own buffer of page.content() is out of reach.
HARD_MAX_BYTES = 25 * 1024 * 1024
DEFAULT_WAIT_UNTIL = "domcontentloaded"
# Rungs 1 and 2 both state a language; a render that did not silently answered a
# multilingual site in English while the same crawl asked for something else.
DEFAULT_LANGUAGE = "en"
DEFAULT_IDENTITY = "patchright-chromium"
# Each permit is a live headless Chromium, so this bounds memory rather than
# correctness. LocalSidecarDaemon passes a fixed argv and has no slot for this, so
# the default is what the JVM gets; --max-concurrent exists for standalone runs.
DEFAULT_MAX_CONCURRENT = 4

# Chromium's headless builds put "HeadlessChrome" in the User-Agent. Patchright
# removes the CDP artifacts a JS challenge inspects, but the UA is plain text that
# any WAF reads first — measured as 52 TRUST_BLOCKs on a corpus run, including
# origins the cheaper impersonation rung fetched without trouble. The default UA is
# read once from the browser itself and the token corrected, so the platform and
# version stay exactly what this build really is.
#
# Sec-CH-UA is NOT fixable from the route interceptor: rewriting it in
# continue_(headers=...) is accepted and then discarded, because Chromium regenerates
# browser-managed client hints after interception. Verified by sending an x-probe header
# through the same call — the probe arrives, the brand list does not change. It goes
# through Emulation.setUserAgentOverride instead, below.
from ssrf import is_allowed_proxy_host, is_public_host, is_public_ip

try:
    from patchright.sync_api import sync_playwright
    _IMPORT_ERROR = None
except Exception as exc:  # pragma: no cover - exercised only on a broken install
    sync_playwright = None
    _IMPORT_ERROR = "%s: %s" % (type(exc).__name__, exc)

_UA_LOCK = threading.Lock()
_UA = None
# A failed probe is retried, not cached forever: the probe launches a browser, so it fails
# on the same transients a render does, and a permanent cache would leave HeadlessChrome in
# every later render on the strength of one of them.
_UA_RETRY_S = 60.0
_UA_RETRY_AT = 0.0

# getaddrinfo takes no timeout and the route gate runs on the thread holding a render
# permit, so one black-holed resolver stalls the render past the JVM's 120s call timeout.
# Decisions survive across renders because a page reaching the same host on every render
# would otherwise pay the lookup again; they expire because an allow held for the process
# lifetime is a DNS-rebinding window.
_RESOLVE_TIMEOUT_S = 3.0
# Total seconds one render may spend waiting on unresolvable hosts. Route handlers run
# serially, so this — not the per-lookup deadline — is what keeps a hostile page inside
# the JVM's 120s call timeout. Past it, an unknown host is denied without waiting.
_RESOLVE_BUDGET_S = 15.0
_HOST_TTL_S = 60.0
_HOST_CACHE_MAX = 512
# A lookup past the deadline is abandoned, and getaddrinfo cannot be interrupted, so its
# thread lives until the resolver gives up. Unbounded, a black-holed resolver would leave
# one live thread per host the page names.
_RESOLVER = concurrent.futures.ThreadPoolExecutor(max_workers=8,
                                                  thread_name_prefix="stealth-resolve")
_HOST_LOCK = threading.Lock()
_HOST_DECISIONS = {}

# Launch the FULL Chromium, never the headless shell. Recent Playwright defaults
# headless=True to chromium-headless-shell, a stripped build, and that — not
# Chromium-versus-Chrome — was what made rung 3 look automated. Measured against a
# headful Chrome on the same probe, the headless shell differs on eight signals that
# the full build matches exactly: navigator.plugins (0 vs 5), mimeTypes (0 vs 2),
# window.chrome (undefined vs an object with loadTimes), WebGL (SwiftShader vs the
# real GPU), languages, Notification.permission, pdfViewerEnabled.
#
# The full build ships WITH Patchright, so this needs no system browser and behaves
# identically on a headless Linux server. Proprietary codecs (H.264, AAC, MP3) and
# Widevine are present in it too, checked rather than assumed.
_CHANNEL = "chromium"
# Falling back to it silently would leave /health and /capability reporting a browser the
# sweep never used, so the channel each render actually launched is published.
_FALLBACK_CHANNEL = "chromium-headless-shell"
_CHANNEL_LOCK = threading.Lock()
_LAST_CHANNEL = _CHANNEL

# The User-Agent is a launch flag, and the brands stay the build's own. A page-level CDP override
# never reaches a cross-site iframe — Turnstile's — which went on saying HeadlessChrome, and
# --disable-site-isolation-trials, which spread it, is itself failed by Cloudflare (measured).

# One story on every surface a challenge compares: frames, Workers, headers (JCLAW-1305, README).
_SCREEN = {"width": 1920, "height": 1080}
_CONTEXT_OPTIONS = {"service_workers": "block", "viewport": _SCREEN, "screen": _SCREEN}
_FINGERPRINT_ARGS = [
    # Headless can report no fine pointer and no hover on a host with no pointing device.
    "--blink-settings=primaryHoverType=2,availableHoverTypes=2,primaryPointerType=4,"
    "availablePointerTypes=4",
    # Neither route gate sees UDP. Each build honors one spelling and silently ignores the
    # other — the full Chromium this one, the headless-shell fallback the force one (measured).
    "--webrtc-ip-handling-policy=disable_non_proxied_udp",
    "--force-webrtc-ip-handling-policy=disable_non_proxied_udp",
]


def _accept_languages(language):
    """The --accept-lang list for a request's language: the tag, then its primary subtag. Given
    fr-FR alone, Chromium's header adds fr and navigator.languages does not (measured)."""
    primary = language.split("-")[0]
    return [language] if primary == language else [language, primary]


def _launch_args(pins, language, user_agent=None):
    """Every flag a render launches with. Language is a flag rather than the context locale,
    which Playwright applies to the main thread only: a Worker kept the host's languages."""
    args = ["--lang=" + language, "--accept-lang=" + ",".join(_accept_languages(language))]
    args += _FINGERPRINT_ARGS
    if user_agent:
        args.append("--user-agent=" + user_agent)
    if pins:
        args.append("--host-resolver-rules="
                    + ",".join("MAP %s %s" % (h, ip) for h, ip in pins.items()))
    return args


def _disguise(context, page, language):
    """The override no launch flag makes, sent before `page` navigates; its dedicated Workers
    inherit it (measured)."""
    # Chromium on macOS takes Intl from the OS and ignores --lang (measured).
    context.new_cdp_session(page).send("Emulation.setLocaleOverride", {"locale": language})


_SURFACES_JS = """() => {
  const d = navigator.userAgentData;
  return {userAgent: navigator.userAgent, brands: d ? d.brands.map(b => b.brand) : [],
          language: navigator.language, languages: [...navigator.languages],
          intl: Intl.DateTimeFormat().resolvedOptions().locale};
}"""

# Reports from the page's own world: Patchright's evaluate runs in an isolated one, which would miss
# a script override on navigator. The RTC half is JCLAW-1286's probe, aimed at the check's socket.
_FIXTURE_PAGE = r"""<!doctype html><script>
const surfaces = %(surfaces)s;
const stun = new RTCPeerConnection({iceServers: [{urls: 'stun:127.0.0.1:%(udp)d'}]});
stun.createDataChannel('probe');
stun.createOffer().then(o => stun.setLocalDescription(o)).catch(() => {});
const direct = new RTCPeerConnection();
direct.createDataChannel('probe');
const answer = ['v=0', 'o=- 1 1 IN IP4 127.0.0.1', 's=-', 't=0 0', 'a=group:BUNDLE 0',
  'm=application 9 UDP/DTLS/SCTP webrtc-datachannel', 'c=IN IP4 0.0.0.0', 'a=mid:0',
  'a=sctp-port:5000', 'a=ice-ufrag:probe', 'a=ice-pwd:probeprobeprobeprobe', 'a=setup:active',
  'a=fingerprint:sha-256 ' + Array(32).fill('00').join(':'),
  'a=candidate:1 1 udp 2113937151 127.0.0.1 %(udp)d typ host', ''].join('\r\n');
direct.createOffer().then(o => direct.setLocalDescription(o))
  .then(() => direct.setRemoteDescription({type: 'answer', sdp: answer})).catch(() => {});
(async () => {
  const report = {};
  try {
    report.main = Object.assign(surfaces(), {
      screen: [screen.width, screen.height], viewport: [innerWidth, innerHeight],
      pointerFine: matchMedia('(pointer: fine)').matches,
      hover: matchMedia('(hover: hover)').matches,
      navigatorOverrides: Object.getOwnPropertyNames(navigator).concat(
        Object.entries(Object.getOwnPropertyDescriptors(Navigator.prototype))
          .filter(([, d]) => d.get && !Function.prototype.toString.call(d.get).includes('[native code]'))
          .map(([name]) => name))});
    await fetch('/echo/main');
    report.worker = await new Promise((resolve, reject) => {
      const worker = new Worker('/worker.js');
      worker.onmessage = e => resolve(e.data);
      worker.onerror = e => reject(new Error('worker: ' + e.message));
    });
    const framed = await new Promise((resolve, reject) => {
      addEventListener('message', e => resolve(e.data));
      setTimeout(() => reject(new Error('the cross-site iframe never answered')), 5000);
      const frame = document.createElement('iframe');
      frame.src = 'http://localhost:' + location.port + '/frame';
      document.documentElement.appendChild(frame);
    });
    if (framed.error) throw new Error('iframe ' + framed.error);
    report.frame = framed.frame;
    report.frameWorker = framed.worker;
  } catch (e) {
    report.error = String(e);
  }
  await fetch('/report', {method: 'POST', body: JSON.stringify(report)});
})();
</script>"""

_FIXTURE_FRAME = """<!doctype html><script>
const surfaces = %(surfaces)s;
const worker = new Worker('/frame-worker.js');
worker.onmessage = e => parent.postMessage({frame: surfaces(), worker: e.data}, '*');
worker.onerror = e => parent.postMessage({error: 'worker: ' + e.message}, '*');
</script>"""

_FIXTURE_WORKER = """const surfaces = %(surfaces)s;
fetch('%(echo)s').finally(() => postMessage(surfaces()));
"""

# Every request the fixture must see, and the ones Chromium sends Sec-CH-UA on: none from a
# dedicated Worker, with or without the UA override (measured).
_FIXTURE_REQUESTS = ("/", "/echo/main", "/worker.js", "/echo/worker",
                     "/frame", "/frame-worker.js", "/echo/frame-worker")
_FIXTURE_CLIENT_HINTED = ("/", "/echo/main", "/frame")
_FIXTURE_THREADS = (("worker", "the Worker"), ("frame", "a cross-site iframe"),
                    ("frameWorker", "the iframe's Worker"))
_CROSS_SITE_THREADS = ("frame", "frameWorker")
# Without the WebRTC flags the first datagram arrived ~100 ms after load (measured).
_WEBRTC_WINDOW_S = 2.0


def fingerprint_problems(observed, language, platform=sys.platform):
    """Each way the self-check's observations disagree with one Chrome in `language`; empty
    when the page, its iframe, their Workers and every request header tell the same story."""
    main = observed.get("main")
    silent = [label for key, label in (("main", "the page"),) + _FIXTURE_THREADS
              if not observed.get(key)]
    if silent:
        return ["the fixture heard nothing from %s: %s"
                % (", ".join(silent), observed.get("error"))]
    problems = []
    for key, label in _FIXTURE_THREADS:
        for field in ("userAgent", "brands", "language", "languages", "intl"):
            # macOS gives an isolated iframe the OS's Intl; the one override that reaches it
            # splits navigator.languages from the Workers' instead (README, measured).
            if field == "intl" and key in _CROSS_SITE_THREADS and platform == "darwin":
                continue
            if observed[key][field] != main[field]:
                problems.append("%s's %s %r differs from the page's %r"
                                % (label, field, observed[key][field], main[field]))
    for field, label in (("language", "navigator.language"), ("intl", "Intl")):
        if main[field].lower() != language.lower():
            problems.append("%s is %r, not %r" % (label, main[field], language))
    if "HeadlessChrome" in main["userAgent"] or "HeadlessChrome" in main["brands"]:
        problems.append("the User-Agent says HeadlessChrome: %r %r"
                        % (main["userAgent"], main["brands"]))
    for path in _FIXTURE_REQUESTS:
        sent = observed["headers"].get(path)
        if sent is None:
            problems.append("no request for %s reached the fixture" % path)
            continue
        if sent["user-agent"] != main["userAgent"]:
            problems.append("%s went out as User-Agent %r" % (path, sent["user-agent"]))
        tags = [t.split(";")[0].strip() for t in (sent["accept-language"] or "").split(",")]
        if tags != main["languages"]:
            problems.append("%s went out as Accept-Language %r" % (path, sent["accept-language"]))
        if path in _FIXTURE_CLIENT_HINTED and "HeadlessChrome" in (sent["sec-ch-ua"] or "HeadlessChrome"):
            problems.append("%s went out as Sec-CH-UA %r" % (path, sent["sec-ch-ua"]))
    size = [_SCREEN["width"], _SCREEN["height"]]
    if main["screen"] != size or main["viewport"] != size:
        problems.append("screen %r and viewport %r are not %r"
                        % (main["screen"], main["viewport"], size))
    if not (main["pointerFine"] and main["hover"]):
        problems.append("pointer: fine is %s and hover: hover is %s"
                        % (main["pointerFine"], main["hover"]))
    if main["navigatorOverrides"]:
        problems.append("a script overrode navigator: %r" % main["navigatorOverrides"])
    if observed["webrtcUdp"]:
        problems.append("WebRTC sent UDP, which no route gate sees")
    return problems


def _header_safe(value):
    """Origin-supplied text, made safe to put in an HTTP header value. send_header
    encodes latin-1 and raises on anything else, taking the whole response with it."""
    return str(value).encode("ascii", "backslashreplace").decode("ascii")


# BlockClassifier's two markers (JCLAW-1304), neither of them page text, so a localized challenge
# reads as the English one does. ScrapeSidecarContractTest holds the two sides in step.
_CF_CHALLENGE_OPTIONS = re.compile(r"_cf_chl_opt", re.IGNORECASE)
_CF_CHALLENGE_TYPE = re.compile(r"ctype\s*:\s*['\"]([a-z-]+)['\"]", re.IGNORECASE)
_CLICKABLE = ("managed", "interactive")
_CHALLENGE_FRAME = "https://challenges.cloudflare.com/cdn-cgi/challenge-platform/"
_MAX_CLICKS = 3
_CLICK_INTERVAL_S = 8.0
_POLL_MS = 500
# Where Turnstile draws its checkbox inside its frame; Scrapling v0.4.15 clicks 26-28 by 25-27.
_CHECKBOX_OFFSET = (27, 26)
CLEARED, UNSOLVED = "cleared", "unsolved"
# page.content() raises while a clearing challenge reloads the page.
_NAVIGATING = "navigating"


def challenge_type(html, mitigated=None):
    """The Cloudflare challenge `html` stands on: the cType its inline options name, "unknown" when
    only the navigation's cf-mitigated header says there is one, or None."""
    options = _CF_CHALLENGE_OPTIONS.search(html)
    if options:
        named = _CF_CHALLENGE_TYPE.search(html, options.end())
        if named:
            return named.group(1).lower()
    if (mitigated or "").strip().lower() == "challenge":
        return "unknown"
    return None


def _click_point(box, rng):
    """A point on Turnstile's checkbox that stays inside the frame's `box`, or None for a box too
    small to hold one."""
    if not box or box["width"] < 2 or box["height"] < 2:
        return None
    dx = min(_CHECKBOX_OFFSET[0] + rng.randint(-1, 1), box["width"] - 1)
    dy = min(_CHECKBOX_OFFSET[1] + rng.randint(-1, 1), box["height"] - 1)
    return box["x"] + dx, box["y"] + dy


def _challenge_box(page):
    """The box of a visible challenge-platform frame, or None. Chosen by URL, so no other frame
    is ever clicked."""
    for frame in page.frames:
        if not frame.url.startswith(_CHALLENGE_FRAME):
            continue
        try:
            element = frame.frame_element()
            if element.is_visible():
                return element.bounding_box()
        except Exception:
            continue  # detached between the listing and the read
    return None


def await_challenge(page, standing, budget_ms, solve, clock=time.monotonic, rng=random):
    """Poll `standing` — the challenge still up, or None — until it clears or `budget_ms` runs out.
    With `solve`, a managed or interactive challenge's checkbox is clicked at most _MAX_CLICKS
    times, _CLICK_INTERVAL_S apart. Returns (CLEARED or UNSOLVED, clicks)."""
    deadline = clock() + budget_ms / 1000.0
    clicks, next_click = 0, clock()
    while True:
        kind = standing()
        if kind is None:
            return CLEARED, clicks
        now = clock()
        if now >= deadline:
            return UNSOLVED, clicks
        if solve and kind in _CLICKABLE and clicks < _MAX_CLICKS and now >= next_click:
            point = _click_point(_challenge_box(page), rng)
            if point:
                page.mouse.click(point[0], point[1], delay=rng.randint(80, 160))
                clicks += 1
                next_click = clock() + _CLICK_INTERVAL_S
        page.wait_for_timeout(max(1, min(_POLL_MS, (deadline - clock()) * 1000)))


def _settle(page, navigation, settle_ms, challenge_ms, solve, clock=time.monotonic, rng=random):
    """Wait out the settle window, or a standing challenge in its place. Returns the X-Challenge
    report, or None when no challenge stood."""
    def standing():
        try:
            return challenge_type(page.content(), navigation["mitigated"])
        except Exception:
            return _NAVIGATING

    kind = standing()
    if kind in (None, _NAVIGATING):
        if settle_ms > 0:
            page.wait_for_timeout(settle_ms)
        return None
    started = clock()
    outcome, clicks = await_challenge(page, standing, challenge_ms, solve, clock, rng)
    if outcome == CLEARED:
        # The page behind the challenge has no marker while it is still downloading either, so it is
        # parsed before it settles, both inside what is left of the same budget.
        _await_parsed(page, challenge_ms - (clock() - started) * 1000)
        left_ms = challenge_ms - (clock() - started) * 1000
        if settle_ms > 0 and left_ms > 0:
            page.wait_for_timeout(min(settle_ms, left_ms))
    return "%s; %s%s" % (kind, outcome, "; clicks=%d" % clicks if clicks else "")


def _await_parsed(page, budget_ms):
    """Wait up to `budget_ms` for the current document's DOMContentLoaded; one that has not reached it
    by then is captured as it is."""
    if budget_ms <= 0:
        return
    try:
        page.wait_for_load_state("domcontentloaded", timeout=budget_ms)
    except Exception:
        pass


class _ResolveBudget:
    """Per-render ceiling on time lost to hosts that never resolve."""

    def __init__(self, seconds):
        self.left = seconds

    def spent(self):
        return self.left <= 0

    def charge(self, seconds):
        self.left -= seconds


def _host_allowed(host, budget=None):
    """is_public_host, bounded by a deadline and cached. Fails closed: a lookup that does
    not answer inside the budget is denied.

    A deadline miss is NOT cached, because it is an answer the resolver never gave and
    caching it would block a legitimate CDN for the whole TTL on one slow lookup. The
    per-render `budget` is what bounds the cost instead: Playwright runs route handlers
    serially on the render thread, so without it a page naming forty dead hosts pays the
    per-lookup deadline forty times over and blows the JVM's call timeout — the outcome
    the deadline was added to prevent.
    """
    now = time.monotonic()
    with _HOST_LOCK:
        cached = _HOST_DECISIONS.get(host)
        if cached and cached[1] > now:
            return cached[0]
    if budget is not None and budget.spent():
        return False
    pending = _RESOLVER.submit(is_public_host, host)
    try:
        allowed = pending.result(_RESOLVE_TIMEOUT_S)
    except concurrent.futures.TimeoutError:
        pending.cancel()  # drops it if it never started; a started one runs to the end
        if budget is not None:
            budget.charge(_RESOLVE_TIMEOUT_S)
        return False
    except Exception:
        # An error is_public_host does not catch escaped into the route handler before,
        # leaving the request neither continued nor aborted until the render timed out.
        return False
    with _HOST_LOCK:
        if len(_HOST_DECISIONS) >= _HOST_CACHE_MAX:
            _HOST_DECISIONS.clear()  # a rendered page can name unlimited hosts
        _HOST_DECISIONS[host] = (allowed, now + _HOST_TTL_S)
    return allowed


class _RenderScope:
    """What one render's gates report and spend."""

    def __init__(self):
        self.blocked = set()
        self.budget = _ResolveBudget(_RESOLVE_BUDGET_S)


def _install_gates(context, pins, scope):
    """The route and WebSocket gates, on the CONTEXT: a popup the page opens is a separate Page with
    no page-level handler, and service workers issue requests a page handler never sees at all.
    `scope()` is the render in progress."""

    def gate(route):
        # Pinned hosts are already guard-validated; anything else the page
        # reaches for gets resolved and range-checked before it is allowed.
        render = scope()
        parts = urlsplit(route.request.url)
        scheme = parts.scheme.lower()
        if scheme in ("http", "https"):
            # urlsplit, not string slicing: the hand-rolled split produced "[2606"
            # for an IPv6 literal and "" for anything it could not parse, and an
            # empty host then skipped the check entirely.
            host = parts.hostname
            if not host:
                render.blocked.add(route.request.url[:80])
                route.abort()
                return
            if host not in pins and not _host_allowed(host, render.budget):
                render.blocked.add(host)
                route.abort()
                return
        elif scheme not in ("data", "blob", "about"):
            # data/blob/about reach no network and a page legitimately uses them.
            # Everything else -- file:, ftp:, chrome-extension: -- has no business
            # being fetched by a rendered page, and defaulting them to "allow" is
            # the wrong way round for a security gate.
            render.blocked.add(scheme + ":")
            route.abort()
            return
        route.continue_()

    def ws_gate(ws):
        # page.route never sees WebSocket traffic -- it is a separate API -- so
        # until this existed a page could open ws://127.0.0.1, read a loopback
        # service and write the reply into the DOM we hand back.
        render = scope()
        host = urlsplit(ws.url).hostname
        if not host or (host not in pins and not _host_allowed(host, render.budget)):
            render.blocked.add(host or ws.url[:80])
            return
        ws.connect_to_server()

    context.route("**/*", gate)
    context.route_web_socket("**/*", ws_gate)


def _navigate(context, page, url, timeout_ms, settle_ms, challenge_ms, solve, wait_until, language):
    """Load `url` in `page` and wait out the settle window or a standing challenge."""
    settled = {"status": 0, "mitigated": None}

    def track(resp):
        # goto's status is the FIRST navigation response, captured before
        # the settle window; an interstitial that resolves itself navigates
        # again inside it, and this records where that landed.
        if resp.request.is_navigation_request() and resp.frame == page.main_frame:
            settled["status"] = resp.status
            settled["mitigated"] = resp.headers.get("cf-mitigated")

    page.on("response", track)
    _disguise(context, page, language)
    response = page.goto(url, wait_until=wait_until, timeout=timeout_ms)
    challenge = _settle(page, settled, settle_ms, challenge_ms, solve)
    return {"html": page.content(),
            "status": response.status if response else 0,
            "settledStatus": settled["status"],
            "mitigated": settled["mitigated"],
            "url": page.url,
            "challenge": challenge}


def _unsafe_pin(pins):
    """The first pinned host whose address is not public, or None. A pin exempts its host from the
    route gate, so an unvalidated one is a way around the gate rather than an input to it."""
    for pinned_host, pinned_ip in pins.items():
        if not is_public_ip(pinned_ip):
            return pinned_host
    return None


def _active_channel():
    with _CHANNEL_LOCK:
        return _LAST_CHANNEL


def _launch_proxy(proxy):
    """Playwright launch settings for the render's proxy — the JVM's network screen (JCLAW-1315) — or None.

    Checked on the provider rule, since a proxy may sit on loopback or the LAN; the route gate
    still range-checks every host the page reaches either way. Raises ValueError naming the fault.
    """
    if not proxy:
        return None
    parts = urlsplit(proxy.get("url") or "")
    if parts.scheme not in ("http", "socks5") or not parts.hostname or not parts.port:
        raise ValueError("proxy url must be http:// or socks5:// with a host and port")
    if not is_allowed_proxy_host(parts.hostname):
        raise ValueError("proxy host is link-local, multicast or unresolvable")
    host = "[%s]" % parts.hostname if ":" in parts.hostname else parts.hostname
    # Patchright adds this only while PLAYWRIGHT_DISABLE_FORCED_CHROMIUM_PROXIED_LOOPBACK is unset;
    # without it Chromium dials loopback and link-local directly, around the JVM's screen.
    settings = {"server": "%s://%s:%d" % (parts.scheme, host, parts.port), "bypass": "<-loopback>"}
    if proxy.get("username"):
        settings["username"] = proxy["username"]
        settings["password"] = proxy.get("password") or ""
    return settings


def _launch(p, args, proxy=None):
    """Launch the full Chromium, falling back to the headless shell for THIS render only.

    The fallback is per-render because a launch failure is not proof the full build is
    absent: a timeout, ENOMEM, a locked profile or fd exhaustion all raise the same way,
    and all are reachable with four renders launching at once. Latching the stripped build
    for the process lifetime would silently downgrade the fingerprint this rung exists for.
    """
    global _LAST_CHANNEL
    channel = _CHANNEL
    try:
        browser = p.chromium.launch(headless=True, channel=channel, args=args, proxy=proxy)
    except Exception as exc:
        sys.stderr.write("[stealth-sidecar] full Chromium launch failed (%s: %s) — this "
                         "render uses the headless shell; if the build is missing, run "
                         "'patchright install chromium'\n" % (type(exc).__name__, exc))
        channel = _FALLBACK_CHANNEL
        browser = p.chromium.launch(headless=True, channel=channel, args=args, proxy=proxy)
    with _CHANNEL_LOCK:
        _LAST_CHANNEL = channel
    return browser


def _user_agent(p, proxy=None):
    """This build's own User-Agent without its HeadlessChrome token, probed once per process, or
    None while a failed probe waits out _UA_RETRY_S — the render then goes ahead as it is."""
    global _UA, _UA_RETRY_AT
    with _UA_LOCK:
        if _UA is not None or time.monotonic() < _UA_RETRY_AT:
            return _UA
        try:
            browser = _launch(p, [], proxy)
            try:
                _UA = browser.new_page().evaluate("navigator.userAgent").replace(
                    "HeadlessChrome/", "Chrome/")
            finally:
                browser.close()
        except Exception as exc:
            sys.stderr.write("[stealth-sidecar] UA probe failed (%s: %s) — rendering with the "
                             "browser's own User-Agent\n" % (type(exc).__name__, exc))
            _UA_RETRY_AT = time.monotonic() + _UA_RETRY_S
        return _UA


class SidecarState:
    def __init__(self, identity, idle_timeout_s, max_concurrent):
        self.identity = identity
        self.idle_timeout_s = idle_timeout_s
        self.last_used = time.time()
        # A browser is launched per render because the DNS pin is a launch argument
        # and cannot be varied on a shared instance. Patchright's sync API is not
        # thread-safe across threads, but one sync_playwright() context per thread is
        # fine (verified: three concurrent renders complete in ~1.2s), so renders run
        # in parallel behind a bound rather than a mutex — serializing them would make
        # a 150-page corpus run take twenty minutes. The bound is memory, not safety:
        # each permit is a live headless Chromium.
        self.render_slots = threading.Semaphore(max_concurrent)

    def touch(self):
        self.last_used = time.time()


def capability():
    channel = _active_channel()
    if sync_playwright is None:
        return {"kind": "render", "runnable": False, "channel": channel,
                "reason": "patchright unavailable (%s)" % _IMPORT_ERROR}
    return {"kind": "render", "runnable": True, "channel": channel, "reason": ""}


# Not a CORS-simple header, so a page the operator visits cannot forge a request this
# sidecar honours: the browser would have to preflight, and this server answers no OPTIONS.
AUTH_HEADER = "X-Sidecar-Token"


def _require_token(ap):
    """The secret the JVM hands the child in its environment. Missing means fail closed —
    an unauthenticated loopback sidecar is reachable by every local process."""
    token = os.environ.get("SIDECAR_TOKEN", "")
    if not token:
        ap.error("SIDECAR_TOKEN is unset — pass --no-auth to run this sidecar unauthenticated")
    return token


class Handler(BaseHTTPRequestHandler):
    state: SidecarState = None  # injected in main()
    token = None  # shared secret; None only under --no-auth

    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("[stealth-sidecar] %s\n" % (fmt % args))

    def _send_json(self, code, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_json(self):
        # read(-1) reads to EOF, so a negative Content-Length would park the handler
        # thread for as long as the caller keeps the socket open.
        length = max(0, int(self.headers.get("Content-Length", "0")))
        raw = self.rfile.read(length) if length else b"{}"
        return json.loads(raw.decode("utf-8"))

    def handle(self):
        try:
            super().handle()
        except BrokenPipeError:
            pass

    def _authorized(self):
        if self.token is None:
            return True
        if hmac.compare_digest(self.headers.get(AUTH_HEADER, "").encode("utf-8", "replace"),
                               self.token.encode("utf-8")):
            return True
        self._send_json(401, {"error": "missing or invalid %s" % AUTH_HEADER})
        return False

    def do_GET(self):
        if not self._authorized():
            return
        if self.path == "/health":
            self.state.touch()
            self._send_json(200, {
                "status": "ok",
                "model": self.state.identity,
                "patchright": sync_playwright is not None,
                "channel": _active_channel(),
                "browser_ready": capability()["runnable"],
            })
        elif self.path == "/capability":
            self._send_json(200, capability())
        else:
            self._send_json(404, {"error": "unknown path %s" % self.path})

    def do_POST(self):
        if not self._authorized():
            return
        if self.path == "/shutdown":
            sys.stderr.write("[stealth-sidecar] shutdown requested — exiting\n")
            self._send_json(200, {"status": "bye"})
            threading.Thread(target=lambda: (time.sleep(0.2), os._exit(0)), daemon=True).start()
            return
        if self.path == "/render":
            self._handle_render()
            return
        self._send_json(404, {"error": "unknown path %s" % self.path})

    def _handle_render(self):
        self.state.touch()
        if sync_playwright is None:
            self._send_json(502, {"error": "patchright unavailable (%s)" % _IMPORT_ERROR})
            return
        # Every coercion of the body belongs inside this try: an escape here reaches no
        # handler at all, so the caller gets a closed connection where a 400 is promised.
        try:
            req = self._read_json()
            if not isinstance(req, dict):
                raise TypeError("body must be a JSON object")
            url = req.get("url")
            if url is not None and not isinstance(url, str):
                raise TypeError("url must be a string")
            pins = req.get("pins") or {}
            if not isinstance(pins, dict):
                raise TypeError("pins must be a JSON object")
            proxy = req.get("proxy")
            if proxy is not None and not isinstance(proxy, dict):
                raise TypeError("proxy must be a JSON object")
            timeout_ms = max(1_000, min(int(req.get("timeoutMs") or DEFAULT_TIMEOUT_MS),
                                        MAX_TIMEOUT_MS))
            settle_ms = max(0, min(int(req.get("settleMs") or DEFAULT_SETTLE_MS),
                                   MAX_SETTLE_MS))
            challenge_ms = max(0, min(int(req.get("challengeMs") or DEFAULT_CHALLENGE_MS),
                                      MAX_CHALLENGE_MS))
            solve = req.get("solveTurnstile", False)
            if not isinstance(solve, bool):
                raise TypeError("solveTurnstile must be true or false")
            # `or HARD_MAX_BYTES` here would read an explicit 0 as "no cap", which is the
            # opposite of what the same field means to the fetch sidecar the JVM hands
            # the identical value to.
            requested_bytes = req.get("maxBytes")
            max_bytes = HARD_MAX_BYTES if requested_bytes is None else int(requested_bytes)
            wait_until = req.get("waitUntil") or DEFAULT_WAIT_UNTIL
            language = req.get("language") or DEFAULT_LANGUAGE
        except Exception as exc:
            self._send_json(400, {"error": "malformed request: %s" % exc})
            return

        if not url:
            self._send_json(400, {"error": "url is required"})
            return
        if max_bytes < 0:
            # Rejected, not clamped: body[:max_bytes] cuts from the END, so a negative
            # cap returns a document missing its tail under a 200.
            self._send_json(400, {"error": "maxBytes must not be negative"})
            return
        max_bytes = min(max_bytes, HARD_MAX_BYTES)

        # The token proves the caller holds the sidecar's secret, not that SsrfGuard approved
        # the pin — and --no-auth drops even that — so pins are re-checked rather than trusted.
        unsafe = _unsafe_pin(pins)
        if unsafe is not None:
            self._send_json(400, {"error": "pin for %s is not a public address" % unsafe})
            return

        try:
            launch_proxy = _launch_proxy(proxy)
        except ValueError as exc:
            self._send_json(400, {"error": str(exc)})
            return

        with self.state.render_slots:
            try:
                result = self._render(url, pins, timeout_ms, settle_ms, challenge_ms, solve,
                                      wait_until, language, launch_proxy)
            except Exception as exc:
                self._send_json(502, {"error": "%s: %s" % (type(exc).__name__, exc)})
                return
        self._send_rendered(url, result, max_bytes)

    def _send_rendered(self, url, result, max_bytes):
        # Assembled before the first write, so a failure here is still answerable as 502.
        try:
            body = result["html"].encode("utf-8", "replace")
            truncated = len(body) > max_bytes
            if truncated:
                # Re-decoding the slice drops a character the cut fell inside, rather
                # than emitting a lone continuation byte.
                body = body[:max_bytes].decode("utf-8", "ignore").encode("utf-8")
            headers = [
                ("Content-Type", "text/html; charset=utf-8"),
                ("X-Upstream-Status", str(result["status"])),
                # Where the settle window ENDED, which differs from X-Upstream-Status
                # when a challenge answered 4xx and then resolved itself client-side.
                ("X-Settled-Status", str(result["settledStatus"])),
                # Both values are origin-influenced — the final URL comes from the page, and
                # the blocked set from URLs it chose to request — and send_header raises
                # on anything outside latin-1, which would drop the whole response.
                ("X-Upstream-Url", _header_safe(result["url"])),
            ]
            if result.get("mitigated"):
                headers.append(("X-Upstream-cf-mitigated", _header_safe(result["mitigated"])))
            if result["challenge"]:
                headers.append(("X-Challenge", result["challenge"]))
            blocked = result["blocked"]
            if blocked:
                # The list is clipped, so the count is the only way to tell twenty
                # blocked hosts from two hundred.
                headers.append(("X-Blocked-Hosts",
                                _header_safe(",".join(sorted(blocked)[:20]))))
                headers.append(("X-Blocked-Hosts-Count", str(len(blocked))))
            if truncated:
                headers.append(("X-Upstream-Truncated", "true"))
            headers.append(("Content-Length", str(len(body))))
        except Exception as exc:
            sys.stderr.write("[stealth-sidecar] response assembly failed for %s: %s\n"
                             % (url, exc))
            self._send_json(502, {"error": "%s: %s" % (type(exc).__name__, exc)})
            return

        self.send_response(200)
        for name, value in headers:
            self.send_header(name, value)
        self.end_headers()
        self.wfile.write(body)

    def _render(self, url, pins, timeout_ms, settle_ms, challenge_ms, solve, wait_until, language,
                proxy=None):
        scope = _RenderScope()
        with sync_playwright() as p:
            browser = _launch(p, _launch_args(pins, language, _user_agent(p, proxy)), proxy)
            try:
                context = browser.new_context(**_CONTEXT_OPTIONS)
                _install_gates(context, pins, lambda: scope)
                result = _navigate(context, context.new_page(), url, timeout_ms, settle_ms,
                                   challenge_ms, solve, wait_until, language)
                result["blocked"] = scope.blocked
                return result
            finally:
                browser.close()


def self_check(language):
    """What the page, a cross-site iframe, their dedicated Workers and each request header said
    under a render's own launch, context and overrides. Loopback rather than a fulfilled route:
    Chromium adds Accept-Language below the interception point, where a route handler never sees
    it (measured). The iframe is on localhost, a different site from the page's 127.0.0.1."""
    seen, report, reported = {}, {}, threading.Event()
    udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    udp.bind(("127.0.0.1", 0))
    def worker_js(echo):
        return "text/javascript", _FIXTURE_WORKER % {"surfaces": _SURFACES_JS, "echo": echo}

    documents = {
        "/": ("text/html", _FIXTURE_PAGE % {"surfaces": _SURFACES_JS, "udp": udp.getsockname()[1]}),
        "/worker.js": worker_js("/echo/worker"),
        "/frame": ("text/html", _FIXTURE_FRAME % {"surfaces": _SURFACES_JS}),
        "/frame-worker.js": worker_js("/echo/frame-worker"),
    }

    class Fixture(BaseHTTPRequestHandler):
        def log_message(self, fmt, *args):
            pass

        def _reply(self, content_type, text):
            body = text.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            seen[self.path] = {name.lower(): self.headers.get(name)
                               for name in ("User-Agent", "Accept-Language", "Sec-CH-UA")}
            self._reply(*documents.get(self.path, ("text/plain", "ok")))

        def do_POST(self):
            report.update(json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0")))))
            self._reply("text/plain", "ok")
            reported.set()

    server = ThreadingHTTPServer(("127.0.0.1", 0), Fixture)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        with sync_playwright() as p:
            browser = _launch(p, _launch_args({}, language, _user_agent(p)))
            try:
                context = browser.new_context(**_CONTEXT_OPTIONS)
                page = context.new_page()
                _disguise(context, page, language)
                page.goto("http://127.0.0.1:%d/" % server.server_address[1],
                          wait_until="domcontentloaded", timeout=15000)
                reported.wait(15)
                udp.settimeout(_WEBRTC_WINDOW_S)
                try:
                    udp.recv(2048)
                    leaked = True
                except socket.timeout:
                    leaked = False
            finally:
                browser.close()
    finally:
        server.shutdown()
        udp.close()
    observed = dict(report, headers=seen, webrtcUdp=leaked)
    return {"language": language, "channel": _active_channel(), "observed": observed,
            "problems": fingerprint_problems(observed, language)}


def _idle_watcher(state):
    if state.idle_timeout_s <= 0:
        return
    while True:
        time.sleep(30)
        if time.time() - state.last_used > state.idle_timeout_s:
            sys.stderr.write("[stealth-sidecar] idle — exiting\n")
            os._exit(0)


def main():
    ap = argparse.ArgumentParser(description="jclaw stealth rendering sidecar")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int)
    ap.add_argument("--model", default=DEFAULT_IDENTITY)
    ap.add_argument("--cache-dir", default=os.path.join("data", "stealth-sidecar"))
    ap.add_argument("--idle-timeout-min", type=float, default=15.0)
    ap.add_argument("--max-concurrent", type=int, default=DEFAULT_MAX_CONCURRENT,
                    help="live headless browsers allowed at once")
    ap.add_argument("--no-auth", action="store_true",
                    help="serve unauthenticated — for hand-running this sidecar without the JVM")
    ap.add_argument("--probe", action="store_true",
                    help="print capability JSON and exit without launching a browser")
    ap.add_argument("--self-check", action="store_true",
                    help="render a loopback fixture and report whether every surface agrees")
    ap.add_argument("--language", default=DEFAULT_LANGUAGE,
                    help="the language --self-check renders in")
    args = ap.parse_args()

    if args.probe:
        print(json.dumps(capability()))
        return
    if args.self_check:
        try:
            if sync_playwright is None:
                raise RuntimeError("patchright unavailable (%s)" % _IMPORT_ERROR)
            result = self_check(args.language)
        except Exception as exc:
            print(json.dumps({"error": "%s: %s" % (type(exc).__name__, exc)}))
            sys.exit(2)
        print(json.dumps(result))
        sys.exit(1 if result["problems"] else 0)
    if args.port is None:
        ap.error("--port is required unless --probe or --self-check is given")

    os.makedirs(os.path.abspath(args.cache_dir), exist_ok=True)
    Handler.token = None if args.no_auth else _require_token(ap)
    Handler.state = SidecarState(args.model, args.idle_timeout_min * 60.0, args.max_concurrent)
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    threading.Thread(target=_idle_watcher, args=(Handler.state,), daemon=True).start()
    sys.stderr.write("[stealth-sidecar] listening on http://%s:%d\n" % (args.host, args.port))
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
