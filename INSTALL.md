# Installing and running JClaw

This guide is for the person who installs and runs JClaw. It covers every way to install
JClaw, how to keep it running and up to date, and the optional extras you can add.

- New to JClaw? Start with the [README](README.md).
- To learn how to use JClaw once it runs, read the user guide inside the app:
  open **Help → User Guide** in the sidebar, or go to <http://localhost:9000/guide>.
- To work on JClaw's code, read [DEVELOPMENT.md](DEVELOPMENT.md).

## Contents

- [Requirements](#requirements)
- [Quick install (one line)](#quick-install-one-line)
- [Managing JClaw](#managing-jclaw)
- [Updating](#updating)
- [Docker](#docker-production)
- [Optional extras](#optional-extras)
- [Browser-trusted HTTPS](#browser-trusted-https)
- [Running behind a reverse proxy](#running-behind-a-reverse-proxy)
- [Custom ports](#custom-ports)
- [Telemetry (OpenTelemetry)](#telemetry-opentelemetry)

---

## Requirements

A Java 25+ runtime ([Zulu](https://www.azul.com/downloads/?version=java-25)
or Temurin), or let the installer download one, plus `bash` (on Alpine, `apk add bash`). Nothing else — the bundle bakes in
the [Abundent Play 1.x fork](https://github.com/tsukhani/play1), app dependencies, precompiled classes, and the prebuilt SPA. The one
exception is the browser tool: the bundle and the Docker image leave out the Node.js its
Playwright driver runs on and Chromium (roughly 300 MB together), and JClaw downloads both
the first time an agent uses the browser, or ahead of time from **Settings → Agents &
Automation → Browser** (**Download now**). That needs access to nodejs.org and Playwright's
download servers; on a machine without it, set `PLAYWRIGHT_NODEJS_PATH` to an installed
Node.js and provide Chromium under `PLAYWRIGHT_BROWSERS_PATH`.

Some features need more software on the host. [Optional extras](#optional-extras) lists them.
To build JClaw from source instead, see [DEVELOPMENT.md](DEVELOPMENT.md).

---

## Quick Install (one-line)

Get JClaw running in one command. The installer downloads the self-contained
`jclaw-bundle.zip` from the latest GitHub Release, checks it against the release's
signed checksum list, verifies Java 25+ with the `jdk.incubator.vector` module (the bundle's
**only** prerequisite besides `bash`) — offering to download a self-contained Zulu JRE 25
into `~/.jclaw/jre` when none is found or the one found lacks the module — extracts it to `~/.jclaw`, and starts JClaw on
<http://localhost:9000>.

**macOS & Linux**

```bash
curl -fsSL https://raw.githubusercontent.com/tsukhani/jclaw/main/install.sh | sh
```

**Windows (PowerShell)**

```powershell
irm https://raw.githubusercontent.com/tsukhani/jclaw/main/install.ps1 | iex
```

> On Windows the bundle runs through **Git Bash** or **WSL** (the launcher is a
> bash script). The installer prefers Git Bash; if only WSL is present it
> launches there; if neither is found it installs and prints how to run it.

Releases after 0.19.25 are signed. `install.sh` refuses a bundle whose signature or
checksum does not verify; on a machine without `openssl` it warns and installs
unverified. `install.ps1` cannot check the signature yet, and says so after the download.

**Configuration** (optional environment variables):

| Variable | Default | Purpose |
| --- | --- | --- |
| `JCLAW_HOME` | `~/.jclaw` | Install directory |
| `JCLAW_VERSION` | `latest` | Pin a release tag, e.g. `v0.14.7` |
| `JCLAW_PORT` | `9000` | Port reported on launch |
| `JCLAW_NO_START` | — | Set to `1` to install without starting |
| `JCLAW_INSTALL_JRE` | — | Set to `1` to download the Zulu JRE without prompting when no Java 25+ with `jdk.incubator.vector` is found |
| `JCLAW_NO_JRE` | — | Set to `1` to never auto-install a JRE |
| `JCLAW_NO_RC_EDIT` | — | Set to `1` to generate completion scripts without editing your shell rc |
| `JCLAW_FORCE_REINSTALL` | — | Set to `1` to replace an existing install from scratch, **discarding** its database, workspace and credentials (otherwise an existing install is upgraded in place) |
| `JCLAW_BIN_DIR` | `~/.local/bin` | Where the `jclaw` command goes (`install.sh` only) |
| `JCLAW_BUNDLE_URL` | — | Install from a specific bundle URL (including `file://`) instead of GitHub Releases, skipping the release-signature check (`install.sh` only) |

```bash
# Pin a version and install without auto-starting:
curl -fsSL https://raw.githubusercontent.com/tsukhani/jclaw/main/install.sh \
  | JCLAW_VERSION=v0.14.7 JCLAW_NO_START=1 sh
```

---

## Managing JClaw

Once running, manage it with `jclaw status`, `jclaw stop`, `jclaw restart` from
any new shell — the installer puts the `jclaw` command on your `PATH` (via
`~/.local/bin`) and wires up `<TAB>` completion for bash and zsh. To remove JClaw
entirely, run `jclaw uninstall`: it stops the app, undoes the PATH and completion
wiring, and deletes `~/.jclaw`.

The `jclaw` command runs the install's `jclaw.sh`. If you unzipped a `jclaw-bundle.zip`
yourself, run `./jclaw.sh` in that folder instead. To build that bundle yourself, see
[Building a release bundle](DEVELOPMENT.md#building-a-release-bundle).

```bash
# Start
./jclaw.sh start

# Stop
./jclaw.sh stop

# View logs
./jclaw.sh logs

# Database: backup (--list to show them), restore, H2 recovery, health
./jclaw.sh backup
./jclaw.sh restore <zip | backup id>   # validated first; a running instance restarts
./jclaw.sh repair                      # rebuild a damaged database with H2's Recover tool
./jclaw.sh compact                     # remove the credentials' old copies from the database file
./jclaw.sh db-clean                    # delete what the last successful repair left behind
./jclaw.sh db-status                   # file size, health verdict, last backup

# Admin
./jclaw.sh secret                      # generate or rotate PLAY_SECRET in certs/.env, keeping the old one as PLAY_SECRET_PREVIOUS
./jclaw.sh reset                       # clear the admin password; the next launch asks for a new one
./jclaw.sh completion install          # bash + zsh tab completion
./jclaw.sh shim                        # re-link the `jclaw` command into ~/.local/bin
```

---

## Updating

`jclaw upgrade` (or **Settings → System → Maintenance** in the app) installs the
newest release in place. Your database, workspace, credentials, installed apps and
edited configuration are carried across; the database is backed up first; and a
release that fails to start is rolled back automatically. The download runs while
JClaw keeps serving, so only the swap itself is downtime. It refuses a release after
0.19.25 whose signature or checksum does not verify, or whose version is not the one
requested. `jclaw upgrade --check`
reports what's available without installing it; `--version <tag>` installs a
specific release (including an earlier one), and `--yes` skips the confirmation
prompt. Re-running the one-line installer
does the same thing — it hands off to `jclaw upgrade` when an install already
exists. Docker deployments upgrade the image instead
(`docker compose pull && docker compose up -d`), and a git clone uses `git pull`.
Release notes for every published version are on the
[Releases page](https://github.com/tsukhani/jclaw/releases); the upgrade panel
shows the newest release's notes too.

---

## Docker (Production)

The simplest way to run JClaw in production is with Docker Compose. The shipped `docker-compose.yml` pulls the prebuilt image from GHCR, publishes the app on **:9000** (HTTP) and **:9443** (HTTPS, TCP and UDP), and persists `data/`, `logs/`, `workspace/`, `skills/`, and `certs/` (the generated secret and TLS material) to the host so config and conversations survive restarts.

```bash
# Start in the background
docker compose up -d

# Follow logs
docker compose logs -f

# Stop and remove the container
docker compose down

# Run on custom ports (defaults: 9000, and 9443 for HTTPS)
JCLAW_PORT=8080 JCLAW_HTTPS_PORT=8443 docker compose up -d
```

That's it — no `.env` setup needed. On first boot the container's entrypoint generates a 64-character `PLAY_SECRET` (used to sign session cookies) and persists it to `./certs/.env`. Subsequent restarts read the same file, so existing user sessions survive across `docker compose down` / `up` cycles.

The stored third-party credentials (provider keys, channel tokens, MCP secrets) are encrypted under a key derived from `PLAY_SECRET`, so keep `./certs/.env` with `./data`: without it every credential reads as missing and has to be entered again. To rotate the secret, rename the `PLAY_SECRET` line in `./certs/.env` to `PLAY_SECRET_PREVIOUS` and restart the container. The entrypoint generates a new `PLAY_SECRET` beside it, JClaw re-encrypts the credentials under the new one, and its log says when the `PLAY_SECRET_PREVIOUS` line can be removed. All existing `PLAY_SESSION` cookies become invalid, which is the point.

If you'd rather pin the secret yourself (e.g. for multi-host deployments that need a shared cookie key, or rotation managed by your secret-store), drop a `.env` file alongside `docker-compose.yml` with `PLAY_SECRET=<value>` — Compose will forward it into the container and the entrypoint will defer to it instead of generating one. Rotate it the same way, with the outgoing value as `PLAY_SECRET_PREVIOUS=<value>` beside the new one.

You can also set `JCLAW_PORT` and `JCLAW_HTTPS_PORT` in `.env` alongside `docker-compose.yml` instead of passing them inline — Compose reads that file for variable interpolation in the YAML, which is how the port mappings pick them up.

The container runs in production mode — the Nuxt SPA is already built into the image, so no local Node.js, pnpm, or Play toolchain is required on the host. Open `http://localhost:9000` (or your custom port) once the container is healthy.

### Verifying the image

`docker compose up -d` pulls the `latest` tag, and a tag is only a pointer: whoever can push to the registry can move it. A digest cannot be moved, because a pull by digest returns only the content that hashes to it. So every release after v0.19.28 also publishes `IMAGE_DIGEST`, the digest of the image that release built, signed with the key that signs the installer downloads.

One command checks that signature, pulls exactly that image, and pins it. Run it in the directory holding `docker-compose.yml`:

```bash
curl -fsSL https://raw.githubusercontent.com/tsukhani/jclaw/main/docker-pull-verified.sh | sh
docker compose up -d

# A specific release rather than the latest:
curl -fsSL https://raw.githubusercontent.com/tsukhani/jclaw/main/docker-pull-verified.sh | JCLAW_VERSION=vX.Y.Z sh
```

It writes `JCLAW_IMAGE=ghcr.io/tsukhani/jclaw@sha256:…` to `.env`, which `docker-compose.yml` reads, and it refuses to pull anything if the signature is missing or wrong, or if the file names another release. On a machine with no `openssl` it cannot check the signature: it says so, then pulls and pins the published digest unverified. To upgrade a pinned install, run it again.

The same check by hand:

```bash
V=vX.Y.Z   # the release you want
curl -fsSLO "https://github.com/tsukhani/jclaw/releases/download/$V/IMAGE_DIGEST"
curl -fsSLO "https://github.com/tsukhani/jclaw/releases/download/$V/IMAGE_DIGEST.sig"
curl -fsSLO https://raw.githubusercontent.com/tsukhani/jclaw/main/release-signing.pub

# Must print "Verified OK". Then read the file: it must name the release you asked for.
openssl dgst -sha256 -verify release-signing.pub -signature IMAGE_DIGEST.sig IMAGE_DIGEST
cat IMAGE_DIGEST

# Put the line this prints in .env beside docker-compose.yml, then start.
awk '{print "JCLAW_IMAGE=" $2}' IMAGE_DIGEST
docker compose up -d
```

Either way this is opt-in: without `JCLAW_IMAGE`, Compose still pulls `latest` unchecked.

---

## Optional extras

JClaw runs without any of these. Each one adds a feature.

**Tesseract OCR** — required for text extraction from images, scanned PDFs,
and image-only PDFs via the `documents` tool. Apache Tika invokes the
`tesseract` binary as a subprocess; without it, image inputs return empty
text. A startup probe logs a WARN line at boot if tesseract is missing so
the missing capability is visible without trial and error.

```bash
# Debian / Ubuntu
sudo apt-get install tesseract-ocr

# macOS
brew install tesseract

# Windows
choco install tesseract
# or: winget install --id UB-Mannheim.TesseractOCR
```

On Windows, the UB Mannheim installer (the `winget` line) does not add
Tesseract to `PATH`, and OCR then silently returns empty text. Set
`ocr.tesseract.path` in `conf/application.conf` to the directory holding
`tesseract.exe` (e.g. `C:\Program Files\Tesseract-OCR`), not to the `.exe`.

Additional language packs install separately. The default is English
(`eng`); install `tesseract-ocr-fra`, `tesseract-ocr-jpn`, etc. for other
languages, then set them under **Settings → Image → OCR**
(e.g. `eng+fra+jpn`).

**Local Ollama** — required only if you want to bind agents to the
`ollama-local` LLM provider for self-hosted inference. JClaw seeds
`provider.ollama-local.baseUrl=http://localhost:11434/v1` at first boot,
so the provider is already listed in Settings without further wiring —
install Ollama on the host and pull a model to make it usable. A startup
probe logs INFO with the model count when the local server is reachable,
and WARN with an install hint when the server is reachable but broken;
a fresh install with no Ollama running stays silent (no spurious WARN
on every JVM start).

```bash
# Linux
curl https://ollama.com/install.sh | sh

# macOS
brew install ollama

# Windows — download the installer from https://ollama.com/download
```

After installing, pull a model:

```bash
ollama pull qwen2.5
```

Then open **Settings → Providers → LLM Providers**, open **Manage models**
on the `ollama-local` row, and either run **Discover Models** against
`http://localhost:11434/v1` or enter the model you pulled in the
**Add model** form (ID, display name, context window, max tokens). Bind an agent
to `ollama-local` from the Agent Edit page to start chatting against
your local model.

**LM Studio** — required only if you want to bind agents to the
`lm-studio` LLM provider. JClaw seeds
`provider.lm-studio.baseUrl=http://localhost:1234/v1` at first boot
so the provider is already listed under "Local" in Settings. Same
boot-time probe as ollama-local: INFO when reachable, WARN with a
launch hint when reachable-but-broken, silent (DEBUG) when LM Studio
isn't running.

LM Studio is a desktop application — install it from
[https://lmstudio.ai](https://lmstudio.ai) (macOS DMG, Windows
installer, or Linux AppImage). After launching, load a model in the
**My Models** tab, then switch to the **Server** tab and click
**Start Server**. The default port is 1234.

In **Settings → Providers → LLM Providers**, open **Manage models** on
the `lm-studio` row and either run **Discover Models** against
`http://localhost:1234/v1` or enter the model you loaded in the
**Add model** form. Bind an agent to
`lm-studio` from the Agent Edit page to use it.

**uv** — required only by the optional local sidecars (ASR, diarization,
TTS, image, video, fetch, stealth). JClaw launches each one with
`uv run serve.py`, and [uv](https://docs.astral.sh/uv/) provisions the
Python and packages it runs on, so no system Python is needed. The image
and video generation checks look for `uv` once and remember the answer,
so restart JClaw after installing it.

**ffmpeg** — required for local Whisper transcription (it converts audio
to the PCM Whisper reads), speaker diarization, shrinking large or
lossless audio attachments before they are sent to a model, encoding
spoken replies for the channel that plays them, and sampling frames for
video interpretation. The Docker image already includes it.

```bash
# Debian / Ubuntu
sudo apt-get install ffmpeg

# macOS
brew install ffmpeg
```

---

## Browser-trusted HTTPS

The container also exposes HTTPS on **:9443** (with HTTP/3 over the same UDP port) using a self-signed TLS cert generated at `certs/host.cert` on first boot. HTTPS works as-is but browsers show a cert-warning interstitial, and Chrome refuses HTTP/3 entirely — QUIC requires the cert to be in the system trust store. To get browser-trusted HTTPS plus working HTTP/3, sign the cert with [mkcert](https://github.com/FiloSottile/mkcert)'s local CA from your host. Install mkcert via your platform's package manager:

```bash
# macOS
brew install mkcert

# Debian / Ubuntu (22.04+)
sudo apt install mkcert libnss3-tools

# Fedora / RHEL
sudo dnf install mkcert nss-tools

# Arch
sudo pacman -S mkcert nss

# Windows
choco install mkcert
# or: scoop bucket add extras && scoop install mkcert
```

For older distros that don't package mkcert, grab the prebuilt binary from [the mkcert releases page](https://github.com/FiloSottile/mkcert/releases) and install `libnss3-tools` (Debian/Ubuntu) or `nss-tools` (Fedora) separately so mkcert can register with Firefox.

Then trust the local CA, regenerate the cert, and restart the container:

```bash
sudo mkcert -install             # adds mkcert's CA to the system trust store (and Firefox NSS if installed)
./jclaw.sh https                 # regenerates certs/host.cert + host.key, signed by the CA
docker compose restart jclaw     # JVM reloads the new cert at boot
```

Subsequent `docker compose up -d` calls reuse the existing cert — you only need to re-run `./jclaw.sh https` after rotating mkcert's CA or deleting the `certs/` directory. On a host install, `./jclaw.sh no-https` deletes the cert+key (the next start boots HTTP/1.1 only); in the container the entrypoint self-signs a fresh pair on the next start, so HTTPS stays on. `conf/application.conf` is never modified by either command.

---

## Running Behind a Reverse Proxy

JClaw already sends the right cache headers, and a proxy that rewrites or ignores them is the one remaining way to serve a stale SPA. The app sends `Cache-Control: no-cache` on the HTML shell (so it always revalidates) and `public, max-age=31536000, immutable` on the content-hashed `_nuxt/` chunks (so they never do). That split only works end-to-end if your proxy leaves it alone.

If you front JClaw with nginx, Caddy, Traefik, or a CDN:

- **Don't cache HTML.** The shell must revalidate on every load, or the browser keeps an `index.html` pointing at chunk hashes that no longer exist.
- **Pass origin `Cache-Control` through unmodified.** Don't add a blanket `expires` or `proxy_cache_valid` covering all responses.
- **If you do enable proxy caching, exclude `/` and `/_nuxt/builds/`.** The build manifest under `builds/` advertises the current build id; caching it defeats new-deploy detection. Everything else under `_nuxt/` is content-hashed and safe to cache hard.

Diagnosing a suspected stale SPA: `curl -s localhost:9000/api/status | jq .spaBuildId` reports the build id the server is actually serving. Compare it against the id the browser has (DevTools → Network → `builds/latest.json`). Matching ids mean the browser is current and the problem is elsewhere; differing ids mean a caching layer is holding an old shell.

```nginx
location / {
    proxy_pass http://127.0.0.1:9000;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    # Let the app decide cacheability — it already differentiates the
    # always-revalidate shell from the immutable hashed chunks.
    proxy_cache off;
}
```

---

## Custom Ports

Use `--backend-port` with any `jclaw.sh` mode; `--frontend-port` applies to dev mode only (it sets the Nuxt dev server port). The frontend reads the backend port via the `JCLAW_BACKEND_PORT` environment variable at startup — no files are modified.

```bash
# Dev mode with custom ports
./jclaw.sh --dev --backend-port 8080 --frontend-port 4000 start

# Bare start with custom backend port
./jclaw.sh --backend-port 8080 start
```

---

## Telemetry (OpenTelemetry)

JClaw exports traces and metrics over OTLP from inside the process: HTTP server spans named from the route, one `turn` span per agent turn with the model call (GenAI semantic conventions), its HTTP call and every JDBC statement beneath it, plus `gen_ai.client.*`, `jclaw.turn.segment.duration` and `jvm.*` metrics. Nothing leaves the process until you turn it on.

1. Run a collector, e.g. `docker run --rm -p 4318:4318 otel/opentelemetry-collector-contrib` with an OTLP receiver.
2. Settings → System → Telemetry: enable, set the endpoint (default `http://localhost:4318`), press **Send test span**. Endpoint, headers, protocol and sampling all change live; no restart.

**Optional: the OpenTelemetry Java agent.** Attach it when you also want Hibernate, Lucene and every other library the agent knows:

```bash
# Host install: add to conf/application.conf and restart
%prod.javaagent.path=/opt/otel/opentelemetry-javaagent.jar

# Container: compose it into the JVM options
docker run -e JAVA_TOOL_OPTIONS="-javaagent:/opt/otel/opentelemetry-javaagent.jar" \
           -e OTEL_EXPORTER_OTLP_ENDPOINT=http://collector:4318 jclaw
```

With the agent attached its `OTEL_*` settings apply and are read at JVM start; the Telemetry panel says so and its keys become read-only. The app then yields HTTP server, OkHttp, JDBC and JVM telemetry to the agent — its request spans are named from the Play route — and keeps emitting the turn and GenAI spans the agent cannot produce. Verified with agent 2.31.1 beside the framework's own enhancer agent on Netty 4.2.
