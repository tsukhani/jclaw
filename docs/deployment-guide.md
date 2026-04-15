# Deployment Guide

How JClaw is built, packaged, and shipped.

## Artifacts

| Artifact | Source | Consumers |
|---|---|---|
| `dist/jclaw.zip` | `play dist` + injected SPA | Manual `./jclaw.sh --deploy` flow, GitHub Releases. |
| `ghcr.io/tsukhani/jclaw:<tag>` | Multi-stage `Dockerfile` | `docker-compose.yml`, anywhere a container runtime is available. |
| GitHub Release | Jenkins `Release` stage | End-users downloading the dist zip. |

## Build pipeline (Jenkins)

`Jenkinsfile` declares a pipeline on a node labeled `JDK25` + `node-22`.

```
Setup ──► Build (parallel BE + FE) ──► Test (parallel BE + FE)
      ──► Package ──► [Release, Cleanup Old Releases] (if RELEASE=true)
```

Key steps:

- **Setup** — `play version`, `corepack prepare pnpm@10.33.0 --activate`, `pnpm install --frozen-lockfile`.
- **Build.Backend** — `play deps --sync && play precompile`.
- **Build.Frontend** — `(cd frontend && npx nuxi generate)`.
- **Test.Backend** — `play autotest` + JUnit XML publish from `test-result/*.xml`.
- **Test.Frontend** — `(cd frontend && pnpm test)`.
- **Package** — `play dist`, then the SPA (`frontend/.output/public`) is unzipped into the dist directory as `public/spa/` and re-zipped as `dist/jclaw.zip`. Archived as a Jenkins artifact.
- **Release** (when param `RELEASE=true`): creates git tag `v<application.version>`, deletes any pre-existing GitHub Release at that tag, uploads `dist/jclaw.zip`, then builds and pushes `ghcr.io/tsukhani/jclaw:<tag>` and `:latest`.
- **Cleanup** — Keeps the 5 most recent GitHub Releases; prunes old GHCR versions while preserving whatever `:latest` currently points to.

## Shell deploy (no Docker)

```bash
# Packages, unzips to <dir>/jclaw/, installs deps, builds frontend, starts both.
./jclaw.sh --deploy /tmp start
./jclaw.sh --deploy /tmp stop
./jclaw.sh --deploy /tmp logs
```

Custom ports: `./jclaw.sh --deploy /opt --backend-port 8080 --frontend-port 4000 start`. The script updates the frontend API proxy to the specified backend port.

Once deployed, subsequent starts skip packaging:

```bash
./jclaw.sh start
./jclaw.sh stop
./jclaw.sh logs
```

## Docker (preferred in prod)

Shipped `docker-compose.yml`:

```yaml
services:
  jclaw:
    image: ghcr.io/tsukhani/jclaw:latest
    pull_policy: always
    ports:
      - "9000:9000"
    volumes:
      - ./data:/app/data
      - ./logs:/app/logs
      - ./workspace:/app/workspace
      - ./skills:/app/skills
    restart: unless-stopped
    healthcheck:
      test: ["CMD-SHELL", "bash -c ': >/dev/tcp/127.0.0.1/9000' || exit 1"]
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

The image runs `play run --%prod`. The SPA is already baked in — no Node/pnpm/Play toolchain required on the host.

### Persisted volumes

| Volume | Purpose |
|---|---|
| `./data` | H2 DB file + `attachments/` blob storage. |
| `./logs` | Runtime logs. |
| `./workspace` | Per-agent workspace files. |
| `./skills` | Global skills registry (authoring output). |

### Healthcheck note

The healthcheck uses bash's `/dev/tcp` to probe `:9000` — avoids pulling `curl`/`wget` back into the runtime image. `start_period: 60s` gives Play time to precompile, seed the DB, and bind on a cold boot.

## Dockerfile stages

1. **`frontend-build`** (`node:22-slim`) — corepack + pnpm + `nuxi generate`.
2. **`backend-build`** (`azul/zulu-openjdk:25`) — downloads the latest `tsukhani/play1` release, `play deps --sync`, installs Playwright Chromium into `/opt/pw-browsers`, copies the SPA into `public/spa/`, `play precompile`, and creates `data/` + `logs/`.
3. **`runtime`** (`azul/zulu-openjdk:25-jre-headless-latest`) — installs the Playwright-Chromium shared libs plus `python3` (for Play's Python CLI wrapper). Copies `/opt` and `/app` wholesale from the backend-build stage so the `/opt/play → /opt/play-<version>` symlink is preserved. `EXPOSE 9000`. `CMD ["play", "run", "--%prod"]`.

## Production configuration

- Template: `conf/application.prod.example.conf`.
- The shipped `application.conf` keeps DB on H2 file DB even in prod — PostgreSQL config is commented-in template (`%prod.db.url=jdbc:postgresql://…`). Switch and provide `DB_PASSWORD` env var before promoting.
- `jpa.ddl=update` in prod (`%prod.jpa.ddl=update`). Explicit tradeoff for pre-1.0 — additive schema only; renames/type changes need manual intervention.
- Pool: `%prod.db.pool.maxSize=30`, `%prod.db.pool.timeout=5000`.
- Play pool: `%prod.play.pool=20` (1 thread in dev).
- Logging: `%prod.application.log.path=/log4j2-prod.xml`.

## Reverse proxy

Template at `conf/nginx.example.conf`. Requirements if fronting with nginx:

- `proxy_buffering off` for SSE endpoints (`/api/events`, `/api/chat/stream`).
- `proxy_set_header Host $host` so Play can compute self-URLs correctly.
- Pass cookies through (don't strip the `PLAY_SESSION` cookie).

## Release cadence

- Each commit bumps `application.version` in `conf/application.conf` (per post-coding workflow).
- Jenkins is triggered on push; release builds are gated by the `RELEASE` parameter.
- Tags: `v0.X.Y`. Latest observed on `main`: `v0.7.18`.

## Prune policy (automated)

From the Jenkins `Cleanup Old Releases` stage:
- Keep 5 most recent GitHub Releases (`gh release list … | jq .[5:]`).
- Keep 5 most recent GHCR versions, NEVER deleting whichever version `:latest` currently points to.
