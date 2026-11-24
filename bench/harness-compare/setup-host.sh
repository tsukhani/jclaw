#!/usr/bin/env bash
# One-time host setup for the benchmark, run by an account with sudo: a dedicated unprivileged user, Node.js and pnpm
# for it, a sparse checkout of this folder in its home, and the folder for the key and the results. Run it again to
# update that Node.js to the newest release of its major version.
# Usage: bash setup-host.sh [user]   (default user: harnessbench)
set -euo pipefail
BENCH_USER=${1:-harnessbench}
PNPM_VERSION=${PNPM_VERSION:-12.15.0}
NODE_MAJOR=${NODE_MAJOR:-26}

if ! id "$BENCH_USER" >/dev/null 2>&1; then
    sudo useradd --create-home --shell /bin/bash "$BENCH_USER"
    echo "created user $BENCH_USER"
fi
sudo -iu "$BENCH_USER" env PNPM_VERSION="$PNPM_VERSION" NODE_MAJOR="$NODE_MAJOR" bash -s <<'SETUP'
set -euo pipefail
# The benchmark's own Node.js, in its own home: other services on a shared host keep the system's.
case "$(uname -m)" in
    x86_64) arch=x64 ;;
    aarch64 | arm64) arch=arm64 ;;
    *) echo "no Node.js build for $(uname -m)" >&2; exit 1 ;;
esac
dist=https://nodejs.org/dist/latest-v$NODE_MAJOR.x
sums=$(curl -fsSL "$dist/SHASUMS256.txt")
file=$(printf '%s\n' "$sums" | awk -v want="linux-$arch.tar.xz" 'substr($2, length($2) - length(want) + 1) == want { print $2 }')
[ -n "$file" ] || { echo "no linux-$arch build listed in $dist" >&2; exit 1; }
version=${file#node-}
version=${version%-linux-*}
if [ "$("$HOME/.local/node/bin/node" --version 2>/dev/null || true)" != "$version" ]; then
    tmp=$(mktemp -d)
    curl -fsSL -o "$tmp/$file" "$dist/$file"
    (cd "$tmp" && printf '%s\n' "$sums" | awk -v f="$file" '$2 == f' | sha256sum --check --quiet -)
    rm -rf "$HOME/.local/node.new"
    mkdir -p "$HOME/.local/node.new"
    tar -xJf "$tmp/$file" -C "$HOME/.local/node.new" --strip-components=1
    rm -rf "$HOME/.local/node" "$tmp"
    mv "$HOME/.local/node.new" "$HOME/.local/node"
fi
PNPM_HOME=$HOME/.local/share/pnpm
PATH=$HOME/.local/node/bin:$PNPM_HOME/bin:$PNPM_HOME:$PATH
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
