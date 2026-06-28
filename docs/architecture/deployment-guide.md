# Deployment Guide

How JClaw is built, packaged, and shipped.

## Artifacts

| Artifact | Source | Consumers |
|---|---|---|
| `dist/jclaw.zip` | `./jclaw.sh dist` (== gradle `playDist`) | Source distribution; target host needs Java 25 + Gradle + the Play fork. |
| `dist/jclaw-bundle.zip` | `./jclaw.sh bundle` (== gradle `playBundle`) — self-contained: precompiled bytecode, framework jar + lib, Gradle-resolved deps, `conf/`, `public/` with the staged SPA, and a `./play` launcher | One-line installers + GitHub Releases; runs with only a Java 25 JRE. |
| `ghcr.io/tsukhani/jclaw:<tag>` | Multi-stage `Dockerfile` | `docker-compose.yml`, anywhere a container runtime is available. |
| GitHub Release | Jenkins `Release` stage | End-users downloading the bundle zip (or the installer). |

## Build pipeline (Jenkins)

`Jenkinsfile` runs on `agent any` with `tools { jdk 'JDK25'; nodejs 'node-24' }`.

```
Setup ──► Build (parallel BE + FE) ──► Test (parallel BE + FE) ──► Sonar
      ──► Package ──► [Release, Publish Dev Container, Cleanup] (gated by params)
```

Key steps:

- **Setup** — check Java/Play versions, `corepack enable`, `pnpm install --frozen-lockfile`.
- **Build.Backend** — `play precompile` (Gradle resolves deps transitively; PF-90 dropped the legacy `play deps --sync`).
- **Build.Frontend** — `(cd frontend && npx nuxi generate)`.
- **Test.Backend** — `play autotest` + JaCoCo XML; JUnit XML published from `test-result/*.xml`.
- **Test.Frontend** — `(cd frontend && pnpm test --coverage)` (Vitest + lcov).
- **Sonar** — `./gradlew sonar -Dsonar.projectVersion=v${appVersion}` (project key `abundent:jclaw`), with the quality-gate binding.
- **Package** — `./jclaw.sh dist` + `./gradlew playBundle` write `dist/jclaw.zip` and `dist/jclaw-bundle.zip`, archived as Jenkins artifacts. The SPA **is** baked into the bundle — `nuxi generate` output is copied into `public/spa/` before packaging.
- **Release** (param `RELEASE=true`): create git tag `v<application.version>`, refresh the GitHub Release with both zips, then multi-arch `buildx` push of `ghcr.io/tsukhani/jclaw:<tag>` + `:latest` for `linux/amd64` + `linux/arm64` (QEMU binfmt for cross-arch).
- **Publish Dev Container** (param `PUBLISH_DEVCONTAINER=true`): buildx push `ghcr.io/tsukhani/jclaw-devcontainer:latest`.
- **Cleanup** (on release): keep the 5 most recent GitHub Releases + 5 most recent GHCR versions (never deleting whatever `:latest` points to); prune BuildKit cache older than 30 days.

## Bare-metal deploy (no Docker)

Build a self-contained bundle, unzip it wherever you want to install, and start it in place:

```bash
./jclaw.sh bundle                         # writes dist/jclaw-bundle.zip (self-contained, JRE-only)
unzip -o dist/jclaw-bundle.zip -d /opt
cd /opt/jclaw && ./jclaw.sh start         # start; stop; status; logs
```

Or use the one-line installer (downloads the latest bundle, installs to `~/.jclaw`, wires the `jclaw` PATH shim + shell completion):

```bash
curl -fsSL https://raw.githubusercontent.com/tsukhani/jclaw/main/install.sh | sh
```

Custom port: `./jclaw.sh --backend-port 8080 start`.

## Docker (preferred in prod)

Shipped `docker-compose.yml`:

```yaml
services:
  jclaw:
    image: ghcr.io/tsukhani/jclaw:latest
    pull_policy: always
    ports:
      - "${JCLAW_PORT:-9000}:9000"
      - "${JCLAW_HTTPS_PORT:-9443}:9443/tcp"   # HTTP/2 over TLS
      - "${JCLAW_HTTPS_PORT:-9443}:9443/udp"   # HTTP/3 over QUIC
    volumes:
      - ./data:/app/data
      - ./logs:/app/logs
      - ./workspace:/app/workspace
      - ./skills:/app/skills
      - ./certs:/app/certs
    restart: unless-stopped
    healthcheck:
      test: ["CMD-SHELL", "curl -fsS http://127.0.0.1:9000/api/status || exit 1"]
      interval: 30s
      timeout: 5s
      retries: 3
      start_period: 60s
```

Start / stop:

```bash
docker compose up -d
docker compose logs -f
docker compose down
```

On first boot the entrypoint generates a 64-char `PLAY_SECRET` (persisted to `./data/.play-secret`) and a self-signed TLS cert at `certs/host.cert` if none exists, then runs the backend in prod mode with the 9443 HTTPS listener. The SPA is baked into the image — no Node/pnpm/Play toolchain on the host. For browser-trusted HTTPS + working HTTP/3, sign the cert with mkcert's local CA (`./jclaw.sh https`) and restart.

### Persisted volumes

| Volume | Purpose |
|---|---|
| `./data` | H2 DB file, `attachments/` blobs, `jclaw-lucene/` index, `.play-secret`. |
| `./logs` | Runtime logs. |
| `./workspace` | Per-agent workspace files (Standing Orders under `main/`). |
| `./skills` | Global skills registry (authoring output). |
| `./certs` | TLS cert + key (`host.cert` / `host.key`). |

## Dockerfile stages

1. **bundle stage** (`azul/zulu-openjdk:25.0.3`) — Node 24 (NodeSource) + corepack + Gradle 9.5.0; downloads the `tsukhani/play1` release pinned in `.play-version`, runs `pnpm install` + `nuxi generate` (SPA → `public/spa/`), `play precompile`, and `gradle playBundle` to produce the self-contained bundle.
2. **chromium stage** (`azul/zulu-openjdk:25.0.3`) — installs Playwright Chromium into `/opt/pw-browsers`.
3. **runtime** (`ubuntu:26.04` + Zulu 25 JRE) — copies the unpacked bundle + Chromium libs; bakes `workspace/main/` (SOUL.md, IDENTITY.md, USER.md, BOOTSTRAP.md, AGENT.md) as the main-agent seed (the `./workspace` bind-mount shadows it at runtime); `EXPOSE 9000 9443/tcp 9443/udp`; entrypoint auto-provisions `PLAY_SECRET` + certs, then `./play run --%prod --https.port=9443`.

## Production configuration

All environment-specific config lives in the single `conf/application.conf` via Play's `%prod.` / `%test.` line prefixes — there is no separate `application.prod.conf`. The only operator-managed value outside the file is `application.secret`, which resolves from the `PLAY_SECRET` env var (`./jclaw.sh secret` / the container entrypoint writes it).

- The shipped `application.conf` keeps DB on H2 file even in prod — PostgreSQL is a commented-in template (`%prod.db.url=jdbc:postgresql://…`). Switch and provide `DB_PASSWORD` before promoting.
- `jpa.ddl=update` in prod (`%prod.jpa.ddl=update`). Explicit pre-1.0 tradeoff — additive schema only; renames/type changes need manual intervention.
- Pool: `%prod.db.pool.maxSize=64`, `%prod.db.pool.timeout=5000` (raised for SSE chat streams that hold a JPA connection).
- Logging: `%prod.application.log.path=/log4j2-prod.xml`.
- HTTPS: off by default; `./jclaw.sh https` + a valid cert enables the 9443 listener (HTTP/2 + HTTP/3) at runtime via `-Dhttps.port=9443`. `conf/application.conf` is never modified by the toggle.

## Reverse proxy

If fronting with nginx:
- `proxy_buffering off` for SSE endpoints (`/api/events`, `/api/chat/stream`).
- `proxy_set_header Host $host` so Play computes self-URLs correctly.
- Pass cookies through (don't strip the `PLAY_SESSION` cookie).

## Release cadence

- `/deploy` bumps `application.version` in `conf/application.conf`, creates the signed release commit + tag (`v0.X.Y`), and pushes to both remotes.
- Jenkins triggers on push; release builds (tag + GitHub Release + GHCR) are gated by the `RELEASE` parameter.
- Latest observed on `main`: `v0.14.47`.
