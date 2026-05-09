# JClaw self-contained Docker build.
#
# Builds the playBundle from source in Stage 1 — no need to pre-run
# `./gradlew playBundle` on the host first. The runtime stage ships
# only the unpacked bundle plus the Azul Zulu 25 JRE.
#
# Build context: the repo root. .dockerignore strips out the heavy
# generated trees (node_modules/, .gradle/, build/, precompiled/) so the
# bundle stage rebuilds them fresh from source rather than reusing
# possibly-stale host artifacts.
#
# Cache layering principle: stable instructions come first (apt, gradle
# install, play1 fork install — change rarely), volatile come last (the
# source-tree COPY + playBundle RUN — change every iteration). Inside
# Stage 1's build RUN, BuildKit cache mounts persist Gradle's dep cache,
# pnpm's content-addressed store, corepack downloads, and the project's
# resolved frontend/node_modules across builds even though the COPY
# layer they sit under invalidates per source change — saves ~60-90 s of
# dep resolution + pnpm install on warm-cache rebuilds. The mounts hold
# no application state; missing them just reverts to clean rebuilds.

# ── Stage 1: Build the playBundle zip ──────────────────────────────────────
# Azul Zulu 25 JDK (Ubuntu noble base) + Gradle 9.5 + Node 24 + Play 1.13.x.
# `gradle playBundle` runs the play1 plugin's full pipeline:
#   1. pnpm install + pnpm run generate (Nuxt SPA → frontend/.output/public/)
#   2. copy frontend SPA into public/spa/
#   3. javac on app/, template precompile → precompiled/
#   4. resolve runtime deps + framework jars into the bundle's lib/
#   5. assemble dist/<rootProject.name>-bundle.zip with the ./play launcher
#
# The output zip is self-contained: precompiled bytecode, framework jar +
# framework lib, Gradle-resolved app deps, extracted modules, the built
# SPA, and a launcher (./play at the bundle root, +x preserved via
# ZipFileSystem POSIX attrs) that invokes `java -classpath ...
# play.server.Server` directly. We unpack it inline so Stage 3's COPY
# pulls a flat /app tree.
FROM azul/zulu-openjdk:25.0.3 AS bundle-stage

ENV DEBIAN_FRONTEND=noninteractive

# TARGETARCH is a buildx-provided global ARG (amd64, arm64, …) that has
# to be redeclared inside each stage that wants to read it; without the
# ARG line it stays empty in this stage's RUN commands. We use it to
# scope the node_modules and Gradle cache mounts per-architecture so a
# multi-arch buildx invocation (linux/amd64,linux/arm64) doesn't share a
# single mount across platforms — node_modules carries arch-specific
# native bindings (oxc-parser, @parcel/watcher, esbuild, …) and Gradle's
# configuration cache encodes absolute paths plus JVM toolchain
# fingerprints. Sharing causes the second arch through to inherit the
# first arch's native deps and crash on require().
ARG TARGETARCH

# Toolchain. unzip extracts the Gradle dist + the play1 release zip + the
# bundle. curl/git fetch the play1 fork. Node 24 + corepack drive pnpm
# (whose exact version + integrity hash is pinned in
# frontend/package.json's "packageManager" field; corepack downloads and
# verifies the binary on first invocation).
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        ca-certificates curl unzip git gnupg && \
    curl -fsSL https://deb.nodesource.com/setup_24.x | bash - && \
    apt-get install -y --no-install-recommends nodejs && \
    corepack enable && \
    rm -rf /var/lib/apt/lists/*

# Gradle 9.5 — pinned to match gradle/wrapper/gradle-wrapper.properties
# (distributionUrl = gradle-9.5.0-bin.zip). Installing the binary directly
# rather than driving `./gradlew` saves a network round-trip on each
# clean build and gives a deterministic toolchain regardless of the
# wrapper jar's state in the source tree.
RUN curl -fsSL https://services.gradle.org/distributions/gradle-9.5.0-bin.zip -o /tmp/gradle.zip && \
    unzip -q /tmp/gradle.zip -d /opt && \
    ln -s /opt/gradle-9.5.0/bin/gradle /usr/local/bin/gradle && \
    rm /tmp/gradle.zip

# Play 1.13.x — tsukhani/play1 fork at /opt/play1, matching the path
# build.gradle.kts (frameworkPath) and settings.gradle.kts (includeBuild)
# both point at. The exact version is pinned in .play-version at the
# repo root (single source of truth shared with build.gradle.kts and
# the .devcontainer Dockerfile). build.gradle.kts also enforces a
# 1.13.x range at configure time, so a typo in .play-version fails
# the build with a clear message rather than producing mysterious
# "play-X.Y.Z.jar not found" errors deeper down.
COPY .play-version /tmp/.play-version
RUN PLAY_VERSION=$(tr -d '[:space:]' < /tmp/.play-version) && \
    test -n "$PLAY_VERSION" || (echo "Could not read .play-version" && exit 1) && \
    curl -fsSL -L "https://github.com/tsukhani/play1/releases/download/v${PLAY_VERSION}/play-${PLAY_VERSION}.zip" \
        -o /tmp/play.zip && \
    unzip -q /tmp/play.zip -d /opt/ && \
    ln -s "/opt/play-${PLAY_VERSION}" /opt/play1 && \
    ln -s /opt/play1/play /usr/local/bin/play && \
    rm /tmp/play.zip /tmp/.play-version

WORKDIR /src
COPY . /src/

# Initialize a throwaway git repo so PlayBundleTask's `git ls-files
# --cached --others --exclude-standard` source enumeration succeeds.
# .dockerignore strips the host's .git/ from the build context (would
# bloat by the full repo history), but the plugin uses git as a layered-
# .gitignore parser, not for VCS metadata. A `git init` with no commits
# leaves --cached empty and lets --others --exclude-standard walk the
# working tree honoring every .gitignore (root, frontend/, nested) just
# as the plugin expects. user.email/name are unset because we never
# commit — `git init` itself doesn't need them.
#
# BuildKit cache mounts (require Docker 23.0+ / BuildKit on by default).
# Mounts marked [per-arch] use id=…-${TARGETARCH} so a multi-arch buildx
# invocation (linux/amd64,linux/arm64) gives each platform its own cache
# — sharing across arches breaks builds whose contents depend on the
# target architecture (native bindings, JVM toolchain fingerprint, etc).
#   /root/.gradle              [per-arch] Gradle's user cache — resolved
#                              jars, dep metadata, configuration cache.
#                              ~500 MB warm. The configuration cache
#                              encodes absolute paths and JVM toolchain
#                              metadata, both of which are arch-tagged,
#                              so cross-arch sharing causes spurious
#                              cache misses and (worse) corrupted
#                              configuration-cache entries.
#   /root/.local/share/pnpm    [shared] pnpm's content-addressed store.
#                              ~200 MB. Each native binding (e.g.
#                              @oxc-parser/binding-linux-arm64-gnu vs
#                              -x64-gnu) is a distinct package with its
#                              own hash, so platforms coexist by hash —
#                              no cross-arch conflict, and sharing
#                              avoids re-downloading the same tarballs
#                              once per arch.
#   /root/.cache/node          [shared] corepack's pnpm-binary download
#                              cache. ~50 MB. The pnpm npm package is
#                              pure JavaScript — arch-independent.
#   /src/frontend/node_modules [per-arch] pnpm's project-resolved tree
#                              (symlinks into the store). ~200 MB.
#                              Carries arch-specific native bindings;
#                              sharing across arches makes the second
#                              arch through hit "lockfile up-to-date"
#                              and inherit the first arch's bindings,
#                              causing nuxi generate to fail on
#                              require('@oxc-parser/binding-…').
# These mounts hold no application state — eviction reverts to clean
# rebuilds, never to corrupt artifacts. CI that doesn't carry a warm
# BuildKit cache simply pays the dep-resolution cost once and exits.
#
# --no-daemon because this runs in a container that exits — the daemon
# is overhead with no warm-cache payoff. After the bundle is built we
# extract it and drop the zip to keep the stage's layer slim (the
# unpacked tree gets COPY'd into Stage 3, the zip would just be dead
# weight on the way through).
#
# Deliberately NOT running `gradle playSecret` here. Baking a secret into
# the image is a layer-caching anti-pattern: a cold cache regenerates it
# needlessly on every source change; a warm cache reuses the same secret
# across rebuilds, leaking it as an admin-session-forgery primitive
# shared between everyone who pulls the image. The secret is generated
# fresh per-container at entrypoint time instead — see
# docker-entrypoint.sh.
RUN --mount=type=cache,target=/root/.gradle,id=gradle-${TARGETARCH} \
    --mount=type=cache,target=/root/.local/share/pnpm \
    --mount=type=cache,target=/root/.cache/node \
    --mount=type=cache,target=/src/frontend/node_modules,id=node_modules-${TARGETARCH} \
    git init -q && \
    gradle --no-daemon playBundle && \
    mkdir /staging && \
    unzip -q dist/jclaw-bundle.zip -d /staging && \
    rm dist/jclaw-bundle.zip

# ── Stage 2: Pre-install Chromium on a Playwright-supported base ────────────
# Playwright's CLI fingerprints the OS via /etc/os-release + uname and
# matches against an internal allowlist. Ubuntu 26.04 (resolute) is a
# development release Microsoft hasn't published browser builds for on
# arm64 yet, so an `install chromium` invocation on resolute aborts with
# "Playwright does not support chromium on ubuntu26.04-arm64". This
# dedicated stage on Azul's Zulu image (noble-based) sidesteps that:
# `install chromium` without `--with-deps` only downloads the browser
# tarball, no shared-lib check, so this stage doesn't need the runtime's
# libnss3/libcups2 stack. Stage 3 still installs those for actual
# browser launches.
FROM azul/zulu-openjdk:25.0.3 AS chromium-stage

ENV PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers

# Only the Playwright JARs are needed; rest of the bundle is dead weight
# in this stage.
COPY --from=bundle-stage /staging/jclaw/lib/ /tmp/lib/

RUN java -cp "$(echo /tmp/lib/playwright-*.jar /tmp/lib/driver-*.jar | tr ' ' ':')" \
        com.microsoft.playwright.CLI install chromium

# ── Stage 3: Runtime on Ubuntu 26.04 + Zulu 25 JRE ──────────────────────────
# The unpacked bundle just needs a Java 25 runtime to launch via the
# bundled `./play` launcher — no javac, no Node, no pnpm, no Python, no
# Play CLI. We install Azul's Zulu 25 JRE from their apt repo onto
# ubuntu:26.04 rather than using azul/zulu-openjdk:25-jre-headless-latest
# directly: the upstream image is pinned to Ubuntu 22.04 (jammy), which
# freezes tesseract-ocr at 4.1.1 and ships ~500 MB vs resolute's ~160 MB
# base. The JRE binary is identical either way; the difference is the
# surrounding OS. The JRE lands at /usr/lib/jvm/zulu25-ca-amd64 with a
# /usr/lib/jvm/zulu25 symlink, matching the upstream image's layout.
#
# Runtime contents:
#   - zulu25-jre-headless         JRE for the Play JVM (the only HARD requirement)
#   - openssl                     entrypoint self-signs PEM cert + key
#   - tesseract-ocr               DocumentsTool's OCR path
#   - ffmpeg                      WhisperJniTranscriber audio→PCM coercion
#                                 (local Whisper only; cloud transcription
#                                 clients ship raw bytes to the API)
#   - lib(asound|atk|...)t64+ etc Playwright/Chromium shared libs
#   - curl                        docker-compose healthcheck probe
#   - bash                        ./play launcher (already in base)
#
# Layer order is engineered for cache stability across source-only
# rebuilds. The volatile bundle COPY (~880 MB unpacked) sits at the end
# so apt installs, ENV declarations, the chromium tree COPY (~150 MB),
# the entrypoint COPY, and `mkdir` all stay cached when only application
# code changes. Metadata instructions (ENTRYPOINT, EXPOSE, CMD) sit
# after the bundle COPY because they re-stamp cheaply (no filesystem
# diff) — a quick CMD edit shouldn't drag the bundle COPY into a
# cache-miss.
FROM ubuntu:26.04 AS runtime

LABEL org.opencontainers.image.source=https://github.com/tsukhani/jclaw

ENV DEBIAN_FRONTEND=noninteractive

# Single RUN so apt lists are dropped in the same layer they were
# resolved from. Two `apt-get update` calls because the Azul sources
# file doesn't exist until midway through.
#
# Ubuntu 24.04+ ran the time_t 64-bit ABI transition — the t64 suffix on
# libasound2, libatk*, libatspi*, libcups2, libglib2 below is load-bearing;
# the un-suffixed jammy-era names no longer resolve.
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        ca-certificates curl gnupg && \
    curl -fsSL https://repos.azul.com/azul-repo.key \
        | gpg --dearmor -o /usr/share/keyrings/azul.gpg && \
    echo "deb [signed-by=/usr/share/keyrings/azul.gpg] https://repos.azul.com/zulu/deb stable main" \
        > /etc/apt/sources.list.d/zulu.list && \
    apt-get update && \
    apt-get install -y --no-install-recommends \
        zulu25-jre-headless \
        openssl \
        tesseract-ocr \
        ffmpeg \
        libasound2t64 libatk-bridge2.0-0t64 libatk1.0-0t64 libatspi2.0-0t64 \
        libcairo2 libcups2t64 libdbus-1-3 libdrm2 libgbm1 libglib2.0-0t64 \
        libnspr4 libnss3 libpango-1.0-0 libwayland-client0 libx11-6 \
        libxcb1 libxcomposite1 libxdamage1 libxext6 libxfixes3 \
        libxkbcommon0 libxrandr2 && \
    rm -rf /var/lib/apt/lists/*

# Combined into one ENV block — three values, one layer.
ENV JAVA_HOME=/usr/lib/jvm/zulu25 \
    PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers \
    PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1

# Chromium browser tree (~150 MB). Sourced from chromium-stage, which
# itself only invalidates when build.gradle.kts bumps the playwright
# dep version. Placed BEFORE the bundle COPY so a typical source-only
# iteration doesn't pay the 150 MB COPY cost.
COPY --from=chromium-stage /opt/pw-browsers /opt/pw-browsers

# Entrypoint script. Cache hits unless docker-entrypoint.sh changes.
COPY --chmod=755 docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh

# Operator-data dirs — populated at container start (data/ via H2,
# logs/ via Play's log4j2 config, certs/ via the entrypoint).
RUN mkdir -p /app/data /app/logs /app/certs

# ── Volatile: bundle COPY sits last among filesystem-mutating steps ──────
# The unpacked bundle tree from Stage 1 lands at /app — a single COPY
# moves the entire precompiled + conf + framework + lib + modules +
# public + skills + workspace tree + ./play launcher into place in one
# layer. Every source change in the application invalidates Stage 1's
# RUN-playBundle layer, which propagates here as a hash change on this
# COPY's source. Everything above this line stays cached; only this
# COPY and the metadata layers below re-execute.
COPY --from=bundle-stage /staging/jclaw/ /app/

WORKDIR /app

# Entrypoint resolves PLAY_SECRET and provisions the TLS PEM cert + key
# on first boot — see the script header for the resolution order. The
# secret is a 64-char alphanumeric, matching the value `play secret`
# would write; the cert is a 10-year self-signed RSA-2048 with SANs for
# localhost + loopback. Both land under /app/certs, which docker-compose
# bind-mounts to ./certs on the host so they persist across `compose up`
# cycles. Operators wanting browser-trusted local TLS replace the
# self-signed pair with mkcert-signed PEMs (Chrome's QUIC stack only
# negotiates h3 against a cert whose chain validates against the system
# trust store).
ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]

# 9000: HTTP/1.1. 9443: HTTP/2 over TLS (TCP) and HTTP/3 over QUIC (UDP).
# Both 9443 entries are required — Compose must publish UDP separately
# for QUIC packets to reach the JVM's UDP listener.
EXPOSE 9000
EXPOSE 9443/tcp
EXPOSE 9443/udp

# Heap: asymmetric Xms 512m / Xmx 2g so an idle deploy doesn't commit 2 GB
# at boot. Override per-flag with `-e JCLAW_JVM_XMS` / `-e JCLAW_JVM_XMX`,
# or pin both with `-e JCLAW_JVM_HEAP=4g`.
#
# JAVA_TOOL_OPTIONS is honored by the JVM and prepended to its argv — we
# use it to inject heap flags without forking the bundle's `play`
# launcher. The launcher itself sets -javaagent (framework jar),
# -Dprecompiled=true, -Dapplication.path, -Dplay.id, classpath, and
# execs play.server.Server directly. The launcher also auto-sources
# certs/.env (so the entrypoint-generated PLAY_SECRET propagates) before
# the exec.
#
# --https.port=9443 lights up the HTTPS listener (ALPN h2 + h3 over
# QUIC) for which the entrypoint provisioned cert + key. The launcher
# parses --https.port= and forwards it to play.server.Server, which
# resolves the conf placeholder `https.port=${https.port:-1}` to 9443.
# Operators who want HTTP-only can override with `-e JCLAW_HTTPS_PORT=-1`,
# which collapses the placeholder back to the conf's "don't listen"
# sentinel.
#
# `--%prod` pins play.id to prod even though the launcher's PLAY_ID
# default is already prod — explicit beats implicit, and surfaces the
# choice for anyone reading `docker inspect`.
#
# `./play run` (foreground) NOT `./play start` (daemonized). The
# launcher's `start` subcommand does `nohup java ... &; echo $! > pid;
# return`, intended for SSH/host-bg use; in a container that exits the
# script (PID 1), so Docker tears the container down with the JVM still
# in the background — clean exit 0, no application logs, container
# stops. `run` does `exec "${JAVA_CMD[@]}"` so the JVM replaces the
# shell, inherits PID 1, and `docker stop`'s SIGTERM lands on Play's
# graceful-shutdown hooks rather than on a script that's already gone.
#
# Shell-form CMD is required for env-var expansion. The `sh -c` shell
# is itself replaced by `bash ./play` (exec), which is itself replaced
# by `java` (exec inside the launcher), so the signal chain is intact
# end-to-end.
CMD ["sh", "-c", "export JAVA_TOOL_OPTIONS=\"-Xms${JCLAW_JVM_XMS:-${JCLAW_JVM_HEAP:-512m}} -Xmx${JCLAW_JVM_XMX:-${JCLAW_JVM_HEAP:-2g}}\" && exec ./play run --%prod --https.port=${JCLAW_HTTPS_PORT:-9443}"]
