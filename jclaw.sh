#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
FRONTEND_PID_FILE="frontend.pid"

usage() {
    cat <<EOF
Usage: jclaw.sh [--deploy <dir>] [--backend-port <port>] [--frontend-port <port>] <start|stop|status>

Commands:
  start    Start the Play backend and Nuxt frontend in production mode
  stop     Stop the running Play backend and Nuxt frontend
  status   Show whether backend and frontend are running

Options:
  --deploy <dir>          Package with play dist, copy to <dir>, and run from there
  --backend-port <port>   Play backend port (default: 9000)
  --frontend-port <port>  Nuxt frontend port (default: 3000)

Examples:
  ./jclaw.sh start                          # Start in current directory
  ./jclaw.sh --deploy /tmp start            # Build, deploy to /tmp/jclaw, and start
  ./jclaw.sh --backend-port 8080 start      # Start backend on port 8080
  ./jclaw.sh --deploy /tmp stop             # Stop services in /tmp/jclaw
  ./jclaw.sh stop                           # Stop services in current directory
EOF
    exit 1
}

# Parse arguments
DEPLOY_DIR=""
BACKEND_PORT="9000"
FRONTEND_PORT="3000"
COMMAND=""

while [[ $# -gt 0 ]]; do
    case "$1" in
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
        start|stop|status)
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
else
    JCLAW_DIR="$(pwd)"
fi

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

    echo "==> Installing frontend dependencies..."
    cd "$JCLAW_DIR/frontend"
    pnpm install --frozen-lockfile 2>/dev/null || pnpm install

    echo "==> Building frontend..."
    pnpm build

    echo "==> Resolving backend dependencies..."
    cd "$JCLAW_DIR"
    play deps --sync

    echo "==> Deployment ready at $JCLAW_DIR"
}

do_start() {
    cd "$JCLAW_DIR"

    if [[ ! -f "conf/application.conf" ]]; then
        echo "Error: Not a JClaw directory (conf/application.conf not found)"
        echo "       Run from the jclaw directory or use --deploy <dir>"
        exit 1
    fi

    # Check if already running
    if [[ -f "server.pid" ]] && kill -0 "$(cat server.pid)" 2>/dev/null; then
        echo "Error: Play backend is already running (pid: $(cat server.pid))"
        exit 1
    fi

    echo "==> Starting Play backend on port $BACKEND_PORT..."
    play start --%prod --http.port="$BACKEND_PORT"

    echo "==> Starting Nuxt frontend on port $FRONTEND_PORT..."
    cd "$JCLAW_DIR/frontend"

    if [[ ! -f ".output/server/index.mjs" ]]; then
        echo "Error: Frontend not built. Run 'cd frontend && pnpm build' first."
        exit 1
    fi

    PORT="$FRONTEND_PORT" nohup node .output/server/index.mjs > "$JCLAW_DIR/logs/frontend.out" 2>&1 &
    echo $! > "$JCLAW_DIR/$FRONTEND_PID_FILE"

    echo ""
    echo "JClaw is running:"
    echo "  Backend:  http://localhost:$BACKEND_PORT  (pid: $(cat "$JCLAW_DIR/server.pid"))"
    echo "  Frontend: http://localhost:$FRONTEND_PORT  (pid: $(cat "$JCLAW_DIR/$FRONTEND_PID_FILE"))"
    echo ""
    echo "Stop with: $0 ${DEPLOY_DIR:+--deploy $DEPLOY_DIR }stop"
}

do_stop() {
    cd "$JCLAW_DIR"

    local stopped=0

    # Stop frontend
    if [[ -f "$FRONTEND_PID_FILE" ]]; then
        local fpid
        fpid=$(cat "$FRONTEND_PID_FILE")
        if kill -0 "$fpid" 2>/dev/null; then
            echo "==> Stopping Nuxt frontend (pid: $fpid)..."
            kill "$fpid"
            rm -f "$FRONTEND_PID_FILE"
            stopped=1
        else
            echo "    Frontend not running (stale pid file)"
            rm -f "$FRONTEND_PID_FILE"
        fi
    else
        echo "    No frontend pid file found"
    fi

    # Stop backend
    if [[ -f "server.pid" ]]; then
        echo "==> Stopping Play backend..."
        play stop
        stopped=1
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

do_status() {
    cd "$JCLAW_DIR"

    echo "JClaw status ($JCLAW_DIR):"
    echo ""

    # Backend
    if [[ -f "server.pid" ]] && kill -0 "$(cat server.pid)" 2>/dev/null; then
        echo "  Backend:  running (pid: $(cat server.pid))"
    else
        echo "  Backend:  stopped"
    fi

    # Frontend
    if [[ -f "$FRONTEND_PID_FILE" ]] && kill -0 "$(cat "$FRONTEND_PID_FILE")" 2>/dev/null; then
        echo "  Frontend: running (pid: $(cat "$FRONTEND_PID_FILE"))"
    else
        echo "  Frontend: stopped"
    fi
}

# Execute
case "$COMMAND" in
    start)
        check_java
        [[ -n "$DEPLOY_DIR" ]] && do_deploy
        # Ensure logs directory exists
        mkdir -p "$JCLAW_DIR/logs"
        do_start
        ;;
    stop)
        do_stop
        ;;
    status)
        do_status
        ;;
esac
