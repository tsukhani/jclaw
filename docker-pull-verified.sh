#!/bin/sh
# JClaw verified image pull (macOS + Linux)
#
#   curl -fsSL https://raw.githubusercontent.com/tsukhani/jclaw/main/docker-pull-verified.sh | sh
#
# Fetches a release's signed IMAGE_DIGEST, checks the signature against the release
# key below, and pulls exactly that image by digest. Run in the directory holding
# docker-compose.yml and it also pins the image as JCLAW_IMAGE in ./.env, so
# `docker compose up -d` runs what was verified rather than whatever `latest` names.
#
#   JCLAW_VERSION   release tag, e.g. v0.20.0 (default: latest)
#
# A machine with no openssl is warned and pulls the published digest unverified, as
# install.sh does; a signature that is missing or wrong is fatal.
#
# POSIX sh — no bashisms; runnable under dash/ash via `| sh`.
set -eu

JCLAW_REPO="tsukhani/jclaw"
JCLAW_VERSION="${JCLAW_VERSION:-latest}"
IMAGE="ghcr.io/tsukhani/jclaw"

# Public half of the Jenkins credential jclaw-release-signing-key (ECDSA P-256).
# jclaw.sh pins the same key; ReleaseSigningConformanceTest holds the copies equal.
RELEASE_PUBKEY='-----BEGIN PUBLIC KEY-----
MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEESTRoh6FNrJfdmlUEwT+7VTYneMd
IZGsQ3joF/NEpzn/jTMzI+UT4r/btJ+LTsXpI+pHG+7Rl2VV2mI4dadqSg==
-----END PUBLIC KEY-----'

step()    { printf '==> %s\n' "$1"; }
substep() { printf '    %s\n' "$1"; }
warn()    { printf 'warning: %s\n' "$1" >&2; }
die()     { printf 'error: %s\n' "$1" >&2; exit 1; }

http_get() {
    if command -v curl >/dev/null 2>&1; then curl -fsSL "$1"
    elif command -v wget >/dev/null 2>&1; then wget -qO- "$1"
    else die "need curl or wget to fetch the release's image digest."; fi
}

command -v docker >/dev/null 2>&1 || die "docker is not on PATH."
CHECKED="signature verified"
if ! command -v openssl >/dev/null 2>&1; then
    warn "openssl is not installed, so the release signature cannot be checked — pulling the image unverified."
    CHECKED="signature NOT checked"
fi

if [ "$JCLAW_VERSION" = "latest" ]; then
    TAG=""
    BASE="https://github.com/$JCLAW_REPO/releases/latest/download"
else
    TAG="$JCLAW_VERSION"
    case "$TAG" in v*) ;; *) TAG="v$TAG" ;; esac
    BASE="https://github.com/$JCLAW_REPO/releases/download/$TAG"
fi

TMP=$(mktemp -d "${TMPDIR:-/tmp}/jclaw-image.XXXXXX")
trap 'rm -rf "$TMP"' EXIT

step "Fetching the signed image digest (${TAG:-latest})"
http_get "$BASE/IMAGE_DIGEST" >"$TMP/IMAGE_DIGEST" 2>/dev/null \
    || die "could not fetch an image digest for ${TAG:-the latest release} (releases up to v0.19.28 publish none) — nothing was pulled."
if [ "$CHECKED" = "signature verified" ]; then
    http_get "$BASE/IMAGE_DIGEST.sig" >"$TMP/IMAGE_DIGEST.sig" 2>/dev/null \
        || die "the release's IMAGE_DIGEST carries no signature — nothing was pulled."
    printf '%s\n' "$RELEASE_PUBKEY" >"$TMP/release.pub"
    # Exit status, not output: LibreSSL and OpenSSL word a failed verify differently.
    openssl dgst -sha256 -verify "$TMP/release.pub" \
        -signature "$TMP/IMAGE_DIGEST.sig" "$TMP/IMAGE_DIGEST" >/dev/null 2>&1 \
        || die "the signature on IMAGE_DIGEST is not valid — nothing was pulled."
fi

read -r VERSION REF <"$TMP/IMAGE_DIGEST" || true
HEX="${REF#"$IMAGE@sha256:"}"
case "$HEX" in ''|*[!0-9a-f]*) HEX="" ;; esac
if [ "$REF" != "$IMAGE@sha256:$HEX" ] || [ "${#HEX}" -ne 64 ]; then
    die "IMAGE_DIGEST does not name a $IMAGE digest — nothing was pulled."
fi
# The signature proves Jenkins published this digest, not for which release:
# an older signed file re-published under this tag would verify.
if [ -n "$TAG" ] && [ "$VERSION" != "$TAG" ]; then
    die "the digest published as $TAG is for $VERSION — nothing was pulled."
fi
substep "$CHECKED: $VERSION"

step "Pulling $REF"
docker pull "$REF" || die "docker pull failed — see the output above."

if [ -f docker-compose.yml ]; then
    if [ -f .env ] && grep -q '^JCLAW_IMAGE=' .env; then
        sed "s|^JCLAW_IMAGE=.*|JCLAW_IMAGE=$REF|" .env >"$TMP/env" && cat "$TMP/env" >.env
    else
        # A last line with no newline would otherwise swallow the new one.
        if [ -s .env ] && [ -n "$(tail -c 1 .env)" ]; then echo >>.env; fi
        printf 'JCLAW_IMAGE=%s\n' "$REF" >>.env
    fi
    step "Pinned $VERSION in .env ($CHECKED)"
    substep "start it with: docker compose up -d"
else
    step "Pulled $VERSION ($CHECKED)"
    substep "no docker-compose.yml here, so nothing was pinned. To pin it, put this in .env beside it:"
    substep "JCLAW_IMAGE=$REF"
fi
