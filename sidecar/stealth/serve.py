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
  POST /render {url, pins?, language?, timeoutMs?, settleMs?, waitUntil?, maxBytes?, proxy?}
        -> 200  rendered HTML; X-Upstream-Status / X-Settled-Status / X-Upstream-Url /
                X-Blocked-Hosts / X-Blocked-Hosts-Count / X-Upstream-Truncated carry
                the outcome
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
     validated. That closes the DNS-rebinding window between the guard's
     lookup and the browser's.
  2. Route interceptors re-check every request the page makes — redirects and
     subresources included, which the launch pin alone does not cover — and
     abort any host that resolves to a non-public address. Registered on the
     CONTEXT and paired with a WebSocket interceptor: page-level routing does not
     cover popups, service workers or ws:// at all, and a page that could open a
     socket to loopback could read it and write the reply into the DOM we return.

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
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

DEFAULT_TIMEOUT_MS = 35_000
# Cloudflare's interstitial resolves itself a few seconds after load. Waiting for
# "networkidle" instead hangs on exactly those pages — a challenge that keeps
# polling never goes idle — so the sidecar waits for domcontentloaded and then
# settles for a fixed interval.
DEFAULT_SETTLE_MS = 4_000
# Both waits hold a render permit for their whole duration and the JVM abandons the call
# at 120s, so an unbounded caller-supplied one parks a browser nobody is waiting for.
MAX_TIMEOUT_MS = 60_000
MAX_SETTLE_MS = 15_000
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
_UA_OVERRIDE = None
# A failed probe is retried, not cached forever: the probe navigates, so it fails on the
# same transients a render does, and a permanent cache would strip the disguise from
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

# One signal the full Chromium still does not match: userAgentData.brands says
# "Chromium" where Chrome says "Google Chrome", and Sec-CH-UA is generated from it.
# Emulation.setUserAgentOverride fixes the header, the JS API and the UA string in one
# call. Every field except the brand list is read back from the browser itself, so a
# Linux host reports Linux rather than whatever the developer's machine was.
#
# The read-back has to happen on a SECURE origin: navigator.userAgentData does not
# exist on about:blank, and probing there silently yielded an empty platform, which
# the override then pinned as empty — worse than not overriding at all. The probe page
# is served locally through a fulfilled route, so it is a real https origin with no
# network request.
_UA_PROBE = """async () => {
  const d = navigator.userAgentData;
  const hi = d ? await d.getHighEntropyValues(
      ['architecture', 'bitness', 'model', 'platformVersion']) : {};
  return {ua: navigator.userAgent, platform: d ? d.platform : '',
          mobile: d ? d.mobile : false, ...hi};
}"""


def _header_safe(value):
    """Origin-supplied text, made safe to put in an HTTP header value. send_header
    encodes latin-1 and raises on anything else, taking the whole response with it."""
    return str(value).encode("ascii", "backslashreplace").decode("ascii")


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


def _active_channel():
    with _CHANNEL_LOCK:
        return _LAST_CHANNEL


def _launch_proxy(proxy):
    """Playwright launch settings for the operator's scrape proxy (JCLAW-1271), or None.

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
    settings = {"server": "%s://%s:%d" % (parts.scheme, host, parts.port)}
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


def _probe_ua(context):
    """This build's UA metadata with the brand list corrected to Chrome, or None when the
    probe fails — a UA with no Chrome/ token included, which the version split cannot
    parse. A probe failure degrades the disguise; it must not fail the render."""
    try:
        page = context.new_page()
        try:
            page.route("**/*", lambda r: r.fulfill(
                status=200, content_type="text/html", body="<html></html>"))
            page.goto("https://ua-probe.jclaw.invalid/",
                      wait_until="domcontentloaded", timeout=15000)
            info = page.evaluate(_UA_PROBE)
        finally:
            page.close()
        ua = info["ua"].replace("HeadlessChrome/", "Chrome/")
        full = ua.split("Chrome/")[1].split(" ")[0]
    except Exception as exc:
        sys.stderr.write("[stealth-sidecar] UA probe failed (%s: %s) — rendering with the "
                         "browser's own User-Agent\n" % (type(exc).__name__, exc))
        return None
    major = full.split(".")[0]
    return {
        "userAgent": ua,
        "userAgentMetadata": {
            "brands": [{"brand": "Not=A?Brand", "version": "99"},
                       {"brand": "Google Chrome", "version": major},
                       {"brand": "Chromium", "version": major}],
            "fullVersion": full,
            "platform": info.get("platform") or "",
            "platformVersion": info.get("platformVersion") or "",
            "architecture": info.get("architecture") or "",
            "bitness": info.get("bitness") or "",
            "model": info.get("model") or "",
            "mobile": bool(info.get("mobile")),
        },
    }


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

        # A pin exempts its host from the route gate, so an unvalidated one is a way
        # around the gate rather than an input to it. The token proves the caller holds
        # the sidecar's secret, not that SsrfGuard approved the pin — and --no-auth drops
        # even that — so pins are re-checked rather than trusted.
        for pinned_host, pinned_ip in pins.items():
            if not is_public_ip(pinned_ip):
                self._send_json(400, {
                    "error": "pin for %s is not a public address" % pinned_host})
                return

        try:
            launch_proxy = _launch_proxy(proxy)
        except ValueError as exc:
            self._send_json(400, {"error": str(exc)})
            return

        with self.state.render_slots:
            try:
                html, status, settled_status, final_url, blocked = self._render(
                    url, pins, timeout_ms, settle_ms, wait_until, language, launch_proxy)
            except Exception as exc:
                self._send_json(502, {"error": "%s: %s" % (type(exc).__name__, exc)})
                return

        # Assembled before the first write, so a failure here is still answerable as 502.
        try:
            body = html.encode("utf-8", "replace")
            truncated = len(body) > max_bytes
            if truncated:
                # Re-decoding the slice drops a character the cut fell inside, rather
                # than emitting a lone continuation byte.
                body = body[:max_bytes].decode("utf-8", "ignore").encode("utf-8")
            headers = [
                ("Content-Type", "text/html; charset=utf-8"),
                ("X-Upstream-Status", str(status)),
                # Where the settle window ENDED, which differs from X-Upstream-Status
                # when a challenge answered 4xx and then resolved itself client-side.
                ("X-Settled-Status", str(settled_status)),
                # Both values are origin-influenced — final_url comes from the page, and
                # the blocked set from URLs it chose to request — and send_header raises
                # on anything outside latin-1, which would drop the whole response.
                ("X-Upstream-Url", _header_safe(final_url)),
            ]
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

    @staticmethod
    def _ua_override(context):
        """Chrome-shaped UA metadata built from this browser's own values. A success is
        cached for the process; a failure only until _UA_RETRY_S has passed, so a
        transient probe failure degrades one render's disguise rather than every later
        one, without re-probing on every page in between."""
        global _UA_OVERRIDE, _UA_RETRY_AT
        with _UA_LOCK:
            if _UA_OVERRIDE is not None or time.monotonic() < _UA_RETRY_AT:
                return _UA_OVERRIDE
            # Probed under the lock: concurrent renders would otherwise each run their
            # own probe navigation, making the failure this retries more likely, not less.
            _UA_OVERRIDE = _probe_ua(context)
            if _UA_OVERRIDE is None:
                _UA_RETRY_AT = time.monotonic() + _UA_RETRY_S
            return _UA_OVERRIDE

    def _render(self, url, pins, timeout_ms, settle_ms, wait_until, language, proxy=None):
        args = []
        if pins:
            clauses = ["MAP %s %s" % (h, ip) for h, ip in pins.items()]
            args.append("--host-resolver-rules=" + ",".join(clauses))

        blocked = set()
        budget = _ResolveBudget(_RESOLVE_BUDGET_S)

        def gate(route):
            # Pinned hosts are already guard-validated; anything else the page
            # reaches for gets resolved and range-checked before it is allowed.
            parts = urlsplit(route.request.url)
            scheme = parts.scheme.lower()
            if scheme in ("http", "https"):
                # urlsplit, not string slicing: the hand-rolled split produced "[2606"
                # for an IPv6 literal and "" for anything it could not parse, and an
                # empty host then skipped the check entirely.
                host = parts.hostname
                if not host:
                    blocked.add(route.request.url[:80])
                    route.abort()
                    return
                if host not in pins and not _host_allowed(host, budget):
                    blocked.add(host)
                    route.abort()
                    return
            elif scheme not in ("data", "blob", "about"):
                # data/blob/about reach no network and a page legitimately uses them.
                # Everything else -- file:, ftp:, chrome-extension: -- has no business
                # being fetched by a rendered page, and defaulting them to "allow" is
                # the wrong way round for a security gate.
                blocked.add(scheme + ":")
                route.abort()
                return
            route.continue_()

        def ws_gate(ws):
            # page.route never sees WebSocket traffic -- it is a separate API -- so
            # until this existed a page could open ws://127.0.0.1, read a loopback
            # service and write the reply into the DOM we hand back.
            host = urlsplit(ws.url).hostname
            if not host or (host not in pins and not _host_allowed(host, budget)):
                blocked.add(host or ws.url[:80])
                return
            ws.connect_to_server()

        with sync_playwright() as p:
            browser = _launch(p, args, proxy)
            try:
                # Routed on the CONTEXT, not the page: a popup the page opens is a
                # separate Page with no page-level handler, and service workers issue
                # requests the page handler never sees at all.
                # Carries the caller's language the way rungs 1 and 2 do. locale
                # sets navigator.language too, so a page branching in JavaScript sees
                # the same preference the header states.
                context = browser.new_context(service_workers="block",
                                              locale=language,
                                              extra_http_headers={
                                                  "Accept-Language": language + ", *;q=0.5"})
                context.route("**/*", gate)
                context.route_web_socket("**/*", ws_gate)
                page = context.new_page()
                settled = {"status": 0}

                def track(resp):
                    # goto's status is the FIRST navigation response, captured before
                    # the settle window; an interstitial that resolves itself navigates
                    # again inside it, and this records where that landed.
                    if resp.request.is_navigation_request() and resp.frame == page.main_frame:
                        settled["status"] = resp.status

                page.on("response", track)
                override = self._ua_override(context)
                if override:
                    context.new_cdp_session(page).send(
                        "Emulation.setUserAgentOverride", override)
                response = page.goto(url, wait_until=wait_until, timeout=timeout_ms)
                if settle_ms > 0:
                    page.wait_for_timeout(settle_ms)
                return (page.content(),
                        response.status if response else 0,
                        settled["status"],
                        page.url,
                        blocked)
            finally:
                browser.close()


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
    args = ap.parse_args()

    if args.probe:
        print(json.dumps(capability()))
        return
    if args.port is None:
        ap.error("--port is required unless --probe is given")

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
