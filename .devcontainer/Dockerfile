# JClaw development container.
#
# Installs the toolchain ./jclaw.sh setup's check_prereqs expects: Java 25
# (Azul Zulu), Python 3, Node 24 (NodeSource), corepack, Play 1.x from
# tsukhani/play1, and tesseract-ocr for OCR paths. Built and consumed by
# .devcontainer/devcontainer.json — postCreateCommand runs ./jclaw.sh setup
# on first open to wire git hooks and pnpm install.

FROM ubuntu:26.04

ENV DEBIAN_FRONTEND=noninteractive

# Base toolchain. python3 is Play's CLI-wrapper runtime — too-old Python
# would surface as a SyntaxError inside `play`, so install the recent
# default to skip that failure mode entirely. tesseract-ocr backs
# DocumentsTool's OCR path; language packs install separately
# (tesseract-ocr-fra, tesseract-ocr-jpn, etc.).
#
# openssl is invoked by ./jclaw.sh start's first-run cert auto-gen path
# (ensure_certs_for_start → do_cert) when no PEM exists at certs/host.cert
# and mkcert isn't installed.
#
# The lib*-dev packages are Playwright/Chromium runtime shared libs —
# parity with the production Dockerfile so dev `play autotest` runs
# (PlaywrightToolTest, WebFetchToolTest) and `./jclaw.sh --dev start`
# can drive a real browser when the WebFetchTool fires. The t64 suffix
# is load-bearing: Ubuntu 24.04+ ran the time_t 64-bit ABI transition
# and the un-suffixed jammy-era names no longer resolve.
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        git \
        gnupg \
        curl \
        wget \
        unzip \
        ca-certificates \
        build-essential \
        python3 \
        python3-pip \
        openssl \
        tesseract-ocr \
        libasound2t64 libatk-bridge2.0-0t64 libatk1.0-0t64 libatspi2.0-0t64 \
        libcairo2 libcups2t64 libdbus-1-3 libdrm2 libgbm1 libglib2.0-0t64 \
        libnspr4 libnss3 libpango-1.0-0 libwayland-client0 libx11-6 \
        libxcb1 libxcomposite1 libxdamage1 libxext6 libxfixes3 \
        libxkbcommon0 libxrandr2 \
    && rm -rf /var/lib/apt/lists/*

# JDK 25 via Azul's apt repo. Ubuntu's defaults lag behind JDK releases and
# JClaw requires 25+. The `signed-by=` keyring is the modern replacement for
# the deprecated `apt-key add` — keys live per-repo, not in the global
# /etc/apt/trusted.gpg.
RUN curl -fsSL https://repos.azul.com/azul-repo.key \
        | gpg --dearmor -o /usr/share/keyrings/azul.gpg && \
    echo "deb [signed-by=/usr/share/keyrings/azul.gpg] https://repos.azul.com/zulu/deb stable main" \
        > /etc/apt/sources.list.d/zulu.list && \
    apt-get update && \
    apt-get install -y --no-install-recommends zulu25-jdk && \
    rm -rf /var/lib/apt/lists/*

# Node.js 24 via NodeSource. corepack ships with Node 16.13+ but isn't
# enabled by default — `corepack enable` symlinks the pnpm shim into PATH;
# the actual binary downloads on first use, verified against the +sha512
# integrity hash pinned in frontend/package.json.
RUN curl -fsSL https://deb.nodesource.com/setup_24.x | bash - && \
    apt-get install -y --no-install-recommends nodejs && \
    rm -rf /var/lib/apt/lists/* && \
    corepack enable

# Play Framework 1.x — Abundent's fork (carries JDK 25 patches upstream Play
# 1.x is missing). Version pinned via .play-version, the single source of
# truth shared with the production Dockerfile; Renovate auto-bumps from
# tsukhani/play1 releases.
COPY .play-version /tmp/play-version
RUN PLAY_VERSION=$(tr -d '[:space:]' < /tmp/play-version) && \
    curl -fsSL "https://github.com/tsukhani/play1/releases/download/v${PLAY_VERSION}/play-${PLAY_VERSION}.zip" \
        -o /tmp/play.zip && \
    unzip -q /tmp/play.zip -d /opt/ && \
    rm /tmp/play.zip /tmp/play-version && \
    test -x "/opt/play-${PLAY_VERSION}/play" && \
    ln -s "/opt/play-${PLAY_VERSION}/play" /usr/local/bin/play

# Drop privileges. Ubuntu ships a default `ubuntu` user at UID 1000 — running
# as non-root preserves bind-mounted workspace ownership and blocks
# accidental privileged ops. WORKDIR matches the Dev Containers default
# mount path so Cursor / Codespaces / JetBrains Gateway find the project at
# the expected location.
USER ubuntu
WORKDIR /workspaces/jclaw
