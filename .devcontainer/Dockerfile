# JClaw development container, and the image Sandcastle runs AFK coding
# agents in — one image for both, so the agents' toolchain is the developers'.
#
# Installs the toolchain ./jclaw.sh setup's check_prereqs expects: Java 25
# (Azul Zulu), Node 26 (NodeSource), pnpm, the Play 1.13.x fork from
# tsukhani/play1, and tesseract-ocr for OCR paths; plus ffmpeg and uv for the
# media and sidecar paths, and Claude Code. Built and consumed by
# .devcontainer/devcontainer.json — postCreateCommand runs ./jclaw.sh setup
# on first open to wire git hooks and pnpm install. Build context is the repo
# root (`-f .devcontainer/Dockerfile .`), because .play-version lives there.
#
# In 1.13.x the Python `play` CLI is gone (PF-90) and Gradle drives every
# build step. settings.gradle.kts resolves the play1 plugin from /opt/play1's
# flat gradle-plugin-repo and build.gradle.kts cross-checks it against
# .play-version, so all this Dockerfile needs to do is land the framework
# tree at /opt/play1, downloaded from the GitHub release .play-version names.

FROM ubuntu:26.04@sha256:da6fc2be547864451aa253836dd926da33623312df4a9a243e35dc877c378a78

ENV DEBIAN_FRONTEND=noninteractive
# Without a UTF-8 locale the JVM encodes process arguments and file names as ASCII (sun.jnu.encoding).
ENV LANG=C.UTF-8

# Base toolchain. python3 is kept (separate from the now-removed Play CLI
# requirement) because some app-level scripts and bmad tools still call it;
# remove if/when those go. tesseract-ocr backs DocumentsTool's OCR path;
# language packs install separately (tesseract-ocr-fra, tesseract-ocr-jpn,
# etc.).
#
# openssl is invoked by ./jclaw.sh start's first-run cert auto-gen path
# (ensure_certs_for_start → do_cert) when no PEM exists at certs/host.cert
# and mkcert isn't installed.
#
# ffmpeg matches the production image (audio transcode, diarization
# extraction). tini is PID 1, so the orphans JClaw's subprocesses leave are
# reaped rather than lingering as zombies that still read as alive.
#
# The lib*-dev packages are Playwright/Chromium runtime shared libs —
# parity with the production Dockerfile so dev `play auto-test` runs
# (PlaywrightToolTest, WebFetchToolTest) and `./jclaw.sh --dev start`
# can drive a real browser when the WebFetchTool fires. The t64 suffix
# is load-bearing: Ubuntu 24.04+ ran the time_t 64-bit ABI transition
# and the un-suffixed jammy-era names no longer resolve.
RUN echo 'Acquire::Retries "5";' > /etc/apt/apt.conf.d/80-retries && \
    apt-get update && \
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
        ffmpeg \
        tini \
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
RUN curl -fsSL --retry 5 --retry-all-errors https://repos.azul.com/azul-repo.key \
        | gpg --dearmor -o /usr/share/keyrings/azul.gpg && \
    echo "deb [signed-by=/usr/share/keyrings/azul.gpg] https://repos.azul.com/zulu/deb stable main" \
        > /etc/apt/sources.list.d/zulu.list && \
    apt-get update && \
    apt-get install -y --no-install-recommends zulu25-jdk && \
    rm -rf /var/lib/apt/lists/*

# Node.js 26 via NodeSource. pnpm, uv and Claude Code install per user below.
# Fetched to a file, then run: `curl ... | sh` reports the shell's exit status,
# so a failed fetch would leave the layer green and the toolchain absent. curl
# retries the fetch; the wrapper retries the run, where these scripts do their
# own downloading.
RUN retry() { n=0; until "$@"; do n=$((n + 1)); [ "$n" -ge 5 ] && return 1; sleep $((n * 3)); done; }; \
    curl -fsSL --retry 5 --retry-all-errors \
        https://deb.nodesource.com/setup_26.x -o /tmp/nodesource.sh && \
    retry bash /tmp/nodesource.sh && \
    apt-get install -y --no-install-recommends nodejs && \
    rm -rf /var/lib/apt/lists/* && \
    rm -f /tmp/nodesource.sh

# Play 1.13.x — tsukhani/play1 fork. Exact version is read from
# .play-version at the jclaw repo root (single source of truth shared
# with the production Dockerfile and build.gradle.kts).
# settings.gradle.kts in jclaw resolves the play1 plugin from /opt/play1's
# flat gradle-plugin-repo (not includeBuild), so we
# unpack the release zip and symlink `/opt/play-<version>` → `/opt/play1`
# to match.
#
# The release zip ships the framework tree (gradle-plugin/, framework/,
# modules/, gradlew, build.gradle.kts, etc.) — everything the Gradle plugin
# resolves from /opt/play1. The bundled `play` shell wrapper goes on PATH so
# devs can run `play run` / `play test` from inside the workspace.
COPY .play-version /tmp/.play-version
RUN PLAY_VERSION=$(tr -d '[:space:]' < /tmp/.play-version) && \
    test -n "$PLAY_VERSION" || (echo "Could not read .play-version" && exit 1) && \
    curl -fsSL -L --retry 5 --retry-all-errors \
        "https://github.com/tsukhani/play1/releases/download/v${PLAY_VERSION}/play-${PLAY_VERSION}.zip" \
        -o /tmp/play.zip && \
    unzip -q /tmp/play.zip -d /opt/ && \
    ln -s "/opt/play-${PLAY_VERSION}" /opt/play1 && \
    test -x "/opt/play1/play" && \
    ln -s /opt/play1/play /usr/local/bin/play && \
    rm /tmp/play.zip /tmp/.play-version

# Drop privileges. Ubuntu's default `ubuntu` user (UID 1000) becomes `agent`
# with home /home/agent, the user and HOME Sandcastle runs its sandboxes as.
# A host whose UID is not 1000 passes AGENT_UID/AGENT_GID to match it.
ARG AGENT_UID=1000
ARG AGENT_GID=1000
RUN groupmod -o -g "$AGENT_GID" -n agent ubuntu && \
    usermod -o -u "$AGENT_UID" -g "$AGENT_GID" -d /home/agent -m -l agent ubuntu
# Sandcastle cannot start a container with no-new-privileges, so leave nothing setuid or setgid to escalate through;
# sudo is not installed, so no workflow here relies on them.
RUN find / -xdev -perm /6000 -type f -exec chmod a-s {} +
USER agent

# pnpm, uv and Claude Code install into the user's home: a root-owned install
# under /root (mode 700) is unreachable once USER drops privileges. Node 25+
# does not ship corepack, and corepack cannot launch pnpm 12 anyway
# (per-platform native binary, no bin/pnpm.cjs); pnpm switches itself to the
# version pinned in frontend/package.json on first use and verifies it
# against the lockfile. $PNPM_HOME/bin, not $PNPM_HOME: pnpm 12 installs the
# executable one level down and prints that path itself. pnpm 11 was reachable
# from the parent, which is what makes the wrong one look right until a build
# says "pnpm: not found" immediately after a successful install.
ENV PNPM_HOME=/home/agent/.local/share/pnpm
ENV PATH=/home/agent/.local/bin:$PNPM_HOME/bin:$PATH
# pnpm is the version frontend/package.json's `packageManager` pins, so the image follows the repo. uv and Claude Code
# install their latest release whenever this layer rebuilds: after any change to frontend/package.json or to an earlier
# input (the base image, .play-version).
COPY --chown=agent frontend/package.json /tmp/frontend-package.json
RUN retry() { n=0; until "$@"; do n=$((n + 1)); [ "$n" -ge 5 ] && return 1; sleep $((n * 3)); done; }; \
    PNPM_VERSION=$(node -p "require('/tmp/frontend-package.json').packageManager.replace(/^pnpm@/, '').split('+')[0]") && \
    curl -fsSL --retry 5 --retry-all-errors https://get.pnpm.io/install.sh -o /tmp/pnpm-install.sh && \
    curl -fsSL --retry 5 --retry-all-errors https://astral.sh/uv/install.sh -o /tmp/uv-install.sh && \
    curl -fsSL --retry 5 --retry-all-errors https://claude.ai/install.sh -o /tmp/claude-install.sh && \
    retry env PNPM_VERSION="$PNPM_VERSION" ENV=/home/agent/.bashrc SHELL=/bin/bash sh /tmp/pnpm-install.sh && \
    retry env UV_NO_MODIFY_PATH=1 sh /tmp/uv-install.sh && \
    retry bash /tmp/claude-install.sh && \
    test "$(pnpm --version)" = "$PNPM_VERSION" && uv --version && claude --version && \
    rm -f /tmp/pnpm-install.sh /tmp/uv-install.sh /tmp/claude-install.sh /tmp/frontend-package.json

# WORKDIR matches the Dev Containers default mount path so Cursor /
# Codespaces / JetBrains Gateway find the project at the expected location;
# Sandcastle overrides it with the worktree it mounts.
WORKDIR /workspaces/jclaw

# Dev Containers override the command with their own keep-alive (and get an
# init from devcontainer.json's `init`); Sandcastle starts the image as-is
# and execs into it, so the image must stay up on its own.
ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["sleep", "infinity"]
