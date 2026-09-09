# stealth sidecar

Rendering with anti-detection — rung 3 of the scrape escalation ladder (JCLAW-1088).

## Two jobs, deliberately kept apart

| Failure mode | What it is | Protection tier |
|---|---|---|
| `THIN_CONTENT` | a client-rendered page whose text only exists after JavaScript runs | any, including none |
| `JS_CHALLENGE` / `TURNSTILE` | a fingerprint gate that must execute its own JavaScript | protected only |

Conflating them would credit anti-bot work for a rendering fix, or the reverse. The
harness scores them on separate axes: `byRendering` against the corpus's SSR/SPA label,
`byStratum` against its protection label.

## Why Patchright drives, instead of the JVM attaching over CDP

The story this implements originally called for `chromium.connectOverCDP` from
Playwright Java. That does not work, and the reason is worth recording so nobody
re-proposes it.

Patchright's anti-detection is **driver-side**. It avoids issuing `Runtime.enable`
(using isolated execution contexts instead) and disables `Console.enable` outright —
things only the client sending CDP commands can do, not the browser binary. Attach a
stock Playwright client over CDP and it issues those commands itself, re-introducing
exactly the leaks the patches remove. You get a real browser with no stealth: enough
for `THIN_CONTENT`, useless for `JS_CHALLENGE`.

Camoufox was the other candidate in the story and is structurally impossible here —
it is Firefox-based, Playwright drives Firefox over Juggler, and `connect_over_cdp`
answers *"CDP connections are only supported by Chromium"*.

So the JVM asks this process to **render a URL** and gets HTML back. Patchright
launches and drives; the stealth survives.

## SSRF containment moved with the launch

`--host-resolver-rules` is a launch-time flag. Moving the launch out of the JVM moved
the pinning with it, so the containment is rebuilt here in two layers, mirroring what
`PlaywrightBrowserTool` does in-process (JCLAW-731):

1. **Launch pin.** The JVM resolves and validates the entry host with `SsrfGuard` and
   sends the address it actually approved. The sidecar turns it into a `MAP` clause, so
   the browser connects only where the guard checked — closing the rebinding window
   between our lookup and the browser's. Verified enforced: a `MAP` to `127.0.0.1`
   makes the navigation fail rather than silently resolving normally.
2. **Route gate.** The launch pin covers the entry host only. A page also follows
   redirects and pulls subresources, so every request is intercepted, its host resolved
   and range-checked, and non-public ones aborted. Blocked hosts come back in
   `X-Blocked-Hosts` (with a total in `X-Blocked-Hosts-Count`) rather than failing
   silently. The lookup runs under a 3 s deadline and fails closed — `getaddrinfo` takes
   no timeout and the gate runs on the thread holding a render permit, so a black-holed
   resolver would otherwise stall the render past the JVM's 120 s call timeout. Decisions
   are cached for 60 s across renders: long enough that a page pulling forty subresources
   from one host resolves it once, short enough that an allow is not a standing
   rebinding window. A deadline miss is **not** cached — it is an answer the resolver
   never gave, and caching it would block a legitimate CDN for the full minute on one slow
   lookup — so the request that hit it is denied and the next one asks again. Lookups run
   on a fixed 8-thread pool, because a lookup past its deadline is abandoned and
   `getaddrinfo` cannot be interrupted: unbounded, a black-holed resolver would leave one
   live thread per host the page names. Across one render the waits on unresolvable hosts
   share a 15 s budget (`_RESOLVE_BUDGET_S`): route handlers run serially, so it is this
   total, not the per-lookup deadline, that keeps a hostile page inside the JVM's call
   timeout — past it an unknown host is denied without waiting. The decision cache holds at
   most 512 hosts and is cleared wholesale when full, since a page can name an unlimited
   number. The gate admits `http`/`https` after the host check and `data`/`blob`/`about`
   unchecked (they reach no network); every other scheme — `file:`, `ftp:`,
   `chrome-extension:` — is aborted, because defaulting an unknown scheme to allow is the
   wrong way round for a security gate. WebSockets go through a second interceptor,
   `context.route_web_socket` (`ws_gate`): `page.route` never sees WebSocket traffic, so
   without it a page could open `ws://127.0.0.1`, read a loopback service and write the
   reply into the DOM handed back. It applies the same host check to the socket's URL and
   counts a refused one in `X-Blocked-Hosts`.

Layer 2 is a **second implementation of a security check**, which is a real cost. It
lives in `ssrf.py` — stdlib-only, no Patchright import — and `StealthBrowserTest` runs
that exact file against the same address table it feeds `SsrfGuard.isUnsafe`, failing
if the Python guard admits anything the JVM rejects — a stricter Python verdict passes.
(A positive control — `8.8.8.8` must come back public — stops a guard that rejects
everything from reading as a pass.)

## Protocol

`--host` defaults to `127.0.0.1` and the daemon passes it explicitly, but the server binds whatever it is handed — the loopback bind is a default, not an enforced constraint.

| Route | Result |
|---|---|
| `GET /health` | `{status, model, patchright, channel, browser_ready}` |
| `GET /capability` | `{kind, runnable, channel, reason}` |
| `POST /render` | rendered HTML; outcome in `X-Upstream-*` / `X-Settled-Status` / `X-Blocked-Hosts*` |
| `POST /shutdown` | exits, so a restarted JVM can evict an orphan |
| `--probe` (CLI) | capability JSON on stdout, no browser launched |

`channel` is the browser the most recent render actually launched, not the one asked
for — see [Looking like a real browser](#looking-like-a-real-browser).

`POST /render` takes `{url, pins?, language?, timeoutMs?, settleMs?, waitUntil?, maxBytes?}`.
Defaults: `timeoutMs` `35000`, `settleMs` `4000`, `language` `en` (sent as `Accept-Language`
and set as the context locale, so `navigator.language` agrees), `waitUntil` `domcontentloaded`.

| Response header | Meaning |
|---|---|
| `X-Upstream-Status` | status of the **first** navigation response, from `page.goto` — captured *before* the settle window. `0` means the navigation returned no response object |
| `X-Settled-Status` | status of the last main-frame navigation the settle window **ended** on |
| `X-Upstream-Url` | where the page finally sat |
| `X-Blocked-Hosts` | up to 20 hosts the route gate aborted |
| `X-Blocked-Hosts-Count` | how many it aborted in total, since the list above is clipped |
| `X-Upstream-Truncated` | `true` when the body was cut at `maxBytes` |

The two status headers differ on exactly the case this rung exists for: an interstitial
served with 403 that then resolves itself client-side ends the settle window on 200, and
the settled body is the real page. The JVM currently reads `X-Upstream-Status` only, and
treats `>= 400` as a failed fetch — so those pages are discarded and scored `TRUST_BLOCK`
even though the HTML in the same response is good. `X-Settled-Status` is reported so that
can be fixed deliberately: it changes the measured access rate, so it is a gate decision,
not a bug fix.

`waitUntil` defaults to `domcontentloaded`, **not** `networkidle`. A challenge page
that keeps polling never goes idle, so waiting for it hangs on exactly the pages this
rung exists for. A fixed `settleMs` window afterwards gives the challenge time to
resolve itself.

`timeoutMs`, `settleMs` and `maxBytes` are clamped here (60 s / 15 s / 25 MiB) rather than
trusted. Each holds a render permit — one of four — for its whole duration, and the JVM
abandons the call at 120 s, so an unbounded request parks a browser nobody is waiting for.

`maxBytes` caps the rendered document this process relays, under that hard ceiling. `0`
means zero bytes, not "no cap"; omit the field to get the ceiling. These are the fetch
sidecar's semantics exactly, because the JVM sends both rungs the same
`WebExtraction.maxBodyBytes()` and a field that meant opposite things at the two ends
would cap one rung and uncap the other.

`400` means a malformed request — a body that is not a JSON object, a `pins` that is not
one, a `timeoutMs`/`settleMs`/`maxBytes` that will not parse as a number, a negative
`maxBytes`, or a pin whose target is not a public address. `502` is a failed navigation.

## Looking like a real browser

**No system browser is required.** Everything below uses the browser Playwright
downloads — Patchright is a Playwright fork and shares the same `ms-playwright` cache —
so a headless Linux server behaves exactly like a developer laptop. That browser is
Google's official **Chrome for Testing** build, not a community Chromium, which is why
proprietary codecs (H.264, AAC, MP3) and Widevine are present in it.

Measured: 106/150 on the corpus against 107/150 for the operator's installed Chrome —
a one-entry difference, inside run-to-run noise. The `channel="chrome"` preference was
removed rather than kept as an optimisation, because a second code path that only some
hosts exercise is a reproducibility problem, not a feature.

The original problem was not Chromium-versus-Chrome. Recent Playwright defaults
`headless=True` to **`chromium-headless-shell`**, a stripped build, and that is what
made rung 3 look automated. Launching the **full** Chromium (`channel="chromium"`,
already downloaded by `patchright install`) closes almost all of it. Measured against a
real headful Chrome on the same probe:

| Signal | headless shell | full Chromium |
|---|---|---|
| `navigator.plugins` / `mimeTypes` | 0 / 0 | **5 / 2** |
| `window.chrome` | `undefined` | **object, with `loadTimes`** |
| WebGL renderer | SwiftShader | **the real GPU** |
| `languages` | `en-US` | **the host's real list** |
| `Notification.permission` | `denied` | **`default`** |
| `pdfViewerEnabled` | false | **true** |

Proprietary codecs (H.264, AAC, MP3) and Widevine are present in the bundled build
too — checked, not assumed.

A launch that fails falls back to the headless shell **for that render only**, and the
next render tries the full build again. Latching the shell for the process lifetime was
wrong: a launch failure is not proof the build is missing — a timeout, ENOMEM, a locked
profile and fd exhaustion raise the same way, and all are reachable with four renders
launching at once — so one transient failure would have downgraded the fingerprint for
every later page of a sweep. The fallback is **reported**, in `channel` on `/health` and
`/capability`, which names the browser the most recent render launched: a silent
substitution would leave a sweep measuring the stripped build while the report named the
full one.

One signal the full Chromium still gets wrong: `userAgentData.brands` says `Chromium`
where Chrome says `Google Chrome`, and **`Sec-CH-UA` is generated from it**. A single
`Emulation.setUserAgentOverride` fixes the header, the JS API and the User-Agent string
together. Every field but the brand list is read back from the browser itself, so a
Linux host reports Linux rather than whatever the author's machine was.

That read-back must happen on a **secure origin** — `navigator.userAgentData` does not
exist on `about:blank`, and probing there silently yields an empty platform which the
override then pins as empty, worse than not overriding at all. The probe page is served
locally through a fulfilled route: a real `https://` origin with no network request.

A failed probe is logged and the render goes out with the build's own User-Agent —
degrading the disguise is the right failure mode, since a probe that fails the render
turns one broken probe into a render error that names Playwright rather than the probe.
The failure is remembered for 60 s and then re-probed. Caching it for the process
lifetime was wrong for the same reason latching the headless shell was: the probe
navigates, so it fails on the transients a render fails on, and every later render would
have gone out undisguised while `/health` still reported the sidecar runnable.

Result: **20 of 21 probed signals identical to a real headful Chrome**, and no failing
rows on `bot.sannysoft.com`.

The one that remains is `outerWidth`/`outerHeight`, which equal the viewport because
headless has no window chrome to add. It is not fixable here — Patchright disables
`add_init_script` (verified: a marker set in one is absent from the page, at both
context and page level), which is the only hook running early enough for a detector
reading the value during load. It is also a weak tell, since a real browser in
fullscreen or kiosk mode reports the same.

**This is not indistinguishability, and should not be described as such.** Every
*static* fingerprint matches. What remains distinguishable is behaviour: a page is
loaded, settles, and is read — no mouse movement, no scrolling, no dwell time. A
detector scoring behaviour rather than fingerprints can still tell, and a render-only
rung structurally cannot produce those signals.

## Concurrency

A browser is launched per render, because the DNS pin is a launch argument and cannot
be varied on a shared instance. Renders run in parallel behind `--max-concurrent`
(default 4) rather than a mutex — serializing them turns a 150-page corpus run into a
twenty-minute one. The bound is memory, not safety: each permit is a live headless
Chromium. One `sync_playwright()` context per thread is safe; sharing one across
threads is not.

The UA probe *is* held under its cache lock, so concurrent cold-start renders queue behind
one probe instead of each running their own. The probe navigates to a locally fulfilled
route rather than the network, so the wait is milliseconds and the 15 s timeout is only a
ceiling; running four of them in parallel would make the failure the retry exists for
more likely, not less.

## Configuration

Keys live in the Config DB (Settings), not `conf/application.conf`; none is seeded by
`DefaultConfigJob`, so an absent key means the default below.

| Key | Default | Read by | Meaning |
|---|---|---|---|
| `scrape.stealth.enabled` | `true` | `StealthSidecarManager.available` | `false` takes rung 3 out of the ladder without touching the sidecar |
| `scrape.stealth.port` | `9532` | `LocalSidecarDaemon.port()` | loopback port; passed as `--port` and used for every call |
| `scrape.stealth.idleTimeoutMinutes` | `15` | `LocalSidecarDaemon.spawnNow` | passed as `--idle-timeout-min`; the process exits after that long without a render |
| `scrape.stealth.startupTimeoutSeconds` | `300` | `LocalSidecarDaemon.awaitHealthy` | how long `/health` may go unanswered after spawn before the launch fails |

`LocalSidecarDaemon` also reads `scrape.stealth.timeoutSeconds` (exported as
`SIDECAR_REQUEST_TIMEOUT_SEC`) and `scrape.stealth.hfToken` (exported as `HF_TOKEN`) for every
sidecar it launches; this one reads neither variable, so the two keys have no effect here.

`serve.py` flags: `--host` (`127.0.0.1`), `--port`, `--model` (`patchright-chromium` — the
identity `/health` echoes and the JVM's health check expects), `--cache-dir`
(`data/stealth-sidecar`), `--idle-timeout-min` (`15`), `--max-concurrent` (`4`; the daemon's
argv has no slot for it, so the JVM always gets the default), `--no-auth`, `--probe`.

## Authentication

Every request must carry `X-Sidecar-Token`, matching the `SIDECAR_TOKEN` the JVM derives
from its own install secret and passes in the child's environment. Without that variable
the sidecar refuses to start. A custom header is deliberately not CORS-simple, so a page
the operator visits cannot reach a warm sidecar even though it listens on loopback.

Hand-running: set a token of your own and send it, or pass `--no-auth` (off by default) to
serve unauthenticated.

## Running it standalone

```bash
uv run serve.py --probe                    # one-shot, no server, no token
SIDECAR_TOKEN=dev uv run serve.py --port 9532 --max-concurrent 4
curl -s -H 'X-Sidecar-Token: dev' localhost:9532/health
```
