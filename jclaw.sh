#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
FRONTEND_PID_FILE="frontend.pid"

usage() {
    cat <<EOF
Usage: jclaw.sh [options] <start|stop|restart|status|logs|loadtest>

Commands:
  start     Start the Play backend and Nuxt frontend
  stop      Stop the running Play backend and Nuxt frontend
  restart   Stop and start (combines stop + start)
  status    Show whether backend and frontend are running
  logs      Tail the production application log
  loadtest  Drive the in-process load-test harness against /api/chat/stream

Options:
  --dev                   Run in development mode (play run + pnpm dev)
  --deploy <dir>          Package with play dist, copy to <dir>, and run in production
  --backend-port <port>   Play backend port (default: 9000)
  --frontend-port <port>  Nuxt dev server port, dev mode only (default: 3000)

Load-test options (only used with the 'loadtest' command):
  --concurrency <n>       Parallel workers (default: 10)
  --iterations <n>        Requests per worker (default: 5)
  --ttft-ms <n>           Simulated time-to-first-token in ms (default: 100)
  --tokens-per-second <n> Simulated token throughput (default: 50)
  --response-tokens <n>   Tokens per simulated response (default: 40)

Examples:
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
    exit 1
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
        start|stop|restart|status|logs|loadtest)
            COMMAND="$1"
            shift
            ;;
        *)
            echo "Unknown argument: $1"
            usage
            ;;
    esac
done

[[ -z "$COMMAND" ]] && usage

# Validate flag combinations
if [[ "$DEV_MODE" == true && -n "$DEPLOY_DIR" ]]; then
    echo "Error: --dev and --deploy cannot be used together."
    exit 1
fi

# Verify Java 25+ is available
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

# Determine the working directory
if [[ -n "$DEPLOY_DIR" ]]; then
    JCLAW_DIR="$DEPLOY_DIR/jclaw"
elif [[ "$DEV_MODE" == true ]]; then
    JCLAW_DIR="$SCRIPT_DIR"
else
    JCLAW_DIR="$(pwd)"
fi

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

    echo "==> Resolving backend dependencies..."
    play deps --sync

    echo "==> Installing frontend dependencies..."
    cd "$JCLAW_DIR/frontend"
    pnpm install --frozen-lockfile 2>/dev/null || pnpm install

    echo "==> Generating static SPA..."
    npx nuxi generate

    echo "==> Copying SPA build to public/spa/..."
    rm -rf "$JCLAW_DIR/public/spa"
    cp -r .output/public "$JCLAW_DIR/public/spa"

    cd "$JCLAW_DIR"

    echo "==> Starting Play backend on port $BACKEND_PORT (prod)..."
    play start --%prod --http.port="$BACKEND_PORT"

    echo ""
    echo "JClaw is running (production):"
    echo "  App: http://localhost:$BACKEND_PORT  (pid: $(cat "$JCLAW_DIR/server.pid"))"
    echo ""
    echo "Stop with: $0 ${DEPLOY_DIR:+--deploy $DEPLOY_DIR }stop"
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

    # Ensure dependencies are installed
    echo "==> Checking frontend dependencies..."
    cd "$JCLAW_DIR/frontend"
    pnpm install --frozen-lockfile 2>/dev/null || pnpm install
    cd "$JCLAW_DIR"

    echo "==> Resolving backend dependencies..."
    play deps --sync

    echo "==> Starting Play backend on port $BACKEND_PORT (dev)..."
    nohup play run --http.port="$BACKEND_PORT" > "$JCLAW_DIR/logs/backend-dev.out" 2>&1 &
    local play_pid=$!
    # play run doesn't create server.pid — store the wrapper pid ourselves
    echo "$play_pid" > "$JCLAW_DIR/server.pid"

    # Wait for backend to be ready by polling the port
    echo "    Waiting for backend to start..."
    local waited=0
    while ! curl -s -o /dev/null "http://localhost:$BACKEND_PORT" 2>/dev/null && kill -0 "$play_pid" 2>/dev/null; do
        sleep 1
        waited=$((waited + 1))
        if [[ $waited -ge 60 ]]; then
            echo "Error: Backend did not start within 60 seconds."
            echo "       Check logs/backend-dev.out for details."
            kill "$play_pid" 2>/dev/null
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
    echo "Stop with: $0 --dev stop"
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

    # Stop backend (play run — we manage the pid file, not Play)
    if [[ -f "server.pid" ]]; then
        local bpid
        bpid=$(cat "server.pid")
        if kill -0 "$bpid" 2>/dev/null; then
            echo "==> Stopping Play backend (pid: $bpid)..."
            kill "$bpid" 2>/dev/null
            # Also kill any child java processes in the group
            pkill -P "$bpid" 2>/dev/null || true
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

    # Declared at script scope (not local) so the EXIT trap below can still
    # see them after do_loadtest returns.
    cookie_jar=$(mktemp -t jclaw-loadtest-cookie.XXXXXX)

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

    # Snapshot the current enabled value so we can restore it when done —
    # leaving it enabled after a one-shot CLI run is a footgun (the harness
    # binds a loopback port and registers loadtest_sleep on every boot).
    # Script-scoped for the same trap-visibility reason as cookie_jar.
    prior_enabled=$(curl -s -b "$cookie_jar" \
        "http://localhost:$BACKEND_PORT/api/config/provider.loadtest-mock.enabled" \
        | sed -nE 's/.*"value":"([^"]*)".*/\1/p')
    [[ -z "$prior_enabled" ]] && prior_enabled="false"

    restore_enabled() {
        curl -s -b "$cookie_jar" \
            -X POST "http://localhost:$BACKEND_PORT/api/config" \
            -H 'Content-Type: application/json' \
            -d "{\"key\":\"provider.loadtest-mock.enabled\",\"value\":\"$prior_enabled\"}" \
            -o /dev/null
        rm -f "$cookie_jar"
    }
    trap restore_enabled EXIT

    echo "==> Enabling provider.loadtest-mock.enabled (was: $prior_enabled)..."
    curl -s -b "$cookie_jar" \
        -X POST "http://localhost:$BACKEND_PORT/api/config" \
        -H 'Content-Type: application/json' \
        -d '{"key":"provider.loadtest-mock.enabled","value":"true"}' \
        -o /dev/null

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

# ─── Execute ───

case "$COMMAND" in
    start)
        check_java
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
        check_java
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
esac
