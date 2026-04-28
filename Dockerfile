# ── Stage 1: Build frontend SPA ──────────────────────────────────────────────
FROM node:24-slim AS frontend-build

# Corepack resolves the pnpm version from frontend/package.json's
# `packageManager` field at `pnpm install` time — no hardcoded version
# here, so a bump in package.json (the single source of truth) doesn't
# need a parallel edit to this file. Download happens inside the install
# layer; invalidation follows package.json + pnpm-lock.yaml as before.
RUN corepack enable

WORKDIR /app/frontend
COPY frontend/package.json frontend/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile

COPY frontend/ ./
RUN npx nuxi generate

# ── Stage 2: Resolve + precompile with full JDK ──────────────────────────────
FROM azul/zulu-openjdk:25.0.3 AS backend-build

# Pin the Play fork version. Previously this stage queried
# tsukhani/play1's `releases/latest` at build time, so the same source +
# same Dockerfile produced different images depending on when the build
# ran — and could silently drift apart from .devcontainer/Dockerfile,
# which is pinned to the same version. Keep prod and dev container
# aligned. Override with --build-arg PLAY_VERSION=x.y.z when bumping;
# bump intentionally, not by accident.
#
# 1.12.3 is the first release that ships the Python launcher's
# loadDotEnv (which lets the bootstrap service in docker-compose.yml
# write PLAY_SECRET into ./.env) and the .env-aware `play secret`
# command. Earlier versions silently mutate conf/application.conf
# inside the container, which the published image discards on exit.
ARG PLAY_VERSION=1.12.3

RUN apt-get update && apt-get install -y --no-install-recommends \
        curl unzip python3 && \
    curl -fsSL -L "https://github.com/tsukhani/play1/releases/download/v${PLAY_VERSION}/play-${PLAY_VERSION}.zip" \
        -o /tmp/play.zip && \
    unzip -q /tmp/play.zip -d /opt && \
    ln -s /opt/play-${PLAY_VERSION} /opt/play && \
    rm /tmp/play.zip && \
    rm -rf /var/lib/apt/lists/*

ENV PLAY_HOME=/opt/play
ENV PATH="${PLAY_HOME}:${PATH}"
ENV PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1

WORKDIR /app

# Resolve dependencies off just the manifest so this expensive layer
# (~hundreds of MB of Ivy modules + Playwright driver jars) caches across
# every change that leaves dependencies.yml alone. Narrower than copying
# all of conf/ because conf/application.conf carries application.version,
# which bumps on every release and would otherwise bust this layer.
COPY conf/dependencies.yml conf/dependencies.yml
RUN play deps --sync

# Playwright browser install depends on the jars brought in by the step
# above, not on app source — keep it here so it caches alongside deps.
RUN java -cp "$(echo lib/playwright-*.jar lib/driver-*.jar | tr ' ' ':')" \
        com.microsoft.playwright.CLI install chromium

# Volatile source last — these COPY layers invalidate on most changes.
COPY app/ app/
COPY conf/ conf/
COPY public/ public/
COPY skills/ skills/
COPY workspace/ workspace/
COPY .gitignore .distignore ./
COPY --from=frontend-build /app/frontend/.output/public public/spa/

RUN play precompile

RUN mkdir -p data logs

# ── Stage 3: Runtime on JRE headless ─────────────────────────────────────────
FROM azul/zulu-openjdk:25-jre-headless-latest AS runtime

LABEL org.opencontainers.image.source=https://github.com/tsukhani/jclaw

# Playwright / Chromium shared libs + python3 for Play's CLI wrapper
RUN apt-get update && apt-get install -y --no-install-recommends \
        python3 \
        libasound2 libatk-bridge2.0-0 libatk1.0-0 libatspi2.0-0 \
        libcairo2 libcups2 libdbus-1-3 libdrm2 libgbm1 libglib2.0-0 \
        libnspr4 libnss3 libpango-1.0-0 libwayland-client0 libx11-6 \
        libxcb1 libxcomposite1 libxdamage1 libxext6 libxfixes3 \
        libxkbcommon0 libxrandr2 && \
    rm -rf /var/lib/apt/lists/*

ENV PLAY_HOME=/opt/play
ENV PATH="${PLAY_HOME}:${PATH}"
ENV PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1

# Copy Play framework + prewarmed browsers + precompiled app from the build stage.
# Copy /opt wholesale so the /opt/play → /opt/play-<version> symlink is preserved.
COPY --from=backend-build /opt /opt
COPY --from=backend-build /app /app

WORKDIR /app

EXPOSE 9000

CMD ["play", "run", "--%prod"]
