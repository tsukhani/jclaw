#!/bin/sh
# JClaw under Docker: install or upgrade (macOS + Linux)
#
#   curl -fsSL https://github.com/tsukhani/jclaw/releases/latest/download/install-docker.sh | sh
#
# Fetches a release's signed IMAGE_DIGEST, checks the signature against the release
# key below, and pulls exactly that image by digest. Run in the directory holding
# docker-compose.yml and it also pins the image as JCLAW_IMAGE in ./.env, so
# `docker compose up -d` runs what was verified rather than whatever `latest` names.
# Then it puts in place what the agent sandbox needs (JF-413): seccomp-bwrap.json
# from the verified image, apparmor=unconfined in a compose file laid out as the
# shipped one (keeping the old file as docker-compose.yml.bak), and, on a host that
# restricts user namespaces such as Ubuntu 23.10 or later, the bwrap AppArmor profile
# if you say yes. It checks the sandbox in a throwaway container and, when that
# passes, asks to start JClaw. Releases also publish it as docker-pull-verified.sh,
# its name before JF-413.
#
#   JCLAW_VERSION     release tag, e.g. v0.20.0 (default: latest)
#   JCLAW_NO_START    set to 1 to set everything up but not start JClaw
#   JCLAW_ASSUME_YES  set to 1 to answer yes to every question
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

# >>> agent sandbox (JF-413) — definitions only. install.sh and install-docker.sh carry this block
# word for word; SandboxSetupScriptsTest runs it and holds the profile equal to INSTALL.md's.
# Ubuntu 23.10+ lets a program make the user namespace bwrap needs only under an AppArmor profile granting one.
BWRAP_PROFILE='abi <abi/4.0>,
include <tunables/global>
profile bwrap /usr/bin/bwrap flags=(unconfined) {
  userns,
  include if exists <local/bwrap>
}'
# The JCLAW_* names below are test seams; an operator never needs them.
USERNS_RESTRICT="${JCLAW_USERNS_RESTRICT:-/proc/sys/kernel/apparmor_restrict_unprivileged_userns}"
BWRAP_PROFILE_PATH="${JCLAW_BWRAP_PROFILE_PATH:-/etc/apparmor.d/bwrap}"
TTY="${JCLAW_TTY:-/dev/tty}"
if [ "$(id -u)" = 0 ]; then SUDO=''; else SUDO='sudo'; fi
SUDO="${JCLAW_SUDO-$SUDO}"

# A yes or no from the terminal, since through `| sh` stdin is this script. With no terminal the answer is
# no, so nothing changes unasked; an empty answer takes the default, $2.
ask() {
    [ "${JCLAW_ASSUME_YES:-}" = 1 ] && return 0
    ( true <"$TTY" ) 2>/dev/null || return 1
    printf '    %s ' "$1" >>"$TTY"
    read -r reply <"$TTY" || reply=''
    case "${reply:-$2}" in [Yy]*) return 0 ;; *) return 1 ;; esac
}

needs_bwrap_profile() {
    [ "$(cat "$USERNS_RESTRICT" 2>/dev/null)" = 1 ] && [ ! -e "$BWRAP_PROFILE_PATH" ]
}

ensure_bwrap_profile() {
    needs_bwrap_profile || return 0
    substep "this host restricts user namespaces (Ubuntu 23.10 or later), so bwrap needs an AppArmor profile"
    if ask "Add $BWRAP_PROFILE_PATH with sudo? [y/N]" n \
        && printf '%s\n' "$BWRAP_PROFILE" | $SUDO tee "$BWRAP_PROFILE_PATH" >/dev/null \
        && $SUDO "${JCLAW_APPARMOR_PARSER:-apparmor_parser}" -r "$BWRAP_PROFILE_PATH"; then
        substep "added $BWRAP_PROFILE_PATH"
        return 0
    fi
    substep "not added. INSTALL.md (Ubuntu 23.10 and later) gives the two commands that add it."
    return 1
}
# <<< agent sandbox

# >>> docker sandbox (JF-413) — definitions only.
# HarnessSandbox.BWRAP_PROBE_AS_ROOT, after `bwrap`: the container runs as root.
PROBE_AS_ROOT='--unshare-user --cap-drop ALL --ro-bind / / --dev /dev --proc /proc --tmpfs /tmp true'

seccomp_from_image() {
    docker run --rm --entrypoint cat "$1" /app/seccomp-bwrap.json >"$2" 2>/dev/null && [ -s "$2" ]
}

# The operator's compose file is theirs: only the shipped seccomp line gains its AppArmor line beneath it.
ensure_compose_apparmor() {
    if grep -q '^[[:space:]]*-[[:space:]]*apparmor=unconfined[[:space:]]*$' docker-compose.yml; then
        substep "docker-compose.yml: apparmor=unconfined is set"
        return 0
    fi
    if grep -q '^[[:space:]]*-[[:space:]]*seccomp=\./seccomp-bwrap\.json[[:space:]]*$' docker-compose.yml; then
        cp docker-compose.yml docker-compose.yml.bak
        awk '{ print }
             /^[[:space:]]*-[[:space:]]*seccomp=\.\/seccomp-bwrap\.json[[:space:]]*$/ {
                 match($0, /^[[:space:]]*-[[:space:]]*/); print substr($0, 1, RLENGTH) "apparmor=unconfined"
             }' docker-compose.yml.bak >docker-compose.yml
        substep "docker-compose.yml: added apparmor=unconfined under security_opt (the old file is docker-compose.yml.bak)"
        return 0
    fi
    warn "docker-compose.yml is not laid out as the shipped one, so it was left alone. Give the jclaw service:"
    substep "security_opt:"
    substep "  - seccomp=./seccomp-bwrap.json"
    substep "  - apparmor=unconfined"
    return 1
}

# From a shell, as JClaw starts it: on Ubuntu 24.04 a bwrap Docker itself starts, as the entrypoint or by
# docker exec, stays refused at its uid map even under the host's bwrap profile (measured, JF-413).
probe_container() {
    # shellcheck disable=SC2086,SC2016 # the probe's words split on purpose; $@ is the inner shell's
    out=$(docker run --rm --security-opt "seccomp=$2" --security-opt apparmor=unconfined \
        --entrypoint sh "$1" -c 'exec bwrap "$@"' sh $PROBE_AS_ROOT 2>&1) && return 0
    first=$(printf '%s\n' "$out" | head -n 1)
    warn "the agent sandbox cannot start in this container: $first"
    case "$first" in
        *'uid map'*) substep "the host restricts user namespaces: add the bwrap AppArmor profile (INSTALL.md, Ubuntu 23.10 and later)" ;;
        *'make / slave'*) substep "Docker's own AppArmor profile refused it: the container needs apparmor=unconfined" ;;
        *'new namespace'*) substep "Docker's default seccomp profile refused it: the container needs seccomp-bwrap.json" ;;
    esac
    return 1
}

start_compose() {
    if [ -n "${JCLAW_NO_START:-}" ]; then
        substep "start it with: docker compose up -d"
        return 0
    fi
    if docker compose ps --status running --services 2>/dev/null | grep -q '^jclaw$'; then
        question="Restart JClaw on this image now? This replaces the running container. [Y/n]"
    else
        question="Start JClaw now? [Y/n]"
    fi
    if ask "$question" y; then
        docker compose up -d || die "docker compose up failed — see the output above."
        step "Started $VERSION"
    else
        substep "start it with: docker compose up -d"
    fi
}
# <<< docker sandbox

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
else
    step "Pulled $VERSION ($CHECKED)"
    substep "no docker-compose.yml here, so nothing was pinned. To pin it, put this in .env beside it:"
    substep "JCLAW_IMAGE=$REF"
fi

step "Checking the agent sandbox"
READY=yes
SECCOMP="$TMP/seccomp-bwrap.json"
if ! seccomp_from_image "$REF" "$SECCOMP"; then
    warn "could not read seccomp-bwrap.json from the image."
    READY=no
fi
if [ -f docker-compose.yml ]; then
    if [ "$READY" = yes ]; then
        if cmp -s "$SECCOMP" seccomp-bwrap.json 2>/dev/null; then
            substep "seccomp-bwrap.json: current"
        else
            cp "$SECCOMP" seccomp-bwrap.json && substep "seccomp-bwrap.json: written from the image"
        fi
        SECCOMP="$PWD/seccomp-bwrap.json"
    fi
    ensure_compose_apparmor || READY=no
fi
ensure_bwrap_profile || true
if [ "$READY" = yes ] && probe_container "$REF" "$SECCOMP"; then
    substep "sandbox ok"
else
    READY=no
fi

if [ -f docker-compose.yml ]; then
    if [ "$READY" = yes ]; then
        start_compose
    else
        substep "Until the sandbox starts, every agent's shell command is refused. Fix the above, then: docker compose up -d"
    fi
fi
