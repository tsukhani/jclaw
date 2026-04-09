# ── Stage 1: Build frontend SPA ──────────────────────────────────────────────
FROM node:22-slim AS frontend-build

RUN corepack enable && corepack prepare pnpm@10.33.0 --activate

WORKDIR /app/frontend
COPY frontend/package.json frontend/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile

COPY frontend/ ./
RUN npx nuxi generate

# ── Stage 2: Runtime ─────────────────────────────────────────────────────────
FROM azul/zulu-openjdk:25 AS runtime

LABEL org.opencontainers.image.source=https://github.com/tsukhani/jclaw

# Install Play Framework (latest release from tsukhani/play1) + Playwright browser deps
RUN apt-get update && apt-get install -y --no-install-recommends \
        curl unzip python3 jq \
        libglib2.0-0 libnss3 libnspr4 libdbus-1-3 libatk1.0-0 \
        libatk-bridge2.0-0 libcups2 libxkbcommon0 libatspi2.0-0 \
        libxcomposite1 libxdamage1 libxfixes3 libxrandr2 libgbm1 \
        libpango-1.0-0 libcairo2 libx11-xcb1 libxcursor1 \
        libgtk-3-0 libpangocairo-1.0-0 libcairo-gobject2 \
        libgdk-pixbuf-2.0-0 && \
    PLAY_URL=$(curl -fsSL https://api.github.com/repos/tsukhani/play1/releases/latest | jq -r '.assets[0].browser_download_url') && \
    PLAY_VERSION=$(curl -fsSL https://api.github.com/repos/tsukhani/play1/releases/latest | jq -r '.tag_name' | sed 's/^v//') && \
    curl -fsSL -L "$PLAY_URL" -o /tmp/play.zip && \
    unzip -q /tmp/play.zip -d /opt && \
    ln -s /opt/play-${PLAY_VERSION} /opt/play && \
    rm /tmp/play.zip && \
    apt-get remove -y curl unzip jq && apt-get autoremove -y && \
    rm -rf /var/lib/apt/lists/*

ENV PLAY_HOME=/opt/play
ENV PATH="${PLAY_HOME}:${PATH}"

# Copy application
WORKDIR /app
COPY app/ app/
COPY conf/ conf/
COPY public/ public/
COPY skills/ skills/
COPY test/ test/
COPY .gitignore .distignore ./

# Resolve dependencies
COPY conf/dependencies.yml conf/dependencies.yml
RUN play deps --sync

# Pre-install only Chromium for Playwright (skip Firefox/WebKit)
RUN java -cp "$(echo lib/playwright-*.jar lib/driver-*.jar | tr ' ' ':')" \
        com.microsoft.playwright.CLI install chromium

# Copy frontend SPA build
COPY --from=frontend-build /app/frontend/.output/public public/spa/

# Precompile for faster startup
RUN play precompile

# Create data and logs directories
RUN mkdir -p data logs

EXPOSE 9000

# Run in production mode
CMD ["play", "run", "--%prod"]
