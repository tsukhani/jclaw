#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
FRONTEND_PID_FILE="frontend.pid"

# Audience detection: developers run from a `git clone`, end users run
# from an unzipped `play dist` tarball with no .git/ in tow. Used by
# both show_intro (bare invocation banner) and usage (--help / unknown
# arg) to render an appropriately-scoped command surface — no point
# offering `setup` to someone who can't run it. Anchored at SCRIPT_DIR
# so the classification follows where jclaw.sh lives, not where the
# user happened to cd.
is_developer_clone() {
    /usr/bin/git -C "$SCRIPT_DIR" rev-parse --is-inside-work-tree >/dev/null 2>&1
}

usage() {
    if is_developer_clone; then
        cat <<EOF
Usage: jclaw.sh [options] <cert|secret|setup|reset|start|stop|restart|status|logs|loadtest|test|help>

Commands:
  setup     One-time per-clone bootstrap: wires git hooks (.githooks/),
            installs frontend dependencies (so pre-commit's lint-staged works),
            generates certs/.env (with a fresh application secret) if missing,
            and verifies both 'origin' and 'github' remotes exist. Run once
            after every fresh clone. Idempotent — safe to re-run.
  secret    Generate (or rotate) the application secret in certs/.env (delegates
            to 'play secret', which writes the variable named in
            application.conf's \${...} placeholder). Run after a suspected
            leak or as routine hygiene. Restart the app to pick up the new
            value.
  cert      Generate a TLS PEM cert+key at ./certs/host.cert and
            ./certs/host.key for the bundled Docker compose stack.
            Uses mkcert when available (browser-trusted; Chrome accepts
            HTTP/3); falls back to a self-signed openssl pair otherwise.
            Run before 'docker compose restart' to swap in a fresh cert.
  reset     Clear the admin password hash from the Config DB so the next
            launch routes through the in-app /setup-password flow. Use
            when the operator has forgotten the password and is locked
            out of the running instance. Safe to run while the app is
            up (db.url has AUTO_SERVER=TRUE).
  start     Start the Play backend and Nuxt frontend
  stop      Stop the running Play backend and Nuxt frontend
  restart   Stop and start (combines stop + start)
  status    Show whether backend and frontend are running
  logs      Tail the production application log
  loadtest  Drive the in-process load-test harness against /api/chat/stream
  test      Run backend (play auto-test) + frontend (pnpm test) and report a
            consolidated pass/fail summary. Exits non-zero on any failure.
  help      Print this usage reference and exit. Equivalent to --help / -h.

Options:
  --dev                   Run in development mode (play run + pnpm dev)
  --deploy <dir>          Package with play dist, copy to <dir>, and run in production
  --backend-port <port>   Play backend port (default: 9000)
  --frontend-port <port>  Nuxt dev server port, dev mode only (default: 3000)

Environment:
  JCLAW_JVM_HEAP          Symmetric heap override — sets both -Xms and -Xmx to
                          the same value. Default is asymmetric (Xms 512m, Xmx
                          2g) to avoid committing 2 GB at boot on idle deploys;
                          ZGC handles resize without pauses, so a fixed heap
                          isn't required for latency.
                          Example: JCLAW_JVM_HEAP=4g ./jclaw.sh start
  JCLAW_JVM_XMS           Override -Xms only (default: 512m).
  JCLAW_JVM_XMX           Override -Xmx only (default: 2g).
  JCLAW_JVM_OPTS          Extra JVM flags appended after the built-in set.
                          Last-wins for value flags (e.g. MaxDirectMemorySize),
                          so this lets you override most hardcoded settings.
                          Example: JCLAW_JVM_OPTS='-XX:MaxDirectMemorySize=512m'

Load-test options (only used with the 'loadtest' command):
  --concurrency <n>       Parallel workers (default: 10)
  --iterations <n>        Requests per worker (default: 5)
  --ttft-ms <n>           Simulated time-to-first-token in ms (default: 100)
  --tokens-per-second <n> Simulated token throughput (default: 50)
  --response-tokens <n>   Tokens per simulated response (default: 40)
  --clean                 Delete loadtest conversations/events from DB instead of running a test
  --compress              Send 'Accept-Encoding: br, gzip' on each loadtest request so the
                          server's HttpContentCompressor engages — measures the cost of the
                          encoding path. Default off (Java HttpClient sends no Accept-Encoding,
                          so compression doesn't engage even when wired into the pipeline).
  --real                  Bind the loadtest agent to a real provider instead of the
                          in-process mock harness. Required to capture real-network/SSE
                          timing (mock latency is deterministic stubs). Default
                          provider is ollama-local; override with --provider.
  --provider <name>       Real-provider name when --real is set (default: ollama-local).
                          Any registered provider works: ollama-cloud, openrouter, openai, etc.
                          The provider must already be configured (apiKey/baseUrl set).
  --model <name>          Model to drive when --real is set (default: gemma4:latest).
                          Must be pullable/serveable by the chosen provider.

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
    else
        # User-facing reference: trimmed to the runtime commands and
        # operator knobs that actually apply to a dist install. Setup,
        # test, --dev, --deploy, --frontend-port, and the loadtest
        # options are all developer-only and would either fail or
        # silently no-op against an unzipped distribution, so they're
        # omitted entirely. loadtest itself technically works against a
        # running prod backend, but it's an operator/dev tool and not
        # part of the "I just want to run JClaw" contract.
        cat <<EOF
Usage: jclaw.sh [options] <cert|reset|start|stop|restart|status|logs|help>

Commands:
  cert      Generate a TLS PEM cert+key at ./certs/host.cert and
            ./certs/host.key for the bundled Docker compose stack.
            Uses mkcert when available (browser-trusted; Chrome accepts
            HTTP/3); falls back to a self-signed openssl pair otherwise.
            Run before 'docker compose restart' to swap in a fresh cert.
  reset     Clear the admin password hash from the Config DB so the next
            launch routes through the in-app /setup-password flow. Use
            when you've forgotten the password and are locked out of
            the running instance.
  start     Start JClaw (Play backend serving the bundled SPA)
  stop      Stop the running instance
  restart   Stop and start (combines stop + start)
  status    Show whether the backend is running
  logs      Tail the application log
  help      Print this usage reference and exit. Equivalent to --help / -h.

Options:
  --backend-port <port>   Backend HTTP port (default: 9000)

Environment:
  JCLAW_JVM_HEAP          Symmetric heap override — sets both -Xms and -Xmx
                          to the same value. Default is asymmetric (Xms 512m,
                          Xmx 2g): JClaw commits ~512 MB at boot and grows
                          to 2 GB on demand. ZGC resizes without pauses.
                          Example: JCLAW_JVM_HEAP=4g ./jclaw.sh start
  JCLAW_JVM_XMS           Override -Xms only (default: 512m).
  JCLAW_JVM_XMX           Override -Xmx only (default: 2g).
  JCLAW_JVM_OPTS          Extra JVM flags appended after the built-in set.
                          Last-wins for value flags (e.g. MaxDirectMemorySize),
                          so this lets you override most hardcoded settings.
                          Example: JCLAW_JVM_OPTS='-XX:MaxDirectMemorySize=512m'

Examples:
  ./jclaw.sh start                              # Start on default port 9000
  ./jclaw.sh --backend-port 8080 start          # Start on a custom port
  ./jclaw.sh status                             # Check whether it's running
  ./jclaw.sh logs                               # Tail the application log
  ./jclaw.sh stop                               # Stop the running instance
  JCLAW_JVM_HEAP=4g ./jclaw.sh start            # Start with a 4 GB heap
EOF
    fi
}

# True if the argument is a recognized subcommand. Used by the help-routing
# logic so that `./jclaw.sh help <cmd>` falls back to the top-level banner
# when <cmd> is unknown rather than blowing up — and so the parser can
# distinguish the per-command help path from the bare-help path.
is_known_command() {
    case "$1" in
        cert|secret|setup|reset|start|stop|restart|status|logs|loadtest|test)
            return 0
            ;;
        *)
            return 1
            ;;
    esac
}

# Dispatch per-command help. Caller has already verified the argument
# names a real subcommand via is_known_command, so the wildcard arm is
# only reachable through programmer error and hands back the full banner.
usage_for() {
    case "$1" in
        cert)     usage_cert     ;;
        secret)   usage_secret   ;;
        setup)    usage_setup    ;;
        reset)    usage_reset    ;;
        start)    usage_start    ;;
        stop)     usage_stop     ;;
        restart)  usage_restart  ;;
        status)   usage_status   ;;
        logs)     usage_logs     ;;
        loadtest) usage_loadtest ;;
        test)     usage_test     ;;
        *)        usage          ;;
    esac
}

usage_cert() {
    cat <<EOF
Usage: jclaw.sh cert

Generate a TLS PEM cert+key at ./certs/host.cert and ./certs/host.key,
overwriting any existing pair. Used by the bundled docker-compose.yml —
the JClaw container picks the new cert up from the ./certs bind-mount
on its next start. Run before 'docker compose restart' when you want
HTTP/2 and HTTP/3 to negotiate cleanly in a real browser without the
self-signed-certificate interstitial.

Cert source preference:
  1. mkcert if installed. Produces a cert signed by the local CA that
     'mkcert -install' added to the system trust store, so Chrome's
     QUIC stack will negotiate HTTP/3 in the browser without warnings.
     Install once with: brew install mkcert && mkcert -install.
  2. openssl as a fallback. Produces a self-signed cert; serves
     correctly but browsers warn and Chrome refuses to upgrade to
     HTTP/3 (its QUIC stack only honors trusted certs).

Errors out if neither tool is on PATH.
EOF
}

usage_secret() {
    cat <<EOF
Usage: jclaw.sh secret

Generate or rotate the application secret. Delegates to 'play secret',
which writes the variable named in conf/application.conf's \${...}
placeholder (default: PLAY_SECRET) into a per-clone certs/.env file. The
file is created with mode 600 on first run and preserved across rotations
(only the secret line is rewritten).

Restart the app to pick up the new value.

Examples:
  ./jclaw.sh secret              # Generate or rotate
  ./jclaw.sh restart             # Pick up the new value
EOF
}

usage_setup() {
    if is_developer_clone; then
        cat <<EOF
Usage: jclaw.sh setup

One-time per-clone bootstrap (developer flow). Wires git hooks
(.githooks/), installs frontend dependencies so pre-commit's
lint-staged is available, generates certs/.env with a fresh
application secret if missing, and verifies both 'origin' and
'github' remotes are configured. Idempotent — safe to re-run after
every fresh clone.

Example:
  ./jclaw.sh setup
EOF
    else
        cat <<EOF
Usage: jclaw.sh setup

Not available in this distribution. The 'setup' command is part of
the developer flow (run after a fresh git clone — wires git hooks,
installs frontend deps, etc.). End-user 'play dist' installs don't
need it: start the app directly with ./jclaw.sh start.

For the full list of commands in this distribution: ./jclaw.sh help
EOF
    fi
}

usage_reset() {
    cat <<EOF
Usage: jclaw.sh reset

Clear the admin password hash from the Config DB so the next launch
routes through the in-app /setup-password flow. Use when the operator
has forgotten the password and is locked out of the running instance.
Safe to run while the app is up — db.url has AUTO_SERVER=TRUE so a
second JDBC connection joins the running H2 instance without
contention.

Prompts for confirmation before touching the DB. Set JCLAW_RESET_YES=1
to skip the prompt for scripted use.

Environment:
  JCLAW_RESET_YES=1       Skip the y/N confirmation prompt.

Examples:
  ./jclaw.sh reset                       # Interactive
  JCLAW_RESET_YES=1 ./jclaw.sh reset     # No prompt
EOF
}

usage_start() {
    if is_developer_clone; then
        cat <<EOF
Usage: jclaw.sh [options] start

Start JClaw in production mode (Play backend serving the bundled SPA),
or with --dev start the Play dev backend plus the Nuxt frontend on
separate ports. First run on a fresh checkout auto-generates certs/.env
with a random application secret if one isn't already present.

Options:
  --dev                   Run in development mode (play run + pnpm dev)
  --deploy <dir>          Package with play dist, copy to <dir>, and run prod from there
  --backend-port <port>   Play backend port (default: 9000)
  --frontend-port <port>  Nuxt dev server port, dev mode only (default: 3000)

Environment:
  JCLAW_JVM_HEAP          Symmetric heap — sets both -Xms and -Xmx to the
                          same value. Default is asymmetric (Xms 512m, Xmx 2g).
  JCLAW_JVM_XMS / XMX     Override -Xms / -Xmx independently
                          (defaults: 512m / 2g).
  JCLAW_JVM_OPTS          Extra JVM flags appended last (last-wins for value
                          flags, e.g. -XX:MaxDirectMemorySize=512m).

Examples:
  ./jclaw.sh start                              # Production in current directory
  ./jclaw.sh --dev start                        # Dev mode
  ./jclaw.sh --dev --backend-port 8080 start    # Dev with custom backend port
  ./jclaw.sh --deploy /tmp start                # Build, copy to /tmp/jclaw, run
  JCLAW_JVM_HEAP=4g ./jclaw.sh start            # 4 GB heap
EOF
    else
        cat <<EOF
Usage: jclaw.sh [options] start

Start JClaw on the bundled Play backend (which serves the SPA from
this distribution package). First run auto-generates certs/.env with
a random application secret if one isn't already present.

Options:
  --backend-port <port>   Backend HTTP port (default: 9000)

Environment:
  JCLAW_JVM_HEAP          Symmetric heap — sets both -Xms and -Xmx to the
                          same value. Default is asymmetric (Xms 512m, Xmx 2g).
  JCLAW_JVM_XMS / XMX     Override -Xms / -Xmx independently
                          (defaults: 512m / 2g).
  JCLAW_JVM_OPTS          Extra JVM flags appended last (last-wins).

Examples:
  ./jclaw.sh start                              # Default port 9000
  ./jclaw.sh --backend-port 8080 start          # Custom port
  JCLAW_JVM_HEAP=4g ./jclaw.sh start            # 4 GB heap
EOF
    fi
}

usage_stop() {
    if is_developer_clone; then
        cat <<EOF
Usage: jclaw.sh [options] stop

Stop the running JClaw instance. Reads the PID file the matching
start path wrote; in --dev mode also stops the Nuxt frontend.

Options:
  --dev                   Stop dev-mode services
  --deploy <dir>          Stop services running from <dir>

Examples:
  ./jclaw.sh stop                # Stop production in current directory
  ./jclaw.sh --dev stop          # Stop dev mode
  ./jclaw.sh --deploy /tmp stop  # Stop services in /tmp/jclaw
EOF
    else
        cat <<EOF
Usage: jclaw.sh stop

Stop the running JClaw instance. Reads the PID file written by
the matching start path.

Example:
  ./jclaw.sh stop
EOF
    fi
}

usage_restart() {
    if is_developer_clone; then
        cat <<EOF
Usage: jclaw.sh [options] restart

Stop and start as one operation. Accepts the same flags as 'start'.

Options:
  --dev                   Restart in dev mode
  --deploy <dir>          Re-deploy from <dir> and restart
  --backend-port <port>   Play backend port (default: 9000)
  --frontend-port <port>  Nuxt dev server port, dev mode only (default: 3000)

Environment:
  JCLAW_JVM_HEAP, JCLAW_JVM_XMS, JCLAW_JVM_XMX, JCLAW_JVM_OPTS — see 'start --help'.

Examples:
  ./jclaw.sh restart
  ./jclaw.sh --dev restart
EOF
    else
        cat <<EOF
Usage: jclaw.sh [options] restart

Stop and start as one operation. Accepts the same flags as 'start'.

Options:
  --backend-port <port>   Backend HTTP port (default: 9000)

Environment:
  JCLAW_JVM_HEAP, JCLAW_JVM_XMS, JCLAW_JVM_XMX, JCLAW_JVM_OPTS — see 'start --help'.

Example:
  ./jclaw.sh restart
EOF
    fi
}

usage_status() {
    if is_developer_clone; then
        cat <<EOF
Usage: jclaw.sh [options] status

Show whether the Play backend (and, in --dev, the Nuxt frontend) is
running. Reports PID and port when up; "not running" otherwise.

Options:
  --dev                   Check dev-mode services

Example:
  ./jclaw.sh status
EOF
    else
        cat <<EOF
Usage: jclaw.sh status

Show whether the backend is running. Reports PID and port when up;
"not running" otherwise.

Example:
  ./jclaw.sh status
EOF
    fi
}

usage_logs() {
    if is_developer_clone; then
        cat <<EOF
Usage: jclaw.sh [options] logs

Tail the production application log (logs/application.log) — equivalent
to 'tail -f' on that file. Ctrl+C to exit.

Options:
  --dev                   Tail the dev-mode backend log instead

Example:
  ./jclaw.sh logs
EOF
    else
        cat <<EOF
Usage: jclaw.sh logs

Tail the application log (logs/application.log) — equivalent to
'tail -f' on that file. Ctrl+C to exit.

Example:
  ./jclaw.sh logs
EOF
    fi
}

usage_loadtest() {
    if is_developer_clone; then
        cat <<EOF
Usage: jclaw.sh [options] loadtest

Drive the in-process load-test harness against /api/chat/stream. The
harness simulates LLM streaming with controllable TTFT and throughput
so you can measure serving overhead, queueing, and latency percentiles
without spinning up a real upstream.

Options:
  --concurrency <n>       Parallel workers (default: 10)
  --iterations <n>        Requests per worker (default: 5)
  --ttft-ms <n>           Simulated time-to-first-token in ms (default: 100)
  --tokens-per-second <n> Simulated token throughput (default: 50)
  --response-tokens <n>   Tokens per simulated response (default: 40)
  --backend-port <port>   Target port (default: 9000)
  --clean                 Delete loadtest conversations/events from the DB
                          instead of running a test
  --compress              Send 'Accept-Encoding: br, gzip' so the server's
                          HttpContentCompressor engages — measures the cost
                          of the encoding path.
  --real                  Drive a real provider with --model instead of the
                          mock harness. Lets the run capture real network
                          and SSE timing. The mock path is fine for pipeline
                          checks but its latency is stubbed, so latency
                          comparisons on the mock aren't meaningful. Default
                          provider is ollama-local.
  --provider <name>       Real-provider name when --real is set
                          (default: ollama-local). Any registered provider:
                          ollama-cloud, openrouter, openai, anthropic-via-
                          openrouter, etc. The provider must already be
                          configured (apiKey/baseUrl set in Settings).
  --model <name>          Model when --real is set (default: gemma4:latest).
                          Must be pullable/serveable by the chosen provider.

Examples:
  ./jclaw.sh loadtest                                                              # mock harness
  ./jclaw.sh --concurrency 50 --iterations 20 loadtest                             # heavier mock run
  ./jclaw.sh --real loadtest                                                       # ollama-local
  ./jclaw.sh --real --provider ollama-cloud --model kimi-k2.5 loadtest             # cloud
  ./jclaw.sh --real --provider openrouter --model google/gemini-3-flash-preview loadtest  # alt cloud
  ./jclaw.sh --clean loadtest                                                      # cleanup only
EOF
    else
        cat <<EOF
Usage: jclaw.sh loadtest

Not available in this distribution. The 'loadtest' command is a
developer/operator tool that exercises /api/chat/stream with a
synthetic LLM stream — it lives in the dev workflow, not the
end-user runtime.

For the full list of commands in this distribution: ./jclaw.sh help
EOF
    fi
}

usage_test() {
    if is_developer_clone; then
        cat <<EOF
Usage: jclaw.sh test

Run the backend (play auto-test) + frontend (pnpm test) suites and
print a consolidated pass/fail summary. Logs at logs/test-backend.log
and logs/test-frontend.log. Exits non-zero if either suite fails, so
it's safe to wire into git hooks or CI.

Example:
  ./jclaw.sh test
EOF
    else
        cat <<EOF
Usage: jclaw.sh test

Not available in this distribution. The 'test' command runs the
backend + frontend suites against a developer checkout (it needs the
test/ directory and the frontend Vitest project, neither of which
ship with 'play dist' tarballs).

For the full list of commands in this distribution: ./jclaw.sh help
EOF
    fi
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

${emerald}  ▟█▙  ▟█▙        ██╗ ██████╗██╗      █████╗ ██╗    ██╗
  ███  ███        ██║██╔════╝██║     ██╔══██╗██║    ██║
   █▜▙▟▛█         ██║██║     ██║     ███████║██║ █╗ ██║
   ███████   ██   ██║██║     ██║     ██╔══██║██║███╗██║
    █████    ╚█████╔╝╚██████╗███████╗██║  ██║╚███╔███╔╝
     ███      ╚════╝  ╚═════╝╚══════╝╚═╝  ╚═╝ ╚══╝╚══╝ ${reset}

${dim}Java-first AI automation platform — Play 1.x backend, Nuxt 3 SPA,
LLM agents, OCR, web tools.${reset}

EOF

    # Audience split: a developer working from a `git clone` needs setup +
    # the full command surface; a user running an unzipped distribution
    # just needs to start/stop the app. Detection lives in
    # is_developer_clone — see the comment block above usage().
    if is_developer_clone; then
        # Developer view: cloned repo
        cat <<EOF
  ${cyan}./jclaw.sh setup${reset}     One-time setup for a fresh clone
                       (validates prereqs, wires git hooks, installs deps,
                        adds github remote)
  ${cyan}./jclaw.sh help${reset}      Full command reference

EOF

        # First-run hint footer (developer-only — users have no setup to
        # run, and the hooksPath signal doesn't apply outside a git work
        # tree). When core.hooksPath != .githooks, append a single
        # highlighted line nudging the user at the setup command. Once
        # setup runs, the hint disappears on its own. `|| true` keeps
        # the substitution safe under `set -e` if config lookup fails.
        local hooks_path
        hooks_path=$(/usr/bin/git -C "$SCRIPT_DIR" config --local core.hooksPath 2>/dev/null || true)
        if [[ "$hooks_path" != ".githooks" ]]; then
            echo "${yellow}${bold}→ Setup hasn't run on this clone yet. Run ${cyan}./jclaw.sh setup${yellow} to wire things up.${reset}"
            echo ""
        fi
    else
        # User view: unzipped distribution, no git context
        cat <<EOF
  ${cyan}./jclaw.sh start${reset}     Start JClaw (backend on :9000)
  ${cyan}./jclaw.sh stop${reset}      Stop the running instance
  ${cyan}./jclaw.sh status${reset}    Show whether the backend is running
  ${cyan}./jclaw.sh logs${reset}      Tail the application log
  ${cyan}./jclaw.sh help${reset}      Full command reference

EOF
    fi
}

# Parse arguments
DEPLOY_DIR=""
DEV_MODE=false
BACKEND_PORT="9000"
FRONTEND_PORT="3000"
COMMAND=""
LT_CONCURRENCY="10"
LT_ITERATIONS="5"
LT_TTFT_MS="100"
LT_TOKENS_PER_SECOND="50"
LT_RESPONSE_TOKENS="40"
LT_CLEAN=false
LT_COMPRESS=false
LT_REAL=false
LT_PROVIDER=""
LT_MODEL="gemma4:latest"

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
        --compress)
            LT_COMPRESS=true
            shift
            ;;
        --real)
            LT_REAL=true
            shift
            ;;
        --provider)
            LT_PROVIDER="$2"
            shift 2
            ;;
        --model)
            LT_MODEL="$2"
            shift 2
            ;;
        cert|secret|reset|start|stop|restart|status|logs)
            COMMAND="$1"
            shift
            ;;
        setup|loadtest|test)
            # Developer-only commands. Available on a `git clone` because
            # they touch repo state (hooks, fixtures, frontend deps); not
            # available on a `play dist` install where there's no .git
            # and the frontend ships pre-built. The end-user banner
            # already advertises a narrower subcommand list — this gate
            # makes the runtime parse match that advertisement, so a
            # mistyped `./jclaw.sh setup` on a dist fails with a clear
            # "developer-only" message instead of silently running into
            # check_prereqs / git mid-execution.
            if ! is_developer_clone; then
                echo "Error: '$1' is a developer-only command, not available in this distribution."
                echo "       This install supports: cert, secret, reset, start, stop, restart, status, logs, help."
                exit 1
            fi
            COMMAND="$1"
            shift
            ;;
        --help|-h)
            # Contextual when a subcommand has been parsed, top-level
            # otherwise — matches `git <cmd> --help` and friends.
            if [[ -n "$COMMAND" ]]; then
                usage_for "$COMMAND"
            else
                usage
            fi
            exit 0
            ;;
        help)
            # Bare `help` → top-level. `help <cmd>` → per-command. The
            # post-COMMAND form (`<cmd> help`) is intentionally NOT a
            # help signal — it falls through to the unknown-argument arm
            # so operators get steered toward the supported
            # `<cmd> --help` shape (per clig.dev / git / kubectl).
            if [[ -n "$COMMAND" ]]; then
                echo "Unknown argument: help"
                usage
                exit 1
            fi
            shift
            if [[ $# -gt 0 ]] && is_known_command "$1"; then
                usage_for "$1"
            else
                usage
            fi
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

# Resolve the env-var name that backs `application.secret` in conf.
# Mirrors framework/pym/play/utils.py:secretVarName so jclaw.sh and
# `play secret` always agree on which variable to read/write — the
# operator can rename `${APP_SECRET}` to anything in conf and this
# helper picks it up automatically. Falls back to the framework's
# DEFAULT_SECRET_VAR ("PLAY_SECRET") when conf is missing or the line
# uses an unparseable form, matching the framework's behaviour.
secret_var_name() {
    local conf="$JCLAW_DIR/conf/application.conf"
    if [[ ! -f "$conf" ]]; then
        echo "PLAY_SECRET"
        return
    fi
    # Match: application.secret=${VARNAME} (skip lines starting with # or !).
    # The framework's regex anchors on a single ${...} placeholder; anything
    # else (literal, missing braces, : default-value form) falls back below.
    local name
    name=$(grep -vE '^[[:space:]]*[#!]' "$conf" \
        | grep -E '^[[:space:]]*application\.secret[[:space:]]*=[[:space:]]*\$\{[^}:]+\}[[:space:]]*$' \
        | head -n1 \
        | sed -E 's/^[[:space:]]*application\.secret[[:space:]]*=[[:space:]]*\$\{([^}:]+)\}[[:space:]]*$/\1/')
    echo "${name:-PLAY_SECRET}"
}

# Source $JCLAW_DIR/certs/.env into the current shell with auto-export so
# the JVM started by `play` inherits the variables. Called from start
# paths (dev + prod). Silent no-op when certs/.env is absent — that path
# is reserved for failure handling at the validate step, not here.
load_env_file() {
    local env_file="$JCLAW_DIR/certs/.env"
    if [[ -f "$env_file" ]]; then
        set -a
        # shellcheck disable=SC1090
        source "$env_file"
        set +a
    fi
}

# First-run helper for the start paths: if certs/.env is absent AND the
# conf-named secret variable is unset in the parent shell, generate one.
# This is what makes `./jclaw.sh start` work on a fresh jclaw.zip install
# with no developer setup step. We DON'T auto-create when the operator
# has already exported the variable externally — that'd overwrite their
# intent with a stored random value they didn't ask for. We DON'T
# auto-create from setup either; setup has its own explicit gate.
ensure_env_for_start() {
    local env_file="$JCLAW_DIR/certs/.env"
    local var_name
    var_name=$(secret_var_name)
    # Bash indirect expansion: `${!var_name:-}` reads the variable whose
    # NAME is held in $var_name. Equivalent to `${APP_SECRET:-}` when
    # var_name="APP_SECRET", but follows whatever the conf line dictates.
    if [[ ! -f "$env_file" && -z "${!var_name:-}" ]]; then
        echo "==> First run detected — no certs/.env and no $var_name in env."
        do_secret
    fi
}

# Hard-fail if the conf-named secret variable ends up unset by the time
# we're about to launch the JVM. application.conf has no dev fallback
# (intentional — the previous in-repo secret was an admin-session-forgery
# primitive), so an unresolved placeholder would yield an empty signing
# key. We detect early to give a clean diagnostic instead of a Play
# startup stacktrace from CookieSessionStore.
require_application_secret() {
    local var_name
    var_name=$(secret_var_name)
    if [[ -z "${!var_name:-}" ]]; then
        # Reachable only when certs/.env exists but lacks (or empties) the
        # key, OR when the launcher set the env var to an empty string.
        # The ensure_env_for_start guard upstream creates certs/.env on
        # first run, so this is operator-misconfiguration territory.
        echo "Error: $var_name resolved to empty after sourcing certs/.env."
        echo "       Check that certs/.env contains a non-empty value:"
        echo "         $var_name=<some-64-char-string>"
        echo "       Rotate or regenerate with: $0 secret"
        exit 1
    fi
}

# Generate or rotate the application secret in $JCLAW_DIR/certs/.env.
# Delegates the actual generation + write to `play secret`, which since
# PF-71 defaults to certs/.env and auto-detects the env-var name from
# conf/application.conf's ${VARNAME} placeholder — so renaming the
# variable in conf flows through here without touching this script.
# The framework's writer preserves any other lines in the file;
# rotation rewrites only the secret line.
do_cert() {
    # Writes to $JCLAW_DIR/certs/, NOT conf/. The Docker compose stack
    # bind-mounts ./certs into the container; the dev-mode `play
    # enable-https` flow handles conf/host.cert separately. Two
    # destinations, two tools, no shared state — keeps the cert
    # provenance unambiguous in each context.
    local certs_dir="$JCLAW_DIR/certs"
    local cert_file="$certs_dir/host.cert"
    local key_file="$certs_dir/host.key"

    mkdir -p "$certs_dir"

    if command -v mkcert >/dev/null 2>&1; then
        mkcert -cert-file "$cert_file" -key-file "$key_file" \
               localhost 127.0.0.1 ::1 >/dev/null
        echo "Generated mkcert-signed PEM cert+key at $certs_dir."
        echo "(Trusted by the system store after 'mkcert -install' — Chrome will accept HTTP/3.)"
    elif command -v openssl >/dev/null 2>&1; then
        # 3650 days mirrors the play1 fork's enable-https default. CN=localhost
        # plus SANs for IPv4 + IPv6 loopback covers what browsers actually
        # validate; modern Chrome rejects certs that lack a SAN even when CN
        # matches, so the SAN is non-optional.
        openssl req -x509 -newkey rsa:2048 -nodes \
            -keyout "$key_file" -out "$cert_file" \
            -days 3650 -subj "/CN=localhost" \
            -addext "subjectAltName=DNS:localhost,IP:127.0.0.1,IP:0:0:0:0:0:0:0:1" \
            >/dev/null 2>&1
        echo "Generated self-signed PEM cert+key at $certs_dir (openssl fallback)."
        echo "Hint: install mkcert (https://github.com/FiloSottile/mkcert) for browser-trusted local-dev TLS."
    else
        echo "ERROR: neither mkcert nor openssl found on PATH. Install one and re-run." >&2
        return 1
    fi

    chmod 600 "$key_file"
    chmod 644 "$cert_file"

    cat <<EOF

If a JClaw Docker container is running, apply the new cert with:
  docker compose restart
EOF
}

do_secret() {
    local env_file="$JCLAW_DIR/certs/.env"

    mkdir -p "$JCLAW_DIR/certs"

    # Seed a brand-new certs/.env with our self-documenting header BEFORE
    # invoking `play secret`. The framework writer preserves existing
    # lines and only appends/replaces the secret variable, so dropping a
    # header here keeps those comments intact across future rotations.
    # umask 077 ensures the file is owner-only readable from creation,
    # before the secret lands.
    if [[ ! -f "$env_file" ]]; then
        (
            umask 077
            printf '%s\n' \
                "# Per-clone environment overrides sourced by jclaw.sh." \
                "# Generated by '$0 secret' or '$0 setup' — do not commit." \
                > "$env_file"
        )
    fi

    # `play secret` reads ${VARNAME} from application.conf's
    # `application.secret=${VARNAME}` line, generates a fresh 64-char
    # alphanumeric value, and writes <VARNAME>=<value> to certs/.env
    # (PF-71 default). Run from $JCLAW_DIR so it locates the right conf.
    (cd "$JCLAW_DIR" && play secret)

    # `play secret` doesn't tighten perms, so re-apply chmod 600 — the
    # secret is the admin-session-forgery primitive; non-owner reads
    # would defeat the point of having it in certs/.env at all.
    chmod 600 "$env_file"

    # DEV_MODE is "true"/"false" string — `${VAR:+...}` would print on
    # both because the string is always non-empty. Compare to "true"
    # explicitly to get the intended boolean behavior. DEPLOY_DIR is
    # empty by default so the existing `${VAR:+...}` form does the right
    # thing for that flag.
    local dev_flag=""
    [[ "$DEV_MODE" == true ]] && dev_flag="--dev "
    echo "    Restart the app to pick up the new value:"
    echo "      $0 ${dev_flag}${DEPLOY_DIR:+--deploy $DEPLOY_DIR }restart"
}

# Locate the H2 jar shipped with the Play framework, falling back to a
# bundled copy in the dist's lib/. Used by do_reset to invoke the H2
# Shell tool standalone — no JVM-side classpath assembly needed.
locate_h2_jar() {
    local jar
    # Dist tarball layout: every dependency including h2 sits in lib/.
    jar=$(ls "$JCLAW_DIR"/lib/h2-*.jar 2>/dev/null | head -1)
    if [[ -n "$jar" ]]; then
        echo "$jar"
        return 0
    fi
    # Developer layout: framework/lib/ inside the play install. Resolve
    # the play CLI's real path (jenv shims aren't symlinks, but realpath
    # on a non-symlink is a no-op so it's safe to call unconditionally).
    if command -v play >/dev/null 2>&1; then
        local play_real play_home
        play_real=$(python3 -c "import os, shutil; print(os.path.realpath(shutil.which('play')))" 2>/dev/null || true)
        if [[ -n "$play_real" ]]; then
            play_home=$(dirname "$play_real")
            jar=$(ls "$play_home"/framework/lib/h2-*.jar 2>/dev/null | head -1)
            if [[ -n "$jar" ]]; then
                echo "$jar"
                return 0
            fi
        fi
    fi
    return 1
}

# Clear the admin password hash from the Config DB so the next launch
# routes through the in-app /setup-password flow. The recovery path for
# an operator who's locked themselves out — the in-app
# POST /api/auth/reset-password endpoint requires being signed in, which
# is impossible when you've forgotten the password.
#
# Safe to run while the app is up because db.url is configured with
# AUTO_SERVER=TRUE — H2 promotes the file lock to a TCP server on first
# connect, so a second process (this one) can join the same database
# without contention. Idempotent: re-running on a fresh install where
# no password has ever been set succeeds with 0 rows deleted.
do_reset() {
    local data_file="$JCLAW_DIR/data/jclaw.mv.db"
    if [[ ! -f "$data_file" ]]; then
        echo "Error: No database found at $data_file."
        echo "       Nothing to reset — the app hasn't been started yet,"
        echo "       so there's no password hash to clear. Just start the"
        echo "       app and use the in-app /setup-password flow."
        exit 1
    fi

    check_java

    local h2_jar
    h2_jar=$(locate_h2_jar) || {
        echo "Error: Could not locate H2 jar. Looked in:"
        echo "  - $JCLAW_DIR/lib/h2-*.jar  (dist layout)"
        echo "  - <play-home>/framework/lib/h2-*.jar  (developer layout)"
        echo "       Without the H2 driver this script can't talk to the DB."
        exit 1
    }

    # Confirmation gate. The reset wipes the credentials that gate
    # access to the running instance — anyone who reaches the post-reset
    # /setup-password page can claim the admin role. Make the operator
    # acknowledge that surface before we touch the DB. JCLAW_RESET_YES=1
    # in the environment skips the prompt for scripted use.
    if [[ "${JCLAW_RESET_YES:-}" != "1" ]]; then
        echo "About to clear the admin password hash from $data_file."
        echo "After this, the next visit to the app will land on the"
        echo "/setup-password page and the first arriver claims the"
        echo "admin role."
        read -r -p "Proceed? [y/N] " reply
        case "$reply" in
            y|Y|yes|YES) ;;
            *) echo "Aborted."; exit 0 ;;
        esac
    fi

    # AUTO_SERVER=TRUE matches application.conf's db.url so a running
    # app's file lock doesn't block us. MODE=MYSQL is irrelevant for a
    # DELETE but kept for parity with the canonical URL — H2 caches
    # connection-time options per file, and a mismatch would cost a
    # short reconnect dance.
    #
    # No -user / -password flags: the app is configured with neither
    # db.user nor db.pass set in conf, so Play opens the database with
    # null credentials. Subsequent connects MUST use the same null
    # pattern — passing -user sa -password "" lands a 28000 invalid-
    # auth error against the very database we created.
    local jdbc_url="jdbc:h2:file:$JCLAW_DIR/data/jclaw;MODE=MYSQL;AUTO_SERVER=TRUE"
    local sql="DELETE FROM config WHERE config_key='auth.admin.passwordHash';"

    echo "==> Clearing auth.admin.passwordHash..."
    if ! java -cp "$h2_jar" org.h2.tools.Shell -url "$jdbc_url" -sql "$sql"; then
        echo "Error: H2 Shell command failed. Inspect the output above."
        exit 1
    fi

    echo "==> Done. Open the app and use /setup-password to set a new password."
    echo "    (No restart needed — ApiAuthController reads the hash on every login.)"
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
    echo "==> Generating certs/.env (per-clone secret store)..."
    # Skip if certs/.env already exists — the operator may have populated
    # it with prod-tuned values. Rotate explicitly via `$0 secret` instead.
    if [[ -f "$JCLAW_DIR/certs/.env" ]]; then
        echo "    certs/.env already exists — leaving it untouched."
        echo "    Rotate with: $0 secret"
    else
        do_secret
    fi

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

    # Refuse to start when our own instance is already running. Auto-stop
    # was the original behavior but it surprised users — they expected
    # `start` to be safe to run on a healthy instance and at worst
    # report "already up", not silently kill the JVM, drain its
    # connections, and rebuild on top. Mirror the dev path: error
    # cleanly and point at stop/restart, leaving the choice deliberate.
    if [[ -f "server.pid" ]] && kill -0 "$(cat server.pid)" 2>/dev/null; then
        echo "Error: JClaw is already running (pid: $(cat server.pid))."
        echo "       Run '$0 ${DEPLOY_DIR:+--deploy $DEPLOY_DIR }stop' to stop it,"
        echo "       or '$0 ${DEPLOY_DIR:+--deploy $DEPLOY_DIR }restart' to restart in place."
        exit 1
    fi

    # Refuse to start if the port is held by anything (a foreign process from
    # a different deploy dir, or a prior instance still inside its shutdown
    # hooks). The pid-file check above only catches OUR own server.pid; a
    # JVM running from /tmp/JClaw/ — say, a pre-existing prod deploy — has
    # its own pid file there and is invisible to us. Without this guard,
    # Play tries to bind, fails with "Could not bind on port 9000", aborts
    # startup → ShutdownJob fires → JPA work in the shutdown sequence
    # produces a giant Hibernate trace that buries the real one-line error.
    #
    # -sTCP:LISTEN is load-bearing: a plain `lsof -ti :PORT` matches any
    # socket on the port, including client-side CLOSE_WAITs (e.g. a Chrome
    # tab that was talking to a now-dead JVM). Only a LISTENing socket
    # blocks bind(), so filtering by state avoids false positives.
    if lsof -ti :"$BACKEND_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
        local holder
        holder=$(lsof -ti :"$BACKEND_PORT" 2>/dev/null | tr '\n' ' ')
        echo "Error: Port $BACKEND_PORT is already in use (pid: ${holder% })."
        echo "       Run '$0 ${DEPLOY_DIR:+--deploy $DEPLOY_DIR }stop' first, or kill the holder."
        exit 1
    fi

    # First-run guard for jclaw.zip distributions: if no certs/.env and
    # the conf-named secret variable isn't already set in the parent
    # shell, generate one on the fly so end-users who skip the developer-
    # only `setup` command don't get blocked. Then source certs/.env into
    # the JVM env and validate.
    ensure_env_for_start
    load_env_file
    require_application_secret

    echo "==> Resolving backend dependencies..."
    play deps --sync

    # Wipe tmp/ on every start. It contains things that go stale across
    # restarts but that the selective-precompile check below can't see:
    #   - tmp/bytecode/<MODE>/   enhanced bytecode cache (BytecodeCache.java);
    #                            keyed by class hash, but a wipe also clears
    #                            entries for classes that were renamed/deleted.
    #   - tmp/classes/           dev-mode JIT compile output; orphan .class
    #                            files from refactors live here.
    #   - tmp/uploads/           Apache MultipartParser staging; should be
    #                            empty between requests, but a crashed app
    #                            can leave half-written upload temp files.
    # Cheap (≤10 MB typical) and the prod path doesn't read tmp/ at all
    # (it loads precompiled/), so wiping is purely defensive here. The
    # selective-precompile guard below still skips the compile step when
    # sources are unchanged.
    rm -rf tmp

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
    #   - Asymmetric heap by default (-Xms 512m, -Xmx 2g): the steady-state
    #     working set fits in ~512 MB, so committing the full 2 GB at boot
    #     would waste resident memory on idle deployments. ZGC handles
    #     heap resizing without stop-the-world pauses, so the
    #     resize-under-load argument that motivates fixed heaps in G1/CMS
    #     doesn't apply. To force a fixed heap (the previous default),
    #     set JCLAW_JVM_HEAP=2g — that pins -Xms == -Xmx == 2g. To split
    #     them independently, use JCLAW_JVM_XMS / JCLAW_JVM_XMX.
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
    #
    # JCLAW_JVM_OPTS is appended last; the JVM uses last-wins for value
    # flags, so a user can override e.g. MaxDirectMemorySize without
    # editing the script. Boolean GC flags (UseZGC vs UseG1GC) conflict
    # rather than override — switching collectors still requires editing
    # the array below.
    # Resolution order (highest priority first):
    #   1. JCLAW_JVM_XMS / JCLAW_JVM_XMX — explicit per-flag override.
    #   2. JCLAW_JVM_HEAP — symmetric override (sets both flags to same value).
    #   3. Asymmetric default — Xms 512m, Xmx 2g.
    # The nested ${var:-${other:-default}} expansion encodes that order in one line.
    local heap="${JCLAW_JVM_HEAP:-}"
    local xms="${JCLAW_JVM_XMS:-${heap:-512m}}"
    local xmx="${JCLAW_JVM_XMX:-${heap:-2g}}"
    local jvm_opts=(
        "-Xms${xms}"
        "-Xmx${xmx}"
        "-XX:+UseZGC"
        "-XX:+HeapDumpOnOutOfMemoryError"
        "-XX:HeapDumpPath=$JCLAW_DIR/logs/heap-oom.hprof"
        "-XX:+ExitOnOutOfMemoryError"
        "-XX:MaxDirectMemorySize=256m"
        "-Dnetworkaddress.cache.ttl=30"
        "-Dnetworkaddress.cache.negative.ttl=0"
        "-Xlog:gc*:file=$JCLAW_DIR/logs/gc.log:time,uptime,level,tags:filecount=5,filesize=10M"
    )

    # User-supplied extras go last so last-wins semantics let them override
    # value flags (e.g. -XX:MaxDirectMemorySize=512m). Word-splitting on the
    # env var is intentional: it lets the operator pass multiple flags.
    if [[ -n "${JCLAW_JVM_OPTS:-}" ]]; then
        # shellcheck disable=SC2206
        local extra_opts=( ${JCLAW_JVM_OPTS} )
        jvm_opts+=( "${extra_opts[@]}" )
    fi

    echo "==> Starting Play backend on port $BACKEND_PORT (prod)..."
    if [[ "$xms" == "$xmx" ]]; then
        echo "    JVM: ${xms} heap (fixed), ZGC, GC log → logs/gc.log"
    else
        echo "    JVM: -Xms${xms} -Xmx${xmx}, ZGC, GC log → logs/gc.log"
    fi
    [[ -n "${JCLAW_JVM_OPTS:-}" ]] && echo "    Extra JVM opts: ${JCLAW_JVM_OPTS}"
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

    if [[ ! -f "server.pid" ]]; then
        echo "Nothing to stop — JClaw does not appear to be running in $JCLAW_DIR"
        return
    fi

    local pid
    pid=$(cat server.pid 2>/dev/null)
    if [[ -z "$pid" ]]; then
        echo "Warning: server.pid is empty — cannot wait for JVM exit."
        play stop
        return
    fi
    echo "==> Stopping Play backend (pid: $pid)..."
    play stop

    # JCLAW-190: `play stop` (framework/pym/play/commands/daemon.py:84-86)
    # signals the JVM with SIGTERM and immediately removes server.pid —
    # it does NOT wait for the JVM to actually exit. The shutdown hook
    # (ShutdownJob + Play plugins + Hikari close) keeps running for up
    # to Play's 30s scheduler-shutdown budget. If we return now, a
    # follow-up `play start` boots a new JVM that overlaps the old one
    # — both polling Telegram with the same bot token, 409 Conflict on
    # the new JVM's first getUpdates.
    #
    # Polling on `[[ -f server.pid ]]` is therefore wrong (the file is
    # gone within milliseconds of `play stop`). The only reliable
    # liveness signal is `kill -0 $pid` — sends signal 0, which probes
    # the process without delivering a signal.
    #
    # Bound the wait at 60s so a wedged JVM doesn't hang the shell
    # forever; at that point an operator can investigate manually.
    local elapsed_ds=0
    local max_ds=120   # 60 seconds, polling every 0.5s = 120 deciseconds
    while [[ $elapsed_ds -lt $max_ds ]]; do
        if ! kill -0 "$pid" 2>/dev/null; then
            break
        fi
        sleep 0.5
        elapsed_ds=$((elapsed_ds + 1))
    done

    if kill -0 "$pid" 2>/dev/null; then
        echo "Warning: pid $pid still alive after $((max_ds / 2))s — JVM may still be shutting down."
        echo "         If a follow-up start fails, check 'ps -p $pid' and decide whether to kill -9."
    else
        # Clean up a stray pid file if Play left one behind (force-kill, crash, etc.)
        [[ -f server.pid ]] && rm -f server.pid
        echo ""
        echo "JClaw stopped."
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
    #
    # -sTCP:LISTEN is load-bearing: a plain `lsof -ti :PORT` matches any
    # socket on the port, including client-side CLOSE_WAITs (e.g. a Chrome
    # tab that was talking to a now-dead JVM). Only a LISTENing socket
    # blocks bind(), so filtering by state avoids false positives.
    if lsof -ti :"$BACKEND_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
        local holder
        holder=$(lsof -ti :"$BACKEND_PORT" 2>/dev/null | tr '\n' ' ')
        echo "Error: Port $BACKEND_PORT is already in use (pid: ${holder% })."
        echo "       Run '$0 --dev stop' first, or kill the holder."
        exit 1
    fi

    # Source certs/.env (the conf-named secret variable, plus any other
    # overrides) into the JVM environment. See the prod-mode counterpart
    # for the rationale.
    ensure_env_for_start
    load_env_file
    require_application_secret

    # Ensure dependencies are installed
    validate_corepack_pnpm

    echo "==> Checking frontend dependencies..."
    cd "$JCLAW_DIR/frontend"
    pnpm install --frozen-lockfile 2>/dev/null || pnpm install
    cd "$JCLAW_DIR"

    echo "==> Resolving backend dependencies..."
    play deps --sync

    # Wipe tmp/ on every start. See the prod-path counterpart for the full
    # rationale — same staleness concerns apply here. In dev the trade-off
    # is that the first request after restart re-enhances every accessed
    # class (a few seconds of latency on the first hit), which is the cost
    # of a guaranteed-clean enhancer cache and bytecode store.
    rm -rf tmp

    echo "==> Starting Play backend on port $BACKEND_PORT (dev)..."
    nohup play run --http.port="$BACKEND_PORT" > "$JCLAW_DIR/logs/backend-dev.out" 2>&1 &
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
# Auth: sends the application secret in the X-Loadtest-Auth header; the
# matching server-side guard on /api/metrics/loadtest checks both the header
# AND that the request comes from a loopback address. The secret lives in
# certs/.env (gitignored) under whatever variable name application.conf's
# `application.secret=${VARNAME}` declares, and is the same value Play uses
# to sign session cookies — reusing it here avoids introducing a separate
# operator-managed credential. JCLAW-181: the previous flow read
# jclaw.admin.password from application.conf and POSTed /api/auth/login,
# but commit caf9422 moved the admin password to a PBKDF2 hash in the
# Config DB, leaving the script with no plaintext to log in with.
do_loadtest() {
    cd "$JCLAW_DIR"

    if [[ ! -f "conf/application.conf" ]]; then
        echo "Error: Not a JClaw directory (conf/application.conf not found)"
        exit 1
    fi

    # Source certs/.env so the secret variable is available, then resolve
    # its name the same way the start paths do (read from conf — operator-
    # renameable).
    load_env_file
    local var_name secret
    var_name=$(secret_var_name)
    secret=${!var_name:-}
    if [[ -z "$secret" ]]; then
        echo "Error: $var_name is not set."
        echo "       Loadtest auth uses $var_name (the same secret"
        echo "       Play signs session cookies with), sent in the"
        echo "       X-Loadtest-Auth header. It must be present in"
        echo "       $JCLAW_DIR/certs/.env or exported in the parent shell."
        echo "       Generate or rotate via: $0 secret"
        exit 1
    fi

    # Verify the backend is reachable before doing anything else
    if ! curl -s -o /dev/null -w '%{http_code}' "http://localhost:$BACKEND_PORT/" | grep -q '^[23]'; then
        echo "Error: Backend is not responding on port $BACKEND_PORT."
        echo "       Start it first: $0 ${DEV_MODE:+--dev }${DEPLOY_DIR:+--deploy $DEPLOY_DIR }start"
        exit 1
    fi

    # --clean: delete loadtest data and exit
    if [[ "$LT_CLEAN" == true ]]; then
        echo "==> Cleaning loadtest data..."
        local clean_status
        clean_status=$(curl -s -o /dev/null -w '%{http_code}' \
            -H "X-Loadtest-Auth: $secret" \
            -X DELETE "http://localhost:$BACKEND_PORT/api/metrics/loadtest/data")
        if [[ "$clean_status" == "200" ]]; then
            echo "==> Loadtest conversations, messages, and events deleted."
        else
            echo "Error: Cleanup failed (HTTP $clean_status)"
            exit 1
        fi
        return
    fi

    local lt_extra=""
    if [[ "$LT_REAL" == true ]]; then
        local lt_real_provider="${LT_PROVIDER:-ollama-local}"
        lt_extra=" real=$lt_real_provider model=$LT_MODEL"
    fi
    echo "==> Running load test: concurrency=$LT_CONCURRENCY iterations=$LT_ITERATIONS$lt_extra"
    echo "    ttft=${LT_TTFT_MS}ms tokens/s=$LT_TOKENS_PER_SECOND response=${LT_RESPONSE_TOKENS} tokens compress=$LT_COMPRESS"
    echo ""

    # Build the JSON body. Include real / provider / model only when set so
    # the default mock-provider path stays bit-identical to the pre-JCLAW-186
    # wire format. JSON-quote $LT_MODEL because Ollama tags carry a colon
    # (`gemma4:latest`) which would otherwise look like a JSON struct.
    local body
    body=$(printf '{"concurrency":%s,"iterations":%s,"ttftMs":%s,"tokensPerSecond":%s,"responseTokens":%s,"compress":%s' \
        "$LT_CONCURRENCY" "$LT_ITERATIONS" "$LT_TTFT_MS" "$LT_TOKENS_PER_SECOND" "$LT_RESPONSE_TOKENS" "$LT_COMPRESS")
    if [[ "$LT_REAL" == true ]]; then
        body="$body,\"real\":true,\"model\":\"$LT_MODEL\""
        if [[ -n "$LT_PROVIDER" ]]; then
            body="$body,\"provider\":\"$LT_PROVIDER\""
        fi
    fi
    body="$body}"

    # The okhttp/real path can take much longer than the default mock run
    # (each turn waits on a real model), so raise the curl wall-clock cap
    # accordingly. Default 300s stays for the existing mock path.
    local lt_max_time=300
    if [[ "$LT_REAL" == true ]]; then lt_max_time=1800; fi

    local response http_code
    response=$(curl -s \
        -H "X-Loadtest-Auth: $secret" \
        -H 'Content-Type: application/json' \
        -X POST "http://localhost:$BACKEND_PORT/api/metrics/loadtest" \
        -d "$body" \
        -w '\n%{http_code}' \
        --max-time "$lt_max_time")
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
    cert)
        do_cert
        ;;
    secret)
        do_secret
        ;;
    setup)
        do_setup
        ;;
    reset)
        do_reset
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
            # JCLAW-190: do_stop_prod now waits for server.pid removal
            # before returning, so we don't need a separate sleep here.
            # The new JVM only boots once the old one has fully exited.
            do_stop_prod
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
