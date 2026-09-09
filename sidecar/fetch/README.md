# fetch sidecar

TLS-impersonating fetch for jclaw — rung 2 of the scrape escalation ladder (JCLAW-1087).

## Why a separate process

Cloudflare and its peers fingerprint the TLS ClientHello (JA3/JA4) and the HTTP/2
SETTINGS frame. Both go out *before* our request does, so no header combination
rescues a fetch that JSSE has already given away. [`curl_cffi`](https://github.com/lexiforest/curl_cffi)
binds curl-impersonate, which reproduces a real Chrome handshake byte for byte.

It is a process rather than a library because the one Java equivalent
(`impersonator-okhttp`) ships its own copy of 309 `okhttp3/` classes —
`OkHttpClient` included — and would collide with the `okhttp-jvm` the rest of
jclaw runs on.

## Redirects are not followed, deliberately

`curl_cffi` follows redirects by default and its impersonation actively disguises
that the hop happened. Left on, a remote origin could bounce a crawl onto a
private address with `SsrfGuard` never seeing the hop. The sidecar returns the
3xx and its `Location` to the JVM, which re-validates and re-calls.

This is the one place where the stealth lane and SSRF containment genuinely
conflict. Containment wins.

## SSRF: what is checked here, and what is not

**The request URL is not checked at all.** The JVM's `SsrfGuard` is authoritative for
it, and stays authoritative on every hop because redirects come back here rather than
being followed.

`pins` *is* checked. Each `{host: ip}` entry becomes a `CURLOPT_RESOLVE` entry, so it
decides where curl actually connects, and the token proves only that the caller held the
secret, not that it was the JVM whose `SsrfGuard` approved the address — so every pin
target is re-validated against `ssrf.py` (shared with the render sidecar, held to the
Java guard by `StealthBrowserTest`), and a non-public address is a `400`.

## Impersonation profiles

`--model` selects the profile (the daemon reuses its `--model` slot for it, so
repinning it in config forces a respawn). Defaults to `chrome`, the rolling alias
that tracks the newest Chrome this build knows; pin an exact one (`chrome146`)
when you need reproducibility across machines. `--probe` prints the profile list
size and whether the requested name is known.

**A static JA3 is not a concern here.** Chrome ≥110 permutes its TLS extension
order per connection, and `curl_cffi` does the same — three consecutive requests
on one profile produced three different JA3 hashes and one unchanging JA4
(measured against `tls.browserleaks.com`, curl_cffi 0.16.0, profile `chrome`).
JA4 sorts extensions before hashing, which is exactly why it replaced JA3; the
stable identity we present is the JA4, and it matches Chrome's.

## Protocol

`--host` defaults to `127.0.0.1`, and `LocalSidecarDaemon` passes it explicitly — but
the server binds whatever it is handed. Loopback is a default here, not a constraint the
code enforces.

| Route | Result |
|---|---|
| `GET /health` | `{status, model, curl_cffi, profile_supported, reason}` — `model` is the profile, so repinning it in config makes the JVM's health check respawn |
| `GET /capability` | `{kind, runnable, profile, profileKnown, profileCount, reason}` — the same keys whether or not the install is usable |
| `POST /fetch` | upstream body verbatim; see below |
| `POST /shutdown` | answers, lets in-flight fetches finish — up to 45 s (`DRAIN_TIMEOUT_S`, the same budget the idle exit uses) — then exits, so a restarted JVM can evict an orphan |
| `--probe` (CLI) | the capability JSON on stdout, no server, no port |

`POST /fetch` takes `{url, pins?, headers?, profile?, timeoutMs?, maxBytes?}` (`timeoutMs`
defaults to `30000`, `DEFAULT_TIMEOUT_MS`) and answers `200` when the exchange completed — **not** when the origin was happy. The
origin's own result rides in headers, so a 403 from the origin stays
distinguishable from a 403 raised by the sidecar itself:

- `X-Upstream-Status` — the origin's status code
- `X-Upstream-Location` — present only on a 3xx
- `X-Upstream-Content-Type` — the origin's content type
- `X-Upstream-Url` — the URL actually requested
- `X-Upstream-Truncated` — `true` when the body hit `maxBytes`

`400` means a malformed request — a body that is not a JSON object, a missing `url`, a
`pins` that is not one, a `timeoutMs`/`maxBytes` that will not parse as a number, or a
negative `maxBytes`. `502` is a transport failure reaching the origin.

`maxBytes` caps what this process buffers, under a hard 25 MB ceiling. `0` means zero
bytes, not "no cap"; omit the field to get the ceiling.

A `curl_cffi` that will not import is a startup failure, not a degraded mode: the process
writes the import error to stderr and exits non-zero. `LocalSidecarDaemon` reads any
non-2xx `/health` as "not up yet", so a sidecar that stayed up reporting its own
brokenness would burn the full startup timeout; a dead child is named within a second.
`--probe` still reports it as `runnable: false`, which is how to diagnose it without a spawn.

## Configuration

Keys live in the Config DB (Settings), not `conf/application.conf`; none is seeded by
`DefaultConfigJob`, so an absent key means the default below.

| Key | Default | Read by | Meaning |
|---|---|---|---|
| `scrape.impersonate.enabled` | `true` | `FetchSidecarManager.available` | `false` takes rung 2 out of the ladder without touching the sidecar |
| `scrape.impersonate.profile` | `chrome` | `FetchSidecarManager.profile` | the impersonation profile, passed as `--model`; repinning it respawns (see above) |
| `scrape.impersonate.port` | `9533` | `LocalSidecarDaemon.port()` | loopback port; passed as `--port` and used for every call |
| `scrape.impersonate.idleTimeoutMinutes` | `15` | `LocalSidecarDaemon.spawnNow` | passed as `--idle-timeout-min`; the process drains and exits after that long without a fetch |
| `scrape.impersonate.startupTimeoutSeconds` | `180` | `LocalSidecarDaemon.awaitHealthy` | how long `/health` may go unanswered after spawn before the launch fails |

`LocalSidecarDaemon` also reads `scrape.impersonate.timeoutSeconds` (exported as
`SIDECAR_REQUEST_TIMEOUT_SEC`) and `scrape.impersonate.hfToken` (exported as `HF_TOKEN`) for
every sidecar it launches; this one reads neither variable, so the two keys have no effect here.

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
SIDECAR_TOKEN=dev uv run serve.py --port 9533 --model chrome
curl -s -H 'X-Sidecar-Token: dev' localhost:9533/health
```

The JVM launches it through `LocalSidecarDaemon` and never needs these directly.
