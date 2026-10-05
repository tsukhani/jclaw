#!/bin/bash
# What the LaunchAgent runs: reinstalls the factory's dependencies whenever pnpm-lock.yaml changed, then starts it.
# `--install-only` stops after installing.
set -euo pipefail
cd "$(dirname "$0")"
stamp=node_modules/.factory-lock-sha
want="$(shasum -a 256 pnpm-lock.yaml | cut -d' ' -f1)"
if [[ ! -x node_modules/.bin/tsx || "$(cat "$stamp" 2>/dev/null || true)" != "$want" ]]; then
    # Clean first: pnpm leaves alone any package in node_modules it did not install itself.
    rm -rf node_modules
    pnpm install --frozen-lockfile
    echo "$want" > "$stamp"
fi
[[ "${1:-}" == "--install-only" ]] && exit 0
exec node_modules/.bin/tsx main.ts
