# JClaw runtime container.
#
# Consumes a pre-built dist/jclaw-bundle.zip (produced by `./gradlew playBundle`) —
# the container ships zero source code, no javac, no Node, no pnpm, no Python.
# The bundle is a self-contained tree: precompiled bytecode, framework jar +
# framework lib, Gradle-resolved app deps, extracted modules, and a launcher
# script (bin/play-start.sh) that invokes `java -classpath ... play.server.Server`
# directly. -Dprecompiled=true (set in the launcher) tells Play to load the
# precompiled bytecode verbatim and skip both the Java and template compile
# passes (and to refuse to start if precompiled/ is missing).
#
# Build context requirement: dist/jclaw-bundle.zip MUST exist before
# `docker build` runs. CI does this via the Jenkinsfile's Package stage;
# for local builds, run `./gradlew playBundle` first.
#
# ── Stage 1: Stage the dist contents ────────────────────────────────────────
# Tiny stage that just unpacks dist/jclaw-bundle.zip so the runtime stage's
# COPY pulls in a flat /app tree. unzip is in this stage's image and isn't
# needed at runtime, so doing the extraction in a throwaway stage keeps it
# out of the final image. The zip's inner prefix is normalized to "jclaw/"
# by playBundle (uses project.name, set in settings.gradle.kts).
FROM ubuntu:26.04 AS dist-stage

RUN apt-get update && \
    apt-get install -y --no-install-recommends unzip && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /staging
COPY dist/jclaw-bundle.zip /staging/jclaw-bundle.zip
RUN unzip -q jclaw-bundle.zip && rm jclaw-bundle.zip

# ── Stage 2: Pre-install Chromium on a Playwright-supported base ────────────
# Playwright's CLI fingerprints the OS via /etc/os-release + uname and matches
# against an internal allowlist. Ubuntu 26.04 (resolute) is a development
# release Microsoft hasn't published browser builds for on arm64 yet, so an
# `install chromium` invocation on that base aborts with "Playwright does not
# support chromium on ubuntu26.04-arm64". The pre-3aba910 multi-stage build
# avoided this by accident — its `backend-build` stage was on
# azul/zulu-openjdk:25 (noble = 24.04, fully supported) and the runtime stage
# just COPY'd /opt/pw-browsers across. The dist-consumer rewrite collapsed
# everything onto resolute, lost that platform decoupling, and broke arm64.
#
# Restore the decoupling with a dedicated stage on Azul's Zulu image (noble-
# based). All this stage does is run the Java CLI's `install chromium` against
# the playwright JARs from the bundle's lib/; the resulting /opt/pw-browsers
# tree gets COPY'd into the resolute runtime stage below. `install chromium`
# without `--with-deps` only downloads the browser tarball — no launch, no
# shared-lib check — so this stage doesn't need the runtime's libnss3/libcups2
# stack. The runtime stage still installs those for actual browser launches.
FROM azul/zulu-openjdk:25.0.3 AS chromium-stage

ENV PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers

# Only the Playwright JARs are needed; rest of the bundle is dead weight in
# this stage. The bundle places resolved deps under <name>/lib/, so the
# playwright + driver jars live at /staging/jclaw/lib/playwright-*.jar.
COPY --from=dist-stage /staging/jclaw/lib/ /tmp/lib/

RUN java -cp "$(echo /tmp/lib/playwright-*.jar /tmp/lib/driver-*.jar | tr ' ' ':')" \
        com.microsoft.playwright.CLI install chromium

# ── Stage 3: Runtime on Ubuntu 26.04 + Zulu JRE 25 ──────────────────────────
# Build atop ubuntu:26.04 rather than azul/zulu-openjdk:25-jre-headless-latest
# (which is pinned to Ubuntu 22.04 / jammy) so apt-shipped tesseract-ocr is
# 5.5.x rather than jammy's frozen 4.1.1. Smaller too: ubuntu:26.04 is ~160 MB
# vs the Zulu image's ~500 MB. Zulu's JRE comes from Azul's apt repo at the
# 25.0.3 line; lands at /usr/lib/jvm/zulu25-ca-amd64 with a /usr/lib/jvm/zulu25
# symlink, matching the upstream image's layout.
FROM ubuntu:26.04 AS runtime

LABEL org.opencontainers.image.source=https://github.com/tsukhani/jclaw

ENV DEBIAN_FRONTEND=noninteractive

# Single RUN so apt lists are dropped in the same layer the packages were
# resolved from. Two apt-get update calls because the Azul sources file
# doesn't exist until midway through.
#
# Ubuntu 24.04+ ran the time_t 64-bit ABI transition — the t64 suffix on
# libasound2, libatk*, libatspi*, libcups2, libglib2 below is load-bearing;
# the un-suffixed jammy-era names no longer resolve. Other Playwright libs
# kept their original names through the transition.
#
# Runtime contents (much slimmer than 1.12-era — no Python, no Play CLI download):
#   - zulu25-jre-headless         JRE for the Play JVM
#   - openssl                     entrypoint self-signs a PEM cert
#   - tesseract-ocr               DocumentsTool's OCR path
#   - lib(asound|atk|...)t64+ etc Playwright/Chromium shared libs
#   - bash                        bin/play-start.sh launcher (already in base)
#
# No javac, no Node/pnpm, no Maven, no Python, no `play` CLI — the bundle
# ships precompiled bytecode + a built SPA + a self-contained launcher that
# directly invokes `java -classpath ... play.server.Server`. Per PF-90 the
# Python CLI is gone and Gradle handles everything at build time.
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
        libasound2t64 libatk-bridge2.0-0t64 libatk1.0-0t64 libatspi2.0-0t64 \
        libcairo2 libcups2t64 libdbus-1-3 libdrm2 libgbm1 libglib2.0-0t64 \
        libnspr4 libnss3 libpango-1.0-0 libwayland-client0 libx11-6 \
        libxcb1 libxcomposite1 libxdamage1 libxext6 libxfixes3 \
        libxkbcommon0 libxrandr2 && \
    rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/usr/lib/jvm/zulu25
ENV PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1

# Pull the unpacked bundle tree into /app. The stage's contents are at
# /staging/jclaw/* — a single COPY moves the entire precompiled + conf +
# framework + lib + modules + public + skills + workspace tree + bin/play-start.sh
# launcher into place in one layer.
COPY --from=dist-stage /staging/jclaw/ /app/

# Pull the pre-installed Chromium browser tree from chromium-stage. See the
# comment block on that stage for why the install can't run here directly.
COPY --from=chromium-stage /opt/pw-browsers /opt/pw-browsers

# Ensure operator-data dirs exist for first-run; both end up empty in
# the image and are populated at container start (data/ via H2, logs/
# via Play's log4j2 config). The launcher script is in the bundle but
# ZipEntry can't carry executable bits, so chmod it here.
RUN mkdir -p /app/data /app/logs && \
    chmod +x /app/bin/play-start.sh

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
# JAVA_TOOL_OPTIONS is honored by the JVM and prepended to its command-line
# args — we use it to inject heap flags without modifying the bundle's
# launcher script. The bundle's bin/play-start.sh already sets -javaagent,
# -Dprecompiled=true, -Dapplication.path, -Dplay.id, classpath, and execs
# play.server.Server directly. We just layer heap on top via the env-var
# convention.
#
# Shell-form CMD is required for env-var expansion. `exec bash bin/play-start.sh`
# replaces the sh process with the launcher (which itself execs java) so
# SIGTERM reaches Play's graceful-shutdown hooks.
CMD ["sh", "-c", "export JAVA_TOOL_OPTIONS=\"-Xms${JCLAW_JVM_XMS:-${JCLAW_JVM_HEAP:-512m}} -Xmx${JCLAW_JVM_XMX:-${JCLAW_JVM_HEAP:-2g}}\" && exec bash bin/play-start.sh"]
