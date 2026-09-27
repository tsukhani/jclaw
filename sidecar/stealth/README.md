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
the pinning with it, so the containment is rebuilt here in two layers (JCLAW-731).
`PlaywrightBrowserTool` no longer pins at launch: since JCLAW-1283 it screens every
connection through an in-JVM SOCKS5 proxy, so only the route gate below is shared with it.

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
3. **WebRTC.** Neither layer sees UDP, so a page could send it anywhere — STUN and a data
   channel's connectivity checks both reach a loopback listener. The launch passes
   `disable_non_proxied_udp` under **both** spellings, because each build ignores one of them
   silently. Measured with a loopback UDP listener: the full Chromium honors
   `--webrtc-ip-handling-policy` and ignores `--force-webrtc-ip-handling-policy`, and the
   headless-shell fallback does the reverse. The in-JVM browser launches with no channel, which
   headless Playwright resolves to the shell — which is why JCLAW-1286 found the force spelling
   the one that works there. The [self-check](#self-check) aims the same probe at a socket of its
   own and reports a datagram as a problem.

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
| `POST /render` | rendered HTML; outcome in `X-Upstream-*` / `X-Settled-Status` / `X-Challenge` / `X-Blocked-Hosts*` |
| `POST /session/open` | `{id}` of a new [session](#sessions); `429` when every session is open |
| `POST /session/close` | `{closed}` |
| `POST /shutdown` | closes every session, then exits, so a restarted JVM can evict an orphan |
| `--probe` (CLI) | capability JSON on stdout, no browser launched |
| `--self-check [--language L]` (CLI) | renders a loopback fixture with a render's own launch and reports whether every surface agrees — see [Self-check](#self-check) |

`channel` is the browser the most recent render actually launched, not the one asked
for — see [Looking like a real browser](#looking-like-a-real-browser).

`POST /render` takes `{url, pins?, language?, timeoutMs?, settleMs?, challengeMs?, solveTurnstile?, waitUntil?, maxBytes?, proxy?}`. `proxy` is the operator's
scrape proxy (`{url, username?, password?}`), given to the browser at launch; it is checked on
the provider rule (loopback and LAN allowed; link-local, multicast, unspecified and unresolvable
refused with `400`). The route gate still range-checks every host the page reaches either way.
Defaults: `timeoutMs` `35000`, `settleMs` `4000`, `challengeMs` `30000`, `solveTurnstile` `false`,
`language` `en` (launched as `--lang` and `--accept-lang`, so the header and `navigator.language`
agree on the page and in its workers — see [One story on every surface](#one-story-on-every-surface)),
`waitUntil` `domcontentloaded`. The JVM leaves `settleMs` at its default but sends `timeoutMs`,
`challengeMs` and `solveTurnstile` on every render — see [Cloudflare challenges](#cloudflare-challenges).

| Response header | Meaning |
|---|---|
| `X-Upstream-Status` | status of the **first** navigation response, from `page.goto` — captured *before* the settle window. `0` means the navigation returned no response object |
| `X-Settled-Status` | status of the last main-frame navigation the settle or challenge window **ended** on |
| `X-Upstream-Url` | where the page finally sat |
| `X-Upstream-cf-mitigated` | the settled navigation's `cf-mitigated` header, present only when the origin sent one |
| `X-Challenge` | the Cloudflare challenge the render met and how it ended: `<type>; <outcome>`, plus `; clicks=N` when the solver clicked — `managed; cleared`, `interactive; unsolved; clicks=3`. Absent when none stood |
| `X-Blocked-Hosts` | up to 20 hosts the route gate aborted |
| `X-Blocked-Hosts-Count` | how many it aborted in total, since the list above is clipped |
| `X-Upstream-Truncated` | `true` when the body was cut at `maxBytes` |
| `X-Clearance` / `X-Clearance-User-Agent` | a session render's `cf_clearance` for its host and the User-Agent the browser sent, only when the request set `clearance` and the browser holds one |

The two status headers differ on exactly the case this rung exists for: an interstitial
served with 403 that then resolves itself client-side ends its window on 200, and the
settled body is the real page. The JVM scores a render from `X-Settled-Status`, falling
back to `X-Upstream-Status` when the settled one is absent or `0` (JCLAW-1304).

`waitUntil` defaults to `domcontentloaded`, **not** `networkidle`. A challenge page
that keeps polling never goes idle, so waiting for it hangs on exactly the pages this
rung exists for. A page with no challenge then settles for a fixed `settleMs`; a standing
challenge is polled instead — see [Cloudflare challenges](#cloudflare-challenges).

`timeoutMs`, `settleMs`, `challengeMs` and `maxBytes` are clamped here (60 s / 15 s / 45 s /
25 MiB) rather than trusted. Each holds a render permit — one of four — for its whole
duration, and the JVM abandons the call at 120 s, so an unbounded request parks a browser
nobody is waiting for.

`maxBytes` caps the rendered document this process relays, under that hard ceiling. `0`
means zero bytes, not "no cap"; omit the field to get the ceiling. These are the fetch
sidecar's semantics exactly, because the JVM sends both rungs the same
`WebExtraction.maxBodyBytes()` and a field that meant opposite things at the two ends
would cap one rung and uncap the other.

`400` means a malformed request — a body that is not a JSON object, a missing `url`, a
`pins` that is not one, a `timeoutMs`/`settleMs`/`challengeMs`/`maxBytes` that will not parse as a
number, a `solveTurnstile` that is not `true` or `false`, a negative `maxBytes`, or a pin whose
target is not a public address. `502` is a failed navigation.

## Sessions

A crawl renders each host in one **session**: a browser and context kept open across its
renders, so a Cloudflare clearance the browser earns on one page is sent with the next
(JCLAW-1307). A single `web_fetch` still launches a browser per render.

`POST /session/open` takes `{host, pins?, language?, proxy?}` and answers `{id}`. The browser is
launched then, with the language, the proxy and the `--host-resolver-rules` pin of that moment,
and keeps them for its life: a pin may name only the session's own host and must be a public
address, as on a render. The context carries the same route and WebSocket gates a render's does,
so every other host the page reaches is still range-checked. `POST /render` with `session` renders
in that context and takes no `pins` or `proxy` of its own (`400`); a URL on any other host is
`400`, and an unknown, closed or lost session `404`, on which the JVM opens a new one. With
`clearance: true` the response carries the browser's `cf_clearance` for the host and its
User-Agent — the JVM asks only when `scrape.impersonate.clearanceHandoff` is on.

Nothing reaches disk. The context has no user data dir, so the cookies live in the browser's
memory, and a session closes when:

- the JVM closes it — every crawl closes its sessions when it ends, fails or is cancelled;
- it goes unused for `--session-idle-min` (5) minutes, checked every 15 s;
- the sidecar exits, on `/shutdown` or its own idle timeout, which closes every browser first
  (bounded at 5 s).

At most `--max-sessions` (4) are open at once. Past that `/session/open` answers `429` and the
crawl renders that page with a browser of its own. The bound is memory: each open session is a
live Chromium besides the ones rendering.

Patchright's sync API binds each object to the thread that made it, so a session's browser
lives on a thread of its own and renders one page at a time. The JVM queues a host's pages
before they reach it, where no call timeout runs and a queued page gives up once the crawl must
stop, and each still holds one of the render slots below. Serial is also the point: a host's second page reuses the clearance the first earned
rather than meeting the challenge beside it.

## Cloudflare challenges

After navigation the sidecar looks for a standing Cloudflare challenge by the two markers
`BlockClassifier` reads (JCLAW-1304): the `cType` in Cloudflare's inline `_cf_chl_opt` options
(`non-interactive`, `managed` or `interactive`), or `cf-mitigated: challenge` on the last
main-frame navigation response, reported as `unknown`. Neither is page text, so a challenge
served in French or Japanese is found as the English one is. A `/cdn-cgi/challenge-platform/`
script alone is not a challenge: Cloudflare loads its detection script from there into ordinary
pages too. `challenge_type` is the pure function, and `ScrapeSidecarContractTest` runs it beside
the classifier on the same bodies so the two sides cannot drift apart.

With no challenge the render settles for `settleMs`, as it always has. With one, the settle
window is replaced by a poll every 500 ms until both markers are gone or `challengeMs` runs out.
Once the markers are gone, the page behind the challenge gets what is left of the budget, up to
`settleMs`, to render. `X-Challenge` reports the type and whether it cleared.

**The click is off unless the operator turns it on** with `scrape.stealth.solveTurnstile`, which
the JVM reads per render and sends as `solveTurnstile`. With it on, the checkbox of a `managed` or
`interactive` gate is clicked once Turnstile's frame is visible: at most three clicks, 8 s apart,
each at the checkbox's offset inside the frame whose URL starts
`https://challenges.cloudflare.com/cdn-cgi/challenge-platform/`, clamped to that frame's bounding
box. No other frame, and no point outside that box, is ever clicked. A `non-interactive` or
`unknown` challenge is only waited on, and with the switch off so is every challenge. A Turnstile
widget inside a page with content carries no `_cf_chl_opt`, so it is not a gate: never waited
on, never clicked.

The click only starts Turnstile's own check of the browser; whether that passes is down to the
fingerprint — see [One story on every surface](#one-story-on-every-surface). Everything the solve
sets off comes from the render's own context, so it passes the route gate and the WebSocket gate
like every other request.

The JVM sends `timeoutMs` 35 s and `challengeMs` 45 s, which leaves 40 s of its 120 s call timeout
for the launch, the UA probe (15 s ceiling) and the route gate's resolve budget (15 s).
`StealthBrowserTest` holds that sum, and `ScrapeSidecarContractTest` holds that the clamps above
never shorten what the JVM asks for. That timeout covers the render alone: the JVM keeps no more
renders in flight than this process has permits (`RenderedFetcher.RENDER_SLOTS`), so a render
never queues here behind another's challenge wait while its call timeout runs.

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
*static* fingerprint matches. What remains distinguishable is behavior: a page is
loaded, settles, and is read — no scrolling, no dwell time, and no mouse movement. The
one pointer input is the [challenge click](#cloudflare-challenges), which lands on the
checkbox with no path leading to it. A detector scoring behavior rather than
fingerprints can still tell, and a render-only rung structurally cannot produce those
signals.

### One story on every surface

A challenge does not only read each signal, it compares them: the page against its iframes (the
Turnstile widget is one) and its Web Workers, and all of them against the request headers
(JCLAW-1305). Measured on a fixture page with `language` `de-DE` on an `en-GB` host:

| Surface | before | after |
|---|---|---|
| `navigator.language` / `languages`, page | `de-DE` / `de-DE` | `de-DE` / `de-DE, de, en-US, en` |
| `navigator.language` / `languages`, dedicated Worker | **`en-GB` / `en-GB, en-US, en`** | `de-DE` / `de-DE, de, en-US, en` |
| `Accept-Language` | `de-DE` on the document and the Worker script, `de-DE, *;q=0.5` on `fetch` calls | `de-DE,de;q=0.9,en-US;q=0.8,en;q=0.7` on every request, a Worker's included |
| `Intl` locale, page and Worker | `de-DE` | `de-DE` |
| `userAgent` / brands, a cross-site iframe and its Worker | **`HeadlessChrome` / `Chromium`**, while the iframe's own document request said Chrome | the page's |
| viewport and screen | 1280×720 | 1920×1080 |

How each surface is set:

- **Language** is a pair of launch flags, `--lang` and `--accept-lang` (the tag, then its primary
  subtag), not the context `locale` — Playwright applies that to the main thread only, which is
  where the Worker's host languages came from. Chromium then builds the header and
  `navigator.languages` from one preference. The primary subtag is listed because, given `fr-FR`
  alone, Chromium's header adds `fr` while `navigator.languages` does not. Chromium adds `en-US`
  and `en` to a two-entry list by itself, on both surfaces, so they still agree.
- **`Intl`** needs one more step. `--lang` moves it where Chromium honors the flag (Linux, per
  Scrapling — not measured here); Chromium on macOS takes its locale from the OS and ignores the
  flag, measured. `Emulation.setLocaleOverride` on the page's CDP session sets it on both, and
  dedicated Workers inherit it — measured.
- **User-Agent.** The `Emulation.setUserAgentOverride` above was measured to reach dedicated
  Workers: `navigator.userAgent`, the `userAgentData` brands and the `User-Agent` header of a
  Worker's requests all match the page, so the mechanism stays. Chromium sends no `Sec-CH-UA` on
  a dedicated Worker's requests with or without the override, so there is nothing there to
  disagree.
- **Cross-site iframes** render in the page's process: `--disable-site-isolation-trials`.
  Isolated, each is a target of its own that the page's CDP session never reaches: measured with
  every override above in place, a cross-site iframe and its Worker still reported
  `HeadlessChrome`, the `Chromium` brand and the host's `Intl` locale. Their requests pass the
  context's route gate either way, measured the same with and without the flag. What isolation
  would add is a process boundary between the sites of one render, in a fresh profile that holds
  nothing of the operator's.
- **Viewport and screen** are both 1920×1080, as context options. **Pointer and hover** come
  from `--blink-settings=primaryHoverType=2,availableHoverTypes=2,primaryPointerType=4,availablePointerTypes=4`,
  because headless can report neither on a host with no pointing device — the reason the story
  and Scrapling give, not reproducible on the macOS host this was measured on, where both were
  already true. The same flag set to "none" turned both false there, so it is honored rather
  than coincidental.
- **WebRTC** — see [SSRF containment](#ssrf-containment-moved-with-the-launch), item 3.
- **No script overrides.** Nothing here defines a property on `navigator` (Patchright disables
  `add_init_script` anyway, above), and the self-check reports any own property on `navigator`
  and any non-native getter on `Navigator.prototype`.

A language tag with a script subtag is the one case that still disagrees: for `zh-Hant-TW`,
Chromium's header keeps a bare `zh` that its `navigator.languages` drops, with or without the
explicit primary subtag. The self-check reports it. Two-part tags (`de-DE`, `en-GB`, `zh-TW`,
`pt-BR`) and bare ones (`en`, `fr`) agree.

### Self-check

`uv run serve.py --self-check --language de-DE` launches the browser exactly as a render does —
the same flags, context options and CDP overrides — loads a fixture page from a loopback server
it starts itself, and prints what the page, a cross-site iframe (`localhost`, where the page is
`127.0.0.1`), their dedicated Workers and each request's headers said, with `problems` listing
every disagreement. Exit `0` means none, `1` at least one, and `2` that it could not run, with
the reason in `error`.

The fixture is served on loopback rather than through a fulfilled route, because Chromium adds
`Accept-Language` below the interception point, where a route handler never sees it. It reports
from the page's own JavaScript world: Patchright's `evaluate` runs in an isolated one, where a
script override on `navigator` would not show.

`StealthFingerprintTest` runs it under `JCLAW_PLAYWRIGHT_TEST`; `ScrapeSidecarContractTest` holds
the launch arguments, the context options and the verdict without a browser.

## Concurrency

Outside a [session](#sessions) a browser is launched per render, because the DNS pin is a
launch argument and cannot be varied on a shared instance. Renders run in parallel behind `--max-concurrent`
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
| `scrape.stealth.solveTurnstile` | `false` | `StealthSidecarManager.solveTurnstile`, per render | `true` lets a render click a Cloudflare gate's checkbox — see [Cloudflare challenges](#cloudflare-challenges) |

`LocalSidecarDaemon` also reads `scrape.stealth.timeoutSeconds` (exported as
`SIDECAR_REQUEST_TIMEOUT_SEC`) and `scrape.stealth.hfToken` (exported as `HF_TOKEN`) for every
sidecar it launches; this one reads neither variable, so the two keys have no effect here.

`serve.py` flags: `--host` (`127.0.0.1`), `--port`, `--model` (`patchright-chromium` — the
identity `/health` echoes and the JVM's health check expects), `--cache-dir`
(`data/stealth-sidecar`), `--idle-timeout-min` (`15`), `--max-concurrent` (`4`; the daemon's
argv has no slot for it, so the JVM always gets the default), `--max-sessions` (`4`) and
`--session-idle-min` (`5`) on the same terms, `--no-auth`, `--probe`, `--self-check` with
`--language` (`en`).

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
uv run serve.py --self-check --language de-DE   # one render of a loopback fixture, no token
SIDECAR_TOKEN=dev uv run serve.py --port 9532 --max-concurrent 4
curl -s -H 'X-Sidecar-Token: dev' localhost:9532/health
```
