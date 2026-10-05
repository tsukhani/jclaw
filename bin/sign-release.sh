#!/usr/bin/env bash
# Sign a release's SHA256SUMS with the private key file named by $SIGNING_KEY, then
# verify the result under the public key jclaw.sh pins, with both verifiers a client
# uses: openssl (install.sh) and the precompiled utils.ReleaseSignature this build is
# about to ship (jclaw.sh upgrade). A credential that no longer matches the pinned key,
# or a verifier class that cannot run on the JDK alone, fails the release here rather
# than on every installed client — those refuse a signature they cannot verify.
#
# Usage: SIGNING_KEY=<private key file> sign-release.sh <SHA256SUMS>   (writes <SHA256SUMS>.sig)
# Run after the bundle is built: it reads precompiled/java.
set -euo pipefail

SUMS="${1:?usage: sign-release.sh <SHA256SUMS>}"
: "${SIGNING_KEY:?sign-release: SIGNING_KEY must name the private key file}"
[ -f "$SUMS" ] || { echo "sign-release: no such file: $SUMS" >&2; exit 2; }
command -v openssl >/dev/null || { echo "sign-release: openssl not on PATH" >&2; exit 2; }

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CLASSES="$ROOT/precompiled/java"
[ -f "$CLASSES/utils/ReleaseSignature.class" ] \
    || { echo "sign-release: no precompiled utils.ReleaseSignature under $CLASSES" >&2; exit 2; }
PUB="$(mktemp)"
trap 'rm -f "$PUB"' EXIT

# \047 is the quote closing the assignment; the PEM itself never contains one.
awk '/^UPGRADE_RELEASE_PUBKEY=/ { p = 1; sub(/^[^-]*/, "") }
     p { sub(/\047$/, ""); print }
     p && /END PUBLIC KEY/ { exit }' "$ROOT/jclaw.sh" >"$PUB"
openssl pkey -pubin -in "$PUB" -noout 2>/dev/null \
    || { echo "sign-release: jclaw.sh pins no readable UPGRADE_RELEASE_PUBKEY" >&2; exit 2; }

openssl dgst -sha256 -sign "$SIGNING_KEY" -out "$SUMS.sig" "$SUMS"
if ! openssl dgst -sha256 -verify "$PUB" -signature "$SUMS.sig" "$SUMS" >/dev/null 2>&1 \
    || ! java -cp "$CLASSES" utils.ReleaseSignature "$PUB" "$SUMS.sig" "$SUMS"; then
    rm -f "$SUMS.sig"
    echo "sign-release: the signature does not verify under the key pinned in jclaw.sh —" >&2
    echo "              the signing credential and the pinned key have diverged, or the" >&2
    echo "              shipped verifier cannot run." >&2
    exit 1
fi
echo "sign-release: signed $SUMS, verified under the pinned key by openssl and the shipped verifier"
