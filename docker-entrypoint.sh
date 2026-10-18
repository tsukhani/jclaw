#!/bin/sh
# JClaw container entrypoint.
#
# Manages two pieces of runtime security material, both stored under
# /app/certs (bind-mounted to ./certs on the host):
#
#   1. PLAY_SECRET — Play's session-cookie signing key. Resolution order:
#      explicit env var (operator override) → bind-mounted ./certs/.env
#      → fresh in-shell generation, persisted to ./certs/.env.
#
#      Generation matches `play secret` exactly (64-char alphanumeric
#      written as PLAY_SECRET=<value>) but runs at *container start*, not
#      at image build. Build-time seeding via `gradle playSecret` was
#      considered and rejected: a baked-in secret is either reused across
#      every rebuild that hits a warm Docker cache (turning
#      session-cookie signing into a shared primitive across pulls of
#      the same image hash) or recomputed needlessly on every source
#      change in cold-cache builds. Generating per-container side-steps
#      both cliffs and keeps the runtime image free of the play1 fork +
#      Gradle that `gradle playSecret` would otherwise demand.
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
#   PLAY_SECRET_PREVIOUS, the outgoing secret of a rotation, resolves the
#   same way as PLAY_SECRET but is never generated.
#
# Rotation: the stored credentials are encrypted under a key derived from
# PLAY_SECRET (JCLAW-1455), so deleting ./certs/.env leaves every one of them
# unreadable. To rotate, rename the PLAY_SECRET line in ./certs/.env to
# PLAY_SECRET_PREVIOUS and restart: a new PLAY_SECRET is generated beside it,
# JClaw re-encrypts the credentials under it, and its log says when the
# PLAY_SECRET_PREVIOUS line can be removed. Delete ./certs/host.cert and key
# and restart to regenerate the TLS pair.

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
        # Appended, so a PLAY_SECRET_PREVIOUS already in the file survives.
        (
            umask 177
            if [ -s "$ENV_FILE" ] && [ -n "$(tail -c 1 "$ENV_FILE")" ]; then
                printf '\n' >> "$ENV_FILE"
            fi
            printf 'PLAY_SECRET=%s\n' "$PLAY_SECRET" >> "$ENV_FILE"
        )
        chmod 600 "$ENV_FILE"
    fi
fi

# ── PLAY_SECRET_PREVIOUS pass-through ───────────────────────────────────────
if [ -z "${PLAY_SECRET_PREVIOUS:-}" ] && [ -s "$ENV_FILE" ]; then
    PLAY_SECRET_PREVIOUS=$(set -a; . "$ENV_FILE"; set +a; printf '%s' "${PLAY_SECRET_PREVIOUS:-}")
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

# Keep only the two newest OOM heap dumps: each is up to -Xmx, and with
# ExitOnOutOfMemoryError plus `restart: unless-stopped` an OOM that recurs
# on every start would otherwise fill the disk. Names begin with the UTC
# start time, so glob order is age order. Twin of prune_heap_dumps in jclaw.sh.
prune_heap_dumps() {
    set -- /app/logs/heap-oom-*.hprof
    [ -e "$1" ] || return 0
    while [ "$#" -gt 2 ]; do
        rm -f "$1"
        shift
    done
}
prune_heap_dumps

export PLAY_SECRET
if [ -n "${PLAY_SECRET_PREVIOUS:-}" ]; then
    export PLAY_SECRET_PREVIOUS
fi
exec "$@"
