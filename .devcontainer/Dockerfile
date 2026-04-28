# JClaw development container.
#
# Installs the full toolchain that ./jclaw.sh setup's check_prereqs
# expects: Java 25 (Azul Zulu), Python 3 (default Ubuntu), Node 20 LTS
# (NodeSource), corepack (ships with Node 20+), and Play 1.x from
# Abundent's custom fork. Also installs tesseract-ocr so JCLAW-177's
# image / scanned-PDF OCR code path can be exercised end-to-end without
# a host install.
#
# Built and consumed by .devcontainer/devcontainer.json. After build,
# postCreateCommand runs ./jclaw.sh setup — which validates these same
# prereqs (so a missing one is caught at the dev-container layer with
# a clean message, not deeper inside pnpm install or play deps --sync),
# wires git hooks, runs corepack install + pnpm install, and adds the
# canonical github remote.

FROM ubuntu:26.04

ENV DEBIAN_FRONTEND=noninteractive

# Base toolchain. git for VCS ops, gnupg for trusted-key import (Azul
# repo signing key), curl/wget for downloads, unzip for play dist
# artifacts, build-essential in case a pnpm dep needs to compile a
# native module. python3 is the Play wrapper script's runtime; a
# too-old Python here would surface as a cryptic SyntaxError inside
# the play command, which is exactly what check_python guards against
# — install the recent default to skip that whole failure mode.
# tesseract-ocr is optional for most dev work but needed to test
# DocumentsTool's OCR path (additional language packs install
# separately as tesseract-ocr-fra, tesseract-ocr-jpn, etc.).
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
        tesseract-ocr \
    && rm -rf /var/lib/apt/lists/*

# JDK 25 via Azul's apt repo. Ubuntu's default repos lag behind JDK
# releases and JClaw requires 25+ (java.source=25 in application.conf).
# Azul publishes one universal `stable` distribution that covers all
# supported Debian/Ubuntu versions, so the line below works on any
# base image without per-codename branching. The signed-by= trusted-
# key flow is the modern replacement for the deprecated apt-key add —
# keys live in a per-repo keyring rather than the global
# /etc/apt/trusted.gpg.
RUN curl -fsSL https://repos.azul.com/azul-repo.key \
        | gpg --dearmor -o /usr/share/keyrings/azul.gpg && \
    echo "deb [signed-by=/usr/share/keyrings/azul.gpg] https://repos.azul.com/zulu/deb stable main" \
        > /etc/apt/sources.list.d/zulu.list && \
    apt-get update && \
    apt-get install -y --no-install-recommends zulu25-jdk && \
    rm -rf /var/lib/apt/lists/*

# Node.js 24 (current LTS) via NodeSource. corepack ships with Node
# 16.13+ but isn't enabled by default — `corepack enable` symlinks
# the corepack-managed pnpm shim into a PATH location so jclaw.sh's
# validate_corepack_pnpm can resolve `corepack pnpm` at start time.
# The actual pnpm binary downloads on first use, verified against the
# +sha512-... integrity hash pinned in frontend/package.json.
RUN curl -fsSL https://deb.nodesource.com/setup_24.x | bash - && \
    apt-get install -y --no-install-recommends nodejs && \
    rm -rf /var/lib/apt/lists/* && \
    corepack enable

# Play Framework 1.x — Abundent's custom fork. Upstream Play 1.x is
# unmaintained; this fork carries JDK 25 compatibility patches the
# backend depends on. Pull the official tagged release zip rather
# than shallow-cloning master, so the image gets a pinned version
# and can't silently pick up a breaking change on master between dev
# container rebuilds. Same canonical distribution format upstream
# Play has shipped for years: top-level play-${VERSION}/ directory
# with the wrapper script, framework JARs, modules, and samples.
# Kept aligned with the production Dockerfile's PLAY_VERSION pin.
ARG PLAY_VERSION=1.12.3
RUN curl -fsSL "https://github.com/tsukhani/play1/releases/download/v${PLAY_VERSION}/play-${PLAY_VERSION}.zip" \
        -o /tmp/play.zip && \
    unzip -q /tmp/play.zip -d /opt/ && \
    rm /tmp/play.zip && \
    test -x "/opt/play-${PLAY_VERSION}/play" && \
    ln -s "/opt/play-${PLAY_VERSION}/play" /usr/local/bin/play

# Drop privileges. Ubuntu 24.04 ships with a default `ubuntu` user at
# UID 1000 — devcontainer best practice is to run as a non-root user
# so bind-mounted workspace files retain host ownership and accidental
# privileged ops are blocked. The WORKDIR matches VS Code Dev
# Containers' default workspace mount path so the Cursor / Codespaces
# / JetBrains Gateway integration finds the project at the expected
# location.
USER ubuntu
WORKDIR /workspaces/jclaw
