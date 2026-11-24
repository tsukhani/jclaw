#!/usr/bin/env bash
# One-time host setup for the benchmark, run by an account with sudo: a dedicated unprivileged user, pnpm for it,
# a sparse checkout of this folder in its home, and the folder for the key and the results.
# Usage: bash setup-host.sh [user]   (default user: harnessbench)
set -euo pipefail
BENCH_USER=${1:-harnessbench}
PNPM_VERSION=${PNPM_VERSION:-12.15.0}

if ! id "$BENCH_USER" >/dev/null 2>&1; then
    sudo useradd --create-home --shell /bin/bash "$BENCH_USER"
    echo "created user $BENCH_USER"
fi
sudo -iu "$BENCH_USER" env PNPM_VERSION="$PNPM_VERSION" bash -s <<'SETUP'
set -euo pipefail
PNPM_HOME=$HOME/.local/share/pnpm
PATH=$PNPM_HOME/bin:$PNPM_HOME:$PATH
if ! command -v pnpm >/dev/null 2>&1; then
    curl -fsSL https://get.pnpm.io/install.sh | env SHELL=/bin/bash sh - >/dev/null
fi
if [ ! -d "$HOME/jclaw/.git" ]; then
    git clone --quiet --filter=blob:none --sparse https://github.com/tsukhani/jclaw.git "$HOME/jclaw"
    git -C "$HOME/jclaw" sparse-checkout set bench/harness-compare
fi
mkdir -p "$HOME/harness-bench"
chmod 700 "$HOME/harness-bench"
# A new Ubuntu user's umask is 002, and OpenClaw refuses a temp folder whose parent group or others can write.
chmod -R go-w "$HOME/.cache" "$HOME/.local" 2>/dev/null || true
echo "pnpm $(pnpm --version), node $(node --version), checkout $(git -C "$HOME/jclaw" log -1 --format=%h)"
SETUP
echo "Next: put the Ollama Cloud key in ~$BENCH_USER/harness-bench/ollama.env (README.md, The key)."
