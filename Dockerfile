# JClaw runtime container.
#
# Consumes a pre-built dist/jclaw.zip (produced by `./jclaw.sh dist`) —
# the container ships zero source code, no javac, no Node, no pnpm. Per
# Play 1.x's deployment.textile § "Deploying without source code", a
# precompiled tree (precompiled/) plus conf/, lib/, public/ is all the
# JVM needs at runtime; -Dprecompiled=true tells Play to load the
# precompiled bytecode verbatim and skip both the Java and template
# compile passes (and to refuse to start if precompiled/ is missing).
#
# Build context requirement: dist/jclaw.zip MUST exist before
# `docker build` runs. CI does this via the Jenkinsfile's Package
# stage; for local builds, run `./jclaw.sh dist` first.
#
# ── Stage 1: Stage the dist contents ────────────────────────────────────────
# Tiny stage that just unpacks dist/jclaw.zip so the runtime stage's COPY
# pulls in a flat /app tree. unzip is in the base image and isn't needed
# at runtime, so doing the extraction in a throwaway stage keeps it out
# of the final image. The zip's inner prefix is normalized to "jclaw/"
# by do_dist regardless of the workspace basename, so the mv below
# always finds it.
FROM ubuntu:26.04 AS dist-stage

RUN apt-get update && \
    apt-get install -y --no-install-recommends unzip && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /staging
COPY dist/jclaw.zip /staging/jclaw.zip
RUN unzip -q jclaw.zip && rm jclaw.zip

# ── Stage 2: Runtime on Ubuntu 26.04 + Zulu JRE 25 ──────────────────────────
# Build atop ubuntu:26.04 rather than azul/zulu-openjdk:25-jre-headless-latest
# (which is pinned to Ubuntu 22.04 / jammy) so apt-shipped tesseract-ocr is
# 5.5.x rather than jammy's frozen 4.1.1. Smaller too: ubuntu:26.04 is ~160 MB
# vs the Zulu image's ~500 MB. Zulu's JRE comes from Azul's apt repo at the
# 25.0.3 line; lands at /usr/lib/jvm/zulu25-ca-amd64 with a /usr/lib/jvm/zulu25
# symlink, matching the upstream image's layout.
FROM ubuntu:26.04 AS runtime

LABEL org.opencontainers.image.source=https://github.com/tsukhani/jclaw

ENV DEBIAN_FRONTEND=noninteractive

# Pin the Play fork via .play-version COPY'd from the build context (single
# source of truth shared with .devcontainer/Dockerfile and dependencies.yml).
# Renovate auto-bumps from tsukhani/play1 releases via renovate.json5's
# customManagers block.
COPY .play-version /tmp/play-version

# Single RUN so apt lists are dropped in the same layer the packages were
# resolved from. Two apt-get update calls because the Azul sources file
# doesn't exist until midway through, and Play CLI is a separate download.
#
# Ubuntu 24.04+ ran the time_t 64-bit ABI transition — the t64 suffix on
# libasound2, libatk*, libatspi*, libcups2, libglib2 below is load-bearing;
# the un-suffixed jammy-era names no longer resolve. Other Playwright libs
# kept their original names through the transition.
#
# Runtime contents:
#   - zulu25-jre-headless         JRE for the Play JVM
#   - python3                     wrapper script for `play` CLI
#   - openssl                     entrypoint self-signs a PEM cert
#   - tesseract-ocr               DocumentsTool's OCR path
#   - lib(asound|atk|...)t64+ etc Playwright/Chromium shared libs
#   - unzip                       Play CLI uses unzip during `play deps`
#                                 even when deps already resolved (no-op
#                                 cost is small but the binary must exist)
#   - curl + ca-certificates      Play CLI download below
#
# No javac, no Node/pnpm, no Maven — the dist tarball ships precompiled
# bytecode and a built SPA, neither is needed at runtime.
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        ca-certificates curl gnupg unzip && \
    curl -fsSL https://repos.azul.com/azul-repo.key \
        | gpg --dearmor -o /usr/share/keyrings/azul.gpg && \
    echo "deb [signed-by=/usr/share/keyrings/azul.gpg] https://repos.azul.com/zulu/deb stable main" \
        > /etc/apt/sources.list.d/zulu.list && \
    apt-get update && \
    apt-get install -y --no-install-recommends \
        zulu25-jre-headless \
        python3 \
        openssl \
        tesseract-ocr \
        libasound2t64 libatk-bridge2.0-0t64 libatk1.0-0t64 libatspi2.0-0t64 \
        libcairo2 libcups2t64 libdbus-1-3 libdrm2 libgbm1 libglib2.0-0t64 \
        libnspr4 libnss3 libpango-1.0-0 libwayland-client0 libx11-6 \
        libxcb1 libxcomposite1 libxdamage1 libxext6 libxfixes3 \
        libxkbcommon0 libxrandr2 && \
    PLAY_VERSION=$(tr -d '[:space:]' < /tmp/play-version) && \
    curl -fsSL -L "https://github.com/tsukhani/play1/releases/download/v${PLAY_VERSION}/play-${PLAY_VERSION}.zip" \
        -o /tmp/play.zip && \
    unzip -q /tmp/play.zip -d /opt && \
    ln -s /opt/play-${PLAY_VERSION} /opt/play && \
    rm /tmp/play.zip /tmp/play-version && \
    rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/usr/lib/jvm/zulu25
ENV PLAY_HOME=/opt/play
ENV PATH="${PLAY_HOME}:${PATH}"
ENV PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1

# Pull the unpacked dist tree into /app. The stage's contents are at
# /staging/jclaw/* — a single COPY moves the entire precompiled+conf+lib+
# public+skills+workspace tree into place in one layer.
COPY --from=dist-stage /staging/jclaw/ /app/

# Install Playwright browsers atop the JARs that came with the dist's
# lib/. Done at runtime-stage build time (not at container start) so the
# browser binary is baked into the image. Same classpath shape as the
# pre-zip build used.
RUN java -cp "$(echo /app/lib/playwright-*.jar /app/lib/driver-*.jar | tr ' ' ':')" \
        com.microsoft.playwright.CLI install chromium

# Ensure operator-data dirs exist for first-run; both end up empty in
# the image and are populated at container start (data/ via H2, logs/
# via Play's log4j2 config).
RUN mkdir -p /app/data /app/logs

# Entrypoint resolves PLAY_SECRET on boot — see the script header for
# the resolution order. `--chmod` sets the executable bit at copy time,
# avoiding a separate RUN layer.
COPY --chmod=755 docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]

WORKDIR /app

# 9000: HTTP/1.1. 9443: HTTP/2 over TLS (TCP) and HTTP/3 over QUIC (UDP).
# Both 9443 entries are required — Compose must publish UDP separately for
# QUIC packets to reach the JVM's UDP listener.
EXPOSE 9000
EXPOSE 9443/tcp
EXPOSE 9443/udp

# Heap: asymmetric Xms 512m / Xmx 2g so an idle deploy doesn't commit 2 GB
# at boot. Override per-flag with `-e JCLAW_JVM_XMS` / `-e JCLAW_JVM_XMX`,
# or pin both with `-e JCLAW_JVM_HEAP=4g`.
#
# `-Dprecompiled=true` is the load-bearing flag — per Play 1.x's
# deployment.textile § "Step 3 — start in precompiled mode", it forces
# prod mode and skips both the Java and template compile passes, loading
# /app/precompiled/ verbatim. --%prod stays alongside as defense-in-depth
# in case a future Play release decouples the implication.
#
# `play run` (foreground) instead of `play start` (background) so the
# container's main process IS the JVM, SIGTERM reaches Play directly,
# and the container doesn't exit immediately after a `play start` fork.
#
# Shell-form CMD is required for env-var expansion. `exec` replaces the
# sh process with the JVM so SIGTERM reaches Play's graceful-shutdown
# hooks. Play 1.x's CLI passes -X / -D args through to the JVM.
CMD ["sh", "-c", "exec play run --%prod -Dprecompiled=true -Xms${JCLAW_JVM_XMS:-${JCLAW_JVM_HEAP:-512m}} -Xmx${JCLAW_JVM_XMX:-${JCLAW_JVM_HEAP:-2g}}"]
