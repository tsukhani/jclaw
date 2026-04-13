#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
FRONTEND_PID_FILE="frontend.pid"

usage() {
    cat <<EOF
Usage: jclaw.sh [options] <start|stop|restart|status>

Commands:
  start    Start the Play backend and Nuxt frontend
  stop     Stop the running Play backend and Nuxt frontend
  restart  Stop and start (combines stop + start)
  status   Show whether backend and frontend are running
  logs     Tail the production application log

Options:
  --dev                   Run in development mode (play run + pnpm dev)
  --deploy <dir>          Package with play dist, copy to <dir>, and run in production
  --backend-port <port>   Play backend port (default: 9000)
  --frontend-port <port>  Nuxt dev server port, dev mode only (default: 3000)

Examples:
  ./jclaw.sh --dev start                              # Start in dev mode
  ./jclaw.sh --dev --backend-port 8080 start          # Dev mode with custom backend port
  ./jclaw.sh start                                    # Start production in current directory
  ./jclaw.sh --deploy /tmp start                      # Build, deploy to /tmp/jclaw, and start
  ./jclaw.sh --deploy /tmp --backend-port 8080 start  # Deploy with custom port
  ./jclaw.sh --dev stop                               # Stop dev mode services
  ./jclaw.sh --deploy /tmp stop                       # Stop services in /tmp/jclaw
  ./jclaw.sh stop                                     # Stop production in current directory
EOF
    exit 1
}

# Parse arguments
DEPLOY_DIR=""
DEV_MODE=false
BACKEND_PORT="9000"
FRONTEND_PORT="3000"
COMMAND=""

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
        start|stop|restart|status|logs)
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

    # Update nuxt devProxy if backend port is non-default
    if [[ "$BACKEND_PORT" != "9000" ]]; then
        echo "==> Updating frontend devProxy to use backend port $BACKEND_PORT..."
        sed -i '' "s|localhost:9000|localhost:$BACKEND_PORT|g" "$JCLAW_DIR/frontend/nuxt.config.ts"
    fi

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
    PORT="$FRONTEND_PORT" nohup pnpm dev > "$JCLAW_DIR/logs/frontend-dev.out" 2>&1 &
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

    # Restore nuxt.config.ts if it was modified
    if git -C "$JCLAW_DIR" diff --quiet frontend/nuxt.config.ts 2>/dev/null; then
        : # no changes to restore
    else
        echo "==> Restoring frontend/nuxt.config.ts..."
        git -C "$JCLAW_DIR" checkout frontend/nuxt.config.ts 2>/dev/null || true
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

    local log_file
    if [[ "$DEV_MODE" == true ]]; then
        log_file="logs/backend-dev.out"
    else
        log_file="logs/application.log"
    fi

    if [[ ! -f "$log_file" ]]; then
        echo "No log file found at $JCLAW_DIR/$log_file"
        exit 1
    fi

    tail -f "$log_file"
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
esac
