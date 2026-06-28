#!/usr/bin/env bash
# Verify the local image/video generation sidecars' uv.lock files are
# consistent with their pyproject.toml. Fast and GPU-free: `uv lock --check`
# is resolution-only — it installs nothing, downloads no torch/wheels, and
# never advances the ref-less git LTX deps to a newer commit (it reuses the
# commit already pinned in uv.lock). It exits non-zero only when a fresh
# resolve WOULD change the lock — i.e. the lock is stale relative to the
# manifest.
#
# Renovate keeps these locks current (the pep621 manager + lockFileMaintenance
# in renovate.json5), so this is a consistency guard, not the update mechanism.
# Its job is to catch a pyproject.toml edited without regenerating the lock —
# e.g. a hand bump that skipped `uv lock`, or a half-applied Renovate run.
#
# Used by Jenkinsfile's "Sidecar Locks" stage; also runnable by hand. Fix a
# failure with `uv lock` in the offending sidecar dir, then commit the result.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)

if ! command -v uv >/dev/null 2>&1; then
    echo "[verify-locks] uv not on PATH — install from https://docs.astral.sh/uv/" \
         "(the sidecars need it to run anyway)." >&2
    exit 1
fi

status=0
for dir in image video; do
    echo "[verify-locks] checking sidecar/$dir ..."
    if ! ( cd "$here/$dir" && uv lock --check ); then
        echo "[verify-locks] sidecar/$dir: uv.lock is out of sync with pyproject.toml —" \
             "run 'uv lock' in sidecar/$dir and commit the result." >&2
        status=1
    fi
done

if [ "$status" -eq 0 ]; then
    echo "[verify-locks] all sidecar locks consistent."
fi
exit "$status"
