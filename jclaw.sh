#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
FRONTEND_PID_FILE="frontend.pid"

usage() {
    cat <<EOF
Usage: jclaw.sh [options] <setup|start|stop|restart|status|logs|loadtest|test>

Commands:
  setup     One-time per-clone bootstrap: wires git hooks (.githooks/),
            installs frontend dependencies (so pre-commit's lint-staged works),
            and verifies both 'origin' and 'github' remotes exist. Run once
            after every fresh clone. Idempotent — safe to re-run.
  start     Start the Play backend and Nuxt frontend
  stop      Stop the running Play backend and Nuxt frontend
  restart   Stop and start (combines stop + start)
  status    Show whether backend and frontend are running
  logs      Tail the production application log
  loadtest  Drive the in-process load-test harness against /api/chat/stream
  test      Run backend (play auto-test) + frontend (pnpm test) and report a
            consolidated pass/fail summary. Exits non-zero on any failure.

Options:
  --dev                   Run in development mode (play run + pnpm dev)
  --deploy <dir>          Package with play dist, copy to <dir>, and run in production
  --backend-port <port>   Play backend port (default: 9000)
  --frontend-port <port>  Nuxt dev server port, dev mode only (default: 3000)
  --play-pool <N>         Explicit Play invocation pool size. When omitted, the
                          JVM-side plugins.PlayPoolAutoSizer auto-sizes to
                          max(8, cores*2) at startup (respects container CPU
                          limits). Pass an integer to force a specific value.

Environment:
  JCLAW_JVM_HEAP          Production heap size (default: 2g). Sets -Xms == -Xmx.
                          Example: JCLAW_JVM_HEAP=4g ./jclaw.sh start

Load-test options (only used with the 'loadtest' command):
  --concurrency <n>       Parallel workers (default: 10)
  --iterations <n>        Requests per worker (default: 5)
  --ttft-ms <n>           Simulated time-to-first-token in ms (default: 100)
  --tokens-per-second <n> Simulated token throughput (default: 50)
  --response-tokens <n>   Tokens per simulated response (default: 40)
  --clean                 Delete loadtest conversations/events from DB instead of running a test

Examples:
  ./jclaw.sh setup                                    # One-time setup after fresh clone
  ./jclaw.sh --dev start                              # Start in dev mode
  ./jclaw.sh --dev --backend-port 8080 start          # Dev mode with custom backend port
  ./jclaw.sh start                                    # Start production in current directory
  ./jclaw.sh --deploy /tmp start                      # Build, deploy to /tmp/jclaw, and start
  ./jclaw.sh --deploy /tmp --backend-port 8080 start  # Deploy with custom port
  ./jclaw.sh --dev stop                               # Stop dev mode services
  ./jclaw.sh --deploy /tmp stop                       # Stop services in /tmp/jclaw
  ./jclaw.sh stop                                     # Stop production in current directory
  ./jclaw.sh loadtest                                 # Drive default 10×5 load test against :9000
  ./jclaw.sh --concurrency 50 --iterations 20 loadtest
EOF
}

# Render the JClaw landing screen on bare invocation: ASCII-art logo in
# emerald, one-line product blurb, and pointers at the two commands every
# new contributor needs (setup for first-time wiring, --help for the full
# reference). Always runs when ./jclaw.sh is invoked with no command —
# the previous design suppressed it after the first setup, but that
# hid the intro from anyone who wanted to see it again.
#
# TTY-aware: ANSI colors only when stdout is an interactive terminal,
# so piping into less or redirecting into a logfile doesn't bury escape
# codes in the output. Modern terminals (iTerm2, macOS Terminal,
# Windows Terminal, VS Code/Cursor integrated, gnome-terminal) render
# 24-bit true color; older 256-color terminals fall back to nearest
# match, still readable.
show_intro() {
    local emerald='' cyan='' yellow='' dim='' bold='' reset=''
    if [[ -t 1 ]]; then
        # Tailwind emerald-400 (#34d399) matches the bg-emerald-* accents
        # used elsewhere in the project (Settings UI toggles, "active"
        # badges) — same color story everywhere reads as one product.
        emerald=$'\033[38;2;52;211;153m'
        cyan=$'\033[1;36m'
        yellow=$'\033[1;33m'
        dim=$'\033[2m'
        bold=$'\033[1m'
        reset=$'\033[0m'
    fi

    cat <<EOF

${emerald}     ██╗ ██████╗██╗      █████╗ ██╗    ██╗
     ██║██╔════╝██║     ██╔══██╗██║    ██║
     ██║██║     ██║     ███████║██║ █╗ ██║
██   ██║██║     ██║     ██╔══██║██║███╗██║
╚█████╔╝╚██████╗███████╗██║  ██║╚███╔███╔╝
 ╚════╝  ╚═════╝╚══════╝╚═╝  ╚═╝ ╚══╝╚══╝ ${reset}

${dim}Java-first AI automation platform — Play 1.x backend, Nuxt 3 SPA,
LLM agents, OCR, web tools.${reset}

  ${cyan}./jclaw.sh setup${reset}     One-time setup for a fresh clone
                       (validates prereqs, wires git hooks, installs deps,
                        adds github remote)
  ${cyan}./jclaw.sh --help${reset}    Full command reference

EOF

    # First-run hint footer. When core.hooksPath != .githooks (the canonical
    # signal that do_setup hasn't run yet), append a single highlighted line
    # nudging the user at the setup command. Composable with everything —
    # no interactivity, no stateful prompt, no scriptability hazard. Once
    # setup runs, the hint disappears on its own.
    #
    # `|| true` after the git config substitution is load-bearing under
    # `set -e`: outside a git repo (e.g. running jclaw.sh from a tarball
    # extract or an ops box that copied the script standalone), git exits
    # 128 and would otherwise kill the script. The empty hooks_path
    # correctly trips the hint in that case.
    local hooks_path
    hooks_path=$(/usr/bin/git config --local core.hooksPath 2>/dev/null || true)
    if [[ "$hooks_path" != ".githooks" ]]; then
        echo "${yellow}${bold}→ Setup hasn't run on this clone yet. Run ${cyan}./jclaw.sh setup${yellow} to wire things up.${reset}"
        echo ""
    fi
}

# Parse arguments
DEPLOY_DIR=""
DEV_MODE=false
BACKEND_PORT="9000"
FRONTEND_PORT="3000"
PLAY_POOL=""
COMMAND=""
LT_CONCURRENCY="10"
LT_ITERATIONS="5"
LT_TTFT_MS="100"
LT_TOKENS_PER_SECOND="50"
LT_RESPONSE_TOKENS="40"
LT_CLEAN=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --dev)
            DEV_MODE=true
            shift
            ;;
        --deploy)
            DEPLOY_DIR="$2"
            shift 2
            ;;
        --backend-port)
            BACKEND_PORT="$2"
            shift 2
            ;;
        --frontend-port)
            FRONTEND_PORT="$2"
            shift 2
            ;;
        --play-pool)
            PLAY_POOL="$2"
            shift 2
            ;;
        --concurrency)
            LT_CONCURRENCY="$2"
            shift 2
            ;;
        --iterations)
            LT_ITERATIONS="$2"
            shift 2
            ;;
        --ttft-ms)
            LT_TTFT_MS="$2"
            shift 2
            ;;
        --tokens-per-second)
            LT_TOKENS_PER_SECOND="$2"
            shift 2
            ;;
        --response-tokens)
            LT_RESPONSE_TOKENS="$2"
            shift 2
            ;;
        --clean)
            LT_CLEAN=true
            shift
            ;;
        setup|start|stop|restart|status|logs|loadtest|test)
            COMMAND="$1"
            shift
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        *)
            echo "Unknown argument: $1"
            usage
            exit 1
            ;;
    esac
done

# Bare invocation (no command given) is a deliberate landing screen, not
# an error — render the intro and exit cleanly. The --help flag above
# is the path to the full command reference; the intro just points at
# it. This frees `./jclaw.sh status` and friends from rendering a
# 30-line banner on every invocation.
if [[ -z "$COMMAND" ]]; then
    show_intro
    exit 0
fi

# Validate flag combinations
if [[ "$DEV_MODE" == true && -n "$DEPLOY_DIR" ]]; then
    echo "Error: --dev and --deploy cannot be used together."
    exit 1
fi

# Resolve an explicit -Dplay.pool=N override from $PLAY_POOL. Prints the JVM
# arg on stdout, or an empty string when no override was requested. The
# default (no flag) is intentionally empty so the JVM-side plugin
# plugins.PlayPoolAutoSizer performs the auto-sizing — that way bare-metal,
# Docker, and Kubernetes all get the same behavior (respecting container
# CPU quotas via Runtime.availableProcessors()).
resolve_play_pool() {
    local requested="$PLAY_POOL"
    [[ -z "$requested" ]] && return 0

    if [[ "$requested" =~ ^[0-9]+$ ]] && (( requested > 0 )); then
        echo "-Dplay.pool=$requested"
    else
        echo "Error: --play-pool expects a positive integer, got '$requested'" >&2
        exit 1
    fi
}

# Route every `pnpm` invocation through corepack so the version pinned
# in frontend/package.json's `packageManager` field is authoritative,
# regardless of what's installed globally. corepack presence is enforced
# upstream by check_prereqs at every dispatch entry point, so this
# function never has to defend against the corepack-missing case.
pnpm() {
    corepack pnpm "$@"
}

# Read the packageManager pin from frontend/package.json. Echoes the raw
# value (e.g. "pnpm@10.33.1+sha512.abc...") on stdout, or empty when the
# field or file is absent. Used by both the setup-time pin migration and
# the start-time validation guard.
read_pnpm_pin() {
    local frontend_dir="$JCLAW_DIR/frontend"
    [[ -f "$frontend_dir/package.json" ]] || return 0
    sed -n 's/.*"packageManager": *"\([^"]*\)".*/\1/p' \
        "$frontend_dir/package.json" | head -1
}

# Setup-time only: ensure the packageManager pin includes a +sha512-...
# integrity hash. Idempotent — already-hashed pins land in the no-op
# branch. Called from do_setup, never from start paths, so the
# package.json mutation is scoped to an explicit "I'm setting up this
# clone" action rather than appearing as a surprise during start.
setup_corepack_pnpm_pin() {
    local frontend_dir="$JCLAW_DIR/frontend"
    [[ -d "$frontend_dir" && -f "$frontend_dir/package.json" ]] || return 0

    local current_pin
    current_pin=$(read_pnpm_pin)
    if [[ -z "$current_pin" ]]; then
        echo "    Warning: no packageManager pin in frontend/package.json — nothing to migrate."
        return 0
    fi

    if [[ "$current_pin" == *"+sha"* ]]; then
        echo "    pnpm pin already includes integrity hash — no migration needed."
        return 0
    fi

    # `corepack use pnpm@VERSION` re-pins to the same version string and
    # appends the +sha512-... hash. Rewrites frontend/package.json — the
    # mutation is the whole point of running setup.
    local pin_version="${current_pin#pnpm@}"
    echo "    Adding pnpm integrity hash via corepack use..."
    echo "      Old pin: $current_pin"
    if ! (cd "$frontend_dir" && corepack use "pnpm@$pin_version" >/dev/null 2>&1); then
        echo "Error: corepack use failed; could not add integrity hash."
        echo "       Try manually: cd frontend && corepack use pnpm@$pin_version"
        exit 1
    fi
    local new_pin
    new_pin=$(read_pnpm_pin)
    echo "      New pin: $new_pin"
    echo "      Note: frontend/package.json was modified — review and commit."
}

# Start-time only: validate that the pinned pnpm is present locally and
# verifies against its +sha hash. Read-only — never mutates package.json.
# Hard-fails on missing hash with an actionable error pointing at setup,
# so the security gate doesn't silently degrade to no-op when someone
# hand-edits the pin and drops the hash.
validate_corepack_pnpm() {
    local frontend_dir="$JCLAW_DIR/frontend"
    [[ -d "$frontend_dir" && -f "$frontend_dir/package.json" ]] || return 0

    local current_pin
    current_pin=$(read_pnpm_pin)
    if [[ -z "$current_pin" ]]; then
        echo "    Warning: no packageManager pin in frontend/package.json — cannot validate pnpm."
        return 0
    fi

    if [[ "$current_pin" != *"+sha"* ]]; then
        echo "Error: pnpm pin lacks integrity hash (frontend/package.json: packageManager=$current_pin)."
        echo "       Without the +sha512-... hash, corepack cannot verify the downloaded"
        echo "       tarball against tampering — refusing to launch."
        echo ""
        echo "       Fix with one of:"
        echo "         ./jclaw.sh setup"
        echo "         cd frontend && corepack use ${current_pin}"
        exit 1
    fi

    # `corepack install` reads packageManager, downloads the version if
    # missing, and verifies it against the +sha hash. Output is suppressed
    # on success because corepack is noisy on cached hits.
    local install_log install_status
    install_log=$(cd "$frontend_dir" && corepack install 2>&1)
    install_status=$?
    if (( install_status != 0 )); then
        echo "Error: corepack install failed — pnpm hash validation may have failed."
        echo "       frontend/package.json packageManager pin: $current_pin"
        echo "       corepack output:"
        echo "$install_log" | sed 's/^/         /'
        exit 1
    fi
    echo "==> pnpm validated via corepack ($current_pin)"
}

# Verify Java 25+ is available. Required for Play backend (compile, run, test).
check_java() {
    local java_version
    java_version=$(java -version 2>&1 | head -1 | grep -oE '"[0-9]+'| tr -d '"')
    if [[ -z "$java_version" ]]; then
        echo "Error: Java not found. JDK 25+ is required."
        exit 1
    fi
    if [[ "$java_version" -lt 25 ]]; then
        echo "Error: Java $java_version found, but JDK 25+ is required."
        echo "       Set JAVA_HOME or use jenv to configure JDK 25."
        exit 1
    fi
}

# Verify Node.js 20+ is available. Required for the Nuxt dev server, the
# prod SPA build (npx nuxi generate), and corepack itself.
check_node() {
    if ! command -v node >/dev/null 2>&1; then
        echo "Error: node not found. Node.js 20+ is required."
        echo "       Install from https://nodejs.org/ (or use nvm/fnm/asdf)."
        exit 1
    fi
    local node_major
    node_major=$(node -v | sed -E 's/^v([0-9]+).*/\1/')
    if [[ -z "$node_major" || "$node_major" -lt 20 ]]; then
        echo "Error: Node $(node -v) found, but Node.js 20+ is required."
        exit 1
    fi
}

# Verify corepack is on PATH. Ships with Node 20+ by default but some
# distros (Debian's `nodejs` package, certain Nix profiles) strip it. We
# use it to validate the pnpm pin's +sha integrity hash on every start —
# without it, the security gate goes inert.
check_corepack() {
    if ! command -v corepack >/dev/null 2>&1; then
        echo "Error: corepack not found. It ships with Node 20+ — your install"
        echo "       may have stripped it. Install with: npm install -g corepack"
        exit 1
    fi
}

# Verify the Play 1.x command is on PATH. Backend builds, dev runs, prod
# starts, dependency syncs, and the test runner all shell out to play.
check_play() {
    if ! command -v play >/dev/null 2>&1; then
        echo "Error: play not found. Play Framework 1.x is required."
        echo "       Install Abundent's fork: https://github.com/tsukhani/play1"
        echo "       Then add the play command to your PATH."
        exit 1
    fi
}

# Verify Python 3.9+ is available. Strictly speaking this is transitively
# enforced by check_play (the play command is itself a Python wrapper
# script), but a too-old or broken Python produces cryptic SyntaxErrors
# from inside the wrapper. The explicit check here gives operators a
# clean "Python X.Y is too old" diagnostic instead.
check_python() {
    local python_cmd=""
    if command -v python3 >/dev/null 2>&1; then
        python_cmd=python3
    elif command -v python >/dev/null 2>&1; then
        python_cmd=python
    else
        echo "Error: python not found. Python 3.9+ is required for the play command."
        exit 1
    fi
    local py_version
    py_version=$("$python_cmd" -c 'import sys; print(f"{sys.version_info.major}.{sys.version_info.minor}")' 2>/dev/null)
    if [[ -z "$py_version" ]]; then
        echo "Error: Could not determine Python version from $python_cmd."
        exit 1
    fi
    local major minor
    IFS=. read -r major minor <<< "$py_version"
    if (( major < 3 )) || { (( major == 3 )) && (( minor < 9 )); }; then
        echo "Error: Python $py_version found, but Python 3.9+ is required for the play command."
        exit 1
    fi
}

# Single entry point for prerequisite validation. Called from setup and
# from each runtime entry point (start/restart/test) so an environment
# missing a dependency fails at the dispatch level with a clean
# diagnostic, instead of cryptically halfway through play deps --sync or
# pnpm install. Cheap (5 fork-execs, ~50ms total on warm caches).
#
# Order matters — foundational toolchains first, derived tools after,
# so each successful check is unambiguous. If python is missing, we
# want "Python not found" before "Play not found", because Play
# happens to be a Python wrapper script — checking play first would
# pass (the binary exists on PATH) only to have it fail later inside
# the wrapper with a cryptic Python error. Same logic for corepack,
# which ships inside Node's binary distribution.
#
# Dependency graph:
#   java     — standalone
#   python   — standalone (Play wrapper script depends on it)
#   node     — standalone (corepack ships inside it)
#   play     — depends on python
#   corepack — depends on node
check_prereqs() {
    # Foundational — no dependencies on other checks
    check_java
    check_python
    check_node

    # Derived — each depends on a foundational check above
    check_play       # Python wrapper script; check_python must pass first
    check_corepack   # Ships with Node; check_node must pass first
}

# Determine the working directory
if [[ -n "$DEPLOY_DIR" ]]; then
    JCLAW_DIR="$DEPLOY_DIR/jclaw"
elif [[ "$DEV_MODE" == true ]]; then
    JCLAW_DIR="$SCRIPT_DIR"
else
    JCLAW_DIR="$(pwd)"
fi

# ─── First-time setup ───

# Idempotent — safe to re-run on a clone that's already configured. The
# things this fixes are all per-clone state that don't survive a fresh
# `git clone` or `rm -rf && git clone` cycle, because they live in
# `.git/config` (which git refuses to track) or under `frontend/node_modules/`
# (gitignored). Running this once after a fresh clone restores the wiring
# the rest of the workflow assumes.
do_setup() {
    cd "$JCLAW_DIR"

    if [[ ! -f "conf/application.conf" ]]; then
        echo "Error: Not a JClaw directory (conf/application.conf not found)"
        echo "       Run from the jclaw directory."
        exit 1
    fi

    echo "==> Checking prerequisites..."
    check_prereqs
    # Print in the same dependency-graph order as check_prereqs runs them:
    # foundational toolchains first, then the wrappers/tools that ride them.
    echo "    Java:     $(java -version 2>&1 | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"
    if command -v python3 >/dev/null 2>&1; then
        echo "    Python:   $(python3 -V 2>&1)"
    else
        echo "    Python:   $(python -V 2>&1)"
    fi
    echo "    Node:     $(node -v)"
    echo "    Play:     $(command -v play)"
    echo "    Corepack: $(corepack -v 2>/dev/null || echo 'present')"

    echo ""
    echo "==> Wiring git hooks (.githooks/)..."
    /usr/bin/git config --local core.hooksPath .githooks
    echo "    core.hooksPath = $(/usr/bin/git config --local core.hooksPath)"

    echo ""
    echo "==> Pinning pnpm via corepack (with integrity hash)..."
    setup_corepack_pnpm_pin

    echo ""
    echo "==> Installing frontend dependencies (so pre-commit's lint-staged is available)..."
    if [[ ! -d "frontend" ]]; then
        echo "    Skipped: frontend/ directory not found."
    elif ! command -v pnpm &>/dev/null; then
        echo "    Warning: pnpm not on PATH. Install with: npm install -g pnpm"
        echo "             Then re-run: ./jclaw.sh setup"
    else
        (cd frontend && (pnpm install --frozen-lockfile 2>/dev/null || pnpm install))
    fi

    echo ""
    echo "==> Installing BMAD (slash commands → .claude/skills/)..."
    # _bmad/ and .claude/skills/ are both gitignored — they hold ~270 BMAD
    # install files and ~42 generated skill manifests respectively. Tracking
    # them caused massive diffs every time BMAD upgraded between minor
    # versions (e.g. 6.2.2 → 6.5.0 deleted/moved hundreds of files), so we
    # let setup regenerate them instead. Quick-update is the lightest action
    # that keeps existing module settings AND re-registers the IDE; -y skips
    # the prompts that would otherwise hang in non-interactive contexts;
    # --directory pins it to this clone (otherwise it asks).
    if ! command -v npx &>/dev/null; then
        echo "    Warning: npx not on PATH. Install Node.js to enable BMAD."
        echo "             Then re-run: ./jclaw.sh setup"
    else
        npx bmad-method install \
            --directory "$JCLAW_DIR" \
            --action quick-update \
            --tools claude-code \
            -y 2>&1 | tail -5
    fi

    echo ""
    echo "==> Checking git remotes..."
    if /usr/bin/git remote get-url origin >/dev/null 2>&1; then
        echo "    origin: $(/usr/bin/git remote get-url origin)"
    else
        echo "    Warning: 'origin' remote not configured (unusual for a fresh clone)."
    fi
    # JClaw is an internal Abundent project with one canonical GitHub mirror,
    # so we auto-add the remote rather than prompting. /deploy requires both
    # `origin` (Bitbucket) and `github` (GitHub) — without this auto-add, every
    # fresh clone would have to read the help text and re-run a manual command
    # before the first deploy. The default URL matches the badge in README.md
    # and the JCLAW_GITHUB_REMOTE env var is the override hatch for the rare
    # contributor working from a personal fork.
    local github_url="${JCLAW_GITHUB_REMOTE:-https://github.com/tsukhani/jclaw.git}"
    if /usr/bin/git remote get-url github >/dev/null 2>&1; then
        echo "    github: $(/usr/bin/git remote get-url github)"
    else
        /usr/bin/git remote add github "$github_url"
        echo "    github: $github_url (added)"
    fi

    echo ""
    echo "==> Setup complete."
    echo ""
    echo "Next steps:"
    echo "  Start dev:        ./jclaw.sh --dev start"
    echo "  Start prod:       ./jclaw.sh start"
    echo "  Run tests:        ./jclaw.sh test"
}

# ─── Production deploy ───

do_deploy() {
    echo "==> Packaging application..."
    cd "$SCRIPT_DIR"
    play dist

    local zip_file="$SCRIPT_DIR/dist/jclaw.zip"
    if [[ ! -f "$zip_file" ]]; then
        echo "Error: play dist did not create $zip_file"
        exit 1
    fi

    echo "==> Deploying to $DEPLOY_DIR..."
    mkdir -p "$DEPLOY_DIR"

    # Clean previous deployment
    if [[ -d "$JCLAW_DIR" ]]; then
        echo "    Removing previous deployment at $JCLAW_DIR"
        rm -rf "$JCLAW_DIR"
    fi

    cp "$zip_file" "$DEPLOY_DIR/"
    cd "$DEPLOY_DIR"
    unzip -q jclaw.zip
    rm -f jclaw.zip

    echo "==> Deployment ready at $JCLAW_DIR"
}

# ─── Production start/stop ───

do_start_prod() {
    cd "$JCLAW_DIR"

    if [[ ! -f "conf/application.conf" ]]; then
        echo "Error: Not a JClaw directory (conf/application.conf not found)"
        echo "       Run from the jclaw directory or use --deploy <dir>"
        exit 1
    fi

    # Stop any running instance before rebuilding
    if [[ -f "server.pid" ]] && kill -0 "$(cat server.pid)" 2>/dev/null; then
        echo "==> Stopping running instance (pid: $(cat server.pid))..."
        play stop
    fi

    # Refuse to start if the port is held by anything (a foreign process from
    # a different deploy dir, or a prior instance still inside its shutdown
    # hooks). The pid-file check above only catches OUR own server.pid; a
    # JVM running from /tmp/JClaw/ — say, a pre-existing prod deploy — has
    # its own pid file there and is invisible to us. Without this guard,
    # Play tries to bind, fails with "Could not bind on port 9000", aborts
    # startup → ShutdownJob fires → JPA work in the shutdown sequence
    # produces a giant Hibernate trace that buries the real one-line error.
    if lsof -ti :"$BACKEND_PORT" >/dev/null 2>&1; then
        local holder
        holder=$(lsof -ti :"$BACKEND_PORT" 2>/dev/null | tr '\n' ' ')
        echo "Error: Port $BACKEND_PORT is already in use (pid: ${holder% })."
        echo "       Run '$0 ${DEPLOY_DIR:+--deploy $DEPLOY_DIR }stop' first, or kill the holder."
        exit 1
    fi

    echo "==> Resolving backend dependencies..."
    play deps --sync

    # Auto-precompile when the existing precompiled/ classes are stale or
    # missing. Play 1.x's `play start --%prod` loads precompiled/ as-is
    # and does NOT recompile when sources have changed — without this
    # check, restarts silently boot the prior binary and code changes
    # don't take effect. The -newer test uses the precompiled/java
    # directory's mtime as the staleness threshold (Play refreshes it on
    # each successful precompile), and -print -quit stops the walk at
    # the first match so a clean tree costs milliseconds.
    if [[ ! -d precompiled/java ]] \
        || [[ -n "$(find app -name '*.java' -newer precompiled/java -print -quit 2>/dev/null)" ]]; then
        echo "==> Precompiling backend (source newer than precompiled classes)..."
        play precompile
    else
        echo "==> Skipping precompile (precompiled classes are up to date)"
    fi

    validate_corepack_pnpm

    echo "==> Installing frontend dependencies..."
    cd "$JCLAW_DIR/frontend"
    pnpm install --frozen-lockfile 2>/dev/null || pnpm install

    echo "==> Generating static SPA..."
    npx nuxi generate

    echo "==> Copying SPA build to public/spa/..."
    rm -rf "$JCLAW_DIR/public/spa"
    cp -r .output/public "$JCLAW_DIR/public/spa"

    cd "$JCLAW_DIR"
    mkdir -p "$JCLAW_DIR/logs"

    # JVM tuning for production. Rationale for each flag:
    #   - ZGC: sub-millisecond pause collector. Matters because SSE streams
    #     hold connections open for seconds/tens of seconds; a 100 ms G1
    #     pause would stutter token output to the client.
    #   - Fixed heap (-Xms == -Xmx): avoids heap-resize pauses under load.
    #     Default 2 GB; override with JCLAW_JVM_HEAP env var (e.g. 4g).
    #   - HeapDumpOnOutOfMemoryError + ExitOnOutOfMemoryError: dump for
    #     postmortem, then exit cleanly so a process manager can restart.
    #   - MaxDirectMemorySize: caps Netty off-heap buffer allocation so a
    #     leak here can't exhaust native memory unnoticed.
    #   - DNS TTLs: LLM providers rotate endpoints via DNS; 30 s positive
    #     TTL keeps us close to current, 0 s negative TTL prevents caching
    #     transient lookup failures indefinitely.
    #   - GC log: rotated, time-stamped, very low overhead; invaluable for
    #     diagnosing GC-related latency spikes.
    # Play 1.x passes unrecognized args straight to the JVM (see
    # framework/pym/play/application.py:java_cmd), so these become the
    # actual java command line — no -J prefix needed.
    local heap="${JCLAW_JVM_HEAP:-2g}"
    local jvm_opts=(
        "-Xms${heap}"
        "-Xmx${heap}"
        "-XX:+UseZGC"
        "-XX:+HeapDumpOnOutOfMemoryError"
        "-XX:HeapDumpPath=$JCLAW_DIR/logs/heap-oom.hprof"
        "-XX:+ExitOnOutOfMemoryError"
        "-XX:MaxDirectMemorySize=256m"
        "-Dnetworkaddress.cache.ttl=30"
        "-Dnetworkaddress.cache.negative.ttl=0"
        "-Xlog:gc*:file=$JCLAW_DIR/logs/gc.log:time,uptime,level,tags:filecount=5,filesize=10M"
    )

    local pool_arg
    pool_arg=$(resolve_play_pool)
    [[ -n "$pool_arg" ]] && jvm_opts+=("$pool_arg")

    echo "==> Starting Play backend on port $BACKEND_PORT (prod)..."
    echo "    JVM: ${heap} heap, ZGC, GC log → logs/gc.log"
    if [[ -n "$pool_arg" ]]; then
        echo "    Play invocation pool: ${pool_arg#-Dplay.pool=} (explicit override)"
    else
        echo "    Play invocation pool: auto (sized by PlayPoolAutoSizer at startup)"
    fi
    play start --%prod --http.port="$BACKEND_PORT" "${jvm_opts[@]}"

    echo ""
    echo "JClaw is running (production):"
    echo "  App: http://localhost:$BACKEND_PORT  (pid: $(cat "$JCLAW_DIR/server.pid"))"
    echo ""
    echo "Tail logs with: $0 ${DEPLOY_DIR:+--deploy $DEPLOY_DIR }logs"
    echo "Stop with:      $0 ${DEPLOY_DIR:+--deploy $DEPLOY_DIR }stop"
}

do_stop_prod() {
    cd "$JCLAW_DIR"

    if [[ -f "server.pid" ]]; then
        echo "==> Stopping Play backend..."
        play stop
        echo ""
        echo "JClaw stopped."
    else
        echo "Nothing to stop — JClaw does not appear to be running in $JCLAW_DIR"
    fi
}

# ─── Dev mode start/stop ───

do_start_dev() {
    cd "$JCLAW_DIR"

    if [[ ! -f "conf/application.conf" ]]; then
        echo "Error: Not a JClaw directory (conf/application.conf not found)"
        exit 1
    fi

    # Check if already running
    if [[ -f "server.pid" ]] && kill -0 "$(cat server.pid)" 2>/dev/null; then
        echo "Error: Play backend is already running (pid: $(cat server.pid))"
        exit 1
    fi

    # Refuse to start if the port is held by anything (a foreign process,
    # or a prior instance still inside its shutdown hooks). Without this,
    # Play silently fails to bind, exits, and the polling loop below would
    # see a stale listener and falsely declare success.
    if lsof -ti :"$BACKEND_PORT" >/dev/null 2>&1; then
        local holder
        holder=$(lsof -ti :"$BACKEND_PORT" 2>/dev/null | tr '\n' ' ')
        echo "Error: Port $BACKEND_PORT is already in use (pid: ${holder% })."
        echo "       Run '$0 --dev stop' first, or kill the holder."
        exit 1
    fi

    # Ensure dependencies are installed
    validate_corepack_pnpm

    echo "==> Checking frontend dependencies..."
    cd "$JCLAW_DIR/frontend"
    pnpm install --frozen-lockfile 2>/dev/null || pnpm install
    cd "$JCLAW_DIR"

    echo "==> Resolving backend dependencies..."
    play deps --sync

    local pool_arg
    pool_arg=$(resolve_play_pool)

    echo "==> Starting Play backend on port $BACKEND_PORT (dev)..."
    if [[ -n "$pool_arg" ]]; then
        echo "    Play invocation pool: ${pool_arg#-Dplay.pool=} (explicit override)"
        nohup play run --http.port="$BACKEND_PORT" "$pool_arg" > "$JCLAW_DIR/logs/backend-dev.out" 2>&1 &
    else
        echo "    Play invocation pool: auto (sized by PlayPoolAutoSizer at startup)"
        nohup play run --http.port="$BACKEND_PORT" > "$JCLAW_DIR/logs/backend-dev.out" 2>&1 &
    fi
    local play_pid=$!
    # play run doesn't create server.pid — store the wrapper pid ourselves
    echo "$play_pid" > "$JCLAW_DIR/server.pid"

    # Wait for backend to be ready. Three exit conditions, in priority
    # order: (1) wrapper died → fail with log tail; (2) port responds →
    # success; (3) timeout → fail. The original loop conflated (1) and
    # (3) and treated any listener on the port as our process — letting
    # a still-shutting-down prior instance mask a fresh bind failure.
    echo "    Waiting for backend to start..."
    local waited=0
    while true; do
        if ! kill -0 "$play_pid" 2>/dev/null; then
            echo "Error: Play backend exited during startup (pid $play_pid no longer alive)."
            echo "       Last lines of logs/backend-dev.out:"
            tail -20 "$JCLAW_DIR/logs/backend-dev.out" 2>/dev/null | sed 's/^/         /'
            rm -f "$JCLAW_DIR/server.pid"
            exit 1
        fi
        if curl -s -o /dev/null "http://localhost:$BACKEND_PORT" 2>/dev/null; then
            break
        fi
        sleep 1
        waited=$((waited + 1))
        if [[ $waited -ge 60 ]]; then
            echo "Error: Backend did not start within 60 seconds."
            echo "       Check logs/backend-dev.out for details."
            kill_tree "$play_pid"
            rm -f "$JCLAW_DIR/server.pid"
            exit 1
        fi
    done

    echo "==> Starting Nuxt dev server on port $FRONTEND_PORT..."
    cd "$JCLAW_DIR/frontend"
    PORT="$FRONTEND_PORT" JCLAW_BACKEND_PORT="$BACKEND_PORT" nohup pnpm dev > "$JCLAW_DIR/logs/frontend-dev.out" 2>&1 &
    echo $! > "$JCLAW_DIR/$FRONTEND_PID_FILE"

    echo ""
    echo "JClaw is running (dev):"
    echo "  Backend:  http://localhost:$BACKEND_PORT  (pid: $play_pid)"
    echo "  Frontend: http://localhost:$FRONTEND_PORT  (pid: $(cat "$JCLAW_DIR/$FRONTEND_PID_FILE"))"
    echo "  Logs:     logs/backend-dev.out, logs/frontend-dev.out"
    echo ""
    echo "Tail logs with: $0 --dev logs"
    echo "Stop with:      $0 --dev stop"
}

kill_tree() {
    local pid=$1
    local children
    children=$(pgrep -P "$pid" 2>/dev/null) || true
    for child in $children; do
        kill_tree "$child"
    done
    kill "$pid" 2>/dev/null || true
}

# Block until $port has no listener, or $timeout seconds elapse.
# Returns 0 when freed, 1 on timeout. We rely on this during restart
# because Play's shutdown hooks (telegram cooldown, DB pool, etc.) hold
# the socket for several seconds after SIGTERM — without waiting, the
# next `play run` races the dying JVM and silently fails to bind 9000.
wait_for_port_free() {
    local port=$1
    local timeout=${2:-30}
    local waited=0
    while lsof -ti :"$port" >/dev/null 2>&1; do
        sleep 1
        waited=$((waited + 1))
        (( waited >= timeout )) && return 1
    done
    return 0
}

do_stop_dev() {
    cd "$JCLAW_DIR"

    local stopped=0

    # Stop frontend (pnpm dev)
    if [[ -f "$FRONTEND_PID_FILE" ]]; then
        local fpid
        fpid=$(cat "$FRONTEND_PID_FILE")
        if kill -0 "$fpid" 2>/dev/null; then
            echo "==> Stopping Nuxt dev server (pid: $fpid)..."
            kill_tree "$fpid"
            rm -f "$FRONTEND_PID_FILE"
            stopped=1
        else
            echo "    Frontend not running (stale pid file)"
            rm -f "$FRONTEND_PID_FILE"
        fi
    else
        echo "    No frontend pid file found"
    fi

    # Clean up any orphan still holding the frontend port
    local orphan
    orphan=$(lsof -ti :"$FRONTEND_PORT" 2>/dev/null) || true
    if [[ -n "$orphan" ]]; then
        echo "    Cleaning up orphan process on port $FRONTEND_PORT (pid: $orphan)..."
        kill $orphan 2>/dev/null || true
    fi

    # Stop backend (play run — we manage the pid file, not Play). The
    # wrapper may have grandchildren (Python `play` → JVM → forked workers),
    # so kill_tree's recursive descent is necessary; pkill -P only catches
    # direct children. We then BLOCK until port 9000 is actually free so a
    # subsequent restart can't race the dying JVM's shutdown hooks.
    if [[ -f "server.pid" ]]; then
        local bpid
        bpid=$(cat "server.pid")
        if kill -0 "$bpid" 2>/dev/null; then
            echo "==> Stopping Play backend (pid: $bpid)..."
            kill_tree "$bpid"

            if ! wait_for_port_free "$BACKEND_PORT" 30; then
                local stragglers
                stragglers=$(lsof -ti :"$BACKEND_PORT" 2>/dev/null) || true
                if [[ -n "$stragglers" ]]; then
                    echo "    Port $BACKEND_PORT still bound after 30s; SIGKILL on residual pids: $stragglers"
                    kill -9 $stragglers 2>/dev/null || true
                    wait_for_port_free "$BACKEND_PORT" 5 || true
                fi
            fi

            rm -f "server.pid"
            stopped=1
        else
            echo "    Backend not running (stale pid file)"
            rm -f "server.pid"
        fi
    else
        echo "    No backend pid file found"
    fi

    if [[ $stopped -eq 1 ]]; then
        echo ""
        echo "JClaw stopped."
    else
        echo ""
        echo "Nothing to stop — JClaw does not appear to be running in $JCLAW_DIR"
    fi
}

# ─── Status ───

do_status() {
    cd "$JCLAW_DIR"

    local mode="production"
    [[ "$DEV_MODE" == true ]] && mode="dev"

    echo "JClaw status ($JCLAW_DIR, $mode):"
    echo ""

    # Backend
    if [[ -f "server.pid" ]] && kill -0 "$(cat server.pid)" 2>/dev/null; then
        echo "  Backend:  running (pid: $(cat server.pid))"
    else
        echo "  Backend:  stopped"
    fi

    # Frontend (dev mode only — production serves SPA from Play)
    if [[ "$DEV_MODE" == true ]]; then
        if [[ -f "$FRONTEND_PID_FILE" ]] && kill -0 "$(cat "$FRONTEND_PID_FILE")" 2>/dev/null; then
            echo "  Frontend: running (pid: $(cat "$FRONTEND_PID_FILE"))"
        else
            echo "  Frontend: stopped"
        fi
    else
        if [[ -f "$JCLAW_DIR/public/spa/index.html" ]]; then
            echo "  Frontend: built (served from public/spa/)"
        else
            echo "  Frontend: not built (run --deploy or nuxi generate)"
        fi
    fi
}

# ─── Logs ───

do_logs() {
    cd "$JCLAW_DIR"

    if [[ "$DEV_MODE" == true ]]; then
        local files=()
        [[ -f "logs/backend-dev.out" ]]  && files+=("logs/backend-dev.out")
        [[ -f "logs/frontend-dev.out" ]] && files+=("logs/frontend-dev.out")
        if [[ ${#files[@]} -eq 0 ]]; then
            echo "No dev log files found in $JCLAW_DIR/logs/"
            exit 1
        fi
        tail -f "${files[@]}"
    else
        if [[ ! -f "logs/application.log" ]]; then
            echo "No log file found at $JCLAW_DIR/logs/application.log"
            exit 1
        fi
        tail -f "logs/application.log"
    fi
}

# ─── Load test ───

# Drive the in-process mock-provider load test against the running backend.
# Authenticates as the admin user (credentials from application.conf), toggles
# provider.loadtest-mock.enabled for the duration of the run, POSTs
# /api/metrics/loadtest, and restores the prior enabled value on exit.
do_loadtest() {
    cd "$JCLAW_DIR"

    if [[ ! -f "conf/application.conf" ]]; then
        echo "Error: Not a JClaw directory (conf/application.conf not found)"
        exit 1
    fi

    # Verify the backend is reachable before doing anything else
    if ! curl -s -o /dev/null -w '%{http_code}' "http://localhost:$BACKEND_PORT/" | grep -q '^[23]'; then
        echo "Error: Backend is not responding on port $BACKEND_PORT."
        echo "       Start it first: $0 ${DEV_MODE:+--dev }${DEPLOY_DIR:+--deploy $DEPLOY_DIR }start"
        exit 1
    fi

    # Read admin credentials straight from application.conf — same source
    # LoadTestRunner uses for its own internal login path.
    local admin_user admin_pass
    admin_user=$(grep -E '^jclaw\.admin\.username=' conf/application.conf | head -1 | cut -d= -f2-)
    admin_pass=$(grep -E '^jclaw\.admin\.password=' conf/application.conf | head -1 | cut -d= -f2-)
    if [[ -z "$admin_user" || -z "$admin_pass" ]]; then
        echo "Error: jclaw.admin.username/password not found in conf/application.conf"
        exit 1
    fi

    cookie_jar=$(mktemp -t jclaw-loadtest-cookie.XXXXXX)
    trap 'rm -f "$cookie_jar"' EXIT

    echo "==> Authenticating as $admin_user..."
    local login_status
    login_status=$(curl -s -o /dev/null -w '%{http_code}' -c "$cookie_jar" \
        -X POST "http://localhost:$BACKEND_PORT/api/auth/login" \
        -H 'Content-Type: application/json' \
        -d "{\"username\":\"$admin_user\",\"password\":\"$admin_pass\"}")
    if [[ "$login_status" != "200" ]]; then
        echo "Error: Login failed (HTTP $login_status). Check jclaw.admin.* credentials."
        exit 1
    fi

    # --clean: delete loadtest data and exit
    if [[ "$LT_CLEAN" == true ]]; then
        echo "==> Cleaning loadtest data..."
        local clean_status
        clean_status=$(curl -s -o /dev/null -w '%{http_code}' -b "$cookie_jar" \
            -X DELETE "http://localhost:$BACKEND_PORT/api/metrics/loadtest/data")
        if [[ "$clean_status" == "200" ]]; then
            echo "==> Loadtest conversations, messages, and events deleted."
        else
            echo "Error: Cleanup failed (HTTP $clean_status)"
            exit 1
        fi
        return
    fi

    echo "==> Running load test: concurrency=$LT_CONCURRENCY iterations=$LT_ITERATIONS"
    echo "    ttft=${LT_TTFT_MS}ms tokens/s=$LT_TOKENS_PER_SECOND response=${LT_RESPONSE_TOKENS} tokens"
    echo ""

    local body
    body=$(printf '{"concurrency":%s,"iterations":%s,"ttftMs":%s,"tokensPerSecond":%s,"responseTokens":%s}' \
        "$LT_CONCURRENCY" "$LT_ITERATIONS" "$LT_TTFT_MS" "$LT_TOKENS_PER_SECOND" "$LT_RESPONSE_TOKENS")

    local response http_code
    response=$(curl -s -b "$cookie_jar" \
        -X POST "http://localhost:$BACKEND_PORT/api/metrics/loadtest" \
        -H 'Content-Type: application/json' \
        -d "$body" \
        -w '\n%{http_code}' \
        --max-time 300)
    http_code=$(echo "$response" | tail -1)
    local json
    json=$(echo "$response" | sed '$d')

    if [[ "$http_code" != "200" ]]; then
        echo "Error: Load test failed (HTTP $http_code)"
        echo "$json"
        exit 1
    fi

    # Pretty-print with python if available, otherwise raw JSON
    if command -v python3 >/dev/null 2>&1; then
        echo "$json" | python3 -m json.tool
    else
        echo "$json"
    fi

    echo ""
    echo "==> Tip: GET /api/metrics/latency for per-segment histograms"
}

# ─── Consolidated test runner ───

# Runs backend (play auto-test) and frontend (pnpm test) sequentially, streams
# each side's output, and prints a two-line summary at the end. Continues
# past a backend failure so the user sees frontend results too — the whole
# point of this subcommand is a single round-trip. Exits non-zero if either
# suite failed so CI/git hooks can depend on it.
#
# play auto-test sometimes returns 0 even when assertions fail, so we also
# scrape its log for the terminal "All tests passed" banner as a second
# confirmation before declaring backend green.
do_test() {
    cd "$SCRIPT_DIR"
    mkdir -p "$SCRIPT_DIR/logs"
    local backend_log="$SCRIPT_DIR/logs/test-backend.log"
    local frontend_log="$SCRIPT_DIR/logs/test-frontend.log"
    local backend_rc=0 frontend_rc=0
    local t0 backend_elapsed frontend_elapsed
    local backend_passed backend_failed frontend_summary

    echo "==> Running backend tests (play auto-test)..."
    t0=$SECONDS
    set +e
    play auto-test 2>&1 | tee "$backend_log"
    backend_rc=${PIPESTATUS[0]}
    set -e
    if ! grep -q "^~ All tests passed" "$backend_log" 2>/dev/null; then
        backend_rc=1
    fi
    backend_elapsed=$((SECONDS - t0))

    echo ""
    echo "==> Running frontend tests (pnpm test)..."
    t0=$SECONDS
    set +e
    (cd "$SCRIPT_DIR/frontend" && pnpm test) 2>&1 | tee "$frontend_log"
    frontend_rc=${PIPESTATUS[0]}
    set -e
    frontend_elapsed=$((SECONDS - t0))

    # Extract human-readable counts for the summary. Each grep is shielded
    # with `|| true` so a missing match under `set -e` + `pipefail` doesn't
    # tank the whole function before we get to print the verdict.
    backend_passed=$(grep -cE "PASSED " "$backend_log" 2>/dev/null || true)
    backend_failed=$(grep -cE "FAILED " "$backend_log" 2>/dev/null || true)
    frontend_summary=$(grep -E "^[[:space:]]+Tests[[:space:]]" "$frontend_log" 2>/dev/null | tail -1 | sed 's/^[[:space:]]*//' || true)
    [[ -z "$frontend_summary" ]] && frontend_summary="(summary unavailable)"

    echo ""
    echo "────────────────────────────────────────────────────────────"
    echo " jclaw test summary"
    echo "────────────────────────────────────────────────────────────"
    if [[ "$backend_rc" -eq 0 ]]; then
        printf " backend  : PASSED  (%s classes, %ss)\n" "${backend_passed:-?}" "$backend_elapsed"
    else
        printf " backend  : FAILED  (%s passed / %s failed, %ss)\n" \
            "${backend_passed:-?}" "${backend_failed:-?}" "$backend_elapsed"
        echo "            log: $backend_log"
    fi
    if [[ "$frontend_rc" -eq 0 ]]; then
        printf " frontend : PASSED  %s (%ss)\n" "$frontend_summary" "$frontend_elapsed"
    else
        printf " frontend : FAILED  %s (%ss)\n" "$frontend_summary" "$frontend_elapsed"
        echo "            log: $frontend_log"
    fi
    echo "────────────────────────────────────────────────────────────"

    if [[ "$backend_rc" -ne 0 || "$frontend_rc" -ne 0 ]]; then
        exit 1
    fi
}

# ─── Execute ───

case "$COMMAND" in
    setup)
        do_setup
        ;;
    start)
        check_prereqs
        if [[ "$DEV_MODE" == true ]]; then
            mkdir -p "$JCLAW_DIR/logs"
            do_start_dev
        else
            [[ -n "$DEPLOY_DIR" ]] && do_deploy
            mkdir -p "$JCLAW_DIR/logs"
            do_start_prod
        fi
        ;;
    stop)
        if [[ "$DEV_MODE" == true ]]; then
            do_stop_dev
        else
            do_stop_prod
        fi
        ;;
    restart)
        check_prereqs
        if [[ "$DEV_MODE" == true ]]; then
            do_stop_dev
            sleep 1
            mkdir -p "$JCLAW_DIR/logs"
            do_start_dev
        else
            do_stop_prod
            sleep 1
            [[ -n "$DEPLOY_DIR" ]] && do_deploy
            mkdir -p "$JCLAW_DIR/logs"
            do_start_prod
        fi
        ;;
    status)
        do_status
        ;;
    logs)
        do_logs
        ;;
    loadtest)
        do_loadtest
        ;;
    test)
        check_prereqs
        do_test
        ;;
esac
