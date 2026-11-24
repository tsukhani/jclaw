#!/usr/bin/env bash
# Runs the JClaw / OpenClaw / Hermes turn-time benchmark end to end: resolves each harness's latest stable release,
# installs JClaw fresh and builds the other two at their release tags, starts all three behind the timing proxy,
# runs the tasks, checks the run is like for like, writes the report and stops everything it started.
# Everything it writes lives under $BENCH_HOME (default ~/harness-bench), never in this folder. README.md has the rest.
set -euo pipefail
# Private by default: the run holds secrets, and OpenClaw refuses a state folder that group or others can write.
umask 077

usage() {
    cat <<'EOF'
Usage: run.sh [options]

  --runs N                      runs of each task (default 10)
  --tasks a,b                   only these tasks (question,followup,summary,reasoning,json,fiveturn,search1,search3)
  --smoke                       one run of question and search1, to check the setup; not added to the history
  --jclaw TAG                   JClaw release to install (default: the latest release)
  --openclaw REF                OpenClaw tag, or main (default: the latest release)
  --hermes REF                  Hermes tag, or main (default: the latest release)
  --approve-hermes-plugin SHA   approve this commit of Hermes's web-ollama-cloud plugin, then run
  --keep                        keep this run's installs and state for inspection
  -h, --help                    this help
EOF
}

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
LIB=$HERE/src
BENCH_HOME=${BENCH_HOME:-$HOME/harness-bench}
KEY_FILE=$BENCH_HOME/ollama.env
RUNS=10 TASKS='' SMOKE=0 KEEP=0 JCLAW_TAG='' OPENCLAW_REF='' HERMES_REF='' APPROVE_PIN=''
while [ $# -gt 0 ]; do
    case "$1" in
        --runs) RUNS=$2; shift 2 ;;
        --tasks) TASKS=$2; shift 2 ;;
        --smoke) SMOKE=1; RUNS=1; TASKS=question,search1; shift ;;
        --jclaw) JCLAW_TAG=$2; shift 2 ;;
        --openclaw) OPENCLAW_REF=$2; shift 2 ;;
        --hermes) HERMES_REF=$2; shift 2 ;;
        --approve-hermes-plugin) APPROVE_PIN=$2; shift 2 ;;
        --keep) KEEP=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
done

export PORT_BASE=${PORT_BASE:-18100} JCLAW_PORT=${JCLAW_PORT:-19100} OPENCLAW_PORT=${OPENCLAW_PORT:-18789} HERMES_PORT=${HERMES_PORT:-8642}
export BENCH_MODEL=${BENCH_MODEL:-deepseek-v4.1-flash}

log() { printf '==> %s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

# ─── Preflight ──────────────────────────────────────────────────────────────
# pnpm's installer adds itself to PATH in ~/.bashrc, which a non-interactive `sudo -iu` login shell never reads.
PNPM_HOME=${PNPM_HOME:-$HOME/.local/share/pnpm}
for d in "$PNPM_HOME" "$PNPM_HOME/bin"; do [ -d "$d" ] && PATH=$d:$PATH; done
for tool in node pnpm git curl unzip openssl; do command -v "$tool" >/dev/null 2>&1 || die "$tool is not installed (README.md, Host setup)"; done
node -e 'const [a, b] = process.versions.node.split(".").map(Number); process.exit(a > 24 || (a === 24 && b >= 16) ? 0 : 1)' \
    || die "Node.js 24.16 or newer is needed; this is $(node --version)"
[ -f "$KEY_FILE" ] || die "no key file at $KEY_FILE (README.md, The key)"
node -e 'process.exit(require("fs").statSync(process.argv[1]).mode & 0o077 ? 1 : 0)' "$KEY_FILE" \
    || die "$KEY_FILE is readable by others: chmod 600 it"
OLLAMA_KEY=$(sed -n 's/^OLLAMA_API_KEY=//p' "$KEY_FILE" | head -n 1)
[ -n "$OLLAMA_KEY" ] || die "$KEY_FILE has no OLLAMA_API_KEY= line"
mkdir -p "$BENCH_HOME/runs" "$BENCH_HOME/work"
# Folders an earlier umask left group-writable would still fail OpenClaw's check of its temp folder's parents.
chmod 700 "$BENCH_HOME" "$BENCH_HOME/runs" "$BENCH_HOME/work"
mkdir "$BENCH_HOME/run.lock" 2>/dev/null || die "another run holds $BENCH_HOME/run.lock (remove it if no run is going)"
LOCKED=1
# AOT training starts JClaw once on its port + 1000.
node "$LIB/ports.mjs" "$PORT_BASE" $((PORT_BASE + 1)) $((PORT_BASE + 2)) $((PORT_BASE + 3)) "$JCLAW_PORT" $((JCLAW_PORT + 1000)) "$OPENCLAW_PORT" "$HERMES_PORT" \
    || die "a port the benchmark needs is in use (an earlier run still going?)"

STAMP=$(date -u +%Y%m%dT%H%M%SZ)
export RUN_DIR=$BENCH_HOME/runs/$STAMP
mkdir -p "$RUN_DIR/logs" "$RUN_DIR/proxy" "$RUN_DIR/pids" "$RUN_DIR/secrets" "$RUN_DIR/openclaw-cwd" "$RUN_DIR/hermes-cwd"
chmod 700 "$RUN_DIR/secrets"
JCLAW_SH=$RUN_DIR/jclaw-home/jclaw/jclaw.sh

stop_all() {
    local f pid
    for f in "$RUN_DIR"/pids/*; do
        [ -f "$f" ] || continue
        pid=$(cat "$f")
        kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true
    done
    [ -x "$JCLAW_SH" ] && "$JCLAW_SH" --backend-port "$JCLAW_PORT" stop >>"$RUN_DIR/logs/jclaw-stop.log" 2>&1 || true
    sleep 3
    for f in "$RUN_DIR"/pids/*; do
        [ -f "$f" ] || continue
        pid=$(cat "$f")
        kill -KILL -- "-$pid" 2>/dev/null || kill -KILL "$pid" 2>/dev/null || true
        rm -f "$f"
    done
}
cleanup() {
    local code=$?
    stop_all
    rm -rf "${RUN_DIR:?}/secrets"
    if [ "$KEEP" = 0 ]; then rm -rf "${RUN_DIR:?}/jclaw-home" "${RUN_DIR:?}/openclaw-state" "${RUN_DIR:?}/hermes-home" "${RUN_DIR:?}/bin"; fi
    [ "${LOCKED:-0}" = 1 ] && rmdir "$BENCH_HOME/run.lock" 2>/dev/null
    [ "$code" = 0 ] || printf 'run ended with exit %s; logs in %s/logs\n' "$code" "$RUN_DIR" >&2
    exit "$code"
}
trap cleanup EXIT
trap 'exit 130' INT TERM HUP

# Starts a long-running process in its own process group, so stop_all can end it and every child it spawned.
# The caller's subshell exports what the process needs, so no secret appears in a command line.
start_bg() {
    local name=$1
    shift
    if command -v setsid >/dev/null 2>&1; then
        ( exec setsid "$@" ) >"$RUN_DIR/logs/$name.log" 2>&1 </dev/null &
    else
        ( exec "$@" ) >"$RUN_DIR/logs/$name.log" 2>&1 </dev/null &
    fi
    echo $! >"$RUN_DIR/pids/$name"
}

checkout() {
    local dir=$1 url=$2 ref=$3
    [ -d "$dir/.git" ] || git clone --quiet --filter=blob:none "$url" "$dir"
    git -C "$dir" fetch --quiet --tags --force origin
    if [ "$ref" = main ]; then git -C "$dir" checkout --quiet --force --detach origin/main
    else git -C "$dir" checkout --quiet --force --detach "refs/tags/$ref"; fi
    git -C "$dir" rev-parse HEAD
}

# ─── Versions ───────────────────────────────────────────────────────────────
log "Resolving versions"
version_args=()
[ -n "$JCLAW_TAG" ] && version_args+=(--jclaw "$JCLAW_TAG")
[ -n "$OPENCLAW_REF" ] && version_args+=(--openclaw "$OPENCLAW_REF")
[ -n "$HERMES_REF" ] && version_args+=(--hermes "$HERMES_REF")
node "$LIB/versions.mjs" "${version_args[@]}" >"$RUN_DIR/versions.json"
tag_of() { node -e 'console.log(JSON.parse(require("fs").readFileSync(process.argv[1], "utf8"))[process.argv[2]].tag)' "$RUN_DIR/versions.json" "$1"; }
JCLAW_TAG=$(tag_of jclaw) OPENCLAW_REF=$(tag_of openclaw) HERMES_REF=$(tag_of hermes)
log "JClaw $JCLAW_TAG, OpenClaw $OPENCLAW_REF, Hermes $HERMES_REF, model $BENCH_MODEL"

# ─── The proxy ──────────────────────────────────────────────────────────────
log "Checking the proxy"
node --test "$HERE/test/proxy.test.mjs" >"$RUN_DIR/logs/proxy-selftest.log" 2>&1 || die "the proxy's self-test failed: $RUN_DIR/logs/proxy-selftest.log"
start_bg proxy env LOG_DIR="$RUN_DIR/proxy" node "$LIB/proxy.mjs"
node "$LIB/prepare.mjs" wait "http://127.0.0.1:$PORT_BASE/" 30 || die "the proxy did not start: $RUN_DIR/logs/proxy.log"

# ─── Hermes's search plugin: an approved commit only ────────────────────────
log "Checking out Hermes $HERMES_REF"
HERMES_DIR=$BENCH_HOME/work/hermes-agent
HERMES_COMMIT=$(checkout "$HERMES_DIR" https://github.com/NousResearch/hermes-agent.git "$HERMES_REF")
CATALOG=$HERMES_DIR/plugin-catalog/web-ollama-cloud.yaml
[ -f "$CATALOG" ] || die "Hermes $HERMES_REF has no web-ollama-cloud entry in its plugin catalog, so it has no Ollama web search"
APPROVED_LOCAL=$BENCH_HOME/approved-plugin-pins.txt
if [ -n "$APPROVE_PIN" ]; then
    node "$LIB/pins.mjs" approve "$CATALOG" "$APPROVE_PIN" "$APPROVED_LOCAL" || die "--approve-hermes-plugin $APPROVE_PIN was not recorded"
fi
pin_status=0
PLUGIN_SHA=$(node "$LIB/pins.mjs" check "$CATALOG" "$HERE/approved-plugin-pins.txt" "$APPROVED_LOCAL") || pin_status=$?
[ "$pin_status" = 0 ] || exit "$pin_status"

# ─── JClaw ──────────────────────────────────────────────────────────────────
log "Installing JClaw $JCLAW_TAG"
curl -fsSL -o "$RUN_DIR/install.sh" "https://github.com/tsukhani/jclaw/releases/download/$JCLAW_TAG/install.sh"
# No terminal and its own session, so the installer never stops at a question.
if command -v setsid >/dev/null 2>&1; then nosession=(setsid -w); else nosession=(); fi
JCLAW_HOME=$RUN_DIR/jclaw-home JCLAW_VERSION=$JCLAW_TAG JCLAW_NO_START=1 JCLAW_NO_RC_EDIT=1 JCLAW_BIN_DIR=$RUN_DIR/bin \
    "${nosession[@]}" sh "$RUN_DIR/install.sh" </dev/null >"$RUN_DIR/logs/jclaw-install.log" 2>&1 \
    || die "the JClaw installer failed: $RUN_DIR/logs/jclaw-install.log"
openssl rand -hex 24 >"$RUN_DIR/secrets/jclaw-admin-password"
"$JCLAW_SH" --backend-port "$JCLAW_PORT" start </dev/null >"$RUN_DIR/logs/jclaw-start.log" 2>&1 || die "JClaw did not start: $RUN_DIR/logs/jclaw-start.log"
node "$LIB/prepare.mjs" wait "http://127.0.0.1:$JCLAW_PORT/api/status" 300 || die "JClaw did not answer: $RUN_DIR/jclaw-home/jclaw/logs"
( export BENCH_OLLAMA_KEY=$OLLAMA_KEY; node "$LIB/prepare.mjs" jclaw ) || die "JClaw's setup failed"

# ─── OpenClaw ───────────────────────────────────────────────────────────────
log "Building OpenClaw $OPENCLAW_REF"
OPENCLAW_DIR=$BENCH_HOME/work/openclaw
OPENCLAW_COMMIT=$(checkout "$OPENCLAW_DIR" https://github.com/openclaw/openclaw.git "$OPENCLAW_REF")
# The build takes about 4 minutes; a commit already built by an earlier run is reused.
BUILT_STAMP=$OPENCLAW_DIR/.bench-built
if [ "$(cat "$BUILT_STAMP" 2>/dev/null)" = "$OPENCLAW_COMMIT" ]; then
    log "OpenClaw $OPENCLAW_REF was built by an earlier run"
else
    rm -f "$BUILT_STAMP"
    ( cd "$OPENCLAW_DIR" && pnpm install --frozen-lockfile && pnpm build ) >"$RUN_DIR/logs/openclaw-build.log" 2>&1 \
        || die "OpenClaw did not build: $RUN_DIR/logs/openclaw-build.log"
    echo "$OPENCLAW_COMMIT" >"$BUILT_STAMP"
fi
OPENCLAW_STATE=$RUN_DIR/openclaw-state
node "$LIB/prepare.mjs" render "$HERE/harness/openclaw.json5" "$OPENCLAW_STATE/openclaw.json"
openssl rand -hex 32 >"$RUN_DIR/secrets/openclaw-token"
(
    export OPENCLAW_STATE_DIR=$OPENCLAW_STATE BENCH_OLLAMA_KEY=$OLLAMA_KEY
    OPENCLAW_GATEWAY_TOKEN=$(cat "$RUN_DIR/secrets/openclaw-token")
    export OPENCLAW_GATEWAY_TOKEN
    unset OLLAMA_API_KEY OLLAMA_BASE_URL OLLAMA_HOST
    cd "$RUN_DIR/openclaw-cwd"
    start_bg openclaw node "$OPENCLAW_DIR/openclaw.mjs" gateway run
)
node "$LIB/prepare.mjs" wait "http://127.0.0.1:$OPENCLAW_PORT/v1/models" 300 openclaw-token || die "OpenClaw did not answer: $RUN_DIR/logs/openclaw.log"
node "$LIB/prepare.mjs" openclaw-seed "$OPENCLAW_STATE" || die "OpenClaw's seed turn failed: $RUN_DIR/logs/openclaw.log"

# ─── Hermes ─────────────────────────────────────────────────────────────────
log "Setting up Hermes $HERMES_REF"
HERMES_HOME_DIR=$RUN_DIR/hermes-home
node "$LIB/prepare.mjs" render "$HERE/harness/hermes-config.yaml" "$HERMES_HOME_DIR/config.yaml"
openssl rand -hex 32 >"$RUN_DIR/secrets/hermes-api-key"
# Hermes's runtime (its Python, tools and dependencies) is kept across runs; its home, the agent's state, is not.
export HERMES_RUNTIME_DIR=$BENCH_HOME/work/hermes-runtime
mkdir -p "$HERMES_RUNTIME_DIR"
# The checkout's `hermes` launcher, run by the runtime's own Python, is what `source ./activate` defines `hermes` as.
hermes_cmd() { "$HERMES_DIR/scripts/run-in-hermes-env" bash -c 'exec "${PYTHON:-python3}" "$0" "$@"' "$HERMES_DIR/hermes" "$@"; }
(
    export HERMES_HOME=$HERMES_HOME_DIR TERMINAL_CWD=$RUN_DIR/hermes-cwd
    unset OLLAMA_BASE_URL
    cd "$RUN_DIR/hermes-cwd"
    hermes_cmd plugins install web-ollama-cloud --ref "$PLUGIN_SHA" --enable --yes-deps
) >"$RUN_DIR/logs/hermes-setup.log" 2>&1 || die "Hermes's setup or plugin install failed: $RUN_DIR/logs/hermes-setup.log"
(
    export HERMES_HOME=$HERMES_HOME_DIR TERMINAL_CWD=$RUN_DIR/hermes-cwd OLLAMA_API_KEY=$OLLAMA_KEY
    export API_SERVER_HOST=127.0.0.1 API_SERVER_PORT=$HERMES_PORT
    API_SERVER_KEY=$(cat "$RUN_DIR/secrets/hermes-api-key")
    export API_SERVER_KEY
    unset OLLAMA_BASE_URL
    cd "$RUN_DIR/hermes-cwd"
    start_bg hermes "$HERMES_DIR/scripts/run-in-hermes-env" bash -c 'exec "${PYTHON:-python3}" "$0" "$@"' "$HERMES_DIR/hermes" gateway run
)
node "$LIB/prepare.mjs" wait "http://127.0.0.1:$HERMES_PORT/health" 300 || die "Hermes did not answer: $RUN_DIR/logs/hermes.log"

node - "$RUN_DIR/versions.json" "$OPENCLAW_COMMIT" "$HERMES_COMMIT" "$PLUGIN_SHA" "$JCLAW_PORT" <<'EOF'
const fs = require('fs')
const [file, openclaw, hermes, plugin, port] = process.argv.slice(2)
const v = JSON.parse(fs.readFileSync(file, 'utf8'))
v.openclaw.commit = openclaw
v.hermes.commit = hermes
v.hermesPlugin = plugin
v.node = process.versions.node
fetch(`http://127.0.0.1:${port}/api/status`).then(r => r.json()).then(s => {
  v.jclaw.reported = s.applicationVersion
  fs.writeFileSync(file, JSON.stringify(v, null, 1))
})
EOF

# ─── The run ────────────────────────────────────────────────────────────────
driver_args=(--runs "$RUNS")
[ -n "$TASKS" ] && driver_args+=(--tasks "$TASKS")
log "Running: $RUNS runs${TASKS:+ of $TASKS} (progress in $RUN_DIR/logs/driver.log)"
node "$LIB/driver.mjs" "${driver_args[@]}" >"$RUN_DIR/logs/driver.log" 2>&1 || die "the driver failed: $RUN_DIR/logs/driver.log"
stop_all

log "Analyzing"
node "$LIB/analyze.mjs"
conformance=0
node "$LIB/conformance.mjs" || conformance=$?
history=$BENCH_HOME/history.jsonl
[ "$SMOKE" = 1 ] && history=''
BENCH_HISTORY=$history node "$LIB/report.mjs"
printf '\nReport: %s/report.md\n' "$RUN_DIR"
exit "$conformance"
