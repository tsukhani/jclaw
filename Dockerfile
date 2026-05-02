# ── Stage 1: Build frontend SPA ──────────────────────────────────────────────
FROM node:24-slim AS frontend-build

# Corepack reads the pnpm version from frontend/package.json's `packageManager`
# field — single source of truth, no parallel pin here.
RUN corepack enable

WORKDIR /app/frontend
COPY frontend/package.json frontend/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile

COPY frontend/ ./
RUN npx nuxi generate

# ── Stage 2: Resolve + precompile with full JDK ──────────────────────────────
FROM azul/zulu-openjdk:25.0.3 AS backend-build

# Pin the Play fork via .play-version (single source of truth shared with
# .devcontainer/Dockerfile). Renovate auto-bumps from tsukhani/play1 releases
# via renovate.json5's customManagers block.
COPY .play-version /tmp/play-version

RUN PLAY_VERSION=$(tr -d '[:space:]' < /tmp/play-version) && \
    apt-get update && apt-get install -y --no-install-recommends \
        curl unzip python3 && \
    curl -fsSL -L "https://github.com/tsukhani/play1/releases/download/v${PLAY_VERSION}/play-${PLAY_VERSION}.zip" \
        -o /tmp/play.zip && \
    unzip -q /tmp/play.zip -d /opt && \
    ln -s /opt/play-${PLAY_VERSION} /opt/play && \
    rm /tmp/play.zip /tmp/play-version && \
    rm -rf /var/lib/apt/lists/*

ENV PLAY_HOME=/opt/play
ENV PATH="${PLAY_HOME}:${PATH}"
ENV PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1

WORKDIR /app

# Resolve dependencies off just the manifest so this expensive layer
# (~hundreds of MB of Ivy modules + Playwright driver jars) caches across
# every change that leaves dependencies.yml alone. Narrower than copying
# all of conf/ — application.conf carries application.version, which bumps
# every release and would otherwise bust this layer.
COPY conf/dependencies.yml conf/dependencies.yml
RUN play deps --sync

# Playwright browser install depends on the jars from `play deps --sync`,
# not on app source — kept here to cache alongside deps.
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

RUN play precompile && mkdir -p data logs

# ── Stage 3: Runtime on JRE headless ─────────────────────────────────────────
FROM azul/zulu-openjdk:25-jre-headless-latest AS runtime

LABEL org.opencontainers.image.source=https://github.com/tsukhani/jclaw

# Playwright/Chromium shared libs, python3 for Play's CLI wrapper,
# tesseract-ocr for DocumentsTool's OCR path (image-only PDFs, scanned
# documents), and openssl so the entrypoint can self-sign a PEM cert
# when the operator hasn't supplied one in /app/certs. apt resolves the
# right arch under buildx's per-platform build; non-English language
# packs install separately as tesseract-ocr-<lang>.
RUN apt-get update && apt-get install -y --no-install-recommends \
        python3 \
        openssl \
        tesseract-ocr \
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

# Copy /opt wholesale so the /opt/play → /opt/play-<version> symlink survives.
COPY --from=backend-build /opt /opt
COPY --from=backend-build /app /app

# Entrypoint resolves PLAY_SECRET on boot — see the script header for the
# resolution order. `--chmod` sets the executable bit at copy time, avoiding
# a separate RUN layer.
COPY --chmod=755 docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]

WORKDIR /app

# 9000: HTTP/1.1. 9443: HTTP/2 over TLS (TCP) and HTTP/3 over QUIC (UDP).
# Both 9443 entries are required — Compose must publish UDP separately for
# QUIC packets to reach the JVM's UDP listener.
EXPOSE 9000
EXPOSE 9443/tcp
EXPOSE 9443/udp

# Heap: asymmetric Xms 512m / Xmx 2g so an idle deploy doesn't commit 2 GB at
# boot. Override per-flag with `-e JCLAW_JVM_XMS` / `-e JCLAW_JVM_XMX`, or pin
# both with `-e JCLAW_JVM_HEAP=4g`.
#
# Shell-form CMD is required for env-var expansion. `exec` replaces the sh
# process with the JVM so SIGTERM reaches Play directly, preserving its
# graceful-shutdown hooks. Play 1.x's CLI passes -X args through to the JVM.
CMD ["sh", "-c", "exec play run --%prod -Xms${JCLAW_JVM_XMS:-${JCLAW_JVM_HEAP:-512m}} -Xmx${JCLAW_JVM_XMX:-${JCLAW_JVM_HEAP:-2g}}"]
