#!/bin/bash
# What the LaunchAgent runs: reinstalls the factory's dependencies whenever package-lock.json changed, then starts it.
# `--install-only` stops after installing.
set -euo pipefail
cd "$(dirname "$0")"
stamp=node_modules/.factory-lock-sha
want="$(shasum -a 256 package-lock.json | cut -d' ' -f1)"
if [[ ! -x node_modules/.bin/tsx || "$(cat "$stamp" 2>/dev/null || true)" != "$want" ]]; then
    npm ci --no-audit --no-fund
    echo "$want" > "$stamp"
fi
[[ "${1:-}" == "--install-only" ]] && exit 0
exec node_modules/.bin/tsx main.ts
