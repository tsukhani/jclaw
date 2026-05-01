#!/bin/sh
# JClaw container entrypoint.
#
# Resolves $PLAY_SECRET (Play's session-cookie signing key) at boot, in this
# order:
#
#   1. If PLAY_SECRET is already set in the env (e.g. operator put it in
#      docker-compose env_file, or wrote PLAY_SECRET=... in the compose
#      project's .env), trust it and continue.
#   2. Else, if /app/data/.play-secret exists and is non-empty, load it.
#      This is the path written by step 3 on a prior boot — what makes the
#      secret survive container restarts.
#   3. Else, generate a fresh 64-char alphanumeric secret, persist it to
#      /app/data/.play-secret with mode 600, and use it.
#
# /app/data is bind-mounted to ./data on the host (per docker-compose.yml),
# so the persisted secret rides along with the rest of the app's runtime
# state. To rotate, delete ./data/.play-secret and restart the container —
# the next boot will regenerate it. Existing PLAY_SESSION cookies become
# invalid after a rotation, which is the point.
#
# This script replaces the prior "one-time bootstrap" Compose profile —
# docker compose up -d now Just Works on a fresh extract.

set -eu

SECRET_FILE=/app/data/.play-secret

if [ -z "${PLAY_SECRET:-}" ]; then
    if [ -s "$SECRET_FILE" ]; then
        PLAY_SECRET=$(cat "$SECRET_FILE")
    else
        mkdir -p "$(dirname "$SECRET_FILE")"
        PLAY_SECRET=$(LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 64)
        # Run the file-write inside a subshell so the restrictive umask
        # (0177 → mode 0600 on creation) stays scoped to that subshell and
        # does NOT leak to the JVM exec'd below. A leaked umask of 0177
        # was the cause of v0.10.74-v0.10.76's "AccessDeniedException on
        # workspace skill copy" bug: with that umask in effect, every
        # `Files.createDirectories(...)` the JVM made produced a directory
        # of mode 0600 — no `x`-bit, hence no traversal — and the next
        # nested file write would fail with EACCES even though root owned
        # the dir. Subshell scoping is the load-bearing fix.
        (
            umask 177
            printf '%s' "$PLAY_SECRET" > "$SECRET_FILE"
        )
        chmod 600 "$SECRET_FILE"
    fi
fi

export PLAY_SECRET
exec "$@"
