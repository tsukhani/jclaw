#!/bin/sh
# JClaw container entrypoint.
#
# Manages two pieces of runtime security material, both stored under
# /app/certs (bind-mounted to ./certs on the host):
#
#   1. PLAY_SECRET — Play's session-cookie signing key. Resolution order:
#      env first (operator override), then ./certs/.env, then auto-generate
#      a fresh 64-char secret and persist it to ./certs/.env.
#
#   2. TLS PEM cert + key — used by Play's HTTPS listener for h2/h3.
#      Resolution: if ./certs/host.cert and ./certs/host.key both exist,
#      use them; else self-sign a fresh pair via openssl. Operators wanting
#      browser-trusted local TLS replace the self-signed pair with mkcert-
#      signed PEMs (Chrome's QUIC stack only negotiates h3 against a cert
#      whose chain validates against the system trust store).
#
# application.conf points certificate.file and certificate.key.file at
# certs/host.cert and certs/host.key, so the JVM reads directly from the
# bind-mounted directory — no symlink indirection needed.
#
# Rotation: delete ./certs/.env (or ./certs/host.cert + key) and restart;
# the next boot regenerates whichever is missing.

set -eu

CERTS_DIR=/app/certs
ENV_FILE="$CERTS_DIR/.env"
CERT_FILE="$CERTS_DIR/host.cert"
KEY_FILE="$CERTS_DIR/host.key"

mkdir -p "$CERTS_DIR"

# ── PLAY_SECRET resolution ──────────────────────────────────────────────────
if [ -z "${PLAY_SECRET:-}" ]; then
    if [ -s "$ENV_FILE" ]; then
        # Source the .env in a subshell to read PLAY_SECRET without leaking
        # any other variables it may contain into the parent shell. The
        # subshell pipes only PLAY_SECRET back through stdout.
        PLAY_SECRET=$(set -a; . "$ENV_FILE"; set +a; printf '%s' "${PLAY_SECRET:-}")
    fi
    if [ -z "${PLAY_SECRET:-}" ]; then
        PLAY_SECRET=$(LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 64)
        # umask 177 inside a subshell so the restrictive mode applies to
        # the file write but does NOT leak to the JVM exec'd below — a
        # leaked umask of 0177 was the cause of v0.10.74-v0.10.76's
        # AccessDeniedException-on-skill-copy bug.
        (
            umask 177
            printf 'PLAY_SECRET=%s\n' "$PLAY_SECRET" > "$ENV_FILE"
        )
        chmod 600 "$ENV_FILE"
    fi
fi

# ── TLS PEM cert + key resolution ───────────────────────────────────────────
if [ ! -f "$CERT_FILE" ] || [ ! -f "$KEY_FILE" ]; then
    # openssl writes both files; the same subshell-umask trick keeps the
    # private key 0600 from inception.
    (
        umask 177
        openssl req -x509 -newkey rsa:2048 -nodes \
            -keyout "$KEY_FILE" -out "$CERT_FILE" \
            -days 3650 -subj "/CN=localhost" \
            -addext "subjectAltName=DNS:localhost,IP:127.0.0.1,IP:0:0:0:0:0:0:0:1" \
            >/dev/null 2>&1
    )
    chmod 600 "$KEY_FILE"
    chmod 644 "$CERT_FILE"
fi

export PLAY_SECRET
exec "$@"
