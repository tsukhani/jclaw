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

# Install Play Framework
ARG PLAY_VERSION=1.11.7
RUN apt-get update && apt-get install -y --no-install-recommends \
        curl unzip python3 && \
    curl -fsSL -L https://github.com/tsukhani/play1/releases/download/v${PLAY_VERSION}/play-${PLAY_VERSION}.zip -o /tmp/play.zip && \
    unzip -q /tmp/play.zip -d /opt && \
    ln -s /opt/play-${PLAY_VERSION} /opt/play && \
    rm /tmp/play.zip && \
    apt-get remove -y curl unzip && apt-get autoremove -y && \
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

# Copy frontend SPA build
COPY --from=frontend-build /app/frontend/.output/public public/spa/

# Precompile for faster startup
RUN play precompile

# Create data and logs directories
RUN mkdir -p data logs

EXPOSE 9000

# Run in production mode
CMD ["play", "run", "--%prod"]
